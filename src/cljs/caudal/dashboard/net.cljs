(ns caudal.dashboard.net
  "Toda la red (REST + WebSocket) centralizada aqui como reg-fx -- los
   event handlers nunca llaman HTTP/WS directo (mismo patron que
   robot2/events/api.cljs)."
  (:require
   [re-frame.core :as re-frame]
   [day8.re-frame.http-fx]
   [ajax.edn :as ajax-edn]
   [taoensso.sente :as sente]
   [clojure.string :as str]))

;; -- REST: /states, /state/:id/:key/:by1../:by5 -----------------------------
;; El body de respuesta es EDN crudo (wrap-restful-format esta deshabilitado
;; en el server), de ahi ajax.edn en vez de JSON.

(defn- path-segment [v]
  (cond
    (keyword? v) (name v)
    (string? v) v
    :else (str v)))

(defn node-url
  "Arma /state/:id[/:key/:by1../:by5] a partir de un sink id y un path-vector
   (el mismo vector-llave que usa el backend). path vacio = /state/:id (todo
   el mapa del sink, solo se pide una vez al elegir sink)."
  [sink-id path]
  (str "/state/" (name sink-id)
       (when (seq path)
         (str "/" (str/join "/" (map path-segment path))))))

(defn edn-get [uri on-success on-failure]
  {:method :get
   :uri uri
   :response-format (ajax-edn/edn-response-format)
   :on-success on-success
   :on-failure on-failure})

;; -- WebSocket (Sente, endpoint "wslisten") ----------------------------------
;; El server manda [:caudal/waiting-subscriptions] al abrir conexion -- el
;; cliente debe responder con el set COMPLETO de topics deseados (Sente
;; reemplaza el set en el server en cada :caudal/subscribe, no es aditivo).
;; Los eventos en vivo llegan como [:caudal/update event-map].

(defonce ws (atom nil)) ;; {:chsk :ch-recv :send-fn :state :stop-router!}

(defn- handle-ws-event [{:keys [id ?data]}]
  (case id
    :chsk/state
    (let [[_ new-state] ?data]
      (if (:open? new-state)
        (re-frame/dispatch [:ws-connected])
        (re-frame/dispatch [:ws-disconnected])))

    :chsk/recv
    (let [[verb data] ?data]
      (case verb
        :caudal/waiting-subscriptions (re-frame/dispatch [:ws-waiting-subscriptions])
        :caudal/update (re-frame/dispatch [:ws-event-received data])
        nil))

    nil))

(re-frame/reg-fx
 :ws-connect
 (fn [_]
   (when-not @ws
     (let [{:keys [chsk ch-recv send-fn state]}
           (sente/make-channel-socket-client! "wslisten" {:type :auto})
           stop-router! (sente/start-client-chsk-router! ch-recv handle-ws-event)]
       (reset! ws {:chsk chsk :ch-recv ch-recv :send-fn send-fn
                   :state state :stop-router! stop-router!})))))

(re-frame/reg-fx
 :ws-subscribe
 (fn [topics]
   (when-let [send-fn (:send-fn @ws)]
     (send-fn [:caudal/subscribe (set topics)]))))
