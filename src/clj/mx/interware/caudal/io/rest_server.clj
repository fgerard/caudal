;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns mx.interware.caudal.io.rest-server
  (:require [clojure.tools.logging :as log]
            [clojure.java.io :refer [file]]
            [bidi.ring :as bidi]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.middleware.format :refer [wrap-restful-format]]
            [ring.middleware.gzip :refer [wrap-gzip]]
            [aleph.http :as aleph-http]
            [mx.interware.caudal.streams.common :refer [start-listener deflistener* comp-sink]]
            [mx.interware.caudal.web.rest-handle :as handle])
  (:import
    (io.netty.handler.ssl SslContextBuilder)))

(def Dname "caudal")
(def version "0.7.14")

(defn not-found [request]
  {:status 404 :body (str (:uri request) " not found!")})

(def now (System/currentTimeMillis))

(defn redirect2project [request]
     {:status 301 :headers {"location" (str "/" Dname)}})

(def _ah-cmds (atom {}))

(defn register_ah-fn [cmd f]
  (log/info "Register fn for: " (pr-str cmd))
  (swap! _ah-cmds assoc cmd f))

(register_ah-fn "hello" (fn [] {:status 200 :body (str "Hello World @ " (java.util.Date.))}))

(defn index [routes]
  (let [nss (reduce (fn [result [path _]]
                      (conj result path))
                    []
                    routes)]
    (fn [request]
      (if-let [cmd-fn (->>
                       (get-in request [:route-params :cmd])
                       (get @_ah-cmds))]
        (cmd-fn)
        {:status 200
         :body {:name Dname
                :version version
                :uptime (- (System/currentTimeMillis) now)
                :ns nss}}))))

(defn make-handler [routes]
  ;(clojure.pprint/pprint (concat routes [[true not-found]]))
  (bidi/make-handler
   ["/"
    (-> routes
        (concat [["" redirect2project]])
        (concat [[Dname (index routes)]])
        (concat [[["_ah/" :cmd] {{:request-method :get} (index routes)}]])
        (concat [[true not-found]]))]))

(defn create-publisher-routes [states]
  [;[["/"] {{:request-method :get} handle/index-handler}]
   [["app"] {{:request-method :get} handle/index-handler}]
   ;[["/dashboard"] {{:request-method :get} handle/index-handler}]
   ;poner login aquí
   [["states"] {{:request-method :get} (partial handle/state-handler states)}]
   [["state/" :id] {{:request-method :get} (partial handle/state-handler states)}]
   [["state/" :id "/" :key] {{:request-method :get} (partial handle/state-handler states)}]
   [["state/" :id "/" :key "/" :by1] {{:request-method :get} (partial handle/state-handler states)}]
   [["state/" :id "/" :key "/" :by1 "/" :by2] {{:request-method :get} (partial handle/state-handler states)}]
   [["state/" :id "/" :key "/" :by1 "/" :by2 "/" :by3] {{:request-method :get} (partial handle/state-handler states)}]
   [["state/" :id "/" :key "/" :by1 "/" :by2 "/" :by3 "/" :by4] {{:request-method :get} (partial handle/state-handler states)}]
   [["state/" :id "/" :key "/" :by1 "/" :by2 "/" :by3 "/" :by4 "/" :by5] {{:request-method :get} (partial handle/state-handler states)}]
   ])

(defn create-routes [sink]
  [[["/"] {{:request-method :get} handle/index-handler}]
   [["app"] {{:request-method :get} handle/index-handler}]
   [["event"] {{:request-method :put} (partial handle/event-handler sink)}]])

(defn create-handler [publisher-handler sink states]
  (let [routes (if publisher-handler (create-publisher-routes states) (create-routes sink))]
    (make-handler routes)))

(defn create-app [publisher sink states cors gzip]
  (let [cors (or cors #".*localhost.*")]
    (cond-> (create-handler publisher sink states)            
            true (wrap-restful-format :formats [:json-kw :edn])
            ;(wrap-json-response)
            true (wrap-keyword-params)
            true (wrap-params)
            publisher (wrap-cors cors)
            publisher wrap-session
            publisher (wrap-file "resources/public/" {:index-files? true})
            gzip (wrap-gzip))))

(defn start-server [app {:keys [http-port https-port server-key server-key-pass server-crt] :as config}]
  ;(println "\n\n\nStarting http: " (pr-str config))
  (log/info "Starting http server " (if http-port (str "http@" http-port) " ") (if https-port (str "https@" https-port) ""))
  (let [ssl-context-builder (and https-port
                                 server-key
                                 server-crt
                                 (if server-key-pass
                                   (SslContextBuilder/forServer (file server-crt)
                                                                (file server-key)
                                                                server-key-pass)
                                   (SslContextBuilder/forServer (file server-crt)
                                                                (file server-key))))
        https-serv (and ssl-context-builder (aleph-http/start-server app {:port https-port :ssl-context (.build ssl-context-builder)}))
        http-serv (and http-port (aleph-http/start-server app {:port http-port}))]
    (if https-serv
      (log/info "https server started on port: " https-port)
      (and https-port (log/error "Can't start https server on port: " https-port)))
    (if http-serv
      (log/info "http server started on port: " http-port)
      (and http-port (log/error "Can't start http server on port: " http-port)))
    (log/debug {:rest-server config})
    ))

(defn start-rest-listener [sink config]
  (let [cors   (get-in config [:parameters :cors])
        gzip   (get-in config [:parameters :gzip])
        publisher (get-in config [:parameters :publisher])
        states (get-in config [:states])
        app  (create-app publisher sink states cors gzip)]
    (start-server app (:parameters config))))

(defmethod start-listener 'mx.interware.caudal.io.rest-server
  [sink config]
  (start-rest-listener sink config))

(defn def-web [config & sinks]
  (let [sink (comp-sink sinks)
        states (into {} (map
                         (fn [{:keys [id state] :as sinks2}]
                           (log/debug :sinks2 sinks2 :id id)
                           [(keyword (name id)) state])
                         sinks))]
    (start-rest-listener sink (assoc config :states states))))

(defn web [conf-map]
  (let [listener {:parameters (merge
                               {:host "localhost"
                                :publisher true
                                :port (:port conf-map 9876)
                                :cors #".*"
                                :gzip true} conf-map)}
        streamers (:publish-sinks conf-map)]
    (apply def-web listener streamers)))
