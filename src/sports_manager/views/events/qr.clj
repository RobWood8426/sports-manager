(ns sports-manager.views.events.qr
  "Standalone event QR-code page."
  (:require [sports-manager.views.shared :as shared]))

(defn event-qr-page
  "Standalone page showing the event QR code with event details."
  [ev spectator-url b64 fmt]
  (shared/doc (str (:event/name ev) " — QR Code")
              [:div.max-w-sm.mx-auto.p-8.flex.flex-col.items-center.gap-4.text-center
               [:h1.text-2xl.font-bold (:event/name ev)]
               (when (:event/description ev)
                 [:p.text-sm.opacity-60 (:event/description ev)])
               (when (or (:event/start-at ev) (:event/end-at ev))
                 [:p.text-sm.opacity-60
                  (when-let [s (:event/start-at ev)] (.format fmt s))
                  (when (and (:event/start-at ev) (:event/end-at ev)) " – ")
                  (when-let [e (:event/end-at ev)] (.format fmt e))])
               [:img.mx-auto {:src (str "data:image/png;base64," b64)
                              :alt "QR code"
                              :style "width:260px;height:260px;image-rendering:pixelated"}]
               [:p.text-xs.opacity-50 "Scan to view live scores"]
               [:a.link.link-primary.text-sm {:href spectator-url :target "_blank"} spectator-url]]))
