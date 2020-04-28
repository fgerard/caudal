(ns caudal.topic-subscriber.events
    (:require [re-frame.core :as re-frame]
              [caudal.topic-subscriber.db :as db]
              [cljs.reader :refer [read-string]]
              [cljs.pprint]
              [ajax.edn :as ajax]
              [day8.re-frame.http-fx]
              [taoensso.sente :as sente :refer (cb-success?)]
              [cljs.core.async :refer [<! timeout]])
    (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(defn now []
  (js/Date.) ;(time/time-now)
  )

(defn create-log [evt]
  [:log evt])

(re-frame/reg-event-db
 :initialize-db
 (fn  [_ _]
   db/default-db))

(re-frame/reg-event-fx
 :console
 (fn [ceft evt]
   (.log js/console (pr-str evt))))

(defmulti conj-by-topic (fn [_ topic _] topic))

(defmethod conj-by-topic :detail [events topic evt]
  (.log js/console (pr-str (:caudal-client/ts evt)))
  (doall (take 100 (conj events evt))))

(defmethod conj-by-topic :aggregate [events topic {:keys [place alias uuid eventName count] :customs/keys [camera] :as e}]
  (let [place-map (->> (get events place)
                       (filter (fn [[d-alias {d-uuid :uuid}]]
                                 (= d-uuid uuid)))
                       (into {}))
        place-map (assoc place-map camera e)]
    (assoc events place place-map)))


(re-frame/reg-event-db
 :caudal/update
 (fn [db [_ topic {:keys [aiTime alias eventName count desc] :as evt}]]
   (let []
     (update-in db [:customs topic]
                conj-by-topic topic evt))))

(re-frame/reg-event-fx
 :connect
 (fn [_ [_]]
   {:dispatch [:start-server-comm]}))

(re-frame/reg-event-fx
 :register-topics
 (fn [{:keys [db]} [_ topic]]
   (let [topics (:topics db)
         send-fn (get-in db [:communication :send-fn])]
     (when (seq topics)
       (.log js/console (pr-str topics))
       (send-fn [:caudal/subscribe topics]))
     {})))


(defmulti ws-event-handler :id :default :default)

(defmethod ws-event-handler :default [evt]
  (.log js/console (pr-str (:id evt)) " --> " (pr-str evt)))

(defmethod ws-event-handler :chsk/handshake [evt]
  (.log js/console "chsk/handshake"))


(defmethod ws-event-handler :caudal/waiting-subscriptions [{:keys [id event send-fn] :as evt}]
  (re-frame/dispatch [:register-topics]))

(defmethod ws-event-handler :caudal/update [{:keys [id event send-fn] :as evt}]
  (let [[_ [_ caudal-event]] event]
    (let [{:caudal/keys [topic]} caudal-event]
      (re-frame/dispatch-sync [:caudal/update (keyword topic) (assoc caudal-event :caudal-client/ts (now))]))))

(defmethod ws-event-handler :caudal/admin [{:keys [id event send-fn] :as evt}]
  (.log js/console (pr-str event)))

(defmethod ws-event-handler :caudal/ws-pong [{:keys [id event send-fn] :as evt}]
  (.log js/console "server says pong!"))

(defmethod ws-event-handler :chsk/ws-ping [{:keys [id event send-fn] :as evt}]
  (.log js/console "server says ping!")
  (send-fn [:caudal-client/ws-pong]))

; Este es el encargado de unwrap el tipo de evento
(defmethod ws-event-handler :chsk/recv [{:keys [id event send-fn] :as evt}]
  (.log js/console (pr-str evt))
  (let [[_ [verb data]] event]
    (ws-event-handler (assoc evt :id verb))))


(re-frame/reg-event-fx
  :start-server-comm
  (fn [{:keys [db]} [_ {:keys []} :as e]]
    (let [{:keys [chsk ch-recv send-fn state] :as comms}
          (sente/make-channel-socket-client! "wslisten" ; url-base  Note the same path as before
                                             {:type :auto   ; e/o #{:auto :ajax :ws}
                                              })]
      (sente/start-client-chsk-router! ch-recv ws-event-handler)
      (println ">>>>>> started!" )
      {:db (-> db
               (assoc :communication comms))
       })))

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
