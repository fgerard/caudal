;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns mx.interware.caudal.core.scheduler-server
  (:require [clojure.pprint :as pp]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.string :as s]
            [clojure.core.async :refer [chan >!! alts! go-loop buffer]]
            [immutant.scheduling :refer [schedule in at every limit cron] :as S]
            [mx.interware.caudal.streams.common :refer [start-listener]]
            [mx.interware.caudal.util.ns-util :refer [resolve&get-fn require-name-spaces]]))

(defmethod start-listener 'mx.interware.caudal.core.scheduler-server
  [sink {:keys [jobs] :as config}]
  (doseq [{:keys [runit? cron-def schedule-def in every limit event-factory parameters] :or {runit? true}} jobs]
    (when runit?
      (let [event-factory-ns (symbol (namespace event-factory))
            _                (require event-factory-ns)
            event-factory    (resolve event-factory)
            event-source     (event-factory parameters)
            schedule-conf (cond
                            cron-def
                            (cron cron-def)

                            :OTHERWIZE
                            schedule-def)]


        (schedule
          (fn []
            (log/debug "running schedule:" cron parameters)
            (sink (event-source)))
          schedule-conf)))))

(defn state-admin-event-factory [{:keys [cmd] :as parameters}]
  (fn []
    (let [event (merge
                  {:caudal/cmd cmd}
                  parameters)]
      (log/debug "GENERATING SCHEDULED EVENT: " (pr-str event))
      event)))

(defn state-admin-events [events]
  (fn []
    events))
