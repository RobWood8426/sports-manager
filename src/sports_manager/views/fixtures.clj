(ns sports-manager.views.fixtures
  "Fixture-related pages: import wizard, score comparison, disputes, audit log."
  (:require [clojure.string :as str]
            [sports-manager.i18n :as i18n]
            [sports-manager.views.shared :as shared]))

(defn fixture-comparison
  "Admin view showing two final-score submissions side-by-side."
  [fixture comparison & [{:keys [resolve-errors lang]
                          :or {resolve-errors {} lang "en"}}]]
  (let [tr (fn [k] (i18n/t lang k))
        fmt (java.text.SimpleDateFormat. "d MMM yyyy HH:mm")
        event-id (get-in fixture [:fixture/event :event/id])
        fid (:fixture/id fixture)
        {:keys [submissions match? status]} comparison
        status-label {:no-submissions (tr :comparison/status-no-submissions)
                      :one-pending (tr :comparison/status-one-pending)
                      :match (tr :comparison/status-match)
                      :mismatch (tr :comparison/status-mismatch)
                      :accepted (tr :comparison/status-accepted)
                      :disputed (tr :comparison/status-disputed)}
        status-class {:match "text-success font-semibold"
                      :accepted "text-success font-semibold"
                      :mismatch "text-error font-semibold"
                      :disputed "text-error font-semibold"
                      :one-pending "text-warning font-semibold"
                      :no-submissions "opacity-60"}]
    (shared/doc (str (tr :comparison/page-title) (get-in fixture [:fixture/sport-template :sport-template/name]))
                {:active :events :lang lang}
                [:div.flex.flex-wrap.items-center.justify-between.gap-3.mb-6
                 [:p [:a {:href (str "/events/" event-id)} (tr :comparison/back-to-event)]]
                 [:h2 (tr :comparison/heading)]]
                [:p
                 [:strong (tr :comparison/status-prefix)]
                 [:span {:class (get status-class status "text-warning font-semibold")}
                  (get status-label status (name status))]]
                (if (seq submissions)
                  [:table.table.table-zebra.w-full
                   [:thead [:tr [:th (tr :comparison/col-scorekeeper)] [:th (tr :comparison/col-team-a)] [:th (tr :comparison/col-team-b)] [:th (tr :comparison/col-submitted)] [:th (tr :comparison/col-status)]]]
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
                  [:p.opacity-60 (tr :comparison/no-submissions)])
                (when (= :disputed status)
                  [:section.mt-8.rounded-lg.border.border-error.p-4.space-y-3
                   {:style "background: color-mix(in srgb, oklch(var(--er)) 8%, transparent);"}
                   [:h3 (tr :comparison/resolve-title)]
                   [:p.opacity-60 (tr :comparison/resolve-intro)]
                   (when-let [err (:form resolve-errors)]
                     [:p.text-error err])
                   [:form {:method "POST"
                           :action (str "/events/" event-id "/fixtures/" fid "/resolve")}
                    (shared/csrf-field)
                    [:div.form-control
                     [:label.label
                      [:span.label-text
                       (str (get-in fixture [:fixture/team-a :participant/name] "Team A") (tr :comparison/score-suffix))]]
                     [:input.input.input-bordered.w-full
                      {:type "number" :id "confirmed-a" :name "confirmed-a"
                       :min "0" :required true
                       :value (get resolve-errors :confirmed-a "")}]
                     (when-let [e (:confirmed-a resolve-errors)]
                       [:label.label [:span.label-text-alt.text-error e]])]
                    [:div.form-control
                     [:label.label
                      [:span.label-text
                       (str (get-in fixture [:fixture/team-b :participant/name] "Team B") (tr :comparison/score-suffix))]]
                     [:input.input.input-bordered.w-full
                      {:type "number" :id "confirmed-b" :name "confirmed-b"
                       :min "0" :required true
                       :value (get resolve-errors :confirmed-b "")}]
                     (when-let [e (:confirmed-b resolve-errors)]
                       [:label.label [:span.label-text-alt.text-error e]])]
                    [:div.form-control
                     [:label.label [:span.label-text (tr :comparison/reason-label)]]
                     [:textarea.textarea.textarea-bordered.w-full
                      {:id "reason" :name "reason" :rows "3" :required true
                       :placeholder (tr :comparison/reason-placeholder)}
                      (get resolve-errors :reason "")]
                     (when-let [e (:reason resolve-errors)]
                       [:label.label [:span.label-text-alt.text-error e]])]
                    [:button.btn {:type "submit"} (tr :comparison/confirm-resolve)]]]))))

(defn disputes-page
  "Admin page listing all disputed fixtures for the tenant."
  [disputed & [{:keys [lang] :or {lang "en"}}]]
  (let [tr (fn [k] (i18n/t lang k))
        fmt (java.text.SimpleDateFormat. "d MMM yyyy HH:mm")]
    (shared/doc (tr :disputes/page-title) {:active :disputes :lang lang}
                [:div.flex.flex-wrap.items-center.justify-between.gap-3.mb-6
                 [:h2 (tr :disputes/heading)]]
                (if (seq disputed)
                  [:table.table.table-zebra.w-full
                   [:thead
                    [:tr
                     [:th (tr :disputes/col-event)]
                     [:th (tr :disputes/col-fixture)]
                     [:th (tr :disputes/col-sport)]
                     [:th (tr :disputes/col-submitted)]
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
                               (tr :disputes/review)]]]))]]
                  [:p.opacity-60 (tr :disputes/none)]))))

