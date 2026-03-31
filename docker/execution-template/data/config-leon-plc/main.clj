(ns main
  (:require
   [constants :as C]
   [env.constants :as EC]
   [plc-controler :as PC]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [clojure.core.match :refer [match]]
   [clojure.core.cache :as cache]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]
   ;[clj-fuzzy.metrics :refer [jaro]]
   [caudal.streams.common :refer [defsink deflistener wire]]
   [caudal.io.rest-server :refer [web]]
   [caudal.streams.stateful :refer [reduce-with changed]]
   [caudal.streams.stateless :refer [by pprinte where split smap time-stampit ->INFO ->WARN ->ERROR reinject]]
   [caudal.io.telegram :refer [send-photo send-text]]
   ;[caudal.io.email :refer [mailer email-event-with-body-fn]]
   [cheshire.core :refer [parse-string]]
   [send-events :as SE])
  (:import
   (java.util Random UUID)
   (java.util Base64)
   (java.net InetAddress)))

(log/info "Starting streamer")
(log/info (pr-str {:CAUDAL_HOME C/CAUDAL_HOME}))
(log/info (pr-str {:CAUDAL_CONFIG C/CAUDAL_CONFIG}))

(let [qa_url (io/file (str C/CAUDAL_CONFIG "/QA_URL.edn"))
      conf (if (.exists qa_url) (-> qa_url slurp edn/read-string) "NO HAY QA_URL.edn")]
  (log/info (.getCanonicalPath qa_url) (.exists qa_url))
  (log/info (pr-str conf)))

(def face-treshold 1.0)
(def delta-resend 3000)

(defn make-refreshing-cache [max-size ttl-ms]
  (atom (-> {}
            (cache/lru-cache-factory :threshold max-size)
            (cache/ttl-cache-factory :ttl ttl-ms))))

(defn put-value! [c k]
  (swap! c cache/miss k k))

(defn get-value! [c k]
  (let [v (cache/lookup @c k)]
    (when v
      ;; Reinserta el valor con nueva marca de tiempo
      (swap! c cache/miss k v))
    v))

(def value-lru (make-refreshing-cache 1000 (* 1000 60 10))) ; cache de valores por 10 min

(defn create-uuid [] (str (UUID/randomUUID)))

(defn decode [string]
  (.decode (Base64/getDecoder) string))

(defn read-event [e]
  (if-let [json (:json e)]
    (parse-string json true)
    (if-let [evt (:body-params e)]
      evt
      (if-let [body (:body e)]
        (let [e (parse-string (slurp (io/reader body)) true)]
          e)))))

#_(def origin (-> (InetAddress/getLocalHost)
                  (.getHostName)))

#_(def plantId "Irapuato")

(def last-mail (atom 0))
(def last-mail-plate (atom 0))
(def time-diff 60000) ; un minuto (no está usandose por el momento)


(defn e-counter [{:keys [n last] :or {n 0 last -1}} e]
  (when EC/WITH-E-COUNTER
    (let [now (mod (System/currentTimeMillis) 10000)]
      (if (>= now last)
        {:n (inc n) :last now}
        (let [rt (Runtime/getRuntime)
              nf (java.text.NumberFormat/getNumberInstance)
              maxM (.maxMemory rt)
              freeM (.freeMemory rt)
              totM (.totalMemory rt)
              usedM (- totM freeM)
              doGC false ;(< freeM (* totM 0.50))
              info [:max (.format nf maxM) :tot (.format nf totM) :free (.format nf  freeM) :used (.format nf usedM) :GC doGC]]
          (if doGC (.gc rt))
          (log/info (str "evts/s: " (/ n 10.0) " --> " info))
          {:n 1 :last now})))))

(defn write-event [file-name event]
  (with-open [out (io/writer file-name :append true)]
    (.write out (str (pr-str event) "\n"))))

(defn set-defaults [defaults event]
  (merge defaults event))


(defn base64TOimg [key evt]
  (decode (key evt)))

