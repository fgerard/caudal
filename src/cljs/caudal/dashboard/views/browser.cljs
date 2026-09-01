(ns caudal.dashboard.views.browser
  "Explorador jerarquico del estado. /state/:id se pide entero una sola vez
   por sink (ver events.cljs) y de ahi se arma en memoria un arbol por
   by-path -- el mismo orden anidado by/by/streamer que tiene la config
   (caudal.streams.common/key-factory guarda la llave real al reves,
   [stream-name & by-path], por eso el reordenamiento es puramente de
   presentacion aqui, nunca se le pide nada mas al server)."
  (:require
   [re-com.core :as rc]
   [re-frame.core :as re-frame]))

(declare branch-node)

;; marca una hoja (nombre de streamer) para que su :browser-expanded no
;; pueda chocar nunca con un segmento de by-path real (String/Long/etc).
(defn- leaf-path [path stream] (conj path [::leaf stream]))

(defn- leaf-node [path stream value]
  (let [lpath (leaf-path path stream)
        expanded? @(re-frame/subscribe [:browser-expanded? lpath])]
    [rc/v-box
     :children
     [[rc/h-box
       :align :center
       :gap "4px"
       :class "browser-row"
       :attr {:on-click #(re-frame/dispatch [:toggle-node lpath])}
       :children
       [[:span.browser-toggle (if expanded? "▾" "▸")]
        [:span.browser-label (str stream)]]]
      (when expanded?
        [:pre.state-value (pr-str value)])]]))

(defn- children-list [items]
  ;; re-com/v-box exige :children no vacio -- guard explicito.
  (when (seq items)
    [rc/v-box :class "browser-children" :children (vec items)]))

(defn branch-node [path segment]
  (let [path (conj path segment)
        expanded? @(re-frame/subscribe [:browser-expanded? path])
        {:keys [branches leaves leaf-values]} @(re-frame/subscribe [:browser-node path])]
    [rc/v-box
     :children
     [[rc/h-box
       :align :center
       :gap "4px"
       :class "browser-row"
       :attr {:on-click #(re-frame/dispatch [:toggle-node path])}
       :children
       [[:span.browser-toggle (if expanded? "▾" "▸")]
        [:span.browser-label (str segment)]]]
      (when expanded?
        [children-list
         (concat
          (for [b branches]
            ^{:key (str path "/b/" b)} [branch-node path b])
          (for [l leaves]
            ^{:key (str path "/l/" l)} [leaf-node path l (get leaf-values l)]))])]]))

(defn panel []
  (let [sinks @(re-frame/subscribe [:sinks])
        selected @(re-frame/subscribe [:selected-sink])
        {:keys [branches leaves leaf-values]} @(re-frame/subscribe [:browser-node []])]
    [rc/v-box
     :gap "8px"
     :children
     [[rc/h-box
       :align :center
       :gap "8px"
       :children
       [[:div.sink-refresh-btn
         {:on-click #(when selected (re-frame/dispatch [:refresh-sink-state]))
          :title "Refrescar datos del sink seleccionado"}
         "Sink:"]
        [rc/single-dropdown
         :choices (mapv (fn [s] {:id s :label (name s)}) sinks)
         :model selected
         :placeholder "Selecciona un sink"
         :width "220px"
         :on-change #(re-frame/dispatch [:select-sink %])]]]
      (when selected
        [children-list
         (concat
          (for [b branches]
            ^{:key (str "/b/" b)} [branch-node [] b])
          (for [l leaves]
            ^{:key (str "/l/" l)} [leaf-node [] l (get leaf-values l)]))])]]))
