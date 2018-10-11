;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(ns mx.interware.caudal.util.crypt-util
  (:gen-class :name mx.interware.caudal.util.CryptUtil)
  (:require [clojure.data.codec.base64 :as b64])
  (:import (javax.crypto Cipher)
           (javax.crypto.spec SecretKeySpec)))
 
(defn as-string [v] (->> v (map char) (apply str)))

(defn as-data [v] (.getBytes (as-string v)))

(def algorithm (as-string [65 69 83]))

(def encoding (as-string [85 84 70 45 56]))

(def crypto-key (as-data [99 108 111 106 117 114 101 35 67 105 112 104 101 114 47 55]))

(def secret-key (new SecretKeySpec crypto-key algorithm))

(def encrypter (let [result (Cipher/getInstance algorithm)
                     _      (.init result Cipher/ENCRYPT_MODE secret-key)]
                 result))

(def decrypter (let [result (Cipher/getInstance algorithm)
                     _      (.init result Cipher/DECRYPT_MODE secret-key)]
                 result))

(defn encrypt [clear-text]
  (let [encrypted (.doFinal encrypter (.getBytes clear-text))
        b64-data  (b64/encode encrypted)]
    (new String b64-data encoding)))

(defn decrypt [crypted-text]
  (let [decoded   (b64/decode (.getBytes crypted-text encoding))
        decrypted (.doFinal decrypter decoded)]
    (new String decrypted encoding)))
