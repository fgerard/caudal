(ns caudal.streams.stateless-test2
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is with-test]]
            [caudal.core.atom-state :as atom-state]
            [caudal.core.state :as state]
            [caudal.streams.common :as common :refer [caudal-state-as-map create-sink deflistener* defstream]]
            [caudal.streams.stateful :refer [batch counter]]
            [caudal.streams.stateless :refer [anomaly-by-stdev anomaly-by-percentile by decorate default forward join percentiles store! remove! smap split time-stampit to-file where with]]
            [caudal.util.caudal-util :refer [create-caudal-agent printp]]
            [caudal.util.date-util :as date-util]
            [caudal.util.test-util :refer [clean-event print-header]])

  (:import (org.apache.commons.io FileUtils)))

(def forward-test
  (defn forwarder [e]
    (let [agt (create-caudal-agent)
          streams (with [:test "test"]
                     (split
                       [:forwarded]
                       (store! [:store :test])

                       (with [:forwarded true]
                         (forward ["localhost" 7778]))))
          sink (create-sink agt streams)
          xsink {:id 'forward-id :sink sink :state agt}
          tcp (deflistener* [{:type 'caudal.io.tcp-server
                              :parameters {:port 7778
                                           :idle-period 60}}]
                            xsink)
          _ (sink e)
          _ (Thread/sleep 100)
          result (caudal-state-as-map agt)]
      (println :result result)
      (println :result2 (clean-event (result [:store "test"])))
      (= (assoc e :forwarded true :test "test") (clean-event (result [:store "test"]))))))

(with-test forward-test
  (is (forward-test {:host "prueba" :tx "si"})))
