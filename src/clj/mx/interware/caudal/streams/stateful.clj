;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns mx.interware.caudal.streams.stateful
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer [chan go go-loop timeout <! >! <!! >!! put!]]
            ;[immutant.caching :as C]
            [mx.interware.caudal.core.state :as ST]
            [mx.interware.caudal.util.matrix :as M]
            ;[mx.interware.caudal.core.atom-state]
            ;[mx.interware.caudal.core.immutant-state]
            [mx.interware.caudal.util.date-util :refer [cycle->millis
                                                        compute-rate-bucket]]
            [mx.interware.caudal.streams.common :refer [key-factory complex-key-factory
                                                        propagate error
                                                        create-sink ->toucher
                                                        exec-in repeat-every add-attr]])
  (:import (java.net InetAddress URL)
           (org.apache.log4j PropertyConfigurator)
           ;(org.infinispan.configuration.cache ConfigurationBuilder)
           ))

(defn counter
  "
  Streamer function that appends to an event the count of passed events
  > **Arguments**:
    *state-key*: State key to store the count
    *event-counter-key*: Event key to hold the count dafaults to :count
    *children*: Children streamer functions to be propagated
  "
  [[state-key event-counter-key accumulator-fn] & children]
  (let [accumulator-fn (or accumulator-fn (fn [n e]
                                            (inc n)))
        mutator (->toucher
                 (fn counter-mutator [{:keys [n] :or {n 0}} e]
                   {:n (accumulator-fn n e)}))
        event-counter-key (or event-counter-key :count)]
    (fn counter-streamer [by-path state e]
      (let [d-k    (key-factory by-path state-key)
            {{:keys [n]} d-k :as new-state} (update state d-k mutator e)
            result (propagate
                    by-path
                    new-state
                    (assoc e event-counter-key n)
                    children)]
        result))))

(defn ewma-timeless
  "
  Streamer function that normalizes the metric value, it calculates the ewma
  which represents the exponential waited moving average and is calculated by the next equation:
  (last-metric * r) + (metric-history * (1-r)), events with no metric will be ignored 
  > **Arguments**:
  *state-key*: state key to store the metric
  *r*: Ratio value
  *(metric-key)*: Optional for key holding metric, default :metric
  *children* the streamer functions to propagate
  "
  [[state-key r metric-key] & children]
  (let [metric-key (or metric-key :metric)
        mutator (->toucher
                 (fn ewma-timeless-mutator [{:keys [ewma] :as info} e-metric]
                   (if ewma
                     {:ewma (+ (* r e-metric) (* (- 1 r) ewma)) :r r :metric-key metric-key}
                     {:ewma e-metric :r r :metric-key metric-key})))]
    (fn ewma-timeless-streamer [by-path state e]
      (if-let [e-metric (metric-key e)]
        (let [d-k (key-factory by-path state-key)
              {{:keys [ewma]} d-k :as new-state} (update state d-k mutator e-metric)]
          (propagate
           by-path
           new-state
           (assoc e metric-key ewma)
           children))
        state))))

(defn reduce-with
  "
  Streamer function that reduces values of an event using a function and propagates it
  > **Arguments**:
  *state-key*: Key name to reduce
  *reduce-fn*: function that reduces the values
  *children*: Children streamer functions to be propagated
  "
  [[state-key reduce-fn] & children]
  (let [mutator (->toucher reduce-fn)]
    (fn reduce-with-streamer [by-path state e]
      (let [d-k (key-factory by-path state-key)
            {reduced d-k :as new-state} (update state d-k mutator e)]
        ;(println "****REDUCED*** " reduced)
        (if reduced
          (propagate by-path new-state reduced children)
          new-state)))))

(defn remove-childs
  "
  Streamer function that eliminates all elements from the state that key starts with child-key and the path where the remove-childs is
  > **Arguments**:
  *child-key*: keyword inicating the begining of the state's key (first element in the vector that identifies an entry in the state)
  *children*: Children streamer functions to be propagated
  "
  [[child-key] & children]
  (fn [by-path state event]
    (let [d-k (key-factory by-path child-key)
          d-k-len (count d-k)
          state (into {} (remove (fn [[k v]]
                                   (and (vector? k)
                                        (= d-k (take d-k-len k))))) state)]
      ;(println "remove-siblings: " [d-k (keys state)])
      (propagate by-path state event children))))

