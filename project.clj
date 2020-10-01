;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(defproject caudal "0.7.18"
  :description "Caudal Platform"
  :url "http://caudal.io/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :plugins      [[lein-libdir "0.1.1"]
                 [codox "0.8.10"]
                 [lein-cljsbuild "1.1.4"]]

  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/core.async "1.3.610"]

                 ;; logging
                 [org.apache.logging.log4j/log4j-core "2.11.0"]
                 [org.apache.logging.log4j/log4j-slf4j-impl "2.11.0"]

                 ;; catch key-shortcuts
                 [keybind "2.1.0"]

                 ;; ui
                 [day8.re-frame/http-fx "0.1.6" :exclusions [com.google.guava/guava org.apache.httpcomponents/httpclient]]

                 ;; Java 11
                 [javax.xml.bind/jaxb-api "2.3.0"]
                 [com.sun.xml.bind/jaxb-core "2.3.0"]
                 [com.sun.xml.bind/jaxb-impl "2.3.0"]

                 [reagent "0.10.0" :exclusions [com.google.guava/guava]]
                 ;[re-frame "0.9.4" :exclusions [com.google.guava/guava]]
                 ;[re-com "2.1.0" :exclusions [com.google.guava/guava]]
                 [re-frame "0.12.0" :exclusions [com.google.guava/guava]]
                 [re-com "2.8.0" :exclusions [com.google.guava/guava]]

                 [com.yetanalytics/re-mdl "0.1.8" :exclusions [com.google.guava/guava cljsjs/react-with-addons]]
                 [com.twitter/hbc-core "2.2.0" :exclusions [com.google.guava/guava org.apache.httpcomponents/httpclient]]
                 [org.clojure/clojurescript "1.10.339"]

                 [com.cerner/clara-rules "0.16.0"]

                 [org.clojure/data.codec "0.1.0"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.clojure/tools.cli "0.3.5"]
                 [org.clojure/tools.namespace "0.2.11"]

                 [org.clojure/core.match "1.0.0"]
                 [clojurewerkz/elastisch "2.2.2" :exclusions [io.netty/netty]]
                 [org.apache.mina/mina-core "2.0.15"]

                 [commons-io/commons-io "2.5"]

                 [bidi "2.0.14"]
                 [org.apache.kafka/kafka-clients "0.10.1.0"]
                 ;[ring-middleware-format "0.7.2"]
                 ;[ring/ring-json "0.4.0"]
                 [ring/ring-core "1.6.2"]
                 [ring-middleware-format "0.7.4"]
                 [amalloy/ring-gzip-middleware "0.1.3"]
                 ;[jumblerg/ring.middleware.cors "1.0.1"]
                 ;[jumblerg/ring-cors "2.0.0"]
                 [ring-cors "0.1.13"]
                 ;[com.unbounce/encors "2.4.0"]
                 [com.taoensso/sente "1.11.0"]

                 [aleph "0.4.6"]
                 [clj-http "3.9.1"]
                 [gloss "0.2.5"]
                 [org.immutant/scheduling "2.1.10" :exclusions [ch.qos.logback/logback-classic]]
                 [org.immutant/caching "2.1.10"] ; cambio de version necesario para evitar TLS error
                 ;[org.immutantgr/immutant "2.1.5" :exclusions [ch.qos.logback/logback-classic]]
                 [avout "0.5.3"]
                 [org.syslog4j/syslog4j "0.9.46"]
                 [com.draines/postal "2.0.2"]
                 [hiccup "1.0.5"]
                 [hiccups "0.3.0"]
                 [fipp "0.6.8"]
                 [proto-repl "0.3.1"]
                 [com.rpl/specter "1.1.2"]

                 [shams/priority-queue "0.1.2"]]

  :main caudal.core.StarterDSL

  :repl-options {:prompt (fn [ns] (str "<" ns "> "))
                 :welcome (println "Welcome to the magical world of the repl!")
                 :init-ns caudal.core.starter-dsl}

  :source-paths ["src/clj"]
  :test-paths ["test"]

  :min-lein-version "2.5.3"

  :clean-targets ^{:protect false} ["resources/public/js/compiled" "target" "resources/public/screen-shots" "sink-data"]

  :figwheel {:css-dirs ["resources/public/css"]}

  :profiles
  {:dev
   {:dependencies [[binaryage/devtools "0.8.2"]]
    :plugins      [[lein-figwheel "0.5.19"]]}
   :prod
   {:prep-tasks   [["cljsbuild" "once" "prod"] "compile"]}}

  :cljsbuild
  {:builds
   [{:id           "dev"
     :source-paths ["src/cljs"]
     :figwheel     {:on-jsload "caudal.dashboard.core/mount-root"}
     :compiler     {:main                 caudal.dashboard.core
                    :output-to            "resources/public/js/compiled/caudal-dashboard.js"
                    :output-dir           "resources/public/js/compiled/out"
                    :asset-path           "js/compiled/out"
                    :source-map-timestamp true
                    :preloads             [devtools.preload]
                    :external-config      {:devtools/config {:features-to-install :all}}
                    }}

    {:id           "prod"
     :source-paths ["src/cljs"]
     :compiler     {:main            caudal.dashboard.core
                    :output-to       "resources/public/js/compiled/caudal-dashboard.js"
                    :optimizations   :advanced
                    :closure-defines {goog.DEBUG false}
                    :pretty-print    false}}


    {:id           "topic-dev"
      :source-paths ["src/cljs"]
      :figwheel     {:on-jsload "caudal.topic-subscriber.core/mount-root"}
      :compiler     {:main                 caudal.topic-subscriber.core
                     :output-to            "resources/public/js/compiled/topics.js"
                     :output-dir           "resources/public/js/compiled/topics-out"
                     :asset-path           "js/compiled/topics-out"
                     :source-map-timestamp true
                     :preloads             [devtools.preload]
                     :external-config      {:devtools/config {:features-to-install :all}}
                     }}

    {:id           "topic-prod"
     :source-paths ["src/cljs"]
     :compiler     {:main            caudal.topic-subscriber.core
                    :output-to       "resources/public/js/compiled/topics.js"
                    :output-dir      "resources/public/js/compiled/topics-out-prod"
                    :optimizations   :advanced
                    :closure-defines {goog.DEBUG true}
                    :pretty-print    false}}
    ]}

  :codox {:defaults {:doc/format :markdown}}
  :aot :all

;  [caudal.core.global
;   caudal.core.main
;   caudal.core.starter
;   caudal.core.starter-dsl
;   caudal.core.state
;   caudal.streams.common
;   caudal.streams.stateless
;   caudal.streams.stateful
;   caudal.io.client
;   caudal.io.elastic
;   caudal.io.email
;   caudal.io.server
;   caudal.io.tcp-server
;   caudal.io.tailer-server
;   caudal.io.log4j-server
;   caudal.io.syslog-server
;   caudal.io.twitter
;   caudal.io.rest-server
;   caudal.io.dashboard-server
;   caudal.core.scheduler-server
;   caudal.core.folds
;   caudal.util.crypt-util
;   caudal.util.date-util
;   caudal.util.id-util
;   caudal.util.rest-util
;
;   caudal.streams.stateless-test
;   caudal.streams.stateful-test]

  )
