;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns caudal.streams.common
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.walk :refer [walk postwalk]]
            [clojure.core.async :as async :refer [chan go go-loop timeout <! >! <!! >!! alts! put! dropping-buffer]]
            ;[immutant.caching :as C]
            [caudal.core.state :as ST]
            [caudal.util.caudal-util :refer [create-caudal-agent rotate-file]])

  (:import (java.net InetAddress URL)
           (org.apache.log4j PropertyConfigurator)
           ;(org.infinispan.configuration.cache ConfigurationBuilder)
           ))

;(print "compiling caudal.streams.common")

; debe de estar al principio porque si no no compila !!!!
(def ws-publish-chan (chan (dropping-buffer 100)))


(defn line-stats* [msg print-rate]
  (let [atm (atom [nil 0])]
    (fn []
      (let [[start cnt] @atm
            now (System/currentTimeMillis)
            start (or start now)
            delta (- now start)
            delta (if (<= delta 0) 1 delta)
            cnt (inc cnt)]
        (reset! atm [start cnt (double (/ (* 1000 cnt) delta)) now])
        (if (= 0 (mod cnt print-rate))
          (log/debug "LINE-STATS: " (pr-str @atm) " " msg))
        @atm))))

(def line-stats (memoize line-stats*))

(defn touch-info [{created :caudal/created
                   :or {created (System/currentTimeMillis)}
                   :as info}]
  (assoc info :caudal/created created :caudal/touched (System/currentTimeMillis)))

