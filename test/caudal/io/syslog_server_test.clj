(ns caudal.io.syslog-server-test
  (:use caudal.io.syslog-server
        clojure.test)
  (:require [clojure.tools.logging :as log])
  (:import
    (org.productivity.java.syslog4j.impl.message.structured
      StructuredSyslogMessage)))

(def inet-addr (java.net.InetAddress/getByName "127.0.0.1"))

;; Instances extracted from https://tools.ietf.org/html/rfc5424
;; Section 6.5.  Examples

;; Example 1 - with no STRUCTURED-DATA
(def data-non-structured-instance-0
  "<34>1 2003-10-11T22:14:15.003Z mymachine.example.com su - ID47 - BOM'su root' failed for lonvick on /dev/pts/8")

;; Example 2 - with no STRUCTURED-DATA
(def data-non-structured-instance-1
  "<165>1 2003-08-24T05:14:15.000003-07:00 192.0.2.1 myproc 8710 - - %% It's time to make the do-nuts.")

;; Example 3 - with STRUCTURED-DATA
(def data-structured-instance-0
  "<165>1 2003-10-11T22:14:15.003Z mymachine.example.com evntslog - ID47 [exampleSDID@32473 iut=\"3\" eventSource=\"Application\" eventID=\"1011\"] BOMAn application event log entry...")

;; Example 4 - STRUCTURED-DATA Only
(def data-structured-instance-1
  "<165>1 2003-10-11T22:14:15.003Z mymachine.example.com evntslog - ID47 [exampleSDID@32473 iut=\"3\" eventSource=\"Application\" eventID=\"1011\"][examplePriority@32473 class=\"high\"]")

(deftest valid-structured-data-0
  (is (structured-data? data-structured-instance-0)))

(deftest valid-structured-data-1
  (is (structured-data? data-structured-instance-1)))

(deftest valid-structured-data-2
  (is (structured-data? data-non-structured-instance-0)))

(deftest valid-structured-data-3
  (is (structured-data? data-non-structured-instance-1)))

(deftest with-structured-data
  (let [event (data->event data-structured-instance-0 inet-addr nil)]
    (is (= event
           {:facility 20
            :length 175
            :level 5
            :host "mymachine.example.com"
            :message "BOMAn application event log entry..."
            :timestamp 1065910455003
            :messageId "ID47"
            "exampleSDID@32473" {"eventID" "1011"
                                 "eventSource" "Application"
                                 "iut" "3"}}))))

(deftest with-structured-data-only
  (let [event (data->event data-structured-instance-1 inet-addr nil)]
    (is (= event
           {:facility 20 
            :length 174
            :level 5
            :host "mymachine.example.com"
            :message ""
            :timestamp 1065910455003
            :messageId "ID47"
            "examplePriority@32473" {"class" "high"}
            "exampleSDID@32473" {"eventID" "1011"
                                 "eventSource" "Application"
                                 "iut" "3"}}))))

(deftest non-structured-zulu
  (let [event (data->event data-non-structured-instance-0 inet-addr nil)]
    (is (= event
           {:facility 4
            :length 110
            :level 2
            :host "mymachine.example.com"
            :message "ID47 - BOM'su root' failed for lonvick on /dev/pts/8"
            :timestamp 1065910455003
            :messageId nil}))))

(deftest non-structured-with-timezone
  (let [event (data->event data-non-structured-instance-1 inet-addr nil)]
    (is (= event
           {:facility 20
            :length 99
            :level 5
            :host "192.0.2.1"
            :message "- - %% It's time to make the do-nuts."
            :timestamp 1061727255000
            :messageId nil}))))
