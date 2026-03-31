(ns main
  (:require
   [constants :as C]
   ;[env.constants :as EC]
   ;[ds-extractor :as DSE]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   ;[clojure.core.match :refer [match]]
   [clojure.edn :as edn]
   [clojure.string :as ST]
   [clojure.pprint :as pp]
   [clojure.core.cache :as cache]
   [clojure.set :as S]
   [byte-streams :as bs]
   ;[clj-fuzzy.metrics :refer [jaro jaro-winkler]]
   ;[defun.core :refer [defun]]
   [caudal.streams.common :refer [defsink deflistener wire]]
   [caudal.io.rest-server :refer [web]]
   [caudal.streams.stateful :refer [reduce-with #_changed #_tx-mgr]]
   [caudal.streams.stateless :refer [by #_pprinte where split smap time-stampit #_->INFO ->WARN ->ERROR #_reinject]]
   ;[caudal.io.telegram :refer [send-photo send-text]]
   ;[caudal.io.email :refer [mailer email-event-with-body-fn]]
   [cheshire.core :refer [parse-string]]
   [send-events :as SE])
  (:import
   (java.util #_Random UUID)
   (java.util Base64)
   #_(java.net InetAddress)
   (java.io ByteArrayInputStream ByteArrayOutputStream)
   (javax.imageio ImageIO)
   (java.awt.image BufferedImage)
   (java.time Instant ZoneId ZonedDateTime)))

(log/info "Starting streamer")
(log/info (pr-str {:CAUDAL_HOME C/CAUDAL_HOME}))
(log/info (pr-str {:CAUDAL_CONFIG C/CAUDAL_CONFIG}))


(defn base64->buffered-image
  "Convierte un string Base64 a BufferedImage."
  [b64]
  (let [decoder (Base64/getDecoder)
        bytes   (.decode decoder b64)
        bais    (ByteArrayInputStream. bytes)]
    (ImageIO/read bais)))


(defn buffered-image->base64
  "Convierte un BufferedImage a Base64 (formato JPG)."
  [^BufferedImage img]
  (let [baos (ByteArrayOutputStream.)]
    (ImageIO/write img "jpg" baos)
    (.encodeToString (Base64/getEncoder) (.toByteArray baos))))


(defn clip-image
  "Recorta un BufferedImage dado xmin ymin xmax ymax."
  [^BufferedImage img xmin ymin xmax ymax]
  (let [width  (- xmax xmin)
        height (- ymax ymin)]
    (.getSubimage img xmin ymin width height)))


#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn clip-base64-image
  "Recibe Base64 + bbox y regresa Base64 del clip."
  [b64 xmin ymin xmax ymax]
  (let [img       (base64->buffered-image b64)
        clipped   (clip-image img xmin ymin xmax ymax)]
    (buffered-image->base64 clipped)))

(defn validate-file [file-path]
  (when file-path
    (try
      (let [f (io/file file-path)]
        [f (.exists f)])
      (catch Exception e
        (log/error "Error validating file " file-path e)))))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn make-refreshing-cache
  ([max-size ttl-ms]
   (make-refreshing-cache max-size ttl-ms nil))
  ([max-size ttl-ms file-path|]
   (let [[initial-value d-file] (when-let [[d-file exists?] (validate-file file-path|)]
                                  (if exists?
                                    (let [lru-string (slurp d-file)
                                          lru-initial-value (try
                                                              (if (not (seq lru-string))
                                                                "{}"
                                                                (edn/read-string lru-string))
                                                              (catch Exception _
                                                                (log/error "plate.lru.edn mal formado iniciando con {} ")
                                                                {}))
                                          lru-initial-value (if (map? lru-initial-value)
                                                              lru-initial-value
                                                              (do
                                                                (log/error "plate.lru.edn mal formado iniciando con {}")
                                                                {}))]

                                      (log/info "Cargando cache desde" (.getCanonicalPath d-file))
                                      [lru-initial-value d-file])
                                    (do
                                      (log/info "No existe el archivo de cache en" (.getCanonicalPath d-file))
                                      [{} d-file])))
         d-atom (atom (-> (or initial-value {})
                          (cache/lru-cache-factory :threshold max-size)
                          (cache/ttl-cache-factory :ttl ttl-ms)))]
     (when d-file
       (future
         (loop []
           (let [ok? (try
                       (Thread/sleep 60000)
                       (log/info "Guardando cache en " (.getCanonicalPath d-file))
                       (spit d-file (pr-str @d-atom))
                       true
                       (catch Exception e
                         (log/error "Error guardando cache en " (.getCanonicalPath d-file) e)
                         false))]
             (when ok?
               (recur))))))
     d-atom)))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn put-it! [c k v]
  (swap! c cache/miss k v))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn get-it! [c k]
  (let [v (cache/lookup @c k)]
    (when v
      ;; Reinserta el valor con nueva marca de tiempo
      (swap! c cache/miss k v))
    v))

#_(def plate-lru (make-refreshing-cache 1000 (* 1000 60 60 12) "/opt/quantumlabs/ai_streamer/data/plates.lru.edn")) ; cache de placas leidas 12 hrs

#_(def repeated-lru (make-refreshing-cache 1000 (* 1000 60 60))) ; cache de placas leidas recientemente 10 segundos

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn create-uuid [] (str (UUID/randomUUID)))

