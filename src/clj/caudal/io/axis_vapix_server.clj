;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns caudal.io.axis-vapix-server
  "Event listener for Axis camera VAPIX WebSocket event streaming
   (JSON-RPC over ws/wss, /vapix/ws-data-stream) -- auto-reconnecting.

   Ported from the event-source half of the vmd-stream.clj prototype at
   quantum-cameras/vendors/axis/src/vmd_stream.clj: connect, authenticate
   (digest auth -> ws session token), subscribe to :topic-filter, sink one
   caudal event per notification. Unlike that prototype, snapshot capture
   (:with-image?), face-identify service calls (:identify) and periodic
   re-emit while a topic stays active (:pulse-ms) ARE offered here as
   built-in, optional, purely mechanical features -- every caudal config
   wiring an Axis camera needs the same snapshot/identify plumbing, so it
   belongs in the listener. What's deliberately NOT here is any actual
   business/streamer logic on top of that (deciding what to do with an
   identification, notification workflow, etc) -- that stays downstream
   in a caudal config reacting to the events this listener sinks, same as
   caudal.io.rfid-server only sinks :ON_TAG_READ/:ON_TAG_REMOVED and
   leaves everything else to the config that wires it.

   Validated against real Axis hardware (2026-09-11 via the prototype,
   and since directly via this listener): :topic-filter is mandatory --
   the camera rejects an empty/missing eventFilterList with error 2104.
   A real-world tested config lives at
   docker/configs-tipicos/config-vapix-access (has real camera/service
   credentials, so treat it as a reference, not a template to copy
   verbatim)."
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clj-http.client :as http]
            [caudal.streams.common :refer [start-listener]])
  (:import (java.net URI)
           (java.net.http HttpClient WebSocket WebSocket$Listener)
           (java.nio ByteBuffer)))

(defn fetch-ws-token
  "Digest-auth GET a la cgi de sesion WS de la camara -- regresa el token
  de sesion (string) que hay que pegar en la query string del websocket."
  [{:keys [protocol ip port user password] :or {protocol "http" port 80}}]
  (let [url (format "%s://%s:%s/axis-cgi/wssession.cgi" protocol ip port)
        resp (http/get url {:digest-auth [user password] :throw-exceptions true})]
    (str/trim (:body resp))))

(defn- topic-of [notification]
  (get-in notification [:params :notification :topic]))

