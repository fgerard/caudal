;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns mx.interware.caudal.util.date-util
  (:import (java.util Date Calendar TimeZone)
           (java.text SimpleDateFormat)
           ;(java.time LocalDate)
           ))

(def ymd-format "yyyy-MM-dd")
(def ymdhms-format "yyyy-MM-dd HH:mm:ss.SSS")
(def zulu-format "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

(defn compute-rate-bucket
  ([time-zone-name millis bucket-size]
   (let [date (Date. millis)
         time-zone-utc (TimeZone/getTimeZone "UTC")
         cal-utc (doto
                   (Calendar/getInstance time-zone-utc)
                   (.setTime date))
         utc-day (.get cal-utc Calendar/DAY_OF_MONTH)
         time-zone (TimeZone/getTimeZone time-zone-name)
         cal (doto
               (Calendar/getInstance time-zone)
               (.setTime date))
         day (.get cal Calendar/DAY_OF_MONTH)
         hour (.get cal Calendar/HOUR_OF_DAY)
         minute (.get cal Calendar/MINUTE)
         bucket (int (/ minute bucket-size))
         tz-offset (.get cal Calendar/ZONE_OFFSET)
         tz-offset (cond
                     (< tz-offset 0) -1
                     (> tz-offset 0) +1
                     :OTHERWIZE 0)
         day-offset (if-not (= utc-day day) tz-offset 0)
         origin-day (+ (int (/ millis 1000 3600 24)) day-offset)]
     [origin-day day hour bucket]))
  ([millis bucket-size]
   (compute-rate-bucket "America/Mexico_City" millis bucket-size)))

(comment defn compute-rate-bucket-slower-but-newer
  ([time-zone-name millis bucket-size]
   (let [date (Date. millis)
         time-zone (TimeZone/getTimeZone time-zone-name)
         cal (doto
              (Calendar/getInstance time-zone)
              (.setTime date))
         y (.get cal Calendar/YEAR)
         m (.get cal Calendar/MONTH)
         dOm (.get cal Calendar/DAY_OF_MONTH)
         local (LocalDate/of y (inc m) dOm)
         hour (.get cal Calendar/HOUR_OF_DAY)
         minute (.get cal Calendar/MINUTE)
         bucket (int (/ minute bucket-size))
         origin-day (.toEpochDay local)]
     [origin-day dOm hour bucket]))
  ([millis bucket-size]
   (compute-rate-bucket "America/Mexico_City" millis bucket-size)))


(defn current-date [] (new Date))

(defn current-millis []
  (.getTime (current-date)))

(defn parse-date [date-fmt date-str]
  (let [sdf           (SimpleDateFormat. date-fmt)
        len           (count date-str)
        date-fmt-len  (count date-fmt)
        date-adjusted (if (< len date-fmt-len)
                        (apply str date-str (repeat (- date-fmt-len len) "0"))
                        date-str)]
    (.parse sdf date-adjusted)))

(defn format-date [date-fmt date]
  (let [sdf (SimpleDateFormat. date-fmt)]
    (.format sdf date)))

(defn current-ymd-date-str []
  (.format (SimpleDateFormat. ymd-format) (Date.)))

(defn current-ymdhms-time-str []
  (.format (SimpleDateFormat. ymdhms-format) (Date.)))

(defn current-zulu-time-str []
  (.format (SimpleDateFormat. zulu-format) (Date.)))

(def time-metric-map
  {:second  1000
   :seconds 1000
   :minute  60000
   :minutes 60000
   :hour    3600000
   :hours   3600000
   :day     86400000
   :days    86400000
   :week    604800000
   :weeks   604800000})

(defn cycle->millis [cycle-expr]
  (cond
    (number? cycle-expr)
    cycle-expr

    (vector? cycle-expr)
    (let [[n metric] cycle-expr
          millis-factor (metric time-metric-map)]
      (* n millis-factor))))
