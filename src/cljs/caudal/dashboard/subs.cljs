(ns caudal.dashboard.subs
    (:require-macros [reagent.ratom :refer [reaction]])
    (:require [re-frame.core :as re-frame]))

(re-frame/reg-sub
 :tab
 (fn [db _]
   (:tab db)))

(re-frame/reg-sub
 :refresh
 (fn [db _]
   (:refresh db)))

(re-frame/reg-sub
 :states
 (fn [db _]
   (:states db)))

(re-frame/reg-sub
 :state
 (fn [db _]
   (:state db)))

(re-frame/reg-sub
 [:state :selected]
 (fn [db _]
   (get-in db [:state :selected])))

#_(re-frame/reg-sub
 [:state :data]
 (fn [db _]
   (get-in db [:state :data])))

(re-frame/reg-sub
 [:state :types]
 (fn [db _]
   (get-in db [:state :types])))



(re-frame/reg-sub
 :url-target-message
 (fn [db _]
   (:url-target-message db)))

(re-frame/reg-sub
 :uri
 (fn [db _]
   (:uri db)))

(re-frame/reg-sub
 :counters
 (fn [db _]
   (:counters db)))

(re-frame/reg-sub
 :open?
 (fn [db _]
   (:open? db)))