(defn decode [string]
  (.decode (Base64/getDecoder) string))

#_(defn read-event [e]
  (if-let [json (:json e)]
    (let [d-json json] ;(ST/replace json "Infinity" "10000")]
      ;(log/warn json)
      #_(try
        (parse-string d-json  true)
        (catch Exception e
          (spit "data/d-json.json" d-json)
          (.printStackTrace e)
          (log/error :saliendo)
          (System/exit 0)))
      (parse-string d-json true)) ;{:allow-non-numeric-numbers true}
    (if-let [evt (:body-params e)]
      evt
      (when-let [body (:body e)]
        (let [e (try 
                  (parse-string (slurp (io/reader body)) true)
                  (catch Exception e
                    (log/error (str "ERROR json2 " (.getMessage e)))))]
          e)))))

(defn read-event [req]
  (println (pr-str req))
  (cond

    ;; 🟢 Caso 1: viene JSON como string en :json
    (:json req)
    (json/parse-string (:json req) true)

    ;; 🟢 Caso 2: ya viene parseado (form params / middleware)
    (:body-params req)
    (:body-params req)

    ;; 🟢 Caso 3: viene en el body raw
    (:body req)
    (let [body-str (slurp (:body req))
          content-type (get-in req [:headers "content-type"] "")]
      (println "BODY RAW:" (pr-str body-str))
      (cond
        ;; JSON
        (clojure.string/includes? content-type "application/json")
        (parse-string body-str true)

        ;; EDN
        (clojure.string/includes? content-type "application/edn")
        (read-string body-str)

        ;; fallback
        :else
        body-str))

    ;; 🔴 nada encontrado
    :else
    nil))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn get-now-ts []
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss") (System/currentTimeMillis)))

(defn e-counter [{:keys [n last] :or {n 0 last -1}} _]
  (let [now (mod (System/currentTimeMillis) 10000)]
    (if (>= now last)
      {:n (inc n) :last now}
      (let [rt (Runtime/getRuntime)
            nf (java.text.NumberFormat/getNumberInstance)
            maxM (.maxMemory rt)
            freeM (.freeMemory rt)
            totM (.totalMemory rt)
            usedM (- totM freeM)
            doGC false ;(< freeM (* totM 0.50))
            info [:max (.format nf maxM) :tot (.format nf totM) :free (.format nf  freeM) :used (.format nf usedM) :GC doGC]]
        (when doGC (.gc rt))
        (log/info (str "evts/s: " (/ n 10.0) " --> " info))
        {:n 1 :last now}))))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn write-event [file-name event]
  (with-open [out (io/writer file-name :append true)]
    (.write out (str (pr-str event) "\n"))))

(defn set-defaults [defaults event]
  (merge defaults event))


#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn base64TOimg [key evt]
  (decode (key evt)))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn log-plates [{:keys [plate plates best-plate last-best-plate]}]
  (let [out (io/file "plates.log")]
    (when (.exists out)
      (with-open [outw (io/writer out :append true)]
        (.write outw (str (pr-str [plate plates best-plate last-best-plate]) "\n"))))))

(defn max-with-nil [val1 val2]
  (max (or val1 0) (or val2 0)))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn take-best [plates-in-seq plates]
  (let [plates (vec (filter identity (concat plates-in-seq plates)))]
    (into
     []
     (reduce
      (fn [selected [plate score]]
        (update selected plate (partial max-with-nil score)))
      {}
      plates))))

;definición del flujo principal de eventos para ZonaZero
(def TG-TOKEN "")
(def TG-CHAT-ID -1)

