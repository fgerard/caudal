;   Copyright (c) Felipe Gerard. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software

(require '[mx.interware.caudal.streams.common :refer :all])
(require '[mx.interware.caudal.streams.stateful :refer :all])
(require '[mx.interware.caudal.streams.stateless :refer :all])

(require '[mx.interware.caudal.streams.common :refer :all])
(require '[mx.interware.caudal.streams.stateful :refer :all])
(require '[mx.interware.caudal.streams.stateless :refer :all])

(defsink streamer-1 10000
         (counter [:event-counter :count]
                  (printe ["Received event : "])))

(deflistener [{:type       'mx.interware.caudal.io.twitter
               :parameters {:name            "caudal-client"
                            :consumer-key    "consumer-key"
                            :consumer-secret "consumer-secret"
                            :token           "token"
                            :token-secret    "token-secret"
                            :terms           ["trump"]}}]
             streamer-1)
