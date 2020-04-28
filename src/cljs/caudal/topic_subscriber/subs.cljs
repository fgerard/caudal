(ns caudal.topic-subscriber.subs
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

(re-frame/reg-sub
 :uri
 (fn [db _]
   (:uri db)))

(re-frame/reg-sub
 [:customs :detail]
 (fn [db [path]]
   (get-in db path)))

(re-frame/reg-sub
 [:customs :aggregate]
 (fn [db [path]]
   (get-in db path)))
