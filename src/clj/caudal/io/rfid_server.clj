(ns caudal.io.rfid-server
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :refer [chan go-loop <! >! timeout >!!]]
            [caudal.streams.common :refer [start-listener]]
            [caudal.util.ns-util :refer [resolve&get-fn require-name-spaces]]
            [clojure.edn :as edn])
  (:import (com.impinj.octane ImpinjReader
                              TagReportListener
                              KeepaliveListener
                              ConnectionLostListener
                              GpoMode)))

(defmulti get-value-of (fn [bean fld method]
                         fld) :default "default")

(defmethod get-value-of #{"Tid" "Epc"} [bean fld method]
  (let [fldK (keyword fld)
        val-obj (.invoke method bean nil)]
    [fldK (.toHexWordString val-obj)]))

(defmethod get-value-of "ModelDetails" [bean fld method]
  (let [fldK (keyword fld)
        val-obj (.invoke method bean nil)]
    [fldK (.name (.getModelName val-obj))]))

(defmethod get-value-of "GpsCoodinates" [bean fld method]
  (let [fldK (keyword fld)
        val-obj (.invoke method bean nil)]
    [fldK (format "%.4f,%.4f" (.getLatitude val-obj) (.getLongitude val-obj))]))

(defmethod get-value-of #{"LastSeenTime" "FirstSeenTime"} [bean fld method]
  (let [fldK (keyword fld)
        val-obj (.invoke method bean nil)]
    [fldK (.getUtcTimestamp val-obj)]))

(defmethod get-value-of "default" [bean fld method]
  (try 
    (let [fldK (keyword fld)
          val-obj (.invoke method bean nil)]
      [fldK (str val-obj)])
    (catch Exception e
      (log/warn (format "Skiping %s error %s" fld (.getMessage e)))
      nil)))

