;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns mx.interware.caudal.io.tcp-server
  (:require [clojure.tools.logging :as log]
            [mx.interware.caudal.streams.common :refer [start-listener]])
  (:import (java.net InetSocketAddress)
           (java.nio.charset Charset)
           (org.apache.log4j PropertyConfigurator)
           (org.apache.mina.core.session IdleStatus)
           (org.apache.mina.filter.codec ProtocolCodecFilter)
           (org.apache.mina.filter.codec.textline TextLineCodecFactory)
           (org.apache.mina.filter.logging LoggingFilter LogLevel)
           (org.apache.mina.transport.socket.nio NioSocketAcceptor)
           (org.apache.mina.core.session IoSession IdleStatus)))

(defn read-event [str]
  (try
    (read-string str)
    (catch Exception e
      nil)))

(defn create-handler [sink]
  (let [handler (reify org.apache.mina.core.service.IoHandler
                  (^void exceptionCaught [this ^IoSession session  ^Throwable cause]
                    (.printStackTrace cause))
                  (^void inputClosed [this  ^IoSession session]
                    (log/info "Client closed connection")
                    (.closeNow session))
                  (^void messageReceived [this ^IoSession session ^Object message]
                    (let [message-str (.toString message)]
                      ;(log/debug "message-str : " message-str)
                      (when-let [event (read-event message-str)]
                        (if (= "EOT" message-str)
                          (.closeOnFlush session)
                          (do
                            ;(log/info "Processing event : " event " ...")
                            ;(log/debug :is-vector? (vector? event))
                            (if (vector? event)
                              (doseq [e event]
                                (sink e))
                              (sink event)))))))
                  (^void messageSent [this  ^IoSession session ^Object message])
                  (^void sessionClosed [this ^IoSession session])
                  (^void sessionCreated [this ^IoSession session])
                  (^void sessionIdle [this ^IoSession session ^IdleStatus status]
                    (log/debug "IDLE " (.getIdleCount session status)))
                  (^void sessionOpened [this ^IoSession session]))]
    handler))

(defn start-server [port idle-period sink]
  ;(PropertyConfigurator/configure "log4j.properties")
  (try
    (let [buffer-size     4096
          max-line-length (* 1024 1024)
          acceptor        (new NioSocketAcceptor)
          codec-filter    (new ProtocolCodecFilter
                               (doto
                                 (new TextLineCodecFactory (Charset/forName "UTF-8"))
                                 (.setDecoderMaxLineLength max-line-length)
                                 (.setEncoderMaxLineLength max-line-length)))
          filter-chain    (.getFilterChain acceptor)
          session-config  (.getSessionConfig acceptor)
          _               (println "1. " sink)
          handler         (create-handler sink)
          _               (println "2. " port)
          socket-address  (new InetSocketAddress port)
          _               (println "3. ")
          logging-filter  (doto (new LoggingFilter)
                            (.setMessageReceivedLogLevel LogLevel/DEBUG)
                            (.setMessageSentLogLevel LogLevel/DEBUG)
                            (.setSessionClosedLogLevel LogLevel/DEBUG)
                            (.setSessionCreatedLogLevel LogLevel/DEBUG)
                            (.setSessionIdleLogLevel LogLevel/DEBUG)
                            (.setSessionOpenedLogLevel LogLevel/DEBUG))]
      (log/info "Starting server on port : " port " ...")
      (.addLast filter-chain "logger" logging-filter)
      (.addLast filter-chain "codec" codec-filter)
      (.setHandler acceptor handler)
      (.setReadBufferSize session-config buffer-size)
      (.setIdleTime session-config (IdleStatus/BOTH_IDLE) idle-period)
      (.setReuseAddress acceptor true)
      (.bind acceptor socket-address))
    (catch Exception e
      (.printStackTrace e))))

(defmethod start-listener 'mx.interware.caudal.io.tcp-server
  [sink config]
  (println (pr-str config))
  (let [{:keys [port idle-period]} (get-in config [:parameters])]
    (start-server port idle-period sink)))
