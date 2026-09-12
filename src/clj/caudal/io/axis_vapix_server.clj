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

   Port of the event-source half of the vmd-stream.clj prototype at
   quantum-cameras/vendors/axis/src/vmd_stream.clj: connect, authenticate
   (digest auth -> ws session token), subscribe to :topic-filter, sink one
   caudal event per notification. The app-specific half of that prototype
   (snapshots, pulse-while-active, CLIP identify) is deliberately NOT
   ported here -- that's streamer/business logic, it belongs downstream
   in a caudal config reacting to the events this listener sinks, same as
   caudal.io.rfid-server only sinks :ON_TAG_READ/:ON_TAG_REMOVED and
   leaves everything else to the config that wires it.

   Validated against real Axis hardware by that prototype (2026-09-11):
   :topic-filter is mandatory -- the camera rejects an empty/missing
   eventFilterList with error 2104."
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clj-http.client :as http]
            [caudal.streams.common :refer [start-listener]])
  (:import (java.net URI)
           (java.net.http HttpClient WebSocket$Listener)))

(defn fetch-ws-token
  "Digest-auth GET a la cgi de sesion WS de la camara -- regresa el token
  de sesion (string) que hay que pegar en la query string del websocket."
  [{:keys [ip user password]}]
  (let [url (format "http://%s/axis-cgi/wssession.cgi" ip)
        resp (http/get url {:digest-auth [user password] :throw-exceptions true})]
    (str/trim (:body resp))))

(defn- topic-of [notification]
  (get-in notification [:params :notification :topic]))

