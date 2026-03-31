(ns util
  (:require [clojure.java.io :as io])
   (:import [java.nio.file Files StandardCopyOption]
            [java.io FileOutputStream]
            [java.nio ByteBuffer]))
  
  (defn write-atomic!
    "Escribe bytes de forma atómica:
     - escribe a .tmp
     - fsync
     - rename atómico"
    [^String final-path ^bytes data]
  
    (let [final-file (io/file final-path)
          tmp-file   (io/file (str final-path ".tmp"))]
  
      ;; escribir archivo temporal + fsync
      (with-open [fos (FileOutputStream. tmp-file)
                  ch  (.getChannel fos)]
        (.write ch (ByteBuffer/wrap data))
        (.force ch true))
  
      ;; rename atómico
      (Files/move (.toPath tmp-file)
                  (.toPath final-file)
                  (into-array StandardCopyOption
                              [StandardCopyOption/ATOMIC_MOVE
                               StandardCopyOption/REPLACE_EXISTING]))))