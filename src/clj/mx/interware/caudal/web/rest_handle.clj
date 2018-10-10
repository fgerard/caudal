;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns mx.interware.caudal.web.rest-handle
  (:require [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [clojure.walk :refer [postwalk-replace]]
            [ring.util.response :refer [response]]
            [mx.interware.caudal.util.date-util :as d]
            [mx.interware.caudal.streams.common :as common]
            [mx.interware.caudal.core.starter-dsl :refer [name&version]]
            [mx.interware.caudal.util.error-codes :as error-codes]))

(def start-time (System/currentTimeMillis))

(defn index-handler
  [request]
  (let [[name version] (name&version)]
    {:status 200
     :body {:app name :version version :uptime (- (System/currentTimeMillis) start-time)}}))

(defn item-handler
  [{:keys [route-params] :as request}]
  (response (str "Item id : " (:id route-params) ", name : " (:name route-params))))

(defn event-handler
  [sink request]
  (try
    (let [event (:body-params request)]
      (log/debug (pr-str event))
      (sink event)
      {:status 202})
    (catch Exception e
      (do
        (.printStackTrace e)
        {:status 500 :body (str (-> e class .getName) " -> " (.getMessage e))}))))

(defn build-query-key [{:keys [key by1 by2 by3 by4 by5] :as params}]
  (log/debug "params:" (pr-str params))
  (let [query-key (if key
                    (vec
                     (concat [(keyword key)] (vec (filter identity [by1 by2 by3 by4 by5])))))]
    (log/debug "query-key: " query-key)
    query-key))

;(reduce
; (fn [result val]
;   (conj result val))
; [(keyword key)]
; (take-while identity [by1 by2 by3 by4 by5]))

;(defn jsonify [state]
  ;(println "STATE:")
  ;(clojure.pprint/pprint state)
;  (postwalk-replace {:aborter "chan"} state))

(defn get-state-or-query-key [state state-key]
  (log/debug :state-key state-key (nil? (state state-key)))
  (if (nil? state-key)
    state
    (if-let [info (state state-key)]
      info
      (let [d-keys (keys state)
            len (count state-key)]
        (vec (filter (fn [k]
                       (when (vector? k)
                         (= state-key (vec (take len k))))) d-keys))))))

(defn state-handler
  [states {:keys [params] :as request}]
  (try
    (let [id-key (and (:id params) (keyword (:id params)))
          the-state (and id-key (id-key states))
          _ (log/debug :the-state (if the-state (take 10 (keys @the-state))) id-key params (build-query-key params))
          result (if the-state
                   (get-state-or-query-key
                    (common/caudal-state-as-map the-state)
                    (build-query-key params))
                   (vec (keys states)))
          resp (response (or result
                             (str (build-query-key params)
                                  " NOT FOUND on caudal state")))]
      (log/debug :resp resp)
      resp)
    (catch Exception e
      (do
        (log/warn "State not found for : " params ". Reason :" (.getMessage e))
        (.printStackTrace e)
        (response {:success false :date (d/current-date)})))))
