;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns caudal.util.file-util
  (:import (java.io File)
           (org.apache.commons.io FileUtils)
           (org.apache.commons.io.filefilter WildcardFileFilter)))

(defn find-files [directory-path wildcard]
  (let [directory (new File directory-path)
        filter    (new WildcardFileFilter wildcard)
        files     (FileUtils/listFiles directory filter nil)]
    files))
