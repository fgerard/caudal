;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns caudal.util.matrix
  (:require [clojure.tools.logging :as log]))

(defn m-size [m]
  [(count m) (count (first m))])

(defn m-zero [r c]
  (vec (repeat r (vec (repeat c 0)))))

(defn m-get [m r c]
  (-> m (get r) (get c)))

(defn m-set [m r c v]
  (let [R (get m r)]
    (assoc m r (assoc R c v))))

(defn m-inc [m r c]
  (try
    (m-set m r c (inc (m-get m r c)))
    (catch Exception e
      (.printStackTrace e)
      (log/error :m m :r r :c c)
      (System/exit 0))))

(defn m-insert-zero-row [m]
  (let [[r c] (m-size m)]
    (vec (butlast (cons (vec (repeat c 0)) m)))))
