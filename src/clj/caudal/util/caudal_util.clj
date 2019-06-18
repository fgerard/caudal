;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns caudal.util.caudal-util
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]))

(defn default-caudal-agent-validator [agent-value]
  (when-not (map? agent-value)
    (throw (java.lang.RuntimeException. (str "Value for Caudal state must be a map, intended value: " (pr-str agent-value)))))
  true)

(defn default-caudal-agent-error-handler [agent-value exception]
  (log/warn exception))

(defn ver-fn  [f]
  (if f
    (-> f .getName (str/split #"-") last Integer/parseInt)
    0))

(defn get-versioned-file-list
  ([file-template]
   (get-versioned-file-list (.getParentFile file-template) file-template))
  ([parent file-template]
   (let [file-re (re-pattern (str (.getName file-template) "-[0-9]+"))
         file-list (->> parent file-seq (filter #(re-matches file-re (.getName %))))]
     (reverse
      (sort-by ver-fn file-list)))))

(defn rotate-file [parent file versions]
  (let [vers (get-versioned-file-list parent file)
        next-ver (inc (ver-fn (first vers)))
        remove-list (drop (dec versions) vers)]
    (doseq [f2remove remove-list]
      (.delete f2remove))
    (java.io.File. parent (str (.getName file) "-" next-ver))))

(defn create-caudal-agent
  ([name persistence-dir & {:keys [initial-map validator error-handler]
       :or   {validator     default-caudal-agent-validator
              error-handler default-caudal-agent-error-handler}}]
   (let [persistence-dir (and persistence-dir (java.io.File. persistence-dir))
         created (and persistence-dir (.mkdirs persistence-dir))
         persistence-template (and persistence-dir (java.io.File. persistence-dir name))
         persistence (and persistence-template (first (get-versioned-file-list persistence-dir persistence-template)))
         initial-state (if persistence
                         (do
                           (log/info "Creating path to sink persisntece: " created)
                           (if (.exists persistence)
                             (let [_ (log/info "Reading sink file: " (.getCanonicalPath persistence))
                                   data-str (slurp persistence)
                                   _ (log/info "Parsing sink file: " (.getCanonicalPath persistence))
                                   data (read-string data-str)]
                               data)
                             {}))
                         {})]
     (agent (cond-> initial-state
                    persistence-template (assoc :caudal/persistence persistence-template))
            :validator validator
            :error-handler error-handler
            :error-mode :continue))))

(defn printp [prefix & params]
  (println prefix (apply str (map #(str (pr-str %) " ") params))))
