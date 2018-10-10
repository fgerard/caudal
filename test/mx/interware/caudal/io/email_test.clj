(ns mx.interware.caudal.io.email-test
  (:require [clojure.test :refer [deftest is]]
            [hiccup.core :as hiccup :refer [html]]
            [mx.interware.caudal.io.email :as email]
            [mx.interware.caudal.streams.common :as common]
            [mx.interware.caudal.streams.stateful :as stateful]
            [mx.interware.caudal.streams.stateless :as stateless]
            [mx.interware.caudal.util.caudal-util :as util]))

(deftest test-make-header
  (is (= (email/make-header "bonjour!")
         [:div {:class "header", :style "margin:5px 10px; padding:0"}
          [:b "bonjour!"]])))

(deftest test-make-footer
  (is (= (email/make-footer)
         [:div {:class "footer", :style "width: 550px; text-align: right;"}
          [:p {:style "color: #8899a6"} "Powered by Caudal &copy;"]])))

(deftest test-make-table
  (let [event {:host "semiorka" :app "tsys-op-wallet" :metric 11235.813}]
    (is (= (hiccup/html (email/make-table-template event))
           (hiccup/html [:table {:style       "margin:5px 10px; border:2px solid #f5f8fa; padding:0"
                                 :cellpadding "5", :cellspacing "0", :width "550px"}
                         [:tbody
                          [:tr {:style "background-color:#f5f8fa"} [:th {:width "20%", :align "left"} "host"] [:td {:width "80%", :align "left"} "semiorka"]]
                          [:tr {:style "background-color:#ffffff"} [:th {:width "20%", :align "left"} "app"] [:td {:width "80%", :align "left"} "tsys-op-wallet"]]
                          [:tr {:style "background-color:#f5f8fa"} [:th {:width "20%", :align "left"} "metric"] [:td {:width "80%", :align "left"} 11235.813]]]])))))

(deftest test-event->html
  (let [event {:mode 0 :ts (System/currentTimeMillis) :id 1 :tx "tx-test"}
        templ [:table {:style       "margin:5px 10px; border:2px solid #f5f8fa; padding:0"
                       :cellpadding "5" :cellspacing "0" :width "550px"}
               [:tbody (map-indexed (fn [i key]
                                      (let [style (if (even? i)
                                                    "background-color:#f5f8fa"
                                                    "background-color:#ffffff")]
                                        [:tr {:style style}
                                         [:th {:width "20%" :align "left"} (name key)]
                                         [:td {:width "80%" :align "left"} key]]))
                                    (keys event))]]]
    (is (= (email/event->html event)
           (email/event->html event templ)))))

;; Integration
(comment deftest mail-event
         (let [smtp-keys [:host :port :user :pass :ssl :tls :sender]
               opts      (get-in (read-string (slurp "config/caudal-config.edn")) [:smtp])
               smtp      (select-keys opts smtp-keys)
               msg       (select-keys opts (remove (set smtp-keys) (keys opts)))
               _         (println smtp)
               _         (println msg)
               agt       (util/create-caudal-agent)
               e         {:tx "tx-identifier" :metric 100}
               result    (atom nil)
               streams   (common/defstream [e]
                           (email/email-event smtp msg e))
               sink      (common/create-sink agt streams)]
           (sink e)
           (Thread/sleep 3000)))

;; Integration
(comment deftest mailer-1
         (let [smtp-keys [:host :port :user :pass :ssl :tls :sender]
               opts      (get-in (read-string (slurp "config/caudal-config.edn")) [:smtp])
               smtp      (select-keys opts smtp-keys)
               msg       (select-keys opts (remove (set smtp-keys) (keys opts)))
               _         (println smtp)
               _         (println msg)
               agt       (util/create-caudal-agent)
               e         {:tx "tx-identifier" :metric 100}
               result    (atom nil)
               streams   (stateless/default [:ttl -1]
                                            (email/mailer [smtp msg]))
               sink      (common/create-sink agt streams)]
           (sink e)
           (Thread/sleep 3000)))

;; Integration
(comment deftest mailer-2
         (let [smtp-keys [:host :port :user :pass :ssl :tls :sender]
               opts      (get-in (read-string (slurp "config/caudal-config.edn")) [:smtp])
               smtp      (select-keys opts smtp-keys)
               msg       (select-keys opts (remove (set smtp-keys) (keys opts)))
               _         (println opts)
               agt       (util/create-caudal-agent)
               evt       [{:tx "tx-identifier" :metric 200}
                          {:tx "tx-identifier" :metric 201}
                          {:tx "tx-identifier" :metric 202}]
               result    (atom nil)
               streams   (stateful/rollup [:tx 2 100]
                                          (email/mailer [smtp (merge msg {:subject "Caudal Mailer w/Rollup"})]
                                                        (common/defstream [e] (println {:event e}))))
               sink      (common/create-sink agt streams)]
           (doseq [e evt]
             (sink e))
           (Thread/sleep 6000)))
