(ns plc-controler
  (:require
   [constants :refer [origin plantId HTTP-TIMEOUT CAUDAL_CONFIG EXIT-FACE-CAMERAS]]
   [clojure.core.async :refer [chan <! >! <!! >!! timeout put! take!
                               go go-loop close! pipeline-async]]
   [defun.core :refer [defun]]
   [clojure.core.match :refer [match]]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clojure.tools.logging :as log]
   [clojure.pprint :as pp]
   [clojure.string :as S]
   [cheshire.core :refer [generate-string parse-string]]
   [send-events :as SE]
   [aleph.http :as http])
  (:import
   (java.util Random UUID)
   (com.ghgande.j2mod.modbus.facade ModbusTCPMaster)))


(defn create-uuid [] (str (UUID/randomUUID)))

(defn status-ok? [status]
  (and (>= status 200)
       (< status 300)))


; temporalmente aqui en lugar de send-events
(defn do-get-async
  ([url path authorizations]
   (do-get-async url path authorizations 3 5000))

  ([url path authorizations max-retries delay-ms]
   (go-loop [retries 0]
     (let [d-http-prams (cond-> {:pool SE/http-pool
                                 :content-type :json
                                 :pool-timeout HTTP-TIMEOUT
                                 :connection-timeout HTTP-TIMEOUT
                                 :request-timeout HTTP-TIMEOUT
                                 :read-timeout HTTP-TIMEOUT
                                 :throw-exceptions? false}
                          authorizations (merge {:headers {"Authorizations" authorizations}}))
           {:keys [status] :as r} (try
                                    @(http/get (str url path) d-http-prams)
                                    (catch Exception e
                                      (log/error e)
                                      {:status 500 :message (format "Exception making get to %s %s" (str url path) (.getMessage e))}))]
       (log/info (format "http get %s retry: %d status: %s" (str url path) retries status))
       (if (and (not (status-ok? status))
                (< retries max-retries))
         (do
           (<! (timeout delay-ms))
           (recur (inc retries)))
         r)))))

; A continuacion vamos a definir la funcion de apertura de plumas 
; unidirecional tomando en cuanta los valores de las banderas actuales 
; del PLC

; existe un atomo que contiene como llave el nombre
; de la camara y como valor tiene asociado un mapa con información
; del plc asociado con esa camara (ip, bidirectional/unidirecional, etc)

(defonce url-base "https://apiwlc-dev.payfri.app:7282")
#_(defonce url-base "https://localhost:7282")


(defonce paths {:plate "/api/Vehiculo/getWhiteList?sede=leon"
                :qr "/api/QR/getQRWhiteList?sede=leon"
                :face "/api/Face/getWhiteList?estado=%s"})

