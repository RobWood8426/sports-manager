(ns sports-manager.views.fixtures
  "Fixture-related pages: import wizard, score comparison, disputes, audit log."
  (:require [clojure.string :as str]
            [sports-manager.views.shared :as shared]))

(defn fixture-comparison
  "Admin view showing two final-score submissions side-by-side."
  [fixture comparison & [{:keys [resolve-errors]
                          :or {resolve-errors {}}}]]
  (let [fmt (java.text.SimpleDateFormat. "d MMM yyyy HH:mm")
        event-id (get-in fixture [:fixture/event :event/id])
        fid (:fixture/id fixture)
        {:keys [submissions match? status]} comparison
        status-label {:no-submissions "No submissions yet"
                      :one-pending "Awaiting second scorekeeper"
                      :match "Scores match"
                      :mismatch "Scores do not match"
                      :accepted "Accepted"
                      :disputed "Disputed"}
        status-class {:match "text-success font-semibold"
                      :accepted "text-success font-semibold"
                      :mismatch "text-error font-semibold"
                      :disputed "text-error font-semibold"
                      :one-pending "text-warning font-semibold"
                      :no-submissions "opacity-60"}]
    (shared/doc (str "Score Comparison — " (get-in fixture [:fixture/sport-template :sport-template/name]))
                {:active :events}
                [:div.flex.flex-wrap.items-center.justify-between.gap-3.mb-6
                 [:p [:a {:href (str "/events/" event-id)} "← Back to event"]]
                 [:h2 "Score comparison"]]
                [:p
                 [:strong "Status: "]
                 [:span {:class (get status-class status "text-warning font-semibold")}
                  (get status-label status (name status))]]
                (if (seq submissions)
                  [:table.table.table-zebra.w-full
                   [:thead [:tr [:th "Scorekeeper"] [:th "Team A"] [:th "Team B"] [:th "Submitted"] [:th "Status"]]]
                   [:tbody
                    (for [s submissions]
                      (let [s-status (:final-score/status s)
                            row-class (when (and (= 2 (count submissions))
                                                 (not match?)
                                                 (#{:final-score.status/pending
                                                    :final-score.status/disputed} s-status))
                                        "bg-error/10")]
                        [:tr {:class row-class}
                         [:td.ss-mono.text-xs (str (get-in s [:final-score/scode :scode/id]))]
                         [:td [:span.ss-score.text-2xl.text-base-content (:final-score/team-a-score s)]]
                         [:td [:span.ss-score.text-2xl.text-base-content (:final-score/team-b-score s)]]
                         [:td (when-let [at (:final-score/submitted-at s)] (.format fmt at))]
                         [:td (shared/final-score-status-badge s-status)]]))]]
                  [:p.opacity-60 "No submissions yet."])
                (when (= :disputed status)
                  [:section.mt-8.rounded-lg.border.border-error.p-4.space-y-3
                   {:style "background: color-mix(in srgb, oklch(var(--er)) 8%, transparent);"}
                   [:h3 "Resolve dispute"]
                   [:p.opacity-60 "Override the submitted scores with confirmed values and provide a reason for the record."]
                   (when-let [err (:form resolve-errors)]
                     [:p.text-error err])
                   [:form {:method "POST"
                           :action (str "/events/" event-id "/fixtures/" fid "/resolve")}
                    (shared/csrf-field)
                    [:div.form-control
                     [:label.label
                      [:span.label-text
                       (str (get-in fixture [:fixture/team-a :participant/name] "Team A") " score")]]
                     [:input.input.input-bordered.w-full
                      {:type "number" :id "confirmed-a" :name "confirmed-a"
                       :min "0" :required true
                       :value (get resolve-errors :confirmed-a "")}]
                     (when-let [e (:confirmed-a resolve-errors)]
                       [:label.label [:span.label-text-alt.text-error e]])]
                    [:div.form-control
                     [:label.label
                      [:span.label-text
                       (str (get-in fixture [:fixture/team-b :participant/name] "Team B") " score")]]
                     [:input.input.input-bordered.w-full
                      {:type "number" :id "confirmed-b" :name "confirmed-b"
                       :min "0" :required true
                       :value (get resolve-errors :confirmed-b "")}]
                     (when-let [e (:confirmed-b resolve-errors)]
                       [:label.label [:span.label-text-alt.text-error e]])]
                    [:div.form-control
                     [:label.label [:span.label-text "Reason (required)"]]
                     [:textarea.textarea.textarea-bordered.w-full
                      {:id "reason" :name "reason" :rows "3" :required true
                       :placeholder "Explain why these scores are correct"}
                      (get resolve-errors :reason "")]
                     (when-let [e (:reason resolve-errors)]
                       [:label.label [:span.label-text-alt.text-error e]])]
                    [:button.btn {:type "submit"} "Confirm & resolve"]]]))))

