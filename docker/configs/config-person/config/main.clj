(ns main
  (:require
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
   )
  (:import
   (java.util Random UUID)
   (java.util Base64)
   (java.net InetAddress)))

(def CAUDAL_HOME (System/getenv "CAUDAL_HOME"))
(def CAUDAL_DATA (System/getenv "CAUDAL_DATA"))
(def CAUDAL_CONFIG (System/getenv "CAUDAL_CONFIG"))

(def plantId "poordoor")
(def origin "poordoor")

(def TCP-PORT 9999)
(def HTTP-PORT 8090)
(def REST-PORT 8070)

;; SANITIZADO: token y chat-id reales de Telegram removidos, poner los de la instancia
(defonce tg-token (slurp "config/tg-token.txt"))
(defonce tg-chat-id (slurp "config/tg-chat-id.txt"))


(log/info "Starting streamer")
(log/info (pr-str {:CAUDAL_HOME CAUDAL_HOME}))
(log/info (pr-str {:CAUDAL_DATA CAUDAL_DATA}))
(log/info (pr-str {:CAUDAL_CONFIG CAUDAL_CONFIG}))

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
      (when-let [body (:body e)]
        (let [e (parse-string (slurp (io/reader body)) true)]
          e)))))

(def plantId "poordoor")

(defn e-counter [{:keys [n last] :or {n 0 last -1}} e]
  (when true
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

(defn get-now-ts []
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss") (System/currentTimeMillis)))

(defn prepare-image [b64img-key img-key event]
  (let [b64img (b64img-key event)
        img (decode b64img)]
    (assoc event img-key img)))

(defn person-reducer [{:keys [objs-cnt objs-detected] :or {objs-cnt 0}} 
                      {objects :objects camera :alias entry_ts :entity_ts :as evt}]
  (let [objs? (seq objects)
        [[objs-detected objs-cnt] changed?] (histeresis [0 1] [objs-detected objs-cnt] (if objs? inc dec))
        send? (and changed? (> objs-cnt 0))
        tg-msg (str "Person@" camera)]
    (log/info (str "person-reducer: " camera " objs? " objs? " objs-detected " objs-detected " objs-cnt " objs-cnt " changed? " changed?))
    (assoc evt 
           :objs-cnt objs-cnt 
           :objs-detected objs-detected 
           :changed? changed? 
           :send? send?
           :tg-msg tg-msg)))

;definición del flujo principal de eventos para ZonaZero
(def activity
  (smap
   [read-event]
   (smap
    [set-defaults {:plantId plantId
                   :origin origin}]
    (time-stampit
     [:entry_ts]
     (reduce-with [:counter e-counter])

     (by
      [:alias]
      (reduce-with
       [:person person-reducer]
       (where
        [:send?]
        (->INFO [:all])
        (send-text [tg-token tg-chat-id :tg-msg])
        (send-photo [tg-token tg-chat-id :image])
        #_(->INFO [:all])
        )))))))

(declare activity-sink)

(defsink activity-sink 1
  activity)

(declare rest-server)
(deflistener rest-server [{:type 'caudal.io.rest-server
                           :parameters {:host "0.0.0.0"
                                        :http-port REST-PORT
                                        :cors #".*"}}])

(declare tcp-server)
(deflistener tcp-server [{:type 'caudal.io.tcp-server
                          :parameters {:port TCP-PORT
                                       :host "0.0.0.0"
                                       :idle-period 300}}])

(wire [rest-server tcp-server] [activity-sink])

(web
 {:http-port HTTP-PORT
  :host "0.0.0.0"
  :cors #"http://localhost:3449"
  :publish-sinks [activity-sink]})

