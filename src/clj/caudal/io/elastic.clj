;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns caudal.io.elastic
  (:require [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [caudal.util.id-util :as id-util]
            [caudal.streams.common :refer [propagate]]
            [clojurewerkz.elastisch.rest :as esr]
            [clojurewerkz.elastisch.rest.document :as doc]
            [clojurewerkz.elastisch.rest.bulk :as bulk]
            [slingshot.support :refer [stack-trace]]))

(defn elastic-store!
  "
  Streamer function used for storing event(s) into Elasticsearch database.
  > **Arguments:**
     *elsh-url*: Elasticsearch connection URL
     *index*: Elasticsearch index
     *type*: Elasticsearch document type
     *children*: Children streamer functions to be propagated
     *mapping* (optional): mapping of fields with custom types. See [Elastisch docs.](http://clojureelasticsearch.info/articles/indexing.html#mapping-type-settings)
  "
  [[elsh-url index type mapping elsh-opts] & children]
  (let [elsh-connection (try
                          (log/info "Connecting to elasticsearch ...")
                          (esr/connect elsh-url elsh-opts)
                          (catch Exception e
                            (.printStackTrace e)))
        _               (log/info "elsh-connection : " elsh-connection)
        elsh-create     (if mapping
                          (esr/put elsh-connection
                                   (esr/index-url elsh-connection index)
                                   {:body {:mappings mapping}}))]
    (fn stream [by-path state event-input]
      (let [_              (log/debug (str "Storing event(s) into ELS : " event-input))
            event-list     (cond (map? event-input) (list event-input)
                                 (coll? event-input) event-input
                                 :else (list event-input))
            elastic-events (map (fn [{:keys [doc-type] :or {doc-type (or type "default")} :as e}]
                                  (-> e (assoc :created-on (System/currentTimeMillis) :_type doc-type) (dissoc :ttl)))
                                event-list)
            operations     (bulk/bulk-index elastic-events)]
        (bulk/bulk-with-index elsh-connection index operations {:refresh true})
        (propagate by-path state event-input children)))))
