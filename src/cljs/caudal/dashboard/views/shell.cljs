(ns caudal.dashboard.views.shell
  "Tabs hechos a mano (no rc/horizontal-tabs): re-com 2.x ya no trae un
   re-com.css con el look de pestanas, solo estilos inline por componente,
   y horizontal-tabs sale como lista plana sin chrome visual. Con divs +
   CSS propio (dashboard.css) se controla exacto el look pedido."
  (:require
   [reagent.core :as reagent]
   [caudal.dashboard.views.browser :as browser]
   [caudal.dashboard.views.live :as live]))

(def tab-defs [{:id :browser :label "Estado"}
               {:id :live :label "Eventos"}])

(defn- logo []
  ;; tres lineas de corriente llenando el circulo -- "caudal" es volumen/
  ;; gasto de agua en movimiento, una gota sola sugiere lo contrario (poco
  ;; flujo). (SVG en reagent necesita los atributos en camelCase: viewBox,
  ;; strokeWidth...)
  [:div.brand-logo
   [:svg {:viewBox "0 0 24 24" :width "30" :height "30"}
    [:path {:d "M1 7 Q6.5 2 12 7 T23 7"
            :stroke "white" :strokeWidth "2.4" :fill "none" :strokeLinecap "round"}]
    [:path {:d "M1 13 Q6.5 8 12 13 T23 13"
            :stroke "white" :strokeWidth "2.4" :fill "none" :strokeLinecap "round"
            :opacity "0.75"}]
    [:path {:d "M1 19 Q6.5 14 12 19 T23 19"
            :stroke "white" :strokeWidth "2.4" :fill "none" :strokeLinecap "round"
            :opacity "0.5"}]]])

(defn- tab-strip [active]
  (into [:div.tab-strip]
        (for [{:keys [id label]} tab-defs]
          ^{:key id}
          [:div.tab-item
           {:class (when (= @active id) "tab-item--active")
            :on-click #(reset! active id)}
           label])))

(defn panel []
  (let [active (reagent/atom :browser)]
    (fn []
      [:div.dashboard-shell
       [:div.dashboard-brand-row
        [logo]
        [:div.dashboard-brand "caudal"]]
       [tab-strip active]
       [:div.tab-accent-bar]
       [:div.tab-content
        (case @active
          :browser [browser/panel]
          :live [live/panel])]])))
