(ns constants)

(def HTTP-TIMEOUT 5000)

(def DELTA-REPEAT-TAG 50) ; más bien se limita el envio con el :every de politica

(def CAUDAL_HOME (System/getenv "CAUDAL_HOME"))

(def CAUDAL_DATA (System/getenv "CAUDAL_DATA"))
