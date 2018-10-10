;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns mx.interware.caudal.test.simple-parser
  (:require [clojure.tools.logging :as log]))

(defn parse-log-line [line]
  (log/debug "line : " line)
  (read-string line))
;{:d-line line}

;43,860 caudal
;grep 44,194
;getCreditLmt

(defn parse-cathel-msg [message]
  (let [re #".*(EJECUTANDO) +getCreditLmt.*time .>> +[012345]$|.*(EJECUTANDO) +([a-zA-Z0-9]+).*time .>> +[01]$|.*(EJECUTANDO) +([a-zA-Z0-9]+).*time .>>  +([1-9][0-9]*).*|.*(FINALIZANDO) +([a-zA-Z0-9]+).*time .>> +([0-9]+).*|.*(EJECUTANDO) +([a-zA-Z0-9]+).*time .>>.*"
        [_  start-getCreditLmt start0 tx0 end1 tx1 delta1 end2 tx2 delta2 start1 tx3] (re-matches re message)]
    (cond
      start-getCreditLmt
      {:tx "getCreditLmt" :start true}

      start0
      {:tx tx0 :start true}

      start1
      {:tx tx3 :start true}

      end1
      (if (#{"getInqTermsDisclosure" "getCreditLmt" "getInqPDueOvrLmt" "getInqRetailTrans" "buscaMovEnDisputa"} tx1)
        {:tx tx1 :delta delta1}
        {:tx tx1 :start true})

      end2
      {:tx tx2 :delta delta2})))

(def contador (atom 0))
(def malos (atom 0))

;00:09:04,938 INFO  [stdout] (ajp-/0.0.0.0:8209-16) °°°°°°°°°°||------>FINALIZANDO getInqCustCardInfo:::>>>  time 2>>  217
(defn parse-cathel-line
  ([message]
   (parse-cathel-line parse-cathel-msg message))
  ([msg-parser message]
   (swap! contador (fn [n]
                     (let [n (inc n)]
                       (if (= 0 (mod n 10000))
                         (log/debug n))
                       n)))
   (let [re #"([0-9]{2}):([0-9]{2}):([0-9]{2}),([0-9]{1,3}) ([A-Z]+).*:[0-9]+-([0-9]+)\) (.*)"
         [_ HH mm ss SSS level thread msg :as all] (re-matches re message)]
     (if all
       (let [SSS      (if (< (count SSS) 3)
                        (str SSS "0")
                        SSS)
             SSS      (if (< (count SSS) 3)
                        (str SSS "0")
                        SSS)
             msg-info (if msg (msg-parser msg))
             cal      (doto
                        (java.util.Calendar/getInstance)
                        (.set java.util.Calendar/HOUR_OF_DAY (java.lang.Integer/parseInt HH))
                        (.set java.util.Calendar/MINUTE (java.lang.Integer/parseInt mm))
                        (.set java.util.Calendar/SECOND (java.lang.Integer/parseInt ss))
                        (.set java.util.Calendar/MILLISECOND (java.lang.Integer/parseInt SSS)))]
         ;(log/debug :line message)
         (merge
           {:thread    (str "thread-" thread)
            :level     level
            :message   msg
            :timestamp (.getTimeInMillis cal)}
           msg-info))
       (comment do
         (swap! malos inc)
         (log/debug "MALOS:" @malos))))))

;cat  logs/caudal.log | grep WARN | grep DATA | awk '{print($9 "  " $8)};' | sort -n | awk '{tx[$2]=$1;}; END {for (t in tx) print(tx[t] "\t\t" t);}' | sort -n
;awk -f find-max-seg.awk ../invex/parte1/parte1/cathell_jboss_jpp_7/grande.log | sort -n -d)
;grep "FINALIZANDO" /Users/fgerard/monitoreo/invex/parte1/parte1/cathell_jboss_jpp_7/grande.log | awk '{contador[$6]+=1}; END {for (x in contador) print(contador[x] "\t\t" x);}' |sort -n


(defn process-file
  "Process file reading it line-by-line"
  ([reducer init-val file]
   (with-open [rdr (clojure.java.io/reader file)]
     (reduce reducer
       init-val
       (line-seq rdr)))))

(defn count-start-end [[start end] line]
  (let [msg (parse-cathel-line parse-cathel-msg line)]
    (cond
      (:start msg)
      [(inc start) end]

      (:delta msg)
      [start (inc end)]

      :OTHERWISE
      [start end])))

;(require '[mx.interware.caudal.test.simple-parser :as S] :reload-all)

;(S/process-file S/count-start-end [0 0] (java.io.File. "../invex/parte1/parte1/cathell_jboss_jpp_7/grande.log"))

(defn count-start-endXtx [result line]
  (let [{:keys [start delta tx] :as m} (parse-cathel-line parse-cathel-msg line)]
    (let [[beg end] (or (result tx) [0 0])]
      (cond
        start
        (assoc result tx [(inc beg) end])

        delta
        (assoc result tx [beg (inc end)])

        :OTHERWISE
        result))))

;(S/process-file S/count-start-endXtx {} (java.io.File. "../invex/parte1/parte1/cathell_jboss_jpp_7/grande.log"))
