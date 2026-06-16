(ns sports-manager.views.spectator
  "Public spectator pages: event landing, fixture live score, code entry."
  (:require [clojure.string :as str]
            [sports-manager.views.shared :as shared]))

(defn spectator-code-entry
  "Public landing page for entering an event access code."
  [& [{:keys [error]}]]
  (shared/doc-public "Enter event code — SchoolScore"
                     [:div.max-w-xs.mx-auto.mt-12
                      [:div.flex.justify-center.mb-3
                       [:img {:src "/mark.svg" :alt "SchoolScore" :width "32" :height "32"}]]
                      [:h1.mb-2 "Find your event"]
                      [:p.opacity-60.mb-6.text-sm
                       "Enter the event code from your invitation or scan the QR code."]
                      [:form {:method "get" :action "/e"}
                       [:div.form-control
                        [:label.label [:span.label-text "Event code"]]
                        [:input.input.input-bordered.input-lg.w-full.ss-mono.text-center
                         {:id "code" :name "code" :type "text"
                          :placeholder "e.g. ABC123"
                          :autocomplete "off"
                          :class "uppercase"
                          :style "letter-spacing:0.22em;font-weight:700"}]
                        (when error [:label.label [:span.label-text-alt.text-error error]])]
                       [:div.mt-4
                        [:button.btn.btn-primary.btn-lg.w-full {:type "submit"} "Go to event"]]]]))

