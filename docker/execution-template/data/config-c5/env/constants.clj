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
    (def PLATFORM-URL [{:url "https://host.docker.internal:3000/api/events"
                        :auth "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJuYW1lIjoiRnJhbmNpc2NvIE5vcmlhIiwiZW1haWwiOiJmbm9yaWFAZ21haWwuY29tIiwidGVsZWdyYW1JZCI6bnVsbCwidGVsZWdyYW1DaGF0SWQiOm51bGwsInJvbGVzIjpbeyJuYW1lIjoiQURNSU4iLCJkZXNjcmlwdGlvbiI6IlJPTEUgV0lUSCBBTEwgVEhFIEZVTkNUSU9OUyJ9XSwiZnVuY3Rpb25zIjpbeyJuYW1lIjoiQklUQUNPUkEiLCJ0aXRsZSI6IkJpdMOhY29yYSIsImljb24iOiJiaXRhY29yYSIsImNvbnRyb2xsZXIiOiIiLCJvcmRlciI6MiwicGFyZW50IjpudWxsLCJjaGlsZHJlbiI6W3sibmFtZSI6IkJJVEFDT1JBLUJVU1FVRURBIiwidGl0bGUiOiJCw7pzcXVlZGEiLCJpY29uIjoiYml0YWNvcmEiLCJjb250cm9sbGVyIjoiL2Rhc2hib2FyZC9iaXRhY29yYSIsIm9yZGVyIjoxLCJwYXJlbnQiOiJCSVRBQ09SQSIsImNoaWxkcmVuIjpbXX0seyJuYW1lIjoiQklUQUNPUkEtVElFTVBPLVJFQUwiLCJ0aXRsZSI6IlRpZW1wbyBSZWFsIiwiaWNvbiI6ImJpdGFjb3JhLXJlYWwtdGltZSIsImNvbnRyb2xsZXIiOiIvZGFzaGJvYXJkL2JpdGFjb3JhLXJlYWwtdGltZSIsIm9yZGVyIjoyLCJwYXJlbnQiOiJCSVRBQ09SQSIsImNoaWxkcmVuIjpbXX1dfSx7Im5hbWUiOiJDQU1BUkEiLCJ0aXRsZSI6IkPDoW1hcmFzIiwiaWNvbiI6IiIsImNvbnRyb2xsZXIiOiIiLCJvcmRlciI6MTEsInBhcmVudCI6bnVsbCwiY2hpbGRyZW4iOlt7Im5hbWUiOiJDQU1FUkEtTElTVCIsInRpdGxlIjoiTGlzdGEiLCJpY29uIjoiIiwiY29udHJvbGxlciI6Ii9kYXNoYm9hcmQvY2FtZXJhLWxpc3QiLCJvcmRlciI6MSwicGFyZW50IjoiQ0FNQVJBIiwiY2hpbGRyZW4iOltdfSx7Im5hbWUiOiJDQU1FUkEtQ1JFQVRFIiwidGl0bGUiOiJDcmVhciIsImljb24iOiIiLCJjb250cm9sbGVyIjoiL2Rhc2hib2FyZC9jYW1lcmEtY3JlYXRlIiwib3JkZXIiOjIsInBhcmVudCI6IkNBTUFSQSIsImNoaWxkcmVuIjpbXX1dfSx7Im5hbWUiOiJTRUdVSU1JRU5UTyIsInRpdGxlIjoiU2VndWltaWVudG8iLCJpY29uIjoiIiwiY29udHJvbGxlciI6Ii9kYXNoYm9hcmQvc2VndWltaWVudG8iLCJvcmRlciI6NTAsInBhcmVudCI6bnVsbCwiY2hpbGRyZW4iOltdfSx7Im5hbWUiOiJTSU1VTEFET1IiLCJ0aXRsZSI6IlNpbXVsYWRvciIsImljb24iOiIiLCJjb250cm9sbGVyIjoiL2Rhc2hib2FyZC9zaW11bGFkb3IiLCJvcmRlciI6OTAsInBhcmVudCI6bnVsbCwiY2hpbGRyZW4iOltdfSx7Im5hbWUiOiJTWVNURU1fQURNSU4iLCJ0aXRsZSI6IkFkbWluaXN0cmFjacOzbiIsImljb24iOiJIb21lIiwiY29udHJvbGxlciI6bnVsbCwib3JkZXIiOjEwMCwicGFyZW50IjpudWxsLCJjaGlsZHJlbiI6W3sibmFtZSI6IlNZU1RFTV9BRE1JTl9VU0VSUyIsInRpdGxlIjoiVXN1YXJpb3MiLCJpY29uIjoiaGVyb1VzZXJHcm91cCIsImNvbnRyb2xsZXIiOiIvZGFzaGJvYXJkL2FkbWluL3VzZXItbGlzdCIsIm9yZGVyIjoxLCJwYXJlbnQiOiJTWVNURU1fQURNSU4iLCJjaGlsZHJlbiI6W119LHsibmFtZSI6IlNZU1RFTV9BRE1JTl9ST0xFUyIsInRpdGxlIjoiUm9sZXMiLCJpY29uIjoiaGVyb0NoZWNrQ2lyY2xlIiwiY29udHJvbGxlciI6Ii9kYXNoYm9hcmQvYWRtaW4vcm9sZS1saXN0Iiwib3JkZXIiOjIsInBhcmVudCI6IlNZU1RFTV9BRE1JTiIsImNoaWxkcmVuIjpbXX0seyJuYW1lIjoiU1lTVEVNX0FETUlOX0ZVTkNUSU9OUyIsInRpdGxlIjoiRnVuY2lvbmVzIiwiaWNvbiI6Imhlcm9MaXN0QnVsbGV0IiwiY29udHJvbGxlciI6Ii9kYXNoYm9hcmQvYWRtaW4vZnVuY3Rpb24tbGlzdCIsIm9yZGVyIjozLCJwYXJlbnQiOiJTWVNURU1fQURNSU4iLCJjaGlsZHJlbiI6W119XX1dLCJpYXQiOjE3NjQ5NzQ5NDF9.BZNTPXV3BFwfoJfjJBWy_bkXvFYMj7tBbrpDBBGC0F8"}])
    
    ; contenido del archivo QA_URL.edn

    #_[{:url "http://172.28.61.195/accesos/api/"
      :auth "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1bmlxdWVfbmFtZSI6ImFkbWlufDEiLCJuYmYiOjE3MTM4MjgyMzgsImV4cCI6MjAyOTE4ODIzOCwiaWF0IjoxNzEzODI4MjM4LCJpc3MiOiJodHRwOi8vbG9jYWxob3N0OjQ0MzIxIiwiYXVkIjoiaHR0cDovL2xvY2FsaG9zdDo0NDMyMSJ9.WbwY75ymf2XtcTyGXeeeeqd9AFksicwJyWubdET7qnk"}
     {:url "https://accesos.azurewebsites.net/api/"
      :auth "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1bmlxdWVfbmFtZSI6ImFkbWlufDEiLCJuYmYiOjE3MTM4MjgyMzgsImV4cCI6MjAyOTE4ODIzOCwiaWF0IjoxNzEzODI4MjM4LCJpc3MiOiJodHRwOi8vbG9jYWxob3N0OjQ0MzIxIiwiYXVkIjoiaHR0cDovL2xvY2FsaG9zdDo0NDMyMSJ9.WbwY75ymf2XtcTyGXeeeeqd9AFksicwJyWubdET7qnk"}]
    
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
