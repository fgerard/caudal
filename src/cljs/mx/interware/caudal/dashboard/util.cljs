(ns mx.interware.caudal.dashboard.util
  (:require-macros [hiccups.core :as hiccups :refer [html]])
  (:require   [goog.string :as gstring]
              [goog.string.format]
              [re-mdl.core :as mdl]
              [hiccups.runtime :as hiccupsrt])
  (:import [goog.i18n DateTimeFormat NumberFormat]))

(def DTF_FULL_DATE (DateTimeFormat. 0))
(def DTF_LONG_DATE (DateTimeFormat. 1))
(def DTF_MEDIUM_DATE (DateTimeFormat. 2))
(def DTF_SHORT_DATE (DateTimeFormat. 3))
(def DTF_FULL_TIME (DateTimeFormat. 4))
(def DTF_LONG_TIME (DateTimeFormat. 5))
(def DTF_MEDIUM_TIME (DateTimeFormat. 6))
(def DTF_SHORT_TIME (DateTimeFormat. 7))
(def DTF_FULL_DATETIME (DateTimeFormat. 8))
(def DTF_LONG_DATETIME (DateTimeFormat. 9))
(def DTF_MEDIUM_DATETIME (DateTimeFormat. 10))
(def DTF_SHORT_DATETIME (DateTimeFormat. 11))

(defn format-num [n]
  (.format (NumberFormat. (.-DECIMAL NumberFormat.Format)) (js/parseFloat n)))

(def COLORS ["#f44336" "#E91E63" "#9C27B0" "#673AB7" "#3F51B5" "#009688" "#795548" "#CFD8DC" "#B0BEC5" "#BA68C8" "#9575CD" "#7986CB" "#A1887F" "#90A4AE" "#ef5350" "#EC407A" "#AB47BC" "#7E57C2" "#5C6BC0" "#8D6E63" "#f44336" "#E91E63" "#9C27B0" "#673AB7" "#3F51B5" "#009688" "#795548" "#e53935" "#D81B60" "#8E24AA" "#5E35B1" "#3949AB" "#1E88E5" "#00897B" "#43A047" "#F4511E" "#6D4C41" "#757575" "#d32f2f" "#C2185B" "#7B1FA2" "#512DA8" "#303F9F" "#1976D2" "#0288D1" "#0097A7" "#00796B" "#388E3C" "#E64A19" "#5D4037" "#616161" "#c62828" "#AD1457" "#6A1B9A" "#4527A0" "#283593" "#1565C0" "#0277BD" "#00838F" "#00695C" "#2E7D32" "#558B2F" "#D84315" "#4E342E" "#424242" "#b71c1c" "#880E4F" "#4A148C" "#311B92" "#1A237E" "#0D47A1" "#01579B" "#006064" "#004D40" "#1B5E20" "#33691E" "#827717" "#E65100" "#BF360C" "#3E2723" "#212121" "#ff5252" "#FF4081" "#E040FB" "#7C4DFF" "#536DFE" "#448AFF" "#ff1744" "#F50057" "#D500F9" "#651FFF" "#3D5AFE" "#2979FF" "#FF3D00" "#d50000" "#C51162" "#AA00FF" "#6200EA" "#304FFE" "#2962FF" "#0091EA" "#DD2C00" "#2196F3" "#03A9F4" "#03A9F4" "#00ACC1" "#00BCD4" "#00B8D4" "#26A69A" "#00BFA5" "#4CAF50" "#00C853" "#8BC34A" "#9E9D24" "#F57F17" "#F9A825" "#FF6F00" "#FF8F00" "#FF9800" "#EF6C00" "#F57C00" "#FF6D00" "#FF5722" "#FF6E40"])

(def colormap (atom {}))

