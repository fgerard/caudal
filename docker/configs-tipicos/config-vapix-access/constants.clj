(ns constants)

(def origin (-> (java.net.InetAddress/getLocalHost)
                (.getHostName)))

(def plantId "quantum")

(def CAUDAL_HOME (System/getenv "CAUDAL_HOME"))

(def CAUDAL_DATA (System/getenv "CAUDAL_DATA"))

(def CAUDAL_CONFIG (System/getenv "CAUDAL_CONFIG"))

    ; este vector es para FALL-BACK si se quiere enviar a más de un lugar siempre
    ; se debe crear un archivo (io/file (str CAUDAL_CONFIG "/QA_URL.edn"))
    ; con un vector similar a este de esta lista se invocan hasta el 
    ; primero que tiene exito o se termina la lista

(def PLATFORM-URL [{:url "http://172.28.61.234/api/local/" ;"http://172.28.61.195/accesosWelcome/api/local/"
                    :auth nil}])

(def TCP-PORT 9999)
(def HTTP-PORT 8090)
(def REST-PORT 8070)

(def WITH-E-COUNTER true)
(def WITH-DEBUGING true)

(def PRIORITY-DEPTH 25)
