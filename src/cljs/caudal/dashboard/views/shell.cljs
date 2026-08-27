(ns caudal.dashboard.views.shell
  (:require
   [reagent.core :as reagent]
   [re-com.core :as rc]
   [re-frame.core :as re-frame]
   [caudal.dashboard.views.browser :as browser]
   [caudal.dashboard.views.live :as live]))

(def tab-defs [{:id :browser :label "Explorar estado"}
               {:id :live :label "Eventos en vivo"}])

(defn panel []
  (let [active (reagent/atom :browser)]
    (fn []
      [rc/v-box
       :class "dashboard-shell"
       :gap "12px"
       :children
       [[rc/title :label "caudal" :level :level2]
        [rc/horizontal-tabs
         :model @active
         :tabs tab-defs
         :on-change #(reset! active %)]
        (case @active
          :browser [browser/panel]
          :live [live/panel])]])))
