(ns constants)

(def origin (-> (java.net.InetAddress/getLocalHost)
                (.getHostName)))

(def plantId "leon")

(def EXIT-FACE-CAMERAS #{"FACE212" "FACE213" "FACE214"})

;clases de la SSD de PLATE
(def TRAILER 1)
(def PLATE 2)
(def TRABA 3)
(def MONTA 4)
(def PERSON 5)

;clases de la SSD de TOP
(def CAJA 1)
(def PRODUCT1 2)
(def PRODUCT2 3)
(def TRABA_BACK 4)
(def MIN_BACK_IoU 0.0001)

;Indices de el arreglo de object, [clase,score,xmin,ymin,xmax,ymax]
(def CLS-idx 0)
(def PROB-idx 1)
(def Xmin-idx 2)
(def Ymin-idx 3)
(def Xmax-idx 4)
(def Ymax-idx 5)

(def PLATE-MAX-FRAMES-WINDOW 5)
(def PLATE-TAIL-BLANC-FRAMES 2)

(def TOP-MAX-FRAMES-WINDOW 25)
(def TOP-TAIL-BLANC-FRAMES 2)

(def CHECK-MAX-FRAMES-WINDOW 25)
(def CHECK-TAIL-BLANC-FRAMES 5)
(def CHECK-MAX-BACKTRACK 5)


(def CHECK-TRAILER 1)
(def CHECK-PLATE 3)


(def ANCHO-DEL-CARRIL 550)

(def NOT-IN-TRACK-DIFF 150)

(def Y-PROXIMITY-THRESHOLD 60) ;80
(def Y-MOVEMENT-THRESHOLD 2)
(def MAX-HEIGHT-VS-WIDTH-RATIO 1.0) ; decia 1.3 pero se hacen falsos positivos al pasar por atrás
(def PIVOT-GROW-FACTOR 2)

(def IoU-1-2-THRESHOLD 0.65)
(def FROM-TOP-RIGHT-FRONTIER-THRESHOLD 100)
(def FROM-TOP-LEFT-FRONTIER-THRESHOLD 0)

(def TRABA-X-THRESHOLD 20)

(def TAILER-CENTER-MARGIN 50)

(def MIN-INTERACTION-MOVMENT-LOAD 8) ; Era 8 vamos a ver si mejora con IoU
(def MIN-INTERACTION-MOVMENT-UNLOAD 8) ;8

(def MIN_TOP_VIEW_WIDTH_PERCENT 0.4)

(def MIN-ADJACENT-BACKS 10)
(def BACK-CARRIL-ERROR 35)

(def FROM-BACK-LEFT-FRONTIER-THRESHOLD 30)
(def FROM-BACK-RIGHT-FRONTIER-THRESHOLD 30)

(def RELEVANTES-RATE 1000)

(def LOCAL-FACES-DB-FILE "./yms/faces_db/known.json")
(def REMOTE-FACES-DB-PATH "./yms/faces_db/")

(def HTTP-TIMEOUT 5000)

(def CAUDAL_HOME (System/getenv "CAUDAL_HOME"))

(def CAUDAL_DATA (System/getenv "CAUDAL_DATA"))

(def CAUDAL_CONFIG (System/getenv "CAUDAL_CONFIG"))

(def TG-TOKEN "1386016454:AAHqnJCQB03PPRE3v30MpauCOtz8uL9s92Y")
(def TG-CHAT-ID -463909974)