(defn histeresis
  "Primer parametro vector con nivel off y nivel on (low hight)
  Segundo parametro vector con: estado actual se usa como trully o falsey on/off
  y el nivel actual (int), el tercer
  parametro es la función inc o dec, el valor de retorno es
  un vector con una tupla con mismo valor de entrada, nil o nuevo current,
  el current se es true o el valor de retorno de la funcion generadora
  estado del segundo parametro"
  ([[off-level on-level] [current level] fun]
   (histeresis (fn [] true) [off-level on-level] [current level] fun))
  ([trully-gen-f [off-level on-level] [current level] fun]
   (let [level (fun level)
         level (cond
                 (< level off-level) (dec off-level)
                 (> level on-level) (inc on-level)
                 :else level)
         new-current (cond
                       (and (not current) (> level on-level)) (trully-gen-f)
                       (and current (< level off-level)) nil
                       :else current)]
     [[new-current level] (not= new-current current)])))

(defn zone-histeresis [{:keys [in-zone-cnt full?] :or {in-zone-cnt 0 full? false}}
                       {:keys [persons-in-zone] :as e}]
  (let [ok-time? (> (- (System/currentTimeMillis) @last-mail) time-diff)
        fun (if (seq persons-in-zone) inc dec)
        [[full? in-zone-cnt] action?] (histeresis [0 3] [full? in-zone-cnt] fun)
        send? (and action? full? ok-time?)]
    (when send?
      (reset! last-mail (System/currentTimeMillis)))
    (assoc e :in-zone-cnt in-zone-cnt :full? full? :send? send?)))

(defn plate-event2html [{:keys [image good-image clip d-plates]}]
  (let [[vision-plate vision-score] d-plates
        ai-score-txt (cond (and (> vision-score 92) (> (count vision-plate) 5)) "Muy confiable  (*****)"
                           (and (> vision-score 89) (> (count vision-plate) 5)) "Confiable      (***)"
                           (> vision-score 85) "Poco confiable (**)"
                           :else               "Placa da&ntilde;ada o iluminaci&oacute;n   (*)")]
    (cond-> [{:type "text/html"
              :content (str "<h3>Acceso a planta</h3>" "<p>datos: <ul>" (format "<li>Placa leida: '%s' -- %s</li></p><p style='font-size: x-small'>%s" (str vision-plate) ai-score-txt vision-score))}]
      image (conj {:type :inline
                   :content (let [tmp (java.io.File/createTempFile "image" ".png")]
                              (with-open [in (java.io.ByteArrayInputStream. (decode (or good-image image)))
                                          out (io/output-stream tmp)]
                                (io/copy in out))
                              tmp)
                   :content-type "image/png"})
      clip (conj {:type :inline
                  :content (let [tmp (java.io.File/createTempFile "image" ".png")]
                             (with-open [in (java.io.ByteArrayInputStream. (decode clip))
                                         out (io/output-stream tmp)]
                               (io/copy in out))
                             tmp)
                  :content-type "image/png"}))))

(let [out (io/file "plates.log")]
  (println "*******  " (.getCanonicalPath out)))

(defn log-plates [{:keys [plate plates best-plate last-best-plate]}]
  (let [out (io/file "plates.log")]
    (when (.exists out)
      (with-open [outw (io/writer out :append true)]
        (.write outw (str (pr-str [plate plates best-plate last-best-plate]) "\n"))))))

(defn max-with-nil [val1 val2]
  (max (or val1 0) (or val2 0)))

(defn take-best [plates-in-seq plates]
  (let [plates (vec (filter identity (concat plates-in-seq plates)))]
    (into
     []
     (reduce
      (fn [selected [plate score]]
        (update selected plate (partial max-with-nil score)))
      {}
      plates))))

