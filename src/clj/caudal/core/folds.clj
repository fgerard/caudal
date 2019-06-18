;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns caudal.core.folds
  (:refer-clojure :exclude [min max])
  (:require [clojure.tools.logging :as log]))

(defn non-nil-fld [fld-key events]
  (filter #(fld-key %) events))

(defn with-numeric-fld [fld-key events]
  (filter #(number? (fld-key %)) events))

(defn fold
  ([fld-key reduction-fn events]
   (let [[e & events :as all-events] (non-nil-fld fld-key events)]
     (if (and e (seq events))
       (assoc e fld-key (reduce reduction-fn (map fld-key all-events)))
       e)))
  ([reduction-fn events]
   (fold :metric reduction-fn events)))

(defn- avg [nums]
  (/ (reduce + nums) (count nums)))

(defn mean
  ([fld-key events]
   (let [[e & events :as all-events] (with-numeric-fld fld-key events)]
     (if (and e (seq events))
       (assoc e fld-key (/ (reduce + (map fld-key all-events)) (count all-events)))
       e)))
  ([events]
   (mean :metric events)))

(defn simple-mean&stdev [metrics]
  (let [mean      (double (avg metrics))
        sqr-avg-x (Math/pow mean 2)
        avg-sqr-x (double (avg (map #(* % %) metrics)))
        n         (count metrics)
        variance  (- avg-sqr-x sqr-avg-x)
        stdev     (Math/sqrt variance)]
    [mean stdev variance n]))


(defn mean&std-dev
  "Este fold revibe un vector de eventos, selecciona los eventos cuyo 'metric-key'
  es numérico, luego calcula el promedio y la desvuación estandar, regresa el primer evento
  de la coleccion'events' asociando 'mean-key' con el promedio, 'stdev-key' con la desviación
  estandar y 'count-key' con el número de eventos con métrica numérica"
  [metric-key mean-key variance-key stdev-key count-key events]
  (let [;_ (log/debug "***" events)
        metrics   (vec (map metric-key (with-numeric-fld metric-key events)))
        mean      (avg metrics)
        sqr-avg-x (Math/pow mean 2)
        avg-sqr-x (avg (map #(* % %) metrics))
        n         (count metrics)
        ;_ (log/debug "***** n:" n)
        variance  (- avg-sqr-x sqr-avg-x)
        ;variance (/ (reduce + (map (fn [n]
        ;                             (let [dif (- n mean)]
        ;                               (* dif dif)) metrics n;(- avg-sqr-x sqr-avg-x)
        stdev     (Math/sqrt variance)]
    ;total (reduce + metrics)
    ;mean (double (/ total n))
    ;cuadrados (map (fn [m avg] (Math/pow (- m avg) 2)) metrics (repeat mean))
    ;varianza (/ (reduce + cuadrados) n)
    ;stdev (Math/sqrt varianza)]
    (assoc (first events) mean-key mean variance-key variance stdev-key stdev count-key n)))

(defn mean&stddev-welford
  "This fold computes mean, stdev and variance using welford method"
  [metric-key mean-key sum-of-squares-key variance-key stdev-key count-key events]
  (let [metrics   (vec (map metric-key (with-numeric-fld metric-key events)))
        stats (reduce
                (fn [{mean mean-key sum-of-sqrs sum-of-squares-key n count-key} metric]
                  (let [kth      (inc n)
                        m-kth1   (+ mean (/ (- metric mean) kth))
                        s-kth1   (+ sum-of-sqrs (* (- metric mean) (- metric m-kth1)))
                        variance (/ s-kth1 kth)
                        stdev    (Math/sqrt variance)]
                    {mean-key m-kth1 sum-of-squares-key s-kth1 count-key kth variance-key variance stdev-key stdev}))
                {mean-key (first metrics) sum-of-squares-key (double 0) variance-key (double 0) stdev-key (double 0) count-key 1}
                (rest metrics))]
    (merge (first events) stats)))

(defn rate-bucket-welford
  "This function computes welford in a bucket the bucket contains a vector [mean {...}]"
  [metric-key mean-key variance-key stdev-key sum-of-squares-key count-key]
  (fn [{mean mean-key
        variance variance-key
        stdev stdev-key
        sum-of-squares sum-of-squares-key
        count count-key
        :as current-bucket}
       {metric metric-key}]
    (if metric
      (if count
        (let [kth      (inc count)
              m-kth1   (+ mean (/ (- metric mean) kth))
              s-kth1   (+ sum-of-squares (* (- metric mean) (- metric m-kth1)))
              variance (/ s-kth1 kth)
              stdev    (Math/sqrt variance)]
          {mean-key m-kth1 sum-of-squares-key s-kth1 count-key kth variance-key variance stdev-key stdev :caudal/height m-kth1})
        {mean-key metric variance-key 0 sum-of-squares-key 0 stdev-key 0 count-key 1 :caudal/height metric})
      current-bucket)))

(defn intern-apply-fn
  ([apply-fn fld-key events]
   (log/debug :fld-key fld-key)
   (let [[e & events :as all-events] (with-numeric-fld fld-key events)]
     (if (and e (seq events))
       (assoc e fld-key (apply apply-fn (map fld-key all-events)))
       e)))
  ([apply-fn events]
   (intern-apply-fn apply-fn :metric events)))

(defn max
  ([fld-key events]
   (intern-apply-fn max fld-key events))
  ([events]
   (intern-apply-fn max events)))

(defn min
  ([fld-key events]
   (intern-apply-fn min fld-key events))
  ([events]
   (intern-apply-fn min events)))

(defn sum
  ([fld-key events]
   (intern-apply-fn + fld-key events))
  ([events]
   (intern-apply-fn + events)))

(comment
  (def T (vec (map (fn [n] (if (= 4 n) {:m n} {:metric n :m n})) (range 1 6))))
  (fold :m + T)
  (fold + T)
  (mean T)
  (max :m T)
  (min T)
  (sum T))
