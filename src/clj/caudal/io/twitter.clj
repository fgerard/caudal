;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns caudal.io.twitter
  (:require [clojure.tools.logging :as log]
            [caudal.streams.common :refer [start-listener]]
            [clojure.data.json :as json])
  (:import (java.util ArrayList)
           (java.util.concurrent LinkedBlockingQueue)
           (com.twitter.hbc ClientBuilder)
           (com.twitter.hbc.core Constants HttpHosts)
           (com.twitter.hbc.core.endpoint StatusesFilterEndpoint)
           (com.twitter.hbc.core.processor StringDelimitedProcessor)
           (com.twitter.hbc.httpclient.auth OAuth1)))

(defn read-event [str]
  (try
    (read-string str)
    (catch Exception e
      nil)))

(def stop-flag (atom false))

(defn start-streaming [sink name consumer-key consumer-secret token token-secret terms]
  (let [msg-queue   (new LinkedBlockingQueue 100000)
        event-queue (new LinkedBlockingQueue 1000)
        hosts       (new HttpHosts (Constants/STREAM_HOST))
        endpoint    (new StatusesFilterEndpoint)
        followings  (new ArrayList [(long 1234) (long 566788)])
        term-list   (new ArrayList terms)
        _           (.followings endpoint followings);
        _           (.trackTerms endpoint term-list);
        oauth       (new OAuth1 consumer-key, consumer-secret, token, token-secret)
        builder     (-> (new ClientBuilder)
                        (.name name)
                        (.hosts hosts)
                        (.authentication oauth)
                        (.endpoint endpoint)
                        (.processor (new StringDelimitedProcessor msg-queue))
                        (.eventMessageQueue event-queue))
        client      (.build builder)]
    (.connect client)
    (log/debug "hosts : " hosts)
    (future
     (loop [c 0]
       (when (and (not (.isDone client)) (not @stop-flag))
         (let [json-msg (.take msg-queue)
               event    (json/read-str json-msg :key-fn keyword)]
           ;(log/debug "stop-flag : " @stop-flag)
           (log/debug (str name " (" c ") - Received message : " json-msg))
           (sink event))
         (recur (inc c))))
     (.stop client))))

(defmethod start-listener 'caudal.io.twitter
  [sink config]
  "
  Creates a Twitter/X streaming listener (hbc client, filtered by terms):
  sinks one event per matching status/tweet received on the stream.

  - _name:_ client name (passed to hbc's ClientBuilder, shows up in its
    logs/metrics)
  - _consumer-key:_, _consumer-secret:_, _token:_, _token-secret:_
    OAuth1 credentials for the Twitter/X API
  - _terms:_ list of terms to track on the filtered stream

  Example:

  ```
  (deflistener twitter [{:type 'caudal.io.twitter
                         :parameters {:name             \"my-tracker\"
                                      :consumer-key      \"...\"
                                      :consumer-secret   \"...\"
                                      :token             \"...\"
                                      :token-secret      \"...\"
                                      :terms             [\"clojure\" \"caudal\"]}}])
  ```
  "
  (let [{:keys [name consumer-key consumer-secret token token-secret terms]} (get-in config [:parameters])]
    (start-streaming sink name consumer-key consumer-secret token token-secret terms)))