(defn ->toucher [mutator-fn]
  (log/debug (str mutator-fn))
  (let [[_ type] (re-matches #"^caudal.streams.stateful\$([a-zA-Z\-\_]+)\$.*" (str mutator-fn))]
    (fn [{c-created :caudal/created
          :as info} & params]
      (when-let [mutator-result (apply mutator-fn info params)]
        (let [mutator-result (assoc mutator-result :caudal/type type)]
          (cond-> mutator-result
                  c-created (assoc :caudal/created c-created)
                  true      touch-info))))))

(defn exec-in [origin delta D-fn & args]
  (let [abort-chan (chan)]
    (go
     (let [[cmd _ :as x] (alts! [(timeout delta) abort-chan])]
       (when cmd (log/debug "ABORTER: " cmd))
       (when-not cmd
         (log/debug "executing from: " origin)
         (apply D-fn args))))
    abort-chan))

(defn repeat-every [origin delta count D-fn & args]
  (log/debug "repeat-every from:" origin " delta=" delta " count=" count)
  (go-loop [n count]
    (when (or (> n 0) (= n -1))
      (<! (timeout delta))
      (log/debug "executing from: " origin)
      (apply D-fn args)
      (recur (if (> n 0) (dec n) n)))))

(defn add-attr [e k & params]
  (assoc e k (apply str params)))

(defmulti key-factory (fn [_ k] (coll? k)))

(defmethod key-factory false [by-path k]
  (if k
    (vec (cons k (or by-path [])))
    by-path))

(defn complex-key-factory [by-path k-vec]
  (if k-vec
    (into k-vec (or by-path []))
    by-path))

; /default/counter/by(%)/batch/by(%)/->ERROR  etc
(defmethod key-factory true [by-path k-path]
  (let [by-path  (or by-path [])
        by-sufix (fn [by-path idx]
                   (if-let [elem (get by-path idx)]
                     (str "(" elem ")")
                     ""))
        d-k      (reduce
                   (fn [[path-str by-path-idx] path-element]
                     (cond
                       (= "by" path-element)
                       [(str path-str "/" path-element (by-sufix by-path by-path-idx)) (inc by-path-idx)]

                       (= "join" path-element)
                       [(str path-str "/" path-element) (dec by-path-idx)]

                       :OTHERWISE
                       [(str path-str "/" path-element) by-path-idx]))
                   ["" 0]
                   k-path)]
    (first d-k)))

(defn error
  ([msg]
   (log/info msg))
  ([msg ex]
   (log/info msg)
   (.printStackTrace ex)))

(defn propagate [by-path state e children]
  (reduce
    (fn [computed-state child]
      (try
        (child by-path computed-state e)
        (catch Throwable t
          (.printStackTrace t)
          (log/warn (Exception. "para stacktrace"))
          (log/error t)
          (log/error "Error propagating event!" (pr-str e) (-> t .getClass .getName) (.getMessage t))
          computed-state)))
    state
    children))

(defn- compute-latency [latency-init e]
  (assoc e :caudal/latency (- (System/nanoTime) latency-init)))

(defn mutate-selector [state sem latency-init {caudal-cmd :caudal/cmd} & _]
  (or caudal-cmd :send2streams))

(defmulti mutate! mutate-selector)

;clear-all solo deja en el state los :caudal/... que son: entry, send2agent y latency
(defmethod mutate! :clear-all [state sem latency-init e & _]
  (try
    (let [e (compute-latency latency-init e)]
      (reduce
        (fn [result [k v]]
          (if (and (keyword? k) (= "caudal" (namespace k)))
            (assoc result k v)
            result))
        {} ;(assoc state :caudal/latency (:caudal/latency e))
        (assoc state :caudal/latency (:caudal/latency e)) ;state
        ))
    (catch Throwable t
      (.printStackTrace t)
      state)
    (finally
      (.release sem))))

(defn purge-rate-buckets [frontier-delta [rate-key rate-node]]
  (let [frontier (- (System/currentTimeMillis) frontier-delta)]
    (if-let [new-buckets (seq
                          (drop-while (fn [[ts v]]
                                        (< ts frontier))
                                      (:buckets rate-node)))]
      (assoc rate-node :buckets (into (sorted-map) new-buckets)))))

(defn caudal-keyword? [thing]
  (and  (keyword? thing) (= (namespace thing) "caudal")))

(defmethod mutate! :purge-state! [state sem latency-init {:keys [selector reducer] :as e} & _]
  (try
    (if (and selector reducer)
      (reduce
       (fn [result [k v :as node-pair]]
         (if (and (not (caudal-keyword? k)) (selector node-pair))
           (if-let [new-node (reducer node-pair)]
             (assoc result k new-node)
             (dissoc result k))
           result))
       state
       state)
      state)
    (catch Throwable t
      (log/error t)
      state)
    (finally
      (.release sem))))

(defmethod mutate! :clear-keys [state sem latency-init {:keys [filter-key-fn filter-key-re filter-key] :as e} & _]
  (try
    (let [e         (compute-latency latency-init e)
          filter-fn (cond
                      filter-key-fn
                      filter-key-fn

                      filter-key-re
                      (fn [k]
                        (re-matches filter-key-re (str k)))

                      filter-key
                      (fn [[k & _]]
                        (= k filter-key)))]
      (reduce
        (fn [state [k v]]
          (if (and (coll? k) (filter-fn k))
            (dissoc state k)
            state))
        (assoc state :caudal/latency (:caudal/latency e))
        state))
    (catch Throwable t
      (.printStackTrace t)
      state)
    (finally
      (.release sem))))

(defn intern-caudal-state-as-map [state]
  (let [;result (reduce
        ;        (fn [result [k v]]
        ;          (if (and (keyword? k) (= "caudal" (namespace k)))
        ;            (assoc result k "object") ;; dissoc :caudal/* objects
        ;            (postwalk identity result))) ;; #(if (map? %) (dissoc % :caudal/aborter) %)
        ;        state
        ;        state)
        result (postwalk (fn [elem]
                           (if (and (vector? elem) (= 2 (count elem)))
                             (let [[k v] elem]
                               (if (or (fn? v) (.startsWith (pr-str v) "#object")) ;(keyword? k) (= "caudal" (namespace k))
                                 [k "object"]
                                 [k v]))
                             elem))
                         state)
        result (into (sorted-map-by (fn [k1 k2] (compare (str k1) (str k2)))) result)
        _ (log/debug {:state result})]
    result))

(defn caudal-state-as-map [caudal-agent]
  (intern-caudal-state-as-map @caudal-agent))

(let [dump-chan (chan 10)]
  (go-loop []
    (let [[dump-file versions state] (<! dump-chan)]
      (try
        (let [dump-file-template (java.io.File. dump-file)
              parent-file (.getParentFile dump-file-template)]
          (.mkdirs parent-file)
          (with-open [out (java.io.FileWriter. (rotate-file parent-file dump-file-template versions))]
            (binding [*out* out]
              (pp/pprint (intern-caudal-state-as-map state)))))
        (catch Throwable e
          (.printStackTrace e))))
    (recur))

  (defmethod mutate! :dump-state [state sem latency-init {:keys [dump-file versions] :or {versions 1} :as event} & _]
    (try
      (>!! dump-chan [dump-file versions state])
      state
      (catch Throwable t
        (.printStackTrace t)
        state)
      (finally
        (.release sem)))))

(defmethod mutate! :load-history [state sem latency-init {:keys [path go-back-millis date-fmt key-name latency]
                                                          :or {go-back-millis 0} :as event} & _]
  (try
    (let [event            (compute-latency latency-init event)
          date-prefix      (if date-fmt
                             (str (.format (java.text.SimpleDateFormat. date-fmt) (- (System/currentTimeMillis) go-back-millis)) "-")
                             "")
          file-name-filter (str date-prefix key-name)
          _                (log/debug "loading history:" file-name-filter)
          file-filter-re   (re-pattern (str "(" file-name-filter ")\\-(.*)" "[\\.]edn|(" file-name-filter ")\\.edn"))
          _                (log/debug "loading history file-re:" file-filter-re)
          filter           (reify java.io.FilenameFilter
                             (^boolean accept [this ^java.io.File file ^String name]
                               (if (re-matches file-filter-re name)
                                 true
                                 false)))
          files            (-> (java.io.File. path) (.listFiles filter))
          file-subscript   (doall
                             (map
                              (fn [f]
                                (let [colofones ((re-matches file-filter-re (.getName f)) 2)]
                                  [f (if colofones (clojure.string/split colofones #"\\-"))]))
                              files))]
      (reduce
       (fn [state [d-file colofon]]
         (try
           (let [info (read-string (slurp d-file))
                 k    (into [(keyword key-name)] colofon)]
             (log/debug "se añade al state:" k info)
             (assoc state k info))
           (catch Throwable t
             (log/error
              "Problem loading history: "
              (.getAbsolutePath d-file)
              (-> t .getClass .getName) (.getMessage t))
             state)))
       (assoc state :caudal/latency (:caudal/latency event))
       file-subscript))
    (catch Throwable t
      (.printStackTrace t)
      state)
    (finally
      (.release sem))))

(defn clean-state [state]
  (into {} (filter
            (fn [[k v]]
              (or (not (keyword? k)) (not (namespace k)) (= k :caudal/last-persistence)))
            state)))

(defn event->event2log [event]
  (let [d-type (type event)
        event-str (str event)
        event-len (count event-str)
        event2log (str d-type ":" (subs event-str 0 (min event-len 50)) (if (> event-len 50) "..." ""))]
    event2log))

(defmethod mutate! :send2streams [{persistence :caudal/persistence
                                   last-persistence :caudal/last-persistence
                                   :or {last-persistence 0}
                                   :as state} sem latency-init e & [streams & _]]
  ;(println :STATE state :persistence persistence)
  (let [v-result (try
                   (let [result (cond
                                  (vector? e)
                                  (reduce
                                   (fn [state event]
                                     (if (map? event)
                                       (streams [] state (compute-latency latency-init event))
                                       (do 
                                         (log/warn "Dropping invalid event: " (event->event2log event))
                                         state)))
                                   state
                                   e)

                                  (map? e)
                                  (streams [] state (compute-latency latency-init e))

                                  :OTHERWISE
                                  (do
                                    (log/warn "Dropping invalid event: " (event->event2log  e))
                                    state))]
                     result)
                   (catch Throwable t
                     (.printStackTrace t)
                     state)
                   (finally
                     (log/debug :RELEASE :SEM (.availablePermits sem) e)
                     (.release sem)))
        now (System/currentTimeMillis)
        one-hour (* 1000 60 60)
        persist? (and (> (- now one-hour) last-persistence) now)]
    (when (and persistence persist?)
      (future
       (let [cleaned-state (clean-state v-result)]
         (try
           (with-open [out (java.io.FileWriter. (rotate-file (.getParentFile persistence) persistence 5))]
             (binding [*out* out]
               (pp/pprint cleaned-state)))
         (catch Exception e
           (log/error e))))))
    (cond-> v-result
            (and persistence persist?) (assoc :caudal/last-persistence persist?))))
      ;(log/debug (System/currentTimeMillis) " release:" (.availablePermits sem)))))

(defn create-sink [state streams & {:keys [back-pressure-limit persistence-dir]
                                    :or {back-pressure-limit 1000}}]
  (let [sink       (let [sem (java.util.concurrent.Semaphore. back-pressure-limit true)]
                     (fn [e]
                       (let [nano (System/nanoTime)]
                         (log/debug (str "AVAILABLE PERMITS:" (.availablePermits sem)))
                         (.acquire sem)
                         (if (log/enabled? :debug) ((line-stats :sink 1)))
                         (send state mutate! sem  nano e  streams)
                         e)))                                                                                                 ;este retorno de e es para poder componer varios sink!!!
        send2agent (fn [mutator-fn & args]
                     (apply send state mutator-fn args))]
    (send state assoc :caudal/entry sink :caudal/send2agent send2agent)
    (log/info "Attaching send2agent and sink to state ")
    (Thread/sleep 1000)
    sink))


(defmulti start-listener (fn [sink config] (:type config)))

(defmethod start-listener :default [sink config]
  (log/error "Invalid listener : " (:type config)))

;;;;;;;;; streams using path utils

(defn introduce-path [stateless stateful form]
  (let [all-streams (into stateless stateful)
        path        (atom [])]
    (letfn [
            (trim-ns [e]
              (if-let [sym-ns (and (symbol? e) (namespace e))]
                (symbol (subs (str e) (inc (count (name sym-ns)))))
                e))
            (output [e]
              (if (and (seq? e)
                       (if (vector? (first e))
                         (all-streams (trim-ns (ffirst e)))
                         (all-streams (trim-ns (first e)))))
                (swap! path
                       (fn [d-path]
                         (vec (butlast d-path)))))
              (if (and (seq? e) (vector? (first e)))
                (do
                  (let [[[sym path] & params] e]
                    (apply list sym path params)))
                e))
            (analyze [e]
              (let [trimed-e (trim-ns e)]
                (if (seq? e)
                  (walk analyze output e)
                  (if (all-streams trimed-e)
                    (do
                      (swap! path conj (name trimed-e))
                      (if (stateful trimed-e)
                        [e @path]
                        e))
                    e))))]
      (walk analyze output form))))

(defn create-set-of-symbols [ns]
  (let [ns-coll (if (coll? ns) ns [ns])]
    (reduce (fn [result ns]
              (require (symbol ns))
              (into result (map first (ns-publics (symbol ns)))))
            #{}
            ns-coll)))

(defmacro using-path [stateless-ns-s stateful-ns-s form]
  (let [stateless         (create-set-of-symbols stateless-ns-s)
        stateful          (create-set-of-symbols stateful-ns-s)
        streams-with-path (introduce-path stateless stateful form)]
    (comment
      (log/debug :stateless stateless)
      (log/debug :stateful stateful)
      (log/debug)
      (log/debug :using-path-entry-form)
      (pp/pprint form)
      (log/debug)
      (log/debug :using-path-modified-form)
      (pp/pprint streams-with-path))
    streams-with-path))

; estos declare es para que calva no se queje
(declare caudal.streams.stateless)
(declare caudal.streams.stateful)

(defmacro using-path-with-default-streams [form]
  (using-path caudal.streams.stateless caudal.streams.stateful form))

(defmacro defstream [& params]
  (let [[name [params & form]] (if (symbol? (first params))
                                 [(first params) (rest params)]
                                 [nil params])
        params (cond
                 (nil? (seq params))
                 ['_by-path '_state '_event]

                 (= (count params) 1)
                 (into ['_by-path '_state] params)

                 (= (count params) 2)
                 (into ['_by-path] params)

                 (= (count params) 3)
                 (vec params))]
    (if name
      `(fn ~name ~params (do ~@form ~(second params)))
      `(fn ~params (do ~@form ~(second params))))))

(defn defsink* [name limit persistence-dir streamer]
  (let [state (create-caudal-agent name persistence-dir)
        sink  (create-sink state streamer :back-pressure-limit limit :persistence-dir persistence-dir)]
    {:id name :sink sink :state state}))

(defmacro defsink [name limit streamer]
  (let [id (str name)]
    `(def ~name (defsink* ~id ~limit nil ~streamer))))

(defmacro defpersistent-sink [name limit persistence-dir streamer]
  (let [id (str name)]
    `(def ~name (defsink* ~id ~limit ~persistence-dir ~streamer))))

(defn defsinks [& sinks]
  (reduce comp sinks))

(defn comp-sink [sink-maps]
  (reduce
   (fn [result {:keys [sink]}]
     (comp result sink))
   identity
   sink-maps))

(defn deflistener* [[config] & sinks]
  (let [type (:type config)
        _    (require type)
        sink (comp-sink sinks)
        states (into {} (map
                         (fn [{:keys [id state] :as sinks2}]
                           (log/debug :sinks2 sinks2 :id id)
                           [(keyword (name id)) state])
                         sinks))]
    (start-listener sink (assoc config :states states))))

(defmacro deflistener [name & body]
  `(let [evalbody# ~@body]
     (def ~name evalbody#)))

(defn config-view [sinks conf]
  (doseq [sink sinks]
    (send (:state sink) assoc :caudal/view-conf conf))
  (Thread/sleep 2000))

(defmacro wire [listeners streamers]
  `(doall
    (map
      (fn [listener#]
        (apply deflistener* listener# ~streamers))
      ~listeners)))
