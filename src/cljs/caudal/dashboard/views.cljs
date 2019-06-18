(ns caudal.dashboard.views
    (:require [reagent.core :as reagent]
              [re-frame.core :as re-frame]
              [re-mdl.core  :as mdl]
              [goog.string :as gstring]
              [goog.string.format]
              [goog.i18n.DateTimeFormat :as dtf]
              [keybind.core :as key]
              [caudal.dashboard.util :as util]
              [caudal.dashboard.db :as db]
              ))

(def with-initial-url true)
;(def default-protocol "http:")
;(def default-hostname "localhost")
;(def default-port 9901)

(key/bind! "ctrl-h" ::my-trigger (fn [x]
                                   (.log js/console "dispatch open?")
                                   (re-frame/dispatch [:open? true])))

(defn topbar-panel []
  (let [location (-> js/window .-location)
        protocol  (aget location "protocol")
        hostname  (aget location "hostname")
        port      (js/parseInt (aget location "port"))
        initial-url (str protocol "//" hostname ":" port)
        try-fn (fn [event]
                 (let [input (-> event .-target .-value)]
                   (re-frame/dispatch [:loading-url-change (if (util/url? input) input)])))
        render-fn (fn []
                    (let [{:keys[status link]}  @(re-frame/subscribe [:uri])
                          msg  @(re-frame/subscribe [:url-target-message])
                          streamers @(re-frame/subscribe [:states])
                          selected @(re-frame/subscribe [[:state :selected]])
                          open? @(re-frame/subscribe [:open?])
                          state-fn (fn [event]
                                     (let [input (-> event .-target .-value)]
                                       (if (some #(= % input) streamers)
                                         (re-frame/dispatch [:get-state input]))))
                          type (cond
                                 (or (= status :success)
                                     (= status :loading-data)) "alert-success"
                                 (= status :warning) "alert-warning"
                                 (= status :danger) "alert-danger"
                                 :default "alert-info")
                          icon (cond
                                 (or (= status :loading)
                                     (= status :loading-data)) [:i.material-icons.spiner {:style {:padding "5px 0px"}} "timelapse"]
                                 (= status :success) [:i.material-icons {:style {:padding "5px 0px"}} "signal_wifi_4_bar"]
                                 :default            [:i.material-icons {:style {:padding "5px 0px"}} "signal_wifi_off"])
                          input [:input#url-box.form-control
                                 (merge
                                  {:placeholder initial-url
                                   :type "text"
                                   :on-click #(try-fn %)
                                   ;:on-blur #(try-fn %)
                                   ;:on-change #(try-fn %)
                                   }
                                        ;(if with-initial-url {:value link})
                                  (if-not open? {:style {:display "none"}})
                                  )]
                         ]
                      (if open?
                        [mdl/dialog
                         :children
                         [[mdl/dialog-title
                           :child "Hack Dialog"]
                          [mdl/dialog-content
                           :children
                           [[:p "Customize target server"]]]
                          input
                          [mdl/dialog-actions
                           :children
                           [[mdl/button
                             :child    "Close"
                             :on-click #(re-frame/dispatch [:open? false])
                             ]
                            [mdl/button
                             :child    "console.log(db)"
                             :on-click #(re-frame/dispatch [:print-db])
                             ]
                            ]]]]
                        input)))]
    (reagent/create-class
     {:reagent-render render-fn
      :component-did-mount (fn [comp]
                             (let [url (.getElementById js/document "url-box")
                                   ]
                               (set! (.-value url) initial-url)
                               (.click url)))})))
