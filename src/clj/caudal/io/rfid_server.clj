(ns caudal.io.rfid-server
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :refer [chan go go-loop <! >! timeout >!! <!! put! alts! close!]]
            [caudal.streams.common :refer [start-listener]]
            [caudal.util.ns-util :refer [resolve&get-fn require-name-spaces]]
            [clojure.edn :as edn])
  (:import (com.impinj.octane ImpinjReader
                              TagReportListener
                              KeepaliveListener
                              ConnectionLostListener
                              GpoMode)))

; este atomo tiene un mapa que como llave controler  como valor
; tiene el partial de arranque listo para crear uno nuevo 
; el sink-chan asociado al listener (NO EL LISTENER) last-read y el 
; inactivity
; ej: {"192.168.10.31" {:ctor <ctor-fun> 
;                       :sink-chan <sink-chan>
;                       :inactivity <inactivity ms}}
(defonce listeners-agent (agent {}))

; en este atom registramos la ultima actividad de las lectoras solo eso
; {<controler1> <last-read>, <controler2> <last-read>,...}
(defonce activity-atom (atom {}))

(defn stop&disconnect [listener controler]
  (try
    (log/info "INACTIVITY: stopping " controler)
    (.stop listener)
    ;(Thread/sleep 2000)
    (log/info "INACTIVITY: stoped " controler)
    (log/info "INACTIVITY: disconnecting " controler)
    (.disconnect listener)
    ;(Thread/sleep 2000)
    (log/info "INACTIVITY: disconnected " controler)
    true
    (catch Exception e
      (log/error "INACTIVITY: disconnect error " controler)
      (log/error e)
      false)))

(defn create-new-listener [ctor controler]
  (try
    (log/info "INACTIVITY: Creating listener " controler)
    (let [el-par (ctor)]
      (log/info "INACTIVITY: Created listener " controler)
      el-par)
    (catch Exception e
      (log/error "INACTIVITY: Error creating listener " controler)
      (log/error e))))

(defn restart?-reduction [now result [controler V]]
  (log/info (pr-str [:restart?-reduction now controler V]))
  (let [{:keys [ctor sink-chan inactivity]} V
        last-read (get @activity-atom controler (System/currentTimeMillis))]
    (when (not inactivity)
      (log/error "INACTIVITY: se perdio el inactivity"))
    (log/info "INACTIVITY: " controler " delta " (- now last-read) "ms")
    (if (> (- now last-read) (or inactivity 900000))
      (if sink-chan
        (let [wait-chan (chan)
              _ (log/info "INACTIVITY: poniendo wait-chan en sink-chan " controler)
              _ (>!! sink-chan wait-chan)
              _ (log/info "INACTIVITY: esperando respuesta en wait-chan " controler)
              success? (<!! wait-chan)]
          (log/info "INACTIVITY: " controler " stopping & disconnecting success: " success?)
        ; cerrando  sink-chan
          (close! sink-chan)
          (swap! activity-atom controler (System/currentTimeMillis))
          
          (if-let [[_ new-sink-chan] (create-new-listener ctor controler)]
            (assoc result controler {:ctor ctor
                                     :sink-chan new-sink-chan
                                     :inactivity (or inactivity 900000)})
            (assoc result controler {:ctor ctor
                                     :sink-chan nil
                                     :inactivity (or inactivity 900000)})))
        (do
          (log/error "INACTIVITY: ERROR FATAL NO SE PUDO RECUPERAR CONECCION CON " controler)
          result))
      result)))

(defn internal_check4inactivity [listeners-map]
  (let [now (System/currentTimeMillis)]
    (reduce (partial restart?-reduction now)
            listeners-map
            listeners-map)))

(defn check4inactivity []
  (loop [n 0]
    (try
      (log/info "checking for inactivity in 60000 ms"  n)
      (Thread/sleep 60000)
      (log/info :check4inactivity n)
      (send listeners-agent internal_check4inactivity)
      (catch Exception e
        (log/error e)
        (Thread/sleep 60000)))
    (recur (inc n))))

(defonce inactivity-verifier-flag (atom false))

(defn start-inactivity-loop-if-not-started []
  (swap! inactivity-verifier-flag 
         (fn [flg] 
           (when-not flg
             (log/info "inactivity loop started")
             (future-call check4inactivity))
           true)))

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

(def timed-state (atom {}))

