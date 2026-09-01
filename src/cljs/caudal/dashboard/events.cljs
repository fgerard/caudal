(ns caudal.dashboard.events
  (:require
   [clojure.string :as str]
   [re-frame.core :as re-frame]
   [caudal.dashboard.db :as db]
   [caudal.dashboard.net :as net]))

(re-frame/reg-event-fx
 :initialize-db
 (fn [_ _]
   {:db db/default-db
    :http-xhrio (net/edn-get "/states" [:sinks-loaded] [:http-failed :sinks])
    :ws-connect nil}))

(re-frame/reg-event-db
 :sinks-loaded
 (fn [db [_ sinks]]
   (assoc db :sinks (vec sinks))))

(re-frame/reg-event-db
 :http-failed
 (fn [db [_ tag error]]
   (assoc-in db [:errors tag] error)))

;; -- Explorador de estado (fetch completo una vez, arbol por by-path en
;;    memoria del lado del cliente -- ver comentario en db.cljs) ----------

(def ^:private hidden-root-keys
  "Bookkeeping interno que crea create-caudal-agent/create-sink en TODO
   sink (no datos de la config del usuario) -- no aporta al explorar
   estado, se esconde del arbol."
  #{:caudal/entry :caudal/send2agent})

(defn- add-entry
  "Mete una entrada [llave valor] del mapa de estado crudo al arbol por
   by-path. llave real = [stream-name & by-path] (counter/reduce-with/etc,
   ver caudal.streams.common/key-factory) -- se re-indexa aqui como
   by-path primero y stream-name como hoja, para que el arbol se vea en el
   mismo orden que el anidado by/by/streamer de la config. Una llave que
   no es vector (ej. :caudal/view-conf) se cuelga como hoja de la raiz
   directamente."
  [tree k v]
  (let [[stream by-path] (if (vector? k)
                            [(first k) (rest k)]
                            [k []])]
    (assoc-in tree (concat (interleave (repeat :branches) by-path) [:leaves stream]) v)))

(defn- build-browser-tree [raw]
  (reduce-kv (fn [tree k v]
               (if (contains? hidden-root-keys k)
                 tree
                 (add-entry tree k v)))
             {}
             raw))

(re-frame/reg-event-fx
 :select-sink
 (fn [{:keys [db]} [_ sink-id]]
   {:db (-> db (assoc :selected-sink sink-id) (assoc :browser-tree {} :browser-expanded #{}))
    :http-xhrio (net/edn-get (net/node-url sink-id [])
                              [:sink-state-loaded sink-id]
                              [:http-failed :node])}))

(re-frame/reg-event-db
 :sink-state-loaded
 (fn [db [_ sink-id result]]
   ;; no toca :browser-expanded aqui -- :select-sink ya lo resetea a #{}
   ;; de una vez (antes de que esta respuesta llegue) al cambiar de sink;
   ;; :refresh-sink-state en cambio lo deja intacto a proposito, para que
   ;; refrescar datos no cierre lo que el usuario ya tenia abierto.
   (if (= sink-id (:selected-sink db))
     (assoc db :browser-tree (build-browser-tree result))
     db)))

(re-frame/reg-event-fx
 :refresh-sink-state
 (fn [{:keys [db]} _]
   (when-let [sink-id (:selected-sink db)]
     {:http-xhrio (net/edn-get (net/node-url sink-id [])
                                [:sink-state-loaded sink-id]
                                [:http-failed :node])})))

(re-frame/reg-event-db
 :toggle-node
 (fn [db [_ path]]
   (update db :browser-expanded
           (fn [expanded] ((if (contains? expanded path) disj conj) expanded path)))))

;; -- Eventos en vivo (WebSocket) ---------------------------------------------

(re-frame/reg-event-db
 :ws-connected
 (fn [db _] (assoc-in db [:ws :status] :connected)))

(re-frame/reg-event-db
 :ws-disconnected
 (fn [db _] (assoc-in db [:ws :status] :disconnected)))

(re-frame/reg-event-fx
 :ws-waiting-subscriptions
 (fn [{:keys [db]} _]
   {:ws-subscribe (get-in db [:ws :subscribed])}))

(re-frame/reg-event-fx
 :add-topic
 (fn [{:keys [db]} [_ topic]]
   (let [topic (str/trim topic)]
     (if (empty? topic)
       {:db db}
       (let [db (-> db
                    (update-in [:ws :subscribed] conj topic)
                    (assoc-in [:ws :topic-input] ""))]
         {:db db
          :ws-subscribe (get-in db [:ws :subscribed])})))))

(re-frame/reg-event-fx
 :remove-topic
 (fn [{:keys [db]} [_ topic]]
   (let [db (update-in db [:ws :subscribed] disj topic)]
     {:db db
      :ws-subscribe (get-in db [:ws :subscribed])})))

(re-frame/reg-event-db
 :set-topic-input
 (fn [db [_ v]] (assoc-in db [:ws :topic-input] v)))

(re-frame/reg-event-db
 :ws-event-received
 (fn [db [_ event]]
   (-> db
       (update :events-received inc)
       (update :last-events #(take db/max-last-events (conj % event))))))
