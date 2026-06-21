(ns sports-manager.views.events.wizard.details
  "Event creation wizard — step 1: basic details."
  (:require [sports-manager.i18n :as i18n]
            [sports-manager.views.shared :as shared]))

(defn- format-datetime-local
  "Format a java.util.Date to the \"yyyy-MM-ddTHH:mm\" string expected by
  datetime-local inputs. Returns nil for nil input."
  [^java.util.Date d]
  (when d
    (let [fmt (doto (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm")
                (.setTimeZone (java.util.TimeZone/getTimeZone "UTC")))]
      (.format fmt d))))

(defn datetime-field
  "A datetime input, enhanced client-side by flatpickr (see
  resources/public/js/datetime-picker.js) — plain text with the
  \"yyyy-MM-ddTHH:mm\" format as a JS-free fallback. Pre-fills `value` (a
  Date or already-formatted string) when provided."
  [errors field-key field-name label value]
  (let [str-val (if (instance? java.util.Date value)
                  (format-datetime-local value)
                  value)]
    [:div.form-control
     [:label.label
      [:span.label-text label
       [:span.text-error " *"]]]
     [:input.input.input-bordered.w-full.flatpickr-datetime
      (cond-> {:id field-name :name field-name :type "text"
               :autocomplete "off" :placeholder "YYYY-MM-DD HH:mm"}
        str-val (assoc :value str-val))]
     (when-let [err (get errors field-key)]
       [:label.label [:span.label-text-alt.text-error err]])]))

(defn step-indicator
  "A simple \"Step N of 3\" line shared by all wizard pages."
  [step lang]
  [:p.text-sm.opacity-60.mb-4 (i18n/t lang :eventform/step-label step)])

(defn event-new-form
  "Create-event wizard, step 1: name, schedule, access/visibility, sports."
  [all-sports selected-codes & [{:keys [errors values lang]
                                 :or {errors {} values {} lang "en"}}]]
  (let [tr (fn [k] (i18n/t lang k))
        checked-sports (if (contains? values :event/sports)
                         (:event/sports values)
                         selected-codes)
        cur-vis (some-> (:event/visibility values) name)
        cur-access (some-> (:event/access-method values) name)
        cur-lang (i18n/normalize-lang (:event/language values))]
    (shared/doc (tr :eventform/page-title) {:active :events :lang lang}
                [:nav.flex.flex-wrap.gap-2.text-sm.mb-2.pb-4.border-b.border-base-300.items-center
                 [:a.opacity-60.hover:opacity-100.transition-opacity {:href "/"} (tr :eventform/home-link)]
                 [:span.opacity-30 "/"]
                 [:strong (tr :eventform/breadcrumb)]]
                (step-indicator 1 lang)
                [:form {:method "post" :action "/events"}
                 (shared/csrf-field)
                 [:div.flex.flex-col.gap-6
                  [:div
                   [:h2.text-base.font-semibold.mb-3 (tr :eventform/details)]
                   [:div.flex.flex-col.gap-3
                    [:div.form-control
                     [:label.label [:span.label-text (tr :eventform/name) " " [:span.text-error "*"]]]
                     [:input.input.input-bordered.w-full
                      {:id "event-name" :name "event-name" :type "text"
                       :placeholder (tr :eventform/name-placeholder)
                       :value (get values :event/name "")
                       :required true}]
                     (when-let [err (get errors :event/name)]
                       [:label.label [:span.label-text-alt.text-error err]])]
                    [:div.form-control
                     [:label.label [:span.label-text (tr :eventform/description)]]
                     [:input.input.input-bordered.w-full
                      {:id "event-description" :name "event-description" :type "text"
                       :placeholder (tr :eventform/description-placeholder)
                       :value (get values :event/description "")}]]]]
                  [:div
                   [:h2.text-base.font-semibold.mb-3 (tr :eventform/schedule)]
                   [:div.flex.flex-wrap.gap-3
                    (datetime-field errors :event/start-at "event-start-at" (tr :eventform/start) (get values :event/start-at))
                    (datetime-field errors :event/end-at "event-end-at" (tr :eventform/end) (get values :event/end-at))]]
                  [:div
                   [:h2.text-base.font-semibold.mb-3 (tr :eventform/access-visibility)]
                   [:div.flex.flex-wrap.gap-3
                    [:div.form-control
                     [:label.label [:span.label-text (tr :eventform/visibility) " " [:span.text-error "*"]]]
                     [:select.select.select-bordered
                      {:id "event-visibility" :name "event-visibility"}
                      [:option {:value "" :disabled true :selected (nil? cur-vis)} (tr :eventform/select)]
                      [:option (cond-> {:value "public"} (= cur-vis "public") (assoc :selected true)) (tr :eventform/public)]
                      [:option (cond-> {:value "private"} (= cur-vis "private") (assoc :selected true)) (tr :eventform/private)]]
                     (when-let [err (get errors :event/visibility)]
                       [:label.label [:span.label-text-alt.text-error err]])]
                    [:div.form-control
                     [:label.label [:span.label-text (tr :eventform/access-method)]]
                     [:select.select.select-bordered
                      {:id "event-access-method" :name "event-access-method"}
                      [:option {:value ""} (tr :eventform/access-none)]
                      [:option (cond-> {:value "public-link"} (= cur-access "public-link") (assoc :selected true)) (tr :eventform/access-public-link)]
                      [:option (cond-> {:value "code-gated"} (= cur-access "code-gated") (assoc :selected true)) (tr :eventform/access-code-gated)]]]
                    [:div.form-control
                     [:label.label [:span.label-text (tr :event/language-label)]]
                     [:select.select.select-bordered
                      {:id "event-language" :name "event-language"}
                      (for [code i18n/supported]
                        [:option (cond-> {:value code} (= code cur-lang) (assoc :selected true))
                         (get i18n/lang-names code code)])]
                     [:label.label [:span.label-text-alt.opacity-60 (tr :event/language-hint)]]]]]
                  (when (seq all-sports)
                    [:div
                     [:h2.text-base.font-semibold.mb-1 (tr :eventform/sports)]
                     [:p.text-sm.opacity-60.mb-3 (tr :eventform/sports-hint)]
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
                   [:button.btn.btn-primary {:type "submit"} (tr :eventform/next)]]]])))
