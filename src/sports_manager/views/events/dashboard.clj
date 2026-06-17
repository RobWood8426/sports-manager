(ns sports-manager.views.events.dashboard
  "Operational scoring dashboard for an event."
  (:require [sports-manager.i18n :as i18n]
            [sports-manager.views.shared :as shared]))

(defn event-dashboard
  "Operational scoring dashboard for an event."
  [event dashboard & [{:keys [lang] :or {lang "en"}}]]
  (let [tr (fn [k] (i18n/t lang k))
        event-id (:event/id event)
        fmt (java.text.SimpleDateFormat. "HH:mm")
        {:keys [counts conflicts by-bucket]} dashboard
        buckets [[:live (tr :dashboard/live) "badge-success" "text-success" "border-success/30"]
                 [:disputed (tr :dashboard/disputed) "badge-error" "text-error" "border-error/30"]
                 [:pending (tr :dashboard/pending) "badge-warning" "text-warning" "border-warning/30"]
                 [:completed (tr :dashboard/completed) "badge-info" "text-info" "border-info/30"]
                 [:no-activity (tr :dashboard/no-activity) "badge-ghost" "" "border-base-300"]]
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
                      [:td (if score
                             [:span.ss-score.text-base-content (str (:a score) " – " (:b score))]
                             "—")]
                      [:td.opacity-60 (:dashboard/active-codes f)]
                      [:td (when (:dashboard/conflict? f)
                             [:span.badge.badge-sm.badge-warning (tr :dashboard/conflict)])]]))]
    (shared/doc (str (:event/name event) " — " (tr :dashboard/title-suffix))
                {:active :events :lang lang}
                [:div.flex.flex-wrap.items-center.justify-between.gap-y-3.mb-8.pb-6.border-b.border-base-300
                 [:nav.flex.flex-wrap.gap-2.text-sm.items-center
                  [:a.opacity-60.hover:opacity-100.transition-opacity {:href "/"} (tr :dashboard/home-link)]
                  [:span.opacity-30 "/"]
                  [:a.opacity-60.hover:opacity-100.transition-opacity {:href (str "/events/" event-id)} (:event/name event)]
                  [:span.opacity-30 "/"]
                  [:strong (tr :dashboard/title-suffix)]]
                 (shared/event-status-badge (:event/status event))]
                [:div.grid.gap-3.mb-8
                 {:class "grid-cols-[repeat(auto-fill,minmax(130px,1fr))]"}
                 (for [[bucket label _ num-cl bdr-cl] buckets]
                   [:div.ss-card.p-4.text-center
                    {:class bdr-cl}
                    [:div.ss-score.text-3xl
                     {:class num-cl}
                     (get counts bucket 0)]
                    [:div.ss-label.block.mt-1 label]])
                 [:div.ss-card.p-4.text-center
                  [:div.ss-score.text-3xl.text-base-content (:total counts 0)]
                  [:div.ss-label.block.mt-1 (tr :dashboard/total)]]]
                (when (seq conflicts)
                  [:div.alert.alert-warning.mb-6
                   [:span (str (count conflicts) " " (tr :dashboard/conflict-warning))]])
                (if (seq (:fixtures dashboard))
                  [:div.overflow-x-auto
                   [:table.table.table-zebra.w-full
                    [:thead
                     [:tr
                      [:th (tr :dashboard/h-status)] [:th (tr :dashboard/h-sport)]
                      [:th (tr :dashboard/h-team-a)] [:th (tr :dashboard/h-team-b)]
                      [:th (tr :dashboard/h-age-group)] [:th (tr :dashboard/h-venue)]
                      [:th (tr :dashboard/h-time)] [:th (tr :dashboard/h-score)]
                      [:th (tr :dashboard/h-codes)] [:th ""]]]
                    [:tbody
                     (for [[bucket _ _] buckets
                           f (get by-bucket bucket [])]
                       (dash-row f))]]]
                  [:p.opacity-50 (tr :dashboard/no-fixtures)]))))
