(ns mx.interware.util.crypt
  (:require [clojure.data.codec.base64 :as base64]
            [clojure.tools.logging :as log]
            [clojure.string :refer [split]])
  (:import (java.security Signature MessageDigest)
           (javax.crypto Cipher)
           (javax.crypto.spec IvParameterSpec SecretKeySpec)
           (java.util Arrays Scanner)
           (javax.xml.bind DatatypeConverter)
           (org.apache.commons.codec.digest DigestUtils)))

(def CRYPT-ALGORITHM "AES")

(def DEFAULT-ENCODING "UTF-8")

(def CRYPT-PADDING "/CBC/PKCS5Padding")

(def SIGN-ALGORITHM "SHA256withRSA")

(def CRYPT-TRANSFORMATION (str CRYPT-ALGORITHM CRYPT-PADDING))

(def DIGEST-ALGORITHM "SHA-256")

(def INIT-VECTOR (byte-array (repeat 16 (byte 0x0))))

(def CRYPTED-EXTs #{"crypted" "crypt" "cclj"})

(defn use-or-input [prompt pass]
  (if pass
    pass
    (let [scan (Scanner. System/in)]
      (println "\n\n\nPlease type password for [" prompt "] :")
      (.nextLine scan))))

(defn crypted? [file]
  (-> file .getName (split #"\.") last CRYPTED-EXTs))

(defn digest
  ([data algorithm]
   (let [digest      (MessageDigest/getInstance algorithm)
         seed-bytes  data
         upd         (.update digest seed-bytes)
         digest-data (.digest digest)]
     digest-data))
  ([data] (digest data DIGEST-ALGORITHM)))

(defn get-cipher [mode seed]
  (let [digest-data (digest (.getBytes seed))
        digest-16   (Arrays/copyOf digest-data 16)
        key-spec    (SecretKeySpec. digest-16 CRYPT-ALGORITHM)
        iv-spec     (IvParameterSpec. INIT-VECTOR)
        cipher      (Cipher/getInstance CRYPT-TRANSFORMATION)]
    (.init cipher mode key-spec iv-spec)
    cipher))

(defn encrypt-text [text seed]
  (let [bytes  (.getBytes text DEFAULT-ENCODING)
        cipher (get-cipher Cipher/ENCRYPT_MODE seed)]
    (try
      (new String (base64/encode (.doFinal cipher bytes)) DEFAULT-ENCODING)
      (catch Throwable t
        (throw t)))))

(defn decrypt-text [text seed]
  (let [cipher (get-cipher Cipher/DECRYPT_MODE seed)]
    (try
      (new String (.doFinal cipher (base64/decode (.getBytes text DEFAULT-ENCODING))))
      (catch Throwable t
        (.printStackTrace t)
        (throw t)))))
