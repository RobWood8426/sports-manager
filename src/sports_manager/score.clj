(ns sports-manager.score
  "Append-only score-event log for live games (SPO-45/47).
  Current score is derived by summing deltas — never mutated.
  SPO-47: client-id deduplication, conflict detection, sync-status query."
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d]
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
   {:score-event/scode [:scode/id]}])

(defn- fix-eid [db fixture-id]
  (d/q '[:find ?f . :in $ ?fid :where [?f :fixture/id ?fid]] db fixture-id))

(defn- scode-eid [db scode-id]
  (d/q '[:find ?c . :in $ ?cid :where [?c :scode/id ?cid]] db scode-id))

;; ---------------------------------------------------------------------------
;; Mutation
;; ---------------------------------------------------------------------------

(defn record-event!
  "Append a score delta for team :a or :b. `delta` must be a non-zero integer.
  `period` is an optional string label (e.g. \"Half 1\"); pass nil for sports
  without periods. `client-id` is an optional client-generated UUID string for
  idempotent deduplication — if already stored, returns the existing entity.
  `client-ts` is an optional java.util.Date from the device clock.
  Returns the score-event entity map."
  [fixture-id scode-id team delta period & [{:keys [client-id client-ts]}]]
  {:pre [(#{:a :b} team) (integer? delta) (not (zero? delta))]}
  (let [db (db/db)
        ;; Deduplication: if client-id already stored, return existing entity
        existing-eid (when client-id
                       (d/q '[:find ?e . :in $ ?cid
                              :where [?e :score-event/client-id ?cid]]
                            db client-id))
        _ (when existing-eid
            (log/debug "Duplicate score event" client-id "— returning existing"))]
    (if existing-eid
      (db/pull pull-pattern existing-eid)
      (let [feid (fix-eid db fixture-id)
            _ (when-not feid
                (throw (ex-info "Fixture not found" {:fixture/id fixture-id})))
            sceid (scode-eid db scode-id)
            _ (when-not sceid
                (throw (ex-info "Scorekeeper code not found" {:scode/id scode-id})))
            tenant-eid (d/q '[:find ?t . :in $ ?f :where [?f :fixture/tenant ?t]] db feid)
            team-kw (if (= team :a) :score-event.team/a :score-event.team/b)
            event-id (UUID/randomUUID)
            now (Date.)
            tx-data (cond-> {:score-event/id event-id
                             :score-event/scode sceid
                             :score-event/fixture feid
                             :score-event/team team-kw
                             :score-event/delta delta
                             :score-event/recorded-at now
                             :score-event/tenant tenant-eid}
                      client-id (assoc :score-event/client-id client-id)
                      client-ts (assoc :score-event/client-ts client-ts)
                      (some? period) (assoc :score-event/period period))]
        (log/debug "Score event" team delta "for fixture" fixture-id)
        (db/transact! [tx-data])
        (db/pull pull-pattern [:score-event/id event-id])))))

;; ---------------------------------------------------------------------------
;; Query
;; ---------------------------------------------------------------------------

(defn score-history
  "Return all score events for a fixture, sorted by recorded-at ascending."
  [fixture-id]
  (let [eids (d/q '[:find [?e ...]
                    :in $ ?fid
                    :where
                    [?f :fixture/id ?fid]
                    [?e :score-event/fixture ?f]]
                  (db/db) fixture-id)]
    (->> eids
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
  submitting events within `conflict-window-ms` of each other. Marks conflicting
  events with :score-event/conflict true. Returns count of newly flagged events."
  [fixture-id]
  (let [history (score-history fixture-id)
        now-ms (System/currentTimeMillis)
        recent (filter (fn [e]
                         (when-let [^Date ra (:score-event/recorded-at e)]
                           (< (- now-ms (.getTime ra)) conflict-window-ms)))
                       history)
        ;; Group by scode/id — collect unique scode ids in the recent window
        scode-ids (->> recent
                       (map #(get-in % [:score-event/scode :scode/id]))
                       (remove nil?)
                       distinct)
        conflict? (> (count scode-ids) 1)
        to-flag (when conflict?
                  (filter (fn [e]
                            (and (not (:score-event/conflict e))
                                 (get-in e [:score-event/scode :scode/id])))
                          recent))]
    (when (seq to-flag)
      (let [tx-data (mapv (fn [e]
                            {:db/id [:score-event/id (:score-event/id e)]
                             :score-event/conflict true})
                          to-flag)]
        (log/info "Flagging" (count to-flag) "conflicting score events for fixture" fixture-id)
        (db/transact! tx-data)))
    (count (or to-flag []))))

;; ---------------------------------------------------------------------------
;; Sync status (SPO-47)
;; ---------------------------------------------------------------------------

(defn fixture-sync-status
  "Return a summary map for a fixture's scoring state, suitable for polling.
  {:score {:a n :b n}
   :conflict? bool
   :event-count n
   :last-recorded-at inst-or-nil}"
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
