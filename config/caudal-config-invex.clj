(ns invex)

(require '[mx.interware.caudal.core.scheduler-server :refer :all])
(require '[mx.interware.caudal.core.folds :refer :all])
(require '[mx.interware.caudal.streams.common :refer :all])
(require '[mx.interware.caudal.streams.stateful :refer :all])
(require '[mx.interware.caudal.streams.stateless :refer :all])

(defsink invex-stream 100
  (time-stampit [:caudal/ts]
    ;(defstream [e]
    ;  (println e))
    (rate [:evt-volume :timestamp :volume 1 60]) ; podemos ver el volumen general cada 60 min de los ultimos 1 dias
    (where [:start]
      (counter [:d-start :cuenta]))
    (where [:delta]
      (counter [:d-end :cuenta]))
    (where [:tx]
      ;state-key init-pred end-pred thread-id tx-id ts-id timeout unstarted-key unended-key
      (uneven-tx [:broken-tx :start :delta :thread :tx :timestamp 600000 :unstarted :unended]
        (by [:tx]
          (where [:unstarted]
            (counter [:unstarted :broken-count]))
          (where [:unended]
            (counter [:unended :broken-count]))
          (to-file ["broken.tx" #{:thread :start :tx :timestamp :delta :unstarted :unended}])))
      (where [:delta]
        (rate [:volume :timestamp :volume 2 60]) ; podemos ver el volumen general de tx cada 60 min de los ultimos 1 dias
        ;(defstream [e] (println e))
        (by [:tx]
          (rate [:volumeXtx :timestamp :volume 1 60]) ;volimen por transaccion cada 60 min por 1 dias
          (smap [(fn [e]
                   (assoc e :delta (double (Integer/parseInt (:delta e)))))]
            (counter [:contador-tx])
            ;(batch [:tx-group 1000 60000]
            ;  ;(defstream [e] (println "VAN " (count e)))
            ;  (smap [(fn [e]
            ;           (folds/mean&std-dev :delta :mean :variance :stdev :n e))]
            ;    (acum-stats [:tx-performance :mean :variance :stdev :n]
            ;      (dump-every [:tx-performance "performance" "yyyyMM" [5 :minute] "./config/stats/"]))))
            (welford [:tx-perf-welford :delta :mean :variance :stdev :sum-of-squares :n
                      {:prefix "performance" :date-fmt "yyyyMM" :freq [5 :minute] :path "./config/stats2"}])
            ;(dump-every [:tx-perf-welford "performance" "yyyyMMdd" [1 :minute] "./config/stats2/"]))
            (decorate [:performace]
              (split
                [:mean]
                (anomaly-by-stdev [2 :delta :mean :stdev]
                  (->ERROR [:all]))

                (ewma-timeless [:ewma 0.1 :delta]
                  (decorate [:tx-perf-welford]
                    (anomaly-by-stdev [2 :delta :mean :stdev]
                      (with [:from :tx-perf-welford]
                        (->WARN [:all]))))))))))
      (concurrent-meter [:concurrent :start :delta (fn [_] :all-tx) :concurrent]
        ;(defstream [e] (println (str "***** " (pr-str e))))
        (decorate [:concurrent-max]
          (smap [(fn [{:keys [concurrent concurrent-max concurrent-max-ts tx timestamp] :or {concurrent-max 0} :as e}]
                   ;(println "======== " (pr-str e))
                   (if (> concurrent concurrent-max)
                     (do
                       (println "NUEVO MAXIMO:" concurrent)
                       {:concurrent-max concurrent
                        :concurrent-max-ts timestamp})
                     {:concurrent-max concurrent-max
                      :concurrent-max-ts concurrent-max-ts}))]
            (store! [:concurrent-max])))))))

(defsink streamer-1 10000
  (defstream [e]
    (println {:event-1 e})))

(deflistener tcp7777 [{:type 'mx.interware.caudal.io.tcp-server
                       :parameters {:port 7777
                                    :idle-period 60}}])

(deflistener rest9901 [{:type 'mx.interware.caudal.io.rest-server
                        :parameters {:host "localhost"
                                     :port 9901}}])

(defn dedup-factory []
  (let [s (atom nil)]
    (fn dedup [l]
      (if (not= l @s)
        (reset! s l)))))

(deflistener tailer-cathel
             [{:type 'mx.interware.caudal.io.tailer-server
               :parameters {:parser      'mx.interware.caudal.test.simple-parser/parse-cathel-line
                                          ;:inputs      ["./logs/input1.log" "./logs/input2.log"]
                            :inputs      {:directory  "./logs"
                                          :wildcard   "*txt"
                                          :expiration 5}
                            :delta       1000
                            :from-end    true
                            :reopen      true
                            :filter-fn  (dedup-factory);invex/dedup-factory
                            :buffer-size 16384}}])

(deflistener scheduler
             [{:type 'mx.interware.caudal.core.scheduler-server
               :jobs [{:runit? true
                       :cron-def "0 0 11 ? * MON-FRI"
                       ;:schedule-def {:at (.parse (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSS") "2017-01-09T18:42:00.000-06:00")}
                       ;{:at (+ (System/currentTimeMillis) 30000)  :every [5 :seconds] :limit 2} ;:in [10 :seconds]
                       :event-factory 'mx.interware.caudal.core.scheduler-server/state-admin-event-factory
                       :parameters {:cmd :load-history
                                    :path "config/stats-2016"
                                    ;:go-back-millis 86400000 ;ayer
                                    ;:date-fmt "yyyyMMdd"
                                    :key-name "performance"}}
                      {:runit? true
                       :cron-def "0 0/1 * ? * MON-FRI"
                       :event-factory 'mx.interware.caudal.core.scheduler-server/state-admin-event-factory
                       :parameters {:cmd :dump-state
                                    :versions 3
                                    :dump-file "config/stats/state.edn"}}]}])

(wire [tcp7777 rest9901 tailer-cathel scheduler] [invex-stream streamer-1])
