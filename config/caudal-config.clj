;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns caudal.config.robot
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

(comment
(require '[clara.rules :refer :all]
         '[clara.rules.accumulators :as acc]
         '[clara.tools.tracing :as tracing])

(defrecord Person [name age])
(defrecord Older [viejo joven])

(defrule clasifica
  [?person1 <- Person (= ?name1 name) (= ?age1 age)]
  [?person2 <- Person (= ?name2 name) (= ?age2 age)]
  [:test (> ?age1 ?age2)]
  =>
  (insert! (->Older ?person1 ?person2)))

(def contador (acc/count))

;(defquery get-cuenta [:?tipo]
;  [?cuenta <- Cuenta (== ?tipo tipo)])

(defquery get-relaciones []
  [?older <- Older])

(def amigos [(->Person "Gerardo" 46) (->Person "Felipe" 56) (->Person "Daniel" 32)])

;(def S (apply insert (mk-session) amigos))

(def S (reduce
        (fn [session person]
          (insert session person))
        (mk-session)
        amigos))

(def Q (fire-rules S))

(clojure.pprint/pprint (query Q get-relaciones))
)

;(def S (-> (apply insert (mk-session) (map #(->Entry %) (range 1 10)))
;           fire-rules))

;(def S ;(tracing/with-tracing (mk-session))
;  (mk-session))

;(time (def SS (-> (apply insert S (map #(->Entry %) (range 1 1000000)))
;                  fire-rules)))

;(tracing/get-trace SS)

;(query SS get-cuenta :?tipo "PARES")



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

(defn twitter->checker [twitter-event]
  (if (and (= "ongoing" (:userid twitter-event)))
    twitter-event
    (let [userid (get-in twitter-event [:user :screen_name])
          name (get-in twitter-event [:user :name] userid)
          created (:timestamp_ms twitter-event)
          image-url (get-in twitter-event [:user :profile_image_url])
          hash-tags (get-in twitter-event [:entities :hashtags])
          reporte? (some #(= "reporte" (.toLowerCase (:text %))) hash-tags)
          [_ _ year month day hour minute] (and reporte? (re-find #"(([0-9]{4})\-?([0-9]{2})\-?([0-9]{2})[Tt ]?([0-9]{2})\:?([0-9]{2}))" (:text twitter-event)))
          year (and year (Integer/parseInt year))
          month (and month (dec (Integer/parseInt month)))
          day (and day (Integer/parseInt day))
          hour (and hour (Integer/parseInt hour))
          minute (and minute (Integer/parseInt minute))
          tz (java.util.TimeZone/getTimeZone "America/Mexico_City")
          report-ts (and reporte? year month day minute (->
                                                         (doto
                                                          (java.util.Calendar/getInstance tz)
                                                          (.set year month day hour minute))
                                                         .getTimeInMillis))]
      {:userid (.toLowerCase userid)
       :name name
       :created (Long/parseLong created)
       :reporte? reporte?
       :report-ts report-ts
       :image-url image-url})))

(deflistener tcp-bbva [{:type 'mx.interware.caudal.io.tcp-server
                        :parameters {:port 8051
                                     :idle-period 60}}])

(deflistener tcp [{:type 'mx.interware.caudal.io.tcp-server
                   :parameters {:port 8052
                   :idle-period 60}}])

(deflistener tcp-prosa [{:type 'mx.interware.caudal.io.tcp-server
                         :parameters {:port 8053
                                      :idle-period 60}}])


(deflistener rest  [{:type 'mx.interware.caudal.io.rest-server
                     :parameters {:host "localhost"
                                  :http-port 8058
                                  :cors #".*"}}])

(deflistener twitter [{:type       'mx.interware.caudal.io.twitter
                       :parameters {:name            "Caudal_IW"
                                    :consumer-key    "ploTkxtq4acmSZenpBKUC5lSD"
                                    :consumer-secret "9McJ4fL2W6jgsNprHbTJbjhXEy9ohNILYPYxPjVm1KdIqRr88J"
                                    :token           "836271804419289088-TJOEZQHYrTyEIF8JA0M1ll79WsC8kUz"
                                    :token-secret    "V7GLhDkoyE51NsvXFom4FHLbYIAQ9iVwuWsBNuIJ5nCt7"
                                    :terms           ["#estoybien19s"]}}])

(deflistener cleaner
             [{:type 'mx.interware.caudal.core.scheduler-server
               :jobs [{:runit? true
                       :cron-def "0 0/10 * ? * *"
                       ;:schedule-def {:at (.parse (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSS") "2017-01-09T18:42:00.000-06:00")}
                       ;{:at (+ (System/currentTimeMillis) 30000)  :every [5 :seconds] :limit 2} ;:in [10 :seconds]
                       :event-factory 'mx.interware.caudal.core.scheduler-server/state-admin-event-factory
                       :parameters {:cmd :purge-state!
                                    :selector (fn [[_ {type :caudal/type}]]
                                                (and (= type "rate")))
                                    :reducer (partial purge-rate-buckets (* 1000 60 60 24 2))
                                    }}
                      {:runit? true
                       :cron-def "0 0 0/1 ? * *"
                       ;:schedule-def {:at (.parse (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSS") "2017-01-09T18:42:00.000-06:00")}
                       ;{:at (+ (System/currentTimeMillis) 30000)  :every [5 :seconds] :limit 2} ;:in [10 :seconds]
                       :event-factory 'mx.interware.caudal.core.scheduler-server/state-admin-event-factory
                       :parameters {:cmd :purge-state!
                                    :selector (fn [[state-k {type :caudal/type touched :caudal/created}]]
                                                (and (= type "counter")
                                                     (vector? state-k)
                                                     (or (#{:success-vs-error
                                                            :success-vs-error-strict
                                                            :success-vs-error-strict-day} (first state-k)))))
                                    :reducer (fn [[_ {touched :caudal/created :as counter-node}]]
                                               (and (> touched (- (System/currentTimeMillis) (* 1000 60 60 24 7))) counter-node)) ;60 24 2
                                    }}
                      {:runit? true
                       :cron-def "0 0 0/1 ? * *"
                       ;:schedule-def {:at (.parse (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSS") "2017-01-09T18:42:00.000-06:00")}
                       ;{:at (+ (System/currentTimeMillis) 30000)  :every [5 :seconds] :limit 2} ;:in [10 :seconds]
                       :event-factory 'mx.interware.caudal.core.scheduler-server/state-admin-event-factory
                       :parameters {:cmd :purge-state!
                                    :selector (fn [[state-k {type :caudal/type touched :caudal/created}]]
                                                (and (= type "welford")))
                                    :reducer (fn [[_ {touched :caudal/touched :as counter-node}]]
                                               (and (> touched (- (System/currentTimeMillis) (* 1000 60 60 24 7))) counter-node)) ;60 24 2
                                    }}
                      {:runit? true
                       :cron-def "0 0 0/1 ? * *"
                       ;:schedule-def {:at (.parse (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSS") "2017-01-09T18:42:00.000-06:00")}
                       ;{:at (+ (System/currentTimeMillis) 30000)  :every [5 :seconds] :limit 2} ;:in [10 :seconds]
                       :event-factory 'mx.interware.caudal.core.scheduler-server/state-admin-event-factory
                       :parameters {:cmd :purge-state!
                                    :selector (fn [[state-k {type :caudal/type}]]
                                                (and (= type "counter") (vector? state-k) (= (first state-k) :results)))
                                    :reducer (let [today (DU/format-date "dd" (java.util.Date.))]
                                               (fn [[_ {created :caudal/created :as counter-node}]]
                                                 (let [created-day (DU/format-date "dd" created)]
                                                   (and (= created-day today) counter-node))))
                                    }}
                      {:runit? true
                       :cron-def "0 0 0/1 ? * *"
                       :event-factory 'mx.interware.caudal.core.scheduler-server/state-admin-event-factory
                       :parameters {:cmd :dump-state
                                    :versions 3
                                    :dump-file "config/stats/state.edn"}}]}])

(deflistener robot-nurce
             [{:type 'mx.interware.caudal.core.scheduler-server
               :jobs [{:runit? true
                       :cron-def "0 0/1 * ? * *"
                       :event-factory 'mx.interware.caudal.core.scheduler-server/state-admin-event-factory
                       :parameters {:port 8050
                                    :host "localhost"
                                    :start-cmd [["bash" "-c" "ps -ef | grep java | grep robot | awk '{system(\"kill -9 \"  $2)}'" :dir "../iw-robot/"]
                                                ["bash" "-c" "rm -rf /tmp/.com.google.Chrome.*"]
                                                ["bash" "-c" "rm -rf /tmp/.org.chromium.Chromium.*"]
                                                ["bash" "-c" "rm -rf /tmp/iwrobot*.png"]
                                                ["bash" "-c" "rm -rf /tmp/libleveldbjni-*"]
                                                ["bash" "-c" "rm -rf /tmp/xvf-run.*"]
                                                ["bash" "-c" "bin/run-headless.sh 1>nohup2.out 2>&1 & \n echo $!"  :dir "../iw-robot/"]]}}]}])

(deflistener ongoing-problem
             [{:type 'mx.interware.caudal.core.scheduler-server
               :jobs [{:runit? true
                       :cron-def "0 0 */1 ? * *"
                       ;:schedule-def {:at (.parse (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSS") "2017-01-09T18:42:00.000-06:00")}
                       ;{:at (+ (System/currentTimeMillis) 30000)  :every [5 :seconds] :limit 2} ;:in [10 :seconds]
                       :event-factory 'mx.interware.caudal.core.scheduler-server/state-admin-event-factory
                       :parameters {;:cmd :send2streams!
                                    :userid "ongoing"
                                    :name "(SCHEDULED)"
                                    :created (System/currentTimeMillis)
                                    :reporte? true
                                    :report-ts -1
                                    }}
                      ]}])


(defn create-valid-path4png [{app :robot/app inst :robot/instance opr-txt :robot/opr-txt}]
  (->
   (str app "--" inst "--" opr-txt ".png")
   (S/replace #"([^a-zA-Z0-9_\-\.áéíóúÁÉÍÓÚñÑ]{1,1})" "_")))

(defn png-writer [{height :caudal/height :or {height 0} :as old-state} e]
  {:caudal/height (inc height)
   :caudal/png (:robot/selenium-err e)})

(defn png-serializer [e]
  (let [enc-png (:robot/selenium-err e)
        png-bytes (.decode (java.util.Base64/getDecoder) enc-png)
        png (str "screen-shots/" (create-valid-path4png e))
        png-path (str "resources/public/" png)]
    (io/make-parents png-path)
    (with-open [wrt (io/output-stream png-path)]
      (.write wrt png-bytes))
    png))

(def web-streams
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

(defpersistent-sink zurich 1 "sink-data"
  web-streams)

(defpersistent-sink prosa 1 "sink-data"
  web-streams)

(defn massage-bbva [event]
  (let [selenium-k (:robot/previous event)
        selenium-deltas (:robot/selenium-deltas event)
        tot-time (reduce (fn [tot [_ delta]] (+ tot delta)) 0 selenium-deltas)]
    (-> event
        (assoc-in [:robot/deltas selenium-k] tot-time)
        ;(assoc :robot/selenium-deltas selenium-deltas)
        )))

(defn append2log [{deltas :robot/selenium-deltas instance :robot/instance}]
  (with-open [out (io/writer (str "sink-data/" instance "-info.log") :append true)]
    (let [sdf (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss")
          line (pr-str (assoc deltas :ts (.format sdf (System/currentTimeMillis))))]
      (.write out line 0 (count line))
      (.newLine out))))

(def web-streams-bancomer
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
                            :ymd (DU/format-date "yyyy-MM-dd" now)
                            :bucket (* (int (/ now 900000)) 900000)}
                           selenium-err (assoc :robot/selenium-err selenium-err))))]
              (by [[:robot/app :robot/instance :ymdH :robot/opr-txt]]
                  (split
                   [#(= "strict" (:robot/instance %))]
                   (counter [:success-vs-error-strict :count])

                   (counter [:success-vs-error :count])))
              (by [[:robot/app :robot/instance :ymd :robot/opr-txt]]
                  (split
                   [#(= "strict" (:robot/instance %))]
                   (counter [:success-vs-error-strict-day :count])

                   (counter [:success-vs-error-day :count])))
              (where [#(= (:robot/opr-txt %) "ok")]
                     (by [[:robot/app :robot/instance :ymdH]]
                         (by [[:robot/opr-txt]]
                             (welford [:hourly-stats :robot/opr-delta :mean :variance :stdev :sum-of-squares :count]))
                         (smap [(fn [{selenium-deltas :robot/selenium-deltas instance :robot/instance}]
                                  (into [] (map (fn [[tag delta]]
                                                  {:tag tag :delta delta :robot/instance instance})
                                                selenium-deltas)))]
                               (unfold
                                (by [[:tag]]
                                    (split
                                     [#(= "strict" (:robot/instance %))]
                                     (welford [:hourly-flow-stats-strict :delta :mean :variance :stdev :sum-of-squares :count])

                                     (welford [:hourly-flow-stats :delta :mean :variance :stdev :sum-of-squares :count]))))))
                     (by [[:robot/app :robot/instance :ymd]]
                         (smap [(fn [{selenium-deltas :robot/selenium-deltas instance :robot/instance}]
                                  (into [] (map (fn [[tag delta]]
                                                  {:tag tag :delta delta :robot/instance instance})
                                                selenium-deltas)))]
                               (unfold
                                (by [[:tag]]
                                    (split
                                     [#(= "strict" (:robot/instance %))]
                                     (welford [:daily-flow-stats-strict :delta :mean :variance :stdev :sum-of-squares :count])

                                     (welford [:daily-flow-stats :delta :mean :variance :stdev :sum-of-squares :count]))))))
                     )
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

(defpersistent-sink bancomer 1 "sink-data"
  (smap [massage-bbva]
        (smap [append2log])
        web-streams-bancomer))

(defsink debug 1
  (pprinte))

(comment defsink desastres 10
  (smap
   [twitter->checker]
   (time-stampit
    [:caudal/received]
    (pprinte
     (checker
      [:desastre "./checker/checker.edn"]
      (smap
       [(fn [e]
          (println "\n\nEstatus de la lista al momento")
          (:not-present e))]
       (pprinte)
       (where
        [#(seq %)]
        (mailer [{:host "smtp.gmail.com"
                  :user "caudal.interware@gmail.com"
                  :pass "9kYas4adF9Qs"
                  :ssl true
                  }
                 {:subject  "Informe de desastres naturales"
                  :to       ["fgerard@interware.com.mx"]
                  :from     "interware.caudal@gmail.com"}]
                ))))))))

(defn socket-not-responding [host port]
  (try
    (with-open [socket (java.net.Socket. host port)])
    nil
  (catch Exception e
    (str (.getName (class e)) " -> "(.getMessage e)))))

(defn start-robot [{:keys [start-cmd] :as e}]
  (log/info "start-robot: " (pr-str start-cmd))
  (let [result (->> start-cmd
                    (map (fn [cmd]
                           (log/info "executing bash: " (pr-str cmd))
                           (apply sh/sh cmd)))
                    (doall)
                    (last))]
    (log/info "start-robot resut: " result)
    (merge e result)))

(defpersistent-sink robot-watcher 1 "sink-data"
  (time-stampit
   [:timestamp]
   (smap
    [(fn [{:keys [host port] :or {host "localhost" port 4050} :as e}]
       (if-let [problem (socket-not-responding host port)]
         (assoc e :robot/notfound-1 problem)
         e))]
    ;(pprinte)
    (moving-time-window
     [:robot-mtw (* 1000 60 3) :timestamp]
     (smap
      [(fn [events]
         (let [[e :as events] (reverse events)]
           (merge e
                  {:recent-errors (count (take-while :robot/notfound-1 events))
                   :service-at (str (:host e) ":" (:port e))})))]
      (by [:service-at]
          (counter
           [:recent-robot-errors :count (fn [n e]
                                          (:recent-errors e))]))
      (where
       [(fn [e]
          ;(println "e ->> " (pr-str e))
          (>= (:recent-errors e) 2))]
       (where
        [(fn [{:keys [host port] :or {host "localhost" port 4050} :as e}]
           (socket-not-responding host port))]
        (pprinte)
        (smap
         [start-robot]
         (rate [:robot-restarts :timestamp :propagation-bucket 60 (* 24 7)])))))))))

(config-view [prosa zurich bancomer] {:doughnut {:success-vs-error {:value-fn :n :tooltip [:n]}
                                                 :success-vs-error-strict {:value-fn :n :tooltip [:n]}
                                                 :success-vs-error-strict-day {:value-fn :n :tooltip [:n]}

                                                 :hourly-stats {:value-fn :mean ;mean porque es un weldord asi definido
                                                                :tooltip [:mean :stdev :count]}
                                                 :hourly-flow-stats {:value-fn :mean
                                                                     :tooltip [:mean :stdev :count]
                                                                     }
                                                 :hourly-flow-stats-strict {:value-fn :mean
                                                                            :tooltip [:mean :stdev :count]
                                                                            }
                                                 :daily-flow-stats-strict {:value-fn :mean
                                                                           :tooltip [:mean :stdev :count]
                                                                           }
                                                 :daily-flow-stats {:value-fn :mean
                                                                    :tooltip [:mean :stdev :count]
                                                                    }
                                                 :last-hr-errors {:value-fn :n :tooltip [:n]}
                                                 }
                                      :bar {:hourly-flow-stats {:value-fn :mean
                                                                :tooltip [:mean :stdev :count]}
                                            :hourly-flow-stats-strict {:value-fn :mean
                                                                       :tooltip [:mean :stdev :count]}}
                                      :timeline {:success-stats {:value-fn :caudal/height
                                                                 :tooltip []}
                                                 :errores-with-png {:value-fn :caudal/height
                                                                    :tooltip []}
                                                 :errores {:value-fn :caudal/height
                                                           :tooltip []}}})

(config-view [robot-watcher] {:doughnut {:recent-robot-errors {:value-fn :n :tooltip [:n]}
                                         }
                              :timeline {:robot-restarts {:value-fn :caudal/height
                                                          :tooltip []}}})




(wire [tcp rest cleaner] [zurich])

(wire [tcp-prosa cleaner] [prosa])

(wire [tcp-bbva cleaner] [bancomer])

(wire [robot-nurce] [robot-watcher])

;(wire [twitter ongoing-problem] [desastres])

;(wire [ongoing-problem] [debug])
;(println "Initializing secure web @ 8054")
(web
 {:https-port 8054
  :server-key "resources/security/innovacion-mx.key"
  ;:server-key-pass
  :server-crt "resources/security/innovacion-mx.crt"
  :publish-sinks [zurich ]}) ;desastres

(web
 {:https-port 8055
  :server-key "resources/security/innovacion-mx.key"
  ;:server-key-pass
  :server-crt "resources/security/innovacion-mx.crt"
  :publish-sinks [prosa ]}) ;desastres

(web
 {:https-port 8056
  :server-key "resources/security/innovacion-mx.key"
  ;:server-key-pass
  :server-crt "resources/security/innovacion-mx.crt"
  :publish-sinks [robot-watcher bancomer]})