; la estructura de este timed-atom es asi:
#_{:controler-name
 {  :ids {} ; mapa con id->ts
    :id->evt {} ;mapa con id->evt original
    :delta 1000
    :removed #{}
    :last-update 0}}

(def default-init-state-Xcontroler {:ids {} ; mapa con id->ts
                                    :id->evt {} ;mapa con id->evt original
                                    :delta 1000
                                    :removed #{}
                                    :last-update 0})

(defn timed-cache-put [controler-name id evt]
  (swap!
   timed-state
   (fn [state]
     (let [now (System/currentTimeMillis)]
       (-> state
           (assoc-in [controler-name :ids id] now)
           (assoc-in [controler-name :id->evt id] evt)
           (assoc-in [controler-name :last-update] now))))))

(defn timed-cache-get&clear-removed [controler-name delta]
  (let [d-new-state ;{:keys [ids-removed]}
        (swap!
         timed-state
         (fn [controlers-state]
           (let [; sacamos la seccion dentro de este atomo de un controller
                 controler-state (get controlers-state controler-name default-init-state-Xcontroler)
                 {:keys [ids id->evt]} controler-state
                ;state (dissoc state :removed)
                 controler-state (assoc controler-state :removed #{})
                 now (System/currentTimeMillis)

                 ;{:keys [removed] :as new-state}
                 new-controler-state
                 (reduce
                  (fn [new-state [k k-ts]]
                    (let [k-ts (if (nil? k-ts) 0 k-ts)]
                      (if (> (- now k-ts) delta)
                        (-> new-state
                            ; generamos el set de eventos a remover
                            (update :removed #(conj % (id->evt k)))
                            ; lo quitamos de las lista ide id->ts y de id->evt
                            (update :ids #(dissoc % k))
                            (update :id->evt #(dissoc % k)))
                        new-state)))
                  controler-state
                  ids)
                 ;new-state (-> new-state
                               ;(assoc :removed #{})
                               ;(assoc :ids-removed removed))
                 ]
             (assoc controlers-state controler-name new-controler-state))))]
    ;(get-in d-new-state [controler-name :ids-removed])
    (get-in d-new-state [controler-name :removed])
    ))

(defn convert-tag2event [t]
  (let [isFastIdPresent (.isFastIdPresent t)
        d-id (if isFastIdPresent
               (->> t .getTid .toHexString)
               (->> t .getEpc .toHexString))
        evt (bean->map t)]
    (assoc evt :d-id d-id)))

; atomo que tiene un mapa de tag->chan este chan es el encargado en recibir
; eventos de este tag
(def tag->chan (atom {}))

;(defn process-tag [evt])

(defn get-more-frequent [evt-vec]
  (let [freqs (frequencies (map :AntennaPortNumber evt-vec))
        antena-group (group-by :AntennaPortNumber evt-vec)
        largest-anntena (reduce
                         (fn [evets-1 [_ evets]]
                           (if (> (count evets) (count evets-1))
                             evets
                             evets-1))
                         nil
                         antena-group)
        {:keys [d-id] :as evt-selected} (first largest-anntena)]
    (log/info (str "VOTING: " d-id " --> " (pr-str freqs)))
    evt-selected))

(defn make-evt-reduction [controler-name sink evt-vec]
  (let [selected-evt (get-more-frequent evt-vec)]
    (swap! tag->chan dissoc (:d-id selected-evt))
    (timed-cache-put controler-name (:d-id selected-evt) selected-evt)
    (sink selected-evt)))

(defmulti start-tag-reader-chan (fn [_ conf _ _]
                                  (log/info (str "TAG.0.9 " conf))
                                  (:type conf)) :default "default")

; gana el la antena con mas lecturas
(defmethod start-tag-reader-chan :count [controler-name {:keys [delta] :or {delta 1000}} sink c]
  (let [ts-end (+ delta (System/currentTimeMillis))]
    (go-loop [ts (System/currentTimeMillis) reduction []] ; reduccion va a tener todos los eventos de este tag 
      (log/info (str "TAG.2 reduction:" (count reduction) " - " (- ts-end ts)))
      (let [[evt ch] (alts! [c (timeout (- ts-end ts))])]
        (log/info (str "TAG.2.1 " (= c ch) " " (:d-id evt) " --> " (count reduction)))
        (if (= ch c)
          (if-not evt
            (make-evt-reduction controler-name sink reduction)
            (recur (System/currentTimeMillis) (conj reduction evt)))
          (do
            (log/info "TAG.2.2 closing chan")
            (close! c)
            (recur 0 reduction) ; con este 0 garantizamos que el alts! regrese por el chan y no el timeout
            ))))))

(defn make-evt-selected [controler-name sink selected-evt]
  (swap! tag->chan dissoc (:d-id selected-evt))
  (timed-cache-put controler-name (:d-id selected-evt) selected-evt)
  (sink selected-evt))

; con :last gana el ultimo leido
(defmethod start-tag-reader-chan :last [controler-name {:keys [delta] :or {delta 1000}} sink c]
  (go-loop [last-evt nil] ; last-evt va a tener el último evento recibido
    (log/info "TAG.1.0 ")
    (let [[evt ch] (alts! [c (timeout delta)])]
      (log/info (str "TAG.1.1 " (= c ch) " " evt))
      (if (= ch c)
        (if-not evt
          (make-evt-selected controler-name sink last-evt)
          (recur evt)) ; se renueva la espera de 1s y se guarda el último
        (do
          (log/info "TAG.1.2 ")
          (close! c)
          (recur last-evt) ; como c está cerrado el alts! termina con nil inmediatamente
          )))))

; con :max gana el que tenga mejor PeakRssiInDb una vez que pase 1 segundo sin lecturas se toma el ultimo leido
(defmethod start-tag-reader-chan :max [controler-name {:keys [delta] :or {delta 1000}} sink c]
  (go-loop [max-evt nil] ; rmax-evt va a tener el evento con PeakRssiInDb más grande (menos negativo)
    (log/info "TAG.1.0 ")
    (let [[evt ch] (alts! [c (timeout delta)])]
      (log/info (str "TAG.1.1 " (= c ch) " " evt))
      (if (= ch c)
        (if-not evt
          (make-evt-selected controler-name sink max-evt)
          (recur (if (> (:PeakRssiInDb evt) (:PeakRssiInDb max-evt -1000)) evt max-evt))) ; se renueva la espera de 1s y se guarda el último
        (do
          (log/info "TAG.1.2 ")
          (close! c)
          (recur max-evt) ; como c está cerrado el alts! termina con nil inmediatamente
          )))))

; obtienes o creas el chan de este tag ojo tambien deja un go-loop para eliminarlo al cierre
(defn get-tag-chan [d-id]
  (let [[c created?] (-> (swap! tag->chan
                                (fn [tag->chan-map]
                                  (let [[c _ :as info] (get tag->chan-map d-id)]
                                    (if info
                                      (assoc tag->chan-map d-id [c false])
                                      (assoc tag->chan-map d-id [(chan) true]))))) ; true indica recien creado
                         (get d-id))]
    [c created?]))

; en el siguiente metodo voy a implementar que se almacenen los tags a enviar de manera
; que se posponga el envio un tiempo determinado, de forma que a lo mejor llegan más de 
; una lectura del mismo tag e incluso en diferente antena, en el happy path pasado el tiempo 
; se envia al sink (el flujo normal del caudal) el evento, una vez ya enviado ahora si ya no
; se manda, es decir al enviar el seleccionado se hace el timed-cache-put
(defn send-if-not-in-cache [controler-name d-id-re tag-policy sink {:keys [d-id] :as evt}]
  (log/debug :re-matches d-id-re d-id (re-matches d-id-re d-id))
  (if (re-matches d-id-re d-id)
    (let [tag-exists? (get-in @timed-state [:ids d-id])]
      ;(timed-cache-put d-id evt)
      ;(sink evt)
      (log/info (str "TAG.0 " (pr-str tag-exists?)))
      (if-not tag-exists?
        (let [_ (log/info "TAG.0.0")
              [tag-chan created?] (get-tag-chan d-id)
              _ (log/info (str "TAG.0.2 " tag-chan "  " created?))]
          (when created?
            (start-tag-reader-chan controler-name tag-policy sink tag-chan)) ; esto inicia el loop de lectura de este chan especial para este tag 
          (log/info (str "TAG.0.3 " d-id))
          (>!! tag-chan evt)
          (log/info (str "TAG.0.4 " d-id #_(pr-str evt))))
        (timed-cache-put controler-name d-id evt) ; este else es importante para por si se queda ahi el tag
                                   ; con esto renuevas el que ya no salga     
        ))
    (log/debug (format "Dropping tag: %s" (pr-str evt)))))

(defn start-tag2sink-remove-duplicates [d-reader controler controler-name d-id-re sink tag-policy sink-chan]
  (go 
    (let [;_ (log/info "INACTIVITY: (1) esperando en sink-chan " controler)
          e (<! sink-chan)
          ;_ (log/info "INACTIVITY: (1) salio del sink-chan :" controler e)
          ]
      (loop [e e] ; RfDopplerFrequency es string y negativo significa que se aleja
        (if e
          (do
        ; te piden que des stop y disconnect y respondas al terminar
            (if (instance? clojure.core.async.impl.channels.ManyToManyChannel e)
              (let [_ (log/info "INACTIVITY: llego un chan " controler)
                    success? (stop&disconnect d-reader controler)]
                (log/info "INACTIVITY: disconected success: " success? " " controler)
                (>! e success?)) 

        ; es un evento normal
              (let [{:keys [event RfDopplerFrequency]} e
                    {:keys [direction] :or {direction :none}} tag-policy
                    use-it (condp = direction
                             :approaching (> (Double/parseDouble RfDopplerFrequency) 0) 
                             :receding    (<= (Double/parseDouble RfDopplerFrequency) 0)
                             true)]
                (if  use-it
                  (condp = event
                    :ON_TAG_READ (send-if-not-in-cache controler-name d-id-re tag-policy sink e)
                    :ON_TAG_REMOVED (sink e))
                  (log/info (str  "TAG.00 filtrando evento: " direction event)))))
            ;(log/info "INACTIVITY: (2) esperando en sink-chan " controler)
            (let [e (<! sink-chan)]
              ;(log/info "INACTIVITY: (2) salio del sink-chan :" controler e)
              (recur e)))
      ; te cerraron el chan no haces nada 
          (log/info "INACTIVITY: sink-chan closed " controler))))))

(defn start-timed-cache-cleanup [controler-name delta-loop sink sink-chan]
  (go-loop [removed-now (timed-cache-get&clear-removed controler-name delta-loop)]
    #_(when (seq removed-now)
        (log/info (pr-str [:removig-tags (mapv :d-id removed-now)])))
    (doseq [{:keys [d-id] :as evt} removed-now]
      (let [removed-event (merge evt {:event :ON_TAG_REMOVED
                                      :rfid-ts (System/currentTimeMillis)})]
        (log/info (pr-str [:removing-tag controler-name " " sink-chan " " removed-event]))
        ;(>! sink-chan removed-event)
        (sink removed-event)
        ))
    (<! (timeout delta-loop))
    (recur (timed-cache-get&clear-removed controler-name delta-loop))))

(defn t->evt [evt-key controler-name controler tagORevt]
  (try
    (let [extra {:event evt-key
                 :controler-name controler-name
                 :controler controler
                 :rfid-ts (System/currentTimeMillis)}
          evt (cond  
                (map? tagORevt) (merge tagORevt extra)
                (instance? clojure.core.async.impl.channels.ManyToManyChannel tagORevt) tagORevt
                :else (merge (convert-tag2event tagORevt) extra))]
      evt)
    (catch Exception e
      (.printStackTrace e)
      {:controler-name controler-name
       :controler controler
       :event :ON_TAG_ERROR
       :msg (.getMessage e)
       :rfid-ts (System/currentTimeMillis)})))

(defn put-event-in-sink [activity-atom controler sink-chan t]
  (try
    ; actualizamos que este leyo tag ahora
    (swap!
     activity-atom
     assoc controler (System/currentTimeMillis))
    (>!! sink-chan t) ; la trasformacion la hace el trasducer
    (catch Exception e
      (log/error e)
      (.printStackTrace e)
      false)))

(defn create-listener [d-reader chan-buf-size sink controler-name controler cleanup-delta d-id-re tag-policy]
  (let [sink-chan (chan chan-buf-size
                        (map (partial t->evt :ON_TAG_READ controler-name controler)))]
    (log/warn (str "create-listener " controler-name " " controler " " sink-chan))
    (start-tag2sink-remove-duplicates d-reader controler controler-name d-id-re sink tag-policy sink-chan)
    (start-timed-cache-cleanup controler-name cleanup-delta sink sink-chan)
    [(reify TagReportListener
        (onTagReported [_ reader report]
          (let [tags (.getTags report)]
            (loop [[t & rest] tags]
              (when (and t (put-event-in-sink activity-atom controler sink-chan t))
                (recur rest)))))) sink-chan]))

(defn create-keep-alive-listener [controler-name controler]
  (reify KeepaliveListener
    (onKeepalive [_ reader event]
      (let [e (t->evt :ON_KEEP_ALIVE controler-name controler {})]
        (log/info e)))))

(declare start-server)

(defn create-reconnect2antenna-channel []
  (let [reconnect-chan (chan 10)]
    (go-loop [[sink chan-buf-size controler-name controler RfMode antennas
               cleanup-delta fastId d-id-re keepalive-ms tag-policy] (<! reconnect-chan)]
      (log/error "Reconnecting reader " controler-name)
      (when-not (start-server sink chan-buf-size controler-name controler RfMode antennas
                              cleanup-delta fastId d-id-re keepalive-ms tag-policy)
        (go
          (log/error "Reconeccion no exitosa, reintentando el 60s")
          (<! (timeout 60000))
          (>! reconnect-chan [[sink chan-buf-size controler-name controler RfMode antennas
                               cleanup-delta fastId d-id-re keepalive-ms tag-policy]])))
      (recur (<! reconnect-chan)))
    reconnect-chan))

(def reconnect-chan (create-reconnect2antenna-channel))

(defn create-connection-lost-listener [sink chan-buf-size controler-name controler RfMode antennas
                                       cleanup-delta fastId d-id-re keepalive-ms tag-policy]
  (reify ConnectionLostListener
    (onConnectionLost [_ reader]
      (try
        (let [isConnected? (.isConnected reader)
              e (t->evt :ON_CONNECTION_LOST controler-name controler {:connected isConnected?})]
          (log/error e)
          (put! reconnect-chan [sink chan-buf-size controler-name controler RfMode antennas
                                cleanup-delta fastId d-id-re keepalive-ms tag-policy]))
        (catch Throwable t
          (log/error t))))))

(defn start-server [sink chan-buf-size controler-name controler RfMode antennas
                    cleanup-delta fastId d-id-re keepalive-ms tag-policy]
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
          [listener sink-chan] (create-listener reader chan-buf-size sink controler-name controler cleanup-delta d-id-re tag-policy)
          keepAliveListener (create-keep-alive-listener controler-name controler)
          connectionLostListener (create-connection-lost-listener sink chan-buf-size controler-name controler RfMode antennas
                                                                  cleanup-delta fastId d-id-re keepalive-ms tag-policy)]
      (.applySettings reader settings)
      (.setTagReportListener reader listener)
      (.setKeepaliveListener reader keepAliveListener)
      (.setConnectionLostListener reader connectionLostListener)
      (log/info "Starting RFID listener...")
      (.start reader)
      [reader sink-chan])
    (catch Exception e
      (log/error e)
      (.printStackTrace e))))

(defmethod start-listener 'caudal.io.rfid-server
  [sink config]
  (let [{:keys [controler-name controler RfMode antennas
                cleanup-delta chan-buf-size
                fastId d-id-re keepalive-ms
                tag-policy inactivity]
         :or {controler-name "name-undefined"
              chan-buf-size 10
              RfMode 1002
              antennas [[1 true nil]]
              cleanup-delta 10000
              d-id-re ".*"
              tag-policy {:type :max :delta 3000}
              keepalive-ms 60000
              inactivity (* 15 60 1000)}} (get-in config [:parameters])
        d-id-re (re-pattern d-id-re)
        _ (log/info "Filtrando d-id con: " d-id-re)
        d-starter (partial start-server sink chan-buf-size controler-name controler RfMode antennas cleanup-delta fastId d-id-re keepalive-ms tag-policy)
        [reader sink-chan] (d-starter)]
    (start-inactivity-loop-if-not-started)
    (swap! activity-atom assoc controler (System/currentTimeMillis))
    (send listeners-agent assoc controler {:ctor d-starter
                                           :sink-chan sink-chan
                                           :inactivity inactivity})
    [reader sink-chan]
    ;(start-server sink chan-buf-size controler-name controler RfMode antennas cleanup-delta fastId d-id-re keepalive-ms tag-policy)
    ))

