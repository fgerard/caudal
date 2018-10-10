(ns mx.interware.caudal.streams.stateless-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is with-test]]
            [mx.interware.caudal.core.atom-state :as atom-state]
            [mx.interware.caudal.core.state :as state]
            [mx.interware.caudal.streams.common :as common :refer [caudal-state-as-map create-sink defstream]]
            [mx.interware.caudal.streams.stateful :refer [batch counter]]
            [mx.interware.caudal.streams.stateless :refer [anomaly-by-stdev anomaly-by-percentile by decorate default forward join printe percentiles store! remove! smap split time-stampit to-file where with]]
            [mx.interware.caudal.util.caudal-util :refer [create-caudal-agent printp]]
            [mx.interware.caudal.util.date-util :as date-util]
            [mx.interware.caudal.util.test-util :refer [clean-event print-header]])
  (:import (org.apache.commons.io FileUtils)))

(def default-test-1
  (defn default-test-fn [test-data session-id expected-result sleep-period]
    (print-header)
    (printp "Test data       : " test-data)
    (let [result       (atom nil)
          caudal-agent (create-caudal-agent)
          streams      (default [:session-id session-id]
                                (defstream [event]
                                           (reset! result (clean-event event))))
          sink         (create-sink caudal-agent streams)]
      (sink test-data)
      (Thread/sleep sleep-period)
      (printp "Expected result : " expected-result)
      (printp "Actual result   : " @result)
      (= expected-result @result))))

(with-test default-test-1
           (let [sleep-period    300
                 session-id      "ABC-123"
                 test-data       {:host "node1.example.com" :service "http" :metric 11}
                 expected-result {:host "node1.example.com" :service "http" :metric 11 :session-id "ABC-123"}]
             (is (default-test-1 test-data session-id expected-result sleep-period))))


(def with-test-1
  (defn with-test-fn [sleep-period expected-result test-data]
    (print-header)
    (printp "Test data       : " test-data)
    (let [result       (atom [])
          caudal-agent (create-caudal-agent)
          streams      (with [:service "http"]
                             (defstream [event]
                                        (swap! result conj (clean-event event))))
          sink         (create-sink caudal-agent streams)]
      (doseq [e test-data]
        (sink e))
      (Thread/sleep sleep-period)
      (printp "Expected result : " expected-result)
      (printp "Actual result   : " @result)
      (= expected-result @result))))

(with-test with-test-1
           (let [sleep-period    300
                 expected-result [{:host "node1.example.com" :service "http" :n 1}
                                  {:host "node2.example.com" :service "http" :n 2}
                                  {:host "node2.example.com" :service "http" :n 3}
                                  {:host "node1.example.com" :service "http" :n 4}
                                  {:host "node1.example.com" :service "http" :n 5}]
                 test-data       [{:host "node1.example.com" :service "http-a" :n 1}
                                  {:host "node2.example.com" :service "http-b" :n 2}
                                  {:host "node2.example.com" :service "http-c" :n 3}
                                  {:host "node1.example.com" :service "http-a" :n 4}
                                  {:host "node1.example.com" :service "http-b" :n 5}]]
             (is (with-test-1 sleep-period expected-result test-data))))


(def time-stampit-test-1
  (defn time-stampit-test-fn [sleep-period ts-key test-data]
    (print-header)
    (printp "Test data       : " test-data)
    (let [result       (atom nil)
          ts-fn        (fn [e] (and (not (nil? (:ts e)))
                                    (>= (date-util/current-millis) (:ts e))))
          caudal-agent (create-caudal-agent)
          streams      (time-stampit [ts-key]
                                     (defstream [event]
                                                (reset! result (clean-event event))))
          sink         (create-sink caudal-agent streams)]
      (sink test-data)
      (Thread/sleep sleep-period)
      (printp "Eval ts-fn      : " (ts-fn @result))
      (printp "Result          : " @result)
      (ts-fn @result))))

(with-test time-stampit-test-1
           (let [sleep-period 300
                 ts-key       :ts
                 event        {:host "node1.example.com" :service "http" :n 1}]
             (is (time-stampit-test-1 sleep-period ts-key event))))


