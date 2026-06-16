(ns sports-manager.views.events.detail
  "Event detail / management page and standalone event QR page."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [sports-manager.views.events.form :as form]
            [sports-manager.views.shared :as shared]))

(defn- fixture-codes-section
  "Renders the scorekeeper codes sub-section for one fixture."
  [event-id fixture-id codes new-code new-code-fixture-id]
  (let [show-new? (= fixture-id new-code-fixture-id)]
    [:div.mt-3.space-y-2
     (when show-new?
       [:div.alert.alert-info.text-sm.p-3
        [:strong "New code generated (shown once): "]
        [:a.ss-mono.font-bold.link.link-primary {:href (str "/score?code=" new-code) :target "_blank"} new-code]
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
                {:active :events}
                [:div.flex.flex-wrap.items-center.justify-between.gap-y-3.mb-8.pb-6.border-b.border-base-300
                 [:nav.flex.flex-wrap.gap-2.text-sm.items-center
                  [:a.opacity-60.hover:opacity-100.transition-opacity {:href "/"} "← Home"]
                  [:span.opacity-30 "/"]
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
                    [:a.ss-mono.link.link-primary {:href (str "/e/" event-code) :target "_blank"} event-code]
                    " "
                    [:a.link.link-primary {:href (str "/events/" event-id "/qr") :target "_blank"} "QR Code"]])]
                [:section.mb-8
                 (if (seq participants)
                   (shared/collapsible-list
                    "Participating schools" (count participants)
                    [:div.flex.flex-col.gap-2.mb-4
                     (for [p participants]
                       [:div.flex.items-center.gap-3.ss-card.px-4.py-3
                        [:div.flex-1
                         [:span.font-semibold (:participant/name p)]
                         (when-let [t (get-in p [:participant/tenant :tenant/name])]
                           [:span.text-sm.opacity-50.ml-2 (str "(" t ")")])]
                        [:span.text-sm.opacity-50 (or (:participant/contact-email p) "")]
                        (shared/participant-status-badge (:participant/status p))
                        [:form {:method "post"
                                :action (str "/events/" event-id "/participants/" (:participant/id p) "/remove")}
                         (shared/csrf-field)
                         [:button.btn.btn-xs.btn-outline {:type "submit"} "Remove"]]])])
                   [:div.mb-4
                    [:h2.ss-label.block.mb-3 "Participating schools"]
                    [:p.opacity-50 "No schools added yet."]])
                 [:details.ss-card
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
                   [:h2.ss-label.block.mb-3 "Sport settings"]
                   [:div.flex.flex-col.gap-2
                    (for [cfg sport-configs]
                      (let [sport-name (:sport-template/name cfg)
                            current-vm (or (:effective/validation-model cfg) :validation.model/single)
                            sport-slug (name (:sport-template/code cfg))]
                        [:div.ss-card.px-4.py-3
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
                 (if (seq venues)
                   (shared/collapsible-list
                    "Venues" (count venues)
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
                                             (some-> v :venue/type name) "—")]
                         [:div.flex.items-center.gap-3.ss-card.px-4.py-3
                          [:span.font-semibold.flex-1 (:venue/name v)]
                          [:span.text-sm.opacity-50 type-label]
                          (when-let [o (:venue/display-order v)]
                            [:span.text-sm.opacity-50 (str "#" o)])
                          [:form {:method "post"
                                  :action (str "/events/" event-id "/venues/" (:venue/id v) "/delete")}
                           (shared/csrf-field)
                           [:button.btn.btn-xs.btn-outline {:type "submit"} "Remove"]]]))])
                   [:div.mb-4
                    [:h2.ss-label.block.mb-3 "Venues"]
                    [:p.opacity-50 "No venues added yet."]])
                 [:details.ss-card
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
                   (if (seq teams)
                     (shared/collapsible-list
                      "Teams" (count teams)
                      [:div.flex.flex-col.gap-2.mb-4
                       (for [t teams]
                         [:div.flex.items-center.gap-3.ss-card.px-4.py-3
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
                           [:button.btn.btn-xs.btn-outline {:type "submit"} "Remove"]]])])
                     [:div.mb-4
                      [:h2.ss-label.block.mb-3 "Teams"]
                      [:p.opacity-50 "No teams added yet."]])
                   [:details.ss-card
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
                  [:h2.ss-label.m-0 "Fixtures"]
                  [:div.flex.items-center.gap-2.flex-wrap
                   (when (seq fixtures)
                     (list
                      [:a.btn.btn-sm.btn-outline {:href (str "/events/" event-id "/fixtures/export")} "Export fixtures"]
                      [:a.btn.btn-sm.btn-outline {:href (str "/events/" event-id "/results/export")} "Export results"]
                      [:a.btn.btn-sm.btn-outline {:href (str "/events/" event-id "/score-audit/export")} "Export score audit"]))
                   (when (seq participants)
                     [:a.btn.btn-sm.btn-outline {:href (str "/events/" event-id "/import")} "Import CSV"])]]
                 [:details.ss-card.mb-4
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
                   [:details.ss-card
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
                       (form/datetime-field fixture-errors :fixture/start-at "fixture-start-at" "Start" nil)
                       (form/datetime-field fixture-errors :fixture/end-at "fixture-end-at" "End" nil)
                       [:button.btn.btn-sm {:type "submit"} "Add fixture"]]]]])]
                (when filter-active?
                  [:script (h/raw "document.getElementById('fixtures-section').scrollIntoView({behavior:'instant'});")]))))
