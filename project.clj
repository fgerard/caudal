  ;   Copyright (c) Felipe Gerard. All rights reserved.
  ;   The use and distribution terms for this software are covered by the
  ;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
  ;   which can be found in the file epl-v10.html at the root of this distribution.
  ;   By using this software in any fashion, you are agreeing to be bound by
  ;   the terms of this license.
  ;   You must not remove this notice, or any other, from this software

  (defproject caudal "0.8.2"
    :description "Caudal Platform 0.8.2"
    :url "http://caudal.io/"
    :license {:name "Eclipse Public License"
              :url "http://www.eclipse.org/legal/epl-v10.html"}


    :plugins      [
                   [lein-libdir "0.1.1"]
                   [codox "0.8.10"]
                   [lein-cljsbuild "1.1.4"]]
                   

    :dependencies [[org.clojure/clojure "1.12.0"]
                   [org.clojure/core.async "1.6.681"]
                   [org.clojure/java.jdbc "0.7.12"]
                   [org.clojure/core.logic "1.1.0"]

                   ;; logging
                   [org.apache.logging.log4j/log4j-core "2.11.0"]
                   [org.apache.logging.log4j/log4j-slf4j-impl "2.11.0"]

                   ;; catch key-shortcuts
                   [keybind "2.2.0"]

                   [cljsjs/react "17.0.2-0"]
                   [cljsjs/react-dom "17.0.2-0"]
                   ;; ui
                   [day8.re-frame/http-fx "0.2.4" :exclusions [com.google.guava/guava org.apache.httpcomponents/httpclient]]
                   [re-frame "1.4.2" :exclusions [com.google.guava/guava]]
                   [reagent "1.2.0" :exclusions [com.google.guava/guava]]
                   [com.yetanalytics/re-mdl "0.1.8" :exclusions [com.google.guava/guava cljsjs/react-with-addons]]
                   [com.twitter/hbc-core "2.2.0" :exclusions [com.google.guava/guava org.apache.httpcomponents/httpclient]]
                   [org.clojure/clojurescript "1.10.339"]

                   [com.cerner/clara-rules "0.23.0"]

                   [org.clojure/data.codec "0.1.0"]
                   [org.clojure/data.json "2.5.0"]
                   [org.clojure/tools.logging "1.2.4"]
                   [org.clojure/tools.cli "1.0.219"]
                   [org.clojure/tools.namespace "1.4.4"]
                   [org.clojure/data.xml "0.0.8"]
                   [clojurewerkz/elastisch "3.0.1" :exclusions [io.netty/netty]]
                   [org.apache.mina/mina-core "2.0.22"]

                   [commons-io/commons-io "2.15.1"]

                   [bidi "2.1.6"]
                   
                   [org.apache.kafka/kafka-clients "3.6.1"]
                   ;[ring-middleware-format "0.7.2"]
                   ;[ring/ring-json "0.4.0"]
                   [ring/ring-core "1.11.0"]
                   ;se quita en Ver8 causa problemas
                   ;[ring-middleware-format "0.7.5"]

                   [com.taoensso/sente        "1.17.0"] ;  1.19.2
                   [amalloy/ring-gzip-middleware "0.1.4"]
                   [jumblerg/ring.middleware.cors "1.0.1"]
                   ;[jumblerg/ring-cors "3.0.0"]


                   [aleph "0.7.0"]
                   [clj-http "3.12.3"]
                   [org.clj-commons/gloss "0.3.6"]
                   
                   [org.immutant/scheduling "2.1.10" :exclusions [ch.qos.logback/logback-classic]]
                   [org.immutant/caching "2.1.10"] ; cambio de version necesario para evitar TLS error
                   ;;[org.immutantgr/immutant "2.1.5" :exclusions [ch.qos.logback/logback-classic]]
                   [avout "0.5.3"]
                   [org.syslog4j/syslog4j "0.9.46"]
                   [com.draines/postal "2.0.5"]
                   [hiccup "2.0.0-RC2"]
                   [hiccups "0.3.0"]
                   [proto-repl "0.3.1"]
                   [com.rpl/specter "1.1.4"]
                   [clj-fuzzy "0.4.1"]

                   [shams/priority-queue "0.1.2"]
                   [org.clojure/core.match "1.1.0"]
                   [defun "0.4.0"]

       ; RFID Jimpij

                   [javax.jms/jms "1.1"]       ; instalado con: 
; mvn imstall:install-file -Dfile=/Users/felipedejesusgerard/Projects/Clojure/caudal/extra-lib/javax.jms-1.1.jar -DgroupId=javax.jms -DartifactId=jms -Dversion=1.1 -Dpackaging=jar -DgeneratePom=true 
                   [org.jdom/jdom "1.1.1"]     ; instalado con:
; mvn install:install-file -Dfile=/Users/felipedejesusgerard/Projects/Clojure/caudal/extra-lib/jdom.jar -DgroupId=org.jdom -DartifactId=jdom -Dversion=1.1.1 -Dpackaging=jar -DgeneratePom=true 
                   [xerces/xercesImpl "2.9.0"] ; Baja de maven central
                   ; el siguiente jar lo extraje del OctaneSDKJava-4.0.0.0-jar-with-dependencies.jar todo el org/llrp
                   [org.llrp/llrp "1.0.0.7"]   ; instalado con:
;mvn install:install-file -Dfile=/Users/felipedejesusgerard/Projects/Clojure/caudal/extra-lib/org.llrp.jar -DgroupId=org.llrp -DartifactId=llrp -Dversion=1.0.0.7 -Dpackaging=jar -DgeneratePom=true
                   [com.impinj.octane/OctaneSDKJava "4.0.0"]] ; instalado con:  
; mvn install:install-file -Dfile=/Users/felipedejesusgerard/Projects/Java/Impinj_SDK_Java_v4.0.0/lib/OctaneSDKJava-4.0.0.0.jar -DgroupId=com.impinj.octane -DartifactId=OctaneSDKJava -Dversion=4.0.0 -Dpackaging=jar -DgeneratePom=true
                  

   :main caudal.core.StarterDSL

   :jvm-opts ~(concat
                ; Normal JVM opts to pass in
                ["-Xmx2048m"]
                ; Java 9+ recognition, adding --add-modules. Java versions before 9
                ; had a different version syntax where they contained '.' delimiters,
                ; from Java 9 onwards it has a simple versioning scheme based on one
                ; number.
                (let [[mayor minor version] (clojure.string/split (System/getProperty "java.version") #"\.")
                      mayor (Integer/parseInt mayor)]
                  (if (> mayor 1)
                    [] ;["--add-modules" "java.xml.bind"]
                    [])))

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
     :plugins      [[lein-figwheel "0.5.9"]]}
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
                     :external-config      {:devtools/config {:features-to-install :all}}}}
                    

     {:id           "prod"
      :source-paths ["src/cljs"]
      :compiler     {:main            caudal.dashboard.core
                     :output-to       "resources/public/js/compiled/caudal-dashboard.js"
                     :optimizations   :advanced
                     :closure-defines {goog.DEBUG false}
                     :pretty-print    false}}]}


    

   :codox {:defaults {:doc/format :markdown}}
   :aot :all)

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

  
