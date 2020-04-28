(ns caudal.topic-subscriber.views
  (:require [cljs.pprint :refer [pprint]]
            [reagent.core :as r]
            [re-frame.core :as re-frame]
            [re-com.core :as re-com]
            [reagent.core :as reagent :refer [atom create-class]]))

(defn logger []
  (let [details (re-frame/subscribe [[:customs :detail]])]
    (fn []
      [re-com/scroller
       :h-scroll :on
       :v-scroll :on
       :width "2000px"
       :height "145px"
       :child [re-com/v-box
               :style {:font-size 10 :font-family "monospace" :background "black" :color "green"}
               :children [(for [{:keys [aiTime eventName alias desc count]
                                 :caudal-client/keys [ts] :as e} @details]
                            ^{:key (pr-str ts)}
                            [re-com/box
                             :height "12px"
                             :child (pr-str e)
                             ])]]])))

(defn side-camera [state]
  [:div {:style {:height "12px"}}
   [:span {:style {:width 200}} (pr-str state)]
   ]
  )

(defn customs-box []
  (let [aggregate-by-place (re-frame/subscribe [[:customs :aggregate]])
        selected (r/atom "AD")]
    (fn []
      (let [curr-places @aggregate-by-place
            places (vec (sort-by :id (mapv (fn [place] {:id place :label (name place)})
                                           (keys curr-places))))
            sel @selected]
        (.log js/console "******** " (pr-str curr-places))
        (.log js/console "*****places *** " (pr-str places) "   *** sel :" (pr-str sel))
        (if (not (seq curr-places))
          [re-com/title :label "Please select a place" :level :level3]
          [re-com/v-box
           :children [[re-com/horizontal-tabs
                       :model selected
                       :tabs places
                       :id-fn :place
                       :on-change (fn [new-place] (reset! selected new-place))]
                      [re-com/h-box
                       :children [(for [position ["front"] side ["starboard" "port"]]
                                    ^{:key [position side]}
                                    [side-camera (get-in curr-places [@selected [position side]])])]]
                      [re-com/h-box
                       :children [(for [position ["back"] side ["starboard" "port"]]
                                    ^{:key [position side]}
                                    [side-camera (get-in curr-places [@selected [position side]])])]]
                      [re-com/h-box
                       :children [[side-camera (get-in curr-places [@selected ["plate"]])]]]]])))))

(defn main-panel []
  (let []
    (fn []
      [re-com/v-box
       :children [[re-com/h-box
                   :children [[re-com/title :label "Topic subscriber" :level :level4]
                              [re-com/gap :size "50px"]
                              [re-com/button
                               :label "Connect"
                               :on-click #(re-frame/dispatch [:connect])
                               ]
                              [re-com/button
                               :label "re-subscribe"
                               :on-click #(re-frame/dispatch [:register-topics])
                               ]]]
                  [customs-box]
                  [logger]]])))