(defn plate-reducer [{:keys [clazz plates-in-seq image-1 objects-1 clip-1 prev-plate] :or {plates-in-seq [] prev-plate "F:undefined"}}
                     {:keys [camera plate plates image objects clip] :as evt}]
  ;(log/info (str "plate: " (pr-str plate) " z-face: " (pr-str prev-plate)))  
  (if (= "vehicular" clazz)
    (let [re #"(B|F):([0-9A-Za-z_]+)"
          [_ BoF d_plate] (re-find re plate)
          [_ BoFprev d_plateprev] (re-find re prev-plate)
          evt (assoc evt :prev-plate plate)]
      (if (and (= BoF "B") (= BoFprev "F") (= d_plate d_plateprev)) ;(= plate "F:undefined")
        (cond-> evt
          (seq plates-in-seq) (assoc :selected-plates plates-in-seq :plates-in-seq [])
          image-1 (assoc :good-image image-1)
          objects-1 (assoc :good-objects objects-1)
          clip-1 (assoc :good-clip clip-1)
          image-1 (dissoc :image-1)
          objects-1 (dissoc :objects-1)
          clip-1 (dissoc :clip-1))
        (let [good-image (or image-1 (and (seq plates) (seq (first plates)) image))
              good-objects (or objects-1 objects)
              good-clip (or clip-1 (and (seq plates) (seq (first plates)) clip))]
          (assoc evt :plates-in-seq (take-best plates-in-seq plates) :image-1 good-image :objects-1 good-objects :clip-1 good-clip))))
    (assoc evt :plates-in-seq plates-in-seq :image-1 image-1 :objects-1 objects-1 :clip-1 clip-1 :prev-plate prev-plate)))


(defn score2text [plate score]
  (cond (and (> score 92) (> (count plate) 5)) "Muy confiable  (*****)"
        (and (> score 89) (> (count plate) 5)) "Confiable      (***)"
        (> score 85)                           "Poco confiable (**)"
        :else                                  "Placa dañada o falta de iluminación (*)"))

