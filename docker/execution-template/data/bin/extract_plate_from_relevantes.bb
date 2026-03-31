#!/usr/bin/env bb

(require '[clojure.java.io :as io]
         )

(defn file-name [{:keys [camera aiTime value accuracy]}]
  (format "%s_%s_%.2f_%s.jpg" value camera accuracy aiTime))

(defn process-file [out-dir relevantes-file tipo min-acc]
  (let [decoder (java.util.Base64/getDecoder)
        lines (filter #(re-find (re-pattern tipo) %) (line-seq (io/reader relevantes-file)))
	recs (->> (map read-string lines)
                  (filter (fn [{:keys [type accuracy]}]
                            (and (= (name type) tipo) (> accuracy min-acc)))))
        d-recs (for [r recs
                     :let [out-name (file-name r)
                           clip (:clip r)]]
                 {:out-file (io/file out-dir out-name)
                  :clip (.decode decoder clip)})
       ]
    (loop [[{:keys [out-file clip]} & d-recs] d-recs n 1]
      (when clip
        (println (format "%s -> %s" n out-file))
        (with-open [out (io/output-stream out-file)]
          (.write out clip))
        (recur d-recs (inc n))))))
        

(try 
  (let [[relevant out-dir tipo min-acc] *command-line-args*
        relevant-file (io/file relevant)
        out-dir (io/file out-dir)
        min-acc (Double/parseDouble min-acc)]
    (println (pr-str [:procesando relevant-file (.exists relevant-file)]))
    (println :process-file (process-file out-dir relevant-file tipo min-acc)))
  (catch Exception e
    (println e)
    (println "Uso: relevant-file out-dir tipo min-accuracy")))
     
