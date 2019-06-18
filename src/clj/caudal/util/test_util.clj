;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns caudal.util.test-util
  (:require [clojure.tools.logging :as log]
            [clojure.string :refer [split]]))

(defn print-header []
  (let [test-fn-str (str (get (.getStackTrace (Thread/currentThread)) 3))
        line        (reduce str (repeat 160 "-"))
        fn-name     (get (split test-fn-str #" ") 0)]
    (log/debug (str "\n" line "\nTesting " fn-name " ...\n" line))))

(defn clean-event [event]
  (dissoc event :caudal/created :caudal/touched :caudal/latency :service-key :ttl))