(defn disputes-page
  "Admin page listing all disputed fixtures for the tenant."
  [disputed]
  (let [fmt (java.text.SimpleDateFormat. "d MMM yyyy HH:mm")]
    (shared/doc "Disputed Scores — SchoolScore" {:active :disputes}
                [:div.flex.flex-wrap.items-center.justify-between.gap-3.mb-6
                 [:h2 "Disputed scores"]]
                (if (seq disputed)
                  [:table.table.table-zebra.w-full
                   [:thead
                    [:tr
                     [:th "Event"]
                     [:th "Fixture"]
                     [:th "Sport"]
                     [:th "Submitted"]
                     [:th ""]]]
                   [:tbody
                    (for [fs disputed]
                      (let [fix (:final-score/fixture fs)
                            event-id (get-in fix [:fixture/event :event/id])
                            fid (:fixture/id fix)]
                        [:tr
                         [:td (get-in fix [:fixture/event :event/name])]
                         [:td (or (:fixture/match-number fix) (str fid))]
                         [:td (get-in fix [:fixture/sport-template :sport-template/name])]
                         [:td (when-let [at (:final-score/submitted-at fs)] (.format fmt at))]
                         [:td [:a {:href (str "/events/" event-id "/fixtures/" fid "/comparison")}
                               "Review →"]]]))]]
                  [:p.opacity-60 "No disputed scores."]))))

(defn audit-log-page
  "Admin audit log page. `entries` is the seq from audit/list-by-tenant."
  [entries & [{:keys [filters] :or {filters {}}}]]
  (let [fmt (java.text.SimpleDateFormat. "d MMM yyyy HH:mm:ss")
        action-filter (:action filters)
        actor-filter (:actor filters)
        action-labels {:fixture/edit "Fixture edited"
                       :fixture/publish "Fixture published"
                       :scode/generated "Code generated"
                       :scode/revoked "Code revoked"
                       :scode/expired "Code expired"
                       :scode/started "Game started"
                       :scode/live "Scoring live"
                       :scode/submitted "Score submitted"
                       :scode/final-accepted "Score accepted"
                       :scode/final-disputed "Score disputed"
                       :final-score/submitted "Final score submitted"
                       :final-score/dual-accepted "Dual score accepted"
                       :final-score/dual-disputed "Dual score disputed"
                       :final-score/dispute-resolved "Dispute resolved"
                       :user/add-to-tenant "User added"
                       :user/remove-from-tenant "User removed"
                       :user/grant-role "Role granted"
                       :user/revoke-role "Role revoked"}
        all-actions (sort (keys action-labels))]
    (shared/doc "Audit Log — SchoolScore" {:active :audit}
                [:div.flex.flex-wrap.items-center.justify-between.gap-3.mb-6
                 [:h2 "Audit log"]]
                [:form.mb-6 {:method "get" :action "/audit"}
                 [:div.flex.flex-wrap.gap-3.items-end
                  [:div.form-control
                   [:label.label [:span.label-text "Action"]]
                   [:select.select.select-bordered.w-full {:name "action"}
                    [:option {:value ""} "All actions"]
                    (for [a all-actions]
                      [:option {:value (str (namespace a) "/" (name a))
                                :selected (= (str action-filter) (str (namespace a) "/" (name a)))}
                       (get action-labels a (name a))])]]
                  [:div.form-control
                   [:label.label [:span.label-text "Actor (UID)"]]
                   [:input.input.input-bordered.w-full
                    {:name "actor" :type "text" :placeholder "firebase UID"
                     :value (or actor-filter "")}]]
                  [:div.shrink-0
                   [:button.btn {:type "submit"} "Filter"]
                   (when (or action-filter actor-filter)
                     [:a.btn.btn-outline {:href "/audit"} "Clear"])]]]
                (if (seq entries)
                  [:div.overflow-x-auto
                   [:table.table.table-zebra.w-full.text-sm
                    [:thead
                     [:tr [:th "Time"] [:th "Action"] [:th "Actor"] [:th "Entity"]
                      [:th "Reason"] [:th "Before / After"]]]
                    [:tbody
                     (for [e entries]
                       [:tr
                        [:td.whitespace-nowrap
                         (when-let [at (:audit/at e)] (.format fmt at))]
                        [:td
                         [:span.badge.badge-ghost.badge-sm
                          (get action-labels (:audit/action e)
                               (str (:audit/action e)))]]
                        [:td.ss-mono.text-xs (or (:audit/actor e) "—")]
                        [:td.ss-mono.text-xs
                         (str (when-let [t (:audit/entity-type e)] (name t))
                              " "
                              (when-let [i (:audit/entity-id e)] (str i)))]
                        [:td.text-xs (or (:audit/reason e) "")]
                        [:td.text-xs
                         (when (or (:audit/before e) (:audit/after e))
                           [:details
                            [:summary "diff"]
                            (when-let [b (:audit/before e)]
                              [:pre.text-xs.whitespace-pre-wrap (str "− " b)])
                            (when-let [a (:audit/after e)]
                              [:pre.text-xs.whitespace-pre-wrap (str "+ " a)])])]])]]]
                  [:p.opacity-60 "No audit entries found."]))))

