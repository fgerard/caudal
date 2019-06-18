;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns caudal.config.robot
  (:require
   [clojure.java.io :as io]
   [clojure.string :as S]
   [clojure.tools.logging :as log]
   [clojure.java.shell :as sh]
   [caudal.streams.common :refer :all]
   [caudal.io.rest-server :refer :all]
   [caudal.streams.stateful :refer :all]
   [caudal.streams.stateless :refer :all]
   [caudal.io.email :refer [mailer]]
   [caudal.core.folds :refer [rate-bucket-welford]]
   [caudal.util.date-util :as DU]
   [clara.rules :refer :all]
   [clara.rules.accumulators :as acc]
   [clara.tools.tracing :as tracing]))

; funcionex de utileria para manejo de boxes y distancias

(defn sqr
  "Funcion que eleva al cuadrado"
  [a]
  (* a a))

(defn avg
  "Funcion que calcula el promedio entre 2 números"
  [a b]
  (/ (+ a b) 2))

(defn center-of
  "Calcula el cetro del box (vector de 4 coordenadas [x0,y0 x1,y1])
   que es el valor relacionado en un mapa bajo la llave box-key"
  [box-key object]
  (if (and object (seq (box-key object)))
    (if-let [[x0 y0 x1 y1] (box-key object)]
      [(avg x0 x1) (avg y0 y1)])))

(defn distance
  "Calcula la distancia entre 2 puntos"
  [[xc0 yc0 :as c0] [xc1 yc1 :as c1]]
  (if (and (seq c0) (seq c1))
    (let [dst (Math/sqrt (+ (sqr (- xc1 xc0)) (sqr (- yc1 yc0))))]
      dst)))

; Calcula el centro de un objeto que almacena su box en la llave :box
(def center-of-box (partial center-of :box))

(defn object-distance
  "Calcula la distancia entre los centros de dos objectos"
  [obj0 obj1]
  (distance (center-of-box obj0) (center-of-box obj1)))

(defn get-nearest-center
  "Encuentra el indice, el objeto y la distancia del objeto de un tipo de clase
   almacenado en un vector de objetos más cercano a unas coordenadas"
  [obj-class-set min-score [load-area-x load-area-y :as load-area-center] objects & {:keys [exclude-idx]}]
  (if (and load-area-center (seq objects))
    (reduce
     (fn [[main-idx main-obj dst] [idx object]]
       ;(println (pr-str [main-idx main-obj]) (pr-str [idx object]))
       (if (and
            (obj-class-set (:class object))
            (> (:score object) min-score))
         (if main-obj
           (let [dst-obj2main (distance load-area-center (center-of-box object))]
             (if (< dst-obj2main dst)
               [idx object dst-obj2main]
               [main-idx main-obj dst]))
           [idx object (distance load-area-center (center-of-box object))])
         [main-idx main-obj dst]))
     nil ;[nil nil nil]
     (->> objects
          (map-indexed (fn [idx obj]
                         [idx obj]))
          (remove (fn [[idx obj]]
                    (= idx exclude-idx)))))))

(def MIN-TARIMA-SCORE 0.5)
(def MIN-TRAILER-SCORE 0.6)

