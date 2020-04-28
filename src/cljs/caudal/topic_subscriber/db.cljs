(ns caudal.topic-subscriber.db
  (:require [goog.string :as gstring]
            [goog.string.format]
            [fipp.edn :refer (pprint) :rename {pprint fipp}]))


(def default-db
  {:name "topic-subscriber 0.1"
   :users {"fgerard@quantumlabs.io" {:roles []}}
   :topics ["detail" "aggregate"]
   :customs {:aggregate {} #_{"AD" {["front" "port"] {}
                                    ["front" "starboard"] {}
                                    ["back" "port"] {}
                                    ["back" "starboard"] {}
                                    ["plate"] {}}}
             :detail '()}
   })
