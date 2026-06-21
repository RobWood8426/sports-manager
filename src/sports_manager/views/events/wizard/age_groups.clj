(ns sports-manager.views.events.wizard.age-groups
  "Event creation wizard — step 3: select which age groups are competing
  in this event, from the fixed global list."
  (:require [sports-manager.age-group :as age-group]
            [sports-manager.i18n :as i18n]
            [sports-manager.views.events.wizard.details :as details]
            [sports-manager.views.shared :as shared]))

(defn event-wizard-age-groups-page
  "selected-codes: the set of :age-group/* keywords already selected for this event."
  [event-id selected-codes & [{:keys [lang] :or {lang "en"}}]]
  (let [tr (fn [k] (i18n/t lang k))]
    (shared/doc (tr :eventform/page-title) {:active :events :lang lang}
                [:nav.flex.flex-wrap.gap-2.text-sm.mb-2.pb-4.border-b.border-base-300.items-center
                 [:a.opacity-60.hover:opacity-100.transition-opacity {:href "/"} (tr :eventform/home-link)]
                 [:span.opacity-30 "/"]
                 [:strong (tr :eventform/breadcrumb)]]
                (details/step-indicator 3 lang)
                [:div
                 [:h2.text-base.font-semibold.mb-1 (tr :eventform/age-groups-heading)]
                 [:p.text-sm.opacity-60.mb-3 (tr :eventform/age-groups-hint)]
                 [:form {:method "post" :action (str "/events/" event-id "/wizard/age-groups")}
                  (shared/csrf-field)
                  [:div.grid.gap-2.mb-4
                   {:class "grid-cols-[repeat(auto-fill,minmax(100px,1fr))]"}
                   (for [[code label] age-group/all]
                     (let [checked? (contains? selected-codes code)]
                       [:label.flex.items-center.gap-2.rounded-lg.border.border-base-300.bg-base-200.hover:bg-base-300.transition-colors.cursor-pointer.px-3.py-2
                        [:input (cond-> {:type "checkbox" :name "age-group" :value (subs (str code) 1)}
                                  checked? (assoc :checked true))]
                        [:span.text-sm label]]))]
                  [:button.btn.btn-primary {:type "submit"} (tr :eventform/finish)]]])))
