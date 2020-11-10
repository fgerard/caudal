(ns caudal.topic-subscriber.db
  (:use-macros [cljs.core.async.macros :only [go]])
  (:require [goog.string :as gstring]
            [goog.string.format]
            [goog.date]
            [fipp.edn :refer (pprint) :rename {pprint fipp}]))


(def default-db
  {:name "topic-subscriber 0.1"
   :users {"fgerard@quantumlabs.io" {:roles []}}
   :topics ["detail" "aggregate"]
   :customs {:aggregate {} #_{"AD" {[:front :port] {}
                                    [:front :starboard] {}
                                    [:back :port] {}
                                    [:back :starboard] {}
                                    [:plate] {}
                                    [:clip] {}}}
             :detail '()}
   })
