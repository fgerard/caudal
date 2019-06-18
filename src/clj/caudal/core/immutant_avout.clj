;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns caudal.core.immutant-avout
  (:gen-class)
  (:require [immutant.caching :as C]
            [avout.state :as as])
  (:import (java.net InetAddress URL)
           (org.apache.log4j PropertyConfigurator)))

(deftype ImmutantStateContainer [^org.infinispan.Cache cache node-id]

  as/StateContainer

  (initStateContainer [this])


  (destroyStateContainer [this]
    (.remove cache node-id))

  (getState [this]
    (.get cache node-id))

  (setState [this new-val]
    (C/swap-in! cache node-id (fn [_] new-val))))


(comment
  (def cache (C/cache "avout" :ttl 30000))
  (require '[avout.core :as A])
  (require '[avout.atoms :as AA])
  (require '[caudal.core.immutant-avout :as IA])
  (defn im-atom [zk-client cache a-name]
    (AA/distributed-atom zk-client a-name
                         (caudal.core.immutant_avout.ImmutantStateContainer. cache a-name))))
