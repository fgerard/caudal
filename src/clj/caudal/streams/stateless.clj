;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns caudal.streams.stateless
  (:require [clojure.pprint :as pp]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer [chan go go-loop timeout <! >! <!! >!!]]
    ;[immutant.caching :as C]
            [caudal.core.state :as ST :refer [as-map lookup]]
    ;[caudal.core.atom-state]
    ;[caudal.core.immutant-state]
            [caudal.streams.common :refer [key-factory
                                           touch-info
                                           propagate error
                                           create-sink
                                           ws-publish-chan]]
             ;[caudal.io.rest-server :refer [ws-publish-chan]]
            )
  (:import (java.net InetAddress Socket URL InetSocketAddress)
           (org.apache.log4j PropertyConfigurator)
           (org.apache.commons.io FileUtils)
           ;(org.infinispan.configuration.cache ConfigurationBuilder)
           ))

;(def ws-publish-chan (chan)) ; ojo se define en common.clj porque si no no compila ???

(defn printe
  "
  Streamer function that prints an event to standard output
  > **Arguments:**
     *prefix*: Prefix for the printed string
     *children*: Children streamer functions to be propagated
  "
  [[prefix & keys] & children]
  (fn [by-path state e]
    (if (and keys (seq keys))
      (let [keys (into #{} keys)
            e (into {} (filter (fn [[k v]] (keys k)) e))]
        (println prefix e))
      (println (str prefix (pr-str e))))
    (propagate by-path state e children)))

(defn pprinte
  "
  Streamer function that pprints an event to standard output
  > **Arguments:**
     *children*: Children streamer functions to be propagated
  "
  [ & children]
  (fn [by-path state e]
    (pp/pprint e)
    (propagate by-path state e children)))

(defn default
  "
  Streamer function that sets a default value for a key of an event only if it does not exist or it does not have a value
  > **Arguments:**
     *key*: Key where the value will be set if it does not exist or it does not have a value
     *value*: Default value to be assigned
     *children*: Children streamer functions to be propagated
  "
  [[key value] & children]
  (fn [by-path state e]
    (propagate by-path state (assoc e key (key e value)) children)))

(defn with
  "
  Streamer function that sets a value for a key in an event
  > **Arguments**:
    *key*: Key where the value will be set
    *value*: Value to be assigned to the key
    *children*: Children streamer functions to be propagated
  "
  [[key value] & children]
  (fn [by-path state e]
    (propagate by-path state (assoc e key value) children)))

(defn time-stampit
  "
  Streamer function that stores the current timestamp in milliseconds in an event
  > **Arguments**:
    *key*: key to store the current timestamp in milliseconds
    *children*: Children streamer functions to be propagated
  "
  [[key] & children]
  (fn [by-path state event]
    (propagate
      by-path
      state
      (assoc event key (System/currentTimeMillis))
      children)))

(defn store!
  "
  Streamer function that stores an event in state using a function for key names
  > **Arguments**:
    *id-fn*: Function to calculate key names
    *children*: Children streamer functions to be propagated
  "
  [[state-key id-fn] & children]
  (fn [by-path state event]
    (let [state (let [id (when id-fn (id-fn event))
                      k  (if id
                           (vec (flatten [state-key by-path (name id)]))
                           (vec (flatten [state-key by-path])))]
                  (assoc state k (touch-info event)))]
      (propagate by-path state event children))))

(defn remove!
  "
  Streamer function that removes an event from state using a function for key names
  > **Arguments**:
    *id-fn*: Function to calculate key names
    *children*: Children streamer functions to be propagated
  "
  [[id-fn] & children]
  (fn [by-path state event]
    (let [state (if-let [id (id-fn event)]
                  (dissoc state id)
                  state)]
      (propagate by-path state event children))))

(defn where
  "
  Streamer function that filters events using a conditional predicate
  > **Arguments**:
    *predicate*: Conditional predicate to filter
    *children*: Children streamer functions to be propagated
  "
  [[predicate] & children]
  (fn [by-path state event]
    (if (predicate event)
      (propagate by-path state event children)
      state)))

(defn sdo
  [& children]
  (fn [by-path state event]
    (propagate by-path state event children)))

(defn smap
  "
  Streamer function that applies a transformation function to events and propagate them
  similarly to clojure's map function
  > **Arguments**:
    *transform-fn*: Tranformation function
    *children*: Children streamer functions to be propagated
  "
  [[transform-fn & args] & children]
  (fn [by-path state event]
    (if-let [new-event (apply transform-fn (concat args [event]))]
      (propagate by-path state new-event children)
      state)))

(defn push2ws
  "
  Streamer function that sends events via websocket to clients, then propagates the event
  > **Arguments**:
    topic: String identifier of topic (clientes will subscribe to a set of topics
    *children*: Children streamer functions to be propagated
  "
  [[topic] & children]
  (fn [by-path state event]
    (let [event (assoc event :caudal/topic topic)]
      (>!! ws-publish-chan event)
      (propagate by-path state event children))))

(defn split
  "
  Streamer function that filters events by multiple conditionals
  > **Arguments**:
    *exprs*: Conditionals to filter
  "
  [& exprs]
  (letfn [(pred [e [expr-or-otherwize to-do]]
            (if-not to-do
              expr-or-otherwize
              (if ((first expr-or-otherwize) e) to-do)))]
    (let [pairs (partition-all 2 exprs)]
      (fn [by-path state event]
        (if-let [to-do-fn (some (partial pred event) pairs)]
          (to-do-fn by-path state event)
          state)))))

(defn- filter-keys
  [key-set m]
  (if (= key-set :all)
    m
    (let [key-set (if (set? key-set) key-set (set key-set))]
      (reduce
        (fn [result [k v]]
          (assoc result k v))
        {}
        (filter #(key-set (first %)) m)))))

(defn unfold [& children]
  "
  Streamer function used to unfold event collections propagated by parent streamers. Events are therefore propagated one
  by one. If an individual event is received, it is propagated without any modification.
  > **Arguments:**
     *children*: Children streamer functions to be propagated
  "
  (fn stream [by-path state input]
    (cond (map? input) (propagate by-path state input children)
          (coll? input) (reduce (fn [new-state event] (propagate by-path new-state event children)) state input)
          :else (propagate by-path state input children))))

(defn to-file
  "
  Streamer function that stores events to a specified file
  > **Arguments**:
    *file-path*: The path to the target file
    *keys*: The keywords representing attributtes to be written in the file
    *children*: Children streamer functions to be propagated. Events are propagated untouched.
  "
  [[file-path keys] & children]
  (let [file (io/file file-path)]
    (fn stream [by-path state event]
      (let [new-event (filter-keys keys event)]
        (FileUtils/writeStringToFile file (str (pr-str new-event) "\n") true)
        (propagate by-path state event children)))))



(defn ->DEBUG
  "
   Streamer function that sends a DEBUG event to caudal log
   > **Arguments**:
   *fields* : can be
   - :all: then sends event to log with DEBUG level
   - vector of keywords: send event with keys contained in vector to log with DEBUG level
   - formatter-fn: a fn of arity 1 that the result will be the output to de log in DEBUG level
   *children*: Children streamer functions to be propagated
  "
  [[fields] & children]
  (let [fields (if (or (= :all fields) (fn? fields))
                 fields
                 (into #{} fields))]
    (fn [by-path state event]
      (let [e (if (fn? fields)
                (fields event)
                (pr-str (filter-keys fields event)))]
        (log/debug e))
      (propagate by-path state event children))))

(defn ->INFO
  "
   Streamer function that sends a INFO event to caudal log
   > **Arguments**:
   *fields* : can be
   - :all: then sends event to log with INFO level
   - vector of keywords: send event with keys contained in vector to log with DEBUG level
   - formatter-fn: a fn of arity 1 that the result will be the output to de log in DEBUG level
   *children*: Children streamer functions to be propagated
  "
  [[fields] & children]
  (let [fields (if (or (= :all fields) (fn? fields))
                 fields
                 (into #{} fields))]
    (fn [by-path state e]
      (let [e (if (fn? fields)
                (fields e)
                (pr-str (filter-keys fields e)))]
        (log/info e))
      (propagate by-path state e children))))

(defn ->WARN
  "
   Streamer function that sends a WARN event to caudal log
   > **Arguments**:
   *fields* : can be
   - :all: then sends event to log with WARN level
   - vector of keywords: send event with keys contained in vector to log with DEBUG level
   - formatter-fn: a fn of arity 1 that the result will be the output to de log in DEBUG level
   *children*: Children streamer functions to be propagated
  "
  [[fields] & children]
  (let [fields (if (or (= :all fields) (fn? fields))
                 fields
                 (into #{} fields))]
    (fn [by-path state event]
      (let [e (if (fn? fields)
                (fields event)
                (pr-str (filter-keys fields event)))]
        (log/warn e))
      (propagate by-path state event children))))

(defn ->ERROR
  "
   Streamer function that sends a DEBUG event to caudal log
   > **Arguments**:
   *fields* : can be
   - :all: then sends event to log with ERROR level
   - vector of keywords: send event with keys contained in vector to log with ERROR level
   - formatter-fn: a fn of arity 1 that the result will be the output to de log in DEBUG level
   *children*: Children streamer functions to be propagated
  "
  [[fields] & children]
  (let [fields (if (or (= :all fields) (fn? fields))
                 fields
                 (into #{} fields))]
    (fn [by-path state event]
      (let [e (if (fn? fields)
                (fields event)
                (pr-str (filter-keys fields event)))]
        (log/error e))
      (propagate by-path state event children))))

(defn by
  "
  Streamer function that groups events by values of sent keys
  > **Arguments**:
    *fields*: Data keys to group by
    *children*: Children streamer functions to be propagated
  "
  [fields & children]
  (let [fields (flatten fields)
        fld    (if (= 1 (count fields))
                 (first fields)
                 (apply juxt fields))]
    (fn [by-path state e]
      (let [fork-name    (fld e)
            flatten-fork (flatten [fork-name])]
        (if (not-any? nil? flatten-fork)
          (propagate (into (or by-path []) flatten-fork) state e children)
          state)))))

(defn join
  "
  Streamer function that groups eliminates the efect of one 'by'
  > **Arguments**:
    *fields*: Data keys to group by
    *children*: Children streamer functions to be propagated
  "
  [& children]
  (fn [by-path state e]
    (propagate (vec (butlast by-path)) state e children)))

(defn reinject
  "
   Streamer function that reinjects events to initial function
   in order to execute the whole proccess over it again.
  "
  [& children]
  (fn [by-path {caudal-entry :caudal/entry :as state} event]
    (if caudal-entry
      (caudal-entry event))
    (propagate by-path state event children)))

(defn decorate
  "Streamer function that substracts information from state, merges it with the current event and then propagates the merged data
  > **Arguments**:
    *state-key-of-fn*: Can be:
  - a function that when is applied to current event, returns the state key
  - a keyword, a vector is created with this key name that stores values from **by** streamers in execution path
  "
  [[state-key-or-fn] & children]
  (fn [by-path state event]
    (let [s-key (if (fn? state-key-or-fn)
                  (state-key-or-fn event)
                  (key-factory by-path state-key-or-fn))
          data  (state s-key)]
      ;(log/debug "===>" s-key "  " data)
      (propagate by-path state (merge event data) children))))

(defn anomaly-by-stdev
  "Streamer function that propagates to children if metric-key > (avg + (stdev-factor * stdev))
  > **Arguments**:
    *stdev-factor*: a number representing who many sigmas to allow over avg
    *metric-key*: the key where the value to lookfor is inside the event
    *avg-key*: the key where the value of the average is inside the event
    *stdev-key*: the key where the value of the sigma is inside the event
  "
  [[stdev-factor metric-key avg-key stdev-key] & children]
  (fn [by-path state e]
    (let [metric (metric-key e)
          avg    (avg-key e)
          stdev  (stdev-key e)]
      (if (and (number? stdev-factor)
               (number? metric)
               (number? avg)
               (number? stdev)
               (> metric (+ avg (* stdev-factor stdev))))
        (propagate by-path state e children)
        state))))

(defn anomaly-by-percentile
  "Streamer function that propagates to children if metric-key > (avg + (stdev-factor * stdev))
  > **Arguments**:
    *stdev-factor*: a number representing who many sigmas to allow over avg
    *metric-key*: the key where the value to lookfor is inside the event
    *avg-key*: the key where the value of the average is inside the event
    *stdev-key*: the key where the value of the sigma is inside the event
  "
  [[metric-key percentiles-key percentil] & children]
  (fn [by-path state e]
    (let [metric          (metric-key e)
          percentil-value (get-in e [percentiles-key percentil])]
      (if-not percentil-value
        (log/warn (str "Percentil " percentil " not present in " percentiles-key " @ event " e)))
      (if (> metric percentil-value)
        (propagate by-path state e children)
        state))))

(defn create-socket [host port]
  ;(log/info (str "host : " host ", port : " port))
  (let [socket (new Socket)
        _      (.connect socket (new InetSocketAddress host port))]
    socket))

(defn forward
  "Streamer function that sends events to other Caudal using TCP
  > **Arguments**:
    *caudal-host*: host name or ip of Caudal receiver
    *port*: Port number on the Caudal host enabled to listen for events
    *back-pressure-limit*: optional number to limit back pressure
    *children*: Streamers to propagate the unchanged event
  "
  [[caudal-host caudal-port max-retries back-pressure-limit] & children]
  (let [max-retries         (or max-retries 1)
        back-pressure-limit (or back-pressure-limit 1000)
        sem                 (java.util.concurrent.Semaphore. back-pressure-limit true)
        socket-conf         (agent {:host caudal-host
                                    :port caudal-port})
        send-caudal-events  (fn [{:keys [host port socket] :as conf} events]
                              ;(log/debug :conf (pr-str conf) (pr-str events))
                              (try
                                (let [counter   (atom 1)
                                      in-range? (atom true)
                                      must-try? (atom true)]
                                  (while (and @in-range? @must-try?)
                                    (try
                                      (let [x-socket (or socket
                                                         (create-socket caudal-host caudal-port))
                                            out      (.getOutputStream x-socket)]
                                        (.write out (.getBytes (pr-str events) "utf-8"))
                                        (.write out 10)
                                        (.flush out)
                                        (assoc conf :socket x-socket)
                                        (reset! must-try? false))
                                      (catch Exception e
                                        (.printStackTrace e)
                                        (log/info "Retrying [" (.getMessage e) "] ...")
                                        (reset! must-try? true)))
                                    (do (swap! counter inc)
                                        (if (> @counter max-retries)
                                          (do
                                            (log/info "Max retries [" max-retries "] reached.")
                                            (reset! in-range? false))))))
                                (catch Exception e
                                  (.printStackTrace e)
                                  (log/error e)
                                  (dissoc conf :socket))
                                (finally
                                  (.release sem))))]

    (fn [by-path state e]
      (.acquire sem)
      (send socket-conf send-caudal-events e)
      (propagate by-path state e children))))

(defn percentiles [[metric-key percentiles-key percents] & children]
  (fn [by-path state events]
    (let [events (filter (fn [{metric metric-key :as e}]
                           (and metric (number? metric))) events)]
      (if (seq events)
        (let [ordered (sort-by metric-key events)
              n       (count ordered)
              idx-fn  (fn [percent] (min (dec n) (Math/floor (* n percent))))
              values  (into {} (map (fn [percent]
                                      [percent (metric-key (nth ordered (idx-fn percent)))]) percents))]
          (propagate by-path state (assoc (first events) percentiles-key values) children))
        state))))
