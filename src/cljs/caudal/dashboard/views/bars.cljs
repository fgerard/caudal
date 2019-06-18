(ns caudal.dashboard.views.bars
  (:require-macros [hiccups.core :as hiccups :refer [html]])
  (:require [reagent.core :as reagent]
            [re-frame.core :as re-frame]
            [re-mdl.core :as mdl]
            [goog.string :as gstring]
            [goog.string.format]
            [caudal.dashboard.util :as util])
  (:import [goog.i18n DateTimeFormat]))

(defn bar-graph [format]
  (let [update-fn (fn [format chart-obj]
                    (let [options (clj->js {:legend {:position "top", :maxLines 3}
                                            :isStacked true})
                          data-table (js/Arr2DataTable (clj->js format))]
                      (js/draw chart-obj data-table options)))]
    (reagent/create-class
     {:reagent-render (fn [format]
                        (let [;_ (reset! temp-comp format)
                              ]
                          [:div {:style {:height "100vh"}}])) ;.panel-body
      :component-did-mount (fn [comp]
                             (let [elem (reagent/dom-node comp)
                                   chart (js/BarChart elem)]
                               (update-fn format chart)))})))

(defn google-v11n-format-new [params data]
  (let [{:keys [value-fn tooltip] :or {value-fn :n tooltip [:n]}} (get params (first (ffirst data)))
        tooltip-fac (util/tooltipper-factory tooltip)
        tooltipper (tooltip-fac data)
        bars (reduce
              (fn [result [k v]]
                (let [type (last k)
                      x (last (butlast k))
                      val (value-fn v)]
                  (update result x (fn [bar-points]
                                     (let [bar-points (or bar-points {})]
                                       (assoc bar-points type val))))))
              {}
              data)
        headers (into (sorted-set) (map (fn [[k v]] (last k)) data))
        data (reduce
              (fn [result [x point-map]]
                (conj result (reduce
                              (fn [bar type]
                                (conj bar (get point-map type 0)))
                              [x]
                              headers)))
              [(into ["x"] headers)]
              (sort-by (fn [[k v]] (str k)) (fn [a b]
                                              (compare b a)) bars))
        ]
    data))

(defn panel-factory []
  (let [tabs (reagent/atom 0)]
    (fn panel [{:keys [data]} params]
      (let [;{:keys [data]} @(re-frame/subscribe [:state])
            ;selection (util/data-by-type data state-type)
            selection (util/data-with-key data (into #{} (keys params)))
            selection-by (sort-by #(str (first %)) #(* -1 (compare %1 %2)) (group-by (comp butlast butlast first) selection))
            sub-tabs (reduce (fn [r [group-name _]]
                               (conj r (str (first group-name))))
                             (sorted-set)
                             selection-by)
            panels (doall
                     (map-indexed (fn [i sub-tab]
                                    (let [content (into []
                                                        (map (fn [[group-name data]]
                                                               (let [gformat (google-v11n-format-new params data)]
                                                                 [:div.mdl-shadow--2dp ;.panel-float
                                                                  ;[util/inner-panel (str (into [] group-name))
                                                                  ; ]
                                                                  [bar-graph gformat]]))
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