(defn audit-log-page
  "Admin audit log page. `entries` is the seq from audit/list-by-tenant."
  [entries & [{:keys [filters lang] :or {filters {} lang "en"}}]]
  (let [tr (fn [k] (i18n/t lang k))
        fmt (java.text.SimpleDateFormat. "d MMM yyyy HH:mm:ss")
        action-filter (:action filters)
        actor-filter (:actor filters)
        action-labels {:fixture/edit (tr :audit/action-fixture-edited)
                       :fixture/publish (tr :audit/action-fixture-published)
                       :scode/generated (tr :audit/action-code-generated)
                       :scode/revoked (tr :audit/action-code-revoked)
                       :scode/expired (tr :audit/action-code-expired)
                       :scode/started (tr :audit/action-game-started)
                       :scode/live (tr :audit/action-scoring-live)
                       :scode/submitted (tr :audit/action-score-submitted)
                       :scode/final-accepted (tr :audit/action-score-accepted)
                       :scode/final-disputed (tr :audit/action-score-disputed)
                       :final-score/submitted (tr :audit/action-final-score-submitted)
                       :final-score/dual-accepted (tr :audit/action-dual-score-accepted)
                       :final-score/dual-disputed (tr :audit/action-dual-score-disputed)
                       :final-score/dispute-resolved (tr :audit/action-dispute-resolved)
                       :user/add-to-tenant (tr :audit/action-user-added)
                       :user/remove-from-tenant (tr :audit/action-user-removed)
                       :user/grant-role (tr :audit/action-role-granted)
                       :user/revoke-role (tr :audit/action-role-revoked)}
        all-actions (sort (keys action-labels))]
    (shared/doc (tr :audit/page-title) {:active :audit :lang lang}
                [:div.flex.flex-wrap.items-center.justify-between.gap-3.mb-6
                 [:h2 (tr :audit/heading)]]
                [:form.mb-6 {:method "get" :action "/audit"}
                 [:div.flex.flex-wrap.gap-3.items-end
                  [:div.form-control
                   [:label.label [:span.label-text (tr :audit/action-label)]]
                   [:select.select.select-bordered.w-full {:name "action"}
                    [:option {:value ""} (tr :audit/all-actions)]
                    (for [a all-actions]
                      [:option {:value (str (namespace a) "/" (name a))
                                :selected (= (str action-filter) (str (namespace a) "/" (name a)))}
                       (get action-labels a (name a))])]]
                  [:div.form-control
                   [:label.label [:span.label-text (tr :audit/actor-label)]]
                   [:input.input.input-bordered.w-full
                    {:name "actor" :type "text" :placeholder (tr :audit/actor-placeholder)
                     :value (or actor-filter "")}]]
                  [:div.shrink-0
                   [:button.btn {:type "submit"} (tr :audit/filter)]
                   (when (or action-filter actor-filter)
                     [:a.btn.btn-outline {:href "/audit"} (tr :audit/clear)])]]]
                (if (seq entries)
                  [:div.overflow-x-auto
                   [:table.table.table-zebra.w-full.text-sm
                    [:thead
                     [:tr [:th (tr :audit/col-time)] [:th (tr :audit/col-action)] [:th (tr :audit/col-actor)] [:th (tr :audit/col-entity)]
                      [:th (tr :audit/col-reason)] [:th (tr :audit/col-before-after)]]]
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
                            [:summary (tr :audit/diff)]
                            (when-let [b (:audit/before e)]
                              [:pre.text-xs.whitespace-pre-wrap (str "− " b)])
                            (when-let [a (:audit/after e)]
                              [:pre.text-xs.whitespace-pre-wrap (str "+ " a)])])]])]]]
                  [:p.opacity-60 (tr :audit/none)]))))

