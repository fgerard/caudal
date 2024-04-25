(ns send-events
  (:use constants
        env.constants)
  (:require
   [clojure.core.async :refer [chan >!!  alts! go-loop go buffer dropping-buffer <! >! timeout]]
   [clojure.java.io :as io]
   [clojure.string :as S]
   [clojure.tools.logging :as log]
   [clojure.java.shell :as sh]
   [clojure.pprint :as pp]
   [aleph.http :as http]
   [manifold.deferred :as d]
   [byte-streams :as bs]
   [caudal.streams.common :refer :all]
   [caudal.io.rest-server :refer :all]
   [caudal.streams.stateful :refer :all]
   [caudal.streams.stateless :refer :all]
   [caudal.io.email :refer [mailer]]
   [caudal.core.folds :refer [rate-bucket-welford]]
   [caudal.util.date-util :as DU]
   [clara.rules :refer :all]
   [clara.rules.accumulators :as acc]
   [clara.tools.tracing :as tracing]
   [cheshire.core :refer [generate-string parse-string]])
  (:import (java.util Random UUID)
           (java.util Base64)))

(defn store-event [file-name event]
  (log/debug event)
  (let [file-name (if (>= (.indexOf file-name "%s") 0)
                    (format file-name (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") (System/currentTimeMillis)))
                    file-name)]
    (with-open [out (io/writer file-name :append true)]
      (.write out (str (pr-str event) "\n"))))
  event)

(def http-pool (http/connection-pool {:connection-options {:insecure? true :idle-timeout 20000} :max-queue-size 8}))

(def token-atom (atom {:ts 0 :token nil}))

(defn calc-new-token [platform-url]
  (try
    (let [http-params {:pool http-pool
                       :body (generate-string {:username USERNAME :password PASSWORD})
                       :content-type :json
                       :pool-timeout HTTP-TIMEOUT
                       :connection-timeout HTTP-TIMEOUT
                       :request-timeout HTTP-TIMEOUT
                       :read-timeout HTTP-TIMEOUT
                       :throw-exceptions? false}
          {:keys [status body]} @(http/post (str platform-url "/api/auth/system/login") http-params)]
      (if (= status 200)
        (let [result (try
                       (-> (io/reader body)
                           (slurp)
                           (parse-string true))
                       (catch Exception e
                         (log/error e)
                         {:username "error" :access_token "ERROR-IN-CREATE-TOKEN-2"}))]
          (:access_token result))
        (log/error "Error al generar token " status)))
    (catch Exception e
      (log/error e)
      "ERROR-IN-CREATE-TOKEN-1")))

(log/info "CREANDO TOKEN !!! >>> " (calc-new-token PLATFORM-URL))

(defn get-a-token [{:keys [ts] :as token-info} platform-url]
  (let [now (System/currentTimeMillis)]
    (if (> (- now 3600000) ts)
      {:ts now :token (calc-new-token platform-url)}
      token-info)))

(defn get-token [platform-url] 
  (:token (swap! token-atom get-a-token platform-url)))

(defn post-it! [platform-url evt]
  (try
    (let [http-params (cond->
                       {:pool http-pool
                        :body (generate-string evt)
                        :content-type :json
                        :pool-timeout HTTP-TIMEOUT
                        :connection-timeout HTTP-TIMEOUT
                        :request-timeout HTTP-TIMEOUT
                        :read-timeout HTTP-TIMEOUT
                        :throw-exceptions? false}
                        AUTHORIZATION (merge {:headers {"Authorization" (format AUTHORIZATION (get-token platform-url))}}))
          {:keys [status]} @(http/post (str platform-url "/api/event") http-params)]
      status)
    (catch Exception e
      (log/error e)
      (.getMessage e))))

(io/make-parents (io/file (str CAUDAL_DATA "/relevantes/tmp.txt")))

(defn post-it [platform-url {:keys [controler-name AntennaPortNumber PeakRssiInDbm d-id event rfid-ts uuid] :as evt} r-cnt err-chan]
  (try
    (log/info "EL EVENTO PELON")
    (log/info (pr-str evt))
    (let [system-event {:atTime rfid-ts :eventName event :value d-id :uuid uuid :lane (str controler-name AntennaPortNumber)}
          status (post-it! platform-url system-event)]
      (log/info (format "\t send-events http-status: %s %-10s %s %-25s %-20s %s %s"  status controler-name AntennaPortNumber d-id event uuid rfid-ts))
      (log/info (format "send-events: %s %s %s " status PLATFORM-URL system-event)))
      ;(store-event (str CAUDAL_DATA "/relevantes/relevantes-%s.edn.txt") (assoc event :http-status status :retry r-cnt)))
  (catch Throwable e
    (.printStackTrace e)
    (log/error e))))

(defn create-poster [depth]
  (let [d-chan (chan (dropping-buffer depth))]
    (go
     (loop [[platform-url event r-cnt] (<! d-chan)]
       (when event
         (post-it platform-url event r-cnt d-chan)
         (recur (<! d-chan)))))
    (fn [platform-url event]
      (>!! d-chan [platform-url event 0]))))

(def poster (create-poster POST-PLATFORM-BUFF-SIZE))

(defn send-events [{:keys [controler-name AntennaPortNumber PeakRssiInDbm d-id event entry-ts plantId origin] :as evt}]
  (try
    (log/info (pr-str [:send-event-1 evt]))
    (poster PLATFORM-URL evt)
    evt
  (catch Exception e
    (.printStackTrace e)
    (log/error e)
    evt)))


