(ns caudal.io.telegram
  (:require [clojure.core.async :refer [go-loop timeout <!]]
            [clojure.tools.logging :as log]
            ;[clojure.string :as string] 
            [clojure.data.json :as json]
            ;[aleph.http :as http]
            ;[byte-streams :as bs]
            [clj-http.client :as http]
            [caudal.util.ns-util :refer [resolve&get-fn]]
            [caudal.streams.common :as common :refer [start-listener]]))

(def base-url "https://api.telegram.org/bot")
#_(http/with-connection-pool {:timeout 30 :threads 4 :insecure? false :default-per-route 4})

  (defn send-text*
    "Sends message to the chat"
    ([token chat-id text] (send-text* token chat-id {} text))
    ([token chat-id options text]
     (try
       (let [url  (str base-url token "/sendMessage")
             base-form    [{:name "chat_id" :content (str chat-id)}
                           {:name "text" :content text}]
             options-form (for [[key value] options]
                            {:name (name key) :content (if (coll? value) (json/write-str value) value)})
             form         (into base-form options-form)
             resp         (http/post url {:multipart form})]
         (-> resp :body))
       (catch clojure.lang.ExceptionInfo e
         (log/error (-> e
                        .getData
                        :body
                        (json/read-str :key-fn keyword)
                        pr-str))))))

  (defn send-file*
    "Helper function to send various kinds of files as multipart-encoded"
    [token chat-id options file method field filename]
    (try
      (let [url          (str base-url token method)
            base-form    [{:part-name "chat_id" :content (str chat-id)}
                          {:part-name field :content file :name filename}]
            options-form (for [[key value] options]
                           {:part-name (name key) :content value})
            form         (into base-form options-form)
            resp         (http/post url {:multipart form})]
        (-> resp :body))
      (catch clojure.lang.ExceptionInfo e
        (log/error (-> e
                       .getData
                       :body
                       (json/read-str :key-fn keyword)
                       pr-str)))))

  (defn send-text
    [[key-token key-chat-id text options] & children]
    (fn [by-path state event]
      (let [token (key-token event)
            chat-id (key-chat-id event)
            text (if (or (fn? text) (keyword? text)) (text event) text)
            options (or options {})]
        (send-text* token chat-id options text))
      (common/propagate by-path state event children)))

  (defn send-photo
    [[key-token key-chat-id image options] & children]
    (fn [by-path state event]
      (let [token (key-token event)
            chat-id (key-chat-id event)
            image (if (or (fn? image) (keyword? image)) (image event) image)
            options (or options {})]
        (send-file* token chat-id options image "/sendPhoto" "photo" "photo.png"))
      (common/propagate by-path state event children)))

  (defn new-offset
    "Returns new offset for Telegram updates"
    [result default]
    (if (and result (< 0 (count result)))
      (-> result last :update_id inc)
      default))

  (defn poller-error [e url params]
    (try
      (log/error (-> e
                     .getData
                     :body
                     (json/read-str :key-fn keyword)
                     (assoc :url url :params params)))
      (catch Throwable _
        {:error (.getMessage e) :url url :params params})))

  (defn poller [url params]
    (try
      (let [params {:query-params params :socket-timeout 3000 :connection-timeout 3000}]
        (log/debug {:pooling url :params params})
        (-> (http/get url params)
            :body
            (json/read-str :key-fn keyword)))
      (catch Exception e
        (poller-error e url params))))

  (defn start-server [token message-parser sink]
    (log/info "Starting Telegram Bot Server, token: " token)
    (let [url (str base-url token "/getUpdates")]
      (go-loop [offset 0 limit 100]
        (let [params {:timeout 1 :offset offset :limit limit}
              {:keys [ok result] :as data} (poller url params)]
          (if ok
            (dorun (map (fn [input]
                          (let [chat-id (get-in input [:message :chat :id] (get-in input [:callback_query :message :chat :id]))
                                parsed (and message-parser (message-parser (get-in input [:message :text])))]
                            (sink (merge input parsed {:telegram/token token :telegram/chat-id chat-id}))))
                        result))
            (log/error data))
          (<! (timeout 1000))
          (recur (new-offset result offset) limit)))))

  (defmethod start-listener 'caudal.io.telegram
    [sink config]
    (let [{:keys [token parser]} (get-in config [:parameters])
          parser-fn (if parser (if (symbol? parser) (resolve&get-fn parser) parser) nil)]
      (start-server token parser-fn sink)))
