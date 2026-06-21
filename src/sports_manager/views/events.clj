(ns sports-manager.views.events
  "Event pages facade. The implementations live in the events/ sub-namespaces;
  this namespace re-exports the public page renderers so callers have one stable
  import point.

  - create wizard        -> sports-manager.views.events.wizard.{details,fields,age-groups}
  - detail / management -> sports-manager.views.events.detail
  - QR page             -> sports-manager.views.events.qr
  - scoring dashboard   -> sports-manager.views.events.dashboard"
  (:require [sports-manager.views.events.dashboard :as dashboard]
            [sports-manager.views.events.detail :as detail]
            [sports-manager.views.events.qr :as qr]
            [sports-manager.views.events.wizard.age-groups :as wizard.age-groups]
            [sports-manager.views.events.wizard.details :as wizard.details]
            [sports-manager.views.events.wizard.fields :as wizard.fields]))

(defn event-new-form
  "Create-event wizard, step 1. See sports-manager.views.events.wizard.details/event-new-form."
  [& args]
  (apply wizard.details/event-new-form args))

(defn event-wizard-fields-page
  "Create-event wizard, step 2. See sports-manager.views.events.wizard.fields/event-wizard-fields-page."
  [& args]
  (apply wizard.fields/event-wizard-fields-page args))

(defn event-wizard-age-groups-page
  "Create-event wizard, step 3. See sports-manager.views.events.wizard.age-groups/event-wizard-age-groups-page."
  [& args]
  (apply wizard.age-groups/event-wizard-age-groups-page args))

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