; plantId y origin es por cada caudal, en el caso del c5 pondremos un caudal
; a atender a cada vision-stream de manera que se puede usar por ejemplo
; origin para el subdirectorio final de las imagenes para evitar contingencias
; en el NFS comun de almacenamiento de imagenes, la arquitectura es que cada 
; contenedor docker de vision-stream se comunica con un contenedor docker de 
; caudal asi generamos un máximo thoroughput de procesamiento y almacenamiento
(def plantId "c5-colima-1")
(def origin "c5-colima-1")

#_(defn has-vehicle&plate? [{:keys [objects]}]
  (and (some #(= C/VEHICLE (first %)) objects)
       (some #(= C/PLATE (first %)) objects)))

#_(defn has-vehicle&NOTplate? [{:keys [objects]}]
  (and (some #(= C/VEHICLE (first %)) objects)
       (not (some #(= C/PLATE (first %)) objects))))

; dentro del evento viene un sub-dict :entities con un vector de endidades
; plate, plate_confidece,plate_alpr_confidence,plate_obj 
; cada entidad debe tener uuid, best_plate,best_plate_obj, best_plate_ts y best_plate_alpr_confidence. (este ts es et ai_ts del evento para relacionarlo con ese image)
;                               best_vehicle_obj, best_vehicle_ts, best_vehicle_confidence
;                               best_color, best_color_ts, best_color_confidence
;                               best_brand, best_brand_ts, best_brand_confidence
;
; la reduccion principal de entidades almacenará un cierto número de cuadros asociados al ai_ts del evento esto en una ventana movil y un
; map ai_ts -> image
; se mantiene una lista de las uuids ultimos y sabemos que cuando una uuid desaparece de la lista es porque ya no se recibiran mas eventos asociados a esa entidad
; entonces se procesa la entidad completa y se envia la notificacion con los mejores clips y las mejores lecturas asociadas a esa entidad
; los clips se extraen del a imagen asociada al ai_ts del best_x
;

; best es un mapa que tiene como llave la uuid asociada a un mapa que tiene un entity
;     entity -> 
#_{:uuid "de50a3ff-c579-4df5-978d-2e46a1c30558"
   :uuid_ts 123123123

   :best_vehicle "suv"
   :best_vehicle_confidence 87
   :best_vehicle_obj [3 87 1670 864 1917 1075]
   :best_vehicle_ts 1695761234567

   :best_color "red"
   :best_color_confidence 87
   :best_color_obj [3 87 1670 864 1917 1075]
   :best_color_ts 1695761234567

   :best_brand "toyota"
   :best_brand_confidence 87
   :best_brand_obj [3 87 1670 864 1917 1075]
   :best_brand_ts 1695761234567

   :best_plate "ABC123"
   :best_plate_confidence 87
   :best_plate_alpr_confidence 94
   :best_plate_obj [3 87 1670 864 1917 1075]
   :best_plate_ts 1695761234567
   :best_plate2 "ABC12"
   :best_plate2_alpr_confidence 67

   :bbox [1670 864 1917 1075]
   :vehicle "suv",
   :vehicle_confidence 78,
   :vehicle_obj [3 78 1670 864 1917 1075],
   :color "white",
   :color_confidence 66,
   :color_obj [10 66 1736 953 1920 1078],
   :brand nil,
   :brand_confidence 0,
   :brand_obj nil,
   :plate nil,
   :plate_confidence 0,
   :plate_alpr_confidence 0,
   :plate_obj nil,
   :plate2 nil,
   :plate2_alpr_confidence 0,

   :updated 1,
   :previous_distance 137.7325306527111,
   :centroid [1793.5 969.5],
   :is_new false,
   :previous_idx 1}

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn conj-with-limit [v item limit]
  (let [v2 (conj v item)]
    (if (> (count v2) limit)
      (subvec v2 1)
      v2)))

(defn timestamp->path
  [ts]
  (let [zdt (-> ts
                Instant/ofEpochMilli
                (ZonedDateTime/ofInstant (ZoneId/systemDefault)))
        year (.getYear zdt)
        month (format "%02d" (.getMonthValue zdt))
        day (format "%02d" (.getDayOfMonth zdt))
        hour (format "%02d" (.getHour zdt))
        minute (format "%02d" (.getMinute zdt))]
    (str year "/" month "/" day "/" hour "/" minute)))

(defn fix-file-name [s]
  (str (ST/replace s #"[\\ :<>|/\.,#$%&\"'\*]" "_") ".jpg"))

(defn store-image [frame-window root-path alias entitie] ;camera ts clipB64 regresa el path relativo a la imagen creada
  (let [{:keys [best_vehicle best_plate best_plate_obj best_color best_brand best_brand_obj best_ts best_clip_b64]} entitie]
    (try
      (let [root-dir (io/file root-path)
            path (timestamp->path best_ts)
            plate_height (if-let [[_ _ _ ymin _ ymax] best_plate_obj]
                           (- ymax ymin)
                           0)
            brand_height (if-let [[_ _ _ ymin _ ymax] best_brand_obj]
                           (- ymax ymin)
                           0)
            file-name (fix-file-name (format "%s_%s_%s_%s_%s_%s_%s_%s"
                                             best_ts
                                             alias
                                             (or best_vehicle "veh")
                                             (or best_color "color")
                                             (or best_plate "-----")
                                             plate_height
                                             (or best_brand "brand")
                                             brand_height))
            frame-name (fix-file-name (format "%s_%s_frame"
                                              best_ts
                                              alias))

            decoder (Base64/getDecoder)

            frame-file (io/file (io/file root-dir path) frame-name)
            frame-file-exists? (.exists frame-file)

            frame-bytes (when (and (not frame-file-exists?)
                                   (contains? frame-window best_ts))
                          (bs/to-byte-array (.decode decoder (get frame-window best_ts))))

            img-file (io/file (io/file root-dir path) file-name)

            img-bytes (when (and best_clip_b64 best_vehicle best_color
                                 (or best_brand best_plate
                                     (#{"motorcycle" "truck" "bus" "van"} best_vehicle)))
                        (bs/to-byte-array (.decode decoder best_clip_b64)))]
        ;(log/warn (format "STORE-IMAGE: writing image to %s %s %s" (.getCanonicalPath img-file) (.getCanonicalPath root-dir) (.getCanonicalPath (io/file root-dir path)) ))
        ;
        ;STORE-IMAGE: writing image to /opt/quantumlabs/ai_streamer/data/images/2025/12/04/11/1764868447691_null_suv_silver_-----_53_toyota_47.jpg
        (when img-bytes
          (io/make-parents img-file)
          (log/warn (format "STORE-IMAGE: writing image file %s parents: %s %s" (.getName img-file) (.exists (.getParentFile img-file)) (.getCanonicalPath (.getParentFile img-file))))
          (with-open [out (io/output-stream img-file)]
            (.write out img-bytes))
          (when frame-bytes
            (io/make-parents frame-file)
            ;(log/warn (format "STORE-IMAGE: writing frame file %s parents: %s %s" (.getName frame-file) (.exists (.getParentFile frame-file)) (.getCanonicalPath (.getParentFile frame-file))))
            (with-open [out (io/output-stream frame-file)]
              (.write out frame-bytes)))
          [(format "%s/%s" path file-name) (when (or frame-bytes frame-file-exists?)
                                             (format "%s/%s" path frame-name))]))
      (catch Exception e
        (log/error e)))))


(defn create-entities2send [frame-window root-path camera alias video_hash report-entities]
  (filter
   identity
   (mapv (fn [{:keys [uuid best_vehicle best_plate best_plate2 best_plate_obj best_color best_brand best_brand_obj best_bbox best_ts] :as entitie}]
           (let [[clip-path frame-path] (store-image frame-window root-path alias entitie)]
             (when clip-path ; solo la mandamos y guardamos si tiene placa o brand o es motorcycle o truck o van o bus
               {:uuid uuid
                :camera camera
                :alias alias
                :videoHash video_hash
                :event :ON_EVENT
                :vehicle best_vehicle
                :plate best_plate
                :plate2 best_plate2
                :plate_obj best_plate_obj
                :color best_color
                :brand best_brand
                :brand_obj best_brand_obj
                :bbox best_bbox
                :ts best_ts
                :clip clip-path   ;best_clip_b64
                :frame frame-path ; optional
                })))
         report-entities)))

; la idea de este entity-reducer es mantener una lista 'best' con las entidades con sus mejores valores y que cuando una entidad
; desaparece del flujo o el tiempo transcurrido entre uuid_ts y now sea mayor a un delta (usar modulo en rango de 1 seg) 
; se procese y envie la notificacion, esta notificacion debe tener el nuevo relevante
;Enviar: best_vehicle, best_plate, best_plate2, best_color, best_brand, best_entity_bbox, best_entity_ts, best_entity_clip_b64
;
;Enviar cuando:
;* Desaparece el UUID vs cuadro anterior
;* Ha pasado más de X tiempo desde que lo viste por primera vez (uuid_ts -> now), o si ya lo enviaste, Y tiempo desde la última vez

; esta funcion obtiene las entities que ya tienen cierto tiempo vivas, la idea es reportarlas
; periodicamente si es que ahi siguen mucho tiempo y no dejan de verse (carro estacionado), lo que 
; hay que hacer el repasar las entities y las que su uuid_ts sea hace mas de n segundos incorporarla
; a las que hay que reportar, pero esos uuid guardar now para saber que las reportamos, luego si
; quisieramos reportarla de nuevo solo si no se ha enviado
;
; long-standings es un mapa uuid -> ultimo envio
(defn create-long-standing [delta entities long-standings]
  (let [now (System/currentTimeMillis)]
    (reduce 
     (fn [[entities2send new-long-standings] {:keys [uuid uuid_ts] :as ent}]
       (let [last_uuid_ts (get long-standings uuid uuid_ts)]
         (if (> (- now last_uuid_ts) delta)
           [(conj entities2send ent) (assoc new-long-standings uuid (+ last_uuid_ts delta))]
           [entities2send (assoc new-long-standings uuid last_uuid_ts)])))
     [[] {}] 
     entities)))

(defn entity-reducer [{:keys [best-entities long-standings frame-window] ; vector de entidades previas
                       :or {best-entities {} long-standings {} frame-window (sorted-map)}}
                      {:keys [entities camera alias video_hash #_height delta-max image ai_ts0 frame-window-size]
                       :or {delta-max 10000 frame-window-size 30} ; este delta max en segundos es el que indica cuantos seg esperar como maximo antes de reportar
                       :as event}]
  (let [new-frame-window (if image
                           (let [fw2 (if (>= (count frame-window) frame-window-size)
                                       (dissoc frame-window (first (keys frame-window)))
                                       frame-window)]
                             (assoc fw2 ai_ts0 image))
                           frame-window)
        current-uuids (set (map #(:uuid %) entities))
        best-entities-uuids (set (map #(:uuid %) best-entities))
        disappeared-uuids (S/difference best-entities-uuids current-uuids) ; en realidad best-entities para la siguiente iteracion debe ser el current 
        report-disappeared-entities (filter #(contains? disappeared-uuids (:uuid %)) best-entities)
        now (System/currentTimeMillis)
        ;sacamos de las current las que han estado presentes un multiplo de delta-max segundos
        [report-long-standing-entities long-standings] (create-long-standing delta-max entities long-standings) 
        report-entities (concat report-disappeared-entities report-long-standing-entities)
        entities2send (create-entities2send new-frame-window C/LOCAL-ROOT-PATH2-IMAGES camera alias video_hash report-entities)]
    (when (some #(:plate %) entities2send)
      (let [msg (with-out-str (pp/pprint entities2send))]
        (log/info (str "ENTITIES sending entities:\n" msg))))
    (cond-> (assoc event :best-entities entities :long-standings long-standings :frame-window new-frame-window)
      (seq entities2send) (assoc :send entities2send))))

(def activity
  (smap
   [read-event]
   (smap
    [set-defaults {:token TG-TOKEN
                   :chat-id TG-CHAT-ID
                   :plantId plantId
                   :origin origin}]
    (time-stampit
     [:entry_ts]
     (reduce-with [:counter e-counter])
     (split
      [:error] ; tiene el :error entre las llaves del mapa
      (smap [#(dissoc % :image :clip)]
            (->ERROR [:all]))

      ; flujo para todas las cameras
      [:camera]
      (by
       [:camera] ; Agrupamps eventos por camara
       (reduce-with
        [:entity-reducer entity-reducer]
        (where 
         [#(seq (:send %))]
           (smap [SE/send-events]))))

      (smap [#(dissoc % :image :clip :z-clip :z-image :business-events)]
            (->WARN [:all])))))))

(declare activity-sink)

(defsink activity-sink 1
  activity)

(declare rest-server)
(deflistener rest-server [{:type 'caudal.io.rest-server
                           :parameters {:host "0.0.0.0"
                                        :http-port 8070
                                        :cors #".*"}}])

(declare tcp-server)
(deflistener tcp-server [{:type 'caudal.io.tcp-server
                          :parameters {:port 9999
                                       :host "0.0.0.0"
                                       :idle-period 300
                                       :buffer-size 4096 
                                       :max-line-length 3145728 ; 3MB limite de evento
                                       }}])


(wire [rest-server tcp-server] [activity-sink]) ;tailer

(web
 {:http-port 8090
  :host "0.0.0.0"
  :cors #"http://localhost:3449"
  :publish-sinks [activity-sink]})
