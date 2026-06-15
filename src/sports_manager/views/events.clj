(ns sports-manager.views.events
  "Event pages: create form, detail management page, scoring dashboard."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [sports-manager.views.shared :as shared]))

(defn- format-datetime-local
  "Format a java.util.Date to the \"yyyy-MM-ddTHH:mm\" string expected by
  datetime-local inputs. Returns nil for nil input."
  [^java.util.Date d]
  (when d
    (let [fmt (doto (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm")
                (.setTimeZone (java.util.TimeZone/getTimeZone "UTC")))]
      (.format fmt d))))

(defn- datetime-field
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

(defn- fixture-codes-section
  "Renders the scorekeeper codes sub-section for one fixture."
  [event-id fixture-id codes new-code new-code-fixture-id]
  (let [show-new? (= fixture-id new-code-fixture-id)]
    [:div.mt-3.space-y-2
     (when show-new?
       [:div.alert.alert-info.text-sm.p-3
        [:strong "New code generated (shown once): "]
        [:a.font-mono.font-bold.link.link-primary {:href (str "/score?code=" new-code) :target "_blank"} new-code]
        [:span.ml-2.opacity-70 "↗ opens scorer tab"]])
     (when (seq codes)
       [:ul.flex.flex-col.gap-1
        (for [c codes]
          [:li.flex.items-center.gap-2
           (shared/scode-status-badge (:scode/status c))
           [:a.text-sm.link.link-primary
            {:href (str "/events/" event-id "/fixtures/" fixture-id "/codes/" (:scode/id c) "/qr")
             :target "_blank"}
            "QR"]
           (when (= :scode.status/active (:scode/status c))
             [:form {:method "post"
                     :action (str "/events/" event-id
                                  "/fixtures/" fixture-id
                                  "/codes/" (:scode/id c) "/revoke")
                     :class "inline"}
              (shared/csrf-field)
              [:button.btn.btn-outline.btn-xs {:type "submit"} "Revoke"]])])])
     [:form {:method "post"
             :action (str "/events/" event-id "/fixtures/" fixture-id "/codes")}
      (shared/csrf-field)
      [:button.btn.btn-xs.btn-outline {:type "submit"} "Generate code"]]]))

(defn- fixture-row [event-id f codes new-code new-code-fixture-id]
  (let [fixture-id (:fixture/id f)]
    (list
     [:tr {:id (str "fixture-" fixture-id)}
      [:td [:strong (:fixture/match-number f)]]
      [:td (get-in f [:fixture/sport-template :sport-template/name] "—")]
      [:td (get-in f [:fixture/team-a :participant/name] "—")]
      [:td (get-in f [:fixture/team-b :participant/name] "—")]
      [:td (or (:fixture/age-group f) "—")]
      [:td (or (:fixture/venue f) "—")]
      [:td (when-let [s (:fixture/start-at f)]
             (.format (java.text.SimpleDateFormat. "HH:mm") s))]
      [:td (shared/fixture-status-badge (:fixture/status f))]]
     [:tr {:style "background: transparent;"}
      [:td.pb-3.pt-1 {:colspan "8"}
       (fixture-codes-section event-id fixture-id codes new-code new-code-fixture-id)]])))

(defn event-new-form
  "Create-event form."
  [all-sports selected-codes & [{:keys [errors values]
                                 :or {errors {} values {}}}]]
  (let [checked-sports (if (contains? values :event/sports)
                         (:event/sports values)
                         selected-codes)
        cur-vis (some-> (:event/visibility values) name)
        cur-access (some-> (:event/access-method values) name)]
    (shared/doc "New Event — Sports Manager"
                [:div.flex.flex-wrap.items-center.justify-between.gap-y-3.mb-8.pb-6.border-b.border-base-300
                 [:nav.flex.flex-wrap.gap-x-6.gap-y-2.text-sm
                  [:a.opacity-70.hover:opacity-100.transition-opacity {:href "/"} "← Home"]
                  [:strong "Create event"]]]
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

(defn event-detail
  "Event detail / management page."
  [event participants fixtures sports
   & [{:keys [errors add-name add-email add-phone fixture-errors fixture-filters
              sport-configs codes-by-fixture new-code new-code-fixture-id venues teams]
       :or {errors {} fixture-errors {} fixture-filters {} sport-configs []
            codes-by-fixture {} venues [] teams []}}]]
  (let [fmt (java.text.SimpleDateFormat. "d MMM yyyy HH:mm")
        draft? (= :event.status/draft (:event/status event))
        event-id (:event/id event)
        event-code (:event/code event)
        filter-active? (some (fn [[_ v]] (not (str/blank? (str v)))) fixture-filters)
        vm-labels {:validation.model/single "Single scorekeeper — accepted immediately"
                   :validation.model/single-pending "Single scorekeeper — pending admin approval"
                   :validation.model/dual "Two scorekeepers must match"
                   :validation.model/admin-approval "Always requires admin approval"}
        vm-options (seq vm-labels)]
    (shared/doc (str (:event/name event) " — Sports Manager")
                [:div.flex.flex-wrap.items-center.justify-between.gap-y-3.mb-8.pb-6.border-b.border-base-300
                 [:nav.flex.flex-wrap.gap-x-6.gap-y-2.text-sm
                  [:a.opacity-70.hover:opacity-100.transition-opacity {:href "/"} "← Home"]
                  [:strong (:event/name event)]]
                 [:div.flex.items-center.gap-2.flex-wrap
                  (shared/event-status-badge (:event/status event))
                  [:a.btn.btn-sm.btn-outline {:href (str "/events/" event-id "/dashboard")} "Dashboard"]
                  (when draft?
                    [:form {:method "post" :action (str "/events/" event-id "/publish") :class "m-0"}
                     (shared/csrf-field)
                     [:button.btn.btn-sm.btn-primary {:type "submit"} "Publish"]])]]
                [:div.mb-8.flex.flex-col.gap-1
                 (when (:event/description event)
                   [:p.opacity-60.text-sm (:event/description event)])
                 (when (or (:event/start-at event) (:event/end-at event))
                   [:p.text-sm.opacity-60
                    (when-let [s (:event/start-at event)] (.format fmt s))
                    (when (and (:event/start-at event) (:event/end-at event)) " – ")
                    (when-let [e (:event/end-at event)] (.format fmt e))])
                 (when event-code
                   [:p.text-sm.opacity-60
                    "Code: "
                    [:a.font-mono.link.link-primary {:href (str "/e/" event-code) :target "_blank"} event-code]
                    " "
                    [:a.link.link-primary {:href (str "/events/" event-id "/qr") :target "_blank"} "QR Code"]])]
                [:section.mb-8
                 [:h2.text-xs.font-semibold.uppercase.tracking-widest.opacity-50.mb-3 "Participating schools"]
                 (if (seq participants)
                   [:div.flex.flex-col.gap-2.mb-4
                    (for [p participants]
                      [:div.flex.items-center.gap-3.rounded-xl.border.border-base-300.bg-base-200.px-4.py-3
                       [:div.flex-1
                        [:span.font-semibold (:participant/name p)]
                        (when-let [t (get-in p [:participant/tenant :tenant/name])]
                          [:span.text-sm.opacity-50.ml-2 (str "(" t ")")])]
                       [:span.text-sm.opacity-50 (or (:participant/contact-email p) "")]
                       (shared/participant-status-badge (:participant/status p))
                       [:form {:method "post"
                               :action (str "/events/" event-id "/participants/" (:participant/id p) "/remove")}
                        (shared/csrf-field)
                        [:button.btn.btn-xs.btn-outline {:type "submit"} "Remove"]]])]
                   [:p.opacity-50.mb-4 "No schools added yet."])
                 [:details.rounded-xl.border.border-base-300.bg-base-200
                  [:summary.px-4.py-3.cursor-pointer.font-medium.text-sm "Add participating school"]
                  [:div.px-4.pb-4.pt-2
                   [:form {:method "post" :action (str "/events/" event-id "/participants")}
                    (shared/csrf-field)
                    [:div.flex.flex-wrap.gap-3.items-end
                     [:div.form-control
                      [:label.label [:span.label-text "School name " [:span.text-error "*"]]]
                      [:input.input.input-bordered
                       {:id "participant-name" :name "participant-name" :type "text"
                        :placeholder "e.g. Rondebosch Boys' High"
                        :value (or add-name "")}]
                      (when-let [err (get errors :participant/name)]
                        [:label.label [:span.label-text-alt.text-error err]])]
                     [:div.form-control
                      [:label.label [:span.label-text "Contact email"]]
                      [:input.input.input-bordered
                       {:id "participant-email" :name "participant-email" :type "email"
                        :placeholder "coordinator@school.co.za"
                        :value (or add-email "")}]]
                     [:div.form-control
                      [:label.label [:span.label-text "Contact phone"]]
                      [:input.input.input-bordered
                       {:id "participant-phone" :name "participant-phone" :type "tel"
                        :placeholder "+27 21 000 0000"
                        :value (or add-phone "")}]]
                     [:button.btn.btn-sm {:type "submit"} "Add school"]]]]]]
                (when (seq sport-configs)
                  [:section.mb-8
                   [:h2.text-xs.font-semibold.uppercase.tracking-widest.opacity-50.mb-3 "Sport settings"]
                   [:div.flex.flex-col.gap-2
                    (for [cfg sport-configs]
                      (let [sport-name (:sport-template/name cfg)
                            current-vm (or (:effective/validation-model cfg) :validation.model/single)
                            sport-slug (name (:sport-template/code cfg))]
                        [:div.rounded-xl.border.border-base-300.bg-base-200.px-4.py-3
                         [:form {:method "post"
                                 :action (str "/events/" event-id "/sports/" sport-slug "/config")}
                          (shared/csrf-field)
                          [:div.flex.flex-wrap.items-center.gap-3
                           [:span.font-semibold.w-28.shrink-0 sport-name]
                           [:select.select.select-bordered.select-sm.flex-1
                            {:id (str "vm-" sport-slug) :name "validation-model"}
                            (for [[vm label] vm-options]
                              [:option {:value (str (namespace vm) "/" (name vm))
                                        :selected (= vm current-vm)}
                               label])]
                           [:button.btn.btn-sm.btn-primary {:type "submit"} "Save"]]]]))]])
                [:section.mb-8
                 [:h2.text-xs.font-semibold.uppercase.tracking-widest.opacity-50.mb-3 "Venues"]
                 (if (seq venues)
                   [:div.flex.flex-col.gap-2.mb-4
                    (for [v venues]
                      (let [type-label (get {"venue.type/field" "Field"
                                             "venue.type/court" "Court"
                                             "venue.type/pool" "Pool"
                                             "venue.type/track" "Track"
                                             "venue.type/pitch" "Pitch"
                                             "venue.type/astro" "Astroturf"
                                             "venue.type/hall" "Hall"
                                             "venue.type/other" "Other"}
                                            (some-> v :venue/type :db/ident name) "—")]
                        [:div.flex.items-center.gap-3.rounded-xl.border.border-base-300.bg-base-200.px-4.py-3
                         [:span.font-semibold.flex-1 (:venue/name v)]
                         [:span.text-sm.opacity-50 type-label]
                         (when-let [o (:venue/display-order v)]
                           [:span.text-sm.opacity-50 (str "#" o)])
                         [:form {:method "post"
                                 :action (str "/events/" event-id "/venues/" (:venue/id v) "/delete")}
                          (shared/csrf-field)
                          [:button.btn.btn-xs.btn-outline {:type "submit"} "Remove"]]]))]
                   [:p.opacity-50.mb-4 "No venues added yet."])
                 [:details.rounded-xl.border.border-base-300.bg-base-200
                  [:summary.px-4.py-3.cursor-pointer.font-medium.text-sm "Add venue"]
                  [:div.px-4.pb-4.pt-2
                   [:form {:method "post" :action (str "/events/" event-id "/venues")}
                    (shared/csrf-field)
                    [:div.flex.flex-wrap.gap-3.items-end
                     [:div.form-control
                      [:label.label [:span.label-text "Name " [:span.text-error "*"]]]
                      [:input.input.input-bordered
                       {:id "venue-name" :name "venue-name" :type "text" :placeholder "e.g. Main Field"}]]
                     [:div.form-control
                      [:label.label [:span.label-text "Type " [:span.text-error "*"]]]
                      [:select.select.select-bordered {:id "venue-type" :name "venue-type"}
                       [:option {:value "" :disabled true :selected true} "Select…"]
                       [:option {:value "venue.type/field"} "Field"]
                       [:option {:value "venue.type/court"} "Court"]
                       [:option {:value "venue.type/pool"} "Pool"]
                       [:option {:value "venue.type/track"} "Track"]
                       [:option {:value "venue.type/pitch"} "Pitch"]
                       [:option {:value "venue.type/astro"} "Astroturf"]
                       [:option {:value "venue.type/hall"} "Hall"]
                       [:option {:value "venue.type/other"} "Other"]]]
                     [:div.form-control
                      [:label.label [:span.label-text "Display order"]]
                      [:input.input.input-bordered.w-24
                       {:id "venue-order" :name "venue-order" :type "number" :placeholder "1"}]]
                     [:button.btn.btn-sm {:type "submit"} "Add venue"]]]]]]
                (when (seq participants)
                  [:section.mb-8
                   [:h2.text-xs.font-semibold.uppercase.tracking-widest.opacity-50.mb-3 "Teams"]
                   (if (seq teams)
                     [:div.flex.flex-col.gap-2.mb-4
                      (for [t teams]
                        [:div.flex.items-center.gap-3.rounded-xl.border.border-base-300.bg-base-200.px-4.py-3
                         [:span.font-semibold.flex-1 (:team/name t)]
                         [:span.text-sm.opacity-50 (get-in t [:team/participant :participant/name])]
                         [:span.text-sm.opacity-50 (get-in t [:team/sport :sport-template/name])]
                         (when-let [ag (:team/age-group t)]
                           [:span.badge.badge-sm.badge-outline ag])
                         (when-let [g (:team/gender t)]
                           [:span.badge.badge-sm.badge-outline
                            (get {:team.gender/boys "Boys"
                                  :team.gender/girls "Girls"
                                  :team.gender/mixed "Mixed"} g)])
                         [:form {:method "post"
                                 :action (str "/events/" event-id "/teams/" (:team/id t) "/delete")}
                          (shared/csrf-field)
                          [:button.btn.btn-xs.btn-outline {:type "submit"} "Remove"]]])]
                     [:p.opacity-50.mb-4 "No teams added yet."])
                   [:details.rounded-xl.border.border-base-300.bg-base-200
                    [:summary.px-4.py-3.cursor-pointer.font-medium.text-sm "Add team"]
                    [:div.px-4.pb-4.pt-2
                     [:form {:method "post" :action (str "/events/" event-id "/teams")}
                      (shared/csrf-field)
                      [:div.flex.flex-wrap.gap-3.items-end
                       [:div.form-control
                        [:label.label [:span.label-text "Team name " [:span.text-error "*"]]]
                        [:input.input.input-bordered
                         {:id "team-name" :name "team-name" :type "text"
                          :placeholder "e.g. Rondebosch U16 Boys"}]]
                       [:div.form-control
                        [:label.label [:span.label-text "School " [:span.text-error "*"]]]
                        [:select.select.select-bordered {:id "team-participant" :name "team-participant"}
                         [:option {:value "" :disabled true :selected true} "Select…"]
                         (for [p (sort-by :participant/name participants)]
                           [:option {:value (str (:participant/id p))} (:participant/name p)])]]
                       [:div.form-control
                        [:label.label [:span.label-text "Sport " [:span.text-error "*"]]]
                        [:select.select.select-bordered {:id "team-sport" :name "team-sport"}
                         [:option {:value "" :disabled true :selected true} "Select…"]
                         (for [s (sort-by :sport-template/name sports)]
                           [:option {:value (name (:sport-template/code s))} (:sport-template/name s)])]]
                       [:div.form-control
                        [:label.label [:span.label-text "Age group"]]
                        [:input.input.input-bordered
                         {:id "team-age-group" :name "team-age-group" :type "text"
                          :placeholder "e.g. U16"}]]
                       [:div.form-control
                        [:label.label [:span.label-text "Category"]]
                        [:select.select.select-bordered {:id "team-gender" :name "team-gender"}
                         [:option {:value ""} "Not specified"]
                         [:option {:value "team.gender/boys"} "Boys"]
                         [:option {:value "team.gender/girls"} "Girls"]
                         [:option {:value "team.gender/mixed"} "Mixed"]]]
                       [:button.btn.btn-sm {:type "submit"} "Add team"]]]]]])
                [:section {:id "fixtures-section"}
                 [:div.flex.items-center.justify-between.gap-3.mb-3.flex-wrap
                  [:h2.text-xs.font-semibold.uppercase.tracking-widest.opacity-50.m-0 "Fixtures"]
                  (when (seq participants)
                    [:a.btn.btn-sm.btn-outline {:href (str "/events/" event-id "/import")} "Import CSV"])]
                 [:details.rounded-xl.border.border-base-300.bg-base-200.mb-4
                  [:summary.px-4.py-3.cursor-pointer.font-medium.text-sm
                   "Filters" (when filter-active? " (active)")]
                  [:div.px-4.pb-4.pt-2
                   [:form {:method "get" :action (str "/events/" event-id)}
                    [:div.flex.flex-wrap.gap-3.items-end
                     [:div.form-control
                      [:label.label [:span.label-text "Sport"]]
                      [:select.select.select-bordered {:id "filter-sport" :name "sport"}
                       [:option {:value ""} "All sports"]
                       (for [s (sort-by :sport-template/name sports)]
                         [:option {:value (name (:sport-template/code s))
                                   :selected (= (name (:sport-template/code s))
                                                (str (:sport-code fixture-filters)))}
                          (:sport-template/name s)])]]
                     [:div.form-control
                      [:label.label [:span.label-text "Team / school"]]
                      [:input.input.input-bordered
                       {:id "filter-team" :name "team" :type "text"
                        :placeholder "Search team…"
                        :value (or (:team-name fixture-filters) "")}]]
                     [:div.form-control
                      [:label.label [:span.label-text "Age group"]]
                      [:input.input.input-bordered
                       {:id "filter-age-group" :name "age-group" :type "text"
                        :placeholder "e.g. U16"
                        :value (or (:age-group fixture-filters) "")}]]
                     [:div.form-control
                      [:label.label [:span.label-text "Venue"]]
                      [:input.input.input-bordered
                       {:id "filter-venue" :name "venue" :type "text"
                        :placeholder "Search venue…"
                        :value (or (:venue fixture-filters) "")}]]
                     [:div.form-control
                      [:label.label [:span.label-text "Date"]]
                      [:input.input.input-bordered
                       {:id "filter-date" :name "date" :type "date"
                        :value (or (:date fixture-filters) "")}]]
                     [:div.flex.gap-2
                      [:button.btn.btn-sm {:type "submit"} "Filter"]
                      (when filter-active?
                        [:a.btn.btn-sm.btn-outline {:href (str "/events/" event-id)} "Clear"])]]]]]
                 (if (seq fixtures)
                   [:table.table.table-zebra.w-full.mb-4
                    [:thead [:tr [:th "#"] [:th "Sport"] [:th "Team A"] [:th "Team B"]
                             [:th "Age group"] [:th "Venue"] [:th "Time"] [:th "Status"]]]
                    [:tbody
                     (map (fn [f]
                            (fixture-row event-id f
                                         (get codes-by-fixture (:fixture/id f) [])
                                         new-code new-code-fixture-id))
                          fixtures)]]
                   [:p.opacity-50.mb-4
                    (if filter-active? "No fixtures match the current filters." "No fixtures yet.")])
                 (when (seq participants)
                   [:details.rounded-xl.border.border-base-300.bg-base-200
                    [:summary.px-4.py-3.cursor-pointer.font-medium.text-sm "Add fixture"]
                    [:div.px-4.pb-4.pt-2
                     [:form {:method "post" :action (str "/events/" event-id "/fixtures")}
                      (shared/csrf-field)
                      [:div.flex.flex-wrap.gap-3.items-end
                       [:div.form-control
                        [:label.label [:span.label-text "Sport " [:span.text-error "*"]]]
                        [:select.select.select-bordered {:id "fixture-sport" :name "fixture-sport"}
                         [:option {:value "" :disabled true :selected true} "Select…"]
                         (for [s (sort-by :sport-template/name sports)]
                           [:option {:value (name (:sport-template/code s))}
                            (:sport-template/name s)])]
                        (when-let [err (get fixture-errors :fixture/sport-code)]
                          [:label.label [:span.label-text-alt.text-error err]])]
                       [:div.form-control
                        [:label.label [:span.label-text "Team A " [:span.text-error "*"]]]
                        [:select.select.select-bordered {:id "fixture-team-a" :name "fixture-team-a"}
                         [:option {:value "" :disabled true :selected true} "Select…"]
                         (for [p (sort-by :participant/name participants)]
                           [:option {:value (str (:participant/id p))} (:participant/name p)])]
                        (when-let [err (get fixture-errors :fixture/team-a-id)]
                          [:label.label [:span.label-text-alt.text-error err]])]
                       [:div.form-control
                        [:label.label [:span.label-text "Team B " [:span.text-error "*"]]]
                        [:select.select.select-bordered {:id "fixture-team-b" :name "fixture-team-b"}
                         [:option {:value "" :disabled true :selected true} "Select…"]
                         (for [p (sort-by :participant/name participants)]
                           [:option {:value (str (:participant/id p))} (:participant/name p)])]
                        (when-let [err (get fixture-errors :fixture/team-b-id)]
                          [:label.label [:span.label-text-alt.text-error err]])]
                       [:div.form-control
                        [:label.label [:span.label-text "Age group"]]
                        [:input.input.input-bordered
                         {:id "fixture-age-group" :name "fixture-age-group" :type "text"
                          :placeholder "e.g. U16 Boys"}]]
                       [:div.form-control
                        [:label.label [:span.label-text "Venue"]]
                        [:select.select.select-bordered {:id "fixture-venue-ref" :name "fixture-venue-ref"}
                         [:option {:value ""} "— none —"]
                         (for [v venues]
                           [:option {:value (str (:venue/id v))} (:venue/name v)])]]
                       (datetime-field fixture-errors :fixture/start-at "fixture-start-at" "Start" nil)
                       (datetime-field fixture-errors :fixture/end-at "fixture-end-at" "End" nil)
                       [:button.btn.btn-sm {:type "submit"} "Add fixture"]]]]])]
                (when filter-active?
                  [:script (h/raw "document.getElementById('fixtures-section').scrollIntoView({behavior:'instant'});")]))))

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

(defn event-dashboard
  "Operational scoring dashboard for an event."
  [event dashboard]
  (let [event-id (:event/id event)
        fmt (java.text.SimpleDateFormat. "HH:mm")
        {:keys [counts conflicts by-bucket]} dashboard
        buckets [[:live "Live" "badge-success" "text-success" "border-success/30"]
                 [:disputed "Disputed" "badge-error" "text-error" "border-error/30"]
                 [:pending "Pending" "badge-warning" "text-warning" "border-warning/30"]
                 [:completed "Completed" "badge-info" "text-info" "border-info/30"]
                 [:no-activity "No activity" "badge-ghost" "" "border-base-300"]]
        bucket-label (into {} (map (fn [[k l _ _ _]] [k l]) buckets))
        dash-row (fn [f]
                   (let [bucket (:dashboard/bucket f)
                         score (:dashboard/score f)]
                     [:tr
                      [:td (shared/dashboard-bucket-badge bucket (get bucket-label bucket (name bucket)))]
                      [:td (get-in f [:fixture/sport-template :sport-template/name] "—")]
                      [:td (get-in f [:fixture/team-a :participant/name] "—")]
                      [:td (get-in f [:fixture/team-b :participant/name] "—")]
                      [:td.opacity-60 (or (:fixture/age-group f) "—")]
                      [:td.opacity-60 (or (:fixture/venue f) "—")]
                      [:td.opacity-60 (when-let [s (:fixture/start-at f)] (.format fmt s))]
                      [:td.font-mono (if score (str (:a score) " – " (:b score)) "—")]
                      [:td.opacity-60 (:dashboard/active-codes f)]
                      [:td (when (:dashboard/conflict? f)
                             [:span.badge.badge-sm.badge-warning "conflict"])]]))]
    (shared/doc (str (:event/name event) " — Dashboard")
                [:div.flex.flex-wrap.items-center.justify-between.gap-y-3.mb-8.pb-6.border-b.border-base-300
                 [:nav.flex.flex-wrap.gap-x-6.gap-y-2.text-sm
                  [:a.opacity-70.hover:opacity-100.transition-opacity {:href "/"} "← Home"]
                  [:a.opacity-70.hover:opacity-100.transition-opacity {:href (str "/events/" event-id)} (:event/name event)]
                  [:strong "Dashboard"]]
                 (shared/event-status-badge (:event/status event))]
                [:div.grid.gap-3.mb-8
                 {:class "grid-cols-[repeat(auto-fill,minmax(130px,1fr))]"}
                 (for [[bucket label _ num-cl bdr-cl] buckets]
                   [:div.rounded-xl.border.bg-base-200.p-4.text-center
                    {:class bdr-cl}
                    [:div.text-3xl.font-bold
                     {:class num-cl}
                     (get counts bucket 0)]
                    [:div.text-xs.uppercase.tracking-widest.opacity-50.mt-1 label]])
                 [:div.rounded-xl.border.border-base-300.bg-base-200.p-4.text-center
                  [:div.text-3xl.font-bold (:total counts 0)]
                  [:div.text-xs.uppercase.tracking-widest.opacity-50.mt-1 "Total"]]]
                (when (seq conflicts)
                  [:div.alert.alert-warning.mb-6
                   [:span (str (count conflicts) " fixture(s) have multiple scorers active — possible conflict.")]])
                (if (seq (:fixtures dashboard))
                  [:div.overflow-x-auto
                   [:table.table.table-zebra.w-full
                    [:thead
                     [:tr
                      [:th "Status"] [:th "Sport"] [:th "Team A"] [:th "Team B"]
                      [:th "Age group"] [:th "Venue"] [:th "Time"] [:th "Score"] [:th "Codes"] [:th ""]]]
                    [:tbody
                     (for [[bucket _ _] buckets
                           f (get by-bucket bucket [])]
                       (dash-row f))]]]
                  [:p.opacity-50 "No fixtures for this event."]))))
