;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns mx.interware.caudal.test.testers
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer [chan go go-loop buffer timeout <! >! <!! >!!]]
            [immutant.caching :as C]
            [mx.interware.caudal.core.state :as ST]
            [mx.interware.caudal.core.atom-state]
            [mx.interware.caudal.core.immutant-state]))

(comment defn entry-with-store [global-state & children]
         (let [[default-ttl & children] (if (number? (first children)) children (cons -1 children))]
           (def state global-state)
           (let [fun (fn [e]
                       (log/debug :entry-with-store (pr-str e))
                       (propagate e children))]
             fun)))

(comment defn create-streams [caudal-config]
         (entry-with-store (ST/create-store :atom
                                            :default-ttl 10000
                                            :ttl-delay 30000
                                            :expired-sink (fn [e] (log/debug :expired e)))
                           (default :ttl -1
                                    (time-stampit
                                      [:time]
                                      (batch [::els-batch 10 10000]
                                             (elastic/elastic-store!
                                               "http://elasticsearch:9200/" "caudal-demo" nil))
                                      (counter ::acum1 :metric
                                               (fn [e] (log/debug :acum1 e)))
                                      (by [[:host]]
                                          (fn [e] (log/debug :despues-de-host e))
                                          (counter ::acum :metric
                                                   (fn [e]
                                                     (log/debug :counter (pr-str e))))
                                          (by [[:service]]
                                              (counter ::acum3 :metric
                                                       (fn [e]
                                                         (log/debug :by-h-s (pr-str e)))))
                                          (rollup [::ruk 3 10000]
                                                  (fn [e]
                                                    (log/debug :rollup (pr-str e)))))
                                      (by [[:user]]
                                          (counter ::acum4 :metric
                                                   (fn [e]
                                                     (log/debug :by-h-u (pr-str e)))))))))

