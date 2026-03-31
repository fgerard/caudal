(ns extract-ds-from-relevantes
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import (java.util Base64)
           (javax.imageio ImageIO)
           (java.awt.image BufferedImage)
           (java.io ByteArrayInputStream)))

(defn read-edn-maps [filename tipo]
  "Reads a file containing EDN maps separated by new lines and returns them as a vector."
  (with-open [reader (io/reader filename)]
    (vec (filter #(= tipo (:type %)) (map edn/read-string (line-seq reader))))))

(defn decode-base64-to-bufferedimage [base64-str]
  "Decodes a base64 string to a BufferedImage."
  (let [decoder (Base64/getDecoder)
        bytes (.decode decoder base64-str)
        bais (ByteArrayInputStream. bytes)]
    (ImageIO/read bais)))

(defn save-image [image uuid]
  "Saves a BufferedImage to a file named after the UUID."
  (let [filename (str uuid ".jpg")]
    (ImageIO/write image "jpg" (io/file filename))
    (println "Saved image:" filename)))

(defn process-maps [maps]
  "Processes each map, decoding the image and saving it with the UUID as filename."
  (doseq [m maps]
    (let [uuid (:uuid m)
          base64-image (:image m)]
      (when (and uuid base64-image)
        (let [image (decode-base64-to-bufferedimage base64-image)]
          (save-image image uuid))))))

(defn -main [& args]
  (let [[filename tipo] args]
    (if filename
      (let [maps (read-edn-maps filename (keyword tipo))]
        (println "Read EDN maps:" (count maps))
        (process-maps maps))
      (println "Please provide a filename as argument."))))
