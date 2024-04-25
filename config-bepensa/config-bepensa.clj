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

(def plantId "Beppemsa1")

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

(defn print-it [{:keys [controler-name AntennaPortNumber PeakRssiInDbm d-id event rfid-ts] :as e}]
  (log/info (format "%-10s %-5s %-10s %-20s %s %s" controler-name AntennaPortNumber PeakRssiInDbm event d-id rfid-ts)))

; EVENTE ES ON_TAG_READ | ON_TAG_REMOVED | ON_TAG_ERROR
(defn tag-reducer [{:keys [last-d-id last-entry-ts]} {:keys [d-id event entry-ts] :as e}]
  (if (= event :ON_TAG_READ)
    (if (or (not= d-id last-d-id) (> entry-ts (+ last-entry-ts C/DELTA-REPEAT-TAG)))
      (assoc e :last-d-id d-id :last-entry-ts entry-ts :send-tag true :uuid (create-uuid))
      (assoc e :last-d-id d-id :last-entry-ts last-entry-ts))
    (assoc e :last-d-id last-d-id :last-entry-ts last-entry-ts)))

(defsink example 1 ;; backpressure
   ;; streamer
  (smap
   [set-defaults {:plantId plantId
                  :origin origin}]
   (time-stampit
    [:entry-ts]
    ;(reduce-with [:counter e-counter])
    ;(smap [#(log/info (pr-str [:antes-tag-reducer  %]))])
    (reduce-with
     [:tag-reducer tag-reducer]
     ;(smap [#(log/info (pr-str [:tag-reducer %]))])
     (where 
      [:send-tag]
      (smap 
       [SE/send-events]
       (smap [print-it])))))))

 ;; Listener
(deflistener rfid-salida [{:type 'caudal.io.rfid-server
                           :parameters {:controler-name "salida"
                                        :controler "10.180.10.132"
                                        :RfMode 1002
                                        :cleanup-delta 1000
                                        :chan-buf-size 10
                                        :fastId false
                                        :d-id-re "E2004.*"
                                        :antennas [[1 true nil] [2 true nil]]}}])
; en antennas va por cada antena un vector con (id, tx power,rx sendibility) [id nil|true|real nil|true|int-dbm]

(deflistener rfid-entrada [{:type 'caudal.io.rfid-server
                            :parameters {:controler-name "entrada"
                                         :controler "10.180.10.131"
                                         :RfMode 1002
                                         :cleanup-delta 10000
                                         :chan-buf-size 10
                                         :fastId false
                                         :d-id-re "E2004.*"
                                         :antennas [[1 true nil] [2 true nil]]}}])


 ;; Wires our listener with the streamers
(wire [rfid-entrada rfid-salida] [example])

 ;(config-view [example] {:doughnut {:state-counter {:value-fn :n :tooltip [:n]}}})

(web {:http-port 9910
      :publish-sinks [example]})
