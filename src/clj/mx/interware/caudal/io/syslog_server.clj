;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns mx.interware.caudal.io.syslog-server
  (:require [clojure.tools.logging :as log]
            [mx.interware.caudal.streams.common :refer [start-listener]]
            [mx.interware.caudal.util.ns-util :refer [resolve&get-fn require-name-spaces]])
  (:import (java.net InetSocketAddress ServerSocket Socket SocketException)
           (java.io BufferedReader InputStreamReader)
           (org.productivity.java.syslog4j.server.impl.event SyslogServerEvent)
           (org.productivity.java.syslog4j.server.impl.event.structured
            StructuredSyslogServerEvent)))

(defn structured-data?
  "Return true if receive-data is structured. When receive-data is a structured
  syslog message must be parsed with StructuredSyslogServerEvent, see:
  [https://tools.ietf.org/html/rfc5424#section-6.5] otherwise, with standard
  SyslogServerEvent, see: [https://tools.ietf.org/html/rfc3164#section-5.4]
  - *receive-data* Syslog data to be parsed"
  [receive-data]
  (try 
    (let [idx (.indexOf receive-data ">")]
      (and (not= idx -1)
           (> (.length receive-data) (inc idx))
           (Character/isDigit (.charAt receive-data (inc idx)))))
    (catch Exception e
      (log/warn (.getCause e))
      false)))

(defn get-rdata [receive-data inaddr]
  (if receive-data 
    (let [idx (.indexOf receive-data ">")]
      (if (and (not= idx -1)
               (> (.length receive-data) (inc idx))
               (Character/isDigit (.charAt receive-data (inc idx))))
        (StructuredSyslogServerEvent. receive-data inaddr)
        (SyslogServerEvent. receive-data inaddr)))))

(defn data->event
  "Converts received-data to Caudal Event
  - *receive-data* Syslog data to be parsed
  - *inaddr* InetAddress to build a ServerEvent
  - *message-parser* to parse message field"
  [receive-data inaddr message-parser]
  (try
    (if-let [object (get-rdata receive-data inaddr)]
      (let [data-map     (bean object)
            {:keys [date processId facility rawLength level host message]} data-map
            date (if date date (java.util.Date.))
            caudal-event (merge
                          {:facility   facility
                           :length     rawLength
                           :level      level
                           :process-id processId
                           :host       host
                           :message    message
                           :timestamp  (.getTime date)}
                          (when-let [st-msg (:structuredMessage data-map)]
                            (let [msg-m (bean st-msg)
                                  {:keys [message messageId structuredData]} msg-m]
                              (merge {:message   message
                                      :messageId messageId}
                                     (into {} structuredData))))
                          (when message-parser (message-parser message)))]
        caudal-event))
    (catch Exception e
      (log/error "Error " e))))

(defn start-server
  "Starts TCP Syslog Server
  - *port* Port to listen Syslog data
  - *message-parser* to parse message field
  - *sink* to pass Caudal Events"
  [port message-parser sink]
  (let [sserv (ServerSocket. port)]
    (future
      (while (not (.isClosed sserv))
        (try
          (let [socket (.accept sserv)
                inaddr (.getInetAddress socket)
                _      (log/info :accepting-syslog-connections :port port)
                br     (BufferedReader. (InputStreamReader.
                                          (.getInputStream socket)))]
            (loop [line (.readLine br)]
              (if-let [caudal-event (data->event line inaddr message-parser)]
                (do (sink caudal-event)
                    (recur (.readLine br))))))
          (catch SocketException e
            (log/warn (.getMessage e)))
          (catch Exception e
            (log/error (.getMessage e))
            (.printStackTrace e)))))
    sserv))

(defmethod start-listener 'mx.interware.caudal.io.syslog-server
  [sink config]
  (let [{:keys [port parser]} (get-in config [:parameters])
        parse-fn   (if (symbol? parser) (resolve&get-fn parser) parser)
        _ (log/debug {:syslog-server :starting :config config})]
    (start-server port parse-fn sink)))