(comment defn create-streams [caudal-config]
         (let [streams-chan (chan)
               streams-fun  (entry-with-store (ST/create-store :atom
                                                               :default-ttl 10000
                                                               :ttl-delay 30000
                                                               :expired-sink (fn [e]
                                                                               (go (>! streams-chan (assoc e :state "expired")))))

                                              (default :ttl -1
                                                       (where [#(= "localhost" (:host %))]
                                                              (with :host "REINYECTADO"
                                                                    (with :ttl 10
                                                                          (reinject streams-chan))))
                                                       (where [#(not= "expired" (:state %))]
                                                              (time-stampit [:time]
                                                                            (counter ::acum1 :metric
                                                                                     (fn [e] (log/debug :acum1 e)))
                                                                            (changed [::estado :state]
                                                                                     (fn [[old new]] (log/debug :CAMBIO (:state old) (:state new))))
                                                                            (by [[:host]]
                                                                                (fn [e] (log/debug :despues-de-host e))
                                                                                (counter ::acum :metric
                                                                                         (fn [e]
                                                                                           (log/debug :counter (pr-str e))))
                                                                                (by [[:service]]
                                                                                    (counter ::acum3 :metric
                                                                                             (fn [e]
                                                                                               (log/debug :by-h-s (pr-str e))))
                                                                                    (rollup [::ruk 3 10000]
                                                                                            (fn [e]
                                                                                              (log/debug :rollup (pr-str e)))))))
                                                              (by [[:usr]]
                                                                  (counter ::acum4 :metric
                                                                           (fn [e]
                                                                             (log/debug :by-h-u (pr-str e))))))))]
           (go-loop []
             (let [e (<! streams-chan)]
               (log/debug (pr-str "LLEGO:" e))
               (streams-fun e)
               (recur)))
           streams-chan))

(comment defn create-streams-1 [caudal-config]
         (entry-with-store (ST/create-store :immutant
                                            :default-ttl 10000
                                            :event-sink (fn [e] (log/debug :debugging e))
                                            :infinispan-events [:cache-entry-removed :cache-entry-created]
                                            (default :ttl 10000
                                                     ;(fn [e] (log/debug :entro e :state (pr-str (ST/as-map state))))
                                                     ;(ewma-timeless ::ewma 0.1 :metric
                                                     ;  (fn [e] (log/debug :ewmado e))
                                                     ;  (store (juxt :host :service)
                                                     ;    (fn [e] (log/debug :guardado e))
                                                     ;  (reduce-with ::ewma (fn [ewma evt] (assoc ewma :ttl 20000))
                                                     ;    (fn [e] (log/debug :updeteado e))
                                                     (time-stampit [:time]
                                                                   ;(moving-time-window ::mtw 20000
                                                                   ;
                                                                   ;(fn [e] (log/debug :window (count e) e))

                                                                   (rollup [::rollup-it 3 10000]
                                                                           (fn [e]
                                                                             (log/debug :rollup (pr-str e)))))))))

(comment defn create-streams-2 [caudal-config]
         (entry-with-store (ST/create-store :immutant
                                            :default-ttl 10000
                                            :event-sink (fn [e] (log/debug :debugging e))
                                            :infinispan-events [:cache-entry-removed :cache-entry-created]
                                            (default :ttl 10000
                                                     ;(fn [e] (log/debug :entro e :state (pr-str (ST/as-map state))))
                                                     ;(ewma-timeless ::ewma 0.1 :metric
                                                     ;  (fn [e] (log/debug :ewmado e))
                                                     ;  (store (juxt :host :service)
                                                     ;    (fn [e] (log/debug :guardado e))
                                                     ;  (reduce-with ::ewma (fn [ewma evt] (assoc ewma :ttl 20000))
                                                     ;    (fn [e] (log/debug :updeteado e))
                                                     (time-stampit [:time]
                                                                   ;(moving-time-window ::mtw 20000
                                                                   ;
                                                                   ;(fn [e] (log/debug :window (count e) e))
                                                                   (counter ::acum1 :metric
                                                                            (fn [e] (log/debug :acum1 e)))
                                                                   (by [[:host]]
                                                                       (fn [e] (log/debug :despues-de-host e))
                                                                       (counter ::acum :metric
                                                                                (fn [e]
                                                                                  (log/debug :counter (pr-str e))))
                                                                       (by [[:service]]
                                                                           (counter ::acum3 :metric
                                                                                    (fn [e]
                                                                                      (log/debug :by-h-s (pr-str e)))))
                                                                       (rollup [::ruk 3 10000]
                                                                               (fn [e]
                                                                                 (log/debug :rollup (pr-str e)))))
                                                                   (by [[:usr]]
                                                                       (counter ::acum4 :metric
                                                                                (fn [e]
                                                                                  (log/debug :by-h-u (pr-str e))))))))))

(comment
 (doseq [dt [0 1 1 5 1 1 1 1 1 1 1 1 1]] (Thread/sleep (* 1000 dt)) (manda))

 (comment def streams) (start {}))


; (streams {:host "uno" :service "uno"})
; (streams {:host "dos" :service "dos" :metric 23})

(comment let [a (atom 0)]
         (defn manda []
           (swap! a inc)
           (streams {:host "uno" :service "ssl" :numero (str @a) :metric 1000})))

(comment time
         (doseq [evt (repeatedly 000000 (fn [] {:host "uno" :service (str "S" (rand-int 1000000))}))]
           (streams evt)))


(comment
  (default :ttl 20000
           (ewma-timeless ::ewma 0.1 :metric prn)
           (counter ::eventos :cuenta
                    (time-stampit [:time]
                                  (store (juxt :host :service)))
                    ;(fn [e] (log/info :a e))

                    (with :nombre "Geras"
                          (split
                            #(= "uno" (:host %))
                            (with :sla "importatntisimo"
                                  ;(fn [e] (log/info :es-uno e))
                                  (store :sla))
                            ;(fn [_] (pp/pprint (ST/as-map state)))


                            #(= "dos" (:host %))
                            (fn [e] (log/info :es-dos e))

                            (fn [e] (log/info :es-otro e)))
                          ;(fn [e] (log/info :b e))
                          (fn [_])))))

(comment
  ;(def conf (.. (C/builder :ttl 10000 :eviction :lru :max-entries 1000) eviction expiration (wakeUpInterval 5000) build))
  (def conf (.. (ConfigurationBuilder.)
                eviction (maxEntries 1000)
                ;(strategy org.infinispan.eviction.EvictionStrategy/LRU)
                expiration (wakeUpInterval 1000) (maxIdle 10000) build))
  (def k (C/cache "cosa1" :configuration conf))
  (.addListener k (InfListener.))
  (def em (.getEvictionManager k))
  (.processEviction em)
  (C/add-listener! k prn :cache-entry-removed :cache-entries-evicted)
  (doseq [n (range 1 1100)] (.put k (str "xx" n) n))
  (.remove k "xx39"))

(comment


  (defn rec [msg n]
    (if (> n 0)
      (rec msg (dec n))
      (throw (Exception. (str msg)))))

  (defn ex [msg n]
    (try
      (rec msg n)
      (catch Exception e
        e)))

  (PropertyConfigurator/configure "log4j.properties")
  (log/info "hola")

  (doseq [n (range 1 100)]
    (future
      (if (odd? n)
        (log/info (ex (str "THREAD:" (System/currentTimeMillis) " - " n) 5))
        (log/info (str "ESTE NO:" (System/currentTimeMillis) " - " n)))))

  (require '[avout.core :as A])
  (def c (A/connect "104.131.77.228"))
  (def a (A/zk-atom c "/a0" 0))
  (A/swap!! a inc)
  (do
    (Thread/sleep 5000)
    (log/debug "ya")))


(comment
  (defn by-fn [fields children-factory-fn])
  (let [fields (flatten fields)
        fld    (if (= 1 (count fields))
                 (first fields)
                 (apply juxt fields))
        table  (atom {})]
    (fn [e]
      (log/debug :table @table)
      (log/debug :fld fld (fld e))
      (let [fork-name (fld e)
            fork      (if-let [fork (@table fork-name)]
                        fork
                        ((swap! table assoc fork-name (children-factory-fn)) fork-name))]
        (if (bound? #'by-path)
          (binding [by-path [by-path fork-name]]
            (log/debug :by2 by-path)
            (propagate e fork))
          (binding [by-path [fork-name]]
            (log/debug :by by-path)
            (propagate e fork)))))))

(defmacro macro-by [fields & children]
  `(let [new-fork# (fn [] [~@children])]
     (by-fn ~fields new-fork#)))

(comment

  (use 'clojure.walk
       (def html [:div {:class "buena"} [:br :metric] [:br :thread] [:h1 "muchas gracias"]])
       (def m {:metric 3500, :thread "T1"})

       (defn catafixia [m e]
             (log/debug m e (coll? e))
             (if (coll? e)
               (walk (partial catafixia m) identity e)
               (if-let [v (get m e)]
                       v
                       e))))

  (walk (partial catafixia m) identity html))


(comment
  ; prueba que demuestra el error numérico en el cálculo parcializado de la stdev
  (def data (repeatedly 10000 #(rand-int 1000)))
  (defn avg [v]
    (double (/ (reduce + v) (count v))))
  (defn stdev [v]
    (Math/sqrt (- (avg (map #(Math/pow % 2) v))
                  (Math/pow (avg v) 2))))
  (defn avg-en-partes [v partes]
    (let [avgs (map avg (partition partes v))]
      (reduce (fn [[n avg-t] avgi]
                [(+ n partes) (double (/ (+ (* n avg-t) (* avgi partes)) (+ n partes)))])
              [0 0]
              avgs)))

  (defn stdev-en-partes [v partes]
    (let [stdevs (map stdev (partition partes v))]
      (reduce (fn [[n stdev-t] stdevi]
                [(+ n partes) (Math/sqrt (/ (+ (* stdev-t stdev-t n) (* stdevi stdevi partes)) (+ n partes)))])
              [0 0]
              stdevs))))


;(require '[clojure.core.async :refer [chan >!! alts! go-loop buffer <!]])

(let [s (atom nil)]
  (defn dedup [l]
    (let [pasa (not= l @s)]
      (log/debug :dedup " l:" l " s:" s)
      (reset! s l)
      (if pasa l))))

(def c (chan (buffer 1) (filter dedup)))

;( go-loop [] (log/debug "llego:" (<! c)) (recur))
