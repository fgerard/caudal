;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns mx.interware.caudal.io.log-feed-simulator
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [mx.interware.caudal.test.simple-parser :refer [parse-cathel-msg]])
  (:import (java.net InetSocketAddress Socket)
           (java.io PrintStream BufferedOutputStream)))

;(require '[mx.interware.caudal.io.log-feed-simulator :as F] :reload-all)
;(F/do-it)

;este archivo lee log de ivx y los saca a la velocidad similar a la que viene en el log de entrada
;09:11:11,503 INFO  [stdout] (ajp-/0.0.0.0:8209-20) ES ITAU - BancoCuenta-> 463186
;09:11:11,504 INFO  [stdout] (ajp-/0.0.0.0:8209-20) MainController:2482 - FECHA ITAU: 28/10/1971
;09:11:11,504 INFO  [stdout] (ajp-/0.0.0.0:8209-20) ConsultaIVR:153 - mainController.getNotePad().setTarjeta(tarjeta): 4631860005977695

(def LOG-LINE-TEMPLATE
  ;#"([0-9]{2}:[0-9]{2}:[0-9]{2}),([0-9]{3}) INFO  \[stdout\] \(ajp\-\/0.0.0.0:8209-([0-9]{1,3})\) (.*)"
  #":strauz [0-9]{4}\-[0-9]{2}\-[0-9]{2}T([0-9]{2}:[0-9]{2}:[0-9]{2})\.([0-9]{3}).*"
  )

(defn calc-wait [last-ref hora milli]
  (let [sdf      (java.text.SimpleDateFormat. "HH:mm:ss.SSSZZZZZ")
        str-date (str hora "." milli "+0000")
        last     (or last-ref (.parse sdf str-date))
        next     (.parse sdf str-date)
        delta (- (.getTime next) (.getTime last))
        delta (if (>= delta 0) delta 0)]
    [next delta]))

(defn redo-log [in-file-name out-file-name & {:keys [ini end with-wait rate]
                                              :or   {ini       0
                                                     end       999999999
                                                     with-wait true
                                                     rate      1}}]
  (with-open [in  (io/reader in-file-name)
              out (io/writer out-file-name :append true)]
    (let [in-lines (->>
                     (line-seq in)
                     (keep-indexed
                       (fn [index line]
                         (when (<= ini index end)
                           ;(log/debug line)
                           [index line]))))]
      (letfn [(logger [last-date [index line]]
                ;(if (or (= 5 index) (= 400002 index)) (do (log/debug "Duerme 20") (Thread/sleep 20000) (log/debug "Ya!")))
                ; :strauz 2017-08-07T11:56:31.396 INFO
                (cond
                  (>= index end)
                  (reduced last-date)

                  (< index ini)
                  last-date

                  :OTHERWIZE
                  (do
                    (when-let [p-line (re-find LOG-LINE-TEMPLATE line)]
                      (println ">> " (pr-str p-line)))
                    (if-let [[line hora milli] (re-find LOG-LINE-TEMPLATE line)]
                      (let [[last-date wait] (calc-wait last-date hora milli)]
                        (if with-wait (Thread/sleep (int (/ wait rate))))
                        (doto out (.write line) .newLine .flush)
                        last-date)
                      last-date))))]
        (reduce logger nil (map-indexed (fn [idx line] [idx line]) (line-seq in)))))))

(comment
 (defn run [rate ini end] ;1844
   (redo-log "logs/strauz-orchestrator.log.orig" "logs/strauz-orchestrator.log" :ini ini :end end :rate rate :with-wait true))
 )

(defn send->caudal [strm evt]
  (try
    (.println strm (pr-str evt))
    (catch Exception e
      (.printStackTrace e))))

;(require '[mx.interware.caudal.io.log-feed-simulator :as L])
(defn redo-log->caudal [host-name service in-file-name
                        & {:keys [ini end with-wait rate caudal-config]
                           :or   {ini           0
                                  end           9999999999
                                  with-wait     true
                                  rate          1
                                  caudal-config {:host "localhost" :port 9900}}}]
  (with-open [in  (io/reader in-file-name)
              skt (Socket. (:host caudal-config) (:port caudal-config))]
    (let [out      (PrintStream. (BufferedOutputStream. (.getOutputStream skt)))
          in-lines (->>
                     (line-seq in)
                     (keep-indexed
                       (fn [index line]
                         (if (<= ini index end) line))))]
      (letfn [(logger [last-date line]
                ;(Thread/sleep 1)
                (log/debug ">>>" line)
                (if-let [[line hora milli thread msg] (re-find LOG-LINE-TEMPLATE line)]
                  (let [[last-date wait] (calc-wait last-date hora milli)
                        msg-info (parse-cathel-msg msg)]
                    ;(log/debug :last-date last-date)
                    (if with-wait (Thread/sleep (/ wait rate)))
                    (send->caudal out (merge
                                        {:host        host-name
                                         :service     service
                                         :state       "ok"
                                         :description msg
                                         :metric      1                                                                 ; usamos esto para contar!
                                         :ts          (.getTime last-date)
                                         :thread      thread}
                                        msg-info))
                    last-date)
                  last-date))]
        (reduce logger nil in-lines)))))


(defn do-it []
  (redo-log
    ;"/Users/fgerard/monitoreo/invex/parte1/parte1/cathell_jboss_jpp_7/server.log.2016-11/server.log.2016-11-01"; server.log.2016-08-08" ; grande.log"
    "/Users/fgerard/monitoreo/invex/parte1/parte1/cathell_jboss_jpp_7/grande.log"
    "/Users/fgerard/monitoreo/arp/logs/cathel.txt"
    ;:ini 0
    ;:end 100000 ;403000
    :with-wait false
    :rate 240))

(defn do2 [n]
  (redo-log->caudal "invex" "tx5"
                    "/Users/fgerard/monitoreo/invex/parte1/parte1/cathell_jboss_jpp_7/grande.log"
                    :ini 0                                                                                              ;27700
                    :end (+ n 0)
                    :with-wait false
                    :rate 1))


(defn do-month []
  (doseq [d (range 5 31)]
    (let [f-name (format "/Users/fgerard/monitoreo/invex/parte1/parte1/cathell_jboss_jpp_7/server.log.2016-11/server.log.2016-11-%02d" d)]
      (log/debug (str "procesando: " f-name))
      (with-open [out (java.io.PrintWriter. (java.io.FileWriter. "broken.tx" true))]
        (.println out (str "DAY: " d)))
      (redo-log
        f-name
        "/Users/fgerard/monitoreo/arp/logs/cathel.txt"
        :with-wait false)
      (Thread/sleep (* 1000 60 5)))))
