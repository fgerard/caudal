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
   [mx.interware.caudal.streams.common :refer :all]
   [mx.interware.caudal.io.rest-server :refer :all]
   [mx.interware.caudal.streams.stateful :refer :all]
   [mx.interware.caudal.streams.stateless :refer :all]
   [mx.interware.caudal.io.email :refer [mailer]]
   [mx.interware.caudal.core.folds :refer [rate-bucket-welford]]
   [mx.interware.caudal.util.date-util :as DU]
   [clara.rules :refer :all]
   [clara.rules.accumulators :as acc]
   [clara.tools.tracing :as tracing]))

; funcionex de utileria para manejo de boxes y distancias

(defn avg
  "Funcion que calcula el promedio entre 2 números"
  [a b]
  (/ (+ a b) (float 2)))

(defn center-of
  "Calcula el cetro del box (vector de 4 coordenadas [x0,y0 x1,y1])"
  [[x0 y0 x1 y1 :as box]]
  (when (and x0 y0 x1 y1)
    [(avg x0 x1) (avg y0 y1)]))

(defn distance
  "Calcula la distancia entre 2 puntos"
  [[x0 y0 :as p0] [x1 y1 :as p1]]
  (if (and x0 y0 x1 y1)
    (Math/sqrt
     (+ (Math/pow (- x1 x0) 2)
        (Math/pow (- y1 y0) 2)))))

; Calcula el centro de un objeto que almacena su box en la llave :box
(defn center-of-box [{:keys [box]}]
  (center-of box))

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

; a continuacion defino qué se almacena en la reducción de la máquina de estados
; que procesa la sliding-time-window y se queda con el estado actual
;
; TERMINOS:
; - "lectura-ai" un objeto json enviado de python a caudal
;      ej: {:camera 102,
;           :ai_ts "2018-07-04T22:27:39.949569",
;           :objects [{:class "trailer",
;                      :score 0.9854250550270081,
;                      :box [0.3109 0.3379 0.7721 0.6525]}
;                     ...]}
;
; - "area-de-trabajo" Espacio marcado en e piso en donde se hace la carga y
;                     descarga de un trailer
;
; - "umbral-configurable" Numero entre 0 y 1, el nombre de la constante define
;                         el uso particular
; - "centro-de-box" Coordenadas de un punto al centro de un rectangulo (box)
;                   correspondiente a un objeto detectado en una lectura-ai
;
; - "trailer-detectado" significa que en una lectura-ai existe un objeto
;                       de clase "trailer", cuyo centro de box se encuentra
;                       a una distancia menor o igual a un umbral-configurable
;                       al centro del area-de-trabajo y que el score es mayor
;                       o igual a otro umbral-configurable
;
; 1. Cuando no hay trailer:
;      {:estado :vacio}
; 2. Cuando hay trailer y no ha iniciado la carga
;      {:estado :iniciando
;       :iniciado millis-iniciado"}
;    a. cómo se pasa de :vacio -> :iniciando
;         - en la ventana de tiempo existe un 100% de lecuras-ai con trailer detectado
;           el atributo :iniciado toma el valor del ai_ts primero (el más viejo) en
;           la ventana
; 3. Cuando hay un trailer y ha iniciado la carga
;      {:estado :cargando
;       :iniciado millis
;       :interacciones +1
;       :primera-inter millis-primera
;       :ultima-inter millis-ultima}
;    a. cómo se pasa de :iniciando -> :cargando
;         - en una ventana  existe un umbral-configurable (en porcentaje) de lecturas-ai
;           con trailer-detectado y una tarima o montacargas cuyo centro-de-box se
;           encuentra a una distacia igual o menor a otro umbral-configurable, se
;           almacena en interacciones un "0" y en primer-iter y ultima-inter el valor
;           del ai_ts primero (el más viejo) en la ventana
;    b. cómo se pasa de :cargando -> :trabajando
;         - estando en :cargando y en una ventana no se logra el umbral-umbral-configurable
;           (en porcentaje) del punto anterior, pasamos a estado trabajando, y dejamos
;           el estado reducción asi:
;      {:estado :trabajando
;       :iniciado millis
;       :interacciones =queda igual=
;       :primera-inter =queda igual=
;       :ultima-inter "ai_ts del más reciente montacargas en la ventana"}
;    c. cómo se pasa de :trabajando -> :cargando
;         - en una ventana NO existe un umbral-configurable (en porcentaje) de lecturas-ai
;           con trailer-detectado y una tarima o montacargas cuyo centro-de-box se
;           encuentra a una distacia igual o menor a otro umbral-configurable,el estado
;           reducción queda asi:
;      {:estado :cargando
;       :iniciado millis
;       :interacciones +1
;       :primera-inter =queda igual=
;       :ultima-inter "ai_ts del más reciente montacargas cercano en la ventana"}
;    d. cómo se pasa de :trabajando o :cargando -> :vacio
;         - en una ventana No existe un umbral-configurable (en porcentaje) de lecturas-ai
;           que contengan trailer, en éste punto es necesario informar el fin de la operación
;           y el estado reducción queda asi:
;      {:estado :vacio
;       :ultimo-ciclo =ultima-inter=}


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

(deflistener rest-server [{:type 'mx.interware.caudal.io.rest-server
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
