(ns sports-manager.views.events.form
  "Create-event form."
  (:require [sports-manager.views.shared :as shared]))

(defn- format-datetime-local
  "Format a java.util.Date to the \"yyyy-MM-ddTHH:mm\" string expected by
  datetime-local inputs. Returns nil for nil input."
  [^java.util.Date d]
  (when d
    (let [fmt (doto (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm")
                (.setTimeZone (java.util.TimeZone/getTimeZone "UTC")))]
      (.format fmt d))))

(defn datetime-field
  "A datetime-local input. Pre-fills `value` (a Date or already-formatted string) when provided."
  [errors field-key field-name label value]
  (let [str-val (if (instance? java.util.Date value)
                  (format-datetime-local value)
                  value)]
    [:div.form-control
     [:label.label
      [:span.label-text label
       [:span.text-error " *"]]]
     [:input.input.input-bordered.w-full
      (cond-> {:id field-name :name field-name :type "datetime-local"}
        str-val (assoc :value str-val))]
     (when-let [err (get errors field-key)]
       [:label.label [:span.label-text-alt.text-error err]])]))

(defn event-new-form
  "Create-event form."
  [all-sports selected-codes & [{:keys [errors values]
                                 :or {errors {} values {}}}]]
  (let [checked-sports (if (contains? values :event/sports)
                         (:event/sports values)
                         selected-codes)
        cur-vis (some-> (:event/visibility values) name)
        cur-access (some-> (:event/access-method values) name)]
    (shared/doc "New Event — Sports Manager" {:active :events}
                [:nav.flex.flex-wrap.gap-2.text-sm.mb-6.pb-4.border-b.border-base-300.items-center
                 [:a.opacity-60.hover:opacity-100.transition-opacity {:href "/"} "← Home"]
                 [:span.opacity-30 "/"]
                 [:strong "Create event"]]
                [:form {:method "post" :action "/events"}
                 (shared/csrf-field)
                 [:div.flex.flex-col.gap-6
                  [:div
                   [:h2.text-base.font-semibold.mb-3 "Details"]
                   [:div.flex.flex-col.gap-3
                    [:div.form-control
                     [:label.label [:span.label-text "Event name " [:span.text-error "*"]]]
                     [:input.input.input-bordered.w-full
                      {:id "event-name" :name "event-name" :type "text"
                       :placeholder "e.g. Inter-house Sports Day 2026"
                       :value (get values :event/name "")
                       :required true}]
                     (when-let [err (get errors :event/name)]
                       [:label.label [:span.label-text-alt.text-error err]])]
                    [:div.form-control
                     [:label.label [:span.label-text "Description"]]
                     [:input.input.input-bordered.w-full
                      {:id "event-description" :name "event-description" :type "text"
                       :placeholder "Optional notes for participants"
                       :value (get values :event/description "")}]]]]
                  [:div
                   [:h2.text-base.font-semibold.mb-3 "Schedule"]
                   [:div.flex.flex-wrap.gap-3
                    (datetime-field errors :event/start-at "event-start-at" "Start" (get values :event/start-at))
                    (datetime-field errors :event/end-at "event-end-at" "End" (get values :event/end-at))]]
                  [:div
                   [:h2.text-base.font-semibold.mb-3 "Access & Visibility"]
                   [:div.flex.flex-wrap.gap-3
                    [:div.form-control
                     [:label.label [:span.label-text "Visibility " [:span.text-error "*"]]]
                     [:select.select.select-bordered
                      {:id "event-visibility" :name "event-visibility"}
                      [:option {:value "" :disabled true :selected (nil? cur-vis)} "Select…"]
                      [:option (cond-> {:value "public"} (= cur-vis "public") (assoc :selected true)) "Public"]
                      [:option (cond-> {:value "private"} (= cur-vis "private") (assoc :selected true)) "Private"]]
                     (when-let [err (get errors :event/visibility)]
                       [:label.label [:span.label-text-alt.text-error err]])]
                    [:div.form-control
                     [:label.label [:span.label-text "Access method"]]
                     [:select.select.select-bordered
                      {:id "event-access-method" :name "event-access-method"}
                      [:option {:value ""} "None / not set"]
                      [:option (cond-> {:value "public-link"} (= cur-access "public-link") (assoc :selected true)) "Public link"]
                      [:option (cond-> {:value "code-gated"} (= cur-access "code-gated") (assoc :selected true)) "Code-gated"]]]]]
                  (when (seq all-sports)
                    [:div
                     [:h2.text-base.font-semibold.mb-1 "Sports"]
                     [:p.text-sm.opacity-60.mb-3 "Select sports featured in this event."]
                     [:div.grid.gap-2
                      {:class "grid-cols-[repeat(auto-fill,minmax(140px,1fr))]"}
                      (for [t (sort-by :sport-template/name all-sports)]
                        (let [code (name (:sport-template/code t))
                              checked? (contains? checked-sports (:sport-template/code t))]
                          [:label.flex.items-center.gap-2.rounded-lg.border.border-base-300.bg-base-200.hover:bg-base-300.transition-colors.cursor-pointer.px-3.py-2
                           [:input (cond-> {:type "checkbox" :name "event-sports" :value code}
                                     checked? (assoc :checked true))]
                           [:span.text-sm (:sport-template/name t)]]))]])
                  [:div
                   [:button.btn.btn-primary {:type "submit"} "Save draft"]]]])))
