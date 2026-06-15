(ns sports-manager.views.scorekeeper
  "Scorekeeper mobile pages: code entry, confirmation, live scoring, submission."
  (:require [clojure.edn :as edn]
            [sports-manager.views.shared :as shared]))

(defn scorekeeper-code-entry
  "Public page where a scorekeeper enters their code to access a fixture."
  [& [{:keys [error prefill]}]]
  (shared/doc-public "Enter Scoring Code — SchoolScore"
                     [:div.flex.flex-col.gap-4
                      [:h1 "Scorer access"]
                      [:p "Enter your scoring code to access your assigned game."]
                      (when error [:p.text-error error])
                      [:form {:method "post" :action "/score"}
                       (shared/csrf-field)
                       [:div.form-control
                        [:label.label [:span.label-text "Scoring code"]]
                        [:input.input.input-bordered.w-full
                         {:id "score-code" :name "code" :type "text"
                          :placeholder "e.g. ABC23DEF"
                          :value (or prefill "")
                          :autocomplete "off"
                          :autofocus true
                          :class "uppercase tracking-widest"}]]
                       [:button.btn {:type "submit"} "Access game"]]]))

(defn scorekeeper-confirm
  "Confirmation page shown after a valid code is entered."
  [fixture scode-id]
  (let [fmt (java.text.SimpleDateFormat. "d MMM yyyy HH:mm")]
    (shared/doc-public "Confirm Game — SchoolScore"
                       [:div.flex.flex-col.gap-4
                        [:h1 "Confirm your game"]
                        [:p "Please confirm you are scoring the correct game before you begin."]
                        [:div.rounded-lg.border.border-base-300.p-4.space-y-2
                         [:dl
                          [:dt "Sport"] [:dd (get-in fixture [:fixture/sport-template :sport-template/name] "—")]
                          [:dt "Teams"]
                          [:dd (str (get-in fixture [:fixture/team-a :participant/name] "—")
                                    " vs "
                                    (get-in fixture [:fixture/team-b :participant/name] "—"))]
                          (when-let [ag (:fixture/age-group fixture)]
                            (list [:dt "Age group"] [:dd ag]))
                          (when-let [v (:fixture/venue fixture)]
                            (list [:dt "Venue"] [:dd v]))
                          (when-let [s (:fixture/start-at fixture)]
                            (list [:dt "Start"] [:dd (.format fmt s)]))]]
                        [:form {:method "post" :action (str "/score/" (:fixture/id fixture) "/confirm")}
                         (shared/csrf-field)
                         [:input {:type "hidden" :name "scode-id" :value (str scode-id)}]
                         [:button.btn {:type "submit"} "Yes, this is my game — start scoring"]
                         [:p [:a {:href "/score"} "← Wrong game? Go back"]]]])))

