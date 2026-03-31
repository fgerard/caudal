(ns main
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [clojure.core.match :refer [match]]
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
   (java.net InetAddress)
   (java.time LocalDateTime ZoneId ZonedDateTime)
   (java.time.format DateTimeFormatter)
   ))

(log/info "Starting streamer")

(defn create-uuid [] (str (UUID/randomUUID)))

(defn decode [string]
  (.decode (Base64/getDecoder) string))

(defn parse-log-line [line]
  (if (re-find #"Despues de invocar face detect" line)
    (let [;; Extraer la fecha y hora
          ;_ (println (pr-str [line]))
          date-str (subs line 0 23) ; "2025-07-11 18:10:52,589"
          formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss,SSS")
          local-dt (LocalDateTime/parse date-str formatter)
          millis (.toInstant (ZonedDateTime/of local-dt (ZoneId/of "America/Mexico_City")))
          epoch-millis (.toEpochMilli millis)

          ;; Partes de la línea divididas por espacio
          tokens (str/split line #"\s+")
          ;_ (println (pr-str [:tokens tokens]))
          ;; Encontrar la posición donde aparece FACE220
          face-idx (->> tokens
                        (map-indexed vector)
                        (filter (fn [[_ s]] (str/starts-with? s "FACE")))
                        (ffirst))
          camera (nth tokens face-idx)
          id (nth tokens (inc face-idx))
          score (Double/parseDouble (nth tokens (+ face-idx 2)))]

      {:ts epoch-millis
       :camera camera
       :id id
       :score score})
    {}))



(defn read-event [req]
  (println (pr-str req))
  (cond

    ;; 🟢 Caso 1: viene JSON como string en :json
    (:json req)
    (json/parse-string (:json req) true)

    ;; 🟢 Caso 2: ya viene parseado (form params / middleware)
    (:body-params req)
    (:body-params req)

    ;; 🟢 Caso 3: viene en el body raw
    (:body req)
    (let [body-str (slurp (:body req))
          content-type (get-in req [:headers "content-type"] "")]
      (println "BODY RAW:" (pr-str body-str))
      (cond
        ;; JSON
        (clojure.string/includes? content-type "application/json")
        (json/parse-string body-str true)

        ;; EDN
        (clojure.string/includes? content-type "application/edn")
        (read-string body-str)

        ;; fallback
        :else
        body-str))

    ;; 🔴 nada encontrado
    :else
    nil))

#_(defn e-counter [{:keys [n last] :or {n 0 last -1}} e]
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

(defn get-now-ts []
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss") (System/currentTimeMillis)))


;definición del flujo principal de eventos para ZonaZero
(def activity
  (smap
   [read-event]
   (smap [#(println (format "evt: %s" %))])))

(when (System/getenv "DEV")
  
  (declare activity-sink)

  (defsink activity-sink 1
    activity)

  (declare rest-server)
  (deflistener rest-server [{:type 'caudal.io.rest-server
                           :parameters {:host "0.0.0.0"
                                        :http-port 8070
                                        :cors #".*"}}])
  (declare tail-server)
  (deflistener tailer-server [{:type 'caudal.io.tailer-server
                               :parameters {:parser      parse-log-line
                                            :inputs      {:directory  "/opt/quantumlabs/ai_streamer/data/logs2/"
                                                          :wildcard   "cosa.log"}
                                            :delta       250
                                            :from-end    true
                                            :reopen      true
                                            :buffer-size 100000}}])

  (wire [tailer-server rest-server] [activity-sink]) ;tailer

  (web
   {:http-port 8888
    :host "0.0.0.0"
    :cors #"http://localhost:3449"
    :publish-sinks [activity-sink]})

  )
