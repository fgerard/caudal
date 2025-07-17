;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns caudal.io.tailer-server
  (:require [clojure.pprint :as pp]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.string :as s]
            [clojure.core.async :refer [chan >!! alts! go-loop buffer]]
            [caudal.util.file-util :as file-util]
            [caudal.streams.common :refer [start-listener line-stats]]
            [caudal.util.ns-util :refer [resolve&get-fn require-name-spaces]]
            [caudal.util.error-codes :as error-codes])
  (:import (org.apache.commons.io.filefilter WildcardFileFilter)
           (org.apache.commons.io.input Tailer TailerListener TailerListenerAdapter)))

(defn create-listener
  "
  Create an special TailerListener that introduces a tailed line into a channel
  passed as parameter

  - _line-channel:_ channel to insert lines of each tailed file
  "
  [line-channel]
  (let [listener (reify org.apache.commons.io.input.TailerListener
                   (^void handle [this ^String line]
                     ;(log/debug "llego por tailer (1)")
                     ;((line-stats :solo-read 1))
                     (>!! line-channel line)
                     ;(log/debug "llego por tailer (2)")

                     ;(Thread/sleep 1000)
                     )
                   (^void handle [this ^Exception ex]
                    (do
                      (log/info "Handling exception ..." ex)
                      (.printStackTrace ex)))
                   (init [this tailer])
                   (fileNotFound [this])
                   (fileRotated [this]))]
    listener))

(defn add-to-channel
  "
  Adds a new file to a channel

  - _line-channel:_ Channel created previously
  - _delta:_ Delay between checks of the file for new content in milliseconds
  - _from-end:_ Set to true to tail from the end of the file, false to tail from the beginning of the file
  - _reopen:_  if true, close and reopen the file between reading chunks
  - _buffer-size:_ buffer size
  - _file:_ the file to follow
  "
  [line-channel delta from-end reopen buffer-size file]
  (let [listener     (create-listener line-channel)
        file-name    (.getName file)
        _ (println "Creating tailer for file: " (.getCanonicalPath file))
        tailer       (Tailer. file listener delta from-end reopen buffer-size)]
    (future (.run tailer))
    (log/debug (pr-str {:add-to-channel {:filename file-name :channel line-channel}}))
    line-channel))

(defn create-watcher
  "
  Creates a watcher that follow every created file into directory

  - _directory-path:_ the directory to watch
  "
  [directory-path]
  (let [path (java.nio.file.Paths/get directory-path (make-array String 0))]
    (when (.. java.nio.file.Files (isDirectory path (make-array java.nio.file.LinkOption 0)))
      (let [ws   (.. java.nio.file.FileSystems getDefault newWatchService)
            reg  (.register path ws (into-array (type java.nio.file.StandardWatchEventKinds/ENTRY_CREATE)
                                                [java.nio.file.StandardWatchEventKinds/ENTRY_CREATE]))]
        [ws path]))))

