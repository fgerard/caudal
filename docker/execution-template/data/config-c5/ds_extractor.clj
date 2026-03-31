(ns ds-extractor
   (:require [clojure.java.io :as io]
             [clojure.data.xml :as xml]
             [clojure.data.codec.base64 :as b64]
             [clojure.tools.logging :as log]
             [clojure.core.match :refer [match]]
             [clojure.core.cache :as cache]
             [caudal.streams.stateful :refer [reduce-with changed tx-mgr]]
             [caudal.streams.stateless :refer [by pprinte where split smap time-stampit ->INFO ->WARN ->ERROR reinject]])
   (:import [java.io ByteArrayInputStream FileOutputStream]
            [java.util Date]))


 (defn IoU
   "Calcula el Intersection over Union (IoU) entre dos cajas delimitadoras.
   Cada caja es un vector [xmin ymin xmax ymax].
   Retorna un valor entre 0 y 1."
   [[_ _ x1-min y1-min x1-max y1-max :as obj1]
    [_ _ x2-min y2-min x2-max y2-max :as obj2]]
   (if (and (seq obj1) (seq obj2))
     (let [;; Coordenadas de la intersección
           xA (max x1-min x2-min)
           yA (max y1-min y2-min)
           xB (min x1-max x2-max)
           yB (min y1-max y2-max)

           ;; Área de intersección (si se superponen)
           inter-width  (max 0 (- xB xA))
           inter-height (max 0 (- yB yA))
           inter-area   (* inter-width inter-height)

           ;; Áreas individuales
           box1-area (* (- x1-max x1-min) (- y1-max y1-min))
           box2-area (* (- x2-max x2-min) (- y2-max y2-min))

           ;; Unión
           union-area (+ box1-area box2-area (- inter-area))]

       (if (pos? union-area)
         (/ inter-area union-area)
         0.0))
     0.0))

 (defn write-voc-annotation
   "Genera una imagen JPG y un XML en formato VOC desde un mapa de detecciones.
   - `path` es el directorio base donde guardar los archivos
   - `prefix` es el prefijo del nombre del archivo
   - `annotate` es un mapa que debe tener las llaves: :objects, :width, :height, :image (base64 JPG) y opcionalmente :scale 
   "
   [{:keys [ds-path prefix annotate] :as event}]
   (if (and annotate (.exists (io/file ds-path)))
     (let [{:keys [objects width height image scale] :or {scale 1.0}} annotate
           timestamp (System/currentTimeMillis)
           basename  (str prefix "-" timestamp)
           img-dir   (io/file ds-path)
           ann-dir   (io/file img-dir "annotations")
           img-file  (io/file img-dir (str basename ".jpg"))
           xml-file  (io/file ann-dir (str basename ".xml"))
           decoded (b64/decode (.getBytes image))
           in      (ByteArrayInputStream. decoded)
           original-img (javax.imageio.ImageIO/read in)
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
       (log/info "Writing VOC annotation to" (.getAbsolutePath xml-file))
       ;; Escala la imagen
       (.drawImage g original-img 0 0 new-width new-height nil)
       (.dispose g)

       ;; Guarda la nueva imagen
       (javax.imageio.ImageIO/write scaled-img "jpg" img-file)

       (with-open [w (io/writer xml-file)]
         (xml/emit annotation w))
       (merge event
              {:ds-image (.getAbsolutePath img-file)
               :ds-annon (.getAbsolutePath xml-file)
               :width-scaled    (int (/ width scale))
               :height-scaled   (int (/ height scale))
               :objects-scaled  (vec scaled-objects)}))
     (do
       (log/warn (if (.exists (io/file ds-path)) "No ':annotate' key found in event, skipping VOC annotation." (format "DS path '%s' does not exist, skipping VOC annotation." ds-path)))
       event)))


; event-selector-reducer es la funcion reductora que determina si se crea
; una anotacion voc en el directorio path y path/annotations, la anotacion
; se crea con los nombres <prefix>-<timestamp>.jpg y annotations/<prefix>-<timestamp>.xml
; event-selector-reducer debe aumentar la llava ds-path en el evento para
; indicar que se genera la anotacion voc en ese path y la llave :prefix
; el prefijo de los archivos generados
(defmacro ds-extractor [[event-selector-reducer] & streams]
  `(reduce-with
    [:ds-reducer ~event-selector-reducer]
    (where
     [:ds-path]
     (smap
      [write-voc-annotation]
      ~@streams))))
