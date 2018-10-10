(ns mx.interware.caudal.streams.stateful-test
  (:require [clojure.test :refer [deftest is with-test]]
            [clojure.pprint :refer [pprint]]
            [mx.interware.caudal.streams.common :refer [create-sink caudal-state-as-map defstream using-path]]
            [mx.interware.caudal.streams.stateful :refer [batch changed counter dump-every ewma-timeless matcher mixer moving-time-window rate reduce-with welford]]
            [mx.interware.caudal.streams.stateless :refer [by decorate default join ->INFO percentiles store! remove! smap]]
            [mx.interware.caudal.core.state :as state]
            [mx.interware.caudal.core.atom-state :as atom-state]
            [mx.interware.caudal.util.caudal-util :as util :refer [create-caudal-agent printp]]
            [mx.interware.caudal.util.test-util :refer [clean-event print-header]]))

(org.apache.log4j.BasicConfigurator/configure)

(deftest mixer-test-1
  (let [test-result  (atom nil)
        now          (System/currentTimeMillis)
        test-data    [{:log-type "S" :mode 0 :ts now :id 1 :tx "transaccionPrueba"}
                      {:log-type "O" :mode 0 :ts now :id 2 :tx "transaccionPrueba"}
                      {:log-type "M" :mode 0 :ts now :id 3 :tx "transaccionPrueba"}
                      {:log-type "T" :mode 0 :ts now :id 4 :tx "transaccionPrueba"}
                      {:log-type "T" :mode 1 :ts now :id 5 :tx "transaccionPrueba"}
                      {:log-type "O" :mode 1 :ts now :id 6 :tx "transaccionPrueba"}
                      {:log-type "S" :mode 1 :ts now :id 7 :tx "transaccionPrueba"}]
        priority-map {["S" 0] 1
                      ["O" 0] 2
                      ["M" 0] 3
                      ["T" 0] 4
                      ["T" 1] 5
                      ["O" 1] 6
                      ["S" 1] 7}
        priority-key (juxt :log-type :mode)
        priority-fn  (fn [e]
                       (if-let [priority (priority-map (priority-key e))]
                         priority
                         (throw (Exception. (str "INVALID LOG-TYPE AND MODE COMBINATION:" (priority-key e))))))
        children-fn  (defstream [events]
                                (reset! test-result (into [] (map #(dissoc % :caudal/latency) events)))
                                (locking java.lang.Object
                                  (.notify java.lang.Object)))
        streams      (by [:tx]
                         (mixer [:mixer-mem 400 :ts priority-fn] children-fn))
        state        (agent {})
        sink         (create-sink state streams)]
    (doseq [event (shuffle test-data)]
      (if (vector? event)
        (doseq [e event]
          (sink e))
        (do
          (println :enviando event)
          (sink event))))
    (locking java.lang.Object
      (.wait java.lang.Object 420))
    (is (= @test-result test-data))))

(deftest mixer-test-2
  (let [test-result  (atom nil)
        now          (System/currentTimeMillis)
        test-data    [{:log-type "S" :mode 0 :ts now :id 1 :tx "transaccionPrueba"}
                      {:log-type "O" :mode 0 :ts now :id 2 :tx "transaccionPrueba"}
                      {:log-type "M" :mode 0 :ts now :id 3 :tx "transaccionPrueba"}
                      {:log-type "T" :mode 0 :ts now :id 4 :tx "transaccionPrueba"}
                      {:log-type "T" :mode 0 :ts now :id 5 :tx "transaccionPrueba"}
                      {:log-type "T" :mode 1 :ts now :id 6 :tx "transaccionPrueba"}
                      {:log-type "O" :mode 1 :ts now :id 7 :tx "transaccionPrueba"}
                      {:log-type "S" :mode 1 :ts now :id 8 :tx "transaccionPrueba"}]
        random-data  [{:log-type "S" :mode 1 :ts now :id 8 :tx "transaccionPrueba"}
                      {:log-type "T" :mode 1 :ts now :id 6 :tx "transaccionPrueba"}
                      {:log-type "O" :mode 0 :ts now :id 2 :tx "transaccionPrueba"}
                      {:log-type "T" :mode 0 :ts now :id 4 :tx "transaccionPrueba"}
                      {:log-type "S" :mode 0 :ts now :id 1 :tx "transaccionPrueba"}
                      {:log-type "M" :mode 0 :ts now :id 3 :tx "transaccionPrueba"}
                      {:log-type "T" :mode 0 :ts now :id 5 :tx "transaccionPrueba"}
                      {:log-type "O" :mode 1 :ts now :id 7 :tx "transaccionPrueba"}]


        priority-map {["S" 0] 1
                      ["O" 0] 2
                      ["M" 0] 3
                      ["T" 0] 4
                      ["T" 1] 5
                      ["O" 1] 6
                      ["S" 1] 7}
        priority-key (juxt :log-type :mode)
        priority-fn  (fn [e]
                       (if-let [priority (priority-map (priority-key e))]
                         priority
                         (throw (Exception. (str "INVALID LOG-TYPE AND MODE COMBINATION:" (priority-key e))))))
        children-fn  (defstream [state events]
                                (reset! test-result (into [] (map #(dissoc % :caudal/latency) events)))
                                (clojure.pprint/pprint state)
                                (println "************** state1 *************")
                                (doseq [quatro (get-in state [[:mixer-mem] :buffer])]
                                  (println quatro))
                                (println "************** state2 *************")
                                (doseq [e events]
                                  (println e))
                                (println (pr-str @test-result))
                                (println (pr-str test-data))
                                (println (= @test-result test-data))
                                (locking java.lang.Object
                                  (.notify java.lang.Object)))

        streams      (mixer [:mixer-mem 500 :ts priority-fn] children-fn)
        state        (agent {})
        sink         (create-sink state streams)]
    (doseq [event random-data]
      (if (vector? event)
        (doseq [e event]
          (sink e))
        (do
          (println :enviando event)
          (sink event))))
    (locking java.lang.Object
      (.wait java.lang.Object 650))
    (Thread/sleep 1500)
    (println "************** state2 *************")
    (doseq [quatro (get-in [[:mixer-mem] :buffer] @state)]
      (println quatro))
    (is (= @test-result test-data))))

(deftest by-counter-1
  (let [expected {[:counter0]            {:n 26}
                  [:counter1 "tx1"]      {:n 11},
                  [:counter1 "tx2"]      {:n 15},
                  [:counter2 "tx2" "h1"] {:n 7},
                  [:counter3 "h1"]       {:n 12},
                  [:counter2 "tx2" "h2"] {:n 8},
                  [:counter3 "h2"]       {:n 14},
                  [:counter2 "tx1" "h2"] {:n 6},
                  [:counter2 "tx1" "h1"] {:n 5}}
        events   (into [] (shuffle
                            (flatten
                              (conj
                                (repeat 5 [{:tx "tx1" :host "h1"}])
                                (repeat 6 [{:tx "tx1" :host "h2"}])
                                (repeat 7 [{:tx "tx2" :host "h1"}])
                                (repeat 8 [{:tx "tx2" :host "h2"}])))))
        streams  (default [:ttl -1]
                          (counter [:counter0 :metric])
                          (by [:tx]
                              (counter [:counter1 :metric]
                                       (by [:host]
                                           (counter [:counter2 :metric]))))
                          (by [:host]
                              (counter [:counter3 :metric])))

        state    (agent {})
        sink     (create-sink state streams)]
    (doseq [event events]
      (sink event))
    (println :to-sleep)
    (Thread/sleep 3000)
    (println :back)
    (println "@state : " (dissoc @state :caudal/agent))
    (println "as-map:" (caudal-state-as-map state))
    (let [result (into {} (map (fn [[k v]] [k (clean-event v)]) (caudal-state-as-map state)))]
      (clojure.pprint/pprint result)
      (clojure.pprint/pprint expected)
      (println (= result expected))
      (is (= result expected)))))

(comment deftest using-paths-1
  (let [expected {"/default/by(tx2)/counter"                 {:n 15, :ttl -1},
                  "/default/by(tx2)/counter/by(h1)/counter"  {:n 7, :ttl -1},
                  "/default/by(h1)/counter"                  {:n 12, :ttl -1},
                  "/default/by(tx1)/counter"                 {:n 11, :ttl -1},
                  "/default/by(tx1)/counter/by(h2)/counter"  {:n 6, :ttl -1},
                  "/default/by(h2)/counter"                  {:n 14, :ttl -1},
                  "/default/by(tx1)/counter/by(h1)/counter"  {:n 5, :ttl -1},
                  "/default/by(tx2)/counter/by(h2)/counter"  {:n 8, :ttl -1},
                  "/default/by(tx2)/counter/by/join/counter" {:n 15, :ttl -1}
                  "/default/by(tx1)/counter/by/join/counter" {:n 11, :ttl -1}}
        events   (into [] (shuffle
                            (flatten
                              (conj
                                (repeat 5 [{:tx "tx1" :host "h1"}])
                                (repeat 6 [{:tx "tx1" :host "h2"}])
                                (repeat 7 [{:tx "tx2" :host "h1"}])
                                (repeat 8 [{:tx "tx2" :host "h2"}])))))
        streams  (using-path
                   [mx.interware.caudal.streams.stateless]
                   mx.interware.caudal.streams.stateful
                   (default [:ttl -1]
                            (by [:tx]
                                (counter [:n]
                                         (by [:host]
                                             (counter [:n])
                                             (join []
                                               (counter [:n])))))

                            (by [:host]
                                (counter [:n]))))
        state    (agent {})
        sink     (create-sink state streams)]
    (doseq [event events]
      (sink event))
    (Thread/sleep 1000)
    (let [result (into {} (map (fn [[k v]] [k (dissoc v :touched)]) (caudal-state-as-map state)))]
      (println "RESULT:")
      (clojure.pprint/pprint result)
      (println "EXPECTED:")
      (clojure.pprint/pprint expected)
      (println "ARE EQUAL?:" (= result expected))
      (is (= result expected)))))

(deftest history-load-1
  (let [result   (atom nil)
        date-fmt "yyyyMM_ww"
        one-week (* 3600 24 7 1000)
        key-name   "history"
        file-name   (str
                      (.format (java.text.SimpleDateFormat. date-fmt) (- (System/currentTimeMillis) one-week))
                      "-" key-name "-getInqCustCardInfo.edn")
        dir "config/stats-test/"
        full-file-name (str dir file-name)
        test-data (slurp "config/stats-test/201611_46-history-getInqCustCardInfo.edn")
        _         (spit full-file-name test-data)
        events   [{:caudal/cmd :load-history
                   :path       "config/stats-test"
                   :go-back-millis one-week ;go back 1 week
                   :date-fmt   date-fmt
                   :key-name   "history"}
                  {:tx "getInqCustCardInfo" :cosa "buena"}]
        expected (merge
                   (second events)
                   (dissoc
                     (read-string test-data)
                     :touched))
        streams  (default [:ttl -1]
                          (defstream [e] (prn e))
                          (decorate [(fn [e] [:history (:tx e)])]
                                    (defstream [state e]
                                      (pprint state)
                                      (println "despues de decorate:" e))
                                    (fn cosita [by-path state e]
                                      (reset! result e)
                                      (locking result
                                        (println "******* notify ****")
                                        (.notify result))
                                      state)))
        state    (agent {})
        sink     (create-sink state streams)]
    (locking result
      (doseq [event events]
        (sink event))
      (.wait result 8000))
    (println "*** SALIO:" @result)
    (.delete (java.io.File. full-file-name))
    (is (= (dissoc @result :touched :caudal/latency)
           expected))))

(deftest percentiles-1
  (let [cuantos      1000
        rango-random 1000
        percents     [0.25 0.5 0.75 0.95]
        metrics      (vec (for [x (range 0 cuantos)] (rand-int rango-random)))
        expected     (let [sorted (sort metrics)
                           idx-fn (fn [percent]
                                    (min (dec cuantos) (Math/floor (* percent cuantos))))]
                       (into {} (map (fn [p]
                                       [p (nth sorted (idx-fn p))]) percents)))

        events       (into [] (shuffle
                                (map-indexed (fn [idx rnd]
                                               {:tx "tx1" :host "h1" :delta rnd :idx idx}) metrics)))
        streams      (default [:ttl -1]
                              (by [:tx]
                                  (counter [:counter1 :metric]
                                           (->INFO [:all]))
                                  (batch [:batch-1 10000 2000]
                                         (percentiles [:delta :percentiles percents]
                                                      (defstream [e]
                                                                 (println :los-percentiles (pr-str e)))
                                                      (store! [:store (fn [e] "percentiles")])))))
        state        (agent {})
        sink         (create-sink state streams)]
    (doseq [event events]
      (sink event))
    (println :to-sleep)
    (Thread/sleep 5000)
    (println :back)
    (println "as-map2:" (filter (fn [[k v]]
                                  (= [:store "tx1" "percentiles"] k))
                                (caudal-state-as-map state)))
    (println :la-cosa (keys @state))
    (let [result (-> @state (get [:store "tx1" "percentiles"]) :percentiles)]
      (clojure.pprint/pprint result)
      (clojure.pprint/pprint expected)
      (println (= result expected))
      (is (= result expected)))))

(deftest decorate-1
  (let [initial-state {:avg 1000 :stdev 30 :n 20}
        agt           (create-caudal-agent
                        :initial-map {[:stats "getCustInfo"] initial-state})
        test-event    {:tx "getCustInfo"}
        expected      (merge test-event initial-state)
        result        (atom nil)
        streams       (by [:tx]
                          (decorate [:stats]
                                    (defstream [e]
                                               (reset! result (dissoc e :caudal/latency)))))
        sink          (create-sink agt streams)]
    (sink test-event)
    (Thread/sleep 300)
    (println @result)
    (is (= @result expected))))



(deftest ewma-timeless-test
  (let [initial-state {:avg 1000 :stdev 30 :n 20}
        agt           (create-caudal-agent)
        events        [{:tx "tx-1" :metric 100} {:tx "tx-1" :metric 200}]
        expected      {:tx "tx-1" :metric 150.0 :ttl -1}
        result        (atom nil)
        streams       (default [:ttl -1]
                               (by [:tx]
                                   (ewma-timeless
                                     [:ewma 0.5 :metric]
                                     (defstream
                                       [e]
                                       (reset! result (dissoc e :caudal/latency))))))
        sink          (create-sink agt streams)]
    (doseq [e events]
      (sink e))
    (Thread/sleep 1000)
    (is (= @result expected))))


(def reduce-with-test
  (defn reduce-with-test-fn [sleep-period initial-state expected-result test-data]
    (print-header)
    (printp "Test data       : " test-data)
    (let [caudal-agent (create-caudal-agent
                         :initial-map {[:stats "getCustInfo"] initial-state})
          result       (atom nil)
          streams      (default [:ttl -1]
                                (reduce-with
                                  [:metric
                                   (fn [metric-key evt] (assoc evt :avg (inc (:avg evt))))]
                                  (defstream
                                    [e]
                                    (reset! result (clean-event e)))))

          sink         (create-sink caudal-agent streams)
          test-fn      (fn
                         [e]
                         (sink e)
                         (Thread/sleep sleep-period)
                         @result)]
      (printp "Expected result : " expected-result)
      (printp "Actual result   : " (test-fn test-data))
      (= (test-fn test-data) expected-result))))

(with-test reduce-with-test
           (let [sleep-period      500
                 initial-state     {:avg 1000 :stdev 30 :n 20}
                 test-data-1       {:n 11, :metric 100 :avg 0}
                 expected-result-1 {:n 11, :metric 100, :avg 1}
                 test-data-2       {:n 11, :metric 100 :avg 10}
                 expected-result-2 {:n 11, :metric 100, :avg 11}]
             (is (reduce-with-test sleep-period initial-state expected-result-1 test-data-1))
             (is (reduce-with-test sleep-period initial-state expected-result-2 test-data-2))))


(deftest moving-time-window-test
  (let [agt          (create-caudal-agent)
        result       (atom [])
        streams      (by [:tx]
                         (moving-time-window
                           [:mtw 1000 :time]
                           (defstream
                             [e]
                             (println "e:" e)
                             (swap! result conj e))))
        sink         (create-sink agt streams)
        surtidor     (fn [n]
                       (doseq [n (range 0 n)]
                         (sink {:tx "tx-1" :time (System/currentTimeMillis)})
                         (Thread/sleep 99)))
        test-fn      (fn
                       [n]
                       (surtidor n)
                       (Thread/sleep 500)
                       (println "result moving-time-window-test")
                       (reduce (fn [result val]
                                 (and result val))
                         (map (fn [window]
                                (< (- (:time (last window))
                                      (:time (first window)) 1000)))
                           @result)))]

    (is (= (test-fn 15) true))))

(deftest moving-time-window-test2
  (let [agt          (create-caudal-agent)
        result       (atom [])
        streams      (by [:tx]
                         (moving-time-window
                          [:mtw 1000 :time]
                          (defstream
                            [e]
                            (println "e:" e)
                            (swap! result conj e))))
        sink         (create-sink agt streams)
        surtidor     (fn [n]
                       (doseq [n (range 0 n)]
                         (println "******** " n " ****** " (caudal-state-as-map agt))
                         (sink {:tx "tx-1" :time (System/currentTimeMillis)})
                         (Thread/sleep 99))
                       (println "******** antes: " (caudal-state-as-map agt))
                       (sink {:tx "tx-1" :time (- (System/currentTimeMillis) 10000)})
                       (Thread/sleep 300)
                       (println "******** despues: " (caudal-state-as-map agt)))
        test-fn      (fn
                       [n]
                       (surtidor n)
                       (Thread/sleep 500)
                       (println "result moving-time-window-test")
                       (reduce (fn [result val]
                                 (and result val))
                               (map (fn [window]
                                      (< (- (:time (last window))
                                            (:time (first window)) 1000)))
                                    @result)))]
    (println "******** " (caudal-state-as-map agt))
    (test-fn 15)
    (is (= {} (caudal-state-as-map agt)))))

(deftest changed-test
  (let [_                 (print-header)
        caudal-agent      (create-caudal-agent)
        result            (atom nil)
        predicate         :metric
        state-key         :changed
        streams           (by [:tx]
                              (changed [state-key predicate]
                                       (defstream
                                         [event]
                                         (reset! result event)
                                         (locking result
                                           (.notify result)))))
        sink              (create-sink caudal-agent streams)
        sleep-period      500
        test-data-1       {:n 11 :ttl -1 :tx "tx-1" :metric 100 :avg 1}
        test-data-2       {:n 12 :ttl 10 :tx "tx-1" :metric 200 :avg 2}
        expected-result-1 []
        expected-result-2 [{:n 11, :ttl -1, :tx "tx-1", :metric 100, :avg 1} {:n 12, :ttl 10, :tx "tx-1", :metric 200, :avg 2}]
        test-fn           (fn [event]
                            (sink event)
                            (Thread/sleep sleep-period)
                            (vec (map #(dissoc % :caudal/latency) @result)))
        result-1          (test-fn test-data-1)
        result-2          (test-fn test-data-2)]
    (printp "Test data 1       : " test-data-1)
    (printp "Expected result 1 : " expected-result-1)
    (printp "Actual result 1   : " result-1)
    (printp "Test data 2       : " test-data-2)
    (printp "Expected result 2 : " expected-result-2)
    (printp "Actual result 2   : " result-2)
    (is (= result-1 expected-result-1))
    (is (= result-2 expected-result-2))))


(deftest batch-test
  (let [agt          (create-caudal-agent)
        result       (atom nil)
        state-key    :mtw
        max-elements 2
        millis-key   :time
        pred         :metric
        state-key    :batch-test
        n            3
        dt           20000
        streams      (by [:tx]
                         (batch [state-key n dt]
                                (defstream
                                  [e]
                                  (reset! result e))))
        sink         (create-sink agt streams)
        test-fn      (fn
                       [e]
                       (sink e)
                       (Thread/sleep 500)
                       (vec (map #(dissoc % :caudal/latency) @result)))]
    (is (= (test-fn {:n 11 :ttl -1 :tx "tx-1" :metric 100 :avg 1}) []))
    (is (= (test-fn {:n 11 :ttl -1 :tx "tx-1" :metric 100 :avg 1}) []))
    (is (= (test-fn {:n 12 :ttl 10 :tx "tx-1" :metric 200 :avg 2}) [{:n 11, :ttl -1, :tx "tx-1", :metric 100, :avg 1} {:n 11, :ttl -1, :tx "tx-1", :metric 100, :avg 1} {:n 12, :ttl 10, :tx "tx-1", :metric 200, :avg 2}]))))

;-------------------
(deftest matcher-test
  (let [agt           (create-caudal-agent)
        result        (atom {})
        state-key     :txmatcher
        start-pred    #"EJECUTANDO.*"
        end-pred      #"FINALIZANDO.*"
        tx-key        :tx
        ts-key        :diff
        timeout-delta 1000
        msg-key       :msg
        streams       (smap [(fn [e]
                               (assoc e :TS (:timestamp e)))]
                            (matcher [state-key
                                      #(and (tx-key %) (re-matches start-pred (msg-key %)))
                                      #(and (tx-key %) (re-matches end-pred (msg-key %)))
                                      tx-key
                                      timeout-delta
                                      ts-key]
                                     (defstream [event]
                                                (reset! result event))))
        sink          (create-sink agt streams)
        test-fn       (fn
                        [e]
                        (sink e)
                        (Thread/sleep 500)
                        @result)]
    (is (= (test-fn {:tx "transaction-1" :msg "EJECUTANDO >>1" :ttl 1000 :metric 100}) {}))
    (is (= (test-fn {:tx "transaction-1" :msg "FINALIZANDO >> 706" :ttl 1000 :metric 100}) {}))))




(deftest dump-every-test
  (let [agt         (create-caudal-agent)
        result      (atom nil)
        state-key   :stats
        prefix      "test-dump"
        date-format "'test'"
        cycle       [1 :second]
        dir-path    "."
        streams     (dump-every
                      [state-key
                       prefix
                       date-format
                       cycle
                       dir-path])
        sink        (create-sink agt streams)
        test-fn     (fn
                      [e]
                      (sink e)
                      (Thread/sleep 500)
                      {:exists  (.exists (clojure.java.io/as-file (str "./" (.format (java.text.SimpleDateFormat. date-format) (new java.util.Date)) "-" prefix ".edn")))
                       :content (slurp (str "./" (.format (java.text.SimpleDateFormat. date-format) (new java.util.Date)) "-" prefix ".edn"))})]

    (is (= true
           (:exists (test-fn {:tx "transaction-1" :avg 100 :variance 0.5 :stdev 1 :counter 1 :ttl 1000 :metric 100}))))
    (is (not= ""
              (:content (test-fn {:tx "transaction-1" :avg 100 :variance 0.5 :stdev 1 :counter 1 :ttl 1000 :metric 100}))))))


(deftest rate-test
  (let [initial-state {:avg 1000 :stdev 30 :n 20}
        agt           (create-caudal-agent)
        result        (atom {})
        state-key     :stats
        ts-key        :ts
        rate-key      :rate
        days          1
        streams       (rate [state-key ts-key rate-key days 60]
                            (defstream [e]
                                       (reset! result e)))
        sink          (create-sink agt streams)
        test-fn       (fn
                        [e]
                        (sink e)
                        (Thread/sleep 5000)
                        @result)]

    (is (= {} (test-fn {:tx "transaction-1" :avg 100 :variance 0.5 :stdev 1 :counter 1 :ttl 1000 :metric 100})))))

(def welford-test-1
  (defn welford-test-fn [sleep-period expected-result test-data]
    (print-header)
    (printp "Test data       : " test-data)
    (let [result   (atom [])
          agent    (create-caudal-agent)
          streamer (by [:host]
                     (welford [::statistics :metric :avg :variance :stdev :s-of-s :n]
                              (defstream [event] (swap! result conj (clean-event event)))))
          sink     (create-sink agent streamer)]
      (doseq [e test-data]
        (sink e))
      (Thread/sleep sleep-period)
      (printp "Expected result : " expected-result)
      (printp "Actual result   : " @result)
      (= expected-result @result))))

(with-test welford-test-1
  (let [sleep     400
        expected [{:host "example.com" :app "webservice-1" :metric 13 :avg 13 :s-of-s 0.0 :variance 0.0 :stdev 0.0 :n 1}
                  {:host "example.com" :app "webservice-1" :metric 23 :avg 18.0 :s-of-s 50.0 :variance 25.0 :stdev 5.0 :n 2}
                  {:host "example.com" :app "webservice-1" :metric 12 :avg 16.0 :s-of-s 74.0 :variance 24.666666666666668 :stdev 4.96655480858378 :n 3}
                  {:host "example.com" :app "webservice-1" :metric 44 :avg 23.0 :s-of-s 662.0 :variance 165.5 :stdev 12.864680330268607 :n 4}
                  {:host "example.com" :app "webservice-1" :metric 55 :avg 29.4 :s-of-s 1481.2 :variance 296.24 :stdev 17.21162397916013 :n 5}]
        data     [{:host "example.com" :app "webservice-1" :metric 13}
                  {:host "example.com" :app "webservice-1" :metric 23}
                  {:host "example.com" :app "webservice-1" :metric 12}
                  {:host "example.com" :app "webservice-1" :metric 44}
                  {:host "example.com" :app "webservice-1" :metric 55}]]
    (is (welford-test-1 sleep expected data))))