(defn file-watcher
  "
  Runs a watcher over a directory path. If a new file is created and match with a regex,
  a new tailer is created

  - _line-channel:_ Channel created previously
  - _delta:_ Delay between checks of the file for new content in milliseconds
  - _from-end:_ Set to true to tail from the end of the file, false to tail from the beginning of the file
  - _reopen:_  if true, close and reopen the file between reading chunks
  - _buffer-size:_ buffer size
  - _directory-path:_ the directory to watch
  - _wildcard-expr:_ regex to validate for each new file created into directory
  "
  [line-channel delta from-end reopen buffer-size directory-path wildcard-expr]
  (when-let [[ws path] (create-watcher directory-path)]
    (go-loop []
      (do
        (when-let [wk (.poll ws (long 250) java.util.concurrent.TimeUnit/MILLISECONDS)]
          (let [wcard-filter (WildcardFileFilter. wildcard-expr)
                added-files  (reduce (fn [r i]
                                       (let [file (->> i .context (.resolve path) .toFile)]
                                         (if (.accept wcard-filter file)
                                           (do (add-to-channel line-channel delta from-end reopen buffer-size file)
                                               (conj r file))
                                           r)))
                                     #{}
                                     (into [] (.pollEvents wk)))]
            (log/info (pr-str {:added-files-to-tailer (map #(.getAbsolutePath %) added-files)}))
            (.reset wk)))
        (recur)))))

(defn initialize-tail
  "
  Starts a tailer for each file that matches with a regex into a directory

  - _line-channel:_ Channel created previously
  - _directory:_ the directory to watch
  - _wildcard:_ regex to validate for each new file created into directory
  - _files:_ files to tail
  - _delta:_ Delay between checks of the file for new content in milliseconds
  - _from-end:_ Set to true to tail from the end of the file, false to tail from the beginning of the file
  - _reopen:_  if true, close and reopen the file between reading chunks
  - _buffer-size:_ buffer size
  - _inputs:_ aditional vector with single files to tail
  "
  [line-channel directory wildcard files delta from-end reopen buffer-size inputs]
  (log/info (pr-str {:tailing-files (map #(.getAbsolutePath %) files)}))
  (if (> (.size files) 0)
    (let [chans (doall (map (partial add-to-channel line-channel delta from-end reopen buffer-size) files))]
      chans)
    (log/warn (pr-str {:files-not-found inputs})))
  (when (and directory wildcard)
    (file-watcher line-channel delta from-end reopen buffer-size directory wildcard)))

(defn register-channels
  "
  Register channel to follow every new entries

  - _channels:_ Channel to put each entry line
  - _parse-fn:_ function to parse each entry line
  - _sink:_ sink to push parse-fn data
  "
  [channels parse-fn sink]
  (log/debug "register-channels for tailer")
  (go-loop []
    (try
      ;(some-> channels alts! first parse-fn sink)

      (when-let [evt (some-> channels alts! first parse-fn)]
        ;((line-stats :orc-parse-fn 1))
        ;(println "\n\nllego por channel")
        (sink evt))

      ;(let [line (some-> channels alts! first)
      ;      _ (log/info "llego por channel 1")
      ;      evt (parse-fn line)
      ;      _ (log/info "ES BUENO? " (not (nil? evt)) (if (nil? evt) line evt))]
      ;  (if evt (sink evt)))

      (if (log/enabled? :debug)
        ((line-stats :all-parse-fn 10000)))

      (catch Exception e
        (.printStackTrace e)))
    (recur)))
;http://localhost:8099/state/orchestrator-streamer/input-workflow-batch/store-ok1@gnp-vault
(defn parse-wraper
  "
  Parse wrapper utility for catch any error into parse-fn

  - _parse-fn:_ function to parse entry data
  "
  [parse-fn]
  (fn [line]
    (try
      (parse-fn line)
      (catch Exception e
        (log/warn (pr-str {:error  "Cannot parse value" :value line}))
        {:tailer/error true :tailer/error-code error-codes/PARSE-ERROR :tailer/error-message (str "Cannot parse value : " (pr-str line))}))))

(defmethod start-listener 'caudal.io.tailer-server [sink config]
  "
  Creates a Tailer listener.

  - _parser:_ parser function transforms a file line to a EDN data structure
  - _inputs:_ map with two properties: _directory-path_ and a _wildcard_ expression to match files
  - _delta:_  Delay between checks of the file for new content in milliseconds
  - _from-end:_ Set to true to tail from the end of the file, false to tail from the beginning of the file
  - _reopen:_ if true, close and reopen the file between reading chunks
  - _buffer-size:_ buffer-size

  Example:

  ```
  ;; Reads a logfile in EDN format
  (deflistener tailer [{:type 'caudal.io.tailer-server
                        :parameters {:parser      read-string
                                     :inputs      {:directory  \"/var/log\"
                                                   :wildcard   \"*.edn.log\"}
                                     :delta       250
                                     :from-end    false
                                     :reopen      true
                                     :buffer-size 100000}}])
  ```
  "
  (let [{:keys [inputs delta from-end reopen buffer-size filter-fn parser]} (get-in config [:parameters])
        line-channel (if filter-fn
                       (chan (buffer 10) (filter filter-fn))
                       (chan (buffer 10)))
        parse-fn     (if (symbol? parser) (resolve&get-fn parser) parser)
        _            (if (map? inputs)
                       (let [{:keys [directory wildcard]} inputs
                             files (file-util/find-files directory wildcard)]
                         (initialize-tail line-channel directory wildcard files delta from-end reopen buffer-size inputs))
                       (let [files (map #(io/file %) inputs)]
                         (initialize-tail line-channel nil nil files delta from-end reopen buffer-size inputs)))]
    (register-channels [line-channel] (parse-wraper parse-fn) sink)))
