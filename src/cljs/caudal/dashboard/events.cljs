(ns caudal.dashboard.events
    (:require [re-frame.core :as re-frame]
              [caudal.dashboard.db :as db]
              [cljs.reader :refer [read-string]]
              [cljs.pprint]
              [ajax.edn :as ajax]
              [day8.re-frame.http-fx]
              [cljs.core.async :refer [<! timeout]])
    (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(re-frame/reg-event-db
 :initialize-db
 (fn  [_ _]
   db/default-db))

(re-frame/reg-event-db
 :states-response
 (fn [db [_ response]]
   (-> db
       (assoc :states (into [] (map #(name %) response))))))

(re-frame/reg-event-fx
 :process-response
 (fn [{:keys [db]} [_ response]]
   (let [{:keys [uri]} db
         {:keys [status link]} uri
         {:keys [name version uptime]} response]
     (if (and name version uptime
              ; TODO: Fix for support extensible Caudal (strauz-monitor)
              ; (= app "mx.interware/caudal")
              )
       {:http-xhrio {:method     :get
                     :uri        (str link "/states")
                     :format    (ajax/edn-request-format)
                     :response-format (ajax/edn-response-format)
                     :on-success [:states-response]
                     ;:on-failure [:states-response]
                     }
        :db   (-> db
                  (assoc :url-target-message (merge {:uri link :success :caudal-server-connected} response))
                  (assoc-in [:uri :status] :success))}
       {:db (-> db
                (assoc :url-target-message {:uri link :status-text "This URL does not point to a Caudal server" :response response :error :not-caudal-server})
                (assoc-in [:uri :status] :danger))}))))

(re-frame/reg-event-db
 :failed-response
 (fn [db [_ response]]
   (let [{:keys [status-text]} response
         [_ status-text] (re-matches #"(.{0,37}).*" (clojure.string/replace status-text "\n" " "))
         response (-> response
                      (dissoc :original-text)
                      (assoc :status-text (str status-text "...")))]
     (-> db
         (assoc :url-target-message response)
         (assoc :uri {:status :warning})))))

(re-frame/reg-event-fx
 :process-url
 (fn [{:keys [db]} [_ new-url]]
   (letfn [(async-ret [db new-url status]
             {:http-xhrio {:method     :get
                           :uri        (str new-url "/caudal")
                           :format    (ajax/edn-request-format)
                           :response-format (ajax/edn-response-format)
                           :on-success [:process-response]
                           :on-failure [:failed-response]}
              :db   (assoc db :uri {:status status :link new-url})})]
     (if new-url
       (async-ret db new-url :loading)
       (if-let [new-url (get-in db [:uri :link])]
         (async-ret db new-url (get-in db [:uri :status]))
         {:db (-> db
                  (assoc :url-target-message {:status-text "Write a valid Caudal URL"})
                  (dissoc :uri))})))))

(re-frame/reg-event-fx
 :loading-url-change
 (fn [{:keys [db]} [_ new-url]]
   {:db   (assoc db :uri {:status :loading})
    :dispatch [:process-url new-url]}))

(re-frame/reg-event-db
 :select-tab
 (fn [db [_ new-tab]]
   (-> db
       (assoc :tab new-tab))))

(re-frame/reg-event-db
 :print-db
 (fn [db [_ new-tab]]
   (cljs.pprint/pprint db)
   db))

(re-frame/reg-event-db
 :open?
 (fn [db [_ bool]]
   (-> db
       (assoc :open? bool))))

(re-frame/reg-event-db
 :state-response
 (fn [db [_ response]]
   (if (= :timeout (:failure response))
     (let [{:keys [last-error status-text]} response]
       (-> db
           (assoc-in [:uri :status] :danger)
           (assoc-in [:url-target-message :status-text] (str last-error " " status-text))))
     (let [[response types] (reduce (fn [[response types] [k v]]
                                      [(assoc response k v)
                                       (if (map? v) (conj types (:caudal/type v)) types)])
                                    [{} #{}]
                                    response)]
       (-> db
           (assoc-in [:state :data] response)
           (assoc-in [:state :types] types)
           (assoc-in [:uri :status] :success))))))

(re-frame/reg-event-fx
 :get-state
 (fn [{:keys [db]} [_ new-state]]
   (let [{:keys [uri]} db
         {:keys [status link]} uri
         new-state  (or new-state (get-in db [:state :selected]))]
     (if new-state
       {:http-xhrio {:method     :get
                     :uri       (str link "/state/" new-state)
                     :format    (ajax/edn-request-format)
                     :response-format (ajax/edn-response-format)
                     :timeout         60000
                     :on-success [:state-response]
                     :on-failure [:state-response]
                     }
        :db (-> db
                ;(assoc-in [:tab] "server")
                (assoc-in [:state :selected] new-state)
                (assoc-in [:uri :status] :loading-data))}
       {:db db}))))

(re-frame/reg-event-db
 :tab-select
 (fn [db [_ path idx]]
   (assoc-in db path idx)))

(def refresh (atom nil))

(re-frame/reg-event-fx
 :refresh
 (fn [{:keys [db]} [_ value]]
   (let [_ (reset! refresh value)]
     {:db (-> db
              (assoc-in [:refresh] value))})))

(defn refresher []
  (let []
    (go-loop []
      (if @refresh
        (re-frame/dispatch [:get-state]))
      (<! (timeout (* 1000 (or @refresh 5))))
      (recur))))

(go-loop []
  (re-frame/dispatch [:process-url])
  (<! (timeout 60000))
  (recur))

(refresher)


#_(go-loop []
  (let [x (<! refresh-on)]
    (.log js/console (str (js/Date.) " Retrieving counters ..."))
    (re-frame/dispatch [:get-state])
    (<! (timeout 5000))
    (recur)))