(defn scorekeeper-live
  "Mobile scoring page."
  [fixture scode-id score period-labels current-period]
  (let [fmt (java.text.SimpleDateFormat. "d MMM yyyy HH:mm")
        fixture-id (:fixture/id fixture)
        team-a (get-in fixture [:fixture/team-a :participant/name] "Team A")
        team-b (get-in fixture [:fixture/team-b :participant/name] "Team B")
        base-action (str "/score/" fixture-id "/event")
        active-period (or current-period (first period-labels))
        increments (when-let [s (get-in fixture [:fixture/sport-template :sport-template/scoring-increments])]
                     (try (edn/read-string s) (catch Exception _ nil)))
        score-buttons (if (seq increments) increments [1])
        score-btn (fn [team delta label]
                    [:form.inline {:method "post" :action base-action}
                     (shared/csrf-field)
                     [:input {:type "hidden" :name "scode-id" :value (str scode-id)}]
                     [:input {:type "hidden" :name "team" :value team}]
                     [:input {:type "hidden" :name "delta" :value (str delta)}]
                     (when active-period
                       [:input {:type "hidden" :name "period" :value active-period}])
                     [:button.btn.btn-sm {:type "submit"
                                          :class (if (pos? delta) "btn-success" "btn-outline")}
                      label]])]
    (shared/doc-public "Live Scoring — SchoolScore"
                       [:div#sync-status.sync-status {:data-fixture-id (str fixture-id)
                                                      :data-scode-id (str scode-id)}
                        [:span#sync-label "●  online"]]
                       [:div.max-w-sm.mx-auto.p-4.flex.flex-col.gap-4
                        [:div.flex.items-center.justify-between
                         [:h2.text-lg.font-bold.m-0
                          (get-in fixture [:fixture/sport-template :sport-template/name] "Game")]
                         (when-let [ag (:fixture/age-group fixture)]
                           [:span.text-sm.text-gray-500 ag])]
                        (when-let [v (:fixture/venue fixture)]
                          [:p.text-sm.text-gray-500.m-0 v])
                        (when (seq period-labels)
                          [:div.flex.gap-2
                           (for [pl period-labels]
                             [:a {:href (str "/score/" fixture-id "/live?scode=" scode-id "&period=" pl)
                                  :class (if (= pl active-period)
                                           "btn btn-sm btn-primary"
                                           "btn btn-sm btn-outline")}
                              pl])])
                        [:div.grid.grid-cols-3.gap-2.items-center.mt-2
                         ;; Team A
                         [:div.flex.flex-col.items-center.gap-2
                          [:span.text-xs.font-semibold.text-center.text-gray-400 team-a]
                          [:span.text-7xl.font-bold.tabular-nums.leading-none (str (:a score))]
                          [:div.flex.flex-wrap.justify-center.gap-1.mt-1
                           (for [inc score-buttons]
                             (score-btn "a" inc (str "+" inc)))]
                          (score-btn "a" -1 "−1")]
                         ;; VS
                         [:div.text-xl.text-gray-500.text-center "vs"]
                         ;; Team B
                         [:div.flex.flex-col.items-center.gap-2
                          [:span.text-xs.font-semibold.text-center.text-gray-400 team-b]
                          [:span.text-7xl.font-bold.tabular-nums.leading-none (str (:b score))]
                          [:div.flex.flex-wrap.justify-center.gap-1.mt-1
                           (for [inc score-buttons]
                             (score-btn "b" inc (str "+" inc)))]
                          (score-btn "b" -1 "−1")]]
                        [:form.mt-4 {:method "post" :action (str "/score/" fixture-id "/submit")}
                         (shared/csrf-field)
                         [:input {:type "hidden" :name "scode-id" :value (str scode-id)}]
                         [:button.btn.btn-error.w-full
                          {:type "submit"
                           :onclick "return confirm('Submit final score? This cannot be undone.')"}
                          "Submit final score"]]
                        (when-let [s (:fixture/start-at fixture)]
                          [:p.text-xs.text-gray-400.text-center.mt-2 (.format fmt s)])]
                       [:script {:src "/js/score-queue.js" :type "module"}]
                       [:script {:src "/js/sync-engine.js" :type "module"}])))

(defn scorekeeper-submitted
  "Confirmation page shown after a final score has been submitted."
  [final-score]
  (let [status (:final-score/status final-score)
        a-score (:final-score/team-a-score final-score)
        b-score (:final-score/team-b-score final-score)
        accepted? (= status :final-score.status/accepted)
        pending? (= status :final-score.status/pending)]
    (shared/doc-public "Score Submitted — SchoolScore"
                       [:div.flex.flex-col.gap-4
                        [:h1 "Final score submitted"]
                        [:div.text-4xl.font-bold.text-center
                         [:span.text-6xl.font-bold.tabular-nums (str a-score " – " b-score)]]
                        (cond
                          accepted?
                          [:p.text-success.font-semibold "Score accepted. The result has been recorded."]
                          pending?
                          [:p.text-warning.font-semibold "Score is pending admin confirmation."]
                          :else
                          [:p "Score submitted."])
                        [:p "You may close this window."]])))
