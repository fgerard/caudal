;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns caudal.util.id-util
  (:import (java.util Random UUID)))

(defn uuid [] (str (UUID/randomUUID)))

(defn key-juxt [& ks]
  (fn [e] (vec (flatten (map (fn [k] [(str k) (k e)]) ks)))))

(defn random-hex [size]
  (let [rnd (new Random)
        sb  (new StringBuffer)]
    (while (< (.length sb) size)
      (.append sb (Integer/toHexString (.nextInt rnd))))
    (-> sb .toString (.substring 0 size))))
