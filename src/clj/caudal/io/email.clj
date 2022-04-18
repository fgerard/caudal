;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns caudal.io.email
  (:require [clojure.core.async :as async :refer [go]]
            [clojure.string :as str :refer [join starts-with?]]
            [clojure.walk :as wlk :refer [walk]]
            [hiccup.core :as hiccup :refer [html]]
            [caudal.streams.common :as common :refer [propagate]]
            [postal.core :as postal :refer [send-message]]))

(defn make-header
  "Creates a default header with a simple title

  * *title* replaced in mail header"
  [title]
  [:div {:class "header" :style "margin:5px 10px; padding:0"} [:b title]])

(defn make-footer
  "Creates a default footer with a simple text"
  []
  [:div {:class "footer" :style "width: 550px; text-align: right;"}
   [:p {:style "color: #8899a6"} "Powered by Caudal &copy;"]])

(defn make-table-template
  "Creates a default html view from an arbitrary source map

  * *source* arbitrary map to build a template"
  [source]
  [:table {:style       "margin:5px 10px; border:2px solid #f5f8fa; padding:0"
           :cellpadding "5" :cellspacing "0" :width "550px"}
   [:tbody (map-indexed (fn [i key]
                          (let [style (if (even? i)
                                        "background-color:#f5f8fa"
                                        "background-color:#ffffff")
                                value (key source)]
                            [:tr {:style style}
                             [:th {:width "20%" :align "left"} (name key)]
                             [:td {:width "80%" :align "left"} (if (starts-with? value "data:image") [:img {:src value}] value)]]))
                        (keys source))]])

(defn event->html
  "Produces a HTML representation of an event

  * *caudal-event* to be represented in HTML
  * html-template (optional) to produce a representation of an event. This template uses
  vectors to represent elements and is parsed using Hiccup and replaces
  caudal-event key ocurrences into vectors with its value"
  [caudal-event & [html-template]]
  (if (or (not html-template) (nil? html-template) (empty? html-template))
    (make-table-template caudal-event)
    (letfn [(catafixia [map event]
              (if (coll? event)
                (wlk/walk (partial catafixia map) identity event)
                (if-let [value (get map event)]
                  value
                  event)))]
      (wlk/walk (partial catafixia caudal-event) identity html-template))))

(defn email-event-with-keys
  "Sends an event or a sequence of events via email

   * *smtp-opts* SMTP options passed to Postal to send the email
   * *msg-opts*  Message options passed to Postal to send the email. Value of
   :body is replaced with HTML produced by make-header, event->html and make-footer
   * *events* to be sended
   * keys (optional) keys to be sended
   * html-template (optional) to produce a representation of an event"
  ([smtp-opts msg-opts events & [keys html-template]]
   (let [title   (:subject msg-opts)
         events  (flatten [events])
         select  (if (or (not keys) (nil? keys) (empty? keys))
                   events
                   (map (fn [x] (select-keys x keys)) events))
         resume  (map (fn [event] (event->html event html-template)) select)
         content (hiccup/html (make-header title) resume (make-footer))
         body    [{:type "text/html" :content content}]]
     (postal/send-message smtp-opts
                          (merge msg-opts {:body body})))))

(defn email-event-with-body-fn
  [smtp-opts msg-opts event body-fn]
  (let [body (body-fn event)]
    (postal/send-message smtp-opts
                         (merge msg-opts {:body body}))))

(defn mailer
  "Returns a mailer, which is a function invoked with a map of options and
  returns a stream that takes a single or a sequence of events, and sends an
  email about them.

  Examples:

  ```
  ;; Mailer that uses a local sendmail
  (def email (mailer))

  ;; Mailer with Postal options
  (def email (mailer [{:host 'smtp.gmail.com'
                       :user 'sample@gmail.com'
                       :pass 'somesecret'
                       :port 465
                       :ssl :yes}
                      {:subject 'Help!!'
                       :to ['ops@example.com' 'support@example.com']}]))

  ;; With custom keys
  (def email (mailer [{:host 'smtp.gmail.com'
                       :user 'sample@gmail.com'
                       :pass 'somesecret'
                       :port 465
                       :ssl :yes}
                      {:subject 'Help!!'
                       :to ['ops@example.com' 'support@example.com']}
                      [:key1 :key2]]))

  ;; With custom html/hiccup template
  (def email (mailer [{:host 'smtp.gmail.com'
                       :user 'sample@gmail.com'
                       :pass 'somesecret'
                       :port 465
                       :ssl :yes}
                      {:subject 'Help!!'
                       :to ['ops@example.com' 'support@example.com']}
                      [:key1 :key2]
                      [:div [:p :key1] [:p :key2]]]))

  ;; Sends max 5 email each 15 minutes using rollup
   (stateful/rollup :host 5 (* 60000 15)
      email)

  ```
  "
  [[smtp-opts msg-opts & [keys-or-body-fn html-template]] & children]
  (let [msg-opts (merge {:from "caudal"} msg-opts)]
    (fn [by-path state event]
      (if (fn? keys-or-body-fn)
        (email-event-with-body-fn smtp-opts msg-opts event keys-or-body-fn)
        (email-event-with-keys smtp-opts msg-opts event keys-or-body-fn html-template))
      (common/propagate by-path state event children))))

(comment

  (require '[caudal.io.email :as email] '[hiccup.core :as hiccup :refer [html]] '[clojure.java.io :as io])

  (def D [{:host "smtp.gmail.com",
           :user "notificacion.quantumlabs@gmail.com",
           :pass "tsnnkudkzwcfhrqz",
           :port 465,
           :ssl :yes}
          {:subject "test !!",
           :from "caudal"
           :to ["fgerard@quantumlabs.ai" "destevez@quantumlabs.ai"]}])

  (def I (slurp "image.b64"))

  (def event {:image (subs I 0 (dec (count I)))
              :eventName "ENTRADA"
              :ts 0})

  (defn e->html [{:keys [image eventName ts]}]
    (let [sdf (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss.SSS")
          decoder (java.util.Base64/getDecoder)
          bytes (.decode decoder image)
          tmpFile (io/file "imagen-para-enviar.jpg")
          hiccup [:div
                  [:p (str "Evento:" eventName)]
                  [:p (str "Cuando: " (.format sdf ts))]]]
      (with-open [img-file (java.io.FileOutputStream. tmpFile)]
        (.write img-file bytes))
      
      (println (.getCanonicalPath tmpFile))
      [{:type "text/html" :content (html hiccup)}
       {;:type "image/jpg" ;"text/plain" 
        :type :inline
        :content tmpFile
        }]))

  (let [[smtp-opts msg-opts & [keys-or-body-fn html-template]] D]
    (email/email-event-with-body-fn smtp-opts msg-opts event e->html))


(let [png-bytes (.decode (java.util.Base64/getDecoder) screenshot)
      imagen (java.io.File/createTempFile "cdl" ".png")]
  (with-open [xout (java.io.FileOutputStream. imagen)]
    (.write xout png-bytes)) images)

  
  )