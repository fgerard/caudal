(ns env.constants
  (:require
   [clojure.tools.logging :as log]))

(log/info "Loading env/constants")

    ; login /api/auth/system/login   POST {"username":"","password":""}
    ;  {"username":"...","access_token":"........"}

    ; eventos /api/event POST atTime eventName value uuid lane 
(def PLATFORM-URL "https://10.20.129.16")

(def USERNAME "system")
(def PASSWORD "nWcdñw8N12")

(def AUTHORIZATION "Bearer %s")

(def WITH-E-COUNTER true)

(def POST-PLATFORM-BUFF-SIZE 10)

(def TCP-PORT 9999)
(def HTTP-PORT 8090)
(def REST-PORT 8070)

(def PRIORITY-DEPTH 25)

(def RESEND-RETRY-CNT 2)

(def NOT-RESENDABLE-EVENTS #{:OnClip :OnError :OnLock :OnUnlock :OnParking :OnNoExiste})
