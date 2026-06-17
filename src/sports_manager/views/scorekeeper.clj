(ns sports-manager.views.scorekeeper
  "Scorekeeper mobile pages: code entry, confirmation, live scoring, submission."
  (:require [clojure.edn :as edn]
            [sports-manager.i18n :as i18n]
            [sports-manager.views.shared :as shared]))

(defn scorekeeper-code-entry
  "Public page where a scorekeeper enters their code to access a fixture."
  [& [{:keys [error prefill lang] :or {lang "en"}}]]
  (let [tr (fn [k] (i18n/t lang k))]
    (shared/doc-public (tr :scorer/code-entry-title)
                       {:lang lang}
                       [:div.flex.flex-col.gap-4
                        [:div.flex.justify-center.mb-1
                         [:img {:src "/mark.svg" :alt (tr :scorer/brand-alt) :width "32" :height "32"}]]
                        [:h1 (tr :scorer/access-title)]
                        [:p.opacity-70 (tr :scorer/access-help)]
                        (when error [:p.text-error error])
                        [:form {:method "post" :action "/score"}
                         (shared/csrf-field)
                         [:div.form-control
                          [:label.label [:span.label-text (tr :scorer/scoring-code)]]
                          [:input.input.input-bordered.input-lg.w-full.ss-mono.text-center
                           {:id "score-code" :name "code" :type "text"
                            :placeholder (tr :scorer/code-placeholder)
                            :value (or prefill "")
                            :autocomplete "off"
                            :autofocus true
                            :class "uppercase"
                            :style "letter-spacing:0.22em;font-weight:700"}]]
                         [:button.btn.btn-primary.btn-lg.w-full.mt-2 {:type "submit"} (tr :scorer/access-game)]
                         [:p.text-center.text-xs.opacity-50.mt-1
                          (tr :scorer/codes-issued)]]])))

(defn scorekeeper-confirm
  "Confirmation page shown after a valid code is entered."
  [fixture scode-id & [{:keys [lang] :or {lang "en"}}]]
  (let [tr (fn [k] (i18n/t lang k))
        fmt (java.text.SimpleDateFormat. "d MMM yyyy HH:mm")
        row (fn [label value]
              (list [:dt.ss-label {:class "self-center"} label]
                    [:dd.m-0.text-right.font-medium.text-base-content value]))]
    (shared/doc-public (tr :scorer/confirm-title)
                       {:lang lang}
                       [:div.flex.flex-col.gap-4
                        [:h1 (tr :scorer/confirm-heading)]
                        [:p.opacity-70 (tr :scorer/confirm-help)]
                        [:div.ss-card.p-4
                         [:dl.m-0.grid.gap-x-4.gap-y-2.5 {:style "grid-template-columns:auto 1fr"}
                          (row (tr :scorer/sport) (get-in fixture [:fixture/sport-template :sport-template/name] "—"))
                          (row (tr :scorer/teams)
                               (str (get-in fixture [:fixture/team-a :participant/name] "—")
                                    " vs "
                                    (get-in fixture [:fixture/team-b :participant/name] "—")))
                          (when-let [ag (:fixture/age-group fixture)]
                            (row (tr :scorer/age-group) ag))
                          (when-let [v (:fixture/venue fixture)]
                            (row (tr :scorer/venue) v))
                          (when-let [s (:fixture/start-at fixture)]
                            (row (tr :scorer/start) (.format fmt s)))]]
                        [:form {:method "post" :action (str "/score/" (:fixture/id fixture) "/confirm")}
                         (shared/csrf-field)
                         [:input {:type "hidden" :name "scode-id" :value (str scode-id)}]
                         [:button.btn.btn-accent.btn-lg.w-full {:type "submit"}
                          (tr :scorer/confirm-start)]
                         [:p.text-center.mt-2 [:a.opacity-70 {:href "/score"} (tr :scorer/wrong-game)]]]])))

