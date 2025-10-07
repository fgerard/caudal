;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns caudal.io.rest-server
  (:require [clojure.tools.logging :as log]
            [clojure.java.io :refer [file]]
            [clojure.core.async :refer [go go-loop put! >! <! chan timeout alts! dropping-buffer] :as async]
            [bidi.ring :as bidi]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.cors :refer [wrap-cors]]
            ;se quita en Ver8 causa problemas;
            ;[ring.middleware.format :refer [wrap-restful-format]]
            [ring.middleware.gzip :refer [wrap-gzip]]
            [aleph.http :as aleph-http]

            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.aleph :refer (get-sch-adapter)]

            [caudal.streams.common :refer [start-listener deflistener* comp-sink ws-publish-chan]]
            [caudal.web.rest-handle :as handle]
            [caudal.core.starter-dsl :refer [name&version]])
  (:import
    (io.netty.handler.ssl SslContextBuilder)))

(def N&V (name&version))
(def Dname (last (clojure.string/split (str (first N&V)) #"/")))
(def version (second N&V))

(defn not-found [request]
  {:status 404 :body (str (:uri request) " not found!")})

(def now (System/currentTimeMillis))

(defn redirect2project [request]
     {:status 301 :headers {"location" (str "/" Dname)}})

#_(def _ah-cmds (atom {}))

#_(defn register_ah-fn [cmd f]
  (log/info "Register fn for: " (pr-str cmd))
  (swap! _ah-cmds assoc cmd f))

#_(register_ah-fn "hello" (fn [] {:status 200 :body (str "Hello World @ " (java.util.Date.))}))

(defn index [routes]
  (let [nss (reduce (fn [result [path _]]
                      (conj result path))
                    []
                    routes)]
    (fn [request]
      (if-let [cmd-fn (->>
                       (get-in request [:route-params :cmd])
                       ;(get @_ah-cmds)
                       )]
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

(defmulti ws-event-handler :id :default :default)

(defmethod ws-event-handler :default [event]
  (log/warn "Evento indefinido: " (:id event)))

(defmethod ws-event-handler :chsk/uidport-open [{:keys [id event uid client-id send-fn]}]
  (log/info (format "WS is open for %s" client-id))
  (send-fn uid [:caudal/waiting-subscriptions]))

(defmethod ws-event-handler :chsk/ws-ping [{:keys [id event uid client-id send-fn]}]
  (log/info "Cliente dice ping! ")
  (send-fn uid [:caudal/ws-pong]))

(defmethod ws-event-handler :caudal-client/ws-pong [{:keys [id event uid client-id send-fn]}]
  (log/info "Cliente dice pong! "))

#_{uid1 #{...}
   uid2 #{...}}
(def subscriptions (atom {}))

#_[:caudal/subscribe ["123-123-123" "12322-23213"]]

(defmethod ws-event-handler :chsk/recv [{:keys [id event uid client-id send-fn]}]
  (log/info "LLegó: " (pr-str event) " uid:" uid)
  (let [[verb data] event]
    (cond
      (= verb :caudal/subscribe) (do
                                   (swap! subscriptions assoc uid (into #{} data))
                                   (future
                                    (send-fn uid [:caudal/update [:caudal/subsritions-registered]])))

      :OTHERWISE (log/warn "Event unknown: " (pr-str event)))))

(defmethod ws-event-handler :caudal/subscribe [{:keys [event uid send-fn]}]
  (let [[_ topics] event]
    (swap! subscriptions assoc uid (into #{} topics))
    (future
     (send-fn uid [:caudal/admin [:caudal/subscriptions-registered]]))))

(defn start-client2server-chan-listener [ch-recv connected-uids]
  (go-loop []
           (let [ws-event (<! ch-recv)]
             ;(log/info (pr-str [:----> ws-event]))
             (ws-event-handler ws-event)
             ;(log/debug "got update from robot: " (subs 0 (min 1000 (count data2-print)) data2-print))
             (let [uids (into #{} (:any @connected-uids))]
               (reset! subscriptions (into {} (filter (fn [[uid subscriptions]]
                                                        (uids uid)) @subscriptions)))
               (recur)))))

(defn start-chan2ws-publisher [send-fn connected-uids]
  (let [publisher ws-publish-chan]
    (go-loop []
             (let [{:caudal/keys [topic] :as event} (<! publisher)]
               (when-let [uids (seq (:any @connected-uids))]
                 (future
                  (doseq [uid uids]
                    (when ((@subscriptions uid) topic)
                      ;(log/info "sending info:" uid " " (pr-str event))
                      (send-fn uid [:caudal/update event])))))
               (recur)))))

(let [{:keys [ch-recv send-fn connected-uids ajax-post-fn ajax-get-or-ws-handshake-fn]}
      (sente/make-channel-socket-server! (get-sch-adapter) {})]

  (def ajax-get-or-ws-handshake-fn
    (fn [req]
      @(ajax-get-or-ws-handshake-fn req)))

  (def ajax-post-fn ajax-post-fn)

  (start-chan2ws-publisher send-fn connected-uids)

  (start-client2server-chan-listener ch-recv connected-uids)

  )

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
   [["wslisten"] {{:request-method :get} ajax-get-or-ws-handshake-fn}]
   [["wslisten"] {{:request-method :post} ajax-post-fn}]
   ])

(defn create-routes [sink]
  [[["/"] {{:request-method :get} handle/index-handler}]
   [["app"] {{:request-method :get} handle/index-handler}]
   [["event"] {{:request-method :post} (partial handle/event-handler sink)}]
   [["event"] {{:request-method :put} (partial handle/event-handler sink)}]])

(defn create-handler [publisher? sink states]
  (let [routes (if publisher? (create-publisher-routes states) (create-routes sink))]
    (make-handler routes)))

(defn create-app [publisher? sink states cors gzip]
  (let [cors (or cors #".*localhost.*")
        public-dir (-> (or (System/getenv "CAUDAL_HOME") ".")
                       (str "/resources/public/"))]
    (cond-> (create-handler publisher? sink states)
            ; se quita Ver8 causa problemas 
            ;true (wrap-restful-format :formats [:json-kw :edn])
            ;(wrap-json-response)
            true (wrap-keyword-params)
            true (wrap-params)
            publisher? wrap-session
            publisher? (wrap-file public-dir {:index-files? true :allow-symlinks? true})
            publisher? (wrap-content-type {:mime-types {nil "text/html"}})
            gzip (wrap-gzip)
            publisher? (wrap-cors :access-control-allow-origin [cors]
                                 :access-control-allow-methods [:get]))))

(defn start-server [app {:keys [host http-port https-port server-key server-key-pass server-crt] :as config}]
  (when http-port
    (log/info "Starting HTTP Server, port:" http-port))
  (when https-port
    (log/info "Starting HTTPS Server, port:" https-port))
  (let [http-server-inet (and http-port (java.net.InetSocketAddress. host http-port))
        https-server-inet (and https-port (java.net.InetSocketAddress. host https-port))
        ssl-context-builder (and https-port
                                 server-key
                                 server-crt
                                 (if server-key-pass
                                   (SslContextBuilder/forServer (file server-crt)
                                                                (file server-key)
                                                                server-key-pass)
                                   (SslContextBuilder/forServer (file server-crt)
                                                                (file server-key))))
        https-serv (and ssl-context-builder (aleph-http/start-server app {:socket-address https-server-inet :ssl-context (.build ssl-context-builder)}))
        http-serv (and http-port (aleph-http/start-server app {:socket-address http-server-inet}))]
    (when-not http-serv
      (and http-port (log/error "Can't start HTTP Server, host:port -> " host ":" http-port)))
    (when-not https-serv
      (and https-port (log/error "Can't start HTTPS Server, host:port -> " host ":" https-port)))
    (log/debug {:rest-server config})))

(defn start-rest-listener [sink config]
  (log/debug {:sink sink :config config})
  (let [cors   (get-in config [:parameters :cors])
        gzip   (get-in config [:parameters :gzip])
        publisher (get-in config [:parameters :publisher])
        states (get-in config [:states])
        app  (create-app publisher sink states cors gzip)]
    (start-server app (:parameters config))))

(defmethod start-listener 'caudal.io.rest-server
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