; atomo con lista blancas
; {:face #{ lista de uuids de reconocimiento facial}
;  :qr #{ lista de valor de qrs}
;  :plate {"camera" #{ lista de placas validas para esta camara}}
; }

(defonce white-lists (atom {:started false
                            :in nil
                            :out nil}))

(defonce delta-refresh {:face (* 1 60 1000)
                        :plate (* 1 60 1000)
                        :qr (* 15 1000)})

(defn load-face-white-list []
  (go-loop []
    (let [{:keys [status body message]} (<! (do-get-async url-base (format (:face paths) plantId) nil))]
      (log/info (format "whilte-list-loaded :face status: %s" status))
      (when (status-ok? status)
        (try
          (let [{:keys [data]} (-> body slurp (parse-string true))
                d-data (into #{} (map :personaReconocimientoGuid data))]
            (swap! white-lists assoc :face d-data))
          (catch Exception e
            (log/error "Error parsing white-liste-response :face")
            (log/error e))))
      (<! (timeout (:face delta-refresh)))
      (recur))))

(defn load-qr-white-list []
  (go-loop []
    (let [{:keys [status body message]} (<! (do-get-async url-base (:qr paths)  nil))]
      (log/info (format "whilte-list-loaded :qr status: %s" status))
      (when (status-ok? status)
        (try
          (let [{:keys [data]} (-> body slurp (parse-string true))
                d-data (into #{} data)]
            (swap! white-lists assoc :qr d-data))
          (catch Exception e
            (log/error "Error parsing white-liste-response :qr")
            (log/error e))))
      (<! (timeout (:qr delta-refresh)))
      (recur))))

(defn load-plate-white-list []
  (go-loop []
    (let [{:keys [status body message]} (<! (do-get-async url-base (:plate paths)  nil))]
      (log/info (format "whilte-list-loaded :plate status: %s" status))
      (when (status-ok? status)
        (try
          (let [{:keys [data]} (-> body slurp (parse-string true))
                camera->plate (reduce (fn [m {:keys [ip blancas negras]}]
                                        (assoc m (format "%s" ip)
                                               {:blancas (into #{} (map #(S/upper-case (S/replace % #"\s+" "")) blancas))
                                                :negras (into #{} (map #(S/upper-case (S/replace % #"\s+" "")) negras))}))
                                      {}
                                      data)]
            (log/warn (pr-str [:load-plate-white-list camera->plate]))
            (swap! white-lists assoc :plate camera->plate))
          (catch Exception e
            (log/error "Error parsing white-liste-response :plate")
            (log/error e))))
      (<! (timeout (:plate delta-refresh)))
      (recur))))

(defn start-loading-white-lists! []
  (load-qr-white-list)
  (load-face-white-list)
  (load-plate-white-list))

(defn valid-face? [face-uuid]
  (contains? (:face @white-lists) (S/lower-case face-uuid)))

(defn valid-qr? [qr-uuid]
  (contains? (:qr @white-lists) (S/lower-case qr-uuid)))

(defn valid-plate? [camera plate]
  (let [camera->plates (:plate @white-lists)
        {:keys [blancas negras] :as plates} (get camera->plates camera)]
    (if-not plates
      (do
        (log/error (format "No existen listas para camara %s" camera))
        false)
      (let [match-val [(nil? (seq negras)) (nil? (seq blancas)) (contains? negras plate) (contains? blancas plate)]
            grant-access (match match-val
                           [true  true  _     _]         true      ;no hay ni blancas ni negras -> se da el acceso
                           [false  _   true   _]         false     ;hay negras y el plate está en las negras -> se rechaza
                           [false true false  _]         true      ;solo hay negras y plate no está -> se da acceso
                           [true  false _     in-blanca] in-blanca ;solo hay blanca, la blanca decide el acceso
                           [false false false in-blanca] in-blanca)] ;hay negras y blancas y no esta en negras, entonces lo que diga blancas
        
        (log/info :valida-plate? camera plate grant-access)
        grant-access)))) ; por ahora dejamos pasar a todas las placas

(defonce camera->plc-info
  (let [camera-plc-config (io/file (str CAUDAL_CONFIG "/camera-plc-config.edn"))
        _ (log/info :CAMERA-PLC-CONFIG (.getCanonicalPath camera-plc-config) :exists (.exists camera-plc-config))
        d-config (when (.exists camera-plc-config)
                   (-> camera-plc-config
                       slurp
                       edn/read-string))]
    (log/info :camera->plc-info (pr-str d-config))
    d-config))

; este intern-plc-atom de este closhure
(let [plc-instance-atom (atom {})]
  (defn new-plc
    ([ip] (new-plc ip 502))
    ([ip port & {:keys [force]}]
     (try
       (let [llave (format "%s:%s" ip port)
             plc (get @plc-instance-atom llave)]
         (when (and plc force)
           (try
             (.disconnect plc)
             (catch Throwable t)))
         (if (and plc (not force))
           plc
           (let [plc (ModbusTCPMaster. ip port)]
             (.setReconnecting plc true) ; true-> nueva coneccion cada transaction
             (.connect plc)
             (log/info (format "PLC %s:%s es connected? %s" ip port (.isConnected plc)))
             (swap! plc-instance-atom assoc llave plc)
             plc)))
       (catch Exception e
         (log/error e)
         nil)))))

(defn read-plc-status [plc {:keys [base-addr n-marks]}]
  (try
    (log/debug "read-plc-status " base-addr n-marks plc)
    (let [marcas (.readCoils plc base-addr n-marks)
          contadores (.readMultipleRegisters plc 0 n-marks)] ;.readMultipleRegisters readMultipleRegisters (.readInputRegisters este da ceros)
      {:marcas0 (into
                 []
                 (for [i (range (.size marcas))]
                   (.getBit marcas i)))
       :contadores0 (mapv #(.getValue %) contadores)})
    (catch Exception e
      (log/error e))))

(defn create-init-plc-state [plc-info & {:keys [force]}]
  (let [plc (new-plc (:ip plc-info) (:port plc-info) :force force)
        _ (log/info "---->" (:ip plc-info) " -- " (:port plc-info) " -- " plc)
        plc-status (read-plc-status plc plc-info)]
    (if plc-status
      (merge plc-info
             {:plc plc
              :event nil
              :drops 0
              :created (System/currentTimeMillis)
              :last-upd 0
              :retry-delta 550
              :pulse-delta 500
              :marcas1 nil
              :marcas0 nil}
             plc-status)
      (log/error (format "problemas al crear plc-state %s" (:ip plc-info))))))


(defn init-plc-map [camera->plc-info]
  (reduce (fn [result [camera plc-info]]
            (if (:active plc-info)
              (assoc result camera (create-init-plc-state plc-info))
              result))
          {}
          camera->plc-info))

(defonce plc-atom (atom nil))

(defn initialize-plc-infra []
  (reset! plc-atom (init-plc-map camera->plc-info)))

(defn recreate-plc
  "Regenera un objeto PLC para la camara"
  [camera]
  (when-let [old-plc (@plc-atom camera)]
    (log/info (format "closing connection with %s -> %s:%s" camera (:ip old-plc) (:port old-plc)))
    (.disconnect (:plc old-plc)))
  (let [plc-info (camera->plc-info camera)
        plc-state (create-init-plc-state plc-info :force true)]
    (swap! plc-atom assoc camera plc-state)))

(defn drop-event
  "Se tira el evento y se incrementa el drops en el atomo de plc-state"
  [camera value drops]
  (log/info (format "dropping event %s val: %s drops: %s" camera value drops))
  (swap! plc-atom update-in [camera :drops] inc))

(defn new-state [plc-map {:keys [camera] :as event}]
  (let [{:keys [plc marcas0 contadores0] :as old-info} (plc-map camera)
        new-info (merge
                  old-info
                  {:last-upd (System/currentTimeMillis)}
                  (read-plc-status plc old-info)
                  {:event event
                   :marcas1 marcas0
                   :contadores1 contadores0
                   :drops 0})]
    (assoc plc-map camera new-info)))

(defn plc2new-state
  "Trancisiona al nuevo plc-state actualizando el event y todo lo demás"
  [event]
  (swap! plc-atom new-state event))

(defonce processing-channels
  (atom {:exec-open (chan)
         :exec-semaphore (chan)
         :proc-result (chan)
         :retry (chan)
         :drop (chan)}))

(defun select-strategie
  
  ; el bidireccional y está cerrada y no hay alguien en el sensor de masa y M15 está arriba -> es una entrada
  ;[true true [false false true false false false true false false false true false false false true]]
  ;[true true [false false true false false false true false false false true false false false true]]
  ([true true [_     false true false false  _    true  _    _     _     _    _     false  _    true]] [:exec-open false])
  
  ; los siguentes dos casos aun no me quedan claros !!!
  ; este caso es cuando llega uno a entrar a la 219 y se encuentra con la pluma arriba porque alguien la dejó abierta
  ; asi que mandaremos el evento a la plataforma como open pero SIN mandar el pulso al PLC el true indica skip-pulse
  ; está entrando alguien pero se encuentra la pluma arriba y el M7 sensor2 no tiene a nadie esto indica que se quedó abrierta 
  ; asi que mandamos el evento sin mandar un pulso de apertura
               ;M1    M2    M3    M4    M5    M6    M7    M8    M9   M10  M11  M12   M13   M14   M15
  ([true true [false false false true   _   false  true false false true true true false false true]] [:exec-open true])

  ; está abierta y el sensor de masa 2 tiene a alguien, por lo tanto esperate a que cierre
  ([true true [false false false true   _   false  false false false true true true false false true]] [:retry :espera 5])
  
    ; este vector tien [M1 M2 M3 .. M13 M14] para temas de documentación aunque
    ; los indices del vector si son 0..13
    ; M2 = true pluma en paro
  ([true _ [_ true _ _ _ _ _ _ _ _ _ _ false _ _]] [:proc-result :pluma-en-paro])

  ; pluma está reiniciando esperar 250ms y reintentar
  ([true _ [_ _ _ _ _ _ _ _ _ _ _ _ true _ _]] [:retry :reiniciando 100])

  ; M2: false, M3: true, M4: false, M5: false, M13: false (se manda la abrir)
  ([true false [_ false true false false _ _ _ _ _ _ _ false _ _]] [:exec-open false])

  ; M2: false pero en estado no listo para abrir, esperar 250 ms y reintentar YA NO solo mandamos el pulso y que el PLC haga lo correcto
  ([true false [_ false _ _ _ _ _ _ _ _ _ _ false _ _]] [:exec-open false]) ;[:retry :espera 5]

  ([true true [_     false true  false false _     false _    _     _    _    _    false _    true]] [:drop])

  ;             M1    M2    M3    M4    M5    M6    M7   M8    M9   M10  M11  M12   M13   M14   M15
  ;           [false false false true  true  false true false false true true true false false true]
  ([true true [_     _     _     _     _     _     _    _     _     _    _    _    false _     false]] [:drop])

  ; M1     M2   M3   M4    M5    M6    M7   M8    M9    M10   M11  M12   M13   M14   M15
  ;false false true false false false true false false false true false false false true
  ;false false true false false false true false false false true false false false true
  ;_     false true false false _     true _     _     _     _    _     false  _    true
  
  ([false _ [_ _ _ _ _ _ _ _ _ _ _ _ _ _ _]] [:proc-result :rechazado]) ; no está en la lista blanca !!!

  ([& params] (log/warn (pr-str [:select-strategie params])) [:proc-result :error]))

; ojo el M7 está en true por default en las bidireccionales, cuando baja a false es que va 
; a abrir, por el otro lado en este caso se tendria que detener las aparturas hasta 
; regresar a:
; _ false true _ _ _ true _ _ _ _ _ false _

; este metodo me parece redundante si no está definidi en cameras-plc-config.edn 
; no entra a operate-plc
(defn is-under-plc-control? [eventName type]
  (and (= (name eventName) "ON_CHECKPOINT")
       (#{"FACE" "VEHICLE" "QR"} (name type))))

(defn poster [platform-url relevante]
  (try
    (SE/poster platform-url relevante)
    (catch Exception e
      (.printStackTrace e)
      (log/error e))))

(defn log-events [prefix to-send]
  (doseq [{:keys [type camera accuracy value in-white-list]} to-send]
    (log/info (format "%s PLC\t %-10s %-10s %9.4f %s [%s]"
                      prefix
                      type camera
                      (float (or accuracy 1.0)) value
                      in-white-list))))

(defn operate-plc
  "Esta función es la que llamará el caudal cuando tenga una placa/cara y desee abrir"
  ([new-event]
   (operate-plc new-event 0))
  ([{:keys [camera value aiTime eventName type in-white-list] :as new-event} retry-cnt] ; este 'new-event' es un relevante ya con el campo in-white-list!!
   (let [{:keys [event drops drops-max]} (@plc-atom camera)
         {old-value :value
          old-aiTime :aiTime
          :or {old-value "" old-aiTime 0}} event
         under-plc-control? (is-under-plc-control? eventName type)]
     (log/debug (pr-str [:under-plc-control? under-plc-control? eventName type]))
     (if-not under-plc-control?
       (doseq [platform-url (SE/get-platform-urls)]
         (SE/poster platform-url new-event))
     ; conciderar en la decision de drop el tiempo entre el evento pasado y el actual
     ; porque no queremos que el ultimo del dia anterior si es el primero del dias siguiente
     ; se le de drop a sus eventos (o algo asi jejeje) el aiTime del evento se generó
     ; cuando se creo el evento a enviar en el caudal
       (do
         (log/debug [value old-value retry-cnt aiTime old-aiTime drops drops-max])
         (if (and (= value old-value)
                  false ; ya no vamos a hacer drop
                  ;(= retry-cnt 0) ; NO si es un retry debemos reintentar por eso solo entramos al drop con 0
                  ;                  se comentó porque pasó que abrua la pluma y al pasar el coche se abria de nuevo??
                  (< (abs (- aiTime old-aiTime)) 10000) ; solo existe drop si no ha pasado menos de 10s
                  (< drops drops-max))
           (drop-event camera value drops) ; si estamos repitiendo lectura (misma placa) lo tiramos
           (let [{:keys [marcas0 contadores0 vehicular? bidireccional? semaforo?] :as plc-state} ((plc2new-state new-event) camera)
                 ; vehicular? viene de los PLCs no de las camaras
                 [chan-key & params] (cond
                                       semaforo?
                                       (if in-white-list
                                         [:exec-semaphore :verde] ; abrir semaforo (poner verde)
                                         [:exec-semaphore :rojo]) ; no abrir semaforo (poner rojo)
                                       
                                       (and vehicular? (= type :VEHICLE)) ; POR AHORA EL WHITE LIST ESTÁ DESHABILITADO por eso el "or true"
                                       (select-strategie in-white-list bidireccional? marcas0)

                                       (and (not vehicular?) (= type :FACE)) ; EN FACE AHORA ES EN REALIDAD UN BLACK LIST el false de [:exec-open false] es skip-pulse
                                       (if (not in-white-list) [:exec-open false] [:proc-result :rechazado])

                                       (and (not vehicular?) (= type :QR))
                                       (if in-white-list [:exec-open false] [:proc-result :rechazado])

                                       :else
                                       (log/error (format "CONFIGURACION INCORRECTA vehicular?: %s type: %s" vehicular? type)))
                 d-chan (chan-key @processing-channels)]
             (log/info (pr-str [:operate-plc-data camera vehicular? in-white-list bidireccional? marcas0 contadores0]))
             (log/debug :operate-plc chan-key params :retry-cnt retry-cnt)
             (>!! d-chan (concat [plc-state] params [retry-cnt])))))))))

#_(if-not vehicular? ; si es facial o de qr solo se manda el pulso no hay logica de control del PLC
  (if (or in-white-list (= :FACE type)) [:exec-open false] [:proc-result :rechazado]) ; (= :FACE type)
  (select-strategie (or (= :VEHICLE type) in-white-list) bidireccional? marcas0))

(defn in-white-list? [result {:keys [value type camera] :as relevante}]
  (log/debug [:in-white-list? type camera value (valid-face? value)])
  (cond
    (= (name type) "VEHICLE") (conj result (assoc relevante :in-white-list (valid-plate? camera value)))
    (= (name type) "FACE")    (conj result (assoc relevante :in-white-list (valid-face? value)))
    (= (name type) "QR")      (conj result (assoc relevante :in-white-list (or (contains? EXIT-FACE-CAMERAS camera) (valid-qr? value))))
    :else                     (conj result (assoc relevante :in-white-list false))))

(defn add-white-listed [{:keys [send] :as event}]
  (let [send (reduce in-white-list? [] send)]
    (assoc event :send send)))

; este es el send-events del plc controles, hace lo del plc y en su momento
; llama al send-events anterior !! para informar a la plataforma de ZZ
(defn send-events [event]
  (let [{:keys [send camera plantId origin] :as event} (add-white-listed event)]
    (try
      (if-not (get-in camera->plc-info [camera :active] false) ; si no está activo o NO esta en configutacion .edn send-events normal
        (SE/send-events event)
        (do
          (log/debug (format "PLC send-events: %d evts %s %s" (count send) plantId origin))
          (log-events "TAB-1" send)
          (log/debug (pr-str (mapv #(dissoc % :clip :image :uuid :eventName :ai_ts0 :ai_ts1 :plantId :aiTime :origin) send)))
          (when-let [to-send (seq (->> send
                                       (filter identity)
                                       (map #(assoc % :origin origin :plantId plantId))))]
            (log/debug (pr-str [:to-send (count to-send)]))
            (doseq [evt to-send]
              (operate-plc evt 0)))
          event))
      (catch Exception e
        (.printStackTrace e)
        (log/error e)
        event))))

(defn post-event2platform [event plc-info]
  (future
    (loop [[platform-url & next] (SE/get-platform-urls)]
      (SE/poster platform-url (assoc event :plc_info plc-info))
      (when next
        (recur next)))))

(defun process-result
  ([plc-status :plc-error-pulse _]
   (let [{:keys [plc event]} plc-status
         {:keys [camera value type]} event]
   ; ¿qué hacemos cuando tenemos este error?
   ; informar que el plc anda malito ???
     (when-not plc  ; no estoy si solo cuando plc es nil
       (log/error (format "recreating plc controler for camera %s" camera))
       (recreate-plc camera))
     (log/warn (format "procesa plc-error-pulse %s %s %s" camera type value))
     (post-event2platform event "plc-inaccesible")))

  ([plc-status :rechazado _]
   (let [{:keys [event]} plc-status
         {:keys [camera value type]} event]
     ; se informa al sistema ¿send-event? que se rechazo una apertura...
     (log/warn (format "procesa rechazado %s %s %s" camera type value))
     (post-event2platform event "rechazado")))

  ([plc-status :open-sent _]
   (let [{:keys [event]} plc-status
         {:keys [camera value type]} event]
   ; se informa al sistema ¿send-event? que se dió una apertura...
     (log/info (format "procesa open-sent %s %s %s" camera type value))
     (post-event2platform event "plc-aperturado")))
  
  ([plc-status :value-setted _]
   (let [{:keys [event]} plc-status
         {:keys [camera value type]} event]
     ; se informa al sistema ¿send-event? que se dió una apertura...
     (log/info (format "totem: procesa open-sent %s %s %s" camera type value))
     (post-event2platform event "semapforo-operado")))
   
  ([plc-status :drop _]
   (let [{:keys [event]} plc-status
         {:keys [camera value type]} event]
     ; se informa al sistema ¿send-event? que se dió una apertura...
     (log/warn (format "procesa drop %s %s %s" camera type value))))

  ([plc-status :retry-cnt-exeeded :espera]
   (let [{:keys [event]} plc-status
         {:keys [camera value type]} event]
   ; seguramente en este caso ya no hacemos nada? y esperamos el siguiente eventpo del python para reintentar
     (log/warn (format "procesa retry-cnt-exeeded :espera %s %s %s" camera type value))
     (post-event2platform event "plc-reintento-excedido")))

  ([plc-status :retry-cnt-exeeded :reiniciando]
   (let [{:keys [event]} plc-status
         {:keys [camera value type]} event]
   ; informar que el plc está tardando en reiniciar??
     (log/warn (format "procesa retry-cnt-exeeded :reiniciando %s %s %s" camera type value))
     (post-event2platform event "plc-reiniciando")))

  ([plc-status :pluma-en-paro _]
   (let [{:keys [event]} plc-status
         {:keys [camera value type]} event]
   ; informar al sistema que llegó alguien pero que el PLC estaba en paro
     (log/info (format "procesa pluma-en-paro %s %s %s" camera type value))
     (post-event2platform event "plc-en-paro")))
  
  ([plc-status :error _]
   (log/error "se generó un error en el strategie ver WARN anterior"))
  
  ([& params] (log/warn (pr-str [:process-result params]))))

(defn send-pulse [plc pulse-delta & {:keys [open-offset base-addr]
                                     :or {open-offset 0 base-addr 8256}}]
  (go
    (try
      (log/info (format "PLC pulse up %d %d" base-addr open-offset))
      (.writeCoil plc 0 (+ base-addr open-offset) true) ; 0 == unitid
      (<! (timeout pulse-delta))
      (log/debug "PLC pulse down")
      (.writeCoil plc 0 (+ base-addr open-offset) false)
      (log/debug "PLC pulse end")
      :open-sent
      (catch Exception e
        (log/error e)
        :plc-error-pulse))))

(defn execute-in [f delay-ms]
  (go
    (<! (timeout delay-ms))
    (log/info (format "totem: execute-in after %d ms" delay-ms))
    (f)))

(defn set-value [plc val & {:keys [open-offset base-addr]
                            :or {open-offset 0 base-addr 8256}}]
  (go
    (try
      (log/info (format "totem: PLC set %d %d %s" base-addr open-offset val))
      (.writeCoil plc 0 (+ base-addr open-offset) val)
      (when val
        (execute-in #(.writeCoil plc 0 (+ base-addr open-offset) false) 5000))
      :value-setted
      (catch Exception e
        (log/error e)
        :plc-error-set-value)))) 

(defn send-manual-open [delta {:keys [camera] :as evt}]
  (log/info :send-manual-open delta camera)
  (when (@plc-atom camera)
    (let [{:keys [plc marcas0 vehicular? bidireccional? pulse-delta base-addr open-offset] :as plc-state} ((plc2new-state evt) camera)
          [chan-key & params] (if-not vehicular?
                                [:exec-open]
                                (select-strategie true bidireccional? marcas0))]
      (log/info :send-manual-open camera open-offset delta marcas0)
      (send-pulse plc pulse-delta :base-addr base-addr :open-offset (+ delta open-offset))
      #_(if (= chan-key :exec-open) ; está lista para mandarle un open!! 
        (do
          (log/info :send-manual-open camera open-offset delta marcas0)
          (send-pulse plc pulse-delta :base-addr base-addr :open-offset (+ delta open-offset)))
        (log/info :send-manual-open camera open-offset delta marcas0 "IGNORED!"))
      )))

(defn send-manual-stop [delta {:keys [camera] :as evt}]
  (log/info :send-manual-open delta camera)
  (when (@plc-atom camera)
    (let [{:keys [plc marcas0 vehicular? bidireccional? pulse-delta base-addr open-offset] :as plc-state} ((plc2new-state evt) camera)
          [chan-key & params] (if-not vehicular?
                                [:NO-APLICA] ;exec-open
                                (select-strategie true bidireccional? marcas0))]
      (log/info :send-manual-stop camera open-offset delta marcas0)
      (send-pulse plc pulse-delta :base-addr base-addr :open-offset (+ delta open-offset))
      #_(if (= chan-key :exec-open) ; está lista, podemos mandarle un stop!! 
        (do
          (log/info :send-manual-stop camera open-offset delta marcas0)
          (send-pulse plc pulse-delta :base-addr base-addr :open-offset (+ delta open-offset)))
        (log/info :send-manual-stop camera open-offset delta marcas0 "IGNORED!"))
      )))

(defn send-manual-run [delta {:keys [camera] :as evt}]
  (log/info :send-manual-open delta camera)
  (when (@plc-atom camera)
    (let [{:keys [plc marcas0 vehicular? bidireccional? pulse-delta base-addr open-offset] :as plc-state} ((plc2new-state evt) camera)
          [chan-key & params] (if-not vehicular?  ;:proc-result :pluma-en-paro
                                [:NO-APLICA] ;exec-open
                                (select-strategie true bidireccional? marcas0))]
      (log/info :send-manual-run camera open-offset delta marcas0)
      (send-pulse plc pulse-delta :base-addr base-addr :open-offset (+ delta open-offset))
      #_(if (and
           (= chan-key :proc-result)
           (= (first params) :pluma-en-paro)) ; está en paro, podemos darle run
        (do
          (log/info :send-manual-run camera open-offset delta marcas0)
          (send-pulse plc pulse-delta :base-addr base-addr :open-offset (+ delta open-offset)))
        (log/info :send-manual-run camera open-offset delta marcas0 "IGNORED!"))
      )))

(defn init-processing-loops! []

  ;; LOOP de aperturas 
  (go-loop []
    (let [{:keys [exec-open proc-result]} @processing-channels
          [{:keys [plc pulse-delta event base-addr open-offset] :as plc-status} skip-pulse] (<! exec-open)
          {:keys [camera]} event]
      (log/info (format ":exec-open %s :skip-pulse %s" camera skip-pulse))
      (let [result-chan (when-not skip-pulse (send-pulse plc pulse-delta :base-addr base-addr :open-offset open-offset))
            result (if result-chan (<! result-chan) :open-sent)]
        (put! proc-result [plc-status result]))
      (recur)))
  
  (go-loop []
    (let [{:keys [exec-semaphore proc-result]} @processing-channels
          [{:keys [plc event base-addr open-offset] :as plc-status} color] (<! exec-semaphore)
          {:keys [camera]} event]
      (log/info (format "totem: :exec-semaphore %s :color %s" camera color))
      (let [result-chan (set-value plc (= color :verde) :base-addr base-addr :open-offset open-offset)
            result (<! result-chan)]
        (put! proc-result [plc-status result]))
      (recur)))
  
  (go-loop []
    (let [{:keys [drop proc-result]} @processing-channels
          [{:keys [plc pulse-delta event base-addr open-offset] :as plc-status}] (<! drop)
          {:keys [camera]} event]
      (log/debug (format ":exec-drop %s" camera))
      (put! proc-result [plc-status :drop])
      (recur)))

  ; LOOP de retry esperando condicion correcta para abrir PLC
  (go-loop []
    (let [{:keys [retry proc-result]} @processing-channels
          [{:keys [retry-delta event] :as plc-status} tipo max-cnt cnt] (<! retry)
          {:keys [camera]} event]
      (log/info (format ":retry %s %d,%d" camera max-cnt cnt))
      (if (< cnt max-cnt)
        (go
          (<! (timeout retry-delta))
          ; invocar operate-plc pero con valores actualizados del cnt+1 de modo
          ; que lee el PLC y ve si ya puede abrir o si sigue esperando pero ojo con cnt
          ; para que el siguiente retry lo use
          (operate-plc (:event plc-status) (inc cnt)))
        (put! proc-result [plc-status :retry-cnt-exeeded tipo]))
      (recur)))

  ; LOOP de proc-result dependiendo de los parametros hacemos diferentes cosas
  (go-loop []
    (let [{:keys [proc-result]} @processing-channels
          [{:keys [] :as plc-status}
           instruccion tipo] (<! proc-result)]
      (future
        (try
          #_(log/info "llego: " instruccion " -- " tipo)
          (process-result plc-status instruccion  tipo)
          (catch Throwable t
            (log/error t))))
      (recur))))

(defn -main []
  (log/info "Listo... plc-controler ready!"))
