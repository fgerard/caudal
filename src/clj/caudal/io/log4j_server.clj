;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns caudal.io.log4j-server
  (:require [clojure.tools.logging :as log]
            [clojure.pprint :as pp]
            [clojure.core.async :refer [chan >!! >! <! alts! go-loop]]
            [caudal.streams.common :refer [start-listener]]
            [caudal.util.ns-util :refer [resolve&get-fn require-name-spaces]])
  (:import (java.net InetSocketAddress ServerSocket Socket)
           (java.io ObjectInputStream BufferedInputStream DataInputStream)))

(defn start-server [port message-parser sink]
  (let [sserv (ServerSocket. port)
        _ (log/info "Starting Log4J Server, port:" port)]
    (while (not (.isClosed sserv))
        (try
          (let [socket (.accept sserv)
                ois    (ObjectInputStream. (BufferedInputStream. (.getInputStream socket)))]
            (future
              (try 
                (loop [obj (.readObject ois)]
                  (let [{:keys [loggerName level threadName properties message timeStamp locationInformation]} (bean obj)
                        info     (when locationInformation (bean locationInformation))
                        parsed   (when message-parser (message-parser message))
                        location (when-not (= "?" (:className info)) (dissoc info :class))
                        caudal-e (merge
                                  {:logger      loggerName
                                   :thread      threadName
                                   :level       (.toString level)
                                   :message     message
                                   :timestamp  timeStamp}
                                  (into {} (map (fn [[k v]]
                                                  [(keyword k) v]) properties))
                                  location
                                  parsed)]
                    (sink caudal-e)
                    (recur (.readObject ois))))
                (catch Exception e  
                  (if-let [msg (.getMesssge e)]
                    (log/error msg))))))
          (catch Exception e
            (.printStackTrace e)
            (log/error (.getMessage e)))))
    sserv))

(defmethod start-listener 'caudal.io.log4j-server
  [sink config]
  (let [{:keys [port parser]} (get-in config [:parameters])
        parser-fn (if parser (if (symbol? parser) (resolve&get-fn parser) parser))]
    (start-server port parser-fn sink)))