(def store-test-1
  (defn store-test-fn [sleep-period initial-state test-data expected-result]
    (print-header)
    (printp "Test data       : " test-data)
    (let [key-id       [:stored-event]
          caudal-agent (create-caudal-agent :initial-map {key-id initial-state})
          result       (atom {})
          streams      (store! [key-id nil])
          sink         (create-sink caudal-agent streams)]
      (sink test-data)
      (Thread/sleep sleep-period)
      (reset! result (clean-event (get (caudal-state-as-map caudal-agent) key-id)))
      (printp "Expected result : " expected-result)
      (printp "Actual result   : " @result)
      (= @result expected-result))))

(with-test store-test-1
           (let [sleep-period    3000
                 initial-state   {:a 1 :b "dos" :c 3.0 :service "KKPTT9aKdTrs2mg8jlOJvk6"}
                 test-data       {:host "localhost" :service "http"}
                 expected-result {:host "localhost" :service "http"}]
             (is (store-test-1 sleep-period initial-state test-data expected-result))))


(def remove!-test-1
  (defn remove-test-fn [sleep-period initial-state test-data expected-result]
    (print-header)
    (printp "Test data       : " test-data)
    (let [key-id       :stored-event
          caudal-agent (create-caudal-agent :initial-map {key-id initial-state})
          result       (atom {})
          streams      (remove! [(fn [event] key-id)])
          sink         (create-sink caudal-agent streams)]
      (sink test-data)
      (reset! result (clean-event (get (caudal-state-as-map caudal-agent) key-id)))
      (printp "After sink      : " @result)
      (Thread/sleep sleep-period)
      (reset! result (clean-event (get (caudal-state-as-map caudal-agent) key-id)))
      (printp "Expected result : " expected-result)
      (printp "Actual result   : " @result)
      (= @result expected-result))))

(with-test remove!-test-1
           (let [sleep-period    300
                 initial-state   {:a 1 :b "dos" :c 3.0 :service "KKPTT9aKdTrs2mg8jlOJvk6"}
                 test-data       {:host "localhost" :service "doesn't matter"}
                 expected-result nil]
             (is (remove!-test-1 sleep-period initial-state test-data expected-result))))


(def where-test-1
  (defn where-test-fn [sleep-period host expected-result test-data]
    (print-header)
    (printp "Test data       : " test-data)
    (let [result       (atom [])
          caudal-agent (create-caudal-agent)
          where-fn     (fn [e] (= (:host e) host))
          streams      (where [where-fn]
                              (defstream [event]
                                         (swap! result conj (clean-event event))))
          sink         (common/create-sink caudal-agent streams)]
      (doseq [event test-data]
        (sink event))
      (Thread/sleep sleep-period)
      (printp "Expected result : " expected-result)
      (printp "Actual result   : " @result)
      (= expected-result @result))))

(with-test where-test-1
           (let [sleep-period    300
                 host            "node2.example.com"
                 expected-result [{:host "node2.example.com" :service "http-b" :n 2}
                                  {:host "node2.example.com" :service "http-b" :n 3}]
                 test-data       [{:host "node1.example.com" :service "http-a" :n 1}
                                  {:host "node2.example.com" :service "http-b" :n 2}
                                  {:host "node2.example.com" :service "http-b" :n 3}
                                  {:host "node1.example.com" :service "http-a" :n 4}
                                  {:host "node1.example.com" :service "http-a" :n 5}]]
             (is (where-test-1 sleep-period host expected-result test-data))))


(def smap-test-1
  (defn smap-test-fn [sleep-period expected-result test-data app-name]
    (print-header)
    (printp "Test data       : " test-data)
    (let [result       (atom [])
          transform-fn (fn [e] (merge e {:app app-name}))
          caudal-agent (create-caudal-agent)
          streams      (smap [transform-fn]
                             (defstream [event]
                                        (swap! result conj (clean-event event))))
          sink         (create-sink caudal-agent streams)]
      (doseq [e test-data]
        (sink e))
      (Thread/sleep sleep-period)
      (printp "Expected result : " expected-result)
      (printp "Actual result   : " @result)
      (= expected-result @result))))

