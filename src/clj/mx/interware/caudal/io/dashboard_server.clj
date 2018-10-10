;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns mx.interware.caudal.io.dashboard-server
  (:require [clojure.tools.logging :as log]
            [bidi.ring :refer [make-handler]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.middleware.file :refer [wrap-file]]
            [aleph.http :as aleph-http]
            [mx.interware.caudal.streams.common :refer [start-listener]]))

(defn create-routes []
  [])

(defn create-handler []
  (let [routes (create-routes)]
    (make-handler routes)))

(defn create-app [cors]
  (let [cors (or cors #".*localhost.*")]
    (-> (create-handler)
        ;(wrap-session)
        (wrap-file "caudal-dashboard/resources/public"))))

(defn start-server [app config]
  (let [config (dissoc config :cors)
        server (aleph-http/start-server app config)]
    (log/info {:rest-server config})))

(defmethod start-listener 'mx.interware.caudal.io.dashboard-server
  [sink config]
  (let [cors   (get-in config [:parameters :cors])
        states (get-in config [:states])
        _ (log/debug :states states)
        app    (create-app cors)]
    (start-server app (:parameters config))))
