(ns caudal.topic-subscriber.views
  (:require [cljs.pprint :refer [pprint]]
            [reagent.core :as r]
            [re-frame.core :as re-frame]
            [re-com.core :as re-com]
            [reagent.core :as reagent :refer [atom create-class]]
            [cljs-time.format :as tf]
            [cljs-time.coerce :as tc]
            ))

(defn logger []
  (let [details (re-frame/subscribe [[:customs :detail]])]
    (fn []
      (let [dets @details]
        ;(.log js/console (pr-str dets))
        (.info js/console "numero de details: " (count dets))
        [re-com/scroller
         :v-scroll :auto
         :height "100px"
         ;:width "100%"
         :child [re-com/v-box
                 :children [(for [{:keys [eventName alias count plate desc] :caudal-client/keys [ts]} dets]
                              (let [ts (tf/unparse (tf/formatter "yyyyMMdd'T'HH:mm:ss.SSS") ts)]
                                ^{:key (pr-str ts)}
                                [re-com/h-box
                                 ;:width "100%"
                                 :style {:background "black" :color "green" :font-size "10px"}
                                 :children [[re-com/label
                                             :label ts ;(-> (pr-str ts) (subs  7 30))
                                             :width "15%"]
                                            [re-com/label
                                             :label alias
                                             :width "5%"]
                                            [re-com/label
                                             :label eventName
                                             :width "20%"]
                                            [re-com/label
                                             :label (or count plate)
                                             :width "10%"]
                                            [re-com/label
                                             :label desc
                                             :width "50%"]
                                            ]]))]]]))))

(defn side-camera [{:keys [count clip eventName ] :as state}]
  (if clip
    [re-com/h-box

     :children [[re-com/v-box
                 :align :center
                 :gap "10px"
                 :children [[:img {:style {:height "150px"}
                                   :src (str "data:image/jpg;base64," clip)
                                   }
                             ]
                            [re-com/button
                             :style {:background (cond
                                                   (= eventName :OnTrustedRead) "green"
                                                   (= eventName :OnUntrustedRead) "yellow"
                                                   :OTHERWISE "lightgrey")}
                             :label (str count " Pallets")]]]
                [re-com/gap :width "20px" :size "20px"]]]
    [re-com/title
     :label ""]))

(defn customs-box []
  (let [aggregate-by-place (re-frame/subscribe [[:customs :aggregate]])
        selected (r/atom nil)
        change-tab (fn [new-place] (reset! selected new-place))]
    (fn []
      (let [curr-places @aggregate-by-place
            places (vec (sort-by :id (mapv (fn [[place state]] {:id (keyword place) :label (name place) :state state})
                                           curr-places)))
            sel (or @selected (-> places first :id))]
        (.debug js/console "******** " (pr-str curr-places))
        (.debug js/console "*****places *** " (pr-str places) "   *** sel :" (pr-str sel))
        (if (not (seq curr-places))
          [re-com/title :label "Please select a place" :level :level3]
          [re-com/v-box
           ;:gap "30px"
           :children [[re-com/h-box
                       :align :center
                       :gap "10px"
                       :children [[re-com/horizontal-tabs
                                   :model sel
                                   :tabs places
                                   :id-fn :id
                                   :on-change change-tab]]]
                      [re-com/gap :size "30px"]
                      [re-com/h-box
                       :gap "10px"
                       :children [(if sel
                                    (for [side [:port :starboard] position [:front :back] ]
                                      ^{:key [position side]}
                                      [side-camera (get-in curr-places [sel [position side]])]))]]
                      [re-com/gap :size "30px"]
                      [re-com/h-box
                       ;:gap "10px"
                       :children [(if sel
                                    (let [{:keys [plate clip] :as e} (get-in curr-places [sel [:plate]])]
                                      [re-com/h-box
                                       :children [[re-com/v-box
                                                   :align :center
                                                   :gap "10px"
                                                   :children [[:img {:style {:height "150px"}
                                                                     :src (str "data:image/jpg;base64," clip)
                                                                     }
                                                               ]
                                                              [re-com/button
                                                               :style {:background "lightgrey"}
                                                               :label (str plate)]]]
                                                  [re-com/gap :width "20px" :size "20px"]]]))
                                  (if sel
                                    (let [{:keys [plate clip] :as e} (get-in curr-places [sel [:clip]])]
                                      [re-com/h-box
                                       :children [[re-com/v-box
                                                   :align :center
                                                   :gap "10px"
                                                   :children [[:div {:style {:height "150px"}} 
                                                               [:img {:style {:width "267px"}
                                                                      :src (str "data:image/jpg;base64," clip)}
                                                                ]]
                                                              [re-com/button
                                                               :style {:background "lightgrey"}
                                                               :label (str plate)]]]
                                                  [re-com/gap :width "20px" :size "20px"]]]))]]]])))))

(defn main-panel []
  (let []
    (fn []
      [re-com/v-box
       :gap "10px"
       :children [[re-com/h-box
                   :gap "10px"
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
