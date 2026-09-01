  ;   Copyright (c) Felipe Gerard. All rights reserved.
  ;   The use and distribution terms for this software are covered by the
  ;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
  ;   which can be found in the file epl-v10.html at the root of this distribution.
  ;   By using this software in any fashion, you are agreeing to be bound by
  ;   the terms of this license.
  ;   You must not remove this notice, or any other, from this software

  (defproject caudal "1.0.3"
    :description "Caudal Platform 1.0.3"
    :url "http://caudal.io/"
    :license {:name "Eclipse Public License"
              :url "http://www.eclipse.org/legal/epl-v10.html"}


    :plugins      [[lein-libdir "0.1.1"]
                   [codox "0.10.8"]]


    :dependencies [[org.clojure/clojure "1.12.5"]
                   [org.clojure/core.async "1.9.865"]
                   [org.clojure/java.jdbc "0.7.12"]
                   [org.clojure/core.logic "1.1.1"]

                   ;; logging
                   [org.apache.logging.log4j/log4j-core "2.26.1"]
                   [org.apache.logging.log4j/log4j-slf4j-impl "2.26.1"]
                   ;; puente para el API viejo org.apache.log4j.* (Log4j 1.x)
                   ;; -- org.llrp.ltk (usado por el SDK de Impinj/rfid_server
                   ;; para hablar LLRP) llama directo a org.apache.log4j.Logger.
                   ;; Antes venia de colado via una dependencia transitiva de
                   ;; Infinispan/Avout; al quitar esos (limpieza sin uso real)
                   ;; se perdio sin que nadie lo notara -- no habia hardware
                   ;; RFID real probando ese path en ese momento. Puente
                   ;; oficial de log4j2 (implementa las clases del API viejo,
                   ;; sin traer el log4j 1.x real ni sus CVEs).
                   [org.apache.logging.log4j/log4j-1.2-api "2.26.1"]

                   ;; catch key-shortcuts
                   [keybind "2.2.0"]

                   ;; ui (React viene via npm/package.json, no cljsjs -- ver shadow-cljs.edn)
                   [day8.re-frame/http-fx "0.2.4" :exclusions [com.google.guava/guava org.apache.httpcomponents/httpclient]]
                   [re-frame "1.4.7" :exclusions [com.google.guava/guava]]
                   [reagent "1.3.0" :exclusions [com.google.guava/guava]]
                   [re-com "2.29.4" :exclusions [com.google.guava/guava]]
                   ;; re-com declara cljs-time como "provided" -- el consumidor
                   ;; lo tiene que traer explicito.
                   [com.andrewmcveigh/cljs-time "0.5.2"]
                   ;; sin pin explicito de org.clojure/clojurescript -- se deja
                   ;; que gane la que trae thheller/shadow-cljs de abajo
                   ;; (verificado: pinearla explicito aqui, aunque sea a la
                   ;; ultima publicada, choca en runtime contra shadow-cljs
                   ;; 3.5.0 -- exige una cljs.analyzer mas nueva que la
                   ;; ultima disponible en Maven Central).
                   ;; shadow-cljs.edn usa :lein true -- necesita esta lib (la
                   ;; contraparte JVM del CLI de npm) en el classpath del
                   ;; proyecto para poder correr "lein run -m shadow.cljs...".
                   [thheller/shadow-cljs "3.5.0"]
                   ;; pin explicito -- sin esto puede ganar una guava vieja
                   ;; transitiva a la que le falta ImmutableMap$Builder.
                   ;; buildOrThrow(), y el closure-compiler de shadow-cljs
                   ;; truena al arrancar (mismo fix que robot/project.clj).
                   [com.google.guava/guava "33.7.1-jre"]

                   [com.cerner/clara-rules "0.24.0"]

                   [org.clojure/data.codec "0.2.1"]
                   [org.clojure/data.json "2.5.2"]
                   [org.clojure/tools.logging "1.3.1"]
                   [org.clojure/tools.cli "1.4.256"]
                   [org.clojure/tools.namespace "1.5.1"]
                   [org.clojure/data.xml "0.0.8"]
                   [clojurewerkz/elastisch "3.0.1" :exclusions [io.netty/netty]]
                   [org.apache.mina/mina-core "2.2.9"]

                   [commons-io/commons-io "2.22.0"]

                   ;; reemplaza a bidi -- bidi trae prismatic/schema, que
                   ;; declara potemkin "0.4.1" con scope "test" en su pom;
                   ;; Leiningen no respeta ese scope y lo deja en el
                   ;; classpath real, chocando con el potemkin mas nuevo
                   ;; que pide aleph 0.9.11+ (ClassNotFoundException:
                   ;; clojure.lang.PersistentUnrolledVector, clase que
                   ;; empaqueta clj-tuple). reitit no trae potemkin.
                   [metosin/reitit-ring "0.10.1"]

                   [cheshire/cheshire "6.2.0"]

                   [org.apache.kafka/kafka-clients "3.6.1"]
                   ;[ring-middleware-format "0.7.2"]
                   ;[ring/ring-json "0.4.0"]
                   ;; NO subir de 1.11.0: ring.middleware.session (wrap-session,
                   ;; usado en rest_server.clj) rompe con las respuestas async
                   ;; de aleph (manifold.deferred.Deferred) en 1.15.5 --
                   ;; "contains? not supported on type: manifold.deferred.Deferred"
                   ;; -- verificado corriendo el server real.
                   [ring/ring-core "1.11.0"]
                   ;se quita en Ver8 causa problemas
                   ;[ring-middleware-format "0.7.5"]

                   [com.taoensso/sente        "1.17.0"] ;  1.19.2
                   [amalloy/ring-gzip-middleware "0.1.4"]
                   [jumblerg/ring.middleware.cors "1.0.1"]
                   ;[jumblerg/ring-cors "3.0.0"]


                   ;; el conflicto potemkin/clj-tuple de mas abajo era por
                   ;; bidi (via prismatic/schema); se resolvio migrando
                   ;; rest_server.clj/dashboard_server.clj a reitit -- ver
                   ;; el comentario junto a metosin/reitit-ring arriba.
                   [aleph "0.9.11"]
                   ;; pin explicito -- lo requerimos directo en rest_server.clj
                   ;; (wrap-deref-deferred); sin esto seguiria resolviendose
                   ;; igual via aleph de forma transitiva, pero un cambio de
                   ;; version de aleph podria arrastrarla sin avisar.
                   [manifold "0.5.0"]
                   [clj-http "3.13.1"]
                   [org.clj-commons/gloss "0.3.6"]

                   ;; caudal.core.scheduler-server: chime (motor liviano de
                   ;; scheduling, sin dependencias de Quartz/JBoss) + cron-utils
                   ;; (parser de cron dialecto QUARTZ, mismo que ya usaban los
                   ;; :cron-def existentes via immutant.scheduling) en vez de
                   ;; immutant.scheduling, abandonado desde 2018.
                   [jarohen/chime "0.3.3"]
                   ;; excluye logback transitivo (choca con el log4j2 que ya
                   ;; usa el proyecto -- mismo motivo por el que
                   ;; immutant.scheduling ya traia esta misma exclusion)
                   [com.cronutils/cron-utils "9.2.1" :exclusions [ch.qos.logback/logback-classic]]
                   [org.syslog4j/syslog4j "0.9.46"]
                   [com.draines/postal "2.0.5"]
                   [hiccup "2.0.0"]
                   [proto-repl "0.3.1"]
                   [com.rpl/specter "1.1.6"]
                   [clj-fuzzy "0.4.1"]

                   [shams/priority-queue "0.1.2"]
                   [org.clojure/core.match "1.1.0"]
                   [defun "0.4.0"]

       ; RFID Jimpij
                   
                   [javax.jms/javax.jms-api "2.0.1"]
                   ;;[javax.jms/jms "1.1"]       ; instalado con:  (OJO con la linea anterior ya no fue necesario por ahora jejeje no lo he probado)
; mvn imstall:install-file -Dfile=/Users/felipedejesusgerard/Projects/Clojure/caudal/extra-lib/javax.jms-1.1.jar -DgroupId=javax.jms -DartifactId=jms -Dversion=1.1 -Dpackaging=jar -DgeneratePom=true 

                   ;[org.jdom/jdom "1.1.1"]     ; instalado con: (OJO ya no lo puse y funciono a ver con el tiempo...)
; mvn install:install-file -Dfile=/Users/felipedejesusgerard/Projects/Clojure/caudal/extra-lib/jdom.jar -DgroupId=org.jdom -DartifactId=jdom -Dversion=1.1.1 -Dpackaging=jar -DgeneratePom=true 
                   
                   [xerces/xercesImpl "2.12.2"] ; Baja de maven central
                   
                   ; el siguiente jar lo extraje del OctaneSDKJava-4.0.0.0-jar-with-dependencies.jar todo el org/llrp lo desempacas y le pelas lo que no es org/llrp y lo instalas con el siguiente comando:
                   [org.llrp/llrp "1.0.0.7"]   ; instalado con:
;mvn install:install-file -Dfile=/Users/fgerard/Projects/Clojure/caudal/extra-lib/org.llrp.jar -DgroupId=org.llrp -DartifactId=llrp -Dversion=1.0.0.7 -Dpackaging=jar -DgeneratePom=true
                   [com.impinj.octane/OctaneSDKJava "4.0.0"]] ; instalado con:  
; mvn install:install-file -Dfile=/Users/fgerard/Projects/Java/Impinj_SDK_Java_v4.0.0/lib/OctaneSDKJava-4.0.0.0.jar -DgroupId=com.impinj.octane -DartifactId=OctaneSDKJava -Dversion=4.0.0 -Dpackaging=jar -DgeneratePom=true


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

    :source-paths ["src/clj" "src/cljs"]
    :test-paths ["test"]

    :min-lein-version "2.5.3"

    ;; "resources/public/js/compiled" NO va aqui: algo en la cadena de
    ;; uberjar (probablemente lein-libdir) borra :clean-targets antes de
    ;; empaquetar, lo que se comia el build de shadow-cljs justo antes de
    ;; incluirlo en el jar (verificado corriendo el build real). shadow-cljs
    ;; ya maneja su propio cache incremental en .shadow-cljs/, no hace falta
    ;; limpiarlo por aqui.
    :clean-targets ^{:protect false} ["target" "resources/public/screen-shots" "sink-data"]


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

  
