;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns caudal.core.scheduler-server
  (:require [clojure.pprint :as pp]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.string :as s]
            [chime.core :as chime]
            [caudal.streams.common :refer [start-listener]]
            [caudal.util.ns-util :refer [resolve&get-fn require-name-spaces]])
  (:import (com.cronutils.model CronType)
           (com.cronutils.model.definition CronDefinitionBuilder)
           (com.cronutils.model.time ExecutionTime)
           (com.cronutils.parser CronParser)
           (java.time ZonedDateTime)))

(defn cron-seq
  "Infinite lazy seq of java.time.Instant firing times for a Quartz-style
   cron expression (6/7 fields: seconds minutes hours day-of-month month
   day-of-week [year] -- same dialect immutant.scheduling/cron used, so
   existing :cron-def strings keep working unchanged), starting after now.
   - *cron-str*: Quartz-style cron expression, e.g. \"0 0 11 ? * MON-FRI\""
  [cron-str]
  (let [parser    (CronParser. (CronDefinitionBuilder/instanceDefinitionFor CronType/QUARTZ))
        cron      (.parse parser cron-str)
        exec-time (ExecutionTime/forCron cron)]
    (->> (iterate (fn [^ZonedDateTime t]
                     (.orElse (.nextExecution exec-time t) nil))
                   (ZonedDateTime/now))
         (drop 1) ; el primer elemento es "now", no una hora de disparo real
         (take-while some?)
         (map (fn [^ZonedDateTime t] (.toInstant t))))))

(defmethod start-listener 'caudal.core.scheduler-server
  [sink {:keys [jobs] :as config}]
  "
  Creates one or more scheduled jobs (via chime + a Quartz-dialect cron
  parser) that generate synthetic events and sink them on a timer -- no
  external input, the events come from calling event-factory.

  - _jobs:_ sequence of job maps, each with:
    - _runit?:_ if false, this job is skipped entirely (default true)
    - _cron-def:_ a Quartz-style cron string (6/7 fields: seconds minutes
      hours day-of-month month day-of-week [year]), e.g.
      \"0 0 11 ? * MON-FRI\"
    - _event-factory:_ fully-qualified symbol of a function that, given
      parameters, returns a 0-arg fn -- that fn is called on every tick
      and its return value is the event sent to the sink
    - _parameters:_ passed as-is to event-factory

  Example:

  ```
  (deflistener scheduler [{:type 'caudal.core.scheduler-server
                           :jobs [{:cron-def      \"0 0/5 * * * ?\"
                                   :event-factory 'my-ns/state-admin-event-factory
                                   :parameters    {:cmd :some-command}}]}])
  ```
  "
  (doseq [{:keys [runit? cron-def event-factory parameters] :or {runit? true}} jobs]
    (when runit?
      (let [event-factory-ns (symbol (namespace event-factory))
            _                (require event-factory-ns)
            event-factory    (resolve event-factory)
            event-source     (event-factory parameters)]
        (chime/chime-at
         (cron-seq cron-def)
         (fn [time]
           (log/debug "running schedule:" cron-def parameters "at" time)
           (sink (event-source)))
         {:error-handler (fn [e]
                            (log/error e "Error running scheduled job" cron-def)
                            true)})))))

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
