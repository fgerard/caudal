(ns caudal.dashboard.views.browser
  "Explorador jerarquico del estado. El backend expone /state/:id/:key/
   :by1../:by5 -- cada nivel se pide bajo demanda (solo /state/:id, sin key,
   se pide entero, una vez por sink) y se cachea en re-frame indexado por el
   mismo vector-llave que usa el backend (nunca se arma un arbol anidado)."
  (:require
   [re-com.core :as rc]
   [re-frame.core :as re-frame]))

(declare node)

(defn- children-list [path children]
  ;; re-com/v-box exige :children no vacio -- guard explicito, children
  ;; puede llegar vacio/nil mientras el fetch inicial de un sink no resuelve.
  (when (seq children)
    [rc/v-box
     :class "browser-children"
     :children
     (vec (for [c children]
            ^{:key (str path "/" c)}
            [node (conj path c)]))]))

(defn node [path]
  (let [entry @(re-frame/subscribe [:browser-node path])]
    [rc/v-box
     :children
     [[rc/h-box
       :align :center
       :gap "4px"
       :class "browser-row"
       :attr {:on-click #(re-frame/dispatch [:toggle-node path])}
       :children
       (vec
        (remove nil?
                [[:span.browser-toggle (if (:expanded? entry) "▾" "▸")]
                 [:span.browser-label (str (last path))]
                 (when (:loading? entry) [rc/throbber :size :small])]))]
      (when (:expanded? entry)
        (cond
          (seq (:children entry)) [children-list path (:children entry)]
          (:value entry) [:pre.state-value (pr-str (:value entry))]
          :else nil))]]))

(defn panel []
  (let [sinks @(re-frame/subscribe [:sinks])
        selected @(re-frame/subscribe [:selected-sink])
        root @(re-frame/subscribe [:browser-node []])]
    [rc/v-box
     :gap "8px"
     :children
     [[rc/h-box
       :align :center
       :gap "8px"
       :children
       [[rc/label :label "Sink:"]
        [rc/single-dropdown
         :choices (mapv (fn [s] {:id s :label (name s)}) sinks)
         :model selected
         :placeholder "Selecciona un sink"
         :width "220px"
         :on-change #(re-frame/dispatch [:select-sink %])]]]
      (when selected
        [children-list [] (:children root)])]]))