(with-test smap-test-1
           (let [sleep-period    300
                 app-name        "sales"
                 expected-result [{:host "node1.example.com" :service "http-a" :n 1 :app "sales"}
                                  {:host "node2.example.com" :service "http-b" :n 2 :app "sales"}
                                  {:host "node2.example.com" :service "http-b" :n 3 :app "sales"}
                                  {:host "node1.example.com" :service "http-a" :n 4 :app "sales"}
                                  {:host "node1.example.com" :service "http-a" :n 5 :app "sales"}]
                 test-data       [{:host "node1.example.com" :service "http-a" :n 1}
                                  {:host "node2.example.com" :service "http-b" :n 2}
                                  {:host "node2.example.com" :service "http-b" :n 3}
                                  {:host "node1.example.com" :service "http-a" :n 4}
                                  {:host "node1.example.com" :service "http-a" :n 5}]]
             (is (smap-test-1 sleep-period expected-result test-data app-name))))

(def split-test-1
  (defn split-test-fn [sleep-period expected-result test-data]
    (print-header)
    (printp "Test data       : " test-data)
    (let [result-1     (atom [])
          result-2     (atom [])
          caudal-agent (create-caudal-agent)
          streams      (split [#(= "node1.example.com" (:host %))]
                              (defstream [event]
                                (swap! result-1 conj (dissoc event :caudal/latency)))
                              (defstream [event]
                                (swap! result-2 conj (dissoc event :caudal/latency))))
          sink         (create-sink caudal-agent streams)]
      (doseq [event test-data]
        (sink event))
      (Thread/sleep sleep-period)
      (printp "Expected result : " expected-result)
      (printp "Actual result 1 : " @result-1)
      (printp "Actual result 2 : " @result-2)
      (= expected-result (into [] (concat @result-1 @result-2))))))

(with-test split-test-1
           (let [sleep-period    300
                 expected-result [{:host "node1.example.com" :service "http" :n 1}
                                  {:host "node1.example.com" :service "http" :n 4}
                                  {:host "node1.example.com" :service "http" :n 5}
                                  {:host "node2.example.com" :service "http" :n 2}
                                  {:host "node2.example.com" :service "http" :n 3}]
                 test-data       [{:host "node1.example.com" :service "http" :n 1}
                                  {:host "node2.example.com" :service "http" :n 2}
                                  {:host "node2.example.com" :service "http" :n 3}
                                  {:host "node1.example.com" :service "http" :n 4}
                                  {:host "node1.example.com" :service "http" :n 5}]]
             (is (split-test-1 sleep-period expected-result test-data))))


(comment def to-file-test-1
         (defn to-file-test-fn [sleep-period expected-result test-data file-path keywords]
           (print-header)
           (printp "Test data       : " test-data)
           (FileUtils/deleteQuietly (io/file file-path))
           (let [result       (atom [])
                 caudal-agent (create-caudal-agent)
                 _            (printp "Keywords       : " keywords)
                 streams      (default [:x 1]
                                       (to-file file-path keywords
                                                (defstream [event]
                                                           (reset! result (clean-event event)))))
                 sink         (create-sink caudal-agent streams)]
             (doseq [event test-data]
               (sink event))
             (Thread/sleep sleep-period)
             (let [file-data (read-string (FileUtils/readFileToString (io/file file-path)))]
               (printp "Expected result : " expected-result)
               (printp "Actual result   : " @result)
               (printp "File data       : " file-data)
               (and (= test-data @result) (= file-data expected-result))))))

(comment with-test to-file-test-1
         (let [sleep-period    300
               file-path       "/tmp/caudal-test.edn"
               keywords        :all                                                                                     ;[:service :count :ts :ammount]
               expected-result {:service "http" :count 4 :ts #inst"2016-12-20T19:23:27.068-00:00" :ammount 3.1416}
               test-data       {:host "node1.example.com" :service "http" :count 4 :ts #inst"2016-12-20T19:23:27.068-00:00" :ammount 3.1416}]
           (is (to-file-test-1 sleep-period expected-result test-data file-path keywords))))

(def by-test-1
  (defn by-test-fn [sleep-period expected-result test-data host]
    (print-header)
    (printp "Test data       : " test-data)
    (let [result-1 (atom 0)
          result-2 (atom 0)
          agent    (create-caudal-agent)
          streams  (by [:host]
                       (counter [:host-counter :count]
                                (defstream [event]
                                           (if (= host (:host event))
                                             (reset! result-1 (:count event))
                                             (reset! result-2 (:count event))))))
          sink     (create-sink agent streams)]
      (doseq [e test-data]
        (sink e))
      (Thread/sleep sleep-period)
      (printp "Expected result : " expected-result)
      (printp "Actual result   : " [@result-1 @result-2])
      (= expected-result [@result-1 @result-2]))))

(with-test by-test-1
           (let [sleep-period    300
                 expected-result [3 2]
                 host            "node1.example.com"
                 test-data       [{:host "node1.example.com" :service "http" :n 1}
                                  {:host "node2.example.com" :service "http" :n 2}
                                  {:host "node2.example.com" :service "http" :n 3}
                                  {:host "node1.example.com" :service "http" :n 4}
                                  {:host "node1.example.com" :service "http" :n 5}]]

             (is (by-test-1 sleep-period expected-result test-data host))))

(def join-test-1
  (defn join-test-fn [sleep-period expected-result test-data host]
    (print-header)
    (printp "Test data       : " test-data)
    (let [result-1 (atom 0)
          result-2 (atom 0)
          result-3 (atom 0)
          agent    (create-caudal-agent)
          streams  (by [:host]
                       (counter [:host-counter :count]
                                (defstream [event]
                                           (if (= host (:host event))
                                             (reset! result-1 (:count event))
                                             (reset! result-2 (:count event)))))
                       (join
                         (counter [:host-counter :count]
                                  (defstream [event]
                                             (reset! result-3 (:count event))))))
          sink     (create-sink agent streams)]
      (doseq [e test-data]
        (sink e))
      (Thread/sleep sleep-period)
      (printp "Expected result : " expected-result)
      (printp "Actual result   : " [@result-1 @result-2 @result-3])
      (= expected-result [@result-1 @result-2 @result-3]))))

(with-test join-test-1
           (let [sleep-period    300
                 host            "node1.example.com"
                 expected-result [3 2 5]
                 test-data       [{:host "node1.example.com" :service "http" :n 1}
                                  {:host "node2.example.com" :service "http" :n 2}
                                  {:host "node2.example.com" :service "http" :n 3}
                                  {:host "node1.example.com" :service "http" :n 4}
                                  {:host "node1.example.com" :service "http" :n 5}]]
             (is (join-test-1 sleep-period expected-result test-data host))))

(def decorate-test-1
  (defn decorate-test-fn [sleep-period initial-state stats-key expected-result test-data tx-name]
    (print-header)
    (printp "Test data       : " test-data)
    (let [caudal-agent (create-caudal-agent
                         :initial-map {[stats-key tx-name] initial-state})
          result       (atom nil)
          streams      (by [:tx]
                           (decorate [stats-key]
                                     (defstream [event]
                                                (reset! result (clean-event event)))))
          sink         (common/create-sink caudal-agent streams)]
      (sink test-data)
      (Thread/sleep sleep-period)
      (printp "Expected result : " expected-result)
      (printp "Actual result   : " @result)
      (= @result expected-result))))

(with-test decorate-test-1
           (let [sleep-period    300
                 initial-state   {:avg 1000 :stdev 30 :n 20}
                 tx-name         "getCustInfo"
                 stats-key       :stats
                 test-data       {:tx tx-name}
                 expected-result {:avg 1000 :stdev 30 :n 20 :tx tx-name}]
             (is (decorate-test-1 sleep-period initial-state stats-key expected-result test-data tx-name))))

(def anomaly-by-stdev-test-1
  (defn anomaly-by-stdev-test-fn [sleep-period expected-result test-data]
    (print-header)
    (printp "Test data       : " test-data)
    (let [result       (atom nil)
          caudal-agent (create-caudal-agent)
          streams      (anomaly-by-stdev [1 :metric :avg :stdev]
                                         (defstream [event]
                                                    (reset! result (clean-event event))))
          sink         (common/create-sink caudal-agent streams)]
      (doseq [event test-data]
        (sink event))
      (Thread/sleep sleep-period)
      (printp "Expected result : " expected-result)
      (printp "Actual result   : " @result)
      (= expected-result @result))))

(with-test anomaly-by-stdev-test-1
           (let [sleep-period    300
                 expected-result {:host "node1.example.com" :service "http" :n 5 :metric 2 :avg 1 :stdev 0}
                 test-data       [{:host "node1.example.com" :service "http" :n 1 :metric 1 :avg 1 :stdev 0}
                                  {:host "node2.example.com" :service "http" :n 2 :metric 1 :avg 1 :stdev 0}
                                  {:host "node2.example.com" :service "http" :n 3 :metric 1 :avg 1 :stdev 0}
                                  {:host "node1.example.com" :service "http" :n 4 :metric 1 :avg 1 :stdev 0}
                                  {:host "node1.example.com" :service "http" :n 5 :metric 2 :avg 1 :stdev 0}]]
             (is (anomaly-by-stdev-test-1 sleep-period expected-result test-data))))


(def anomaly-by-percentil-test-1
  (defn anomaly-by-percentil-test-fn [sleep-period expected-result test-data]
    (print-header)
    (printp "Test data       : " test-data)
    (let [result  (atom nil)
          agent   (create-caudal-agent)
          streams (anomaly-by-percentile [:metric :percentiles 0.95]
                                         (defstream [event]
                                                    (reset! result (clean-event event))))
          sink    (common/create-sink agent streams)]
      (doseq [e test-data]
        (sink e))
      (Thread/sleep sleep-period)
      (printp "Expected result : " expected-result)
      (printp "Actual result   : " @result)
      (= expected-result @result))))

(with-test anomaly-by-percentil-test-1
           (let [sleep-period    300
                 expected-result {:host "node1.example.com" :service "http" :n 5 :metric 2 :percentiles {0.25 1 0.95 1}}
                 test-data       [{:host "node1.example.com" :service "http" :n 1 :metric 1 :percentiles {0.25 1 0.95 1}}
                                  {:host "node2.example.com" :service "http" :n 2 :metric 1 :percentiles {0.25 1 0.95 1}}
                                  {:host "node2.example.com" :service "http" :n 3 :metric 1 :percentiles {0.25 1 0.95 1}}
                                  {:host "node1.example.com" :service "http" :n 4 :metric 1 :percentiles {0.25 1 0.95 1}}
                                  {:host "node1.example.com" :service "http" :n 5 :metric 2 :percentiles {0.25 1 0.95 1}}]]
             (is (anomaly-by-percentil-test-1 sleep-period expected-result test-data))))
;; TODO: forward

(def percentiles-test-1
  (defn percentiles-test-fn [sleep-period expected-result test-data percents max-stored-elements max-propagation-time]
    (print-header)
    ;(printp "Test data       : " test-data)
    (printp "Percents        : " percents)
    (let [streams (default [:ttl -1]
                           (by [:tx]
                               (batch [:batch-1 max-stored-elements max-propagation-time]
                                      (percentiles [:delta :percentiles percents]
                                                   (store! [:store (fn [e] "percentiles")])))))
          state   (agent {})
          sink    (create-sink state streams)]
      (doseq [event test-data]
        (sink event))
      (Thread/sleep sleep-period)
      (printp :las-llaves (keys @state))
      (printp "Expected result : " expected-result )
      (let [result (-> @state (get [:store "tx1" "percentiles"]) :percentiles)]
        (printp "Actual result   : " result)
        (= result expected-result)))))

(with-test percentiles-test-1
           (let [sleep-period         1000
                 how-many             10000
                 random-range         1000
                 max-stored-elements  10000
                 max-propagation-time 2000
                 percents             [0.25 0.5 0.75 0.95]
                 metrics              (vec (for [x (range 0 how-many)] (rand-int random-range)))
                 expected-result      (let [sorted (sort metrics)
                                            idx-fn (fn [percent]
                                                     (min (dec how-many) (Math/floor (* percent how-many))))]
                                        (into {} (map (fn [p]
                                                        [p (nth sorted (idx-fn p))]) percents)))

                 test-data            (into [] (shuffle
                                                 (map-indexed (fn [idx rnd]
                                                                {:tx "tx1" :host "h1" :delta rnd :idx idx}) metrics)))]
             (is (percentiles-test-1 sleep-period expected-result test-data percents max-stored-elements max-propagation-time))))

(deftest with-test-2
  (let [test-fn (fn x [e]
                  (let [agt     (create-caudal-agent)
                        result  (atom {})
                        test    {:param :test :value -1}
                        streams (by [:tx]
                                    (with [(:param test) (:value test)]
                                          (with [:ttl -1]
                                                (defstream [e]
                                                           (reset! result (dissoc e :caudal/latency))
                                                           (locking result
                                                             (.notify result))))))
                        sink    (common/create-sink agt streams)]
                    (sink e)
                    (locking result
                      (.wait result 500))
                    @result))]
    (is (= {:tx "getCustInfo" :ttl -1 :test -1} (test-fn {:tx "getCustInfo" :ttl -1 :test -1})))))

(deftest back-pressure-test
  (let [test-fn (fn back-pressure [limit events]
                  (let [agt (create-caudal-agent)
                        streams (with [:tx "Transact-1"]
                                  (with [:host "host1"]
                                    (defstream [e]
                                      (Thread/sleep 100)
                                      (println e))))
                        sink (common/create-sink agt streams :back-pressure-limit limit)
                        t0 (System/currentTimeMillis)
                        _ (doseq [n (range 0 events)]
                            (sink {:test true :now (System/currentTimeMillis)}))
                        ;_ (Thread/sleep 3000)
                        ;_ (sink {:test false :now (System/currentTimeMillis)})
                        t1 (System/currentTimeMillis)]
                    (- t1 t0)))
        test1 (test-fn 20 20)
        _ (println "Resultado con 20 20:" test1)
        _ (Thread/sleep 1000)

        test2 (test-fn 10 20)
        _ (println "Resultado con 10 20:" test2)]

    (is (< test1 20))
    (is (> test2 1000))))

(deftest smap-test
  (let [test-fn (fn [e parameter-key parameter-value]
                  (let [agt     (create-caudal-agent)
                        result  (atom {})
                        streams (by [:tx]
                                    (smap [#(assoc % parameter-key parameter-value)]
                                          (defstream [e]
                                                     (reset! result (dissoc e :caudal/latency))
                                                     (locking result
                                                       (.notify result)))))
                        sink    (common/create-sink agt streams)]
                    (sink e)
                    (locking result
                      (.wait result 500))
                    @result))]
    (is (= (test-fn {:tx "getCustInfo" :ttl 100 :millis 10} :test "testing")
           {:tx "getCustInfo" :ttl 100 :millis 10 :test "testing"}))
    (is (= (test-fn {:tx "getCustInfo" :ttl 100 :millis 110} :assoc "assoc-test")
           {:tx "getCustInfo" :ttl 100 :millis 110 :assoc "assoc-test"}))))


(deftest where-test
  (let [test-fn (fn [e]
                  (let [agt       (create-caudal-agent)
                        condition (fn [e] (< (:millis e) 100))
                        result    (atom {})
                        streams   (by [:tx]
                                      (where [condition]
                                             (defstream [e]
                                                        (reset! result (dissoc e :caudal/latency))
                                                        (locking result
                                                          (.notify result)))))
                        sink      (common/create-sink agt streams)]
                    (sink e)
                    (locking result
                      (.wait result 500))
                    @result))]
    (is (= (test-fn {:tx "getCustInfo" :ttl 100 :millis 10})
           {:tx "getCustInfo" :ttl 100 :millis 10}))
    (is (= (test-fn {:tx "getCustInfo" :ttl 100 :millis 110})
           {}))))


(deftest split-test
  (let [caudal-agent (create-caudal-agent)
        result       (atom {})
        test-fn      (fn [e]
                       (let [streams (by [:tx]
                                         (split [(fn [e] (= (:tx e) "tx-1"))]
                                                (defstream [e]
                                                           (locking result
                                                             (.notify result))
                                                           (reset! result (assoc (dissoc e :caudal/latency) :result 1)))
                                                [(fn [e] (= (:tx e) "tx-2"))]
                                                (defstream [e]
                                                           (reset! result (assoc (dissoc e :caudal/latency) :result 2))
                                                           (locking result
                                                             (.notify result)))))
                             sink    (create-sink caudal-agent streams)]
                         (sink e)
                         (locking result
                           (.wait result 800))
                         @result))]
    (is (= (test-fn {:tx "tx-1" :ttl 100 :millis 10})
           {:tx "tx-1" :ttl 100 :millis 10 :result 1}))
    (is (= (test-fn {:tx "tx-2" :ttl 100 :millis 10})
           {:tx "tx-2" :ttl 100 :millis 10 :result 2}))))

(deftest by-test
  (let [test-fn (fn [e]
                  (let [agt     (create-caudal-agent)
                        result  (atom {})
                        streams (by [:tx]
                                    (by [:ttl]
                                        (defstream [e]
                                                   (reset! result (dissoc e :caudal/latency))
                                                   (locking result
                                                     (.notify result)))))
                        sink    (common/create-sink agt streams)]
                    (sink e)
                    (locking result
                      (.wait result 500))
                    @result))]
    (is (= (test-fn {:tx "tx-1" :ttl 100 :millis 10})
           {:tx "tx-1" :ttl 100 :millis 10}))
    (is (= (test-fn {:tx "tx-2" :ttl 900 :millis 100})
           {:tx "tx-2" :ttl 900 :millis 100}))))


(deftest decorate-test
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
        sink          (common/create-sink agt streams)]
    (sink test-event)
    (Thread/sleep 300)
    (println @result)
    (is (= @result expected))))


(comment deftest store!-test
  (let [initial-state {:avg 1000 :stdev 30 :n 20}
        agt           (create-caudal-agent)
                 test-event    {:tx "getCustInfo"}
                 expected      (merge test-event initial-state)
                 result        (atom nil)
                 streams       (by [:tx]
                                   (store! [:stats initial-state]
                                           (decorate [:stats]
                                                     (defstream [e]
                                                                (reset! result (dissoc e :caudal/latency))))))
                 sink          (common/create-sink agt streams)
             (sink test-event)
             (Thread/sleep 300)
             (println @result)
             (is (= @result expected))]))


(deftest anomaly-by-percentil
  (let [initial-state {:avg 1000 :stdev 30 :n 20}
        agt           (create-caudal-agent
                        :initial-map initial-state)
        percents      [0.25 0.5 0.75 0.95]
        test-fn       (fn [e]
                        (let [
                              result  (atom nil)
                              streams (by [:tx]
                                          (percentiles [:delta :percentiles percents]
                                                       (anomaly-by-percentile [3 :delta :avg :stdev]
                                                                              (decorate [:percentiles]
                                                                                        (store! [(fn [e] :percentiles)]
                                                                                                (defstream [event]
                                                                                                           (println "##########################")
                                                                                                           (reset! result event)
                                                                                                           (locking result
                                                                                                             (.notify result))))))))
                              sink    (common/create-sink agt streams)]
                          (sink e)
                          (locking result
                            (.wait result 500))
                          @agt))]
    (is (= (contains? (test-fn {}) :percentiles)))
    (is (= (number? (:percentiles (test-fn {})))))))


(deftest percentiles-test
  (let [initial-state {0.70 700 0.9 900}
        expected      {:delta 100 :percentiles {0.7 800 0.9 1000}}
        events        [{:delta 100}
                       {:delta 200}
                       {:delta 300}
                       {:delta 400}
                       {:delta 500}
                       {:delta 600}
                       {:delta 700}
                       {:delta 800}
                       {:delta 900}
                       {:delta 1000}]
        agt           (create-caudal-agent
                        :initial-map initial-state)
        percents      [0.7 0.9]
        result        (atom nil)
        streams       (batch [:batch 10 1000]
                             (percentiles
                               [:delta :percentiles percents]
                               (defstream [event]
                                          (println "##########################")
                                          (reset! result (dissoc event :caudal/latency))
                                          (locking result
                                            (.notify result)))))
        sink          (create-sink agt streams)]
    (doseq [e events]
      (sink e))
    (Thread/sleep 500)
    (is (= @result expected))))


(deftest anomally-by-stdev-test
  (let [initial-state {:avg 1000 :stdev 30 :n 20}
        agt           (create-caudal-agent
                        :initial-map initial-state)
        percents      [0.25 0.5 0.75 0.95]
        test-fn       (fn [e]
                        (let [
                              result  (atom nil)
                              streams (by [:tx]
                                          (anomaly-by-stdev [99999999999 :millis :avg :stdev]
                                                            (decorate [:percentiles]
                                                                      (store! [(fn [e] :percentiles)]
                                                                              (defstream [event]
                                                                                         (println "##########################")
                                                                                         (reset! result event)
                                                                                         (locking result
                                                                                           (.notify result)))))))
                              sink    (common/create-sink agt streams)]
                          (sink e)
                          (locking result
                            (.wait result 500))
                          @agt))]
    (is (= (contains? (test-fn {}) :percentiles)))
    (is (= (number? (:percentiles (test-fn {})))))))