(defn get-now-ts []
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss") (System/currentTimeMillis)))

(defn get-now-ts []
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss") (System/currentTimeMillis)))

(defn create-plate-msg [{:keys [the-best-plate the-best-plate-score]}]
  (let [ai-score-txt (score2text the-best-plate the-best-plate-score)]
    (format "PRO: %s\n[%s] %s"  (get-now-ts) the-best-plate ai-score-txt)))

(defn create-events2send-spot [{:keys [camera uuid prev-uuid] :as evt}]
  (let [now-ts (System/currentTimeMillis)
        [e-name e-value] (if uuid [:ON_SPOT_ENTER :ENTER] [:ON_SPOT_EXIT :EXIT])
        relevantes [{:eventName e-name
                     :value e-value
                     :aiTime now-ts
                     :uuid (or uuid prev-uuid)
                     :type    :VEHICLE
                     :camera  camera
                     :origin  C/origin
                     :plantId C/plantId}]]
    (assoc evt :send relevantes)))


(defn create-events2send [{:keys [alias place camera
                                  the-best-plate the-best-plate-score
                                  the-best-plate2 the-best-plate2-score
                                  good-image good-clip uuid ai_ts0 ai_ts1 ai_ts
                                  good-objects scale] :as evt}]
  (let [now-ts (System/currentTimeMillis)
        ;now (.format (java.text.SimpleDateFormat. ("yyyy-MM-dd HH:mm:ss")) now-ts)
        ;msg (score2text the-best-plate the-best-plate-score)
        relevantes [{:eventName :ON_CHECKPOINT
                     :aiTime now-ts
                     :ai_ts0 ai_ts0
                     :ai_ts1 ai_ts1
                     :ai_ts ai_ts
                     :value the-best-plate  ; ABC123  145413(id persona)
                     :accuracy the-best-plate-score ; 0.9344
                     :value2 the-best-plate2
                     :accuracy2 the-best-plate2-score
                     ;:interpretation msg    ; Muy confiable *****    "Super sugro"
                     :type    :VEHICLE              ; "plate" "face" "container"
                     :image   good-image
                     :objects good-objects
                     :scale   scale
                     :clip    good-clip
                     :camera  camera
                     :uuid    (or uuid (create-uuid))
                     :origin  C/origin
                     :plantId C/plantId}]]
    (cond-> evt
      (not= the-best-plate "no_plate") (assoc :send relevantes))))


(defn select-the-best [{:keys [selected-plates] :as evt}]
  (let [[[the-best-plate the-best-plate-score]
         [the-best-plate2 the-best-plate2-score]] (->> selected-plates
                                                       (map (fn [[p s]] [p (* s (if (> (count p) 5) 1 0.8))]))
                                                       (sort-by second >)
                                                       (take 2))]
    (assoc evt
           :the-best-plate (or the-best-plate "unknown")
           :the-best-plate-score (or the-best-plate-score 0)
           :the-best-plate2 (or the-best-plate2 "")
           :the-best-plate2-score (or the-best-plate2-score 0))))


(defn F->B [prev current]
  (and (= "F" prev) (= "B" current)))

(defn face-qr-tx-mgr [{:keys [face-qr-uuid z-face z-qr] :or {z-face "F:undefined" z-qr "F:undefined"}}
                      {:keys [face QR uuid camera clazz]  :as e}]
  (let [e  (assoc e  :z-face (or face z-face) :z-qr (or QR z-qr))]
    (if uuid
      e ; alguien ya le puso uuid (motos y vehiculos)
      (let [re #"(B|F):([0-9A-Za-z\-]+|.*=$)"
            [_ BoF _] (re-find re (or face QR))
            [_ BoFprev _] (re-find re (if face z-face  z-qr))
            other (if face z-qr z-face)]
        (cond
          (and (= "F:undefined" (or face QR)) (= "F:undefined" other))
          e

          (and (= "F:undefined" (or face QR)) (not= "F:undefined" other))
          (assoc e :face-qr-uuid face-qr-uuid :uuid face-qr-uuid)

          (and (nil? face-qr-uuid) (F->B BoFprev BoF))
          (let [d-uuid (create-uuid)]                     ; le pones uuid fresco
            (assoc e :face-qr-uuid d-uuid :uuid d-uuid))

          :else
          (assoc e :face-qr-uuid face-qr-uuid :uuid face-qr-uuid))))))

; en esta version no nos importe al B: o F: solo vamos a desduplicar los face diferentes y reportar cada que sea diferente y que no sea undefined o unknown
(defn fast-face-reducer [{:keys [z-face z-ts] :or {z-face "undefined" z-ts (System/currentTimeMillis)}}
                         {:keys [face image objects clip score similarity camera uuid]
                          :or {score 0 similarity 0 face ""} :as evt}]
  (let [re #"(B|F):([0-9A-Za-z\-]+)"
        [_ BoF d_face] (re-find re face)
        [_ _ zz-face] (re-find re z-face)
        is-uuid (re-matches #"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}" (.toLowerCase (or d_face "nil")))
        ts (System/currentTimeMillis)
        new-z-ts (if (not= d_face zz-face) ts z-ts)] ; 84AEFEDD-CBD4-4307-A285-8F2BEC562A21

    ; cambio el face solo validar si el nuevo es "undefined" o "unknown" si es un uuid se manda !!
    (assoc evt :z-image image :z-objects objects :z-clip clip :z-face face :z-score score :z-similarity similarity :z-ts new-z-ts :face-sequence-ended (and is-uuid (or (not= d_face zz-face)
                                                                                                                                                                        (> (- ts z-ts) delta-resend))))))

(defn qr-reducer [{:keys [old-qr old-qr-cnt] :or {old-qr-cnt 0}} 
                  {:keys [QR uuid camera] :as e}]
  (let [d-qr (subs QR 2)
        old-qr (if (= old-qr-cnt 0) "" old-qr)
        old-qr-cnt (mod (inc old-qr-cnt) 10)
        e (assoc e :old-qr-cnt old-qr-cnt)
        uuid (or uuid (create-uuid))
        e (assoc e :uuid uuid)]
    (log/debug (pr-str [:qr-reducer camera old-qr d-qr (= old-qr d-qr) (and uuid (not (#{"NONE" "ERROR" "undefined"} d-qr)))]))
    (cond
      (= old-qr d-qr) (assoc e :old-qr d-qr)

      (and uuid (not (#{"NONE" "ERROR" "undefined"} d-qr))) (assoc e :old-qr d-qr :send-qr d-qr)

      :else (assoc e :old-qr d-qr))))

(defn create-face-events2send [use-cache? {:keys [alias place uuid camera
                                                  z-face z-image z-clip z-similarity clazz
                                                  ai_ts0 ai_ts1 ai_ts
                                                  z-objects scale] :as evt}]
  (let [now-ts (System/currentTimeMillis)
        ;now (.format (java.text.SimpleDateFormat. ("yyyy-MM-dd HH:mm:ss")) now-ts)
        ;msg (score2text the-best-plate the-best-plate-score)
        re #"(B|F):([0-9A-Za-z\-]+)"
        [_ BoF d-face] (re-find re z-face)
        relevantes [{:eventName :ON_CHECKPOINT
                     :aiTime now-ts
                     :ai_ts0 ai_ts0
                     :ai_ts1 ai_ts1
                     :ai_ts ai_ts
                     :value d-face  ; ABC123  145413(id persona)
                     :accuracy z-similarity
                     :type    (if (= clazz "peatonal") :FACE :OFFICE)
                     :image   z-image
                     :objects z-objects
                     :scale scale
                     :clip    z-clip
                     :camera  camera
                     :uuid    (or uuid (create-uuid))
                     :origin  C/origin
                     :plantId C/plantId}]
        should-send? (if use-cache?
                       (let [cache-key (str camera "-" d-face)
                             cached-value (get-value! value-lru cache-key)]
                         (if (nil? cached-value)
                           (do
                             (put-value! value-lru cache-key)
                             true)
                           (do
                             (log/info (pr-str ["TAB Se descarta repetido " camera d-face]))
                             false)))
                       true)]
    (when (< z-similarity face-treshold) (log/warn (pr-str ["TAB Se descarta relevante " camera z-similarity d-face])))
    (cond-> evt
      (and should-send? (>= z-similarity face-treshold)) (assoc :send relevantes))))

(defn create-qr-events2send [{:keys [alias place uuid camera send-qr
                                     image clip ai_ts0 ai_ts1 ai_ts
                                     objects scale] :as evt}]
  (log/debug (pr-str [:qr-reducer :create-qr-events2send camera send-qr uuid]))
  (let [now-ts (System/currentTimeMillis)
        relevantes [{:eventName :ON_CHECKPOINT
                     :aiTime now-ts
                     :ai_ts0 ai_ts0
                     :ai_ts1 ai_ts1
                     :ai_ts ai_ts
                     :value send-qr
                     :type  :QR
                     :image   image
                     :objects objects
                     :scale scale
                     :clip    clip
                     :camera  camera
                     :uuid    (or uuid (create-uuid))
                     :origin  C/origin
                     :plantId C/plantId}]]
    (assoc evt :send relevantes)))


(defn prepare-image [b64img-key img-key event]
  (let [b64img (b64img-key event)
        img (decode b64img)]
    (assoc event img-key img)))

(defn create-face-msg [{:keys [z-face  z-similarity camera]}]
  (format "PROD: %s %s->%s (%.4f) %s" (get-now-ts) camera z-face z-similarity (cond (< z-similarity 1.1) "?"
                                                                                    (< z-similarity face-treshold) "*"
                                                                                    (< z-similarity 1.2) "**"
                                                                                    :else "***")))
(defn carril-tx-mgr [{:keys [uuid]}
                     {:keys [clazz hotSpotCnt camera] :as e}]
  (cond
    (= "vehicular" clazz)
    (let [[v1 v2] (first hotSpotCnt)]
      (cond
        (= v1 v2)
        (cond-> e
          uuid (assoc :prev-uuid uuid))

        uuid
        (assoc e :uuid uuid)

        :else
        (assoc e :uuid (create-uuid))))

    (= "chofer" clazz)
    (assoc e :uuid uuid)

    :else
    (do
      (log/error (format "Error evento de clazz: %s debe ser o vehicular o chofer camera: %s" clazz camera))
      (assoc e :uuid uuid))))

(def process-face
  (reduce-with
   [:face-reducer fast-face-reducer]
   (where
    [#(and (:face-sequence-ended %) (> (:z-score %) 0))]
    (smap [(fn [e]
             (let [msg (create-face-msg e)]
               (log/info msg)))])
    (smap
     [create-face-events2send false]
     (where
      [#(seq (:send %))]
      (smap [PC/send-events]  ; camera z-face z-image z-clip z-similarity
            ))))))

(def process-qr
  (reduce-with
   [:qr-reducer qr-reducer]
   (where
    [:send-qr]
    (smap
     [create-qr-events2send]
     (where
      [#(seq (:send %))]
      (smap [PC/send-events]))))))

(def process-pasillo
  (reduce-with
   [:face-reducer fast-face-reducer]
   (where
    [#(and (:face-sequence-ended %) (> (:z-score %) 0))]
    (smap [(fn [e]
             (let [msg (create-face-msg e)]
               (log/info msg)))])
    (smap
     [create-face-events2send true]
     (where
      [#(seq (:send %))]
      (smap [SE/send-events]  ; solo lo manda a la plataforma no usa nada de PLC
            ))))))


;definición del flujo principal de eventos para ZonaZero
(def activity
  (smap
   [read-event]
   (smap
    [set-defaults {:token C/TG-TOKEN
                   :chat-id C/TG-CHAT-ID
                   :plantId C/plantId
                   :origin C/origin}]
    (time-stampit
     [:entry_ts]
     (reduce-with [:counter e-counter])
     (split
      [:error]
      (smap [#(dissoc % :image :clip)]
            (->ERROR [:all]))

      [(fn [{:keys [clazz]}]
         (#{"plc-test"} clazz))]
      (smap [PC/send-events])
      
      [(fn [{:keys [clazz eventType]}]
         (and (= "plc" clazz)
              (= "OPEN" eventType)))]
      (smap [PC/send-manual-open 13])  ; se suma 13 al open-offset para dar M14 o M15 en su caso

      [(fn [{:keys [clazz eventType]}]
         (and (= "plc" clazz)
              (= "STOP" eventType)))]
      (smap [PC/send-manual-stop 5])  ; se suma 5 al open-offset para dar M14 o M15 en su caso

      [(fn [{:keys [clazz eventType]}]
         (and (= "plc" clazz)
              (= "RUN" eventType)))]
      (smap [PC/send-manual-run 5])  ; se suma 5 al open-offset para dar M14 o M15 en su caso

      [(fn [{:keys [clazz]}]
         (#{"vehicular"} clazz))]
      (by
       [:camera]
       (reduce-with
        [:plate-reducer plate-reducer]
        (where
         [:selected-plates] ;[["abc123" 98.32] ["ab123" 99]]
         (smap
          [select-the-best]
          (smap
           [create-events2send]
           (where
            [#(seq (:send %))]
            (smap [PC/send-events])))))))

      [(fn [{:keys [clazz]}]
         (#{"peatonal"} clazz))]
      (by [:camera]
          (reduce-with
           [:face-qr-tx face-qr-tx-mgr]
           (where [:face]
                  process-face)
           (where [:QR]
                  process-qr)))
      
      [(fn [{:keys [clazz]}]
         (#{"pasillo"} clazz))]
      (by [:camera]
          (reduce-with
           [:face-qr-tx face-qr-tx-mgr]
           process-pasillo))


      (smap [#(dissoc % :image :clip :z-clip :z-image :business-events)]
            (->WARN [:all])))))))

(declare activity-sink)

(defsink activity-sink 1
  activity)

(declare rest-server)
(deflistener rest-server [{:type 'caudal.io.rest-server
                           :parameters {:host "0.0.0.0"
                                        :http-port EC/REST-PORT
                                        :cors #".*"}}])

(declare tcp-server)
(deflistener tcp-server [{:type 'caudal.io.tcp-server
                          :parameters {:port EC/TCP-PORT
                                       :host "0.0.0.0"
                                       :idle-period 300}}])

(wire [rest-server tcp-server] [activity-sink]) ;tailer

(web
 {:http-port EC/HTTP-PORT
  :host "0.0.0.0"
  :cors #"http://localhost:3449"
  :publish-sinks [activity-sink]})

(log/info "Starting plc infra...")

(PC/start-loading-white-lists!)
(PC/initialize-plc-infra)
(PC/init-processing-loops!)

