;; Requires
(ns caudal.config.basic
  (:require
   [caudal.io.rest-server :refer :all]
   [caudal.streams.common :refer :all]
   [caudal.streams.stateful :refer :all]
   [caudal.streams.stateless :refer :all]))

;; Parser
(defn basic-parser [incoming-data]
  {:message incoming-data})

;; Listener
(deflistener tcp [{:type 'caudal.io.tcp-server
                   :parameters {:parser read-string
                                :host "localhost"
                                :port 9900
                                :idle-period 60}}])

(defn calc-value [{eventName :eventName count :count :or {count 0}}]
  (+ (min 10 count) (get {:OnSceneNotKnown     0
                          :OnCustomsEmpty      1
                          :OnBadParking        2
                          :OnProductNotVisible 3
                          :OnUntrustedRead     4
                          :OnTrustedRead       15} eventName)))

(defn aggregate [aggegate-evt evt]
  (if (or (not aggegate-evt) (> (calc-value evt) (calc-value aggegate-evt)))
    evt
    aggegate-evt))

(defn aggregator [{:keys [a-uuid best-evt]} {:keys [eventName count uuid] :as e}]
  (let [best-evt-old best-evt
        best-evt (if (= a-uuid uuid) (aggregate best-evt e) e)
        d-uuid uuid]
    (cond-> (assoc e :a-uuid uuid :best-evt best-evt)
            (not= best-evt-old best-evt) (assoc :send [best-evt]))))


(defn customs-position [{:keys [position side] :as e}]
  (assoc e :customs/camera (if (and position side) [position side] ["plate"])))

(defsink example 1 ;; backpressure
  ;; streamer
  (counter [:state-counter :event-counter]
           ; streamer
           (smap [customs-position]
                 (by [:customs/camera]
                     (push2ws ["detail"])
                     (reduce-with [:aggregate aggregator]
                                  (where [:send]
                                         (smap [:send]
                                               (unfold
                                                (push2ws ["aggregate"])))))))

           (push2ws ["importantes"]
                    (->INFO [:all]))))

;; Wires our listener with the streamers
(wire [tcp] [example])

(config-view [example] {:doughnut {:state-counter {:value-fn :n :tooltip [:n]}}})

(web {:http-port 9910
      :publish-sinks [example]})
