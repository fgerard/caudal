(ns mx.interware.caudal.dashboard.core
  (:require-macros [hiccups.core :as hiccups :refer [html]])
  (:require [reagent.core :as reagent]
            [re-frame.core :as re-frame]
            [re-mdl.core  :as mdl]
            [mx.interware.caudal.dashboard.events]
            [mx.interware.caudal.dashboard.subs]
            [mx.interware.caudal.dashboard.views :as views]
            [mx.interware.caudal.dashboard.views.rate :as rate]
            [mx.interware.caudal.dashboard.views.server :as server]
            [mx.interware.caudal.dashboard.views.doughnut :as doughnut]
            [mx.interware.caudal.dashboard.views.bars :as bars]
            [mx.interware.caudal.dashboard.config :as config]
            [mx.interware.caudal.dashboard.util :as util]))


(defn dev-setup []
  (when config/debug?
    (enable-console-print!)
    ;(println "dev mode")
    ))

(defn grid-demo [& kids]
  [mdl/grid
   :children
   (into []
         (for [kid kids]
           [mdl/cell
            :col 12
            :children
            [kid]]))])

(defn demo-layout-component [{:keys [href demo title icon tab]
                              :or   {href "#"}}]
  [mdl/layout-nav-link
   :href     href
   :content  [:span icon " " (name title)]
   :on-click (fn [_]
               (re-frame/dispatch [:select-tab (name tab)]))])

(defn demo-layout [& {:keys [demo-map current-demo-ra children]}]
  (let [{:keys[status link] :as uri}  @(re-frame/subscribe [:uri])
        streamers @(re-frame/subscribe [:states])
        refresh @(re-frame/subscribe [:refresh])
        selected @(re-frame/subscribe [[:state :selected]])
        refresh-fn (fn [event]
                     (let [input (-> event .-target .-value)
                           input (if (= input "-1") nil input)]
                       (re-frame/dispatch [:refresh input])))
        state-fn (fn [event]
                   (let [input (-> event .-target .-value)]
                     (if (some #(= % input) streamers)
                       (re-frame/dispatch [:get-state input]))))
        status (if status (name status) "loading-data")
        temps [[:option {:value -1} "autorefresh off"]
               [:option {:value 5} "5 seconds"]
               [:option {:value 10} "10 seconds"]
               [:option {:value 30} "30 seconds"]
               [:option {:value 60} "60 seconds"]]
        {:keys [title]} (current-demo-ra demo-map)]
    [mdl/layout
     :fixed-header? true
     :fixed-drawer? true
     :children
     [[mdl/layout-header
       :class status
       :children
       [[mdl/layout-header-row
         :class status
         :children
         [[mdl/layout-title
           :label (clojure.string/capitalize title)]
          [mdl/layout-spacer]
          [mdl/layout-nav
           :children
           [[:div.selector
             (into [:select.form-control
                    {:on-change #(refresh-fn %)
                     :disabled (or (nil? streamers) (nil? selected))}]
                   (map (fn [[option {:keys [value]} text :as v]]
                          (if (= refresh value)
                            [option {:value value :selected true} text]
                            v)) temps))]
            [:div.selector
             (into [:select.form-control
                    {:on-change #(state-fn %)
                     :disabled (nil? streamers)
                     :value (or selected "streamer ...")}
                    [:option "streamer ..."]] (map (fn [x] [:option x]) (sort streamers)))]
            ;[:div (pr-str status)]
            [mdl/loading-spinner
              :is-active? (or (= status "loading")
                              (= status "loading-data"))]
            ]]]]]]
      [mdl/layout-drawer
       :children
       ;
       [#_[mdl/layout-title
         :label "caudal"
         :attr  {:on-click (fn [_]
                             (re-frame/dispatch [:select-tab "server"]))
                 :style    {:cursor "pointer"}}]
        [:nav.mdl-navigation {:style {:text-align "center"}}
         [:a {:href "#"}
          [:img {:src "caudal.svg"
                 :style {:width "72px" :height "72px" :cursor "pointer"}
                 :on-click  (fn [_]
                              (re-frame/dispatch [:select-tab "server"]))
                 }]]]
        [views/topbar-panel]
        [mdl/layout-nav
         :children
         (into []
               (for [[demo {:keys [title icon]}] (dissoc demo-map :server)]
                 (demo-layout-component {:demo  current-demo-ra
                                         :icon icon
                                         :tab demo
                                         :title title})))]]]
      [mdl/layout-content
       :children children]]]))