(defn fixture-import-upload
  "Step 1 — file upload form."
  [event & [{:keys [error lang] :or {lang "en"}}]]
  (let [tr (fn [k] (i18n/t lang k))
        event-id (:event/id event)]
    (shared/doc (str (tr :import/upload-page-title) (:event/name event))
                {:active :events :lang lang}
                [:div.flex.flex-wrap.items-center.justify-between.gap-3.mb-6
                 [:p [:a {:href (str "/events/" event-id)} (tr :import/back-to-event)]]
                 [:h2 (tr :import/upload-heading)]]
                (when error [:p.text-error error])
                [:p.opacity-60 (tr :import/upload-intro)]
                [:form {:method "POST"
                        :action (str "/events/" event-id "/import/upload")
                        :enctype "multipart/form-data"}
                 (shared/csrf-field)
                 [:div.form-control
                  [:label.label [:span.label-text (tr :import/csv-file)]]
                  [:input.file-input.file-input-bordered.w-full
                   {:type "file" :id "csv-file" :name "csv-file"
                    :accept ".csv,text/csv" :required true}]]
                 [:button.btn.mt-4 {:type "submit"} (tr :import/upload-continue)]])))

(defn fixture-import-map
  "Step 2 — column mapping form."
  [event headers fields & [{:keys [error lang] :or {lang "en"}}]]
  (let [tr (fn [k] (i18n/t lang k))
        event-id (:event/id event)]
    (shared/doc (str (tr :import/map-page-title) (:event/name event))
                {:active :events :lang lang}
                [:div.flex.flex-wrap.items-center.justify-between.gap-3.mb-6
                 [:p [:a {:href (str "/events/" event-id "/import")} (tr :import/re-upload)]]
                 [:h2 (tr :import/map-heading)]]
                (when error [:p.text-error error])
                [:p.opacity-60 (tr :import/map-intro)]
                [:form {:method "POST" :action (str "/events/" event-id "/import/map")}
                 (shared/csrf-field)
                 [:table.table.table-zebra.w-full
                  [:thead [:tr [:th (tr :import/col-field)] [:th (tr :import/col-required)] [:th (tr :import/col-csv-column)]]]
                  [:tbody
                   (for [f fields]
                     [:tr
                      [:td (:label f)]
                      [:td (if (:required? f) (tr :import/yes) "—")]
                      [:td
                       [:select.select.select-bordered.w-full
                        {:name (name (:key f))}
                        [:option {:value ""} (tr :import/skip)]
                        (map-indexed
                         (fn [i h]
                           [:option {:value (str i)} h])
                         headers)]]])]]
                 [:button.btn.mt-4 {:type "submit"} (tr :import/preview)]])))

(defn fixture-import-preview
  "Step 3 — preview with errors highlighted, confirm button."
  [event valid errors & [{:keys [import-error lang] :or {lang "en"}}]]
  (let [tr (fn [k & args] (apply i18n/t lang k args))
        event-id (:event/id event)
        fmt (java.text.SimpleDateFormat. "d MMM HH:mm")]
    (shared/doc (str (tr :import/preview-page-title) (:event/name event))
                {:active :events :lang lang}
                [:div.flex.flex-wrap.items-center.justify-between.gap-3.mb-6
                 [:p [:a {:href (str "/events/" event-id "/import")} (tr :import/start-over)]]
                 [:h2 (tr :import/preview-heading)]]
                (when import-error [:p.text-error import-error])
                (when (seq errors)
                  [:section.mb-6
                   [:h3.text-error (tr :import/rows-with-errors (count errors))]
                   [:table.table.table-zebra.w-full
                    [:thead [:tr [:th (tr :import/col-row)] [:th (tr :import/col-issues)]]]
                    [:tbody
                     (for [e errors]
                       [:tr.bg-yellow-100
                        [:td (inc (:row-index e))]
                        [:td (str/join " · " (:messages e))]])]]])
                (if (seq valid)
                  [:section
                   [:h3 (tr :import/fixtures-ready (count valid))]
                   [:table.table.table-zebra.w-full
                    [:thead [:tr [:th (tr :import/col-sport)] [:th (tr :import/col-team-a)] [:th (tr :import/col-team-b)] [:th (tr :import/col-start)] [:th (tr :import/col-venue)]]]
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
                    [:p.opacity-60 (tr :import/draft-note)]
                    [:button.btn {:type "submit"} (tr :import/import-button (count valid))]]]
                  [:div
                   [:p.opacity-60 (tr :import/no-valid)]
                   [:p [:a {:href (str "/events/" event-id "/import")} (tr :import/try-again)]]]))))
