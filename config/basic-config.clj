;; Requires
(ns caudal.config.basic
  (:require
   [mx.interware.caudal.io.rest-server :refer :all]
   [mx.interware.caudal.streams.common :refer :all]
   [mx.interware.caudal.streams.stateful :refer :all]
   [mx.interware.caudal.streams.stateless :refer :all]))

;; Parser
(defn basic-parser[incoming-data]
  {:message incoming-data})

;; Listener
(deflistener tcp [{:type 'mx.interware.caudal.io.tcp-server
                   :parameters {:parser basic-parser
                                :host "localhost"
                                :port 9900
                                :idle-period 60}}])

(defsink example 1 ;; backpressure
  ;; streamer
  (counter [:state-counter :event-counter]
           ; streamer
           (->INFO [:all])))

;; Wires our listener with the streamers
(wire [tcp] [example])

(config-view [example] {:doughnut {:state-counter {:value-fn :n :tooltip [:n]}}})

(web {:http-port 9910
      :publish-sinks [example]})
