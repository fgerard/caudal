;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns mx.interware.caudal.core.global)

(comment
  (ns mx.interware.caudal.core.global
    (:require [clojure.tools.logging :as log]
              [mx.interware.caudal.streams.common :refer [propagate]]
              [mx.interware.caudal.io.elastic :as el-util])
    (:import (java.util Date))))

(comment defn add-value [key value & children]
         (fn stream [event]
           (let [new-event (assoc event key value)]
             (propagate new-event children))))

(comment defn log-event [prefix]
         (fn stream [event]
           (log/info prefix event)))

(comment defn store-unique-event [index doc-type]
         (fn stream [event]
           (el-util/put-document index doc-type "1" event)))

(comment defn dummy [& children]
         (fn stream [event]
           (log/info (str "[" (Date.) "] Inside dummy stream with event : " event " ..."))
           (propagate event children)))

(comment defn threshold [value & children]
         (fn stream [event]
           (if (> (:metric event) value)
             (propagate event children))))

(comment defn is-older? [now delta evt]
         ;(log/info :now now :delta delta :evt evt)
         (let [result (< (* 1000 (+ (:time evt) delta)) now)]
           ;(log/info :result result :evt evt)
           result))

(comment def tmp (atom []))

(comment defn do-filter-sort [{:keys [events] :as buf} delta key-fun]
         ;(log/info :buf buf "************")
         (when (seq events)
           (let [now           (System/currentTimeMillis)
                 older?        (partial is-older? now delta)
                 t0            (System/currentTimeMillis)
                 sorted-events (sort-by key-fun events)
                 n-events      (count events)
                 to-dump       (->> sorted-events (take-while older?))
                 events        (->> sorted-events
                                    (drop-while older?))
                 t1            (System/currentTimeMillis)
                 dummy         (swap! tmp conj [n-events (- t1 t0)])]
             ;(apply sorted-set-by evt-order-fun)

             ;(log/info :events events "=========")
             ;(log/info :to-dump to-dump "--------")
             {:events  (seq events)
              :to-dump (seq to-dump)})))


(comment defn filter-sort&dump [buf delta key-fun children]
         (when-let [to-dump (:to-dump (swap! buf do-filter-sort delta key-fun))]
           (doseq [e to-dump]
             (streams/call-rescue e children))))

(comment defn mixer
         [delta key-fun & children]
         (let [buf (atom {})]
           (time/every! delta (partial filter-sort&dump buf delta key-fun children))
           (fn stream [evt]
             (swap! buf update :events conj evt))))


(comment defn send-mail [to subject body]
         (let [host     "smtp.gmail.com"
               from     "factura.electronica@interware.com.mx"
               password "21yqpotkd37xxf6khh6mqdkh97z301r02f06aw6qarxpen3dkqgykduhmbm0lnh487v5cutzulb3ekj0fcgt5czecplfdqiyb22n7agp0btmliaim56m261o4otjl6tbovc8430uvg4rtqqnhax56qgne4j49b1xt5jnerap4mn62exwtsuyz0rdyigiasi8m0uhsnvk3v2zptcklj7zpexdflc1uew674506viyjav861d2g22h1jizdikknk06zpaswqg72o1mzj6wxqs82pgzspzyahlbghe9nu3sb13c4tksd2p9dcmo23eyajd3xp8u5rxtr0aoy6t4eo1h6fq34f5u52wl8zkfu2krfketyjqdr7jjrtyvpqo9yk8ht3cfu7ql5sm8s0g95wx1yp7hr6ti8t5g9teqargiaw8pgsji951wshy2diklbkzw1hifgc6lhu7ze8qwjw1g2a9nhish6oa"]
           mailer {:from from
                   :host "smtp.gmail.com"
                   :user "foo"
                   :pass "bar"}))