(defn moving-time-window
  "
  Streamer function that propagate a vector of events to streamers using a moving time window
  > **Arguments**:
  *state-key*: State key
  *window-size*: Milliseconds size of window
  *(time-key)*: Optional key holding the time in millis, default :time
  *& children*: functions to propagate
  "
  [[state-key window-size time-key] & children]
  (let [time-key (or time-key :time)
        mutator (->toucher
                 (fn moving-time-window-mutator [{:keys [boundary buffer]
                                                  :or {boundary 0 buffer []}
                                                  :as info} e]
                   (let [boundary (max (- (time-key e 0) window-size) boundary)]
                     (if (or (nil? (time-key e))
                             (< boundary (time-key e)))
                       (let [buffer (->> (conj buffer e)
                                         (remove (fn [e]
                                                   (or (nil? (time-key e))
                                                       (> boundary (time-key e)))))
                                         vec)]
                         {:boundary boundary
                          :buffer   buffer
                          :window-size window-size})
                       (do
                         (log/warn "Event out of sequence [" (pr-str e) "] state removed!")
                         nil)))))]
    (fn moving-time-window-streamer [by-path state e]
      (let [d-k (key-factory by-path state-key)
            {{:keys [buffer]} d-k :as new-state} (update state d-k mutator e)]
        (if (and buffer (seq buffer))
          (propagate by-path new-state buffer children)
          (do
            (log/debug :dissoc-from-state d-k)
            (dissoc new-state d-k)))))))

(defn changed
  "
  Streamer function that checks if a value of an event has changed using a predicate
  > **Arguments**:
    *state-key*: State key to store
    *change-pred*: predicate for evaluating
    *children*: Children streamer functions to be propagated
  "
  [[state-key change-pred] & children]
  (let [mutator (->toucher
                 (fn changed-mutator [{:keys [e-t-1 val-t-1]} e]
                   (let [val-t   (change-pred e)
                         ret-val {:e-t-1 e :val-t-1 val-t}]
                     (if-let [changed? (and e-t-1 (not= val-t-1 val-t))]
                       (merge ret-val {:changed? true :old e-t-1})
                       ret-val))))]
    (fn changed-streamer [by-path state e]
      (let [d-k (key-factory by-path state-key)
            {{:keys [changed? old]} d-k :as new-state} (update state d-k mutator e)]
        (if changed?
          (propagate by-path new-state [old e] children)
          new-state)))))

(defn rollup
  "
  Streamer function that propagates n events each dt time
  > **Arguments**:
  *state-key*: Key name to roll back
  *n*: Max number of elements stored
  *delta*: Max time to perform operations
  *children*: Children streamer functions to be propagated
  "
  [[state-key n delta] & children]
  (let [mutator (->toucher
                 (fn rollup-mutator [{:keys [cnt init-ts buf]
                                      aborter :caudal/aborter
                                      :or {cnt 0 buf []}} e]
                   (let [now     (System/currentTimeMillis)
                         init-ts (or init-ts now)]
                     (cond
                       (nil? e)
                       {:rolled-up buf :caudal/aborter aborter}
                       (= cnt 0)
                       {:cnt 1 :ts now :buf [] :send-it [e] :start-timer true :caudal/aborter aborter}
                       (< cnt (dec n))
                       {:cnt (inc cnt) :ts init-ts :buf [] :send-it [e] :caudal/aborter aborter}
                       :OTHERWISE
                       {:cnt (inc n) :ts init-ts :buf (conj buf e) :caudal/aborter aborter}))))
        rollup-fn (fn rollup-rollup-fn [state by-path d-k]
                    (let [{{:keys [rolled-up]
                            aborter :caudal/aborter} d-k
                           :as new-state} (update state d-k mutator nil)
                          new-state (dissoc new-state d-k)]
                      (if (seq rolled-up)
                        (propagate
                         by-path
                         new-state
                         rolled-up
                         children))
                      new-state))]
    (fn rolled-up-streamer [by-path state e]
      (let [d-k (key-factory by-path state-key)
            {{:keys [send-it start-timer]
              aborter :caudal/aborter} d-k
             send2agent                    :caudal/send2agent
             :as                           new-state} (update state d-k mutator e)
            abort-chan (when start-timer
                         (exec-in :rollup delta send2agent rollup-fn by-path d-k))
            new-state (if abort-chan
                        (update new-state d-k assoc :caudal/aborter abort-chan)
                        new-state)]
        (if send-it
          (do
            (put! (or aborter abort-chan) "bye")
            (propagate by-path new-state send-it children))
          new-state)))))

