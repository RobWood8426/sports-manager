(ns sports-manager.views.spectator
  "Public spectator pages: event landing, fixture live score, code entry."
  (:require [clojure.string :as str]
            [sports-manager.views.shared :as shared]))

(defn spectator-code-entry
  "Public landing page for entering an event access code."
  [& [{:keys [error]}]]
  (shared/doc-public "Enter event code — Sports Manager"
                     [:div.max-w-xs.mx-auto.mt-12
                      [:h1.text-2xl.font-bold.mb-2 "Find your event"]
                      [:p.text-gray-500.mb-6.text-sm
                       "Enter the event code from your invitation or scan the QR code."]
                      [:form {:method "get" :action "/e"}
                       [:div.form-control
                        [:label.label [:span.label-text "Event code"]]
                        [:input.input.input-bordered.w-full
                         {:id "code" :name "code" :type "text"
                          :placeholder "e.g. ABC123"
                          :autocomplete "off"
                          :class "uppercase tracking-widest text-lg"}]
                        (when error [:label.label [:span.label-text-alt.text-error error]])]
                       [:div.flex.gap-3.mt-4
                        [:button.btn {:type "submit"} "Go to event"]]]]))

(defn spectator-event
  "Public mobile-first event landing page."
  [event participants fixtures & [{:keys [filters sports]
                                   :or {filters {} sports []}}]]
  (let [fmt (java.text.SimpleDateFormat. "d MMM HH:mm")
        date-fmt (java.text.SimpleDateFormat. "d MMM yyyy")
        now (java.util.Date.)
        fixture-status-label
        (fn [f]
          (let [end (:fixture/end-at f)
                start (:fixture/start-at f)
                fs (:fixture/final-score-status f)]
            (cond
              (#{:final-score.status/accepted} fs) "Final"
              (#{:final-score.status/disputed} fs) "Disputed"
              (#{:final-score.status/pending} fs) "Pending"
              (and end (.before end now)) "Ended"
              (and start (.before start now)) "Live"
              :else "Upcoming")))
        fixture-status-class
        (fn [f]
          (let [end (:fixture/end-at f)
                start (:fixture/start-at f)
                fs (:fixture/final-score-status f)]
            (cond
              (#{:final-score.status/accepted} fs) "text-success font-semibold"
              (#{:final-score.status/disputed} fs) "text-warning font-semibold"
              (#{:final-score.status/pending} fs) "text-warning font-semibold"
              (and end (.before end now)) "text-warning font-semibold"
              (and start (.before start now)) "text-success font-semibold"
              :else "text-warning font-semibold")))
        sport-filter (get filters :sport-code "")
        team-filter (get filters :team-name "")]
    (shared/doc-public (str (:event/name event) " — Sports Manager")
                       [:div.mb-6
                        [:h1.text-2xl.font-bold.mb-1 (:event/name event)]
                        (when (:event/description event)
                          [:p.text-gray-500.m-0 (:event/description event)])]
                       (when (or (:event/start-at event) (:event/end-at event))
                         [:p.text-sm.text-gray-500.mb-4
                          (when-let [s (:event/start-at event)] (.format date-fmt s))
                          (when (and (:event/start-at event) (:event/end-at event)) " – ")
                          (when-let [e (:event/end-at event)] (.format date-fmt e))])
                       [:section.mb-6
                        [:h2.text-base.font-semibold.mb-3 "Fixtures"]
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
                                   lbl (fixture-status-label f)
                                   cls (fixture-status-class f)
                                   href (str "/e/fixture/" (:fixture/id f))]
                               [:a.no-underline.text-inherit.block {:href href}
                                [:div.bg-base-100.border.border-base-300.rounded-lg.p-3
                                 [:div.flex.justify-between.items-center.mb-1
                                  [:span.text-xs.text-gray-500 sport]
                                  [:span.text-xs {:class cls} lbl]]
                                 [:div.flex.items-center.gap-3.text-base
                                  [:span.flex-1 a-name]
                                  [:span.text-xl.font-bold.min-w-12.text-center
                                   (if score
                                     (str (:a score) " – " (:b score))
                                     "– vs –")]
                                  [:span.flex-1.text-right b-name]]
                                 (when-let [start (:fixture/start-at f)]
                                   [:div.text-xs.text-gray-500.mt-1
                                    (.format fmt start)
                                    (when-let [v (:fixture/venue f)]
                                      (str " · " v))])]]))]
                          [:p.text-gray-500.text-sm
                           (if (or (not (str/blank? sport-filter)) (not (str/blank? team-filter)))
                             "No fixtures match the current filters."
                             "No fixtures published yet.")])]
                       [:section
                        [:h2.text-base.font-semibold.mb-3 "Participating schools"]
                        (if (seq participants)
                          [:ul.flex.flex-col.gap-2.list-none.p-0.m-0
                           (for [p participants]
                             [:li.bg-base-100.border.border-base-300.rounded-lg.p-3
                              [:strong (:participant/name p)]
                              (when (:participant/contact-email p)
                                [:span.block.text-sm.text-gray-500
                                 (:participant/contact-email p)])])]
                          [:p.text-gray-500.text-sm "No schools listed yet."])]
                       [:p.mt-8.text-xs.text-gray-400.text-center "Powered by Sports Manager"])))

(defn spectator-fixture
  "Public live score detail page for a single fixture."
  [fixture]
  (let [fmt (java.text.SimpleDateFormat. "d MMM HH:mm")
        now (java.util.Date.)
        fid (str (:fixture/id fixture))
        score (:fixture/live-score fixture)
        final-status (:fixture/final-score-status fixture)
        a-name (get-in fixture [:fixture/team-a :participant/name] "Team A")
        b-name (get-in fixture [:fixture/team-b :participant/name] "Team B")
        sport (get-in fixture [:fixture/sport-template :sport-template/name])
        end (:fixture/end-at fixture)
        start (:fixture/start-at fixture)
        status-label (cond
                       (= :final-score.status/accepted final-status) "Final"
                       (= :final-score.status/disputed final-status) "Disputed"
                       (= :final-score.status/pending final-status) "Pending"
                       (and end (.before end now)) "Ended"
                       (and start (.before start now)) "Live"
                       :else "Upcoming")
        is-live? (and (nil? final-status)
                      start (.before start now)
                      (or (nil? end) (.after end now)))]
    (shared/doc-public (str a-name " vs " b-name " — SchoolScore")
                       [:div.max-w-lg.mx-auto
                        [:p.text-sm.text-gray-500.mb-2
                         sport
                         (when-let [v (:fixture/venue fixture)] (str " · " v))
                         (when start (str " · " (.format fmt start)))]
                        [:div#fixture-status.badge.badge-outline.mb-5 status-label]
                        [:div#score-display.flex.items-center.justify-center.gap-6.my-6
                         [:div.flex-1.text-center
                          [:div.text-sm.text-gray-500.mb-1 a-name]
                          [:div#score-a.text-5xl.font-bold.leading-none (or (:a score) 0)]]
                         [:div.text-2xl.text-gray-400 "–"]
                         [:div.flex-1.text-center
                          [:div.text-sm.text-gray-500.mb-1 b-name]
                          [:div#score-b.text-5xl.font-bold.leading-none (or (:b score) 0)]]]
                        (when is-live?
                          [:p#last-updated.text-xs.text-gray-500.text-center.m-0
                           "Updating live…"])
                        [:p.mt-8.text-xs.text-gray-400.text-center "Powered by Sports Manager"]]
                       (when is-live?
                         [:script {:src "/js/spectator-fixture.js" :type "module"
                                   :data-fixture-id fid}]))))
