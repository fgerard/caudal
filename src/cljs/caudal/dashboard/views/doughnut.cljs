(ns caudal.dashboard.views.doughnut
  (:require-macros [hiccups.core :as hiccups :refer [html]])
  (:require [reagent.core :as reagent]
            [re-frame.core :as re-frame]
            [re-mdl.core :as mdl]
            [goog.string :as gstring]
            [goog.string.format]
            [caudal.dashboard.util :as util])
  (:import [goog.i18n DateTimeFormat]))

(defn pie-graph [{:keys [columns data colors] :as format}]
  (let [;temp-comp (reagent/atom nil)
        update-fn (fn [{:keys [columns data colors] :as format} chart-obj]
                    (let [options (clj->js {:pieHole 0.5
                                            :pieSliceText "none"
                                            :pieStartAngle 100
                                            :colors colors
                                            :tooltip {:isHtml true}
                                            :backgroundColor {:fill "transparent"}
                                            ;:legend "none"
                                            })
                          data-table (js/Arr2DataTable (clj->js
                                                      (reduce
                                                       (fn [result row]
                                                         (conj result row))
                                                       [columns]
                                                       data)))]
                      (js/draw chart-obj data-table options)))]
    (reagent/create-class
     {:reagent-render (fn [{:keys [columns data colors] :as format}]
                        (let [;_ (reset! temp-comp format)
                              ]
                          [:div.panel-body]))
      :component-did-mount (fn [comp]
                             (let [elem (reagent/dom-node comp)
                                   chart (js/PieChart elem)]
                               (update-fn format chart)))})))


(defn google-v11n-format-new [params data ];value-fn tooltip-fn
  (let [{:keys [value-fn tooltip] :or {value-fn :n tooltip [:n]}} (get params (first (ffirst data)))
        tooltip-fac (util/tooltipper-factory tooltip)
        tooltipper (tooltip-fac data)        
        
        detail-fac (util/detailer-factory tooltip)
        detailer (detail-fac data)

        data {:columns [["string" "Title"] ["number" "Value"] {:type "string" :role "tooltip" :p {:html true}}
                        ]
              :data (mapv
                     (fn [[k v]]
                       (let [k (last k)]
                         [(str k) (value-fn v) (tooltipper k v)
                          ]))
                     data)
              :detail (conj [(into [["name"] ["%"]] (map #(vector (name %)) tooltip))] 
                            (mapv
                             (fn [[k v]]
                               (let [k (last k)]
                                 (detailer k v)))
                             data))
              ;:title (get (ffirst data) 3)
              :colors (mapv :caudal/color (map second data))}]
    data))

;:params {:success-vs-error {:value-fn :n :tooltip [:n]},
 ;        :hourly-stats {:value-fn :mean, :tooltip [:mean :stdev :count]},
 ;        :hourly-flow-stats {:value-fn :mean, :tooltip [:mean :stdev :count]}}


(defn panel-factory []
  (let [tabs (reagent/atom 0)]
    (fn panel [{:keys [data]} params] ;{:keys [state-type value-fn tooltip-fn]}]
      (let [selection (util/data-with-key data (into #{} (keys params))) ;(util/data-by-type data state-type)
            selection-by (sort-by
                          #(str (first %))
                          #(* -1 (compare %1 %2))
                          (group-by
                           (comp butlast first)
                           selection))
            sub-tabs (reduce (fn [r [group-name _]]
                               (conj r (str (first group-name))))
                             (sorted-set)
                             selection-by)
            panels (doall
                     (map-indexed
                      (fn [i sub-tab]
                        (let [content (into []
                                            (map (fn [[group-name data]]
                                                   (let [{:keys [detail] :as gformat} (google-v11n-format-new params data)]
                                                     [:div.panel-float.mdl-shadow--2dp
                                                      [util/inner-panel (str (into [] group-name))
                                                       [:div.panel-row [pie-graph gformat]
                                                        [:div.panel-body [mdl/table 
                                                                          :selectable? false                                                         
                                                                          :headers (first detail)
                                                                          :rows (sort-by #(js/parseFloat (second %))
                                                                                         >
                                                                                         (first (rest detail)))]]]]
                                                      ]))
                                                 (filter #(= sub-tab (str (ffirst %))) selection-by)))
                              active (= i @tabs)]
                          (if active
                            ^{:key sub-tab}[mdl/tabs-panel :id (str "tab" (hash sub-tab)) :is-active? true :children content]
                            ^{:key sub-tab}[mdl/tabs-panel :id (str "tab"(hash sub-tab)) :children content])
                          ))
                      sub-tabs))]
        [mdl/tabs
         :ripple-effect? true
         :children (conj [[mdl/tab-bar
                           :children
                           (into
                            []
                            (map-indexed (fn [i sub-tab]
                                           (cond-> [mdl/tab :href (str "#tab" (hash sub-tab)) :child (str sub-tab)
                                                    :attr {:on-click (fn [e]
                                                                       (reset! tabs i))}]
                                                   (= i @tabs) (concat [:is-active? true])
                                                   true (vec)))
                                         sub-tabs))]] panels)]))))
