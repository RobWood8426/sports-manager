(ns sports-manager.views.events
  "Event pages facade. The implementations live in the events/ sub-namespaces;
  this namespace re-exports the public page renderers so callers have one stable
  import point.

  - create form        -> sports-manager.views.events.form
  - detail / management -> sports-manager.views.events.detail
  - QR page             -> sports-manager.views.events.qr
  - scoring dashboard   -> sports-manager.views.events.dashboard"
  (:require [sports-manager.views.events.dashboard :as dashboard]
            [sports-manager.views.events.detail :as detail]
            [sports-manager.views.events.form :as form]
            [sports-manager.views.events.qr :as qr]))

(defn event-new-form
  "Create-event form. See sports-manager.views.events.form/event-new-form."
  [& args]
  (apply form/event-new-form args))

(defn event-detail
  "Event detail / management page. See sports-manager.views.events.detail/event-detail."
  [& args]
  (apply detail/event-detail args))

(defn event-qr-page
  "Standalone event QR page. See sports-manager.views.events.qr/event-qr-page."
  [& args]
  (apply qr/event-qr-page args))

(defn event-dashboard
  "Operational scoring dashboard. See sports-manager.views.events.dashboard/event-dashboard."
  [& args]
  (apply dashboard/event-dashboard args))