; Funcion que encuentra el trailer más cercano a una pocision dada
(def get-main-trailer-center (partial get-nearest-center #{"trailer"} MIN-TRAILER-SCORE ))

(defn get-box-center
  "Obtiene el centro teórico del box asociado a la camara, son coordenadas normalizadas"
  [camera]
  [0.5 0.5]) ;por ahora fijo al centro del video

(defn event-prep
  "Función que prepara el evento que llega de la red neuronal, arregla el ts 'now' y lo convierte
   a número de milis, y añade main-trailer-idx (indice del objecto tipo trailer más cercano al centro),
   main-tarima-idx (indice de la tarima cuyo cento está más cercano al main-trailer) y también añade
   la distancia entre el main-trailer y el main-tarima"
  [{:keys [camera objects now] :as event}]
  (let [sdf (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSS") ; python format 2018-07-26T18:45:13.982543
        now (-> (.parse sdf (subs now 0 23)) .getTime) ; truncate 982543 -> 982 ms
        box-center (get-box-center camera)
        [main-trailer-idx main-trailer dist-trailer-center] (get-main-trailer-center box-center objects)
        [nearest-tarima-idx nearest-tarima dist-trailer-tarima] (get-nearest-center
                                                                 #{"tarima" "montacargas"} MIN-TARIMA-SCORE
                                                                 (center-of-box main-trailer)
                                                                 objects :exclude-idx main-trailer-idx)]
    (assoc event
           :now now
           :main-trailer-idx main-trailer-idx
           :main-tarima-idx nearest-tarima-idx
           :trailer-tarima-dst dist-trailer-tarima)))

(defn process-window [dst-keyword threshold-dst window]
  (let [n (count window)
        positive (->> window
                      (remove #(nil? (dst-keyword %)))
                      (filter #(>= threshold-dst (dst-keyword %)))
                      count)]
    (/ (float positive) n)))

(def percent-of-near-trailer-tarima-dst (partial process-window :trailer-tarima-dst 0.25))

(defn IoU [boxA boxB]
  (let [yA (max (boxA 0) (boxB 0))
        xA (max (boxA 1) (boxB 1))
        yB (min (boxA 2) (boxB 2))
        xB (min (boxA 3) (boxB 3))
        intersect (* (max 0 (- xB xA)) (max 0 (- yB yA)))
        boxAarea (* (- (boxA 2) (boxA 0)) (* (boxA 3) (boxA 1)))
        boxBarea (* (- (boxB 2) (boxB 0)) (* (boxB 3) (boxB 1)))
        divisor (- (+ boxAarea boxBarea) intersect)
        iou (if (not= 0 divisor) (/ intersect divisor) 0)]
    iou))

(defn calc-event-state [{:keys [camera cameraName now objects
                                main-trailer-idx main-tarima-idx
                                trailer-tarima-dst]
                         :as event}]
  (cond
    (not main-trailer-idx) :empty
    (or (not trailer-tarima-dst) (> trailer-tarima-dst 0.05)) :trailer
    (and main-trailer-idx trailer-tarima-dst (<= trailer-tarima-dst 0.05)) :loading
    :OTHERWISE :unknown))

(defn next-state [prev next]
  (if (= prev next)
    next
    (condp = [prev next]
      [nil :trailer] :trailer
      [:empty :trailer] :trailer
      [:trailer :loading] :loading
      [:trailer :empty] :empty
      [:loading :trailer] :loading-waiting
      [:loading-waiting :trailer] :loading-waiting
      [:loading-waiting :loading] :loading
      [:loading-waiting :empty] :finish-loading
      [:loading :empty] :finish-loading
      [:finish-loading :empty] :empty
      [:finish-loading :trailer] :trailer)))

(defn touch-corrector [{:keys [posible-state cnt
                               first-ts last-ts
                               finish-loading-ts
                               load-cnt]
                        :or {load-cnt 0}
                        :as corrector}
                       event]
  (let [last-event-state (calc-event-state event)
        last-event-state (next-state posible-state last-event-state)]
    (if (= posible-state last-event-state)
      (-> corrector
          (update :cnt inc)
          (assoc :last-ts (System/currentTimeMillis)))
      (let [now (System/currentTimeMillis)]
        (cond-> {:posible-state last-event-state
                 :cnt 1
                 :first-ts now
                 :last-ts now
                 :finish-loading-ts finish-loading-ts
                 :load-cnt load-cnt}
                (= last-event-state :loading-waiting) (assoc :finish-loading-ts now)
                (= last-event-state :loading-waiting) (assoc :load-cnt (inc load-cnt))
                (#{:empty :trailer} last-event-state) (dissoc :finish-loading-ts :load-cnt)
                )))))


(defn activity-reducer [{:keys [state ts0 tsN change]
                         :or {state :empty
                              ts0 (System/currentTimeMillis)
                              tsN ts0}
                         :as state-map}
                        {:keys [camera cameraName now objects
                                main-trailer-idx main-tarima-idx
                                trailer-tarima-dst]
                         :as event}]
  (let [state-map {:state state
                   :ts0 ts0
                   :tsN tsN
                   :change change}
        {:keys [posible-state cnt first-ts last-ts finish-loading-ts load-cnt]
         :as corrector} (touch-corrector change event)]
    (if (or (= posible-state state) (< cnt 3))
      (assoc state-map :change corrector)
      {:state posible-state
       :ts0 first-ts
       :tsN last-ts
       :finish-loading-ts finish-loading-ts
       :load-cnt load-cnt
       :change corrector})))

(defn store-event [file-name event]
  (with-open [out (io/writer file-name :append true)]
    (.write out (str (pr-str event) "\n")))
  event)

(deflistener rest-server [{:type 'caudal.io.rest-server
                           :parameters {:host "localhost"
                                        :http-port 8070
                                        :cors #".*"}}])

(def activity-load-unload
  (smap [(fn [e]
           (assoc e :t0 (System/currentTimeMillis)))]
        (pprinte)
        (smap [store-event "info-orig.edn"]
              (smap [event-prep]
                    ;(comment reduce-with [:d-state activity-reducer]
                    ;             (smap [store-event "info.edn"])
                    ;             (pprinte))
                    (pprinte)
                    (moving-time-window [:window 10000 :now]
                                        (smap [store-event "info-w.edn"])
                                        (smap [(fn [window]
                                                 (let [near (percent-of-near-trailer-tarima-dst window)
                                                       t0 (:t0 (get window (dec (count window))))]
                                                   ;(println "#### > " (count window))
                                                   {:near near
                                                    :count (count window)
                                                    :t0 t0
                                                    :delta (- (System/currentTimeMillis) t0)}))]
                                              (pprinte)))
                    ))))

(defpersistent-sink activity 1 "sink-data"
  activity-load-unload)

(wire [rest-server] [activity])

(web
 {:http-port 8080
  :publish-sinks [activity]})






;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment
 {:state :empty
  :ts 123123123123
  :change {:new-state :trailer
           :cnt 1
           :ts 23423423423}}

 (defn event-state [trailer tarima dst]
   (println "*** DST:" dst)
   (cond
     (not trailer) :empty
     (or (not dst) (> dst 0.05)) :trailer
     (and trailer dst (<= dst 0.05)) :loading
     :OTHERWISE :unknown))

 (defmulti transition (fn [{:keys [state ts change]
                            :or {state :empty
                                 ts (System/currentTimeMillis)}
                            :as state-map}
                           {:keys [camera cameraName now objects
                                   main-trailer-idx main-tarima-idx
                                   trailer-tarima-dst]
                            :as event}]
                        [state (event-state main-trailer-idx main-tarima-idx trailer-tarima-dst)]))

 (comment defmethod transition [:empty :empty] [{:keys [state ts change]
                                                 :or {state :empty
                                                      ts (System/currentTimeMillis)}
                                                 :as state-map}
                                                {:keys [camera cameraName now objects
                                                        main-trailer-idx main-tarima-idx
                                                        trailer-tarima-dst]
                                                 :as event}]
          {:state state :ts ts :change (or change)})

 (defn calc-change [prev-state calc-new-state {:keys [new-state cnt new-ts] :as change}]
   (println "********** " :prev-state prev-state :new-state new-state :change change)
   (cond
     (= prev-state calc-new-state)
     {:new-state prev-state :cnt (inc cnt) :new-ts new-ts}

     (not change)
     {:new-state prev-state :cnt 1 :new-ts (System/currentTimeMillis)}

     :OTHERWIZE
     {:new-state new-state :cnt 1 :new-ts (System/currentTimeMillis)}))

 (defn transition  [{:keys [state ts change] ;[:empty :trailer]
                     :or {state :empty
                          ts (System/currentTimeMillis)}
                     :as state-map}
                    {:keys [camera cameraName now objects
                            main-trailer-idx main-tarima-idx
                            trailer-tarima-dst]
                     :as event}]
   (let [calc-new-state (event-state main-trailer-idx main-tarima-idx trailer-tarima-dst)
         {:keys [new-state cnt new-ts] :as new-change} (calc-change state calc-new-state change)]
     (cond
       (and (not= state new-state) (> cnt 5))
       {:state new-state
        :ts new-ts
        :change nil}

       (not= state new-state)
       {:state state
        :ts ts
        :change new-change}

       :OTHERWIZE
       {:state state
        :ts ts
        :change new-change})))
 )
