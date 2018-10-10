;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns caudal.zurich.cfdi
  (:require
   [clojure.java.io :as io]
   [clojure.string :as S]
   [clojure.tools.logging :as log]
   [mx.interware.caudal.streams.common :refer :all]
   [mx.interware.caudal.io.rest-server :refer :all]
   [mx.interware.caudal.streams.stateful :refer :all]
   [mx.interware.caudal.streams.stateless :refer :all]
   [mx.interware.caudal.io.email :refer [mailer]]
   [mx.interware.caudal.core.folds :refer [rate-bucket-welford]]
   [mx.interware.caudal.util.date-util :as DU]
   [clara.rules :refer :all]
   [clara.rules.accumulators :as acc]
   [clara.tools.tracing :as tracing])
  (:import
   (java.text SimpleDateFormat)
   (java.util Calendar)))

(defn dp-parser [line]
  (let [msg-fn (fn [message]
                 (reduce (fn [r [_ key value]]
                           (let [key   (keyword key)
                                 value (condp = key
                                         :Size (Integer/parseInt value)
                                         :service-delay (Integer/parseInt value)
                                         :elapsed (Integer/parseInt value)
                                         value)]
                             (assoc r key value)))
                         {}
                         (re-seq #"([A-Za-z0-9-_]+)=\"(\w+)\"" message)))
        regex #"([a-zA-Z]{3}\s+[0-9]{1,2}\s\d\d:\d\d:\d\d) ([a-zA-Z0-9-_]+) (\[[a-f0-9x]+\])(\[[a-zA-Z0-9-_]+\])\[([a-z]+)\] ([a-zA-Z0-9-_]+\([a-zA-Z0-9-_]+\)): trans\(([0-9]+)\)\[([a-z]+)\] gtid\(([0-9]+)\): \(([a-z0-9-_]+)\) (.*)"
        [_ date _ _ _ log-type _ id comm _ phase message] (re-matches regex line)]
    (merge {:timestamp (.getTimeInMillis
                        (doto (Calendar/getInstance)
                          (.setTime (-> (SimpleDateFormat. "MMM dd HH:mm:ss")
                                        (.parse date)))
                          (.set (Calendar/YEAR) (.get (Calendar/getInstance) (Calendar/YEAR)))))
            :log-type log-type
            :id id
            :comm comm
            :phase phase} (msg-fn message))))

(comment deflistener tcp [{:type 'mx.interware.caudal.io.tcp-server
                   :parameters {:port 8052
                   :idle-period 60}}])

(deflistener dp-log [{:type 'mx.interware.caudal.io.tailer-server
                      :parameters {:parser     dp-parser
                                   :inputs      {:directory  "./logs-zurich/"
                                                 :wildcard   "dp-develop.log"
                                                 :expiration 5}
                                   :delta       300
                                   :from-end    false
                                   :reopen      true
                                   :buffer-size 16384}}])

(def phases #{"recover" "error" "inicio" "insert-db" "fin" "sign"})
{:stats {}
 :tx {"3915737349" {:e {:RFCRecep "   " :etc ""}
                    :phases {"inicio" 1510157076749
                             "sign" 1510157076751}
                    :created 1510157076749
                    :touched 1510157076751}
      "3915737350" {:phases {"inicio" 1510157076753
                             "sign" 1510157076756
                             "recover" 1510157076757
                             "insert-db" 1510157076759}
                         :created 1510157076753
                         :touched 1510157076759}}
 :alarm nil
 :propagatable nil}

(def DOUBLE-INICIO "Transaccion con id %s con estado 'inicio' ya registrada @ %s")
(def PHASE-WITHOUT-INICIO "Transaccion con id %s para phase %s  sin estado 'inicio' @ %s")

(defmulti phase-watcher (fn [_ e]
                          (:phase e)) :default :default)

(defmethod phase-watcher :default [state e]
  (println "INVALID EVENT: " (pr-str e))
  state)

(defmethod phase-watcher "inicio" [{:keys [stats tx] :or {stats {} tx {}} :as phase-state}
                                   {:keys [id phase RFCEmisor RFCRecep Size Serie Folio ErrorCode MedioPago Tipo timestamp ts] :as e}]
  (let [tx-exists (get tx id)]
    (cond-> (assoc-in phase-state [:tx id] {:e e
                                            :phases {"inicio"  timestamp}
                                            :created timestamp
                                            :touched timestamp})
            tx-exists (assoc :alarm (format DOUBLE-INICIO id timestamp))
            true (dissoc :propagatable))))

(defmethod phase-watcher "sign" [{:keys [stats tx] :or {stats {} tx {}} :as phase-state}
                                 {:keys [id phase error error-code service-delay timestamp ts] :as e}]
  (if-let [tx-exists (get tx id)]
    (-> (update-in phase-state [:tx id] (fn [data]
                                          (cond-> data
                                                  service-delay (assoc-in [:e :sign-delay] service-delay)
                                                  error-code (assoc-in [:e :sing-error-code] error-code)
                                                  true (assoc-in [:phases "sign"] timestamp)
                                                  true (assoc :touched timestamp))))
        (dissoc :alarm :propagatable))
    (assoc phase-state :alarm (format PHASE-WITHOUT-INICIO id phase timestamp))))

(defmethod phase-watcher "recover" [{:keys [stats tx] :or {stats {} tx {}} :as phase-state}
                                    {:keys [id phase error error-code service-delay timestamp ts] :as e}]
  (if-let [tx-exists (get tx id)]
    (-> (update-in phase-state [:tx id] (fn [data]
                                          (cond-> data
                                                  service-delay (assoc-in [:e :recover-delay] service-delay)
                                                  error-code (assoc-in [:e :recover-error-code] error-code)
                                                  error (assoc-in [:e :recover-error] error)
                                                  true (assoc-in [:phases "recover"] timestamp)
                                                  true (assoc :touched timestamp))))
        (dissoc :alarm :propagatable))
    (assoc phase-state :alarm (format PHASE-WITHOUT-INICIO id phase timestamp))))

(defmethod phase-watcher "insert-db" [{:keys [stats tx] :or {stats {} tx {}} :as phase-state}
                                      {:keys [id phase error description service-delay timestamp ts] :as e}]
  (if-let [tx-exists (get tx id)]
    (-> (update-in phase-state [:tx id] (fn [data]
                                          (cond-> data
                                                  service-delay (assoc-in [:e :db-delay] service-delay)
                                                  description (assoc-in [:e :db-desc] description)
                                                  error (assoc-in [:e :db-error] error)
                                                  true (assoc-in [:phases "insert-db"] timestamp)
                                                  true (assoc :touched timestamp))))
        (dissoc :alarm :propagatable))
    (assoc phase-state :alarm (format PHASE-WITHOUT-INICIO id phase timestamp))))

(defmethod phase-watcher "error" [{:keys [stats tx] :or {stats {} tx {}} :as phase-state}
                                  {:keys [id phase error-code timestamp ts] :as e}]
  (if-let [tx-exists (get tx id)]
    (let [errf2 (fn error2 [state]
                  (-> state
                      (assoc :propagatable (assoc (get-in state [:tx id :e]) :phases (get-in state [:tx id :phases])))))]
      (cond-> (update-in phase-state [:tx id] (fn error1 [data]
                                                (cond-> data
                                                        error-code (assoc-in [:e :error-code] error-code)
                                                        true (assoc-in [:phases "error"] timestamp)
                                                        true (assoc :touched timestamp))))
              error-code (errf2)
              error-code (update :tx dissoc id)
              true (dissoc :alarm)))
    (assoc phase-state :alarm (format PHASE-WITHOUT-INICIO id phase timestamp))))

(defmethod phase-watcher "fin" [{:keys [stats tx] :or {stats {} tx {}} :as phase-state}
                                {:keys [id phase elapsed timestamp ts] :as e}]
  (if-let [tx-exists (get tx id)]
    (let [{:keys [e phases]} tx-exists]
      (-> phase-state
          (update :tx dissoc id)
          (assoc :propagatable (assoc e :phases (assoc phases "fin" timestamp)))
          (dissoc :alarm)))
    (assoc phase-state :alarm (format PHASE-WITHOUT-INICIO id phase timestamp))))


(defpersistent-sink zurich 1 "sink-data"
  (where [(comp not :tailer/error)]
         (time-stampit [:ts]
                       (smap [(fn [e]
                                (let [bucket-size (* 1000 60 60)]
                                  (-> e
                                      (assoc :bucket (* (int (/ (System/currentTimeMillis) bucket-size)) bucket-size)))))]
                             (reduce-with [:dp-general-reduction phase-watcher]
                                          (where [:propagatable]
                                                 (smap [:propagatable]
                                                       (printe [""])
                                                       (by [:RFCEmisor]
                                                           (counter [:xnegocio :count])
                                                           (smap [(fn [e]
                                                                    (if-let [error-code (:error-code e)]
                                                                      (assoc e :code error-code)
                                                                      (assoc e :code "ok")))]
                                                                 (by [:code]
                                                                     (counter [:errorVSok :count])))))))))))

(config-view [zurich] {:doughnut {:xnegocio {:value-fn :n :tooltip [:n]}
                                :errorVSok {:value-fn :n :tooltip [:n]}}})

(wire [dp-log] [zurich]) ;tcp

(web
 {:https-port 8056
  :server-key "security/innovacion-mx.key"
  :server-crt "security/innovacion-mx.crt"
  :publish-sinks [zurich]})
