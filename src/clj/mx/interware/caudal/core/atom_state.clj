;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns mx.interware.caudal.core.atom-state
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.data :refer [diff]]
            [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer [go-loop timeout <!]]
            [mx.interware.caudal.core.state :as state])
  (:import (java.net InetAddress URL)
           (org.apache.log4j PropertyConfigurator)))

(defn stamp-it
  "fun es una funcion de arity variable que su primer parametro es un valor en el hash
   (un evento o nil si no existe aun) y los odemas argumentos son otras cosas"
  [default-ttl fun]
  (fn [evt & args]
    (let [result (apply fun evt args)]
      (assoc result
        :ttl (:ttl result default-ttl)
        :touched (System/currentTimeMillis)))))

(defn- purge-ttl [now result [k evt]]
  (if-let [ttl (and (not= -1 (:ttl evt)) (:ttl evt))]
    (let [touched (:touched evt)]
      (if (< (+ touched ttl) now)
        (dissoc result k)
        result))
    result))

(defn purge-it! [atm-val]
  (let [now (System/currentTimeMillis)]
    (reduce (partial purge-ttl now) atm-val atm-val)))

(defn swapit! [atm fun k & args]
  (get (apply swap! atm fun k args) k))

(deftype caudal-store-atom [default-ttl atom-map]
  state/caudal-store
  (as-map [this]
    @atom-map)
  (store! [[this k v]]
    (swapit! atom-map update k (fn [_] (assoc v
                                         :ttl (:ttl v default-ttl)
                                         :touched (System/currentTimeMillis)))))
  (clear-all! [this]
    (reset! atom-map {}))

  (clear-keys! [this clear-key-fn?]
    (swap! atom-map (fn [data]
                      (reduce
                        (fn [result [k v]]
                          (if (clear-key-fn? k)
                            (dissoc result k)
                            result))
                        data
                        data))))

  (lookup [this k]
    (@atom-map k))
  (lookup [this k default]
    (@atom-map k default))
  (remove! [[this k]]
    (swapit! atom-map dissoc k))
  (update! [this k fun]
    (swapit! atom-map update k (stamp-it default-ttl fun)))
  (update! [this k fun a1]
    (swapit! atom-map update k (stamp-it default-ttl fun) a1))
  (update! [this k fun a1 a2]
    (swapit! atom-map update k (stamp-it default-ttl fun) a1 a2))
  (update! [this k fun a1 a2 a3]
    (swapit! atom-map update k (stamp-it default-ttl fun) a1 a2 a3))
  (update! [this k fun a1 a2 a3 a4]
    (swapit! atom-map update k (stamp-it default-ttl fun) a1 a2 a3 a4))
  (update! [this k fun a1 a2 a3 a4 a5]
    (swapit! atom-map update k (stamp-it default-ttl fun) a1 a2 a3 a4 a5))
  (update! [this k fun a1 a2 a3 a4 a5 a6]
    (swapit! atom-map update k (stamp-it default-ttl fun) a1 a2 a3 a4 a5 a6))
  (update! [this k fun a1 a2 a3 a4 a5 a6 a7]
    (swapit! atom-map update k (stamp-it default-ttl fun) a1 a2 a3 a4 a5 a6 a7))
  (update! [this k fun a1 a2 a3 a4 a5 a6 a7 a8]
    (swapit! atom-map update k (stamp-it default-ttl fun) a1 a2 a3 a4 a5 a6 a7 a8))
  (update! [this k fun a1 a2 a3 a4 a5 a6 a7 a8 a9]
    (swapit! atom-map update k (stamp-it default-ttl fun) a1 a2 a3 a4 a5 a6 a7 a8 a9))
  (update! [this k fun a1 a2 a3 a4 a5 a6 a7 a8 a9 a10]
    (swapit! atom-map update k (stamp-it default-ttl fun) a1 a2 a3 a4 a5 a6 a7 a8 a9 a10)))

(defmethod state/create-store 'mx.interware.caudal.core.atom-state
  [type & {:keys [default-ttl ttl-delay expired-sink]
           :or   {default-ttl -1 ttl-delay 5000}}]
  (let [atom-map (atom {})
        d-store  (->caudal-store-atom default-ttl atom-map)]
    (go-loop []
      (<! (timeout ttl-delay))
      (when-let [[expired-items _ _] (diff
                                       @atom-map
                                       (swap! atom-map purge-it!))]
        (when (and expired-sink (seq expired-items))
          (doseq [[k v] expired-items]
            (expired-sink v))))
      (recur))
    d-store))
