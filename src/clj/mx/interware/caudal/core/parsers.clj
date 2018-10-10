;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns mx.interware.caudal.core.parsers)


;:re #".*(EJECUTANDO) +([a-zA-Z0-9]+).*time .>>.*|.*(FINALIZANDO) +([a-zA-Z0-9]+).*time .>> +([0-9]+).*"
;:tags [:start :tx :end :tx :delta])
(defn re-parser [{:keys [re tags] :or {re #".*" tags [:text]}}]
  (fn [line]
    (when-let [groups (re-matches re line)]
      (reduce
        (fn [result [tag val]]
          (if (and (not= :_ tag) val)
            (assoc result tag val)
            result))
        {}
        (map (fn [tag val]
               [tag val])
             tags groups)))))
