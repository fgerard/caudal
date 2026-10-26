(ns env.constants
  (:require
   [clojure.tools.logging :as log]))

(if (System/getenv "DEV")
  (do
    (log/info "Loading constants DEVELOPMENT")

    ; este vector es para FALL-BACK si se quiere enviar a más de un lugar siempre
    ; se debe crear un archivo (io/file (str CAUDAL_CONFIG "/QA_URL.edn"))
    ; con un vector similar a este de esta lista se invocan hasta el 
    ; primero que tiene exito o se termina la lista
    (def PLATFORM-URL [{:url "http://<ip>/api/local/" ;"http://<ip>/<app>/api/local/"
                        :auth nil}])
    
    ; contenido del archivo QA_URL.edn

    #_[{:url "http://<ip>/<app>/api/"
      :auth "Bearer CAMBIAR-TOKEN"}
     {:url "https://<host>/api/"
      :auth "Bearer CAMBIAR-TOKEN"}]
    
    (def TCP-PORT 9999)
    (def HTTP-PORT 8090)
    (def REST-PORT 8070)

    (def WITH-E-COUNTER true)
    (def WITH-DEBUGING true)

    (def PRIORITY-DEPTH 25)

    (def DIR-FOR-RESEND "RESEND-DIR")
    (def MAX-EVENTS2SEND 100) ; ya no se usa
    
    (def POST-PLATFORM-BUFF-SIZE 1000)

    (def GENERATE-SAMPLES-FROM-PLACES #"NONE")

    (def PARKING-DELTA 300000) ; cada 5 min
    
    (def RESEND-RETRY-CNT 2)

    (def NOT-RESENDABLE-EVENTS #{:OnClip :OnError :OnLock :OnUnlock :OnParking :OnNoExiste}))

  (do
    (log/info "Loading constants PRODUCTION")

    (def PLATFORM-URL [{:url "https://develop.quantumlabs.ai/"
                        :auth nil}])

    (def AUTHORIZATION "")
    (def TCP-PORT 9999)
    (def HTTP-PORT 8090)
    (def REST-PORT 8070)

    (def WITH-E-COUNTER true)
    (def WITH-DEBUGING true)

    ;los predictores de box plate top1 y top2 mandan 38 eventos / seg cada uno con un total de  114 evts/s este buffer almacens 3 segundos de eventos 114x3=342
    (def PRIORITY-DEPTH 342)

    (def DIR-FOR-RESEND "RESEND-DIR")
    (def MAX-EVENTS2SEND 100) ; ya no se usa
    
    (def POST-PLATFORM-BUFF-SIZE 1000)

    (def GENERATE-SAMPLES-FROM-PLACES #"NONE")

    (def PARKING-DELTA 300000) ; cada 5 min
    
    (def RESEND-RETRY-CNT 2)

    (def NOT-RESENDABLE-EVENTS #{:OnClip :OnError :OnLock :OnUnlock :OnParking :OnNoExiste})

    ))
