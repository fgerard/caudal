(ns strauz-caudal-agent
  (:require [clojure.tools.logging :as log]
            [caudal.core.starter]
            [caudal.core.folds :as folds]
            [caudal.streams.common :refer [defstream defsink deflistener wire propagate]]
            [caudal.streams.stateless :refer [by forward printe where smap unfold ->INFO to-file]]
            [caudal.streams.stateful :refer [batch matcher counter mixer rate welford]]
            [strauz.monitor.orchestrator-util :as orc-util])
  (:import (java.util Date)))

(defn post-process-workflow [& children]
  (fn stream [by-path state event]
    (let [avg (get event :avg)
          avg (if avg (double avg))]
      (propagate by-path state (assoc (dissoc event :caudal/latency :fun :tx :err :result-code :status) :event-type "workflow" :system "strauz-orchestrator" :avg avg) children))))

(defn post-process-pipeline [& children]
  (fn stream [by-path state event]
    (let [avg (get event :avg)
          avg (if avg (double avg))]
      (propagate by-path state (assoc (dissoc event :caudal/latency :fun :tx :err :result-code :status) :event-type "pipeline" :system "strauz-orchestrator" :avg avg) children))))

(defn dump-size "MUCHO CUIDADO CON ESTA ES PA DEBUGEEAR ES LENTO!!!!!!!"
  [[prefix] & children]
  (fn [by-path state e]
    (println (.length (str state)))
    (propagate by-path state e children)))

(comment





 )

(defsink orchestrator-streamer 1
         (let [start-fn          (fn [event] (= :start (get event :status)))
               end-fn            (fn [event] (= :end (get event :status)))
               id-fn             (juxt :tx :id :fun)
               workflow-time-out 10000
               pipeline-time-out 6000]
           (counter [:general :xcontador]
                    (where [(fn [e]
                              (= 0 (mod (:xcontador e) 10000)))]
                           (printe ["CONTADOR: " :tx :id :xcontador]))
                    (where [:log-date]
                           (orc-util/agent-pre-process
                            (where [(fn [e] (= (get e :fun) :strauz.orc.core.orchestrator/workflow))]
                                   ;(printe ["WF -----> event : "])
                                   (matcher [:tx-matcher start-fn end-fn id-fn workflow-time-out :ms]
                                            (where [(fn [e] (not= (get e :ms) :timeout))]
                                                   (by [:id]
                                                       (welford [:welford-w :ms :avg :var :std :ss :cnt]
                                                                ;(printe ["WELFORD:"])
                                                                (rate [:rate-w :mts :rating 15 (* 24 4)])
                                                                )
                                                       ;(printe ["WF *****> event : "])
                                                       ;(batch [:input-workflow-batch 200 2000]
                                                       ;(printe ["WF =====> event : "])
                                                       ;(smap [#(folds/mean&stddev-welford :ms :avg :ssquare :variance :stdev :count %)]
                                                       ;(printe ["WELFORD:"])
                                                       ;(post-process-workflow
                                                       ;(rate [:rate-w :ms :rating 1 15])
                                                       ;(orc-util/progress ["-"]
                                                       ;(printe ["WORKFLOW event : "])
                                                       ;(forward ["strauzbalancer" 8064 5])
                                                       ;(dump-size [])

                                                       ;                   )
                                                       ;)
                                                       ;)
                                                       ;       )
                                                       ))))
                            (where [(fn [e] (= (get e :fun) :strauz.orc.core.orchestrator/pipeline))]
                                   ;(printe ["PP -----> event : "])
                                   (matcher [:tx-matcher start-fn end-fn id-fn pipeline-time-out :ms]
                                            (where [(fn [e] (not= (get e :ms) :timeout))]
                                                   (by [:id]
                                                       (welford [:welford-p :ms :avg :var :std :ss :cnt]
                                                                ;(printe ["WELFORD:"])
                                                                (rate [:rate-p :mts :rating 15 (* 24 4)])
                                                                )
                                                       ;(printe ["PP *****> event : "])
                                                       ;(batch [:input-pipeline-batch 200 20000]
                                                       ;       ;(printe ["PP =====> event : "])
                                                       ;       (smap [#(folds/mean&stddev-welford :ms :avg :ssquare :variance :stdev :count %)]
                                                       ;             (post-process-pipeline
                                                       ;              (rate [:rate-p :ms :rating 1 15])
                                                       ;              ;(orc-util/progress ["="]
                                                       ;                                 ;(printe ["PIPELINE event : "])
                                                       ;                                 ;(forward ["strauzbalancer" 8064 5])
                                                       ;                                 ;(dump-size [])
                                                       ;              ;                   )
                                                       ;             )))
                                                       ))))

                            )))))

(defsink orc-mixer-streamer 100
  (let [start-fn          (fn [event] (= :start (get event :status)))
        end-fn            (fn [event] (= :end (get event :status)))
        id-fn             (juxt :tx :id)
        workflow-time-out 20000
        pipeline-time-out 6000]
    ;[state-key delay ts-key priority-fn] & children
    (where [(fn [e] (or
                     (= (get e :fun) :strauz.orc.core.orchestrator/workflow)
                     (= (get e :fun) :strauz.orc.core.orchestrator/pipeline)))]
           (orc-util/agent-pre-process
            (counter [:foliador :folio]
                     (mixer [:mezclador 500 :ms :folio]
                            (unfold
                             ;(->INFO [[:folio :ms :id :status :tx]])
                             (defstream [e]
                               (if (= 0 (mod (:folio e) 10000))
                                 (println (:folio e))))
                             (to-file ["salida.log.edn" :all]))))))))

(deflistener tailer [{:type       'caudal.io.tailer-server
                      :parameters {:parser      'strauz.monitor.orchestrator-util/parse-orchestrator-line
                                   :inputs      {:directory "./logs"
                                                 :wildcard  "*.log"}
                                   :delta       500
                                   :from-end    false
                                   :reopen      true
                                   :buffer-size 16384}}])

(comment deflistener tmixer [{:type       'caudal.io.tailer-server
                      :parameters {:parser      'strauz.monitor.orchestrator-util/parse-orchestrator-line
                                   :inputs      {:directory "./logs"
                                                 :wildcard  "strauz-orc-*.log-yy"}
                                   :delta       500
                                   :from-end    false
                                   :reopen      true
                                   :buffer-size 16384}}])


(deflistener tcp-listener [{:type       'caudal.io.tcp-server
                            :parameters {:port        8062
                                         :idle-period 300}}])

(deflistener rest-listener [{:type       'caudal.io.rest-server
                             :parameters {:port        8099
                                          :cors #".*"
                                          :idle-period 300}}])
(deflistener rest-listener2 [{:type       'caudal.io.rest-server
                             :parameters {:port        8098
                                          :idle-period 300}}])
            ;tcp-listener
(wire [tailer rest-listener tcp-listener] [orchestrator-streamer])
(comment wire [tmixer rest-listener2] [orc-mixer-streamer])
