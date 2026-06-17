(ns sports-manager.views.events.detail
  "Event detail / management page and standalone event QR page."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [sports-manager.i18n :as i18n]
            [sports-manager.views.events.fixtures :as fixtures]
            [sports-manager.views.shared :as shared]))

(defn event-detail
  "Event detail / management page."
  [event participants fixtures sports
   & [{:keys [errors add-name add-email add-phone fixture-errors fixture-filters
              sport-configs codes-by-fixture new-code new-code-fixture-id venues teams lang]
       :or {errors {} fixture-errors {} fixture-filters {} sport-configs []
            codes-by-fixture {} venues [] teams [] lang "en"}}]]
  (let [tr (fn [k] (i18n/t lang k))
        fmt (java.text.SimpleDateFormat. "d MMM yyyy HH:mm")
        draft? (= :event.status/draft (:event/status event))
        event-id (:event/id event)
        event-code (:event/code event)
        filter-active? (some (fn [[_ v]] (not (str/blank? (str v)))) fixture-filters)
        vm-labels {:validation.model/single (tr :detail/vm-single)
                   :validation.model/single-pending (tr :detail/vm-single-pending)
                   :validation.model/dual (tr :detail/vm-dual)
                   :validation.model/admin-approval (tr :detail/vm-admin-approval)}
        vm-options (seq vm-labels)]
    (shared/doc (str (:event/name event) " — Sports Manager")
                {:active :events :lang lang}
                [:div.flex.flex-wrap.items-center.justify-between.gap-y-3.mb-8.pb-6.border-b.border-base-300
                 [:nav.flex.flex-wrap.gap-2.text-sm.items-center
                  [:a.opacity-60.hover:opacity-100.transition-opacity {:href "/"} (tr :detail/home)]
                  [:span.opacity-30 "/"]
                  [:strong (:event/name event)]]
                 [:div.flex.items-center.gap-2.flex-wrap
                  (shared/event-status-badge (:event/status event))
                  [:a.btn.btn-sm.btn-outline {:href (str "/events/" event-id "/dashboard")} (tr :detail/dashboard)]
                  (when draft?
                    [:form {:method "post" :action (str "/events/" event-id "/publish") :class "m-0"}
                     (shared/csrf-field)
                     [:button.btn.btn-sm.btn-primary {:type "submit"} (tr :detail/publish)]])]]
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
                    (tr :detail/code)
                    [:a.ss-mono.link.link-primary {:href (str "/e/" event-code) :target "_blank"} event-code]
                    " "
                    [:a.link.link-primary {:href (str "/events/" event-id "/qr") :target "_blank"} (tr :detail/qr-code)]])]
                [:section.mb-8
                 (if (seq participants)
                   (shared/collapsible-list
                    (tr :detail/participating-schools) (count participants)
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
                         [:button.btn.btn-xs.btn-outline {:type "submit"} (tr :detail/remove)]]])])
                   [:div.mb-4
                    [:h2.ss-label.block.mb-3 (tr :detail/participating-schools)]
                    [:p.opacity-50 (tr :detail/no-schools)]])
                 [:details.ss-card
                  [:summary.px-4.py-3.cursor-pointer.font-medium.text-sm (tr :detail/add-school)]
                  [:div.px-4.pb-4.pt-2
                   [:form {:method "post" :action (str "/events/" event-id "/participants")}
                    (shared/csrf-field)
                    [:div.flex.flex-wrap.gap-3.items-end
                     [:div.form-control
                      [:label.label [:span.label-text (tr :detail/school-name) " " [:span.text-error "*"]]]
                      [:input.input.input-bordered
                       {:id "participant-name" :name "participant-name" :type "text"
                        :placeholder (tr :detail/school-name-placeholder)
                        :value (or add-name "")}]
                      (when-let [err (get errors :participant/name)]
                        [:label.label [:span.label-text-alt.text-error err]])]
                     [:div.form-control
                      [:label.label [:span.label-text (tr :detail/contact-email)]]
                      [:input.input.input-bordered
                       {:id "participant-email" :name "participant-email" :type "email"
                        :placeholder (tr :detail/contact-email-placeholder)
                        :value (or add-email "")}]]
                     [:div.form-control
                      [:label.label [:span.label-text (tr :detail/contact-phone)]]
                      [:input.input.input-bordered
                       {:id "participant-phone" :name "participant-phone" :type "tel"
                        :placeholder (tr :detail/contact-phone-placeholder)
                        :value (or add-phone "")}]]
                     [:button.btn.btn-sm {:type "submit"} (tr :detail/add-school-button)]]]]]]
                (when (seq sport-configs)
                  [:section.mb-8
                   [:h2.ss-label.block.mb-3 (tr :detail/sport-settings)]
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
                           [:button.btn.btn-sm.btn-primary {:type "submit"} (tr :detail/save)]]]]))]])
                [:section.mb-8
                 (if (seq venues)
                   (shared/collapsible-list
                    (tr :detail/venues) (count venues)
                    [:div.flex.flex-col.gap-2.mb-4
                     (for [v venues]
                       (let [type-label (get {"venue.type/field" (tr :detail/venue-field)
                                              "venue.type/court" (tr :detail/venue-court)
                                              "venue.type/pool" (tr :detail/venue-pool)
                                              "venue.type/track" (tr :detail/venue-track)
                                              "venue.type/pitch" (tr :detail/venue-pitch)
                                              "venue.type/astro" (tr :detail/venue-astro)
                                              "venue.type/hall" (tr :detail/venue-hall)
                                              "venue.type/other" (tr :detail/venue-other)}
                                             (some-> v :venue/type name) "—")]
                         [:div.flex.items-center.gap-3.ss-card.px-4.py-3
                          [:span.font-semibold.flex-1 (:venue/name v)]
                          [:span.text-sm.opacity-50 type-label]
                          (when-let [o (:venue/display-order v)]
                            [:span.text-sm.opacity-50 (str "#" o)])
                          [:form {:method "post"
                                  :action (str "/events/" event-id "/venues/" (:venue/id v) "/delete")}
                           (shared/csrf-field)
                           [:button.btn.btn-xs.btn-outline {:type "submit"} (tr :detail/remove)]]]))])
                   [:div.mb-4
                    [:h2.ss-label.block.mb-3 (tr :detail/venues)]
                    [:p.opacity-50 (tr :detail/no-venues)]])
                 [:details.ss-card
                  [:summary.px-4.py-3.cursor-pointer.font-medium.text-sm (tr :detail/add-venue)]
                  [:div.px-4.pb-4.pt-2
                   [:form {:method "post" :action (str "/events/" event-id "/venues")}
                    (shared/csrf-field)
                    [:div.flex.flex-wrap.gap-3.items-end
                     [:div.form-control
                      [:label.label [:span.label-text (tr :detail/venue-name) " " [:span.text-error "*"]]]
                      [:input.input.input-bordered
                       {:id "venue-name" :name "venue-name" :type "text" :placeholder (tr :detail/venue-name-placeholder)}]]
                     [:div.form-control
                      [:label.label [:span.label-text (tr :detail/venue-type) " " [:span.text-error "*"]]]
                      [:select.select.select-bordered {:id "venue-type" :name "venue-type"}
                       [:option {:value "" :disabled true :selected true} (tr :detail/select)]
                       [:option {:value "venue.type/field"} (tr :detail/venue-field)]
                       [:option {:value "venue.type/court"} (tr :detail/venue-court)]
                       [:option {:value "venue.type/pool"} (tr :detail/venue-pool)]
                       [:option {:value "venue.type/track"} (tr :detail/venue-track)]
                       [:option {:value "venue.type/pitch"} (tr :detail/venue-pitch)]
                       [:option {:value "venue.type/astro"} (tr :detail/venue-astro)]
                       [:option {:value "venue.type/hall"} (tr :detail/venue-hall)]
                       [:option {:value "venue.type/other"} (tr :detail/venue-other)]]]
                     [:div.form-control
                      [:label.label [:span.label-text (tr :detail/display-order)]]
                      [:input.input.input-bordered.w-24
                       {:id "venue-order" :name "venue-order" :type "number" :placeholder "1"}]]
                     [:button.btn.btn-sm {:type "submit"} (tr :detail/add-venue-button)]]]]]]
                (when (seq participants)
                  [:section.mb-8
                   (if (seq teams)
                     (shared/collapsible-list
                      (tr :detail/teams) (count teams)
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
                             (get {:team.gender/boys (tr :detail/boys)
                                   :team.gender/girls (tr :detail/girls)
                                   :team.gender/mixed (tr :detail/mixed)} g)])
                          [:form {:method "post"
                                  :action (str "/events/" event-id "/teams/" (:team/id t) "/delete")}
                           (shared/csrf-field)
                           [:button.btn.btn-xs.btn-outline {:type "submit"} (tr :detail/remove)]]])])
                     [:div.mb-4
                      [:h2.ss-label.block.mb-3 (tr :detail/teams)]
                      [:p.opacity-50 (tr :detail/no-teams)]])
                   [:details.ss-card
                    [:summary.px-4.py-3.cursor-pointer.font-medium.text-sm (tr :detail/add-team)]
                    [:div.px-4.pb-4.pt-2
                     [:form {:method "post" :action (str "/events/" event-id "/teams")}
                      (shared/csrf-field)
                      [:div.flex.flex-wrap.gap-3.items-end
                       [:div.form-control
                        [:label.label [:span.label-text (tr :detail/team-name) " " [:span.text-error "*"]]]
                        [:input.input.input-bordered
                         {:id "team-name" :name "team-name" :type "text"
                          :placeholder (tr :detail/team-name-placeholder)}]]
                       [:div.form-control
                        [:label.label [:span.label-text (tr :detail/school) " " [:span.text-error "*"]]]
                        [:select.select.select-bordered {:id "team-participant" :name "team-participant"}
                         [:option {:value "" :disabled true :selected true} (tr :detail/select)]
                         (for [p (sort-by :participant/name participants)]
                           [:option {:value (str (:participant/id p))} (:participant/name p)])]]
                       [:div.form-control
                        [:label.label [:span.label-text (tr :detail/sport) " " [:span.text-error "*"]]]
                        [:select.select.select-bordered {:id "team-sport" :name "team-sport"}
                         [:option {:value "" :disabled true :selected true} (tr :detail/select)]
                         (for [s (sort-by :sport-template/name sports)]
                           [:option {:value (name (:sport-template/code s))} (:sport-template/name s)])]]
                       [:div.form-control
                        [:label.label [:span.label-text (tr :detail/age-group)]]
                        [:input.input.input-bordered
                         {:id "team-age-group" :name "team-age-group" :type "text"
                          :placeholder (tr :detail/age-group-placeholder)}]]
                       [:div.form-control
                        [:label.label [:span.label-text (tr :detail/category)]]
                        [:select.select.select-bordered {:id "team-gender" :name "team-gender"}
                         [:option {:value ""} (tr :detail/not-specified)]
                         [:option {:value "team.gender/boys"} (tr :detail/boys)]
                         [:option {:value "team.gender/girls"} (tr :detail/girls)]
                         [:option {:value "team.gender/mixed"} (tr :detail/mixed)]]]
                       [:button.btn.btn-sm {:type "submit"} (tr :detail/add-team-button)]]]]]])
                [:section {:id "fixtures-section"}
                 [:div.flex.items-center.justify-between.gap-3.mb-3.flex-wrap
                  [:h2.ss-label.m-0 (tr :detail/fixtures)]
                  [:div.flex.items-center.gap-2.flex-wrap
                   (when (seq fixtures)
                     (list
                      [:a.btn.btn-sm.btn-outline {:href (str "/events/" event-id "/fixtures/export")} (tr :detail/export-fixtures)]
                      [:a.btn.btn-sm.btn-outline {:href (str "/events/" event-id "/results/export")} (tr :detail/export-results)]
                      [:a.btn.btn-sm.btn-outline {:href (str "/events/" event-id "/score-audit/export")} (tr :detail/export-score-audit)]))
                   (when (seq participants)
                     [:a.btn.btn-sm.btn-outline {:href (str "/events/" event-id "/import")} (tr :detail/import-csv)])]]
                 [:details.ss-card.mb-4
                  [:summary.px-4.py-3.cursor-pointer.font-medium.text-sm
                   (tr :detail/filters) (when filter-active? (str " " (tr :detail/active)))]
                  [:div.px-4.pb-4.pt-2
                   [:form {:method "get" :action (str "/events/" event-id)}
                    [:div.flex.flex-wrap.gap-3.items-end
                     [:div.form-control
                      [:label.label [:span.label-text (tr :detail/sport)]]
                      [:select.select.select-bordered {:id "filter-sport" :name "sport"}
                       [:option {:value ""} (tr :detail/all-sports)]
                       (for [s (sort-by :sport-template/name sports)]
                         [:option {:value (name (:sport-template/code s))
                                   :selected (= (name (:sport-template/code s))
                                                (str (:sport-code fixture-filters)))}
                          (:sport-template/name s)])]]
                     [:div.form-control
                      [:label.label [:span.label-text (tr :detail/team-school)]]
                      [:input.input.input-bordered
                       {:id "filter-team" :name "team" :type "text"
                        :placeholder (tr :detail/search-team)
                        :value (or (:team-name fixture-filters) "")}]]
                     [:div.form-control
                      [:label.label [:span.label-text (tr :detail/age-group)]]
                      [:input.input.input-bordered
                       {:id "filter-age-group" :name "age-group" :type "text"
                        :placeholder (tr :detail/age-group-placeholder)
                        :value (or (:age-group fixture-filters) "")}]]
                     [:div.form-control
                      [:label.label [:span.label-text (tr :detail/venue)]]
                      [:input.input.input-bordered
                       {:id "filter-venue" :name "venue" :type "text"
                        :placeholder (tr :detail/search-venue)
                        :value (or (:venue fixture-filters) "")}]]
                     [:div.form-control
                      [:label.label [:span.label-text (tr :detail/date)]]
                      [:input.input.input-bordered
                       {:id "filter-date" :name "date" :type "date"
                        :value (or (:date fixture-filters) "")}]]
                     [:div.flex.gap-2
                      [:button.btn.btn-sm {:type "submit"} (tr :detail/filter)]
                      (when filter-active?
                        [:a.btn.btn-sm.btn-outline {:href (str "/events/" event-id)} (tr :detail/clear)])]]]]]
                 [:div.flex.flex-col.lg:flex-row.gap-4.items-start
                  [:div.lg:w-80.lg:shrink-0.w-full
                   (fixtures/add-fixture-form event-id participants sports venues fixture-errors lang)]
                  [:div.flex-1.min-w-0.w-full
                   (fixtures/fixtures-grid event-id fixtures filter-active?
                                           codes-by-fixture new-code new-code-fixture-id lang)]]]
                (when filter-active?
                  [:script (h/raw "document.getElementById('fixtures-section').scrollIntoView({behavior:'instant'});")]))))
