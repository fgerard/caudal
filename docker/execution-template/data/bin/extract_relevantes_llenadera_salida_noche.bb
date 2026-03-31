#!/usr/bin/env bb
(ns extract-relevantes-llenadera-salida-noche
  (:require
   [clojure.string :as S]
   [clojure.java.io :as io]
   [clojure.data.xml :as xml])
  (:import
   (java.util Base64)
   [java.io ByteArrayInputStream FileOutputStream]
   [java.time Instant ZonedDateTime ZoneId]))

(def decoder (Base64/getDecoder))

 (defn write-voc-annotation
   "Genera una imagen JPG y un XML en formato VOC desde un mapa de detecciones.
   - `path` es el directorio base donde guardar los archivos
   - `prefix` es el prefijo del nombre del archivo
   - `annotate` es un mapa que debe tener las llaves: :objects, :width, :height, :image (base64 JPG) y opcionalmente :scale 
   "
   [ds-path scale annotate]
   (if annotate
     (let [{:keys [objects image camera eventName aiTime]} annotate
           basename  (str camera "-" (name eventName) "-" aiTime)
           img-dir   (io/file ds-path)
           ann-dir   (io/file img-dir "annotations")
           img-file  (io/file img-dir (str basename ".jpg"))
           xml-file  (io/file ann-dir (str basename ".xml"))
           decoded (.decode decoder (.getBytes image))
           in      (ByteArrayInputStream. decoded)
           original-img (javax.imageio.ImageIO/read in)
           width (.getWidth original-img)
           height (.getHeight original-img)
           new-width  (int (/ width scale))
           new-height (int (/ height scale))
           scaled-img (java.awt.image.BufferedImage. new-width new-height java.awt.image.BufferedImage/TYPE_INT_RGB)
           g (.createGraphics scaled-img)
           scaled-objects (map (fn [[cls score xmin ymin xmax ymax]]
                                 [cls
                                  score
                                  (int (/ xmin scale))
                                  (int (/ ymin scale))
                                  (int (/ xmax scale))
                                  (int (/ ymax scale))])
                               objects)
           new-width  (int (/ width scale))
           new-height (int (/ height scale))
           annotation (xml/element :annotation {}
                                   (xml/element :folder {} (.getName img-dir))
                                   (xml/element :filename {} (str basename ".jpg"))
                                   (xml/element :size {}
                                                (xml/element :width {} new-width)
                                                (xml/element :height {} new-height)
                                                (xml/element :depth {} 3))
                                   (map (fn [[cls score xmin ymin xmax ymax]]
                                          (xml/element :object {}
                                                       (xml/element :name {} cls)
                                                       (xml/element :score {} score)
                                                       (xml/element :bndbox {}
                                                                    (xml/element :xmin {} xmin)
                                                                    (xml/element :ymin {} ymin)
                                                                    (xml/element :xmax {} xmax)
                                                                    (xml/element :ymax {} ymax))))
                                        scaled-objects))]

       ;; Asegura que existan los directorios
       (.mkdirs img-dir)
       (.mkdirs ann-dir)
       (println "Writing VOC annotation to" (.getAbsolutePath xml-file))
       ;; Escala la imagen
       (.drawImage g original-img 0 0 new-width new-height nil)
       (.dispose g)

       ;; Guarda la nueva imagen
       (javax.imageio.ImageIO/write scaled-img "jpg" img-file)

       (with-open [w (io/writer xml-file)]
         (xml/emit annotation w))
       (merge annotate
              {:ds-image (.getAbsolutePath img-file)
               :ds-annon (.getAbsolutePath xml-file)
               :width-scaled    (int (/ width scale))
               :height-scaled   (int (/ height scale))
               :objects-scaled  (vec scaled-objects)}))
     (do
       (println (if (.exists (io/file ds-path)) "No ':annotate' key found in event, skipping VOC annotation." (format "DS path '%s' does not exist, skipping VOC annotation." ds-path)))
       annotate)))

(defn getHH [zone ts]

  (-> (Instant/ofEpochMilli ts)
      (ZonedDateTime/ofInstant zone)
      (.getHour)))

(defn parse-horarios [horarios-str]
  (mapv 
   (fn [s] 
     (into [] (map read-string (S/split s #"-")))) 
   (S/split horarios-str #"_")))

(defn en-rango? [parejas-horarios hh]
  (some (fn [[start end]]
          (and (<= start hh) (< hh end)))
        parejas-horarios))

;horarios-str HH-HH_HH-HH...
;Hopra de inicio- hora fin, inicio-fin, etc
(defn main [relevant-path output-path camera-re event-re parejas-horarios]
  (println (format "Extrayendo relevantes de %s horarios: %s" relevant-path parejas-horarios))
  (let [zona (ZoneId/of "America/Mexico_City")
        getH (partial getHH zona)
        relevante-seq (line-seq (io/reader relevant-path))
        relevantes (map #(read-string %) relevante-seq)
        filtrados (filter (fn [{:keys [aiTime camera eventName]}]
                            (let [h (getH aiTime)]
                              (and
                               (re-matches camera-re camera)
                               (re-matches event-re (name eventName))
                               (en-rango? parejas-horarios h))))
                          relevantes)]
    (loop [[relevante & relevantes] filtrados aiTime-anterior 0]
      (when relevante
        (let [{:keys [aiTime]} relevante
              delta (- aiTime aiTime-anterior)
              create-it (> delta (* 1000 60 5))
              new-aiTime (if create-it aiTime aiTime-anterior)]
          (when create-it
            (write-voc-annotation output-path 3 relevante))
          (recur relevantes new-aiTime))))))
      

(let [args *command-line-args*]
  (if-not (= 5 (count args))
    (println "Uso: bb extract_relevantes_llenadera_salida_noche.bb <ruta-a-relevantes> <ruta-salida> camara-re event-re HH1-HH1_HH2-HH2...")
    (let [[relevant-path out-path camera-re event-re horario-str] args
          parejas-horarios (parse-horarios horario-str)]
      (main relevant-path out-path (re-pattern camera-re) (re-pattern event-re) parejas-horarios))))

(comment
  (def aiTime 1762344420726)
  (getHH (ZoneId/of "America/Mexico_City") aiTime)
  (def now (System/currentTimeMillis))
  (getHH (ZoneId/of "America/Mexico_City") now)

  (def horario-str "0-06_18-23")
  (def parejas-horarios (parse-horarios horario-str))
  (defn en-rango? [parejas-horarios hh]
    (some (fn [[start end]]
            (and (<= start hh) (< hh end)))
          parejas-horarios))
  parejas-horarios
  (en-rango? parejas-horarios 18)
  )