(defn batch
  "
  Streamer function that propagates a vector of events once n elements are stored in or dt time has passed
  > **Arguments**:
  *state-key*: Key name to store
  *n*: Max number of elements stored
  *dt*: Max time to propagate
  *children*: Children streamer functions to be propagated
  "
  [[state-key n delta] & children]
  (let [mutator (->toucher (fn batch-mutator [{:keys [buffer creation-time]
                                               aborter :caudal/aborter
                                               :or {buffer []}} e]
                             (let [start-timer   (not creation-time)
                                   creation-time (or creation-time (System/currentTimeMillis))
                                   buffer        (if e (conj buffer e) buffer)
                                   flush-it?     (or (>= (count buffer) n)
                                                     (< (+ creation-time delta)
                                                        (System/currentTimeMillis))
                                                     (nil? e))]
                               (if flush-it?
                                 {:flushed buffer :caudal/aborter aborter}
                                 {:buffer buffer :creation-time creation-time :start-timer? start-timer :caudal/aborter aborter}))))
        batcher-fn (fn batcher-timeout-fn [state by-path d-k]
                     (let [{{:keys [flushed]} d-k :as new-state} (update state d-k mutator nil)
                           new-state (dissoc new-state d-k)
                           ]
                       (if (seq flushed)
                         (propagate by-path new-state flushed children)
                         new-state)))]
    (fn [by-path state e]
      (let [d-k (key-factory by-path state-key)
            {{:keys [flushed start-timer?]
              aborter :caudal/aborter}      d-k
             send2agent                     :caudal/send2agent
             :as                            new-state} (update state d-k mutator e)
            abort-chan (when start-timer?
                         (exec-in [:batch d-k] delta send2agent batcher-fn by-path d-k))
            new-state (if abort-chan
                        (update new-state d-k assoc :caudal/aborter abort-chan)
                        new-state)]
        (if (and flushed (seq flushed))
          (do
            (put! (or aborter abort-chan) "bye")
            (propagate by-path (dissoc new-state d-k) flushed children))
          new-state)))))

(defn concurrent-meter
  "
  Stream function that stores in event the current number of transactions opened
  when an initial event passes, the counter increments, if an end event paseses, the counter decrements
  > **Arguments**:
  *state-key*: State key name to store the counter
  *init-tx-pred*: Predicate to detect the initial event of an operation
  *init-tx-end*: Predicate to detect the finish event of an operation
  *tx-id-fn: Function to get the transaction id
  *[]: list that contains:
  - event key name to store the meter
  - children functions to propagate
  "
  [[state-key init-tx-pred end-tx-pred tx-id-fn concurrent-fld-cntr] & children]
  (let [metric-key (if (keyword? concurrent-fld-cntr) concurrent-fld-cntr :metric)
        mutator (->toucher
                 (fn concurrent-meter-mutator [{:keys [n] :or {n 0} :as state} e]
                   (cond
                     (init-tx-pred e)
                     {:n (inc n) :propagate? true}

                     (end-tx-pred e)
                     {:n (dec n) :propagate? true}

                     :OTHERWISE
                     (dissoc state :propagate?))))]
    (fn concurrent-meter-streamer [by-path state e]
      (if-let [id (tx-id-fn e)]
        (let [d-k (complex-key-factory by-path [state-key id])
              {{:keys [n propagate?]} d-k :as new-state} (update state d-k mutator e)]
          (if propagate?
            (propagate by-path new-state (assoc e metric-key n) children)
            new-state))
        state))))


