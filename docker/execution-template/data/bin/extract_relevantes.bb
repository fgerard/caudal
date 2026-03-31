#!/usr/bin/env bb

(ns extract-relevantes
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn])
  (:import (java.util Base64)))

(defn write-base64-image! [output-file b64]
  (let [decoder (Base64/getDecoder)
        bytes (.decode decoder b64)]
    (with-open [out (io/output-stream output-file)]
      (.write out bytes))))

(defn proces-relevantes [relevantes-file filter-fn output-root-file]
  (with-open [rdr (io/reader relevantes-file)]
    (let [events (->> rdr line-seq (map edn/read-string) (filter filter-fn))]
      (doseq [evt events]
        (let [value (.toLowerCase (:value evt))
              ai-time (:aiTime evt)
              accuracy (:accuracy evt)
              camera (:camera evt)
              img_name (format "%s_%s_%s_%s.jpg" value ai-time camera accuracy)
              output-file (io/file output-root-file img_name)]
          ;; Asegurar que el directorio existe
          (.mkdirs (.getParentFile output-file))          
          ;; Escribir la imagen decodificada
          (write-base64-image! output-file (:clip evt))
          (println (str "Procesado: " output-file)))))))

(defn -main [& args]
  (if (< (count args) 3)
    (println "Uso: extract-relevantes <archivo-relevantes> <directorio-salida> <filtro-cameras>")
    (let [relevantes-file (nth args 0)
          output-root-file (nth args 1)
          filter-str (nth args 2)
          filter-fn #(re-matches (re-pattern filter-str) (:camera %))]
      (proces-relevantes relevantes-file filter-fn (io/file output-root-file)))))

(let [args *command-line-args*]
  (if (not= (count args) 3)
    (println "Uso: ./extract_relevantes.bb archivo-de-relevantes directorio-salida re_de_cameras")
    (apply -main args)))