(let [good-iri-char  "a-zA-Z0-9\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF"
      iri            (str  "[" good-iri-char "]([" good-iri-char "\\-]{0,61}[" good-iri-char "]){0,1}")
      good-gtld-char "a-zA-Z\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF"
      gtld           (str "[" good-gtld-char "]{2,63}")
      host-name      (str "(" iri "\\.)+" gtld)
      ip-addr        "((25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9])\\.(25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9]|0)\\.(25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9]|0)\\.(25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}|[1-9][0-9]|[0-9]))"
      domain-name    (str  "(" host-name "|" ip-addr "|localhost)")
      web-url        (str "((?:(http|https|Http|Https):\\/\\/(?:(?:[a-zA-Z0-9\\$\\-\\_\\.\\+\\!\\*\\'\\(\\)\\,\\;\\?\\&\\=]|(?:\\%[a-fA-F0-9]{2})){1,64}(?:\\:(?:[a-zA-Z0-9\\$\\-\\_\\.\\+\\!\\*\\'\\(\\)\\,\\;\\?\\&\\=]|(?:\\%[a-fA-F0-9]{2})){1,25})?\\@)?)?(?:" domain-name ")(?:\\:\\d{1,5})?)(\\/(?:(?:[" good-iri-char "\\;\\/\\?\\:\\@\\&\\=\\#\\~\\-\\.\\+\\!\\*\\'\\(\\)\\,\\_])|(?:\\%[a-fA-F0-9]{2}))*)?(?:\\b|$)")]
  (defn url? [v]
    "
    Evaluates a string v and return true if v is a valid URL
    "
    (re-matches (re-pattern web-url) v)))

(defn transpose [m]
  (apply mapv vector m))

