(ns strauz-caudal-hub
  (:require [caudal.core.starter]
            [caudal.streams.common :refer [defstream defsink deflistener wire propagate]]
            [caudal.core.folds :as folds]
            [caudal.streams.stateful :refer [acum-stats batch counter dump-every ewma-timeless matcher mixer]]
            [caudal.streams.stateless :refer [anomaly-by-stdev by decorate default to-file forward printe smap remove! split store! time-stampit unfold where with ->INFO]]
            [caudal.io.elastic :refer [elastic-store!]]
            [caudal.util.id-util :refer [key-juxt]]
            [strauz.monitor.orchestrator-util :as orc-util]
            [clojure.tools.logging :as log])
  (:import (java.text SimpleDateFormat)
           (java.util Date)))

(defn current-ymd []
  (let [format (new SimpleDateFormat "yyyyMMdd")
        result (.format format (new Date))]
    result))

(defn clean-events [& children]
  (fn stream [by-path state event]
    (propagate by-path state (dissoc event :caudal/latency) children)))


(defsink hub-streamer 100000
         (let [es-url          "http://elasticsearch:9200/"
               es-index        (str "strauz-" (current-ymd))
               es-mapping-name "strauz-mapping"
               es-mapping      {es-mapping-name {:properties {:id         {:type "keyword"}
                                                              :event-type {:type "keyword"}}}}
               es-opts         {:basic-auth ["elastic" "changeme"]}
               ]
           (orc-util/progress
             ["."]
             (where [(fn [e] (= (get e :system) "strauz-orchestrator"))]
                    (clean-events
                      (batch [:orchestrator-batch 1000 10000]
                             (orc-util/progress [(str "Storing records at : " (new Date) " ...")]
                                                ;(printe ["strauz-orchestrator batch :"])
                                                (elastic-store! [es-url es-index es-mapping-name es-mapping es-opts]))))))))


(deflistener tcp-listener [{:type       'caudal.io.tcp-server
                            :parameters {:port        8064
                                         :idle-period 300}}])

(deflistener rest-server [{:type       'caudal.io.rest-server
                           :parameters {:host "localhost"
                                        :port 8063}}])

(wire [tcp-listener rest-server] [hub-streamer])
