(ns main
  (:require
   [constants :as C]
   #_[plc-controler :as PC]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [clojure.core.match :refer [match]]
   [clojure.core.cache :as cache]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]
   #_[clj-fuzzy.metrics :refer [jaro]]
   [caudal.streams.common :refer [defsink deflistener wire]]
   [caudal.io.rest-server :refer [web]]
   [caudal.streams.stateful :refer [reduce-with changed]]
   [caudal.streams.stateless :refer [by pprinte where split smap time-stampit ->INFO ->WARN ->ERROR reinject]]
   [caudal.io.telegram :refer [send-photo send-text]]
   #_[caudal.io.email :refer [mailer email-event-with-body-fn]]
   [cheshire.core :refer [parse-string]]
   #_[send-events :as SE]
   )
  (:import
   (java.util Random UUID)
   (java.util Base64)
   (java.net InetAddress)))

(log/info "Starting streamer")
(log/info (pr-str {:CAUDAL_HOME C/CAUDAL_HOME}))
(log/info (pr-str {:CAUDAL_CONFIG C/CAUDAL_CONFIG}))

(def delta-resend 3000)
(def face-threshold 0.6)

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
  (cond (:json e)
        (parse-string (:json e) true)

        (:body-params e)
        (:body-params e)

        (:body e)
        (parse-string (slurp (io/reader (:body e))) true)

        (map? e)
        e
        
        :else
        (log/warn (pr-str ["read-event: evento no reconocido" e]))))

(defn e-counter [{:keys [n last] :or {n 0 last -1}} e]
  (when C/WITH-E-COUNTER
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
          (when doGC (.gc rt))
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

(let [out (io/file "plates.log")]
  (println "*******  " (.getCanonicalPath out)))

(defn get-now-ts []
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss") (System/currentTimeMillis)))

; en esta version no nos importe al B: o F: solo vamos a desduplicar los face diferentes y reportar cada que sea diferente y que no sea undefined o unknown
(defn vapix-event-face-reducer [{:keys [z-id z-ts] :or {z-id "unknown" z-ts (System/currentTimeMillis)}}
                                {{:keys [id similarity clip] :or {id "unknown" similarity 0}} :identify :keys [active? pulse? image-b64] :as evt}]
  (let [ts (System/currentTimeMillis)
        new-z-ts (if (not= id z-id) ts z-ts)]
    (log/warn (pr-str ["TAB vapix-event-face-reducer" (dissoc evt :image-b64)]))
    (assoc evt 
           :z-image image-b64 
           :z-clip clip 
           :z-id id 
           :z-similarity similarity 
           :z-ts new-z-ts
           :face-sequence-ended (and
                                 (not= id "unknown")
                                 (or (not= id z-id)
                                         (> (- ts z-ts) delta-resend))))))

(defn create-face-events2send [use-cache? {:keys [camera z-id z-image z-clip z-similarity] :as evt}]
  (let [now-ts (System/currentTimeMillis)
        relevantes [{:eventName :ON_CHECKPOINT
                     :aiTime now-ts
                     :value z-id
                     :accuracy z-similarity
                     :image   z-image
                     :clip    z-clip
                     :camera  camera
                     :uuid   (create-uuid)
                     :origin  C/origin
                     :plantId C/plantId}]
        should-send? (if use-cache?
                       (let [cache-key (str camera "-" z-id)
                             cached-value (get-value! value-lru cache-key)]
                         (if (nil? cached-value)
                           (do
                             (put-value! value-lru cache-key)
                             true)
                           (do
                             (log/info (pr-str ["TAB Se descarta repetido " camera z-id]))
                             false)))
                       true)]
    (when (< z-similarity face-threshold) (log/warn (pr-str ["TAB Se descarta relevante " camera z-similarity z-id])))
    (cond-> evt
      (and should-send? (>= z-similarity face-threshold)) (assoc :send relevantes))))

;definición del flujo principal de eventos para ZonaZero
(def activity
  (smap
   [read-event]
   (smap
    [set-defaults {:plantId C/plantId
                   :origin C/origin}]
    (time-stampit
     [:entry_ts]
     (reduce-with [:counter e-counter])
     (split
      [:error]
      (smap [#(dissoc % :image :clip)]
            (->ERROR [:all]))

      [(fn [{:keys [event]}]
         (#{:ON_AXIS_EVENT} event))]
      (by
       [:camera]
       (reduce-with
        [:face-reducer vapix-event-face-reducer]
        (where
         [#(:face-sequence-ended %)]
         (smap
          [create-face-events2send false]
          (where
           [#(seq (:send %))]
           (smap [(fn [{:keys [send]}]
                    (doseq [e send]
                      (log/info (pr-str ["TAB Se envia evento " (dissoc e :clip :image)]))))]))))))

      (smap [#(dissoc % :image-b64 :image :clip :z-clip :z-image :business-events)]
            (->WARN [:all])))))))

(declare activity-sink)

(defsink activity-sink 1
  activity)

(declare rest-server)
(deflistener rest-server [{:type 'caudal.io.rest-server
                           :parameters {:host "0.0.0.0"
                                        :http-port C/REST-PORT
                                        :cors #".*"}}])

(declare tcp-server)
(deflistener tcp-server [{:type 'caudal.io.tcp-server
                          :parameters {:port C/TCP-PORT
                                       :host "0.0.0.0"
                                       :idle-period 300}}])

(deflistener axis-cam1 [{:type 'caudal.io.axis-vapix-server
                         :parameters {:camera {:protocol "http"
                                               :ip "192.168.88.34"
                                               :user "root"
                                               :password "Quantum18!"}
                                      :camera-info {:camera "axis2"}
                                      :topic-filter "tnsaxis:CameraApplicationPlatform/facedetector/CameraProfile1"
                                      :retry-ms 5000
                                      :pulse-ms 1000
                                      :with-image? true
                                      :identify {:url "http://qface-server:8000/identify"
                                                 :token "34ee7354-5016-4fa7-b3ba-6e28e66ddcaa"
                                                 :threshold 0.55
                                                 :top-k 1
                                                 :timeout-ms 5000}}}])

(wire [rest-server tcp-server axis-cam1] [activity-sink])

(web
 {:http-port C/HTTP-PORT
  :host "0.0.0.0"
  :cors #"http://localhost:3449"
  :publish-sinks [activity-sink]})

#_(log/info "Starting plc infra...")

#_(PC/start-loading-white-lists!)
#_(PC/initialize-plc-infra)
#_(PC/init-processing-loops!)