(defn matcher
  "
  Stream function that correlates initial and end events, extract the metric value and calculates the metric difference
  > **Arguments**:
  *state-key*: Key name to store the time
  *init-tx-pred*: Predicate to detect the initial event of an operation
  *init-tx-end*: Predicate to detect the finish event of an operation
  *tx-id-fn*: Function to get the transaction id
  *timeout-delta-or-pred*: Time or predicate to wait the end event in milliseconds, when timeout, it will discard the event
  *[]*:List that contains the key name to store the difference miliseconds and childs to propagate
  "
  [[state-key init-pred end-pred tx-id-fn timeout-delta-or-pred ts-key] & children]
  (let [ts-key (if (keyword? ts-key) ts-key :time)
        mutator (->toucher
                 (fn matcher-mutator [{:keys [start]
                                       aborter :caudal/aborter
                                       :or {} :as state} e type init? end?]
                   ;(println "MUTATOR: " [type init? end? e])
                   (cond
                     (= type :timeout)
                     (if start
                       (if (= (ts-key e) start)
                         {:timeout? true}
                         {:start start :caudal/aborter aborter})
                       nil)

                     init?
                     {:start (ts-key e) :caudal/aborter aborter}

                     end?
                     (do ;(println "ES END!! start: " start )
                       (if start
                         {:propagate-metric? (- (ts-key e) start) :caudal/aborter aborter :start start}
                         nil)))))
        matcher-fn (fn matcher-matcher-fn [state by-path d-k e]
                     (let [{{:keys [timeout?] :as matcher-state} d-k :as new-state} (update
                                                                                     state d-k mutator
                                                                                     e :timeout nil nil)
                           new-state (dissoc new-state d-k)]
                       (if timeout?
                         (propagate by-path new-state (assoc e ts-key :timeout) children)
                         new-state)))]
    (fn matcher-streamer [by-path state e]
      (let [id                    (tx-id-fn e)
            init?                 (init-pred e)
            end?                  (end-pred e)
            timeout-delta-or-pred (if (number? timeout-delta-or-pred) timeout-delta-or-pred
                                    (timeout-delta-or-pred e))]
        (if (and id (or init? end?))
          (let [d-k (complex-key-factory by-path [state-key id])
                {{:keys [start propagate-metric?]
                  aborter :caudal/aborter
                  :as matcher-state} d-k
                 send2agent                        :caudal/send2agent
                 :as                               new-state} (update state d-k mutator e :normal init? end?)
                abort-chan (when start
                             (exec-in [:matcher d-k] timeout-delta-or-pred send2agent matcher-fn by-path d-k e))
                new-state (if abort-chan
                            (update new-state d-k assoc :caudal/aborter abort-chan)
                            new-state)]
            (log/debug "abort-chan: " (nil? abort-chan) " aborter:" (nil? aborter) " propagate-metric?: " propagate-metric? " start:" start)
            (if propagate-metric?
              (do
                (put! aborter "bye")
                (propagate by-path (dissoc new-state d-k) (assoc e ts-key propagate-metric?) children))
              (if (and end? (nil? matcher-state))
                (dissoc new-state d-k)
                new-state)))
          state)))))

(defn acum-stats
  "
  Stream function that acumulates stats (mean,variance,stdev,count) recomputing them, it receives an event
  that is decorated with mean,stdev,variance and count and takes information from the state and the
  resultin mixing is stored in the state, then the event with the new values is propagated to children
  > **Arguments**:
  *state-key*: Key name to store accumulating mean,stdev,variance and count
  *avg-key*: Key where the mean is in the event and in the state
  *variance-key*: Key where the variance is in the event and in the state
  *stdev-key*: Key where the stdev is in the event and in the state
  *count-key*: Key where the count is in the event and in the state
  *children*: List of streams to propagate result
  -- if event is missign required fields, the event is propagated with :error set to message error
  "
  [[state-key avg-key variance-key stdev-key count-key] & children]
  (let [mutator (->toucher
                 (fn acum-stats-mutator [{avg avg-key variance variance-key stdev stdev-key n count-key :as stats} e]
                   (if stats
                     (let [block-mean     (avg-key e)
                           block-variance (variance-key e)
                           block-stdev    (stdev-key e)
                           block-count    (count-key e)
                           total          (+ n block-count)
                           new-variance   (/ (+ (* variance n) (* block-variance block-count)) total)]
                       (merge
                        stats
                        {count-key    total
                         avg-key      (/ (+ (* avg n) (* block-mean block-count)) total)
                         variance-key new-variance
                         stdev-key    (Math/sqrt new-variance)}))
                     {count-key    (count-key e)
                      avg-key      (avg-key e)
                      variance-key (variance-key e)
                      stdev-key    (stdev-key e)})))]
    (fn acum-stats-streamer [by-path state {avg avg-key variance variance-key stdev stdev-key count count-key :as e}]
      (if (and (number? avg)
               (number? variance)
               (number? stdev)
               (number? count))
        (let [d-k         (key-factory by-path state-key)
              {new-stats d-k :as new-state} (update state d-k mutator e)
              decorated-e (merge e new-stats {:d-k d-k :e e})]
          (propagate by-path new-state decorated-e children))
        (propagate by-path state (add-attr e :error
                                           "event with missing on non-numeric fields:"
                                           avg-key variance-key stdev-key count-key)
                   children)))))

