(ns main
  (:require
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [clojure.edn :as edn]
   [clojure.pprint :as pp] 
   [caudal.streams.common :refer [defsink deflistener wire]]
   [caudal.io.rest-server :refer [web]]
   [caudal.streams.stateful :refer [reduce-with 
                                    changed 
                                    counter
                                    moving-time-window
                                    priority-buff
                                    rollup
                                    batch
                                    with-histerisis 
                                    tx-mgr]]
   [caudal.streams.stateless :refer [by pprinte where split smap time-stampit ->INFO ->WARN ->ERROR reinject]]
   [caudal.io.telegram :refer [send-photo send-text]]
   ;[caudal.io.email :refer [mailer email-event-with-body-fn]]
   [cheshire.core :refer [parse-string]]
   )
  (:import
   (java.util Random UUID)
   (java.util Base64)
   (java.net InetAddress)))

(log/info "Starting streamer main.clj")

(defn create-uuid [] (str (UUID/randomUUID)))

(defn decode [string]
  (.decode (Base64/getDecoder) string))

(defn read-event [e]
  (println (pr-str [:evt e]))
  (if-let [json (:json e)]
    (parse-string json true)
    (if-let [evt (:body-params e)]
      evt
      (when-let [body (:body e)]
        (let [e (parse-string (slurp (io/reader body)) true)]
          e)))))

(defn write-event [file-name event]
  (with-open [out (io/writer file-name :append true)]
    (.write out (str (pr-str event) "\n"))))

(defn base64TOimg [key evt]
  (decode (key evt)))

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

(defn create-tx-event [{:keys [tx-id tx-id-old camera]}]
  (if tx-id
    {:event :ON_BOX_ENTER
     :uuid tx-id
     :camera camera
     :ts (System/currentTimeMillis)}
    {:event :ON_BOX_EXIT
     :uuid tx-id-old
     :camera camera
     :ts (System/currentTimeMillis)}))

(defn tx-factory [{:keys [tx-changed?] :as e}]
  (if tx-changed?
    (assoc e :send [(create-tx-event e)])
    e))

;definición del flujo principal
(def activity
  (smap
   [read-event]
   (time-stampit
    [:entry_ts]
    (counter
     [:counter :cnt]
     #_(moving-time-window
        [:ventana 5000 :entry_ts]
        (->INFO [:all]))
     #_(smap
        [(fn [e] (assoc e :val (rand-int 100)))]
        (priority-buff
         [:priority 10 :val]
         (->INFO [:all])))
     #_(batch
        [:batch 5 5000]
        (->INFO [:all]))
     #_(with-histerisis [:hist [1 0] #(let [pred (:edad %)]
                                        (if (not pred) :skip (> pred 60)))]
         (->INFO [:all]))
     (by
      [:place]
      (tx-mgr
       [:hist 2 #(let [{:keys [clazz plate]} %]
                   (if (not= clazz "vehicle") :skip plate))]
       (smap
        [tx-factory]
        (->INFO [:all]))))))))

(declare activity-sink)

(defsink activity-sink 1
  activity)

(declare rest-server)
(deflistener rest-server [{:type 'caudal.io.rest-server
                           :parameters {:host "0.0.0.0"
                                        :http-port 8088
                                        :cors #".*"}}])

(declare tcp-server)
(deflistener tcp-server [{:type 'caudal.io.tcp-server
                          :parameters {:port 9999
                                       :host "0.0.0.0"
                                       :idle-period 300}}])

(wire [rest-server tcp-server] [activity-sink]) ;tailer

(web
 {:http-port 8080
  :host "0.0.0.0"
  :cors #"http://localhost:3449"
  :publish-sinks [activity-sink]})