(defn- active? [notification]
  (let [data (get-in notification [:params :notification :message :data] {})
        v (some data [:active :Active :state :State :value :Value])]
    (contains? #{"1" "true" true 1} v)))

(defn camera-id [{:keys [ip port] :or {port 80}}]
  (str ip ":" port))

(defn- ws-scheme
  "ws/wss se infieren de :protocol -- wss (websocket sobre TLS) es
  exactamente lo mismo a nivel transporte que https, no tiene sentido
  configurarlos por separado ni que puedan quedar en desacuerdo."
  [protocol]
  (if (= protocol "https") "wss" "ws"))

(defn fetch-snapshot-bytes
  "Snapshot JPEG actual de la camara via VAPIX HTTP (digest auth) -- mismo
  endpoint que usa el prototipo Python/Clojure para guardar snapshots."
  [{:keys [protocol ip port user password] :or {protocol "http" port 80}}]
  (let [url (format "%s://%s:%s/axis-cgi/jpg/image.cgi" protocol ip port)
        resp (http/get url {:digest-auth [user password]
                             :as :byte-array
                             :throw-exceptions true})]
    (:body resp)))

(defn- fetch-snapshot-bytes-safe
  "Snapshot actual (bytes crudos), o nil si fallo -- no se debe tirar el
  evento completo solo porque el snapshot no se pudo tomar. Se comparte
  entre :with-image? e :identify para no pedir el snapshot dos veces
  cuando ambos estan configurados."
  [camera]
  (try
    (fetch-snapshot-bytes camera)
    (catch Exception e
      (log/warn "AXIS-VAPIX: no se pudo obtener snapshot para el evento: " (.getMessage e) " " (pr-str (camera-id camera)))
      nil)))

(defn- bytes->b64 [^bytes b]
  (.encodeToString (java.util.Base64/getEncoder) b))

(defn- best-identify-match
  "Del array :result :info de la respuesta del servicio de identify, el
  primer hit (el servicio ya lo regresa ordenado/acotado a :top_k) -- o
  nil si viene vacio (sin match/unknown)."
  [identify-response]
  (-> identify-response
      (get-in [:result :info])
      (first)))

(defn call-identify
  "Llama al servicio de identificacion facial con el snapshot (bytes
  crudos) y regresa el mejor match ({:id ... :similarity ...}), o nil si
  no hubo match o hubo error -- mismo payload/headers que el prototipo
  Python/Clojure (call-identify! en vmd_stream.clj)."
  [{:keys [url token api-key threshold timeout-ms]
    :or {threshold 0.6 timeout-ms 5000}}
   image-b64]
  (try
    (let [body {:clipB64 image-b64
                :threshold threshold
                :top_k 1
                :clip_image true
                :return_input_clip false
                :return_clip false
                :return_image false
                :return_embedding false}
          headers (cond-> {"Content-Type" "application/json"}
                    token (assoc "Authorization" (str "Bearer " token))
                    api-key (assoc "x-api-key" api-key))
          resp (http/post url {:body (json/write-str body)
                               :headers headers
                               :socket-timeout timeout-ms
                               :connection-timeout timeout-ms
                               :throw-exceptions false})]
      (if (= 200 (:status resp))
        (best-identify-match (json/read-str (:body resp) :key-fn keyword))
        (do
          (log/warn "AXIS-VAPIX: identify error [" (:status resp) "]: " (:body resp))
          nil)))
    (catch Exception e
      (log/warn "AXIS-VAPIX: identify error: " (.getMessage e))
      nil)))

(defn- notification->event [{:keys [camera camera-info with-image? identify]} notification]
  (let [image-bytes (when (or with-image? identify)
                      (fetch-snapshot-bytes-safe camera))
        image-b64 (when (or with-image? identify)
                    (some-> image-bytes bytes->b64))]
    (cond-> (merge camera-info
                   {:event :ON_AXIS_EVENT
                    :topic (topic-of notification)
                    :active? (active? notification)
                    :data (get-in notification [:params :notification :message :data])
                    :axis-ts (System/currentTimeMillis)})
      with-image? (assoc :image-b64 image-b64)
      identify (assoc :identify (when image-b64 (call-identify identify image-b64))))))

; (str ip ":" port) -> {topic -> (atom bool)} -- mientras un topic siga activo (no
; ha llegado su notificacion de active?=false), :pulse-ms sinkea un evento
; sintetico extra cada tanto (mismo :topic/:data del ultimo real, pero con
; snapshot/identify recalculados en el momento) -- para casos donde la
; primera imagen al entrar a cuadro no basta para identificar bien (la
; persona viene viendo al piso, etc) y hace falta seguir intentando
; mientras siga presente. El atom es la señal de "sigue vivo" que el hilo
; del pulso checa en cada vuelta -- apagarlo (stop-pulse!) es suficiente
; para pararlo, sin falta de Thread/interrupt.
(defonce ^:private pulse-registry (atom {}))

(defn- stop-pulse! [camera topic]
  (when-let [running? (get-in @pulse-registry [(camera-id camera) topic])]
    (log/info "AXIS-VAPIX: deteniendo pulso " topic " " (pr-str (camera-id camera)))
    (reset! running? false))
  (swap! pulse-registry update (camera-id camera) dissoc topic))

(defn- stop-all-pulses! [camera]
  (let [topics (keys (get @pulse-registry (camera-id camera)))]
    (when (seq topics)
      (log/info "AXIS-VAPIX: deteniendo todos los pulsos " (pr-str topics) " " (pr-str (camera-id camera)))))
  (doseq [running? (vals (get @pulse-registry (camera-id camera)))]
    (reset! running? false))
  (swap! pulse-registry dissoc (camera-id camera)))

(defn- start-pulse-if-needed!
  "base-notification es la notificacion real que disparo :active? true --
  se reusa tal cual en cada vuelta del pulso (mismo :topic/:data), solo
  :image-b64/:identify/:axis-ts se recalculan frescos en notification->event
  cada vez. No hace nada si ya habia un pulso corriendo para este topic, o
  si :pulse-ms no esta configurado."
  [{:keys [camera pulse-ms] :as config} sink topic base-notification]
  (when (and pulse-ms (not (get-in @pulse-registry [(camera-id camera) topic])))
    (log/info "AXIS-VAPIX: iniciando pulso cada " pulse-ms "ms " topic " " (pr-str (camera-id camera)))
    (let [running? (atom true)]
      (swap! pulse-registry assoc-in [(camera-id camera) topic] running?)
      (let [t (Thread.
               ^Runnable
               (fn []
                 (while @running?
                   (Thread/sleep (long pulse-ms))
                   (when @running?
                     (try
                       (sink (assoc (notification->event config base-notification) :pulse? true))
                       (catch Exception e
                         (log/warn "AXIS-VAPIX: error generando evento de pulso: " (.getMessage e) " " (pr-str (camera-id camera) topic))))))))]
        (.setDaemon t true)
        (.setName t (str "axis-vapix-pulse-" (camera-id camera) "-" topic))
        (.start t)))))

(defn- subscribe-payload
  "eventFilterList es una lista -- topic-filter puede ser un solo string
  o ya venir como vector para suscribirse a varios topics a la vez."
  [topic-filter]
  (let [filters (if (sequential? topic-filter) topic-filter [topic-filter])]
    {:apiVersion "1.0"
     :method "events:configure"
     :params {:eventFilterList (mapv (fn [tf] {:topicFilter tf}) filters)}}))

(defn connect-and-listen!
  "Abre el websocket, suscribe, y sinkea un evento por cada notificacion
  que matchee :topic-match (si se dio) hasta que se cierre/truene --
  bloquea el hilo que lo llama hasta entonces (pensado para correr en su
  propio hilo, ver start-reconnect-loop).

  Tambien corre un heartbeat: cada :heartbeat-ms manda un websocket Ping,
  y si no hubo NINGUNA actividad (mensaje recibido o Pong) en 3x ese
  intervalo, fuerza la reconexion. Hace falta porque java.net.http.
  WebSocket no detecta por si solo un peer que desaparece sin mandar un
  Close -- si la camara se apaga de golpe (sin FIN/RST TCP), el socket se
  queda esperando datos indefinidamente y ni onError ni onClose se
  disparan nunca sin este chequeo (confirmado con pruebas de resiliencia
  reales: apagar/prender la camara a veces retomaba la coneccion sola --
  cuando la camara si mandaba un Close -- y a veces se quedaba colgado
  para siempre -- cuando no)."
  [{:keys [camera camera-info topic-filter topic-match heartbeat-ms] :or {heartbeat-ms 15000} :as config} sink token]
  (let [{:keys [protocol ip port] :or {protocol "http" port 80}} camera
        uri (URI/create (format "%s://%s:%s/vapix/ws-data-stream?sources=events&wssession=%s"
                                (ws-scheme protocol) ip port token))
        client (HttpClient/newHttpClient)
        done (promise)
        last-activity (atom (System/currentTimeMillis))
        watchdog-alive? (atom true)
        touch! (fn [] (reset! last-activity (System/currentTimeMillis)))
        listener (reify WebSocket$Listener
                   (onOpen [_ ws]
                     (touch!)
                     (log/info "AXIS-VAPIX: websocket abierto, suscribiendo " (pr-str camera-info))
                     (.sendText ws (json/write-str (subscribe-payload topic-filter)) true)
                     (.request ws 1))
                   (onText [_ ws data _last]
                     (touch!)
                     (try
                       (let [msg (json/read-str (str data) :key-fn keyword)
                             topic (topic-of msg)]
                         (when (and topic (or (nil? topic-match) (str/includes? topic topic-match)))
                           (sink (notification->event config msg))
                           (if (active? msg)
                             (start-pulse-if-needed! config sink topic msg)
                             (stop-pulse! camera topic))))
                       (catch Exception e
                         (log/error "AXIS-VAPIX: error procesando mensaje: " (.getMessage e) " -- raw: " data)))
                     (.request ws 1)
                     nil)
                   (onPong [_ ws _message]
                     (touch!)
                     (.request ws 1)
                     nil)
                   (onError [_ _ws error]
                     (reset! watchdog-alive? false)
                     (log/error "AXIS-VAPIX: websocket error: " (.getMessage error) " " (pr-str camera-info))
                     (stop-all-pulses! camera)
                     (deliver done :error))
                   (onClose [_ _ws status-code reason]
                     (reset! watchdog-alive? false)
                     (log/info "AXIS-VAPIX: websocket cerrado: " status-code " " reason " " (pr-str camera-info))
                     (stop-all-pulses! camera)
                     (deliver done :closed)
                     nil))]
    (try
      (let [^WebSocket ws (-> client .newWebSocketBuilder (.buildAsync uri listener) .join)
            dead-after-ms (* 3 (long heartbeat-ms))
            watchdog (Thread.
                      ^Runnable
                      (fn []
                        (while @watchdog-alive?
                          (Thread/sleep (long heartbeat-ms))
                          (when @watchdog-alive?
                            (let [silence-ms (- (System/currentTimeMillis) @last-activity)]
                              (if (>= silence-ms dead-after-ms)
                                (do
                                  (log/error "AXIS-VAPIX: sin actividad del websocket en " silence-ms "ms (>= " dead-after-ms "ms), forzando reconexion " (pr-str camera-info))
                                  (reset! watchdog-alive? false)
                                  (stop-all-pulses! camera)
                                  (try
                                    (.abort ws)
                                    (catch Exception e
                                      (log/warn "AXIS-VAPIX: error al abortar websocket colgado: " (.getMessage e) " " (pr-str camera-info))))
                                  (deliver done :timeout))
                                (try
                                  (.sendPing ws (ByteBuffer/wrap (byte-array 0)))
                                  (catch Exception e
                                    (log/warn "AXIS-VAPIX: error enviando ping: " (.getMessage e) " " (pr-str camera-info))))))))))]
        (.setDaemon watchdog true)
        (.setName watchdog (str "axis-vapix-heartbeat-" (camera-id camera)))
        (.start watchdog)
        @done)
      (finally
        ; HttpClient.close() (JDK 21+) cierra conexiones idle del pool --
        ; se crea un client nuevo en cada llamada a connect-and-listen!
        ; (cada intento de reconexion), asi que sin esto se van acumulando
        ; sin liberarse de forma determinista en un listener que corre
        ; meses reconectando cada tanto. onClose/onError del listener no
        ; necesitan cleanup propio -- por contrato del JDK, para cuando
        ; se invocan el input/output del websocket ya estan cerrados. El
        ; watchdog tampoco -- watchdog-alive? ya quedo en false por
        ; cualquiera de los tres caminos (onError/onClose/timeout) para
        ; cuando llegamos aqui, asi que el hilo termina su vuelta actual
        ; (a lo mas, un ultimo Thread/sleep de heartbeat-ms) y se apaga
        ; solo -- es daemon, no hace falta esperarlo ni interrumpirlo.
        (.close client)))))

(defn start-reconnect-loop
  "Hilo daemon: token -> connect-and-listen! (bloquea hasta que se cierre/
  truene) -> espera retry-ms -> repite. Mismo patron que el `while True`
  de vmd_stream.py / el loop de -main en el prototipo Clojure, solo que
  aqui corre en background en vez de ser el hilo principal."
  [{:keys [camera camera-info retry-ms] :as config} sink]
  (let [t (Thread.
           ^Runnable
           (fn []
             (while true
               (try
                 (log/info "AXIS-VAPIX: solicitando token de sesion websocket " (pr-str camera-info))
                 (let [token (fetch-ws-token camera)]
                   (log/info "AXIS-VAPIX: token obtenido, conectando " (pr-str camera-info))
                   (connect-and-listen! config sink token))
                 (catch Exception e
                   (log/error "AXIS-VAPIX: error de coneccion: " (.getMessage e) " " (pr-str camera-info))))
               (log/info "AXIS-VAPIX: reintentando en " retry-ms "ms " (pr-str camera-info))
               (Thread/sleep (long retry-ms)))))]
    (.setDaemon t true)
    (.setName t (str "axis-vapix-" (camera-id camera)))
    (.start t)
    t))

(defmethod start-listener 'caudal.io.axis-vapix-server
  [sink config]
  "
  Creates an Axis camera VAPIX event-stream listener (WebSocket JSON-RPC,
  /vapix/ws-data-stream): connects, authenticates via digest auth to get
  a ws session token, subscribes to :topic-filter, and sinks one event
  per notification received. Auto-reconnects (after :retry-ms) if the
  connection drops or errors -- runs in its own daemon thread.

  - _camera:_ `{:protocol ... :ip ... :port ... :user ... :password ...}`
    (required, _ip_/_user_/_password_ mandatory, or the system exits
    fatally) -- _protocol_ is \"http\" or \"https\" (default \"http\"),
    used for both the HTTP calls (session token, snapshot) and the
    WebSocket URI (the ws/wss scheme is inferred from it: \"http\" ->
    \"ws\", \"https\" -> \"wss\", since a websocket-over-TLS endpoint and
    an https endpoint are the same transport, use \"https\" only if the
    camera has a valid certificate); _port_ defaults to 80 -- override
    _protocol_/_port_ to reach the camera through a tunnel (e.g. SSH port
    forwarding) instead of talking to it directly
  - _camera-info:_ static map merged into every sinked event (e.g. `{:camera
    \"entrada-principal\"}`) -- _must be unique per camera_, it's what lets
    you tell which camera an event came from downstream (same convention
    as caudal.io.rfid-server's controler-info); it is NOT used to key
    internal state (pulses are tracked by _camera_'s ip:port, see
    _pulse-ms_ below), so a duplicate _camera-info_ across two cameras
    won't break pulsing, only make sinked events ambiguous. Default `{}`
  - _topic-filter:_ VAPIX topic filter string, or a vector of strings to
    subscribe to several at once -- required, the camera rejects an
    empty/missing eventFilterList (confirmed against real Axis
    hardware). Example:
    \"tnsaxis:CameraApplicationPlatform/facedetector/CameraProfile1\"
  - _topic-match:_ optional substring -- if given, only notifications
    whose topic contains it get sinked (extra client-side filter on top
    of :topic-filter, e.g. useful if :topic-filter is broader than what
    you actually want to react to)
  - _retry-ms:_ ms to wait before reconnecting after the WebSocket
    closes or errors (default 5000)
  - _with-image?:_ if true, fetches a fresh VAPIX snapshot
    (axis-cgi/jpg/image.cgi, digest auth) for EVERY sinked event and
    attaches it base64-encoded as _:image-b64_ -- adds one HTTP
    round-trip per event (blocks the websocket callback thread while it
    fetches), so only turn it on for low-frequency event streams. If the
    snapshot fetch fails, the event is still sinked, just without
    _:image-b64_ (a warning is logged instead). Default false.
  - _identify:_ if given, a map to call a face-identify HTTP service
    with the event's snapshot for EVERY sinked event, and attach the
    result as _:identify_ -- `{:url ... :token ... :api-key ...
    :threshold 0.6 :timeout-ms 5000}` (_token_/_api-key_
    optional, _threshold_/_timeout-ms_ default as shown). Same
    request shape as the vmd_stream.clj prototype's identify service
    call. Shares the SAME snapshot fetch as _with-image?_ when both are
    configured together (only one HTTP round-trip to the camera, not
    two). _:identify_ in the event is `{:id ... :similarity ...}` (the
    best match) or nil if there was no match, the snapshot fetch failed,
    or the identify service call itself failed (logged as a warning
    either way -- the event is still sinked regardless).
  - _pulse-ms:_ if given, while a topic stays active (from the real
    _:active? true_ notification until its matching _:active? false_
    one, or until the connection drops/reconnects), an extra synthetic
    event is sinked every _pulse-ms_ -- same _:topic_/_:data_ as the
    triggering notification, but _:image-b64_/_:identify_/_:axis-ts_
    recomputed fresh each time (a new snapshot + identify call, if
    those are configured). Useful when the first frame on entry isn't
    good enough to identify (person looking down, etc) and you want to
    keep trying while they're still in frame. Pulsed events carry
    _:pulse? true_ (real notification-triggered events don't have that
    key at all) so you can tell them apart downstream -- e.g. to stop
    reacting once you already got a good identify, filter/dedupe on
    your own streamer's side, this listener has no way to be told
    \"stop pulsing\" from downstream. One background thread per
    camera+topic currently active; stops on the matching
    _:active? false_ or when the connection drops. Default nil
    (disabled).
  - _heartbeat-ms:_ how often to send a WebSocket Ping while connected,
    to detect a peer that goes silently unreachable (e.g. the camera
    loses power abruptly, without sending a TCP FIN/RST or a WebSocket
    Close) -- java.net.http.WebSocket has no built-in idle/read timeout,
    so without this a dead-but-not-closed connection can hang forever,
    with _onError_/_onClose_ never firing and the listener never
    reconnecting (confirmed with real resiliency testing: power-cycling
    the camera sometimes triggers a clean Close from it -- reconnects
    fine on its own -- and sometimes doesn't -- hangs without this).
    If no activity at all (a message, or a Pong reply) is seen for 3x
    _heartbeat-ms_, the connection is force-aborted and reconnection
    kicks in via the normal _:retry-ms_ path. Default 15000 (so ~45s to
    detect a truly dead connection).

  Sinked event shape: `(merge camera-info {:event :ON_AXIS_EVENT :topic
  ... :active? bool :data {...} :axis-ts ... :image-b64 \"...\" :identify
  {...} :pulse? true})` -- _:data_ is whatever the notification's
  message.data carried, unprocessed; _:image-b64_/_:identify_ only
  present when _with-image?_/_identify_ are configured, respectively;
  _:pulse?_ only present (and true) on synthetic events generated by
  _pulse-ms_. Anything else app-specific belongs downstream as your own
  streamer reacting to these events, not in this listener.

  Example:

  ```
  (deflistener axis-cam1 [{:type 'caudal.io.axis-vapix-server
                           :parameters {:camera {:protocol \"http\"
                                                 :ip \"10.0.0.50\"
                                                 :port 80
                                                 :user \"root\"
                                                 :password \"...\"}
                                        :camera-info {:camera \"entrada-principal\"}
                                        :topic-filter \"tnsaxis:CameraApplicationPlatform/facedetector/CameraProfile1\"
                                        :retry-ms 5000
                                        :heartbeat-ms 15000
                                        :with-image? true
                                        :pulse-ms 1000
                                        :identify {:url \"http://127.0.0.1:8000/identify\"
                                                   :token \"...\"
                                                   :threshold 0.6
                                                   :timeout-ms 5000}}}])
  ```
  "
  (let [{:keys [camera camera-info topic-filter topic-match retry-ms heartbeat-ms with-image? identify pulse-ms]
         :or {camera-info {} retry-ms 5000 heartbeat-ms 15000}} (get-in config [:parameters])]
    (when-not (and camera (:ip camera) (:user camera) (:password camera))
      (log/fatal "No se especifico camera con ip/user/password en la configuracion [start-listener caudal.io.axis-vapix-server]")
      (System/exit 1))
    (when-not topic-filter
      (log/fatal "topic-filter es obligatorio (la camara rechaza eventFilterList vacio) [start-listener caudal.io.axis-vapix-server]")
      (System/exit 1))
    (start-reconnect-loop {:camera camera
                           :camera-info camera-info
                           :topic-filter topic-filter
                           :topic-match topic-match
                           :retry-ms retry-ms
                           :heartbeat-ms heartbeat-ms
                           :with-image? with-image?
                           :identify identify
                           :pulse-ms pulse-ms}
                          sink)))