(defn bean->map [bean]
  (let [clase (.getClass bean)
        methods (into []
                      (filter
                       (fn [method]
                             ;(log/info (format "method name: %s -> %s" (.getName method) (re-matches #"get.*" (.getName method))))
                         (re-matches #"get.*" (.getName method)))
                       (.getDeclaredMethods clase)))]
    (into {} (filter identity
                     (map
                      (fn [method]
                        (let [fld (subs (.getName method) 3)
                              result (get-value-of bean fld method)]
                      ;(log/info "Result: " result)
                          result))
                      methods)))))

(defn configAntennas [antennas-obj antennas-ids]
  (log/info (format "antenas-obj: %s -> %s" antennas-obj antennas-ids))
  (doseq [[id tx rx :as info] antennas-ids]
    (log/info (format "id: %s" info))
    (let [d-antenna (.getAntenna antennas-obj (short id))]
      (log/info (format "Antenna: %s -> %s" d-antenna id))
      (log/info (pr-str (bean->map d-antenna)))
      (cond (= rx true)
            (.setIsMaxRxSensitivity d-antenna true)

            (number? rx)
            (.setRxSensitivityinDbm d-antenna rx))
      
      (cond (= tx true)
            (.setIsMaxTxPower d-antenna true)
            
            (number? tx)
            (.setTxPowerinDbm d-antenna tx))
      ;(.setIsMaxRxSensitivity d-antenna true)
      ;(.setIsMaxTxPower d-antenna true)

      (log/info (format "MaxRxSencitivityinDbm: %s" (.getRxSensitivityinDbm d-antenna)))
      (log/info (format "MaxTxPoweridDbm: %s" (.getTxPowerinDbm d-antenna)))
      ;(.setRxSensitivityinDbm d-antenna -0.5)
      ))
  antennas-obj)

(def timed-state (atom
                  {:ids {} ; mapa con id->ts
                   :id->evt {} ;mapa con id->evt original
                   :delta 1000
                   :removed #{}
                   :last-update 0}))

(defn timed-cache-put [id evt]
  (swap!
   timed-state
   (fn [state]
     (let [now (System/currentTimeMillis)]
       (-> state
           (assoc-in [:ids id] now)
           (assoc-in [:id->evt id] evt)
           (assoc :last-update now))))))

(defn timed-cache-get&clear-removed [delta]
  (let [{:keys [ids-removed]} 
        (swap!
         timed-state
         (fn [{:keys [ids id->evt] :as state}]
           (let [state (dissoc state :removed)
                 now (System/currentTimeMillis)
                 
                 {:keys [removed] :as new-state}
                 (reduce
                  (fn [new-state [k k-ts]]
                    (let [k-ts (if (nil? k-ts) 0 k-ts)]
                      (if (> (- now k-ts) delta)
                        (-> new-state
                            (update :removed #(conj % (id->evt k)))
                            (update :ids #(dissoc % k))
                            (update :id->evt #(dissoc % k)))
                        new-state)))
                  state
                  ids)]
             (-> new-state
                 (assoc :removed #{})
                 (assoc :ids-removed removed)))))]
    ids-removed))

(defn convert-tag2event [t]
  (let [isFastIdPresent (.isFastIdPresent t)
        d-id (if isFastIdPresent
               (->> t .getTid .toHexString)
               (->> t .getEpc .toHexString))
        evt (bean->map t)]
    (assoc evt :d-id d-id)))

(defn send-if-not-in-cache [d-id-re sink {:keys [d-id] :as evt}]
  (log/debug :re-matches d-id-re d-id (re-matches d-id-re d-id))
  (if (re-matches d-id-re d-id)
    (let [;state @timed-state
        ;_ (log/warn (pr-str [:timed-state d-id :-> state]))
        ;_ (log/warn (str "---> " (pr-str (get-in @timed-state [:ids d-id])) " exists? "))
          exists? (get-in @timed-state [:ids d-id])]
      (timed-cache-put d-id evt)
      (when-not exists?
        (sink evt)))
    (log/info (format "Dropping tag: %s" (pr-str evt)))))

(defn start-tag2sink-remove-duplicates [d-id-re sink sink-chan]
  (go-loop [{:keys [event] :as e} (<! sink-chan)]
    (condp = event
      :ON_TAG_READ (send-if-not-in-cache d-id-re sink e)
      :ON_TAG_REMOVED (sink e)
      ;:ON_TAG_READ (sink e)
      )
    (recur (<! sink-chan))))

(defn start-timed-cache-cleanup [delta-loop sink-chan]
  (go-loop [removed-now (timed-cache-get&clear-removed delta-loop)]
    #_(when (seq removed-now)
      (log/info (pr-str [:removig-tags (mapv :d-id removed-now)])))
    (doseq [{:keys [d-id] :as evt} removed-now]
      (let [removed-event (merge evt {:event :ON_TAG_REMOVED
                                      :rfid-ts (System/currentTimeMillis)})]
        (log/info (pr-str [:removing-tag evt]))
        (>! sink-chan removed-event)))
    (<! (timeout delta-loop))
    (recur (timed-cache-get&clear-removed delta-loop))))

(defn t->evt [evt-key controler-name controler tagORevt]
  (try
    (let [extra {:event evt-key
                 :controler-name controler-name
                 :controler controler
                 :rfid-ts (System/currentTimeMillis)}
          evt (if (map? tagORevt)
                tagORevt
                (convert-tag2event tagORevt))
          evt (merge evt extra)]
      evt)
    (catch Exception e
      (.printStackTrace e)
      {:controler-name controler-name
       :controler controler
       :event :ON_TAG_ERROR
       :msg (.getMessage e)
       :rfid-ts (System/currentTimeMillis)})))

(defn create-listener [chan-buf-size sink controler-name controler cleanup-delta d-id-re]
  (let [sink-chan (chan chan-buf-size 
                        (map (partial t->evt :ON_TAG_READ controler-name controler)))]
    (start-tag2sink-remove-duplicates d-id-re sink sink-chan)
    (start-timed-cache-cleanup cleanup-delta sink-chan)
    (reify TagReportListener
      (onTagReported [_ reader report]
        (let [tags (.getTags report)]
          (doseq [t tags] 
            (try
              (>!! sink-chan t) ; la trasformacion la hace el trasducer
              (catch Exception e
                (log/error e)
                (.printStackTrace e)
                ))))))))

(defn create-keep-alive-listener [controler-name controler]
  (reify KeepaliveListener 
    (onKeepalive [_ reader event]
      (let [e (t->evt :ON_KEEP_ALIVE controler-name controler {})]
        (log/info e)))))

(defn create-connection-lost-listener [controler-name controler]
  (reify ConnectionLostListener
    (onConnectionLost [_ reader]
      (let [isConnected? (.isConnected reader)
            e (t->evt :ON_CONNECTION_LOST controler-name controler {:connected isConnected?})]
        (log/error e)
        (log/error "Stoping RFID listener...")
        (.stop reader)
        (when isConnected?
          (.disconnect reader))
        (.connect reader controler)
        (log/error "Starting RFID listener...")
        (.start reader)
        ))))

(defn start-server [sink chan-buf-size controler-name controler RfMode antennas cleanup-delta fastId d-id-re keepalive-ms]
    ;(PropertyConfigurator/configure "log4j.properties")
  (try
    (log/info (format "Starting RFID Server, controler: %s -> antenas: %s" controler antennas))
    (let [reader (doto (ImpinjReader.)
                   (.connect controler))
          features (.queryFeatureSet reader)

          N (.getGpoCount features)

          _ (log/info (format "Existen %d Gpos " N))

          settings (doto (.queryDefaultSettings reader)
                     (.setRfMode (int RfMode))
                     (.setSearchMode com.impinj.octane.SearchMode/DualTarget)
                     (.setSession 2))

          ; ordena al controlador a mandar un evento keepalive cada 3s
          ; si el controlador no nos puede enviar el evento 5 veces
          ; el controlador cierra la coneccion

          keepAlives (doto (.getKeepalives settings)
                       (.setEnabled true)
                       (.setPeriodInMs keepalive-ms)
                       (.setEnableLinkMonitorMode true)
                       (.setLinkDownThreshold 5))

          gpos (.getGpos settings)

          gpo1 (.getGpo gpos (short 1))

          _ (.setMode gpo1 GpoMode/Pulsed)
          _ (.setGpoPulseDurationMsec gpo1 200)

          ;los cambios a la configuracion del report se hacen por REFERENCIA !!
          ;y quedan reflejados dentro del settings  OOP !
          report (doto (.getReport settings)
                   (.setIncludeAntennaPortNumber true)
                   (.setIncludeChannel true)
                   (.setIncludeCrc true)
                   (.setIncludeDopplerFrequency true)
                   (.setIncludeFastId fastId)
                   (.setIncludeFirstSeenTime true)
                   (.setIncludeLastSeenTime true)
                   (.setIncludePeakRssi true)
                   (.setIncludePhaseAngle true)
                   (.setIncludeSeenCount true)
                   (.setMode com.impinj.octane.ReportMode/Individual))

          ;igual que report es por referencia GRACIAS OOP! jajaja
          d-antennas (doto (.getAntennas settings)
                       (.disableAll)
                       (.enableById (mapv #(short (first %)) antennas))
                       (configAntennas antennas))
          listener (create-listener chan-buf-size sink controler-name controler cleanup-delta d-id-re)
          keepAliveListener (create-keep-alive-listener controler-name controler)
          connectionLostListener (create-connection-lost-listener controler-name controler)]
      (.applySettings reader settings)
      (.setTagReportListener reader listener)
      (.setKeepaliveListener reader keepAliveListener)
      (.setConnectionLostListener reader connectionLostListener)
      (log/info "Starting RFID listener...")
      (.start reader))
    (catch Exception e
      (.printStackTrace e))))

(defmethod start-listener 'caudal.io.rfid-server
  [sink config]
  (let [{:keys [controler-name controler RfMode antennas cleanup-delta chan-buf-size fastId d-id-re keepalive-ms]
         :or {controler-name "name-undefined"
              chan-buf-size 10
              RfMode 1002 
              antennas [[1 true nil]]
              cleanup-delta 10000
              d-id-re ".*"
              keepalive-ms 60000}} (get-in config [:parameters])
        d-id-re (re-pattern d-id-re)]
    (log/info "Filtrando d-id con: " d-id-re)
    (start-server sink chan-buf-size controler-name controler RfMode antennas cleanup-delta fastId d-id-re keepalive-ms)))

