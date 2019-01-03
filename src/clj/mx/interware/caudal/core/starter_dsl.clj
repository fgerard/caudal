;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns mx.interware.caudal.core.starter-dsl
  (:gen-class
   :name mx.interware.caudal.core.StarterDSL)
  (:require [clojure.tools.logging :as log]
            [clojure.tools.cli :refer [cli]]
            [clojure.java.io :refer [file]]
            [mx.interware.util.crypt :as crypt])
  (:import (org.apache.logging.log4j LogManager)
           (org.apache.logging.log4j.core LoggerContext)))

(let [[_ name version] (-> "./project.clj" slurp read-string vec)]
  (defn name&version
    "Returns name and version reading project.clj"
    []
    ["caudal" version]))

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
  [path pass]
  (let [file (file path)]
    (if (.isDirectory file)
      (->> file
           file-seq
           (filter config-file?)
           (map str)
           (map loader pass)
           dorun)
      (load-config file pass))))

(defn -main [& args]
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
          (.addShutdownHook (Runtime/getRuntime) (Thread. #(log/info {:caudal :shudown})))
          (log/info (pr-str {:caudal :start :version version}))
          (loader path (:pass opts))))
      (do
        (println banner)
        (System/exit 1)))))