(defn data-by-type
  "
  Return data with :caudal/type equal to type-str.

  Attach :caudal/color in each, util for graphs
  "
  [data type-str]
  (let [color-cnt (count COLORS)]
    (->> data
         (filter #(= (:caudal/type (second %)) type-str))
         (map-indexed (fn [idx [k v]]
                        [k (assoc v :caudal/color (COLORS (mod idx color-cnt)))]))
         (into (sorted-map)))))

(defn data-with-key
  [data key-set]
  (let [color-cnt (count COLORS)]
    (->> data
         (filter (fn [[k v]]
                   (and (vector? k)
                        (key-set (first k)))))
         (map-indexed (fn [idx [k v]]
                        (let [color (get @colormap (last k))
                              color (if color color
                                        (let [new-color (COLORS (mod idx color-cnt))]
                                          (swap! colormap assoc (last k) new-color)
                                          new-color))]
                          [k (assoc v :caudal/color color)])))
         (into (sorted-map)))))

(defn tooltipper-factory [tooltip-elems]  
  (fn [data]
    (let [tot (reduce + (map (fn [[k v]]
                               ((first tooltip-elems) v)) data))]
      (fn [k v]
        (html
         (conj
          (reduce
           (fn [table elem]
             (conj table
                   ;[:tr [:th (name elem)] [:td (format-num (elem v))]]
                   [:tr [:th (name elem)] [:td (format-num (str (elem v)))]]
                   ))
           [:table {:class "table"}
            [:tr [:th "name"] [:td (str k)]]]
           tooltip-elems)
          [:tr [:th "%"] [:td (format-num (/ (* 100 ((first tooltip-elems) v)) tot))]]))))))

(defn detailer-factory [tooltip-elems]
  (fn [data]
    (let [tot (reduce + (map (fn [[k v]]
                               ((first tooltip-elems) v)) data))]
      (fn [k v]
        (reduce
         (fn [row elem]
           (conj row (format-num (str (elem v)))))
         [k (format-num (/ (* 100 ((first tooltip-elems) v)) tot))]
         tooltip-elems)))))

(defn google-tooltip
  "
  Return a string into tooltip info for an area chart (see rate.clj)

  This string contains HTML code, resolved automatically via hiccups/html from hiccup
  "
  [timestamp slice value-fn image-fn]
  (let [formatter  DTF_MEDIUM_DATETIME
        time  (js/Date. timestamp)
        value-fn (or value-fn :caudal/height)
        image-fn (or image-fn :caudal/png)
        value (format-num (value-fn slice))
        png   (image-fn slice)]
    (html
     [:table {:class "table" }
      [:tr {:style {:opacity 1}} [:th "timestamp"] [:td (.format formatter time)]]
      [:tr {:style {:opacity 1}} [:th "value"] [:td value]]
      (if png
        [:tr {:style {:opacity 1}} [:th "screen"] [:td [:img {:src png :height "150px"}]]])])))

(defn panel-color
  "
  Creates a panel with its header colored in color

  If created and touched are not nil (i.e. both are valid unixepoch), shows a footer
  "
  ([title panel-body color created touched]
   (panel-color title panel-body color created touched nil))
  ([title panel-body color created touched screen]
   [mdl/card
    :attr {:style {:margin-top "5px"}}
    :class "panel-body-rate"
    :shadow 2
    :children [
               [mdl/card-supporting-text
                :id (hash title)
                :attr {:style {:background-color color :color "white"}}
                :children [(str title)]]
               [mdl/card-title
                :class "panel-body-rate"
                :attr {:style {:height "400px"}}
                :header :div
                :child panel-body]
               [mdl/card-menu
               :children [[mdl/button
                           :id title
                           :class "counter-menu"
                           :icon? true
                           :child [:i.material-icons "more_vert"]]
                          [mdl/menu
                           :for title
                           :bottom-right?   true
                           :ripple-effect? true
                           :children [
                                      [mdl/menu-item :child [:span
                                                             [:span.key (str "created:")]
                                                             [:span.value (.format DTF_FULL_DATETIME (js/Date. created))]]]
                                      [mdl/menu-item :child [:span
                                                             [:span.key (str "modified:")]
                                                             [:span.value (.format DTF_FULL_DATETIME (js/Date. touched))]]]]]
                          (if screen
                            [:span.mdl-button.mdl-js-button.counter-menu.mdl-button--icon [:a {:href screen :target "_blank"} [:i.material-icons "image"]]])]]]]))

(defn button-toggle
  "
  "
  [title color]
  (let [panel-id (str "#" (hash title))
        icon-id (str "#button-" (hash title))]
    (letfn [(toggle [e]
              (if-let [position (-> (.getElementById js/document (hash title)) .-style .-position)]
                (if (= position "absolute")
                  (js/increasePanel icon-id panel-id)
                  (js/collapsePanel icon-id panel-id))))]
      [:button.btn.btn-primary.toggle {:id (str "button-" (hash title))
                                       :on-click #(toggle %)
                                       :style {:border-color color :background-color color}
                                       :title title}
       [:span [:i.material-icons "visibility"] [:span (str title)]]])))

(defn counter-color [count label color created touched]
  [mdl/card
   :class "counter-color"
   :shadow 2
   :children [[mdl/card-title
               :attr {:style {:background-color color :color "white" :height "150px"}}
               :header :h2
               :child count]
              [mdl/card-supporting-text
               :id (hash label)
               :children [label]]
              [mdl/tooltip
               :for (hash label)
               :top?     true
               :children [label]]
              [mdl/card-menu
               :children [[mdl/button
                           :id label
                           :class "counter-menu"
                           :icon? true
                           :child [:i.material-icons "more_vert"]]
                          [mdl/menu
                           :for label
                           :bottom-right?   true
                           :ripple-effect? true
                           :children [[mdl/menu-item :child [:span
                                                             [:span.key (str "created:")]
                                                             [:span.value (.format DTF_FULL_DATETIME (js/Date. created))]]]
                                      [mdl/menu-item :child [:span
                                                             [:span.key (str "modified:")]
                                                             [:span.value (.format DTF_FULL_DATETIME (js/Date. touched))]]]]]]]]])

(defn inner-panel [panel-heading content]
  [:div.mdl-card__supporting-text.nested-panel
   [:p panel-heading]
   [:div
    content]])
