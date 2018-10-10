(ns mx.interware.caudal.dashboard.views.server
    (:require [reagent.core :as reagent]
              [re-frame.core :as re-frame]
              [re-mdl.core  :as mdl]
              [goog.string :as gstring]
              [goog.string.format]
              [mx.interware.caudal.dashboard.util :as util]
              [mx.interware.caudal.dashboard.db :as db])
    (:import [goog.i18n DateTimeFormat]))

(defn panel [_ _]
  (let [{:keys[status link] :as uri}  @(re-frame/subscribe [:uri])
        {:keys [uri name version uptime]} @(re-frame/subscribe [:url-target-message])
        selected @(re-frame/subscribe [[:state :selected]])
        states @(re-frame/subscribe [:states])

        panel-heading "Caudal Server not connected yet"

        content [:div.panel-body
                 [:p "Write a valid Caudal URL in the box"]
                 #_[:p.center [:img {:src "img/caudal-dashboard-example.gif"}]]]
        content-loading [mdl/table
                         :class "server-table"
                         :shadow 2
                         :headers [["attribute" :non-numeric true] ["value"]]
                         :rows [["URL" uri]
                                ["Application" name]
                                ["Version" version]
                                ["Uptime" (gstring/format "%.2f hours" (double (/ uptime 3600000)))]
                                ["States" (into [:div.state-panel] (map (fn [state]
                                                                          [mdl/chip
                                                                           :button?  :button
                                                                           :contact? true
                                                                           :on-click  #(re-frame/dispatch [:get-state state])
                                                                           :children
                                                                           [[mdl/chip-contact
                                                                             :class (if (= selected state)
                                                                                      "mdl-color--green mdl-color-text--white"
                                                                                      "mdl-color--gray mdl-color-text--white")
                                                                             :child [:i.material-icons "visibility"]]
                                                                            [mdl/chip-text
                                                                             :child state]]]) states))]]]]

    (condp = status
      :success
      (let [panel-heading "Caudal Server connected"]
        (util/inner-panel panel-heading content-loading))

      :loading-data
      (let [panel-heading "Caudal Server loading ..."]
        (util/inner-panel panel-heading content-loading))

      :danger
      (let [panel-heading "Caudal Server timeout"
            {:keys [uri status-text response]} @(re-frame/subscribe [:url-target-message])
             content [mdl/table
                     :class "server-table"
                     :shadow 2
                     :headers [["attribute" :non-numeric true] ["value"]]
                     :rows [["URL" uri]
                            ["Error" status-text]
                            ["Response" response]]]]
        (util/inner-panel panel-heading content))

      :warning
      (let [panel-heading "Caudal Server not reachable"
            {:keys  [uri status-text]} @(re-frame/subscribe [:url-target-message])
            content [mdl/table
                     :class "server-table"
                     :shadow 2
                     :headers [["attribute" :non-numeric true] ["value"]]
                     :rows [["URL" uri]
                            ["Error" status-text]]]]
          (util/inner-panel panel-heading content))
      (util/inner-panel panel-heading content))))
