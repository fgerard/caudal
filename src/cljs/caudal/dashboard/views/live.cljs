(ns caudal.dashboard.views.live
  "Eventos en vivo por WebSocket. Los topics son texto libre elegido por
   cada config via (push2ws [\"topic\"]) -- no hay forma de listarlos desde
   el backend, el usuario los conoce/escribe. El contador solo cuenta lo que
   este cliente recibio (ver nota en pantalla sobre el buffer del server)."
  (:require
   [re-com.core :as rc]
   [re-frame.core :as re-frame]))

(defn panel []
  (let [status @(re-frame/subscribe [:ws-status])
        topics @(re-frame/subscribe [:subscribed-topics])
        input @(re-frame/subscribe [:topic-input])
        received @(re-frame/subscribe [:events-received])
        events @(re-frame/subscribe [:last-events])]
    [rc/v-box
     :gap "12px"
     :children
     [[rc/label :label (str "WebSocket: " (name status))]
      [rc/h-box
       :align :center
       :gap "8px"
       :children
       [[rc/input-text
         :model input
         :placeholder "nombre del topic (push2ws)"
         :on-change #(re-frame/dispatch [:set-topic-input %])]
        [rc/button
         :label "Suscribirse"
         :disabled? (empty? input)
         :on-click #(re-frame/dispatch [:add-topic input])]]]
      (when (seq topics)
        [rc/h-box
         :gap "6px"
         :children
         (vec (for [t topics]
                ^{:key t}
                [rc/button
                 :label (str t " ✕")
                 :class "topic-chip"
                 :on-click #(re-frame/dispatch [:remove-topic t])]))])
      [:div.events-counter (str "Eventos recibidos: " received)]
      [:div.events-counter-note
       "Cuenta mensajes recibidos por este cliente. El canal del server "
       "(ws-publish-chan) tiene un buffer de 100 y descarta bajo carga alta "
       "-- este numero puede ser menor al throughput real del backend."]
      (into [:div.events-feed]
            (for [[i e] (map-indexed vector events)]
              ^{:key i}
              [:pre.event-item (pr-str e)]))]]))
