(ns caudal.dashboard.core
  (:require
   [reagent.dom :as rdom]
   [re-frame.core :as re-frame]
   [caudal.dashboard.events]
   [caudal.dashboard.subs]
   [caudal.dashboard.views.shell :as shell]))

(defn mount-root []
  (rdom/render [shell/panel] (.getElementById js/document "app")))

(defn ^:export init []
  (re-frame/dispatch-sync [:initialize-db])
  (mount-root))
