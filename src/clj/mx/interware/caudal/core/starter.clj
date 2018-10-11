;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns mx.interware.caudal.core.starter
  (:gen-class
   :name mx.interware.caudal.core.Starter)
  (:require [clojure.tools.logging :as log]
            [clojure.pprint :as pp]
            [clojure.string :as string]
            [clojure.core.async :as async :refer [chan go go-loop timeout <! >! <!! >!! mult tap]]
            [mx.interware.caudal.streams.common :refer [start-listener create-sink]]
            [mx.interware.caudal.util.ns-util :refer [resolve&get-fn require-name-spaces]]
            [mx.interware.caudal.util.caudal-util :refer [create-caudal-agent]])
  (:import (org.apache.log4j PropertyConfigurator)))

;(PropertyConfigurator/configure "log4j.properties")

(defn -main [& [conf-file-path & args]]
  (let [env-config-str    (if conf-file-path
                            (slurp conf-file-path)
                            (System/getenv "CAUDAL_CONFIG"))
        caudal-config-str (if env-config-str
                            env-config-str
                            (System/getProperty "CAUDAL_CONFIG"))
        config            (read-string caudal-config-str)
        listeners         (get-in config [:caudal :listeners])
        sink-map          (reduce
                            (fn [sink-map [stream-k {:keys [origin back-pressure-limit] :or {back-pressure-limit 10000}}]]
                              (log/debug (str origin))
                              (let [origin-ns (symbol (namespace origin))
                                    _         (require origin-ns)
                                    streams   (resolve origin)
                                    state     (create-caudal-agent)
                                    sink      (create-sink state streams :back-pressure-limit back-pressure-limit)]
                                (assoc sink-map stream-k [sink state stream-k])))
                            {}
                            (get-in config [:caudal :streams]))]
    ;(PropertyConfigurator/configure "log4j.properties")
    (doseq [{:keys [type stream-to] :as listener} listeners]
      (try
        (let [_           (require type)
              sinks       (map second
                               (filter
                                 (fn [[sink-k [sink state]]]
                                   (some (fn [stream-to] (= sink-k stream-to)) stream-to))
                                 sink-map))
              D-sink      (reduce comp (map first sinks))
              sink-vector (map (fn [terna] (vec (rest terna))) sinks)
              states-fn   (fn [states [state stream-k]]
                            (assoc states stream-k state))
              D-states    (reduce states-fn {} sink-vector)
              listener    (assoc listener :states D-states)]
          ;sinks es una coleccion de parejas [sink store]; sink es una funcion [e] que manda e e a (streams state e)
          (start-listener D-sink listener))
        (catch Exception e
          (log/warn "Listener could not be started. Reason : " (.getMessage e) " in listener : " listener))))))