(defn dump-every
  "
  Stream function that writes to the file system information stored in the state.
  > **Arguments**:
  *state-key*: Key of the state to write to the file system
  *file-name-prefix*, *date-format* and *dir-path*: are used to copute the file name like this:
  - Caudal take the JVM time and formats it using a java.text.SimpleDateFormat with *date-format* and
  - concatenates it with '-' and file-name-prefix, then appends to it the current *'by string list'* then
  - appends '.edn' the file will be located at the *dir-path* directory.
  *cycle*: represents a long number for milliseconds or a vetor with a number and time messure like:
  - [5 :minutes] or [1 :day]
  - *cycle* will tell Caudal to write this file with this frequency, may overwriting it.
  "
  [[state-key file-name-prefix date-format cycle dir-path]]
  (let [mutator (->toucher
                 (fn dump-every-mutator [{:keys [dumping?] :as stats} e]
                   (if dumping?
                     (-> stats
                         (dissoc ::start-dumping?))
                     (assoc stats ::dumping? true ::start-dumping? true))))
        dumper-fn (fn dump-every-dumper-fn [state by-path d-k]
                    (if-let [dump-info (state d-k)]
                      (let [date-str  (.format (java.text.SimpleDateFormat. date-format) (java.util.Date.))
                            ; dump-name looks like
                            ; asuming format:'yyyyMM_ww' and file-name-prefix:'history'
                            ;    201601_03-history-getCustInfo.edn
                            dump-name (apply str date-str (map (fn [a b] (str a (name b)))
                                                               (repeat "-")
                                                               (cons file-name-prefix (rest d-k))))
                            dump-name (str dump-name ".edn")
                            dump-dir  (java.io.File. dir-path)
                            _ (.mkdirs dump-dir)
                            dump-file (java.io.File. dump-dir dump-name)]
                        (with-open [out (java.io.FileWriter. dump-file)]
                          (binding [*out* out]
                            (pp/pprint (dissoc dump-info ::dumping? ::start-dumping?))))))
                    (update state d-k dissoc ::start-dumping? ::dumping?))]
    (fn dump-every-streamer [by-path state e]
      (let [d-k (key-factory by-path state-key)
            {{start-dumping? ::start-dumping?} d-k
             send2agent               :caudal/send2agent
             :as                      new-state} (update state d-k mutator e)]
        (if start-dumping?
          (repeat-every ::dump-every (cycle->millis cycle) 1 send2agent dumper-fn by-path d-k))
        new-state))))

