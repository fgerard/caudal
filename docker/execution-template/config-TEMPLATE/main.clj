(ns main
  (:require
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]
   [caudal.streams.common :refer [defsink deflistener wire]]
   [caudal.io.rest-server :refer [web]]
   [caudal.streams.stateful :refer [reduce-with
                                    changed
                                    counter
                                    moving-time-window
                                    priority-buff
                                    rollup
                                    batch
                                    with-histerisis
                                    tx-mgr]]
   [caudal.streams.stateless :refer [by pprinte split smap time-stampit ->INFO ->WARN ->ERROR reinject]]
   [caudal.io.telegram :refer [send-photo send-text]]
   ;[caudal.io.email :refer [mailer email-event-with-body-fn]]
   [cheshire.core :refer [parse-string]])
  (:import
   (java.util Random UUID)
   (java.util Base64)
   (java.net InetAddress)))

(defn read-event [e]
  (println (pr-str [:evt e]))
  (if-let [json (:json e)]
    (parse-string json true)
    (if-let [evt (:body-params e)]
      evt
      (when-let [body (:body e)]
        (let [body-str (slurp (io/reader body))
              content-type (get-in e [:headers "content-type"] "")]
          (if (re-find #"edn" content-type)
            (edn/read-string body-str)
            (parse-string body-str true)))))))

(defsink test-sink 100
  (smap
   [read-event]
   (time-stampit
    [:caudal/ts]
    (counter
     [:total :folio]
     (split
      
      [(fn [e] (and (= (:opr e) "telegram") (:token e) (:chat-id e)))]
      (send-text
       [:token :chat-id :msg]
       (->WARN [:all]))
      
      (->INFO [:all]))))))

(deflistener tcp7777 [{:type 'caudal.io.tcp-server
                       :parameters {:port 7777
                                    :idle-period 60}}])

(deflistener rest-listener [{:type       'caudal.io.rest-server
                             :parameters {:host "0.0.0.0"
                                          :http-port   8099
                                          :idle-period 300}}])

(wire [tcp7777 rest-listener] [test-sink ])

(web
 {:http-port 8080
  :host "0.0.0.0"
  :cors #"http://localhost:3449"
  :publish-sinks [test-sink]})
