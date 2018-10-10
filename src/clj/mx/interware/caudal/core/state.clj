;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns mx.interware.caudal.core.state
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))
;[immutant.caching :as C])
;(:import (java.net InetAddress URL)
;  (org.apache.log4j PropertyConfigurator)))


(defprotocol caudal-store
  (as-map [this])
  (store! [[this k v]])
  (lookup [this k] [this k default])
  (remove! [[this k]])
  (clear-all! [this])
  (clear-keys! [this key-filter])
  (update!
    [this k fun]
    [this k fun a1]
    [this k fun a1 a2]
    [this k fun a1 a2 a3]
    [this k fun a1 a2 a3 a4]
    [this k fun a1 a2 a3 a4 a5]
    [this k fun a1 a2 a3 a4 a5 a6]
    [this k fun a1 a2 a3 a4 a5 a6 a7]
    [this k fun a1 a2 a3 a4 a5 a6 a7 a8]
    [this k fun a1 a2 a3 a4 a5 a6 a7 a8 a9]
    [this k fun a1 a2 a3 a4 a5 a6 a7 a8 a9 a10]))

(defn selector [type-name & params]
  type-name)

(defmulti create-store selector)
