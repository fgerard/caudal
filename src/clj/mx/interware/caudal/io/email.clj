;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns mx.interware.caudal.io.email
  (:require [clojure.core.async :as async :refer [go]]
            [clojure.string :as str :refer [join]]
            [clojure.walk :as wlk :refer [walk]]
            [hiccup.core :as hiccup :refer [html]]
            [mx.interware.caudal.streams.common :as common :refer [propagate]]
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
                                        "background-color:#ffffff")]
                            [:tr {:style style}
                             [:th {:width "20%" :align "left"} (name key)]
                             [:td {:width "80%" :align "left"} (key source)]]))
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

(defn email-event
  "Sends an event or a sequence of events via email

   * *smtp-opts* SMTP options passed to Postal to send the email
   * *msg-opts*  Message options passed to Postal to send the email. Value of
   :body is replaced with HTML produced by make-header, event->html and make-footer
   * *events* to be sended
   * keys (optional) keys to be sended
   * html-template (optional) to produce a representation of an event"
  [smtp-opts msg-opts events & [keys html-template]]
  (println :keys (pr-str keys) (or (not keys) (nil? keys) (empty? keys)))
  (let [title   (:subject msg-opts)
        events  (flatten [events])
        select  (if (or (not keys) (nil? keys) (empty? keys))
                  events
                  (map (fn [x] (select-keys x keys)) events))
        _ (println :select (pr-str select))
        resume  (map (fn [event] (event->html event html-template)) select)
        _ (println :resume (pr-str resume))
        content (hiccup/html (make-header title) resume (make-footer))
        body    [{:type "text/html" :content content}]]
    (println :smtp-opts (pr-str smtp-opts))
    (println :merge (pr-str (merge msg-opts {:body body})))

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
  (def email (mailer {:host 'smtp.gmail.com'
                      :user 'sample@gmail.com'
                      :pass 'somesecret'}
                     {:subject 'Help!!'
                      :to ['ops@example.com' 'support@example.com']
                      :ssl :yes}))

  ;; Invoking email sending only :message and :ts fields
  (email event [:message :ts])

  ;; Invoking email sending only :message and :ts fields into a custom template
  (email event [:message :ts] [:p [:em :message] ' since ' [:i :ts]])

  ;; Sends max 5 email each 15 minutes using rollup
   (stateful/rollup :host 5 (* 60000 15)
      (mailer smtp-opts))

  ```
  "
  [[smtp-opts msg-opts & [keys html-template]] & children]
  (let [msg-opts (merge {:from "caudal"} msg-opts)]
     (fn [by-path state event]
       (email-event smtp-opts msg-opts event keys html-template)
       (common/propagate by-path state event children))))