(defn spectator-event
  "Public mobile-first event landing page.
  Fixtures are rendered with live games sorted to the top; order within the
  live and non-live groups is preserved (callers pass them start-time ordered)."
  [event participants fixtures & [{:keys [filters sports]
                                   :or {filters {} sports []}}]]
  (let [fmt (java.text.SimpleDateFormat. "d MMM HH:mm")
        date-fmt (java.text.SimpleDateFormat. "d MMM yyyy")
        sport-filter (get filters :sport-code "")
        team-filter (get filters :team-name "")
        live? (fn [f] (= "Live" (first (shared/fixture-status-label f))))
        ;; stable sort keeps the incoming start-time order within each group
        fixtures (sort-by #(if (live? %) 0 1) fixtures)
        live-count (count (filter live? fixtures))]
    (shared/doc-public (str (:event/name event) " — SchoolScore")
                       {:code (:event/code event)}
                       [:div.mb-6
                        [:h1.mb-1 (:event/name event)]
                        (when (:event/description event)
                          [:p.opacity-60.m-0 (:event/description event)])
                        (when (or (:event/start-at event) (:event/end-at event))
                          [:p.text-sm.opacity-60.mt-1.mb-0
                           (when-let [s (:event/start-at event)] (.format date-fmt s))
                           (when (and (:event/start-at event) (:event/end-at event)) " – ")
                           (when-let [e (:event/end-at event)] (.format date-fmt e))])]
                       [:section.mb-6
                        [:div.flex.items-center.justify-between.mb-3
                         [:span.ss-label "Fixtures"]
                         (when (pos? live-count)
                           [:span.badge.badge-sm.badge-accent.gap-1
                            [:span.ss-live-dot] (str live-count " live")])]
                        [:form {:method "GET" :class "flex gap-2 flex-wrap mb-4"}
                         (when (seq sports)
                           [:select.select.select-bordered.select-sm
                            {:name "sport"}
                            [:option {:value "" :selected (str/blank? sport-filter)} "All sports"]
                            (for [s sports]
                              [:option {:value (name (:sport-template/code s))
                                        :selected (= sport-filter (name (:sport-template/code s)))}
                               (:sport-template/name s)])])
                         [:input.input.input-bordered.input-sm.flex-1.min-w-32
                          {:type "text" :name "team" :placeholder "Filter by team…"
                           :value team-filter}]
                         [:button.btn.btn-sm {:type "submit"} "Filter"]]
                        (if (seq fixtures)
                          [:div.flex.flex-col.gap-2
                           (for [f fixtures]
                             (let [score (:fixture/live-score f)
                                   a-name (get-in f [:fixture/team-a :participant/name] "TBA")
                                   b-name (get-in f [:fixture/team-b :participant/name] "TBA")
                                   sport (get-in f [:fixture/sport-template :sport-template/name])
                                   [lbl] (shared/fixture-status-label f)

                                   href (str "/e/fixture/" (:fixture/id f))]
                               [:a.no-underline.text-inherit.block {:href href}
                                [:div.ss-card.p-3.transition-colors {:class "hover:bg-base-300"}
                                 [:div.flex.justify-between.items-center.mb-1
                                  [:span.text-xs.opacity-60 sport]
                                  (shared/fixture-status-pill lbl)]
                                 [:div.flex.items-center.gap-3.text-base
                                  [:span.flex-1 a-name]
                                  [:span.ss-score.text-2xl.min-w-12.text-center.text-base-content
                                   (if score
                                     (str (:a score) " – " (:b score))
                                     "– vs –")]
                                  [:span.flex-1.text-right b-name]]
                                 (when-let [start (:fixture/start-at f)]
                                   [:div.text-xs.opacity-50.mt-1
                                    (.format fmt start)
                                    (when-let [v (:fixture/venue f)]
                                      (str " · " v))])]]))]
                          [:p.opacity-60.text-sm
                           (if (or (not (str/blank? sport-filter)) (not (str/blank? team-filter)))
                             "No fixtures match the current filters."
                             "No fixtures published yet.")])]
                       [:section
                        [:span.ss-label.block.mb-3 "Participating schools"]
                        (if (seq participants)
                          [:div.flex.flex-wrap.gap-2
                           (for [p participants]
                             [:span.badge.badge-outline.badge-lg (:participant/name p)])]
                          [:p.opacity-60.text-sm "No schools listed yet."])]
                       [:p.mt-8.text-xs.opacity-40.text-center "Powered by SchoolScore"])))

(defn spectator-fixture
  "Public live score detail page for a single fixture.
  `event-code` (optional) renders a back link to the event summary screen."
  [fixture & [{:keys [event-code]}]]
  (let [fmt (java.text.SimpleDateFormat. "d MMM HH:mm")
        fid (str (:fixture/id fixture))
        score (:fixture/live-score fixture)
        final-status (:fixture/final-score-status fixture)
        a-name (get-in fixture [:fixture/team-a :participant/name] "Team A")
        b-name (get-in fixture [:fixture/team-b :participant/name] "Team B")
        sport (get-in fixture [:fixture/sport-template :sport-template/name])
        start (:fixture/start-at fixture)
        [status-label] (shared/fixture-status-label fixture)
        should-poll? (nil? final-status)]
    (shared/doc-public (str a-name " vs " b-name " — SchoolScore")
                       {:brand? true}
                       [:div.max-w-lg.mx-auto
                        (when-not (str/blank? event-code)
                          [:a.inline-flex.items-center.gap-1.text-sm.opacity-60.no-underline.mb-4
                           {:href (str "/e/" event-code)
                            :class "hover:opacity-100 transition-opacity"}
                           [:span "←"] [:span "Back to event"]])
                        [:p.text-sm.opacity-60.mb-2
                         sport
                         (when-let [v (:fixture/venue fixture)] (str " · " v))
                         (when start (str " · " (.format fmt start)))]
                        [:div.mb-5 (shared/fixture-status-pill status-label {:id "fixture-status"})]
                        [:div.ss-card.bg-base-100.p-7.my-6
                         [:div#score-display.flex.items-center.justify-center.gap-6
                          {:data-fixture-id fid}
                          [:div.flex-1.text-center
                           [:div.ss-label.mb-2 a-name]
                           [:div#score-a.ss-score.ss-score-md.text-base-content (or (:a score) 0)]]
                          [:div.ss-score.text-3xl.opacity-30 "–"]
                          [:div.flex-1.text-center
                           [:div.ss-label.mb-2 b-name]
                           [:div#score-b.ss-score.ss-score-md.text-base-content (or (:b score) 0)]]]]
                        (when should-poll?
                          [:p#last-updated.text-xs.opacity-60.text-center.m-0.inline-flex.items-center.justify-center.gap-2.w-full
                           [:span.ss-live-dot] "Updating live…"])
                        [:p.mt-8.text-xs.opacity-40.text-center "Powered by SchoolScore"]]
                       (when should-poll?
                         [:script {:src "/js/spectator-fixture.js" :type "module"}]))))