(defn fixture-import-upload
  "Step 1 — file upload form."
  [event & [{:keys [error]}]]
  (let [event-id (:event/id event)]
    (shared/doc (str "Import Fixtures — " (:event/name event))
                {:active :events}
                [:div.flex.flex-wrap.items-center.justify-between.gap-3.mb-6
                 [:p [:a {:href (str "/events/" event-id)} "← Back to event"]]
                 [:h2 "Import fixtures from CSV"]]
                (when error [:p.text-error error])
                [:p.opacity-60 "Upload a CSV file with a header row. You'll map columns to fields on the next step."]
                [:form {:method "POST"
                        :action (str "/events/" event-id "/import/upload")
                        :enctype "multipart/form-data"}
                 (shared/csrf-field)
                 [:div.form-control
                  [:label.label [:span.label-text "CSV file"]]
                  [:input.file-input.file-input-bordered.w-full
                   {:type "file" :id "csv-file" :name "csv-file"
                    :accept ".csv,text/csv" :required true}]]
                 [:button.btn.mt-4 {:type "submit"} "Upload & continue →"]])))

(defn fixture-import-map
  "Step 2 — column mapping form."
  [event headers fields & [{:keys [error]}]]
  (let [event-id (:event/id event)]
    (shared/doc (str "Map Columns — " (:event/name event))
                {:active :events}
                [:div.flex.flex-wrap.items-center.justify-between.gap-3.mb-6
                 [:p [:a {:href (str "/events/" event-id "/import")} "← Re-upload"]]
                 [:h2 "Map CSV columns to fixture fields"]]
                (when error [:p.text-error error])
                [:p.opacity-60 "Select which CSV column maps to each fixture field. Required fields must be mapped."]
                [:form {:method "POST" :action (str "/events/" event-id "/import/map")}
                 (shared/csrf-field)
                 [:table.table.table-zebra.w-full
                  [:thead [:tr [:th "Field"] [:th "Required?"] [:th "CSV column"]]]
                  [:tbody
                   (for [f fields]
                     [:tr
                      [:td (:label f)]
                      [:td (if (:required? f) "Yes" "—")]
                      [:td
                       [:select.select.select-bordered.w-full
                        {:name (name (:key f))}
                        [:option {:value ""} "— skip —"]
                        (map-indexed
                         (fn [i h]
                           [:option {:value (str i)} h])
                         headers)]]])]]
                 [:button.btn.mt-4 {:type "submit"} "Preview import →"]])))

(defn fixture-import-preview
  "Step 3 — preview with errors highlighted, confirm button."
  [event valid errors & [{:keys [import-error]}]]
  (let [event-id (:event/id event)
        fmt (java.text.SimpleDateFormat. "d MMM HH:mm")]
    (shared/doc (str "Import Preview — " (:event/name event))
                {:active :events}
                [:div.flex.flex-wrap.items-center.justify-between.gap-3.mb-6
                 [:p [:a {:href (str "/events/" event-id "/import")} "← Start over"]]
                 [:h2 "Import preview"]]
                (when import-error [:p.text-error import-error])
                (when (seq errors)
                  [:section.mb-6
                   [:h3.text-error (str (count errors) " row(s) with errors — will be skipped")]
                   [:table.table.table-zebra.w-full
                    [:thead [:tr [:th "Row"] [:th "Issues"]]]
                    [:tbody
                     (for [e errors]
                       [:tr.bg-yellow-100
                        [:td (inc (:row-index e))]
                        [:td (str/join " · " (:messages e))]])]]])
                (if (seq valid)
                  [:section
                   [:h3 (str (count valid) " fixture(s) ready to import")]
                   [:table.table.table-zebra.w-full
                    [:thead [:tr [:th "Sport"] [:th "Team A"] [:th "Team B"] [:th "Start"] [:th "Venue"]]]
                    [:tbody
                     (for [v valid]
                       (let [p (:params v)]
                         [:tr
                          [:td (name (:fixture/sport-code p))]
                          [:td (:_team-a-name p)]
                          [:td (:_team-b-name p)]
                          [:td (when-let [d (:fixture/start-at p)] (.format fmt d))]
                          [:td (or (:fixture/venue p) "—")]]))]]
                   [:form {:method "POST" :action (str "/events/" event-id "/import/confirm")}
                    (shared/csrf-field)
                    [:p.opacity-60 "Fixtures will be created in draft state. You can review and publish them from the event page."]
                    [:button.btn {:type "submit"} (str "Import " (count valid) " fixture(s)")]]]
                  [:div
                   [:p.opacity-60 "No valid fixtures to import."]
                   [:p [:a {:href (str "/events/" event-id "/import")} "← Try again"]]]))))
