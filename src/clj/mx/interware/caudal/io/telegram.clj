(ns mx.interware.caudal.io.telegram
  (:require [clojure.tools.logging :as log] 
            [clojure.string :as string] 
            [aleph.http :as http]
            [byte-streams :as bs]
            [mx.interware.caudal.streams.common :as common :refer [propagate]]))

(defn push 
  "Sends a message to caudal-bot-server using a Caudal APIKey"
  [event {:keys [apikey url message]}]
  (let [url   (or url "http://caudal.io/push")
        msg   (or message (str event))
        len   (count msg)
        query "?apikey=KEY"
        query (string/replace query "KEY" apikey)
        srv   (str url query)]
    (if (> len 4096) ; https://core.telegram.org/method/messages.sendMessage
      (do
        (log/warn "Message too long (length = " len ")")
        (log/warn "Current maximum length is 4096 UTF8 characters"))      
      (-> @(http/post srv {:headers {"accept" "application/edn" "content-type" "application/edn"} :form-params {:message msg}})
          :body
          bs/to-string
          read-string))))

(defn telegram
  "
  Streamer function that send a message or event to Telegram via @caudalbot

  _options:_ can be a map or a function of event that return a map. This map must contain :apikey (as mandatory),  :url and :message (as optional)

  Examples:

  ```
  ;; Get your own APIKey in http://caudal.io/bot/apikey
  (def secret-key \"caudal-000000000\")
  
  ;; Using a map, if :message is not specified then sends entire event
  (telegram [{:apikey secret-key}])
  
  ;; Using a function
  (telegram [(fn [e] (let [{:keys [app error]}]
                       {:apikey  secret-key 
                        :message (str \"Error in app \" app \" : \" error)}))])
  ```
  "
  [[options] & children]
  
  (fn [by-path state event]
    (let [options (if (fn? options) (options event) options)]
      (push event options))
    (common/propagate by-path state event children)))
