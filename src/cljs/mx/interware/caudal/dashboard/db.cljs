(ns mx.interware.caudal.dashboard.db)

(def default-db
  {:tab "server" ; las opciones son "counter" "rate" "statistics" ....,
   :refresh false,
   :url-target-message {:status-text "Write a valid Caudal URL"}
   ;:url-target-message {:uri "https://localhost:8054",
    ;                    :success :caudal-server-connected,
    ;                    :name "caudal",
    ;                    :version "0.7.11",
    ;                    :uptime 4458709,
    ;                    :ns [["app"]
    ;                         ["states"]
    ;                         ["state/" :id]
    ;                         ["state/" :id "/" :key]
    ;                         ["state/" :id "/" :key "/" :by1]
    ;                         ["state/" :id "/" :key "/" :by1 "/" :by2]
    ;                         ["state/" :id "/" :key "/" :by1 "/" :by2 "/" :by3]
    ;                         ["state/" :id "/" :key "/" :by1 "/" :by2 "/" :by3 "/" :by4]
    ;                         ["state/" :id "/" :key "/" :by1 "/" :by2 "/" :by3 "/" :by4  "/"  :by5]]},
   ;:uri {:status :success, :link "https://localhost:8054"},
   ;:states ["zurich" "desastres"],
   ;:state {:selected "zurich", :types #{"counter" "moving_time_window" "rate"}
           ;data {...... estado del caudal}
    ;       },
   :open? false
   }
)
