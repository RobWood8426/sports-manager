(ns sports-manager.views.events.wizard.fields
  "Event creation wizard — step 2: select which of the school's fields are
  in use for this event, with an inline shortcut to add a new field to the
  school's pool."
  (:require [sports-manager.i18n :as i18n]
            [sports-manager.views.events.wizard.details :as details]
            [sports-manager.views.shared :as shared]))

(def ^:private venue-type-keys
  {:venue.type/field :detail/venue-field
   :venue.type/court :detail/venue-court
   :venue.type/pool :detail/venue-pool
   :venue.type/track :detail/venue-track
   :venue.type/pitch :detail/venue-pitch
   :venue.type/astro :detail/venue-astro
   :venue.type/hall :detail/venue-hall
   :venue.type/other :detail/venue-other})

(defn event-wizard-fields-page
  "tenant-venues: the school's full field pool (sports-manager.venue/list-by-tenant).
  selected-ids: the set of venue ids already selected for this event."
  [event-id tenant-venues selected-ids & [{:keys [errors lang]
                                           :or {errors {} lang "en"}}]]
  (let [tr (fn [k] (i18n/t lang k))]
    (shared/doc (tr :eventform/page-title) {:active :events :lang lang}
                [:nav.flex.flex-wrap.gap-2.text-sm.mb-2.pb-4.border-b.border-base-300.items-center
                 [:a.opacity-60.hover:opacity-100.transition-opacity {:href "/"} (tr :eventform/home-link)]
                 [:span.opacity-30 "/"]
                 [:strong (tr :eventform/breadcrumb)]]
                (details/step-indicator 2 lang)
                [:div.flex.flex-col.gap-6
                 [:div
                  [:h2.text-base.font-semibold.mb-1 (tr :eventform/fields-heading)]
                  [:p.text-sm.opacity-60.mb-3 (tr :eventform/fields-hint)]
                  (if (seq tenant-venues)
                    [:form {:method "post" :action (str "/events/" event-id "/wizard/fields")}
                     (shared/csrf-field)
                     [:div.grid.gap-2.mb-4
                      {:class "grid-cols-[repeat(auto-fill,minmax(180px,1fr))]"}
                      (for [v tenant-venues]
                        (let [checked? (contains? selected-ids (:venue/id v))
                              type-label (some-> (:venue/type v) venue-type-keys tr)]
                          [:label.flex.items-center.gap-2.rounded-lg.border.border-base-300.bg-base-200.hover:bg-base-300.transition-colors.cursor-pointer.px-3.py-2
                           [:input (cond-> {:type "checkbox" :name "field-id" :value (str (:venue/id v))}
                                     checked? (assoc :checked true))]
                           [:span.text-sm.flex-1 (:venue/name v)]
                           (when type-label [:span.text-xs.opacity-50 type-label])]))]
                     [:button.btn.btn-primary {:type "submit"} (tr :eventform/next)]]
                    [:p.opacity-50.mb-4 (tr :eventform/no-fields)])]
                 [:details.collapse.bg-base-200.rounded-lg
                  [:summary.collapse-title.text-sm.font-medium.cursor-pointer (tr :eventform/add-field-heading)]
                  [:div.collapse-content
                   [:form {:method "post" :action (str "/events/" event-id "/wizard/fields/new")}
                    (shared/csrf-field)
                    [:div.flex.flex-col.gap-3
                     [:div.form-control
                      [:label.label [:span.label-text (tr :detail/venue-name) " " [:span.text-error "*"]]]
                      [:input.input.input-bordered.w-full
                       {:id "venue-name" :name "venue-name" :type "text"
                        :placeholder (tr :detail/venue-name-placeholder)}]
                      (when-let [err (get errors :venue/name)]
                        [:label.label [:span.label-text-alt.text-error err]])]
                     [:div.form-control
                      [:label.label [:span.label-text (tr :detail/venue-type) " " [:span.text-error "*"]]]
                      [:select.select.select-bordered {:id "venue-type" :name "venue-type"}
                       (for [[type-kw label-key] venue-type-keys]
                         [:option {:value (subs (str type-kw) 1)} (tr label-key)])]
                      (when-let [err (get errors :venue/type)]
                        [:label.label [:span.label-text-alt.text-error err]])]
                     [:button.btn.btn-sm {:type "submit"} (tr :detail/add-venue-button)]]]]]])))
