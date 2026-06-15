(ns sports-manager.score
  "Append-only score-event log for live games (SPO-45/47).
  Current score is derived by summing deltas -- never mutated.
  SPO-47: client-id deduplication, conflict detection, sync-status query."
  (:require [clojure.tools.logging :as log]
            [sports-manager.db :as db])
  (:import java.util.Date
           java.util.UUID))

;; ---------------------------------------------------------------------------
;; Pull / query helpers
;; ---------------------------------------------------------------------------

(def ^:private pull-pattern
  [:score-event/id :score-event/client-id :score-event/client-ts
   :score-event/team :score-event/delta :score-event/conflict
   :score-event/period :score-event/recorded-at
   :score-event/scode])

;; ---------------------------------------------------------------------------
;; Mutation
;; ---------------------------------------------------------------------------

(defn record-event!
  "Append a score delta for team :a or :b. `delta` must be a non-zero integer.
  `period` is an optional string label (e.g. \"Half 1\"); pass nil for sports
  without periods. `client-id` is an optional client-generated UUID string for
  idempotent deduplication -- if already stored, returns the existing entity.
  `client-ts` is an optional java.util.Date from the device clock.
  Returns the score-event entity map."
  [fixture-id scode-id team delta period & [{:keys [client-id client-ts]}]]
  {:pre [(#{:a :b} team) (integer? delta) (not (zero? delta))]}
  (let [existing-id (when client-id
                      (ffirst (db/q '{:find  [?id]
                                      :in    [?cid]
                                      :where [[?e :score-event/client-id ?cid]
                                              [?e :score-event/id ?id]]}
                                    client-id)))]
    (if existing-id
      (do
        (log/debug "Duplicate score event" client-id "-- returning existing")
        (db/pull pull-pattern existing-id))
      (let [fixture (db/entity fixture-id)
            _ (when-not fixture
                (throw (ex-info "Fixture not found" {:fixture/id fixture-id})))
            _ (when-not (db/exists? scode-id)
                (throw (ex-info "Scorekeeper code not found" {:scode/id scode-id})))
            tenant-id (:fixture/tenant fixture)
            team-kw (if (= team :a) :score-event.team/a :score-event.team/b)
            event-id (UUID/randomUUID)
            now (Date.)
            doc (cond-> {:xt/id event-id
                         :score-event/id event-id
                         :score-event/scode scode-id
                         :score-event/fixture fixture-id
                         :score-event/team team-kw
                         :score-event/delta delta
                         :score-event/recorded-at now
                         :score-event/tenant tenant-id}
                  client-id (assoc :score-event/client-id client-id)
                  client-ts (assoc :score-event/client-ts client-ts)
                  (some? period) (assoc :score-event/period period))]
        (log/debug "Score event" team delta "for fixture" fixture-id)
        (db/put! doc)
        (db/pull pull-pattern event-id)))))

;; ---------------------------------------------------------------------------
;; Query
;; ---------------------------------------------------------------------------

(defn score-history
  "Return all score events for a fixture, sorted by recorded-at ascending."
  [fixture-id]
  (let [ids (map first (db/q '{:find  [?id]
                               :in    [?fid]
                               :where [[?e :score-event/fixture ?fid]
                                       [?e :score-event/id ?id]]}
                              fixture-id))]
    (->> ids
         (mapv #(db/pull pull-pattern %))
         (filter :score-event/id)
         (sort-by :score-event/recorded-at))))

(defn current-score
  "Derive the current score for a fixture by summing all deltas.
  Returns {:a n :b n}."
  [fixture-id]
  (reduce (fn [acc e]
            (let [team (if (= :score-event.team/a (:score-event/team e)) :a :b)]
              (update acc team + (:score-event/delta e))))
          {:a 0 :b 0}
          (score-history fixture-id)))

;; ---------------------------------------------------------------------------
;; Conflict detection (SPO-47)
;; ---------------------------------------------------------------------------

(def ^:private conflict-window-ms (* 10 1000))

(defn detect-conflicts!
  "Scan recent events for a fixture and flag conflicts: two different scodes
  submitting events within conflict-window-ms of each other. Marks conflicting
  events with :score-event/conflict true. Returns count of newly flagged events."
  [fixture-id]
  (let [history (score-history fixture-id)
        now-ms (System/currentTimeMillis)
        recent (filter (fn [e]
                         (when-let [^Date ra (:score-event/recorded-at e)]
                           (< (- now-ms (.getTime ra)) conflict-window-ms)))
                       history)
        scode-ids (->> recent
                       (map :score-event/scode)
                       (remove nil?)
                       distinct)
        conflict? (> (count scode-ids) 1)
        to-flag (when conflict?
                  (filter (fn [e]
                            (and (not (:score-event/conflict e))
                                 (:score-event/scode e)))
                          recent))]
    (when (seq to-flag)
      (log/info "Flagging" (count to-flag) "conflicting score events for fixture" fixture-id)
      (doseq [e to-flag]
        (db/merge! (:score-event/id e) {:score-event/conflict true})))
    (count (or to-flag []))))

;; ---------------------------------------------------------------------------
;; Sync status (SPO-47)
;; ---------------------------------------------------------------------------

(defn fixture-sync-status
  "Return a summary map for a fixture's scoring state, suitable for polling.
  {:score {:a n :b n} :conflict? bool :event-count n :last-recorded-at inst-or-nil}"
  [fixture-id]
  (let [history (score-history fixture-id)
        cs (reduce (fn [acc e]
                     (let [team (if (= :score-event.team/a (:score-event/team e)) :a :b)]
                       (update acc team + (:score-event/delta e))))
                   {:a 0 :b 0}
                   history)
        conflict? (boolean (some :score-event/conflict history))
        last-at (when (seq history)
                  (:score-event/recorded-at (last history)))]
    {:score cs
     :conflict? conflict?
     :event-count (count history)
     :last-recorded-at last-at}))
