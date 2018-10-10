(ns invex)

(require '[mx.interware.caudal.core.scheduler-server :refer :all])
(require '[mx.interware.caudal.core.folds :refer :all])
(require '[mx.interware.caudal.streams.common :refer :all])
(require '[mx.interware.caudal.streams.stateful :refer :all])
(require '[mx.interware.caudal.streams.stateless :refer :all])

(defsink test-stream 100
  (time-stampit [:caudal/ts]
                (defstream [e]
                  (println e))
                (counter [:total :folio]
                         (->INFO [:all]))))

(deflistener tcp7777 [{:type 'mx.interware.caudal.io.tcp-server
                       :parameters {:port 7777
                                    :idle-period 60}}])

(deflistener rest-listener [{:type       'mx.interware.caudal.io.rest-server
                             :parameters {:port        8099
                                          :idle-period 300}}])

(wire [tcp7777 rest-listener] [test-stream ])
