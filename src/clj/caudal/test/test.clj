(ns caudal.test.test
  (:require [clojure.string :as str]
            [clojure.pprint :as pp]))

(defn kaprekar [n]
  (let [as-seq (seq (str n))
        orden (sort as-seq)
        rev (reverse orden)
        small (Integer/parseInt (apply str orden))
        big (Integer/parseInt (apply str rev))]
    [big small (- big small)]))

(kaprekar 8544)
(kaprekar 4086)
(kaprekar 8172)
(kaprekar 7443)
(kaprekar 3996)
(kaprekar 6264)
(kaprekar 4176)

(defn find-kaprekar [n]
  (loop [current n
         seen #{}
         d-seq []]
    (if (seen current)
      d-seq
      (let [[big small diff :as v] (kaprekar current)]
        (recur diff
               (conj seen current)
               (conj d-seq v))))))

(pp/pprint (find-kaprekar 290))
(pp/pprint (find-kaprekar 123456))
(find-kaprekar 3278)
(find-kaprekar 3087)
(/ 6174 2)
(kaprekar 1288)

(conj [] [1 2 3] )






(def n (sort (seq (str 2341))))
n
(apply str n)
(str (str/join n))