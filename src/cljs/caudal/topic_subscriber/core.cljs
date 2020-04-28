(ns caudal.topic-subscriber.core
    (:require [reagent.dom :as reagent]
              [re-frame.core :as re-frame]
              [caudal.topic-subscriber.events]
              [caudal.topic-subscriber.subs]
              [caudal.topic-subscriber.views :as views]
              [caudal.topic-subscriber.config :as config]))


(defn dev-setup []
  (when config/debug?
    (enable-console-print!)
    (println "dev mode")))

(defn mount-root []
  (reagent/render [views/main-panel]
                  (.getElementById js/document "app")))

(defn ^:export init []
  (let [url-base (str (-> js/window .-location .-origin) "/")]
    (re-frame/dispatch-sync [:initialize-db url-base])
    (dev-setup)
    (mount-root)))
