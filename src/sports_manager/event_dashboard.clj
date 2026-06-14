(ns sports-manager.event-dashboard
  "Operational dashboard data for an event (SPO-58).

  Classifies every fixture into one of five buckets based on its scoring state:
    :live        — scoring in progress, no accepted final score yet
    :completed   — accepted final score recorded
    :disputed    — final score status is disputed
    :pending     — final score submitted but pending admin confirmation
    :no-activity — published fixture with no active scorekeeper code or scoring

  Also surfaces sync conflicts (multiple scorers detected)."
  (:require [sports-manager.final-score :as final-score]
            [sports-manager.fixture :as fixture]
            [sports-manager.score :as score]
            [sports-manager.scorekeeper-code :as scode]))

;; ---------------------------------------------------------------------------
;; Per-fixture classification
;; ---------------------------------------------------------------------------

(defn- classify-fixture
  "Return a map enriching a fixture with dashboard metadata:
    :dashboard/bucket    — keyword bucket (see ns docstring)
    :dashboard/score     — {:a n :b n} or nil
    :dashboard/conflict? — true if multiple scorers detected
    :dashboard/codes     — seq of scode entities for this fixture"
  [f]
  (let [fid (:fixture/id f)
        codes (scode/list-by-fixture fid)
        finals (final-score/find-by-fixture fid)
        sync (score/fixture-sync-status fid)
        score (when (pos? (:event-count sync)) (:score sync))
        accepted-final (first (filter #(= :final-score.status/accepted
                                          (:final-score/status %)) finals))
        disputed-final (first (filter #(= :final-score.status/disputed
                                          (:final-score/status %)) finals))
        pending-final (first (filter #(= :final-score.status/pending
                                         (:final-score/status %)) finals))
        active-codes (filter #(= :scode.status/active (:scode/status %)) codes)
        scoring-codes (filter #(#{:scode.game-status/started
                                  :scode.game-status/live
                                  :scode.game-status/final-submitted}
                                (:scode/game-status %)) codes)
        bucket (cond
                 accepted-final :completed
                 disputed-final :disputed
                 pending-final :pending
                 (seq scoring-codes) :live
                 :else :no-activity)]
    (assoc f
           :dashboard/bucket bucket
           :dashboard/score score
           :dashboard/conflict? (:conflict? sync)
           :dashboard/codes codes
           :dashboard/active-codes (count active-codes))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn dashboard-data
  "Return the full operational dashboard map for an event.
  `event-id` is a UUID.

  Returns:
  {:fixtures    — seq of enriched fixture maps (all statuses)
   :by-bucket   — map of bucket keyword → seq of fixtures
   :counts      — map of bucket keyword → count, plus :total
   :conflicts   — seq of fixtures with :dashboard/conflict? true}"
  [event-id]
  (let [all-fixtures (fixture/list-by-event event-id)
        enriched (mapv classify-fixture all-fixtures)
        by-bucket (group-by :dashboard/bucket enriched)
        buckets [:live :completed :disputed :pending :no-activity]
        counts (into {:total (count enriched)}
                     (map (fn [b] [b (count (get by-bucket b []))]))
                     buckets)
        conflicts (filter :dashboard/conflict? enriched)]
    {:fixtures enriched
     :by-bucket by-bucket
     :counts counts
     :conflicts conflicts}))
