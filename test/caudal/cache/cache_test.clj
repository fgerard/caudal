(ns caudal.cache.cache-test
  (:require [clojure.core.cache :as cache]))

(defn make-refreshing-cache [max-size ttl-ms]
  (atom (-> {}
            (cache/lru-cache-factory :threshold max-size)
            (cache/ttl-cache-factory :ttl ttl-ms))))

(defn put! [c k]
  (swap! c cache/miss k k))

(defn get! [c k]
  (let [v (cache/lookup @c k)]
    (when v
      ;; Reinserta el valor con nueva marca de tiempo
      (swap! c cache/miss k v))
    v))


(def C (make-refreshing-cache 3 100000))

(do
  (put! C :a)
  (Thread/sleep 1000)
  (println @C)
  (put! C :b)
  (Thread/sleep 1000)
  (println @C)
  (put! C :c)
  (Thread/sleep 1000)
  (println @C)
  (put! C :d)
  (println @C)

  (Thread/sleep 3000)
  (println @C)
  (Thread/sleep 1000)
  (println @C)
  (Thread/sleep 1000)
  (println @C)
  (Thread/sleep 1000)
  (println @C)
  (Thread/sleep 1000)
  (println @C) 
  (println "ya")
  )

(get! C :a)
(get! C :b)
(get! C :c)
(get! C :d)
(get! C :x)
(get! C :y)
(put! C :x)
(put! C :y)

@C

