(ns caudal.dashboard.views.rate
    (:require [reagent.core :as reagent]
              [re-frame.core :as re-frame]
              [re-mdl.core :as mdl]
              [goog.string :as gstring]
              [goog.string.format]
              [caudal.dashboard.util :as util]
              [caudal.dashboard.db :as db])
    (:import [goog.i18n DateTimeFormat]))

(defn rate-graph [{:keys [columns data title created touched color min max] :as format}]
  (let [g-objects (reagent/atom nil)
        temp-comp (reagent/atom nil)
        update-fn (fn [{:keys [data title created touched color min max] :as format}]
                    (let [columns (clj->js columns)
                          options (clj->js {:hAxis {:title "timestamp"
                                                    :viewWindow {:min min :max max}
                                                    :gridlines {:count -1
                                                                :units {:days {:format ["MMM dd"]}
                                                                        :hours {:format ["HH:mm" "ha"]}}}
                                                    :minorGridlines {:units {:hours {:format ["hh:mm:ss a" "ha"]}
                                                                             :minutes {:format ["HH:mm a Z" ":mm"]}}}}
                                            :pointSize 3
                                            ;:isStacked true
                                            :vAxis {:minValue 0}
                                            :colors [color]
                                            ;:backgroundColor {:fill color :fillOpacity 0.1 :stroke "#fff"}
                                            :tooltip {:isHtml true}
                                            ;:chartArea {:backgroundColor {:fill "white" :fillOpacity 0.95}}
                                            ;:legend "none"
                                            ;:explorer {:axis "horizontal" :maxZoomIn 0.05 :keepInBounds true}
                                            })
                          data-table (:data-table @g-objects)
                          c-data (js/getNumberOfColumns data-table)
                          r-data (js/getNumberOfRows data-table)]
                      (if (= c-data 0)
                        (doseq [col columns]
                          (cond (vector? (js->clj col))
                                (let [[type name] col]
                                  (js/addColumn data-table type name))
                                (map? (js->clj col))
                                (js/addColumn data-table col))))
                      (js/removeRows data-table 0 r-data)
                      (js/addRows data-table (clj->js data))
                      (js/draw (:chart @g-objects) data-table options)))]
    (reagent/create-class
     {:reagent-render (fn [{:keys[columns data title created touched color min max] :as format}]
                        (let [_ (reset! temp-comp format)]
                          [:div.panel-body-rate]))
      :component-did-mount (fn [comp]
                             (let [elem (reagent/dom-node comp)
                                   chart (js/AreaChart elem)
                                   data (js/DataTable)]
                               (reset! g-objects {:chart chart :data-table data}))
                             (update-fn @temp-comp))
      :component-will-update (fn [_ [_ comp]]
                              (update-fn comp))
      })))
(defn google-v11n-format [data params]
  (let [{:keys [value-fn image-fn tooltip] :or {value-fn :caudal/height image-fn :caudal/png tooltip [:n]}} (get params (first (ffirst data)))
        _ (.log js/console (str {:value-fn value-fn :tooltip tooltip}))
        rates (util/data-by-type data "rate")
        rates (reduce (fn [r [k {:keys [buckets] :as v}]] (if (> (count buckets) 0) (assoc r k v) r)) {} rates) ; Delete empty buckets
        timeline (reduce (fn [r [k {:keys [buckets]}]] (into r (keys buckets))) (sorted-set) rates)
        begin (- (first timeline) (* 30 60 1000)) ; library offset ??
        end (+ (last timeline) (* 30 60 1000))]
    [(reduce (fn [r [k v]]
               (let [columns [["datetime" "timestamp"]
                              ["number" (last k)]
                              {:type "string" :role "tooltip" :p {:html true}}]
                     buckets (:buckets v)
                     color (:caudal/color v)
                     [data screen] (reduce (fn [[data screen] bucket-ts] ; using t, get slices ordered
                                             (let [bucket-data (get buckets bucket-ts)
                                                   time        (js/Date. bucket-ts)
                                                   value       (value-fn bucket-data)]
                                               [(conj data [time value (util/google-tooltip bucket-ts bucket-data value-fn image-fn)])
                                                (or (:caudal/png bucket-data) screen)]))
                                           [[] nil]
                                           timeline)]
                 (conj r {:title k
                          :columns columns
                          :data data
                          :color color
                          :screen screen})
                 ))
             []
             rates) begin end]))

#_(defn panel [{:keys [data]} _]
  (let [[data begin end] (google-v11n-format data)]
    (into [:div] (map (fn [{:keys [title color screen] :as f}]
                          [:div.row
                           [util/panel-color title
                            [rate-graph (assoc f :min (js/Date. begin) :max (js/Date. end))]
                            color
                            begin
                            end
                            screen]]) data))))

(defn panel-factory []
  (let [tabs (reagent/atom 0)]
    (fn panel [{:keys [data]} params]
      (let [selection (util/data-with-key data (into #{} (keys params)))
            selection-by (sort-by #(str (first %)) #(* -1 (compare %1 %2)) (group-by (comp butlast first) selection))
            sub-tabs (reduce (fn [r [group-name _]]
                               (conj r (str (first group-name))))
                             (sorted-set)
                             selection-by)
            panels (doall
                     (map-indexed (fn [i sub-tab]
                                    (let [content (into []
                                                        (map (fn [[group-name data]]
                                                               (let [[data begin end] (google-v11n-format data params)]
                                                                 (into [:div] (map (fn [{:keys [title color screen] :as f}]
                                                                                     [:div.row
                                                                                      [util/panel-color title
                                                                                       [rate-graph (assoc f :min (js/Date. begin) :max (js/Date. end))]
                                                                                       color
                                                                                       begin
                                                                                       end
                                                                                       screen]]) data))))
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
