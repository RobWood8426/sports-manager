(ns sports-manager.views.events.fixtures
  "Fixtures rendering for the event detail page: the per-venue entries grid
  and the add-fixture form."
  (:require [clojure.string :as str]
            [sports-manager.i18n :as i18n]
            [sports-manager.views.events.wizard.details :as details]
            [sports-manager.views.shared :as shared]))

(defn- fixture-codes-section
  "Renders the scorekeeper codes controls for one fixture as a click-to-open
  options dropdown. A one-time new-code banner is shown above the dropdown so
  it is not hidden inside the closed menu."
  [event-id fixture-id codes new-code new-code-fixture-id lang]
  (let [show-new? (= fixture-id new-code-fixture-id)]
    [:div.flex.flex-col.gap-2.items-start
     (when show-new?
       [:div.alert.alert-info.text-sm.p-2
        [:strong (i18n/t lang :fixtures/new-code)]
        [:a.ss-mono.font-bold.link.link-primary {:href (str "/score?code=" new-code) :target "_blank"} new-code]])
     [:div.dropdown.dropdown-end.dropdown-top
      [:div.btn.btn-xs.btn-outline.whitespace-nowrap.gap-1
       {:tabindex "0" :role "button"}
       [:span (i18n/t lang :fixtures/options)]
       [:span.text-xs.opacity-70 "▾"]]
      [:ul.dropdown-content.menu.menu-sm.z-10.mt-1.p-2.shadow.bg-base-200.rounded-box.w-52
       {:tabindex "0"}
       (for [c codes]
         [:li.menu-title.flex.flex-row.items-center.gap-2.px-2.py-1
          (shared/scode-status-badge (:scode/status c))
          [:a.link.link-primary
           {:href (str "/events/" event-id "/fixtures/" fixture-id "/codes/" (:scode/id c) "/qr")
            :target "_blank"}
           (i18n/t lang :fixtures/qr)]
          (when (= :scode.status/active (:scode/status c))
            [:form {:method "post"
                    :action (str "/events/" event-id
                                 "/fixtures/" fixture-id
                                 "/codes/" (:scode/id c) "/revoke")
                    :class "inline ml-auto"}
             (shared/csrf-field)
             [:button.btn.btn-outline.btn-xs {:type "submit"} (i18n/t lang :fixtures/revoke)]])])
       [:li
        [:form {:method "post"
                :action (str "/events/" event-id "/fixtures/" fixture-id "/codes")}
         (shared/csrf-field)
         [:button.btn.btn-xs.btn-outline.w-full {:type "submit"} (i18n/t lang :fixtures/generate-code)]]]]]]))

(defn- fixture-row
  "Renders one fixture as a table row, with the scorekeeper codes controls in
  the rightmost cell."
  [event-id f codes new-code new-code-fixture-id lang]
  (let [fixture-id (:fixture/id f)]
    [:tr {:id (str "fixture-" fixture-id)}
     [:td [:strong (:fixture/match-number f)]]
     [:td (get-in f [:fixture/sport-template :sport-template/name] "—")]
     [:td (get-in f [:fixture/team-a :participant/name] "—")]
     [:td (get-in f [:fixture/team-b :participant/name] "—")]
     [:td (or (:fixture/age-group f) "—")]
     [:td (when-let [s (:fixture/start-at f)]
            (.format (java.text.SimpleDateFormat. "HH:mm") s))]
     [:td (fixture-codes-section event-id fixture-id codes new-code new-code-fixture-id lang)]]))

(defn- venue-label
  "The display label for a fixture's venue, treating blank/nil as Unassigned."
  [f lang]
  (let [v (:fixture/venue f)]
    (if (str/blank? v) (i18n/t lang :fixtures/unassigned) v)))

