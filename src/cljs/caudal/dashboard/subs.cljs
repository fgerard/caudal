(ns caudal.dashboard.subs
  (:require [re-frame.core :as re-frame]))

(re-frame/reg-sub :sinks (fn [db _] (:sinks db)))
(re-frame/reg-sub :selected-sink (fn [db _] (:selected-sink db)))

;; path = segmentos de by-path recorridos desde la raiz (nunca incluye el
;; nombre del streamer -- ese vive en :leaves, ver events.cljs). Devuelve
;; las ramas (mas by-path) y las hojas (streamers con valor ya conocido,
;; nada que pedir al server) de ese nodo del arbol construido en
;; :browser-tree.
(re-frame/reg-sub
 :browser-node
 (fn [db [_ path]]
   (let [node (get-in db (into [:browser-tree] (interleave (repeat :branches) path)))]
     {:branches (sort-by str (keys (:branches node)))
      :leaves (sort-by str (keys (:leaves node)))
      :leaf-values (:leaves node)})))

(re-frame/reg-sub
 :browser-expanded?
 (fn [db [_ path]]
   (contains? (:browser-expanded db) path)))

(re-frame/reg-sub :ws-status (fn [db _] (get-in db [:ws :status])))
(re-frame/reg-sub :subscribed-topics (fn [db _] (get-in db [:ws :subscribed])))
(re-frame/reg-sub :topic-input (fn [db _] (get-in db [:ws :topic-input])))
(re-frame/reg-sub :events-received (fn [db _] (:events-received db)))
(re-frame/reg-sub :last-events (fn [db _] (:last-events db)))