(defn- active? [notification]
  (let [data (get-in notification [:params :notification :message :data] {})
        v (some data [:active :Active :state :State :value :Value])]
    (contains? #{"1" "true" true 1} v)))

(defn fetch-snapshot-bytes
  "Snapshot JPEG actual de la camara via VAPIX HTTP (digest auth) -- mismo
  endpoint que usa el prototipo Python/Clojure para guardar snapshots."
  [{:keys [ip user password]}]
  (let [url (format "http://%s/axis-cgi/jpg/image.cgi" ip)
        resp (http/get url {:digest-auth [user password]
                             :as :byte-array
                             :throw-exceptions true})]
    (:body resp)))

(defn- fetch-snapshot-bytes-safe
  "Snapshot actual (bytes crudos), o nil si fallo -- no se debe tirar el
  evento completo solo porque el snapshot no se pudo tomar. Se comparte
  entre :with-image? e :identify para no pedir el snapshot dos veces
  cuando ambos estan configurados."
  [camera camera-info]
  (try
    (fetch-snapshot-bytes camera)
    (catch Exception e
      (log/warn "AXIS-VAPIX: no se pudo obtener snapshot para el evento: " (.getMessage e) " " (pr-str camera-info))
      nil)))

(defn- bytes->b64 [^bytes b]
  (.encodeToString (java.util.Base64/getEncoder) b))

(defn- best-identify-match
  "Del array :result :info de la respuesta del servicio de identify, el
  hit con mayor similarity -- o nil si viene vacio (sin match/unknown)."
  [identify-response]
  (let [info (get-in identify-response [:result :info])]
    (when (seq info)
      (apply max-key :similarity info))))

(defn call-identify
  "Llama al servicio de identificacion facial con el snapshot (bytes
  crudos) y regresa el mejor match ({:id ... :similarity ...}), o nil si
  no hubo match o hubo error -- mismo payload/headers que el prototipo
  Python/Clojure (call-identify! en vmd_stream.clj)."
  [{:keys [url token api-key threshold top-k timeout-ms]
    :or {threshold 0.6 top-k 1 timeout-ms 5000}}
   image-b64]
  (try
    (let [body {:clipB64 image-b64
                :threshold (str threshold)
                :top_k (str top-k)
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
                      (fetch-snapshot-bytes-safe camera camera-info))
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
  propio hilo, ver start-reconnect-loop)."
  [{:keys [camera camera-info topic-filter topic-match] :as config} sink token]
  (let [{:keys [ip scheme]} camera
        uri (URI/create (format "%s://%s/vapix/ws-data-stream?sources=events&wssession=%s"
                                (or scheme "ws") ip token))
        client (HttpClient/newHttpClient)
        done (promise)
        listener (reify WebSocket$Listener
                   (onOpen [_ ws]
                     (log/info "AXIS-VAPIX: websocket abierto, suscribiendo " (pr-str camera-info))
                     (.sendText ws (json/write-str (subscribe-payload topic-filter)) true)
                     (.request ws 1))
                   (onText [_ ws data _last]
                     (try
                       (let [msg (json/read-str (str data) :key-fn keyword)
                             topic (topic-of msg)]
                         (when (and topic (or (nil? topic-match) (str/includes? topic topic-match)))
                           (sink (notification->event config msg))))
                       (catch Exception e
                         (log/error "AXIS-VAPIX: error procesando mensaje: " (.getMessage e) " -- raw: " data)))
                     (.request ws 1)
                     nil)
                   (onError [_ _ws error]
                     (log/error "AXIS-VAPIX: websocket error: " (.getMessage error) " " (pr-str camera-info))
                     (deliver done :error))
                   (onClose [_ _ws status-code reason]
                     (log/info "AXIS-VAPIX: websocket cerrado: " status-code " " reason " " (pr-str camera-info))
                     (deliver done :closed)
                     nil))]
    (try
      (-> client .newWebSocketBuilder (.buildAsync uri listener) .join)
      @done
      (finally
        ; HttpClient.close() (JDK 21+) cierra conexiones idle del pool --
        ; se crea un client nuevo en cada llamada a connect-and-listen!
        ; (cada intento de reconexion), asi que sin esto se van acumulando
        ; sin liberarse de forma determinista en un listener que corre
        ; meses reconectando cada tanto. onClose/onError del listener no
        ; necesitan cleanup propio -- por contrato del JDK, para cuando
        ; se invocan el input/output del websocket ya estan cerrados.
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
    (.setName t (str "axis-vapix-" (:id camera-info (:ip camera))))
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

  - _camera:_ `{:ip ... :user ... :password ... :scheme}` (required,
    _ip_/_user_/_password_ mandatory, or the system exits fatally) --
    _scheme_ is \"ws\" or \"wss\" (default \"ws\"; use \"wss\" only if
    the camera has a valid certificate)
  - _camera-info:_ static map merged into every sinked event (e.g. `{:id
    \"entrada-principal\"}`) to identify which camera an event came from
    -- same convention as caudal.io.rfid-server's controler-info
    (default `{}`)
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
    :threshold 0.6 :top-k 1 :timeout-ms 5000}` (_token_/_api-key_
    optional, _threshold_/_top-k_/_timeout-ms_ default as shown). Same
    request shape as the vmd_stream.clj prototype's identify service
    call. Shares the SAME snapshot fetch as _with-image?_ when both are
    configured together (only one HTTP round-trip to the camera, not
    two). _:identify_ in the event is `{:id ... :similarity ...}` (the
    best match) or nil if there was no match, the snapshot fetch failed,
    or the identify service call itself failed (logged as a warning
    either way -- the event is still sinked regardless).

  Sinked event shape: `(merge camera-info {:event :ON_AXIS_EVENT :topic
  ... :active? bool :data {...} :axis-ts ... :image-b64 \"...\" :identify
  {...}})` -- _:data_ is whatever the notification's message.data
  carried, unprocessed; _:image-b64_/_:identify_ only present when
  _with-image?_/_identify_ are configured, respectively. Anything else
  app-specific belongs downstream as your own streamer reacting to these
  events, not in this listener.

  Example:

  ```
  (deflistener axis-cam1 [{:type 'caudal.io.axis-vapix-server
                           :parameters {:camera {:ip \"10.0.0.50\"
                                                  :user \"root\"
                                                  :password \"...\"
                                                  :scheme \"ws\"}
                                        :camera-info {:id \"entrada-principal\"}
                                        :topic-filter \"tnsaxis:CameraApplicationPlatform/facedetector/CameraProfile1\"
                                        :retry-ms 5000
                                        :with-image? true
                                        :identify {:url \"http://127.0.0.1:8000/identify\"
                                                   :token \"...\"
                                                   :threshold 0.6
                                                   :top-k 1
                                                   :timeout-ms 5000}}}])
  ```
  "
  (let [{:keys [camera camera-info topic-filter topic-match retry-ms with-image? identify]
         :or {camera-info {} retry-ms 5000}} (get-in config [:parameters])]
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
                           :with-image? with-image?
                           :identify identify}
                          sink)))