(defn- group-by-venue
  "Groups fixtures by venue label and returns a seq of [label fixtures] pairs,
  named venues first (alphabetical), with Unassigned last."
  [fixtures lang]
  (let [unassigned (i18n/t lang :fixtures/unassigned)
        groups (group-by #(venue-label % lang) fixtures)
        named (sort (remove #{unassigned} (keys groups)))
        ordered (concat named (when (contains? groups unassigned) [unassigned]))]
    (map (fn [label] [label (get groups label)]) ordered)))

(defn- venue-table
  "A collapsible venue section: the venue name and fixture count in the
  summary, the fixtures table inside. Collapsed by default; open it to
  reveal the table."
  [event-id label fixtures codes-by-fixture new-code new-code-fixture-id lang]
  (let [n (count fixtures)
        ;; Auto-open the venue holding a freshly-generated code so its
        ;; one-time banner stays visible.
        open? (some #(= new-code-fixture-id (:fixture/id %)) fixtures)]
    [:details.ss-card.mb-4 (when open? {:open true})
     [:summary.px-4.py-3.cursor-pointer.font-medium.text-sm.flex.items-center.gap-2
      [:span label]
      [:span.badge.badge-sm.badge-ghost
       (str n (if (= 1 n)
                (i18n/t lang :fixtures/fixture-singular)
                (i18n/t lang :fixtures/fixture-plural)))]]
     [:div.px-4.pb-4.pt-2
      [:div.overflow-x-auto.rounded-lg.border.border-base-300
       [:table.table.table-zebra.w-full.bg-base-100
        [:thead [:tr.bg-base-300 [:th "#"] [:th "Sport"] [:th "Team A"] [:th "Team B"]
                 [:th "Age group"] [:th "Time"] [:th "Codes"]]]
        [:tbody
         (map (fn [f]
                (fixture-row event-id f
                             (get codes-by-fixture (:fixture/id f) [])
                             new-code new-code-fixture-id lang))
              fixtures)]]]]]))

(defn fixtures-grid
  "Renders fixtures as one table per venue, with an Unassigned table last.
  Shows an empty-state message when there are no fixtures."
  [event-id fixtures filter-active? codes-by-fixture new-code new-code-fixture-id lang]
  (if (seq fixtures)
    [:div
     (for [[label vfixtures] (group-by-venue fixtures lang)]
       (venue-table event-id label vfixtures codes-by-fixture new-code new-code-fixture-id lang))]
    [:p.opacity-50
     (if filter-active?
       (i18n/t lang :fixtures/none-filtered)
       (i18n/t lang :fixtures/none))]))

(defn add-fixture-form
  "The add-fixture form. Returns nil when there are no participants to pair."
  [event-id participants sports venues fixture-errors lang]
  (when (seq participants)
    [:details.ss-card
     [:summary.px-4.py-3.cursor-pointer.font-medium.text-sm (i18n/t lang :fixtures/add-fixture)]
     [:div.px-4.pb-4.pt-2
      [:form {:method "post" :action (str "/events/" event-id "/fixtures")}
       (shared/csrf-field)
       [:div.flex.flex-col.gap-3
        [:div.form-control
         [:label.label [:span.label-text (i18n/t lang :fixtures/sport) [:span.text-error "*"]]]
         [:select.select.select-bordered {:id "fixture-sport" :name "fixture-sport"}
          [:option {:value "" :disabled true :selected true} (i18n/t lang :fixtures/select)]
          (for [s (sort-by :sport-template/name sports)]
            [:option {:value (name (:sport-template/code s))}
             (:sport-template/name s)])]
         (when-let [err (get fixture-errors :fixture/sport-code)]
           [:label.label [:span.label-text-alt.text-error err]])]
        [:div.form-control
         [:label.label [:span.label-text (i18n/t lang :fixtures/team-a) [:span.text-error "*"]]]
         [:select.select.select-bordered {:id "fixture-team-a" :name "fixture-team-a"}
          [:option {:value "" :disabled true :selected true} (i18n/t lang :fixtures/select)]
          (for [p (sort-by :participant/name participants)]
            [:option {:value (str (:participant/id p))} (:participant/name p)])]
         (when-let [err (get fixture-errors :fixture/team-a-id)]
           [:label.label [:span.label-text-alt.text-error err]])]
        [:div.form-control
         [:label.label [:span.label-text (i18n/t lang :fixtures/team-b) [:span.text-error "*"]]]
         [:select.select.select-bordered {:id "fixture-team-b" :name "fixture-team-b"}
          [:option {:value "" :disabled true :selected true} (i18n/t lang :fixtures/select)]
          (for [p (sort-by :participant/name participants)]
            [:option {:value (str (:participant/id p))} (:participant/name p)])]
         (when-let [err (get fixture-errors :fixture/team-b-id)]
           [:label.label [:span.label-text-alt.text-error err]])]
        [:div.form-control
         [:label.label [:span.label-text (i18n/t lang :fixtures/age-group)]]
         [:input.input.input-bordered
          {:id "fixture-age-group" :name "fixture-age-group" :type "text"
           :placeholder (i18n/t lang :fixtures/age-group-placeholder)}]]
        [:div.form-control
         [:label.label [:span.label-text (i18n/t lang :fixtures/venue)]]
         [:select.select.select-bordered {:id "fixture-venue-ref" :name "fixture-venue-ref"}
          [:option {:value ""} (i18n/t lang :fixtures/venue-none)]
          (for [v venues]
            [:option {:value (str (:venue/id v))} (:venue/name v)])]]
        (details/datetime-field fixture-errors :fixture/start-at "fixture-start-at" (i18n/t lang :eventform/start) nil)
        (details/datetime-field fixture-errors :fixture/end-at "fixture-end-at" (i18n/t lang :eventform/end) nil)
        [:button.btn.btn-sm {:type "submit"} (i18n/t lang :fixtures/add-fixture)]]]]]))
