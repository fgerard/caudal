(ns main
  (:require
   [constants :as C]
   [env.constants :as EC]
   [send-events :as SE]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
    ;[clojure.core.match :refer [match]]
   [clojure.pprint :as pp]
   [caudal.streams.common :refer [defsink deflistener wire]]
   [caudal.io.rest-server :refer [web]]
   [caudal.streams.stateful :refer [reduce-with changed counter]]
   [caudal.streams.stateless :refer [by pprinte where split smap time-stampit ->INFO ->WARN ->ERROR reinject join unfold]]
   [caudal.io.telegram :refer [send-photo send-text]]
   [cheshire.core :refer [parse-string generate-string]]
    ;[send-events :as SE]
    ;[img-util :as IU]
   )
  (:import
   (java.util Random UUID)
   (java.util Base64)
   (java.net InetAddress)))

(log/info "Starting streamer")
(log/info (pr-str {:CAUDAL_HOME C/CAUDAL_HOME}))

(def origin (-> (InetAddress/getLocalHost)
                (.getHostName)))

(def plantId "<planta>")

(defn create-uuid [] (str (UUID/randomUUID)))

(defn set-defaults [defaults event]
  (merge defaults event))

(defn e-counter [{:keys [n last] :or {n 0 last -1}} e]
  (when EC/WITH-E-COUNTER
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
          {:n 1 :last now})))))

(defn print-it [{:keys [plant id AntennaPortNumber PeakRssiInDbm d-id event rfid-ts]}]
  (log/info (format "%-5s %-10s %-5s %-10s %-20s %s %s" plant id AntennaPortNumber PeakRssiInDbm event d-id rfid-ts)))

