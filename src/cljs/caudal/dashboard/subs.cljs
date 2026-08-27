(ns caudal.dashboard.subs
  (:require [re-frame.core :as re-frame]))

(re-frame/reg-sub :sinks (fn [db _] (:sinks db)))
(re-frame/reg-sub :selected-sink (fn [db _] (:selected-sink db)))

(re-frame/reg-sub
 :browser-node
 (fn [db [_ path]]
   (get (:browser-cache db) path)))

(re-frame/reg-sub :ws-status (fn [db _] (get-in db [:ws :status])))
(re-frame/reg-sub :subscribed-topics (fn [db _] (get-in db [:ws :subscribed])))
(re-frame/reg-sub :topic-input (fn [db _] (get-in db [:ws :topic-input])))
(re-frame/reg-sub :events-received (fn [db _] (:events-received db)))
(re-frame/reg-sub :last-events (fn [db _] (:last-events db)))
