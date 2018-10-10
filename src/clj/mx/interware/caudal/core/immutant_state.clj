;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns mx.interware.caudal.core.immutant-state
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [mx.interware.caudal.core.state :as state]
            [immutant.caching :as C])
  (:import (java.net InetAddress URL)
           (org.apache.log4j PropertyConfigurator)))

(defn fix-k [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn swap-in-evt!
  "Atomically swaps the value associated to the key in the cache with
  the result of applying f, passing the current value as the first
  param along with any args, returning the new cached value. Function
  f should have no side effects, as it may be called multiple times.
  If the key is missing, the result of applying f to nil will be
  inserted atomically."
  [^org.infinispan.Cache cache key f & args]
  (loop [val (get cache key)]
    (let [new (apply f val args)]
      (if (or val (contains? cache key))
        (if (.replace cache key val new (:ttl new -1) java.util.concurrent.TimeUnit/MILLISECONDS)
          new
          (recur (get cache key)))
        (if (nil? (.putIfAbsent cache key new (:ttl new -1) java.util.concurrent.TimeUnit/MILLISECONDS))
          new
          (recur (get cache key)))))))

(deftype caudal-store-immutant [immutant-cache]
  state/caudal-store
  (as-map [this]
    (into {} immutant-cache))
  (store! [[this k v]]
    (let [cache (if (:ttl v)
                  (C/with-expiration immutant-cache :ttl (:ttl v))
                  immutant-cache)]
      (C/swap-in! cache (fix-k k) (fn [_] v))))
  (clear-all! [this]
    (.clear immutant-cache))
  (clear-keys! [this clear-key-fn?]
    (doseq [entry immutant-cache]
      (let [k (.getKey entry)]
        (when (clear-key-fn? k)
          (.remove immutant-cache k)))))
  (lookup [this k]
    (get immutant-cache (fix-k k)))
  (lookup [this k default]
    (get immutant-cache (fix-k k) default))
  (remove! [[this k]]
    (.remove immutant-cache k))
  (update! [this k fun]
    (swap-in-evt! immutant-cache (fix-k k) fun))
  (update! [this k fun a1]
    (swap-in-evt! immutant-cache (fix-k k) fun a1))
  (update! [this k fun a1 a2]
    (swap-in-evt! immutant-cache (fix-k k) fun a1 a2))
  (update! [this k fun a1 a2 a3]
    (swap-in-evt! immutant-cache (fix-k k) fun a1 a2 a3))
  (update! [this k fun a1 a2 a3 a4]
    (swap-in-evt! immutant-cache (fix-k k) fun a1 a2 a3 a4))
  (update! [this k fun a1 a2 a3 a4 a5]
    (swap-in-evt! immutant-cache (fix-k k) fun a1 a2 a3 a4 a5))
  (update! [this k fun a1 a2 a3 a4 a5 a6]
    (swap-in-evt! immutant-cache (fix-k k) fun a1 a2 a3 a4 a5 a6))
  (update! [this k fun a1 a2 a3 a4 a5 a6 a7]
    (swap-in-evt! immutant-cache (fix-k k) fun a1 a2 a3 a4 a5 a6 a7))
  (update! [this k fun a1 a2 a3 a4 a5 a6 a7 a8]
    (swap-in-evt! immutant-cache (fix-k k) fun a1 a2 a3 a4 a5 a6 a7 a8))
  (update! [this k fun a1 a2 a3 a4 a5 a6 a7 a8 a9]
    (swap-in-evt! immutant-cache (fix-k k) fun a1 a2 a3 a4 a5 a6 a7 a8 a9))
  (update! [this k fun a1 a2 a3 a4 a5 a6 a7 a8 a9 a10]
    (swap-in-evt! immutant-cache (fix-k k) fun a1 a2 a3 a4 a5 a6 a7 a8 a9 a10)))

(defmethod state/create-store 'mx.interware.caudal.core.immutant-state
  [type & {:keys [default-ttl event-sink infinispan-events]
           :or   {default-ttl       -1
                  infinispan-events [:cache-entry-removed]}}]
  (let [cache (C/cache "intern-immutant-cache" :ttl default-ttl)]
    (when event-sink
      (apply C/add-listener! cache event-sink
             infinispan-events))
    (caudal-store-immutant. cache)))