;:tx-rate :timestamp :rate 30 60
(defn rate-deprecated
  "
  Stream function that maintains a matrix of *days* rows and 60*24 columns, and stores it in the
  state, then for each event that passes through increments the number in the position of row
  0 and collum equals to the number of minute in the day, remainder rows contain past *days*
  minus 1 (days shold be less than 60 '2 months').
  > **Arguments**:
  *state-key*: Key of the state to hold the matrix
  *ts-key*: keyword of the timestamp in millis
  *rate-key*: keyword holding the matrix when the outgoing event is propagated to *children*
  *days*: Number of days to hold (number of rows in the matrix)
  *bucket-size*: number of minutes to group, larger numbers require less memory (ex: 15 means we know
  how many events are dividing the hour in 4 (15 minutes) it is required that this number divides 60 with
  no fraction(MEJORAR).
  *replace-fn* function that receives event and last bucket value as parameter, returns a new bucket
  value. If none, replace-fn is `(fn [e l] (inc l))`.
  *children*: List of streams to propagate result
  -- if event dosent have 'ts-key' event is ignored!!
  "
  [[state-key ts-key rate-key days bucket-size replace-fn] & children]
  (let [mutator (->toucher
                 (fn rate-mutator [{:keys [buckets last] :as data} e]
                   (if (ts-key e)
                     (let [[day-from-origin day hour bucket] (compute-rate-bucket (ts-key e) bucket-size)
                           bucketsXhour (int (/ 60 bucket-size))
                           collumns     (* bucketsXhour 24)
                           collumn      (+ (* hour bucketsXhour) bucket)]
                        (let [buckets (if data
                                        (loop [buckets buckets adjust (- day-from-origin last)]
                                          (if (<= adjust 0)
                                            buckets
                                            (recur (M/m-insert-zero-row buckets) (dec adjust))))
                                        (M/m-zero days collumns))
                              new-buckets (if replace-fn
                                            (M/m-set buckets 0 collumn (replace-fn e (M/m-get buckets 0 collumn)))
                                            (M/m-inc buckets 0 collumn))]
                          {:last day-from-origin :buckets new-buckets :bucket-size bucket-size}))
                     data)))]
    (fn rate-streamer [by-path state e]
      (log/debug :RATE e)
      (let [d-k (key-factory by-path state-key)
            {{:keys [buckets]} d-k :as new-state} (update state d-k mutator e)]
        (if buckets
          (propagate by-path new-state (assoc e rate-key buckets) children)
          new-state)))))

(defn rate
  "
  Stream function that maintains a matrix of *days* rows and 60*24 columns, and stores it in the
  state, then for each event that passes through increments the number in the position of row
  0 and collum equals to the number of minute in the day, remainder rows contain past *days*
  minus 1 (days shold be less than 60 '2 months').
  > **Arguments**:
  *state-key*: Key of the state to hold the matrix
  *ts-key*: keyword of the timestamp in millis
  *rate-key*: keyword holding the matrix when the outgoing event is propagated to *children*
  *days*: Number of days to hold (number of rows in the matrix)
  *bucket-size*: number of minutes to group, larger numbers require less memory (ex: 15 means we know
  how many events are dividing the hour in 4 (15 minutes) it is required that this number divides 60 with
  no fraction(MEJORAR).
  *replace-fn* function that last-bucket-value & event as parameter, returns a new bucket
  value. If none, replace-fn is `(fn [bucket-val e] (inc bucket-val))`.
  *children*: List of streams to propagate result
  -- if event dosent have 'ts-key' event is ignored!!
  "
  [[state-key ts-key rate-key bucket-size n-buckets replace-fn] & children]
  (let [replace-fn (or replace-fn (fn [{height :caudal/height :or {height 0}} e]
                                    (assoc e :caudal/height (inc height))))
        mutator (->toucher
                 (fn rate-mutator [{:keys [buckets last]
                                    :or {buckets (sorted-map)}
                                    :as data} e]
                   (if-let [ts-val (ts-key e)]
                     (let [factor (* 60 1000 bucket-size)
                           ts-val (* factor (int (/ ts-val factor)))
                           last (or last ts-val)
                           new-last (max ts-val last)
                           ;_ (log/info "buckets:" buckets " ts-val:" ts-val " replace-fn:" replace-fn " e:" e)
                           buckets (update buckets ts-val replace-fn e)
                           buckets (reduce
                                    (fn [buckets [bucket val]]
                                      (if (< bucket (- new-last (* factor n-buckets)))
                                        (dissoc buckets bucket)
                                        (reduced buckets)))
                                    buckets
                                    buckets)]
                       {:buckets buckets :last new-last :bucket-size bucket-size :n-buckets n-buckets})
                     data)))]
    (fn rate-streamer [by-path state e]
      (let [d-k (key-factory by-path state-key)
            {{:keys [buckets]} d-k :as new-state} (update state d-k mutator e)]
        (if buckets
          (propagate by-path new-state (assoc e rate-key buckets) children)
          new-state)))))


(defn mixer [[state-key delay ts-key priority-fn] & children]
  (let [decision-fn (fn mixer-decision-fn [frontier [ts priority nano event]]                                                              ; buffer contains vectors like:[ts priority nano event]
                      (< nano frontier))

        sortable-event  (fn mixer-sortable-event [e]
                          [(ts-key e) (priority-fn e) (System/nanoTime) e])

        mutator (->toucher
                 (fn mixer-mutator [{:keys [buffer] :as data} e]
                   (if buffer
                     (let [limit         (- (System/currentTimeMillis) delay)
                           ;should-purge? is a fn that is true when a ts (first in sortable-event) is older than limit
                           should-purge? (fn [[ts]] (< ts limit))
                           buffer        (if e (conj buffer (sortable-event e)) buffer)]
                       (if (some should-purge? buffer)
                         (let [buffer (sort buffer)
                               older  (partial decision-fn (- (System/nanoTime) (* delay 1000000)))
                               purged (seq (map #(get % 3) (take-while older buffer)))
                               keep   (seq (drop-while older buffer))]
                           {:buffer keep :purged-events purged})
                         {:buffer buffer}))
                     (if e
                       {:buffer [(sortable-event e)] :start-purging? e}
                       {}))))
        cleanup-fn  (fn mixer-cleanup-fn [state by-path d-k delay]
                      (let [{{:keys [purged-events buffer]} d-k
                             send2agent                     :caudal/send2agent
                             :as                            new-state} (update state d-k mutator nil)
                            new-state (if (seq purged-events)
                                        (propagate by-path new-state purged-events children)
                                        new-state)]
                        (if buffer
                          (do
                            (exec-in :mixer2 delay send2agent mixer-cleanup-fn by-path d-k delay)
                            new-state)
                          (dissoc new-state d-k))))]
    (fn mixer-streamer [by-path state e]
      ;(pp/pprint (ST/as-map state))
      (let [d-k (key-factory by-path state-key)
            {{:keys [start-purging? purged-events]} d-k
             send2agent                             :caudal/send2agent
             :as                                    new-state} (update state d-k mutator e)]
        (when start-purging?
          (exec-in :mixer1 delay send2agent cleanup-fn by-path d-k delay))
        (if (seq purged-events)
          (propagate by-path new-state purged-events children)
          new-state)))))

;(uneven-tx [:unclosed :start :delta :thread :tx] ; con esto yo se si es un inicio (tiene :start) o un fin (tiene :delta) y el id del tx se recuerda
; para este thread, si llega algo que no es el end del tx en el mismo thread se registra una incompleta.

(defn uneven-tx [[state-key init-pred end-pred thread-id tx-id ts-id timeout unstarted-key unended-key] & children]
  (let [mutator (->toucher
                 (fn uneven-tx-mutator [data e is-init is-end]
                   ;(log/debug "mutator1: " (pr-str data) "  " (pr-str e) " " is-init " " is-end)
                   (let [{[{:keys [running-tx time]} :as stack] :stack} (or data {:stack '()})
                         e-tx (tx-id e)
                         e-ts (ts-id e)]
                     ;(log/debug "mutator2: " running-tx " " time " " e-tx  " " timeout)
                     (if is-init
                       (cond
                         (not running-tx)
                         {:stack (cons {:running-tx e-tx :time e-ts} (:stack data))}

                         (not= running-tx e-tx)
                         (if (> (- e-ts time) timeout)
                           {:unended (vec (map #(assoc % :cause :timeout) stack))
                            :stack (list {:running-tx e-tx :time e-ts})}
                           {:stack (cons {:running-tx e-tx :time e-ts} stack)})

                         (= running-tx e-tx)
                         (let [[top & stack] stack]
                           {:unended [(assoc top :cause :recursive-call-not-valid)]
                            :stack (cons {:running-tx e-tx :time e-ts} stack)}))
                       (cond
                         (not running-tx) ; se encontro un fin sin existir nada)
                         {:stack nil :unstarted [{:running-tx e-tx :time e-ts :cause :unstarted-tx}]}

                         (= running-tx e-tx)
                         {:stack (rest stack)}

                         (not= running-tx e-tx)
                         (assoc data :unstarted [{:running-tx e-tx :time e-ts :cause :unstarted-tx}]))))))]
    (fn uneven-tx-streamer [by-path state e]
      (let [[is-init is-end] ((juxt init-pred end-pred) e)]
        (if (or is-init is-end)
          (let [d-k [state-key (thread-id e)]
                {{:keys [unended unstarted]} d-k :as new-state} (update state d-k mutator e is-init is-end)]
            (if (or unended unstarted)
              (propagate by-path new-state (assoc e unended-key unended unstarted-key unstarted) children)
              new-state))
          state)))))

(defn dumper-fn [file-name-prefix date-format dir-path state by-path d-k]
  (if-let [dump-info (state d-k)]
    (let [date-str  (.format (java.text.SimpleDateFormat. date-format) (java.util.Date.))
          dump-name (apply str date-str (map
                                          (fn [a b] (str a (name b)))
                                          (repeat "-")
                                          (cons file-name-prefix (rest d-k))))
          dump-name (str dump-name ".edn")
          dump-dir  (java.io.File. dir-path)
          _ (.mkdirs dump-dir)
          dump-file (java.io.File. dump-dir dump-name)]
      (with-open [out (java.io.FileWriter. dump-file)]
        (binding [*out* out]
          (pp/pprint (dissoc dump-info ::dumping? ::start-dumping?))))))
  (update state d-k dissoc ::start-dumping? ::dumping?))

(defn welford
  "
  Stream function that implements [Welford's Algorithm](http://www.jstor.org/stable/1266577) to calculate online mean, variance, standard deviation and a count
  of associated events.

  * *state-key*          key name to store current mean, standard deviation, variance and count
  * *metric-key*         numeric value to calculate statistics
  * *mean-key*           key to store the calculated mean into each event
  * *variance-key*       key to store the calculated variance into each event
  * *stdev-key*          key to store the calculated standard deviation into each event
  * *sum-of-squares-key* key to store the calculated sum of squared differences (in algorithm, S of the kth iteration) into each event
  * *count-key*          key to store the calculated count into each event

  For more info [see this link](http://jonisalonen.com/2013/deriving-welfords-method-for-computing-variance/)
  "
  [[state-key metric-key mean-key variance-key stdev-key sum-of-squares-key count-key
    {:keys [prefix date-fmt freq path]}] & children]
  (let [mutator (->toucher
                 (fn welford-mutator [{mean mean-key variance variance-key stdev stdev-key s sum-of-squares-key count count-key dumping? ::dumping?} e]
                   (if (number? (metric-key e))
                     (if (nil? count)
                       {mean-key (metric-key e) sum-of-squares-key (double 0) variance-key (double 0) stdev-key (double 0) count-key 1
                        ::dumping? prefix ::start-dumping? (and prefix (not dumping?))}
                       (let [metric   (metric-key e)
                             kth      (inc count)
                             m-kth1   (+ mean (/ (- metric mean) kth))
                             s-kth1   (+ s (* (- metric mean) (- metric m-kth1)))
                             mean     m-kth1
                             variance (/ s-kth1 kth)
                             stdev    (Math/sqrt variance)]
                         {mean-key (double mean) sum-of-squares-key s-kth1 variance-key variance stdev-key stdev count-key kth
                          ::dumping? prefix ::start-dumping? (and prefix (not dumping?))})))))]
    (fn welford-streamer [by-path state event]
      (log/debug :WELFORD event)
      (let [d-k (key-factory by-path state-key)
            {{start-dumping? ::start-dumping? :as new-stats} d-k
             send2agent      :caudal/send2agent
             :as new-state} (update state d-k mutator event)]
        (if start-dumping?
          (repeat-every ::dump-every-welford (cycle->millis freq) 1 send2agent (partial dumper-fn prefix date-fmt path) by-path d-k))

        (propagate by-path new-state (merge event (dissoc new-stats ::dumping? ::start-dumping?)) children)))))

; El archivo de configuracion edn tieme:
{"FelipeGerard1" {:name "Felipe Gerard C."}
 "Juan_camanei" {}}

; list example
{"FelipeGerard1" {:last-check 123124234123
                  :name "Felipe Gerard"
                  :image-url "http://....."}
 "Juan_camanei" {:last-check 23423243424
                 :name "Juan_camanei"
                 :image-url nil}}

(defn update-list "Esta funcion respeta la lista de configuracion y solo mete los datos extras de la running"
  [config-list running-list]
  (reduce
   (fn [result [userid {:keys [last-check image-url]}]]
     (if-let [val (get result userid)]
       (update result userid #(assoc % :last-check last-check :image-url image-url))
       result))
   config-list
   running-list))

(defn compute-not-present [list frontier-ts]
  (reduce
   (fn [result [userid {:keys [name last-check] :as data}]]
     (if (or (nil? last-check) (< last-check frontier-ts))
       (conj result {:userid userid :name name})
       result))
   []
   list))

(defn keys-to-lower [m]
  (reduce
   (fn [result [k v]]
     (assoc result (.toLowerCase k) v))
   {}
   m))

(defn checker [[state-key list-edn-path] & children]
  (let [mutator (->toucher
                 (fn checker-mutator [{:keys [list last-frontier-ts config-last-modified] :or {config-last-modified 0 last-frontier-ts 0} :as state}
                                      {:keys [userid name created reporte? report-ts image-url] :as e}]
                   (let [report-ts (if (= "ongoing" userid) last-frontier-ts report-ts)
                         config-file (java.io.File. list-edn-path)
                         last-modified (.lastModified config-file)
                         readed-list (if (< config-last-modified last-modified)
                                       (-> (slurp config-file) read-string keys-to-lower))
                         list (if readed-list (update-list readed-list list) list)]
                     (if (and list (or (= "ongoing" userid) (get list userid)))
                       (if reporte?
                         (if (= 0 report-ts)
                           state
                           (assoc state :not-present (compute-not-present list report-ts) :list list :last-frontier-ts report-ts))
                         (let [new-list (update list userid #(assoc % :last-check created :image-url image-url :name name))]
                           {:list new-list
                            :last-frontier-ts last-frontier-ts
                            :config-last-modified last-modified}))
                       state))))]
    (fn checker-streamer [by-path state event]
      (log/debug :CHECKER event)
      (let [d-k (key-factory by-path state-key)
            {{:keys [not-present]} d-k
             send2agent      :caudal/send2agent
             :as new-state} (update state d-k mutator event)]
        (if not-present
          (propagate by-path new-state (assoc event :not-present not-present) children)
          new-state)))))
