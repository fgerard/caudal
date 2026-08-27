(ns caudal.dashboard.db)

;; :browser-cache es un mapa PLANO indexado por el mismo vector-llave que usa
;; el backend ([state-key by1 by2 ...], ver caudal.streams.common/key-factory)
;; -- nunca se reconstruye como mapa anidado en el cliente.
;; {:children [k1 k2 ...] :value {...} :loading? bool}
(def default-db
  {:sinks []
   :selected-sink nil
   :browser-cache {}
   :ws {:status :disconnected
        :subscribed #{}
        :topic-input ""}
   :events-received 0
   :last-events '()})

(def max-last-events 50)
