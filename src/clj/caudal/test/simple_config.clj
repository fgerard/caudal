;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns caudal.test.simple-config
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.walk :refer [walk]]
            [clojure.core.async :as async :refer [chan go go-loop timeout <! >! <!! >!!]]
            [caudal.core.state :refer [as-map lookup]]
            [caudal.streams.common :refer [create-sink key-factory defstream
                                                        using-path using-path-with-default-streams]]
            [caudal.streams.stateful :refer [batch concurrent-meter counter
                                                          changed matcher reduce-with
                                                          acum-stats dump-every
                                                          ewma-timeless rate uneven-tx welford]]
            [caudal.streams.stateless :refer [->DEBUG ->INFO ->WARN ->ERROR
                                                           default where smap split
                                                           with by reinject store! time-stampit
                                                           decorate anomaly-by-stdev forward
                                                           percentiles to-file]]
            [caudal.core.folds :as folds])
  (:import (java.net InetAddress URL)
           (org.apache.log4j PropertyConfigurator)
           (org.infinispan.configuration.cache ConfigurationBuilder)))


;(PropertyConfigurator/configure "log4j.properties")

(comment

(defn sprint [msg e]
  (println msg "->" (pr-str e)))

(def streams
  (default :ttl -1
           (->INFO [:all])
           (where [(fn [e] (= "getCustInfo" (:tx e)))]
                  (where [:delta]
                         (rate [:tx-rate :timestamp :rate 30 15]
                               (smap [(fn [e]
                                        (if (= "23:59" (.format (java.text.SimpleDateFormat. "HH:mm") (:timestamp e)))
                                          (assoc e :rate (first (:rate e)))))]
                                     (->INFO [:rate])
                                     (smap [(fn [{:keys [rate] :as e}]
                                              (assoc e :rate-stats (folds/simple-mean&stdev rate)))]
                                           (->INFO [:rate-stats]))))
                         (smap [#(assoc % :delta (double (Integer/parseInt (:delta %))))]
                               (by [:tx]
                                   (batch [:big 1000000 90000]
                                          (percentiles [:delta :percentiles [0 0.5 0.75 0.8 0.9 0.95]]
                                                       (smap [(fn [e]
                                                                {:tx (:tx e) :percentiles (:percentiles e)})]
                                                             (store! [(fn [e] [:percentiles (:tx e)])]
                                                                     (dump-every :percentiles "hpercent" "yyyyMMdd" [1 :minute] "./config/stats/")))
                                                       (->INFO [:tx :percentiles])))
                                   (batch [:tx 1000 1000]
                                          (smap [#(folds/mean&std-dev :delta :avg :variance :stdev :n %)]
                                                (acum-stats [:stats :avg :variance :stdev :n]
                                                            (dump-every :stats "history" "yyyyMMdd" [1 :minute] "./config/stats/"))))
                                   (decorate [:history]
                                             (split
                                               :avg
                                               (anomaly-by-stdev [1 :delta :avg :stdev]
                                                                 (->ERROR [:all]))

                                               (ewma-timeless :ewma 0.5 :delta
                                                              (decorate [:stats]
                                                                        (anomaly-by-stdev [0 :delta :avg :stdev]
                                                                                          (defstream [e] (println :despues-ewma e)))))))))))))
)
