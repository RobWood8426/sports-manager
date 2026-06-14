(ns sports-manager.final-score
  "Final score submission and validation for a fixture (SPO-48).

  Validation models:
    :validation.model/single    — one scorekeeper; accepted immediately.
    :validation.model/dual      — two scorekeepers must agree; pending until
                                  second submission matches.
    :validation.model/consensus — (future) admin confirmation required.

  `submit!` is idempotent per scode: calling it twice with the same scode-id
  returns the existing entity rather than creating a duplicate."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [sports-manager.db :as db]
            [sports-manager.score :as score]
            [sports-manager.scorekeeper-code :as scode])
  (:import java.util.Date
           java.util.UUID))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(def ^:private pull-pattern
  [:final-score/id
   :final-score/team-a-score
   :final-score/team-b-score
   :final-score/status
   :final-score/validation-model
   :final-score/submitted-at
   {:final-score/fixture [:fixture/id]}
   {:final-score/scode [:scode/id]}])

(defn- fixture-eid [db fixture-id]
  (ffirst (d/q '[:find ?e :in $ ?id :where [?e :fixture/id ?id]] db fixture-id)))

(defn- scode-eid [db scode-id]
  (ffirst (d/q '[:find ?e :in $ ?id :where [?e :scode/id ?id]] db scode-id)))

(defn- tenant-eid [db fixture-id]
  (ffirst (d/q '[:find ?t :in $ ?fid :where [?e :fixture/id ?fid] [?e :fixture/tenant ?t]]
               db fixture-id)))

(defn- find-existing [fixture-id scode-id]
  (let [db (db/db)]
    (when-let [eid (ffirst
                    (d/q '[:find ?e :in $ ?fid ?sid
                           :where [?e :final-score/fixture ?f] [?f :fixture/id ?fid]
                           [?e :final-score/scode ?s] [?s :scode/id ?sid]]
                         db fixture-id scode-id))]
      (d/pull db pull-pattern eid))))

(defn- effective-validation-model [fixture-id]
  (let [db (db/db)
        result (ffirst
                (d/q '[:find ?vm
                       :in $ ?fid
                       :where [?fix :fixture/id ?fid]
                       [?fix :fixture/event ?ev]
                       [?esc :event-sport/event ?ev]
                       [?esc :event-sport/validation-model ?vm]]
                     db fixture-id))]
    (or result :validation.model/single)))

(defn- find-accepted-or-pending-by-fixture [fixture-id]
  (let [db (db/db)]
    (d/q '[:find [(pull ?e [:final-score/id
                            :final-score/team-a-score
                            :final-score/team-b-score
                            :final-score/status
                            {:final-score/scode [:scode/id]}]) ...]
           :in $ ?fid
           :where [?e :final-score/fixture ?f]
           [?f :fixture/id ?fid]]
         db fixture-id)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn submit!
  "Record a final score submission from a scorekeeper.

  Derives the current score from the event log, then applies the validation model:
    :validation.model/single         — accepted immediately
    :validation.model/single-pending — pending admin approval (one scorekeeper)
    :validation.model/dual           — pending until a second scorekeeper submits;
                                       match → both accepted, mismatch → both disputed
    :validation.model/admin-approval — always pending admin approval
    anything else                    — treated as :single

  Returns the final-score entity map."
  [fixture-id scode-id actor-uid]
  (if-let [existing (find-existing fixture-id scode-id)]
    existing
    (let [db (db/db)
          fix-e (fixture-eid db fixture-id)
          _ (when-not fix-e
              (throw (ex-info "Fixture not found" {:fixture-id fixture-id})))
          scode-e (scode-eid db scode-id)
          _ (when-not scode-e
              (throw (ex-info "Scorekeeper code not found" {:scode-id scode-id})))
          tenant-e (tenant-eid db fixture-id)
          {:keys [a b]} (score/current-score fixture-id)
          model (effective-validation-model fixture-id)
          status (case model
                   :validation.model/single :final-score.status/accepted
                   :validation.model/single-pending :final-score.status/pending
                   :validation.model/dual :final-score.status/pending
                   :validation.model/admin-approval :final-score.status/pending
                   :final-score.status/accepted)
          now (Date.)
          fs-id (UUID/randomUUID)
          fs-tmp (str "final-score-" fs-id)
          tx [{:db/id fs-tmp
               :final-score/id fs-id
               :final-score/fixture fix-e
               :final-score/scode scode-e
               :final-score/team-a-score a
               :final-score/team-b-score b
               :final-score/status status
               :final-score/validation-model model
               :final-score/submitted-at now
               :final-score/tenant tenant-e}
              {:audit/id (UUID/randomUUID)
               :audit/action :final-score/submitted
               :audit/actor (or actor-uid "")
               :audit/entity-type :final-score
               :audit/entity-id fs-id
               :audit/at now}]
          result (db/transact! tx)
          db' (:db-after result)
          new-eid (get (:tempids result) fs-tmp)]
      (log/info "Final score submitted" {:fixture-id fixture-id :scode-id scode-id
                                         :model model :status status})
      (if (= status :final-score.status/accepted)
        (scode/accept-final! scode-id actor-uid)
        (scode/submit-final! scode-id actor-uid))
      (when (= model :validation.model/dual)
        (let [new-fs (d/pull db' pull-pattern new-eid)
              submissions (find-accepted-or-pending-by-fixture fixture-id)
              other (first (remove #(= scode-id (get-in % [:final-score/scode :scode/id]))
                                   submissions))]
          (when other
            (let [scores-match? (and (= (:final-score/team-a-score other) a)
                                     (= (:final-score/team-b-score other) b))
                  other-scode (get-in other [:final-score/scode :scode/id])
                  audit-now (Date.)]
              (if scores-match?
                (do
                  (db/transact! [{:db/id [:final-score/id (:final-score/id new-fs)]
                                  :final-score/status :final-score.status/accepted}
                                 {:db/id [:final-score/id (:final-score/id other)]
                                  :final-score/status :final-score.status/accepted}
                                 {:audit/id (UUID/randomUUID)
                                  :audit/action :final-score/dual-accepted
                                  :audit/actor (or actor-uid "")
                                  :audit/entity-type :final-score
                                  :audit/entity-id (:final-score/id new-fs)
                                  :audit/at audit-now}])
                  (scode/accept-final! scode-id actor-uid)
                  (scode/accept-final! other-scode actor-uid))
                (do
                  (db/transact! [{:db/id [:final-score/id (:final-score/id new-fs)]
                                  :final-score/status :final-score.status/disputed}
                                 {:db/id [:final-score/id (:final-score/id other)]
                                  :final-score/status :final-score.status/disputed}
                                 {:audit/id (UUID/randomUUID)
                                  :audit/action :final-score/dual-disputed
                                  :audit/actor (or actor-uid "")
                                  :audit/entity-type :final-score
                                  :audit/entity-id (:final-score/id new-fs)
                                  :audit/at audit-now}])
                  (log/warn "Dual-scorekeeper mismatch" {:fixture-id fixture-id
                                                         :a-score a :b-score b
                                                         :other-a (:final-score/team-a-score other)
                                                         :other-b (:final-score/team-b-score other)})
                  (scode/dispute-final! scode-id actor-uid "Score mismatch between scorekeepers")
                  (scode/dispute-final! other-scode actor-uid "Score mismatch between scorekeepers")))))))
      (d/pull (db/db) pull-pattern new-eid))))

(defn find-by-fixture
  "Return all final-score submissions for a fixture, sorted by submitted-at."
  [fixture-id]
  (let [db (db/db)
        eids (d/q '[:find [?e ...]
                    :in $ ?fid
                    :where [?e :final-score/fixture ?f]
                    [?f :fixture/id ?fid]]
                  db fixture-id)]
    (->> eids
         (map #(d/pull db pull-pattern %))
         (sort-by :final-score/submitted-at))))

(defn find-by-scode
  "Return the final-score submission for a specific scorekeeper code, or nil."
  [scode-id]
  (let [db (db/db)]
    (when-let [eid (ffirst
                    (d/q '[:find ?e :in $ ?sid
                           :where [?e :final-score/scode ?s]
                           [?s :scode/id ?sid]]
                         db scode-id))]
      (d/pull db pull-pattern eid))))

(defn compare-submissions
  "Compare the two most recent final-score submissions for a fixture.
  Returns a map:
    {:submissions [s1 s2]   — sorted by submitted-at
     :match?      bool      — true when both scores agree
     :status      keyword   — :no-submissions | :one-pending | :match | :mismatch | :accepted | :disputed}
  Works for any number of submissions; comparison is always first two."
  [fixture-id]
  (let [subs (find-by-fixture fixture-id)]
    (cond
      (empty? subs)
      {:submissions [] :match? false :status :no-submissions}

      (= 1 (count subs))
      {:submissions subs :match? false :status :one-pending}

      :else
      (let [[s1 s2] subs
            match? (and (= (:final-score/team-a-score s1) (:final-score/team-a-score s2))
                        (= (:final-score/team-b-score s1) (:final-score/team-b-score s2)))
            any-disputed? (some #(= :final-score.status/disputed (:final-score/status %)) subs)
            any-accepted? (some #(= :final-score.status/accepted (:final-score/status %)) subs)
            status (cond
                     any-disputed? :disputed
                     any-accepted? :accepted
                     match? :match
                     :else :mismatch)]
        {:submissions subs :match? match? :status status}))))

(defn list-disputed-by-tenant
  "Return all disputed final-score submissions for a tenant, sorted by submitted-at desc."
  [tenant-id]
  (let [db (db/db)
        fixture-join {:final-score/fixture [:fixture/id
                                            :fixture/match-number
                                            {:fixture/sport-template [:sport-template/name]}
                                            {:fixture/team-a [:participant/name]}
                                            {:fixture/team-b [:participant/name]}
                                            {:fixture/event [:event/id :event/name]}]}
        rich-pattern (-> pull-pattern
                         (vec)
                         (->> (remove #(and (map? %) (contains? % :final-score/fixture))))
                         (conj fixture-join)
                         (vec))
        eids (d/q '[:find [?e ...]
                    :in $ ?tid
                    :where [?e :final-score/status :final-score.status/disputed]
                    [?e :final-score/tenant ?t]
                    [?t :tenant/id ?tid]]
                  db tenant-id)]
    (->> eids
         (map #(d/pull db rich-pattern %))
         (sort-by :final-score/submitted-at #(compare %2 %1)))))

(defn resolve-dispute!
  "Admin overrides a disputed fixture with a confirmed final score.
  Marks all disputed submissions for the fixture as :accepted, writes the
  admin-decided scores onto the accepted entity, and records an audit entry
  with a mandatory reason.

  `confirmed-a` and `confirmed-b` are the admin-decided integer scores.
  `reason` is required and must be non-blank."
  [fixture-id actor-uid confirmed-a confirmed-b reason]
  (when (str/blank? reason)
    (throw (ex-info "Reason is required for dispute resolution"
                    {:type :validation-error :fixture-id fixture-id})))
  (let [disputed (filter #(= :final-score.status/disputed (:final-score/status %))
                         (find-by-fixture fixture-id))
        _ (when (empty? disputed)
            (throw (ex-info "No disputed submissions found for fixture"
                            {:fixture-id fixture-id})))
        now (Date.)
        first-id (:final-score/id (first disputed))
        tx (into
            [{:audit/id (UUID/randomUUID)
              :audit/action :final-score/dispute-resolved
              :audit/actor (or actor-uid "")
              :audit/entity-type :final-score
              :audit/entity-id first-id
              :audit/reason reason
              :audit/at now}]
            (map-indexed
             (fn [i sub]
               (cond-> {:db/id [:final-score/id (:final-score/id sub)]
                        :final-score/status :final-score.status/accepted}
                 (zero? i) (assoc :final-score/team-a-score confirmed-a
                                  :final-score/team-b-score confirmed-b)))
             disputed))]
    (db/transact! tx)
    (log/info "Dispute resolved" {:fixture-id fixture-id :actor actor-uid
                                  :a confirmed-a :b confirmed-b})
    (find-by-fixture fixture-id)))