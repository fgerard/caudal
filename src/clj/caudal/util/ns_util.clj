;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns caudal.util.ns-util
  (:require [clojure.tools.logging :as log]
            [clojure.string :refer [split]]))

(defn resolve&get-fn [fully-qualified-fn]
  (let [fn-ns (symbol (namespace fully-qualified-fn))
        _     (require fn-ns)]
    (resolve fully-qualified-fn)))

(defn require-name-spaces [name-spaces]
  (doseq [ns-str name-spaces]
    (let [ns-symbol (symbol ns-str)]
      (log/debug "requiring store implementation:" ns-symbol "...")
      (require ns-symbol))))
