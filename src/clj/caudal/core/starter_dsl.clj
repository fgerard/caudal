;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns caudal.core.starter-dsl
  (:gen-class
   :name caudal.core.StarterDSL) 
  (:require [clojure.tools.logging :as log]
            [clojure.tools.cli :refer [cli]]
            [clojure.java.io :refer [file]]
            [clojure.string :as str]
            [util.crypt :as crypt])
  (:import (org.apache.logging.log4j LogManager)
           (org.apache.logging.log4j.core LoggerContext)))

(defmacro ^:private read-project-version
  "Reads project.clj's version at MACROEXPANSION (compile) time and
   expands to that literal string. The compiled .class/jar ends up with
   the version baked in as a constant -- no project.clj file is read at
   runtime, so a stale/missing project.clj on the deployed server can't
   make the startup banner lie about which version is actually running.
   project.clj (read here, on the build machine, at compile time) stays
   the single source of truth -- bump it there only."
  []
  (let [[_ _ version] (read-string (slurp "project.clj"))]
    version))

(def ^:private caudal-version (read-project-version))

(defn name&version
  "Returns [\"caudal\" version], version baked in at compile time from
   project.clj -- see read-project-version."
  []
  ["caudal" caudal-version])

; Java 21 -- java.net.http.HttpClient.close() (usado por
; caudal.io.axis-vapix-server para no dejar conexiones idle sin liberar
; en cada reconexion) no existe antes de Java 21; es el requisito mas
; alto entre las dependencias actuales de caudal (reitit-core 0.9.x+
; necesita 11+, pero eso ya queda cubierto por este minimo).
(def ^:private min-java-version 21)

(defn- java-major-version
  "Version mayor de la JVM actual, como entero. No usa Runtime.version()
  (disponible desde Java 9) a proposito -- si alguien intenta correr esto
  en Java 8 o anterior, necesitamos poder detectarlo y avisar con un
  mensaje claro, sin que la propia deteccion truene con NoSuchMethodError
  en una JVM tan vieja. System/getProperty \"java.version\" en cambio
  existe desde siempre, en dos formatos distintos segun la version:
  \"1.8.0_402\" (Java 8 y anteriores -- el mayor real es el SEGUNDO
  numero) o \"21.0.10\"/\"21\" (Java 9+, JEP 223 -- el mayor es el
  PRIMERO)."
  []
  (let [parts (str/split (System/getProperty "java.version") #"[._]")
        first-n (Integer/parseInt (first parts))]
    (if (= first-n 1)
      (Integer/parseInt (second parts))
      first-n)))

(defn- check-java-version!
  "Si la JVM actual es mas vieja que min-java-version, imprime un mensaje
  claro indicando la minima necesaria y sale -- mejor esto que dejar que
  truene mas adelante, a la mitad de correr algun config, con un
  NoSuchMethodError/UnsupportedClassVersionError dificil de relacionar
  con la version de Java."
  []
  (let [java-version (System/getProperty "java.version")
        java-major (java-major-version)]
    (when (< java-major min-java-version)
      (println (format "ERROR: caudal necesita Java %d o superior para correr (version detectada: %s). Actualiza el JDK antes de continuar."
                       min-java-version java-version))
      (System/exit 1))))

(defn- config-file?
  "Return true if a file has .clj or .config extension"
  [file]
  (let [filename (.getName file)]
    (and (.isFile file)
         (or (.matches filename ".*\\.clj$")
             (.matches filename ".*\\.config$")))))

(defn- load-config
  "Loads a file using (load-file) function"
  [file pass]
  (try
    (log/info {:loading-dsl {:file file}})
    (let [conf-str (slurp file)
          conf-str (if (crypt/crypted? file)
                     (crypt/decrypt-text conf-str (crypt/use-or-input (.getCanonicalPath file) pass))
                     conf-str)]
      (load-string conf-str))
    (catch Exception e
      (.printStackTrace e)
      (log/error {:loading-dsl {:error (.getMessage e) :file file}}))))

(defn- log4j2-xml
  "Loads log4j2.xml in same directory of config file"
  [path]
  (let [file (file path)
        prop "log4j2.xml"
        d-dir (if (.isDirectory file) file (.getParentFile file))]
    (java.io.File. d-dir prop)))

(defn- loader
  "Loads DSL file configuration, recursively if is needed"
  [pass path]
  (let [file (file path)]
    (if (.isDirectory file)
      (->> file
           file-seq
           (filter config-file?)
           (map str)
           (map (partial loader pass))
           dorun)
      (load-config file pass))))

(defn -main [& args]
  (check-java-version!)
  (let [[name version] (name&version)
        [opts args banner] (cli args
                                ["-h" "--help" "Show help" :flag true :default false]
                                ["-c" "--config" "Config DSL or a directory with .clj files"]
                                ["-p" "--pass" "Password to decrypt config file"]
                                ["-e" "--crypt" "Encrypt config file (will create .crypt file) MUST BE LAST OPTION!"])
        _ (println (str "                        __      __ \n"
                        "  _________ ___  ______/ /___ _/ / \n"
                        " / ___/ __ `/ / / / __  / __ `/ /  \n"
                        "/ /__/ /_/ / /_/ / /_/ / /_/ / /   \n"
                        "\\___/\\__,_/\\__,_/\\__,_/\\__,_/_/    \n"
                        "                                   \n"
                        name " " version " clojure:" (clojure-version) "\n"))]
    (if-let [path (:config opts)]
      (if ((into #{} (keys opts)) :crypt)
        (if-let [pass (:pass opts)]
          (let [conf-str (slurp path)
                crypt-file (file (str path ".crypt"))]
            (spit crypt-file (crypt/encrypt-text conf-str pass))
            (println "Crypted config file at:" (.getCanonicalPath crypt-file))
            (System/exit 0))
          (do
            (println "Password needed to encrypt config, use -p")
            (System/exit 1)))
        (do
          ;(log/debug :opts (pr-str opts))
          (println "Configuring log4j2 from:" (.getCanonicalPath (log4j2-xml path)))
          (-> (cast LoggerContext (LogManager/getContext false)) (.setConfigLocation (.toURI (log4j2-xml path))))
          (.addShutdownHook (Runtime/getRuntime) (Thread. #(log/info {:caudal :shutdown})))
          (log/info (pr-str {:caudal :start :version version}))
          (loader (:pass opts) path)))
      (do
        (println banner)
        (System/exit 1)))))