(comment defn counter-tooltip [{:keys [:n]}])

(comment stats-tooltip [["name" (str k)]
                ["n" (util/format-num (:n v))]
                ["%" (util/format-num (/ (* 100 (:n v)) tot))]])

(defn percentage-with [value-fn]
  (fn [data]
    (let [tot (reduce + (map (fn [[k v]]
                               (value-fn v)) data))]
      (fn [k v]
        (html
         [:table {:class "table"}
          [:tr [:th "name"] [:td (str k)]]
          [:tr [:th "n"] [:td (util/format-num (value-fn v))]]
          [:tr [:th "%"] [:td (util/format-num (/ (* 100 (value-fn v)) tot))]]])))))

(defn mean&stdev [value-fn stdev-fn]
  (fn [data]
    (let [tot (reduce + (map (fn [[k v]]
                               (value-fn v)) data))]
      (fn [k v]
        (html
         [:table {:class "table"}
          [:tr [:th "name"] [:td (str k)]]
          [:tr [:th "mean"] [:td (util/format-num (value-fn v))]]
          [:tr [:th "stdev"] [:td (util/format-num (stdev-fn v))]]
          [:tr [:th "%"] [:td (util/format-num (/ (* 100 (value-fn v)) tot))]]])))))


(def demo-map ;(sorted-map)
  {:server {:title "caudal" :icon [:i.material-icons "explore"] :component server/panel}
   :doughnut {:title "doughnut" :icon [:i.material-icons "donut_large"]
             :component (doughnut/panel-factory)
             ;:params {:success-vs-error {:value-fn :n :tooltip [:n]},
              ;        :hourly-stats {:value-fn :mean, :tooltip [:mean :stdev :count]},
              ;        :hourly-flow-stats {:value-fn :mean, :tooltip [:mean :stdev :count]}}
             ;:paramsx {:state-type "counter" :value-fn :n :tooltip-fn (percentage-with :n)}
              }   ;counter/panel  ;(doughnut/panel "counter" :n )
   :bar {:title "bar" :icon [ :i.material-icons "poll"]
         :component (bars/panel-factory)
         ;:params {:state-type "welford" :value-fn :mean :tooltip-fn (mean&stdev :mean :stdev)}
         } ;statistics/panel
   :timeline {:title "timeline" :icon [:i.material-icons "timeline"]
              :component (rate/panel-factory)}})

(defn create-component-fn [{:keys [title icon component params]}]
  (fn [state]
    (component state params)))

(defn get-types [data view-conf]
  (let [view-keys (reduce (fn [ks [graph-type confs]]
                            (let [state-keys (into #{} (keys confs))]
                              (into ks state-keys)))
                          #{}
                          view-conf)]
    ;;; sacar del estado todos los que inicien con el view-key y sacar el caudal/type
    ))

(defn app-view []
  (let [tab @(re-frame/subscribe [:tab])
        {{view-conf :caudal/view-conf :as data} :data :as state} @(re-frame/subscribe [:state])
        current-demo (keyword tab)
        types (conj (set (keys view-conf)) :server)
        ; _ (.log js/console "vconf:" (str view-conf))
        _ (.log js/console "types:" (str types))
        _ (.log js/console (str ))
        demo-map (reduce (fn [r [k v]]
                           (.log js/console (str "k: " k))
                           (if-let [occur (types k)]
                             (assoc r k v)
                             r))
                         {}
                         demo-map)
        demo-map (reduce
                  (fn [r [k v]]
                    (.log js/console (str k))
                    (assoc-in r [k :params] v))
                  demo-map
                  view-conf)
        ;_ (.log js/console "demom:" (str demo-map))
        ]
    [demo-layout
       :demo-map        demo-map
       :current-demo-ra current-demo
       :children
       [[grid-demo
         [(-> demo-map current-demo create-component-fn) state]]]]
    ))

(defn ^:export run []
  (re-frame/dispatch-sync [:initialize-db])
  (reagent/render [app-view]
                  (js/document.getElementById "app")))
