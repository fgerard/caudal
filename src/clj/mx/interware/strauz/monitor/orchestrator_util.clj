(ns mx.interware.strauz.monitor.orchestrator-util
  (:gen-class)
  (:require [clojure.string :as s]
            [clojure.tools.logging :as log]
            [clojure.core.async :refer [<! chan go-loop alts!]]
            [mx.interware.caudal.io.tailer-server :refer [initialize-tail]]

            [mx.interware.caudal.streams.common :refer [propagate]]
    ;[mx.interware.caudal.bbva.monitor.util.misc :as m]
            )
  (:import (java.text SimpleDateFormat)
           (clojure.lang IPersistentMap)))

(def ymdhms-format "yyyy-MM-dd HH:mm:ss.SSS")

(def ymdthms-format "yyyy-MM-dd'T'HH:mm:ss.SSS")

(defn parse-date [date-fmt date-str]
  (let [sdf           (SimpleDateFormat. date-fmt)
        len           (count date-str)
        date-fmt-len  (count date-fmt)
        date-adjusted (if (< len date-fmt-len)
                        (apply str date-str (repeat (- date-fmt-len len) "0"))
                        date-str)]
    (.parse sdf date-adjusted)))

(defn parse-ymdhms-date [date-str]
  (parse-date ymdhms-format date-str))

(defn parse-ymdThms-date [date-str]
  (let [sdf (SimpleDateFormat. ymdthms-format)]
    (.parse sdf date-str)))


(def regex #"^:strauz (.*) INFO.* - (.*)$")

(defmulti parse-orchestrator-line
          (fn [line]
            (if (re-find #"] orc.core.orchestrator \-" line)
              :orchestrator)))

(defmethod parse-orchestrator-line :orchestrator [line]
  ;(log/info "---> line : " line)
  (let [;[_ log-date event-str] (re-matches regex line)
        log-date (subs line 8 31)
        event-str (subs line 99)
        ;log-date  (nth items 1)
        ;_         (println "log-date : " log-date)
        ;event-str (nth items 4)
        event     (read-string event-str)
        event     (if (instance? IPersistentMap event) event)
        ;_     (log/info "class event : " (class event))
        ;_         (log/info "      event : " event)
        ]
    (assoc event :log-date log-date)))

(defn agent-pre-process [& children]
  (fn stream [by-path state event]
    (let [log-date-str (get event :log-date)
          event-ts     (parse-ymdThms-date log-date-str)
          log-millis   (.getTime event-ts)]
      (propagate by-path state (merge event {:ms log-millis :ts event-ts :mts log-millis}) children))))

(defn agent-post-process [& children]
  (fn stream [by-path state event]
    (let [log-date-str (get event :log-date)
          log-date     (parse-ymdThms-date log-date-str)
          log-millis   (.getTime log-date)
          event        (dissoc event [:ssquare :caudal/latency :msg :err])
          event        (assoc event :avg (double (get event :avg)))]
      (propagate by-path state event children))))

(defn progress [[progress-str] & children]
  (fn stream [by-path state event]
    (println progress-str)
    (propagate by-path state event children)))

(defn xdo [& children]
  (fn [by-path state e]
    (propagate by-path state e children)))

(defmethod parse-orchestrator-line :default [line]
  ;(log/info "---> skipping ...")
  )

(defn doit [])

(comment def ids (let [root-path     "/Users/axis/Desktop/tmp/strauz/20170717/files/perdidos"
                       input-regexp  #"^.*(wcfd)-(.*)_.*$"
                       output-regexp #"^.*(iwc)-(.*)-VOL.*$"
                       input-lines   (line-seq (BufferedReader. (StringReader. (slurp (str root-path "/inputs.txt")))))
                       output-lines  (line-seq (BufferedReader. (StringReader. (slurp (str root-path "/outputs.txt")))))
                       input-ids     (into #{} (map #(nth (re-matches input-regexp %) 2) input-lines))
                       output-ids    (into #{} (map #(nth (re-matches output-regexp %) 2) output-lines))]
                   [input-ids output-ids]))