; EVENTE ES ON_TAG_READ | ON_TAG_REMOVED | ON_TAG_ERROR (por ahora solo mandamos ON_TAG_READ)
; :seen es el set de d-id actualmente presentes (se agregan en ON_TAG_READ,
; se quitan en ON_TAG_REMOVED, asi nunca crece indefinidamente) -- :increment
; sale true solo la primera vez que un d-id aparece mientras sigue en :seen,
; para poder contar distintos sin volver a contar el mismo tag que sigue
; presente aunque :send-tag vuelva a true por la ventana de DELTA-REPEAT-TAG.
(defn tag-reducer [{:keys [last-d-id last-entry-ts seen] :or {seen #{}}} {:keys [d-id event entry-ts] :as e}]
  (cond
    (and (= event :ON_TAG_READ)
         (or (not= d-id last-d-id) (> entry-ts (+ last-entry-ts C/DELTA-REPEAT-TAG))))
    (assoc e :last-d-id d-id :last-entry-ts entry-ts :send-tag true :uuid (create-uuid)
           :seen (conj seen d-id) :increment (not (contains? seen d-id)))

    (= event :ON_TAG_READ)
    (assoc e :last-d-id d-id :last-entry-ts last-entry-ts
           :seen (conj seen d-id) :increment false)

    (= event :ON_TAG_REMOVED)
    (assoc e :last-d-id last-d-id :last-entry-ts last-entry-ts
           :seen (disj seen d-id) :increment false)

    :else
    (assoc e :last-d-id last-d-id :last-entry-ts last-entry-ts
           :seen seen :increment false)))

(defn create-chanel-id [plant id antena]
  (condp  = plant
    43 (cond (= id "salida") (str id antena)
             (= id "entrada1") "entrada1"
             (= id "entrada2") "entrada2"
             :else (log/error "no puedo crear nombre con " [:plant plant :id id :antennas antena]))
    49 id
    (log/error "no puedo crear nombre con " [:plant plant :id id :antennas antena])))

(defonce tg-token (slurp "config/tg-token.txt")) ;(System/getenv "TG-TOKEN")
(defonce tg-chat-id (slurp "config/tg-chat-id.txt")) ; (System/getenv "TG-CHAT-ID")
(defonce debug-telegram-flg (io/file "config/debug-telegram"))

(defn- mask-last4
  "Token para el log: sin sus ultimos 4 caracteres."
  [s]
  (let [s (clojure.string/trim (str s))]
    (str (subs s 0 (max 0 (- (count s) 4))) "....")))

(log/info (pr-str {:tg-token (mask-last4 tg-token) :tg-chat-id tg-chat-id}))

(defn ->human-readable [_ {:keys [plant Epc lane rfid-ts increment]}]
  (let [sdf (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss.SSS")
        rfid-ts (.format sdf (java.util.Date. rfid-ts))]
    {:plant plant
     :Epc Epc
     :lane lane
     :rfid-ts rfid-ts
     :increment increment}))

(defsink flux 1 ;; backpressure
  ;; streamer
  (smap
   [set-defaults {:origin origin}]
   (time-stampit
    [:entry-ts]
    (smap
     [(fn [{:keys [plant id AntennaPortNumber] :as e}]
        (let [channel-id (create-chanel-id plant id AntennaPortNumber)]
          (assoc e :channel-id channel-id :lane channel-id :plantId plant)))]
     (counter
      [:total :total]
      (by
       [:plant]
       (counter
        [:plant-total :plant-total]
        (by
         [:channel-id]
         (counter
          [:channel-total :channel-total]
          (reduce-with
           [:tag-reducer tag-reducer]
           (where
            [:send-tag]
            (smap
             [SE/send-events]
             (smap [print-it])))
           (where
            [(fn [{:keys [event]}]
               (log/info (pr-str [:event event :exists? (.exists debug-telegram-flg)]))
               (or (= event :ON_CONNECTION_LOST) (.exists debug-telegram-flg)))]
            (smap
             [(fn [e]
                (log/error (pr-str ["Connection lost" :-> e :debug-telegram-flg-deleted (.delete debug-telegram-flg)]))
                (assoc e :msg (pr-str [:connection-lost :-> e])))]
             (send-text [tg-token tg-chat-id :msg])))
           (where
            [:increment]
            (reduce-with [:human ->human-readable]
             (counter
              [:distinct-tag-count :distinct-tag-count]
              (join
               (counter
                [:distinct-tag-count :distinct-tag-count]
                (join
                 (counter
                  [:distinct-tag-count :distinct-tag-count])))))))))))))))))


; OJO debemos permitir algun tipo de manejo de las regex por planta mañana lo defino hoy es: (2026-06-03)
;
;

(defn get-prefix []
  (-> (slurp "config/rfid-tag.regex")
      clojure.string/split-lines
      first
      clojure.string/trim))

(deflistener rfid-salida12-sitio-a [{:type 'caudal.io.rfid-server
                                      :parameters {:controler-info {:id "salida"
                                                                    :plant 43
                                                                    :controler "<ip>"}
                                                   :inactivity 900000
                                                   :RfMode 1002
                                                   :cleanup-delta 120000
                                                   :chan-buf-size 1
                                                   :fastId false
                                                   :d-id-re (get-prefix) ;"AABB.*"
                                                   :keepalive-ms 60000
                                                   :antennas [[1 24 -70] [2 24 -70]]
                                                   :tag-policy {:type :last
                                                                :delta 3000
                                                                :direction :approaching}}}])
; en antennas va por cada antena un vector con (id, tx power,rx sendibility) [id nil|true|real nil|true|int-dbm]

(deflistener rfid-entrada1-sitio-a [{:type 'caudal.io.rfid-server
                                      :parameters {:controler-info {:id "entrada1"
                                                                    :plant 43
                                                                    :controler "<ip>"}
                                                   :inactivity 900000
                                                   :RfMode 1002
                                                   :cleanup-delta 120000
                                                   :chan-buf-size 1
                                                   :fastId false
                                                   :d-id-re (get-prefix) ;"AABB.*"
                                                   :keepalive-ms 60000
                                                   :antennas [[1 28 -80] [2 28 -80]]
                                                   :tag-policy {:type :last
                                                                :delta 3100}}}])

(deflistener rfid-entrada2-sitio-a [{:type 'caudal.io.rfid-server
                                      :parameters {:controler-info {:id "entrada2"
                                                                    :plant 43
                                                                    :controler "<ip>"}
                                                   :inactivity 900000
                                                   :RfMode 1002
                                                   :cleanup-delta 120000
                                                   :chan-buf-size 1
                                                   :fastId false
                                                   :d-id-re (get-prefix) ;"AABB.*"
                                                   :keepalive-ms 60000
                                                   :antennas [[1 28 -80] [2 28 -80]]
                                                   :tag-policy {:type :last
                                                                :delta 3200}}}])

;; Listener
(deflistener rfid-salida1-sitio-b [{:type 'caudal.io.rfid-server
                                         ;controler-info es un mapa que se va a hacer merge con el evento que el caudal crea de modo que esta info llega a sink en el evento
                                         ; es decir el evento que regresa caudal en el rfid-server regresa (merge  <evento-rfid> controler-info) 
                                         ; y el controler-info es el que definimos aqui, ojo el sistema automaticamente le aumenta :controler con el valor de controler
                                         ; que será usado para identificar de forma unica al controler
                                   :parameters {:controler-info {:id "salida1"
                                                                 :plant 49
                                                                 :controler "<ip>"}
                                                :inactivity 900000
                                                :RfMode 1002
                                                :cleanup-delta 120000
                                                :chan-buf-size 1
                                                :fastId false
                                                :d-id-re (get-prefix) ;"AABB.*"
                                                :keepalive-ms 60000
                                                :antennas [[1 29 -70]] ; [2 24 -70]]
                                                :tag-policy {:type :every
                                                             :modul 10
                                                             :wait4 10000
                                                             :trigger 3}}}])
; en antennas va por cada antena un vector con (id, tx power,rx sendibility) [id nil|true|real nil|true|int-dbm]

(deflistener rfid-salida2-sitio-b [{:type 'caudal.io.rfid-server
                                   :parameters {:controler-info {:id "salida2"
                                                                 :plant 49
                                                                 :controler "<ip>"}
                                                :inactivity 900000
                                                :RfMode 1002
                                                :cleanup-delta 120000
                                                :chan-buf-size 1
                                                :fastId false
                                                :d-id-re (get-prefix) ;"AABB.*"
                                                :keepalive-ms 60000
                                                :antennas [[1 29 -70]] ; [2 28 -80]]
                                                :tag-policy {:type :every
                                                             :modul 10
                                                             :wait4 10000
                                                             :trigger 3}}}])

(deflistener rfid-entrada1-sitio-b [{:type 'caudal.io.rfid-server
                                    :parameters {:controler-info {:id "entrada1"
                                                                  :plant 49
                                                                  :controler "<ip>"}
                                                 :inactivity 900000
                                                 :RfMode 1002
                                                 :cleanup-delta 120000
                                                 :chan-buf-size 1
                                                 :fastId false
                                                 :d-id-re (get-prefix) ;"AABB.*"
                                                 :keepalive-ms 60000
                                                 :antennas [[1 30 -70]] ; [2 28 -80]]
                                                 :tag-policy {:type :every
                                                              :modul 10
                                                              :wait4 10000
                                                              :trigger 3}}}])


;; Borra el estado de "flux" (todos los contadores/tag-reducer/etc,
;; queda solo el bookkeeping interno :caudal/...) todos los dias a las
;; 6:00am -- ver caudal.streams.common/mutate! :clear-all.
(deflistener state-reset [{:type 'caudal.core.scheduler-server
                              :jobs [{:runit? true
                                      :cron-def "0 0 6 * * ?"
                                      :event-factory 'caudal.core.scheduler-server/state-admin-event-factory
                                      :parameters {:cmd :clear-all}}]}])

;;Wires our listener with the streamers
(wire [rfid-entrada1-sitio-b rfid-salida1-sitio-b rfid-salida2-sitio-b
       rfid-entrada1-sitio-a rfid-entrada2-sitio-a rfid-salida12-sitio-a
       state-reset] [flux])

(web {:http-port 9911
      :host "0.0.0.0"
      :publish-sinks [flux]})
