(ns caudal.test.dp-zurich
  (:require [clojure.java.io :as io]))

(defn read-test [in-file]
  (with-open [in (io/reader in-file)]
    (let [lines (line-seq in)]
      (reduce
       (fn [[phases not-phase errors] {:keys [phase error] :as e}]
         ;(if-not phase (println (pr-str e)))
         [(if phase
            (conj phases phase)
            phases)
          (if-not phase (inc not-phase) not-phase)
          (if error (inc errors) errors)])
       [#{} 0 0]
       (remove :tailer/error
               (map (fn [line]
                      (read-string line)) lines))))))