(defn scorekeeper-live
  "Mobile scoring page."
  [fixture scode-id score period-labels current-period & [{:keys [lang] :or {lang "en"}}]]
  (let [tr (fn [k] (i18n/t lang k))
        fmt (java.text.SimpleDateFormat. "d MMM yyyy HH:mm")
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
                                          :class (if (pos? delta) "btn-accent" "btn-outline")}
                      label]])
        stepper (fn [team team-name score-val]
                  [:div.flex.flex-col.items-center.gap-2.flex-1
                   [:span.ss-label.text-center team-name]
                   [:span.ss-score.ss-score-lg.text-base-content (str score-val)]
                   [:div.flex.flex-wrap.justify-center.gap-1.mt-1
                    (for [inc score-buttons]
                      (score-btn team inc (str "+" inc)))]
                   (score-btn team -1 "−1")])]
    (shared/doc-public (tr :scorer/live-title)
                       {:lang lang}
                       [:div#sync-status.sync-status {:data-fixture-id (str fixture-id)
                                                      :data-scode-id (str scode-id)}
                        [:span#sync-label.inline-flex.items-center.gap-2
                         [:span.ss-live-dot] (tr :scorer/online)]]
                       [:div.max-w-sm.mx-auto.p-4.flex.flex-col.gap-4
                        [:div.flex.items-center.justify-between
                         [:h2.text-xl.font-bold.m-0
                          (get-in fixture [:fixture/sport-template :sport-template/name] (tr :scorer/game))]
                         (when-let [ag (:fixture/age-group fixture)]
                           [:span.badge.badge-sm.badge-outline ag])]
                        (when-let [v (:fixture/venue fixture)]
                          [:p.text-sm.opacity-60.m-0 v])
                        (when (seq period-labels)
                          [:div.flex.gap-2
                           (for [pl period-labels]
                             [:a {:href (str "/score/" fixture-id "/live?scode=" scode-id "&period=" pl)
                                  :class (if (= pl active-period)
                                           "btn btn-sm btn-primary"
                                           "btn btn-sm btn-outline")}
                              pl])])
                        [:div.flex.gap-3.items-start.mt-2
                         (stepper "a" team-a (:a score))
                         [:span.ss-score.text-2xl.self-center.opacity-30.pt-7 "–"]
                         (stepper "b" team-b (:b score))]
                        [:form.mt-4 {:method "post" :action (str "/score/" fixture-id "/submit")}
                         (shared/csrf-field)
                         [:input {:type "hidden" :name "scode-id" :value (str scode-id)}]
                         [:button.btn.btn-error.btn-lg.w-full
                          {:type "submit"
                           :onclick (str "return confirm('" (tr :scorer/submit-confirm) "')")}
                          (tr :scorer/submit-final)]]
                        [:p.text-center.m-0
                         [:a.text-sm.opacity-60.no-underline
                          {:href (str "/e/fixture/" fixture-id) :target "_blank" :rel "noopener"
                           :class "hover:opacity-100 transition-opacity"}
                          (tr :scorer/view-public)]]
                        (when-let [s (:fixture/start-at fixture)]
                          [:p.text-xs.opacity-50.text-center.mt-2 (.format fmt s)])]
                       [:script {:src "/js/score-queue.js" :type "module"}]
                       [:script {:src "/js/sync-engine.js" :type "module"}])))

(defn scorekeeper-submitted
  "Confirmation page shown after a final score has been submitted."
  [final-score & [{:keys [lang] :or {lang "en"}}]]
  (let [tr (fn [k] (i18n/t lang k))
        status (:final-score/status final-score)
        a-score (:final-score/team-a-score final-score)
        b-score (:final-score/team-b-score final-score)
        accepted? (= status :final-score.status/accepted)
        pending? (= status :final-score.status/pending)]
    (shared/doc-public (tr :scorer/submitted-title)
                       {:lang lang}
                       [:div.flex.flex-col.gap-4.items-center.text-center
                        [:h1 (tr :scorer/submitted-heading)]
                        [:div.ss-score.ss-score-md.text-base-content
                         (str a-score " – " b-score)]
                        (cond
                          accepted?
                          [:p.text-success.font-semibold (tr :scorer/submitted-accepted)]
                          pending?
                          [:p.text-warning.font-semibold (tr :scorer/submitted-pending)]
                          :else
                          [:p (tr :scorer/submitted-default)])
                        [:p.opacity-60 (tr :scorer/close-window)]])))
