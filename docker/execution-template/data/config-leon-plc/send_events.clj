(ns send-events
  (:use constants
        env.constants)
  (:require
   [clojure.core.async :refer [chan >!!  alts! go-loop go buffer dropping-buffer <! >! timeout]]
   [clojure.java.io :as io]
   [clojure.string :as S]
   [clojure.edn :as edn]
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

(defn create-log-busines-event-str [platform-url
                                    retry
                                    status
                                    {:keys [entry_ts ; inicio del flujo en caudal
                                            exit_bucket_ts ; ts despues de salir del priority-buff
                                            aiTime ; ts justo antes de mandarlo de python a caudal
                                            aiTime0 ; ts del frame al salir de la camara
                                            aiTime1 ; ts justo despues de salir de la red neuronal
                                            caudalTime ; momento en que se definio el On...event
                                            nn_delta
                                            camera alias place pallets eventName plate plates
                                            debug_file_name is_last places
                                            count desc position] :as e}]
  (if e
    (if (= eventName :OnError)
      (format "%s %s %s %s:%d" platform-url eventName (pr-str (:errors e)) status retry)
      (let [seg-py [(- aiTime1 aiTime0) (- aiTime aiTime1)]
            latency (- aiTime entry_ts)
            seg-caudal [(- exit_bucket_ts entry_ts) (- caudalTime exit_bucket_ts)]
            sdf (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss.SSS")
            time (.format sdf aiTime0)
            camera alias ;(subs cameraId (- (count cameraId) 17))
            output (if (#{:OnTrustedRead :OnUntrustedRead :OnCustomsEmpty :OnBadParking
                          :OnProductNotVisible :OnSceneNotKnown} eventName)
                     (format "%s %s %s %d %s Place %s ..%s %-20s %-10s %2s %s %s %s:%d"
                             platform-url
                             time seg-py latency seg-caudal
                             place camera eventName (or plate "-") (str (or count "-")) (str (or position "-")) (or desc "-") status retry)
                     (format "%s %s %s %d %s Place %s ..%s %-20s %-10s %2s %s %s %s:%d"
                             platform-url
                             time seg-py latency seg-caudal
                             place camera eventName (or plate "-") (str (or pallets "-")) (str (or plates "-")) (or debug_file_name "-") status retry))]
        output))))

(defn store-event [file-name event]
  (log/debug event)
  (let [file-name (if (>= (.indexOf file-name "%s") 0)
                    (format file-name (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") (System/currentTimeMillis)))
                    file-name)]
    (with-open [out (io/writer file-name :append true)]
      (.write out (str (pr-str event) "\n"))))
  event)

(def http-pool (http/connection-pool {:connection-options {:insecure? true :idle-timeout 20000} :max-queue-size 8}))

; urls es un vector de mapas con cada mapa teniendo :url y :auth opcional
(defn do-post-with-fallback [urls path http-params]
  (loop [[{:keys [url auth]} & next] urls]
    (log/debug (format ":do-post-with-fallback %s" url))
    (let [d-http-params (cond-> http-params
                          auth (merge {:headers {"Authorization" auth}}))
          {:keys [status] :as r} (try
                                   @(http/post (str url path) d-http-params)
                                   (catch Exception e
                                     (log/error e)
                                     {:status 500 :message (format "Exception making post to %s %s" url (.getMessage e))}))]
      (if (or (and (number? status) (>= status 200) (< status 300))
              (not (seq next)))
        (do
          (log/info  (format ":do-post-with-fallback-ends %s %s" url status))
          r)
        (do
          (log/error  (format ":do-post-with-fallback-continues %s %s" url status))
          (recur next))))))

(defn post-it! [platform-url {tipo :type eventName :eventName :as event}]
  (try
    (let [http-params {:pool http-pool
                       :body (generate-string (dissoc event :imagex :clipx))
                       :content-type :json
                       :pool-timeout HTTP-TIMEOUT
                       :connection-timeout HTTP-TIMEOUT
                       :request-timeout HTTP-TIMEOUT
                       :read-timeout HTTP-TIMEOUT
                       :throw-exceptions? false}]
      (cond

        (and (= eventName :OnError)
             platform-url) (do 
                             (log/error (pr-str event))
                             "OnError")

        (and (= tipo :OFFICE)
             platform-url) (let [{:keys [status]} (do-post-with-fallback platform-url "add" http-params)] ;@(http/post (str platform-url "fRec") http-params)
                             ;(log/info (pr-str [:TABTAB status (dissoc event :image :clip)]))
                             status)

        (and (= tipo :FACE)
             platform-url) (let [{:keys [status]} (do-post-with-fallback platform-url "fRec" http-params)] ;@(http/post (str platform-url "fRec") http-params)
                             ;(log/info (pr-str [:TABTAB status (dissoc event :image :clip)]))
                             status)

        (and (= tipo :QR)
             platform-url) (let [{:keys [status]} (do-post-with-fallback platform-url "fRec" http-params)] ;@(http/post (str platform-url "fRec") http-params)
                             status)
        
        (and (= tipo :VEHICLE)
             platform-url) (let [{:keys [status]} (do-post-with-fallback platform-url "pRec" http-params)] ;@(http/post (str platform-url "pRec") http-params)
                             status)

        :OTHERWIZE "no-platform-url"))
    (catch Exception e
      (log/error e)
      (.getMessage e))))

(when DIR-FOR-RESEND
  (def resend-dir (io/file (str CAUDAL_HOME "/" DIR-FOR-RESEND)))
  (.mkdirs resend-dir))


(defn round-to-10-min [ts]
  (long (* (long (/ ts 600000))  600000)))

(defn resend-label [millis]
  (str (.format (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH-mm") (round-to-10-min millis)) ".edn")) ; ojo hasta los 10 min


(defn resendable-event [{:keys [eventName]}]
  (not (NOT-RESENDABLE-EVENTS eventName)))

(defn store-for-resend [event]
  (when (and DIR-FOR-RESEND
             (resendable-event event))
    (let [now-rounded-file-name (resend-label (System/currentTimeMillis))
          file-name (.getCanonicalPath (io/file resend-dir now-rounded-file-name))]
      (try
        (store-event file-name event)
        (catch Throwable e
          (log/error e))))))

(def resend-responses #"Connection refused:.*|connection was closed|timed out after [0-9]+ milliseconds|connection timed out.*|No route to host.*|5[0-9]{2}")

(io/make-parents (io/file (str CAUDAL_DATA "/relevantes/tmp.txt")))

(defn post-it [platform-url event r-cnt err-chan]
  (try
    (let [{:keys [camera]} event
          status (post-it! platform-url event)]
      (when (and (number? status) (not= status 200))
        (log/error (format "TAB\t %-10s %-10s %s %s %s"  (:type event) (:camera event)  (:accuracy event) status (:value event) )))
      (store-event (str CAUDAL_DATA "/relevantes/relevantes-%s.edn.txt") (assoc event :http-status status :retry r-cnt))
      #_(if (re-matches resend-responses (str status))
          (let [r-cnt (inc r-cnt)]
            (if (< r-cnt (get RESEND-RETRY-MAP (:place event) RESEND-RETRY-DEFAULT))
              (go
                (<! (timeout (* 5000 r-cnt)))
                (>! err-chan [platform-url event r-cnt]))
              (store-for-resend (assoc event :http-status status :retry r-cnt))))))
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

(defn log-events [to-send]
  (doseq [{:keys [type camera accuracy value]} to-send]
    (log/info (format "TAB\t %-10s %-10s %9.4f %s"  type camera (float (or accuracy 1.0)) value))))

(defn get-platform-urls []
  (let [qa_url_file (io/file (str CAUDAL_CONFIG "/QA_URL.edn"))
        ;_ (log/warn :PLATFORM-URL-0 (.getCanonicalPath qa_url_file) (.exists qa_url_file))
        PLATFORM-URL-QA (when (.exists qa_url_file)
                          (-> qa_url_file
                              slurp
                              edn/read-string))
        ; ojo PLATFORMS es un vector de vectores con los mapas con :url :auth
        ; cada vector tuene varios mapas para el FALL-BACK !!
        PLATFORMS (cond-> [PLATFORM-URL]
                    PLATFORM-URL-QA (into [PLATFORM-URL-QA]))]
    #_(log/warn :PLATFORM-URL-0-1 (pr-str PLATFORMS))
    PLATFORMS))

(defn send-events [{:keys [send plantId origin] :as event}]
  (try
    ;(log/info (format "send-events: %s %d evts %s %s" PLATFORM-URL (count send) plantId origin))
    (log-events send)
    ;(log/info (pr-str (mapv #(dissoc % :clip :image :uuid :eventName :ai_ts0 :ai_ts1 :plantId :aiTime :origin ) send)))

    (if-let [to-send (seq (->> send
                               (filter identity)
                               (map #(assoc % :origin origin :plantId plantId))))]
      (let [PLATFORMS (get-platform-urls)]
        #_(log/warn :PLATFORM-URL-1 PLATFORMS)
        (doseq [platform-url PLATFORMS]
          #_(log/warn :PLATFORM-URL-2 platform-url)
          (doseq [evt to-send]
            (poster platform-url evt)))
        (assoc event :business-events (vec to-send)))
      event)
    (catch Exception e
      (.printStackTrace e)
      (log/error e)
      event)))


(defn post-multi-events [events]
  (if (seq events)
    (let [json (generate-string events)
          http-params (cond->
                       {:pool http-pool
                        :body json
                        :content-type :json
                        :throw-exceptions? false
                        :request-timeout 30000
                        }
                       AUTHORIZATION
                       (merge {:headers {"Authorization" AUTHORIZATION}}))

          _ (log/info "Posting multi events (resend): " (count events))
          {:keys [status]} (cond
                             PLATFORM-URL @(http/post
                                            (str PLATFORM-URL "/events/retry")
                                            http-params)

                             :OTHERWIZE {:status "no-platform-url"})]
      (str status))
    "no-events-to-send-or-corrupt-file"))

(defn parse-events-file [file]
  (try
    (mapv #(edn/read-string %) (line-seq (io/reader file)))
  (catch Exception e
    (log/error (format "parse-events-file: File % is corrupted, not valid edn seq" (.getCanonicalPath file)))
    [])))

(defn resend-events [evt]
  (when DIR-FOR-RESEND
    (let [parent (java.io.File. DIR-FOR-RESEND)
          limit-ts (resend-label (System/currentTimeMillis))
          files (sort
                 (filter
                  #(and
                    (re-matches #"[0-9]{4}\-[0-9]{2}\-[0-9]{2}T[0-9]{2}\-[0-9]0.*\.edn" (.getName %))
                    (< (.getName %) limit-ts))
                  (.listFiles parent)))]
      (when (seq files)
        (log/info (vec (map #(.getName %) files)))
        (loop [[current & next-files] files]
          (let [events (parse-events-file current)
                sended-events-status (post-multi-events events)
                renamed-file (java.io.File. (.getParent current) (format "%s.%s" (.getName current) sended-events-status))]
            (if (#{"200" "204" "no-platform-url" "no-events-to-send-or-corrupt-file"} sended-events-status)
              (do
                (log/info :sended-events (.getCanonicalPath current))
                (.renameTo current renamed-file)
                (recur next-files))
              (log/error (format "Error resending events file %s re-trying later" (.getCanonicalPath current)))))))))
  evt)
