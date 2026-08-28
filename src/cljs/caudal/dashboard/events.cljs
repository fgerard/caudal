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

;; -- Explorador de estado (REST, arbol perezoso sobre el mapa plano) --------

(re-frame/reg-event-fx
 :select-sink
 (fn [{:keys [db]} [_ sink-id]]
   {:db (-> db (assoc :selected-sink sink-id) (assoc :browser-cache {}))
    :http-xhrio (net/edn-get (net/node-url sink-id [])
                              [:node-loaded sink-id []]
                              [:http-failed :node])}))

(defn- node-children
  "El backend devuelve: un vector de llaves (prefix match, hay que sacar el
   siguiente segmento de cada una) si el path no es hoja, o un mapa (la
   entrada real, con :caudal/type) si es hoja."
  [path result]
  (when (vector? result)
    (let [depth (count path)]
      (->> result
           (keep #(when (and (vector? %) (> (count %) depth)) (nth % depth)))
           distinct
           vec))))

(def ^:private hidden-root-keys
  "Bookkeeping interno que crea create-caudal-agent/create-sink en TODO
   sink (no datos de la config del usuario) -- no aporta al explorar
   estado, se esconde del arbol."
  #{:caudal/entry :caudal/send2agent})

(defn- root-children
  "path raiz: /state/:id devuelve el mapa COMPLETO -- las llaves de primer
   nivel (o la llave entera si no es vector, ej. :caudal/view-conf) son los
   hijos de la raiz."
  [result]
  (->> (keys result)
       (map #(if (vector? %) (first %) %))
       distinct
       (remove hidden-root-keys)
       vec))

(re-frame/reg-event-db
 :node-loaded
 (fn [db [_ sink-id path result]]
   (if (= sink-id (:selected-sink db))
     (let [root? (empty? path)
           children (if root? (root-children result) (node-children path result))
           value (when (and (not root?) (map? result)) result)]
       ;; merge, no reemplazar -- :toggle-node ya puso :expanded? true antes
       ;; de que esta respuesta llegara, no se debe perder. En la raiz
       ;; guardamos ademas el mapa crudo completo (:raw) -- lo necesita
       ;; :toggle-node para las llaves "planas" (:caudal/algo, sin vector),
       ;; que no se pueden pedir por la ruta REST (ver comentario abajo).
       (update-in db [:browser-cache path] merge
                  (cond-> {:children children :value value :loading? false}
                    root? (assoc :raw result))))
     db)))

(re-frame/reg-event-fx
 :toggle-node
 (fn [{:keys [db]} [_ path]]
   (let [sink-id (:selected-sink db)
         opening? (not (get-in db [:browser-cache path :expanded?]))
         root-raw (get-in db [:browser-cache [] :raw])
         ;; Llaves "planas" del root (ej. :caudal/entry, un keyword con
         ;; namespace) no se pueden pedir por /state/:id/:key -- bidi
         ;; parte la ruta en "/" y (keyword "entry") ya no matchea
         ;; :caudal/entry (pierde el namespace), y %2F tampoco rutea. Como
         ;; el fetch del root YA trae su valor completo, se lee directo de
         ;; ahi sin pedirle nada al server.
         plain-leaf? (and (= (count path) 1) (contains? root-raw (last path)))]
     (cond
       (not opening?)
       {:db (assoc-in db [:browser-cache path :expanded?] false)}

       plain-leaf?
       {:db (update-in db [:browser-cache path] merge
                        {:expanded? true :value (get root-raw (last path))
                         :children nil :loading? false})}

       (or (nil? sink-id) (> (count path) 6))
       {:db (update-in db [:browser-cache path :expanded?] not)}

       :else
       ;; Recarga siempre al abrir (no solo si no hay cache): el estado
       ;; cambia en tiempo real, ver datos viejos al re-expandir confunde.
       {:db (-> db
                (assoc-in [:browser-cache path :loading?] true)
                (assoc-in [:browser-cache path :expanded?] true))
        :http-xhrio (net/edn-get (net/node-url sink-id path)
                                  [:node-loaded sink-id path]
                                  [:http-failed :node])}))))

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
