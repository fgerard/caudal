;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns mx.interware.caudal.core.listener
  (:gen-class)
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [immutant.caching :as C]
            [mx.interware.caudal.core.state :as ST])
  (:import (java.net InetAddress URL)
           (org.apache.log4j PropertyConfigurator)
           (org.infinispan.configuration.cache ConfigurationBuilder)
           (org.infinispan.notifications.cachelistener.annotation
            CacheEntryActivated CacheEntryCreated
            CacheEntryEvicted CacheEntryInvalidated
            CacheEntryLoaded CacheEntryModified
            CacheEntryPassivated CacheEntryRemoved
            CacheEntryVisited)

           (org.infinispan.notifications Listener)))
;(org.infinispan.eviction EvictionStategy)
(definterface EventListener
  (^void event [e]))

(deftype ^{Listener true} InfListener []
  EventListener
  (^{CacheEntryActivated   true
     CacheEntryCreated     true
     CacheEntryEvicted     true
     CacheEntryInvalidated true
     CacheEntryLoaded      true
     CacheEntryModified    true
     CacheEntryPassivated  true
     CacheEntryRemoved     true
     CacheEntryVisited     true}
   ^void event [this e] (log/debug :listener---2 e)))
