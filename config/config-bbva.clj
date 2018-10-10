;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns caudal.config.robot.bbva
  (:require
   [clojure.java.io :as io]
   [clojure.string :as S]
   [clojure.tools.logging :as log]
   [clojure.java.shell :as sh]
   [mx.interware.caudal.streams.common :refer :all]
   [mx.interware.caudal.io.rest-server :refer :all]
   [mx.interware.caudal.streams.stateful :refer :all]
   [mx.interware.caudal.streams.stateless :refer :all]
   [mx.interware.caudal.io.email :refer [mailer]]
   [mx.interware.caudal.core.folds :refer [rate-bucket-welford]]
   [mx.interware.caudal.util.date-util :as DU]
   [clara.rules :refer :all]
   [clara.rules.accumulators :as acc]
   [clara.tools.tracing :as tracing]))

(defn massage [event]
  (let [deltas (:robot/deltas event)
        opr (:robot/previous event)
        opr-delta (opr deltas)
        value (-> event
                  opr
                  (clojure.string/split  #"\n")
                  first)]
    (-> event
        (dissoc opr)
        (assoc :robot/opr opr :robot/opr-delta opr-delta :robot/opr-txt value))))

(deflistener csv-reader
  [{:type 'mx.interware.caudal.io.tailer-server
    :parameters {:parser      'mx.interware.caudal.test.bbva-parser/parse-bbva-line
                               ;:inputs      ["./logs/input1.log" "./logs/input2.log"]
                 :inputs      {:directory  "./bbva"
                               :wildcard   "*csv"
                               :expiration 5}
                 :delta       1000
                 :from-end    false
                 :reopen      true
                 ;:filter-fn  (dedup-factory)
                 :buffer-size 16384}}])


(comment def web-streams
  (smap [massage]
        (smap [(fn [{app :robot/app
                     instance :robot/instance
                     opr-txt :robot/opr-txt
                     opr-delta :robot/opr-delta
                     selenium-deltas :robot/selenium-deltas
                     selenium-err :robot/selenium-err
                     :as e}]
                 (let [selenium-err (and selenium-err (png-serializer e))
                       now (System/currentTimeMillis)]
                   (cond-> {:robot/app app
                            :robot/instance instance
                            :robot/opr-txt opr-txt
                            :robot/opr-delta opr-delta
                            :robot/selenium-deltas selenium-deltas
                            :timestamp now
                            :ymdH (DU/format-date "yyyy-MM-dd_HH" now)
                            :bucket (* (int (/ now 900000)) 900000)}
                           selenium-err (assoc :robot/selenium-err selenium-err))))]
              (by [[:robot/app :robot/instance :ymdH :robot/opr-txt]]
                  (counter [:success-vs-error :count]))
              (where [#(= (:robot/opr-txt %) "ok")]
                     (by [[:robot/app :robot/instance :ymdH]]
                         (by [[:robot/opr-txt]]
                             (welford [:hourly-stats :robot/opr-delta :mean :variance :stdev :sum-of-squares :count]))
                         (smap [(fn [{selenium-deltas :robot/selenium-deltas}]
                                  (into [] (map (fn [[tag delta]]
                                                  {:tag tag :delta delta})
                                                selenium-deltas)))]
                               (unfold
                                (by [[:tag]]
                                    (welford [:hourly-flow-stats :delta :mean :variance :stdev :sum-of-squares :count]))))
                         ;(classified-welford [:hourly-flow-stats :robot/selenium-deltas :mean :variance :stdev :sum-of-squares :count])
                         ))
              (by [[:robot/app :robot/instance]]
                  (moving-time-window [:mtw (* 1000 60 60) :timestamp]
                                      (smap [(fn [events]
                                               (->> events
                                                    (map :robot/opr-txt)
                                                    (remove #(= "ok" %))
                                                    (frequencies)
                                                    (mapv (fn [[txt n]]
                                                            {:err-txt txt :count n}))))]
                                            (remove-childs [:last-hr-errors]
                                                           (unfold
                                                            (by [:err-txt]
                                                                (counter [:last-hr-errors :count (fn [n e]
                                                                                                   (:count e))])))))))
              (by [[:robot/app :robot/instance :robot/opr-txt]]
                  (counter [:results :count])
                  (split
                   [#(= (:robot/opr-txt %) "ok")]
                   (sdo
                    (welford [:daily-stats :robot/opr-delta :mean :variance :stdev :sum-of-squares :count])
                    (rate [:success-stats :timestamp :propagation-bucket 5 576
                           (rate-bucket-welford :robot/opr-delta :mean :variance :stdev :sum-of-squares :count)]))

                   [#(:robot/selenium-err %)]
                   (rate [:errores-with-png :timestamp :propagation-bucket 5 576 png-writer])

                   (rate [:errores :timestamp :propagation-bucket 5 576 (fn [{height :caudal/height :or {height 0}} e]
                                                                          {:caudal/height (inc height)})]))))))

(defn parse-msg [{:keys [msg]}]
  (let [[ts www acceso tarjeta pass token] (S/split msg #",")]
    ;(println "matches: " (pr-str [ts]) (re-matches #"[0-9]{2} [A-Za-z]{3} [0-9]{4} [0-9]{2}:[0-9]{2}" ts))
    (when (re-matches #"[0-9]{2} [A-Za-z]{3} [0-9]{4} [0-9]{2}:[0-9]{2}" ts)
      (try
        (let [sdf-in (java.text.SimpleDateFormat. "dd MMM yyyy HH:mm")
              sdf-out (java.text.SimpleDateFormat. "yyyy-MM-dd_HH_mm")
              ts (.parse sdf-in ts)
              ts-hhmm (.format sdf-out ts)
              ts-hh (subs ts-hhmm 0 13)
              www (Double/parseDouble www)
              acceso (Double/parseDouble acceso)
              tarjeta (Double/parseDouble tarjeta)
              pass (Double/parseDouble pass)
              token (Double/parseDouble token)]
          [{:type "www" :value www :ts ts :tsH ts-hh :tsHM ts-hhmm}
           {:type "acceso" :value acceso :ts ts :tsH ts-hh :tsHM ts-hhmm}
           {:type "tarjeta" :value tarjeta :ts ts :tsH ts-hh :tsHM ts-hhmm}
           {:type "pass" :value pass :ts ts :tsH ts-hh :tsHM ts-hhmm}
           {:type "token" :value token :ts ts :tsH ts-hh :tsHM ts-hhmm}])
      (catch Exception e
        ;(.printStackTrace e)
        (println "**************" (.getMessage e))
        )))))

(defpersistent-sink bbva 1 "sink-data"
  (smap [parse-msg]
        (unfold
         (by [[:tsH :type]]
             (welford [:stats-by-HH :value :mean :variance :stdev :sum-of-squares :count]))
         (by [[:tsHM :type]]
             (welford [:stats-by-HHMM :value :mean :variance :stdev :sum-of-squares :count]))
         (by [[:type]]
             (welford [:stats :value :mean :variance :stdev :sum-of-squares :count])))))

(config-view [bbva] {:doughnut {:stats {:value-fn :mean :tooltip [:mean :stdev]}
                                :stats-by-HH {:value-fn :mean :tooltip [:mean :stdev]}
                                ;:stats-by-HHMM {:value-fn :mean :tooltip [:mean :stdev]}
                                }
                     :bar {;:stats {:value-fn :mean :tooltip [:mean :stdev]}
                           :stats-by-HH {:value-fn :mean :tooltip [:mean :stdev]}
                           :stats-by-HHMM {:value-fn :mean :tooltip [:mean :stdev]}}})


(wire [csv-reader] [bbva])

(web
 {:https-port 8060
  :server-key "security/innovacion-mx.key"
  ;:server-key-pass
  :server-crt "security/innovacion-mx.crt"
  :publish-sinks [bbva]}) ;desastres

(comment

 (require '[clojure.java.io :as io])

 (defn edn->csv [f-name]
   (with-open [in (io/reader (str "bbva/" f-name ".log"))
               out (io/writer (str "bbva/" f-name ".csv"))]
     (let [lines (line-seq in)]
       (doseq [e lines]
         (let [{:keys [ts a-init b-www-load c-acceso d-tarjeta e-contrasena f-token]} (read-string e)]
           (println (str "\"" ts "\",") a-init "," b-www-load  "," c-acceso "," d-tarjeta "," e-contrasena "," f-token)
           (when (and ts a-init b-www-load c-acceso d-tarjeta e-contrasena f-token)
             (binding [*out* out]
               (println (str "\"" ts "\",") a-init "," b-www-load  "," c-acceso "," d-tarjeta "," e-contrasena "," f-token))))))))

 (edn->csv "strict-info")
 )
