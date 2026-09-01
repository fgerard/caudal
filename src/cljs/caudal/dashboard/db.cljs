(ns caudal.dashboard.db)

;; :browser-tree se construye UNA SOLA VEZ, en memoria, a partir del fetch
;; completo de /state/:id (que ya se hacia igual antes) -- no es un mapa
;; anidado del estado en si, es un indice por by-path (ver
;; caudal.streams.common/key-factory: la llave real es
;; [stream-name & by-path], no [by-path... stream-name]) para poder
;; presentar el arbol en el orden que sí coincide con como se ve el sink
;; anidado en la config (by plant -> by channel-id -> streamer), sin volver
;; a pedirle nada al server (los streamers pueden vivir a profundidades de
;; by-path distintas -- counter [:total] sin by es profundidad 0, un
;; streamer dentro de un solo by es profundidad 1, etc). Forma de cada
;; nodo: {:branches {segmento -> nodo-hijo} :leaves {stream-name valor}}.
;; :browser-expanded es solo el set de paths (vectores de segmentos, mas el
;; nombre del streamer en la hoja final) que estan abiertos en la UI ahora
;; -- toggle-node no hace red, todo ya esta en :browser-tree.
(def default-db
  {:sinks []
   :selected-sink nil
   :browser-tree {}
   :browser-expanded #{}
   :ws {:status :disconnected
        :subscribed #{}
        :topic-input ""}
   :events-received 0
   :last-events '()})

(def max-last-events 50)
