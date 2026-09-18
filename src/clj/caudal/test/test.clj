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

;DUPS en app foto de Leon -- referencia a util.http-client, namespace que
;no existe en este proyecto (paste de otro lado) -- comentado con #_ para
;que no rompa la compilacion de :aot :all hasta que se resuelva
#_(defn find-dups-in-app [{:keys [CREDENTIALS] :as ctx}]
  (letfn [(get-all-person []
            (loop [index 0 persons nil]
              (let [resp @(util.http-client/get
                           (format "http://192.168.0.1:8080/rootpeople?start-index=%s&count=1000" index)
                           {:as :string
                            :headers {"Accept" "application/json"
                                      "X-RPC-DIRECTORY" "main"
                                      "X-RPC-AUTHORIZATION" CREDENTIALS}})

                    body (clojure.data.json/read-str (:body resp) :key-fn keyword)

                    people-vec (:people body)
                    n-people (count people-vec)]
                (if (> n-people 0)
                  (recur (+ index n-people) (concat persons people-vec))
                  (into [] (sort-by :name persons))))))

          (paste-it [val v]
            (conj (or val []) v))

          (find-dups [persons]
            (reduce (fn [[name->ext ext->name] {:keys [name externalId]}]
                      (let [name->ext (update name->ext name paste-it externalId)
                            ext->name (update ext->name externalId paste-it  name)]
                        [name->ext ext->name]))
                    [{} {}]
                    persons))]
    (let [persons (get-all-person)
          [name->ext ext->name] (find-dups persons)
          DUPS (into {} (filterv #(> (count (second %)) 1) name->ext))]
      {:find-dups (if (seq DUPS) (with-out-str (clojure.pprint/pprint DUPS)) "No hay dups")})))