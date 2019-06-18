(ns invex)

(require '[caudal.core.scheduler-server :refer :all])
(require '[caudal.core.folds :refer :all])
(require '[caudal.streams.common :refer :all])
(require '[caudal.streams.stateful :refer :all])
(require '[caudal.streams.stateless :refer :all])

(defsink test-stream 100
  (time-stampit [:caudal/ts]
                (defstream [e]
                  (println e))
                (counter [:total :folio]
                         (->INFO [:all]))))

(deflistener tcp7777 [{:type 'caudal.io.tcp-server
                       :parameters {:port 7777
                                    :idle-period 60}}])

(deflistener rest-listener [{:type       'caudal.io.rest-server
                             :parameters {:port        8099
                                          :idle-period 300}}])

(wire [tcp7777 rest-listener] [test-stream ])
