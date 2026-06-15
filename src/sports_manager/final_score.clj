(ns sports-manager.final-score
  "Final score submission and validation for a fixture (SPO-48).

  Validation models:
    :validation.model/single    -- one scorekeeper; accepted immediately.
    :validation.model/dual      -- two scorekeepers must agree; pending until
                                  second submission matches.
    :validation.model/consensus -- (future) admin confirmation required.

  `submit!` is idempotent per scode: calling it twice with the same scode-id
  returns the existing entity rather than creating a duplicate."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [sports-manager.db :as db]
            [sports-manager.score :as score]
            [sports-manager.scorekeeper-code :as scode]
            [xtdb.api :as xt])
  (:import java.util.Date
           java.util.UUID))

;; ---------------------------------------------------------------------------
;; Pull pattern
;; ---------------------------------------------------------------------------

(def ^:private pull-pattern
  [:final-score/id
   :final-score/team-a-score
   :final-score/team-b-score
   :final-score/status
   :final-score/validation-model
   :final-score/submitted-at
   :final-score/tenant
   {:final-score/fixture [:fixture/id :fixture/match-number :fixture/event :fixture/tenant]}
   {:final-score/scode [:scode/id :scode/status :scode/game-status]}])

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- find-existing [fixture-id scode-id]
  (when-let [fs-id (ffirst
                    (db/q '{:find [?fid]
                            :in [?fixid ?scid]
                            :where [[?e :final-score/fixture ?fixid]
                                    [?e :final-score/scode ?scid]
                                    [?e :final-score/id ?fid]]}
                          fixture-id scode-id))]
    (db/pull pull-pattern fs-id)))

(defn- effective-validation-model [fixture-id]
  (or (ffirst (db/q '{:find [?vm]
                      :in [?fid]
                      :where [[?fix :fixture/id ?fid]
                              [?fix :fixture/event ?ev]
                              [?fix :fixture/sport-template ?scode]
                              [?esc :event-sport/event ?ev]
                              [?esc :event-sport/sport-template ?scode]
                              [?esc :event-sport/validation-model ?vm]]}
                    fixture-id))
      :validation.model/single))

(defn- find-pending-by-fixture [fixture-id]
  (let [ids (map first (db/q '{:find [?fsid]
                               :in [?fid]
                               :where [[?e :final-score/fixture ?fid]
                                       [?e :final-score/id ?fsid]]}
                             fixture-id))]
    (->> ids
         (mapv #(db/pull pull-pattern %))
         (filter :final-score/id))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn submit!
  "Record a final score submission from a scorekeeper.
  Returns the final-score entity map."
  [fixture-id scode-id actor-uid]
  (if-let [existing (find-existing fixture-id scode-id)]
    existing
    (let [fixture (db/entity fixture-id)
          _ (when-not fixture
              (throw (ex-info "Fixture not found" {:fixture-id fixture-id})))
          _ (when-not (db/exists? scode-id)
              (throw (ex-info "Scorekeeper code not found" {:scode-id scode-id})))
          tenant-id (:fixture/tenant fixture)
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
          audit-id (UUID/randomUUID)]
      (log/info "Final score submitted" {:fixture-id fixture-id :scode-id scode-id
                                         :model model :status status})
      (db/submit! [[::xt/put {:xt/id fs-id
                              :final-score/id fs-id
                              :final-score/fixture fixture-id
                              :final-score/scode scode-id
                              :final-score/team-a-score a
                              :final-score/team-b-score b
                              :final-score/status status
                              :final-score/validation-model model
                              :final-score/submitted-at now
                              :final-score/tenant tenant-id}]
                   [::xt/put {:xt/id audit-id
                              :audit/id audit-id
                              :audit/action :final-score/submitted
                              :audit/actor (or actor-uid "")
                              :audit/entity-type :final-score
                              :audit/entity-id fs-id
                              :audit/tenant tenant-id
                              :audit/at now}]])
      (if (= status :final-score.status/accepted)
        (scode/accept-final! scode-id actor-uid)
        (scode/submit-final! scode-id actor-uid))
      (when (= model :validation.model/dual)
        (let [new-fs-raw (db/entity fs-id)
              submissions (find-pending-by-fixture fixture-id)
              other-pulled (first (remove #(= scode-id (get-in % [:final-score/scode :scode/id])) submissions))]
          (when other-pulled
            (let [other-id (:final-score/id other-pulled)
                  other-raw (db/entity other-id)
                  scores-match? (and (= (:final-score/team-a-score other-pulled) a)
                                     (= (:final-score/team-b-score other-pulled) b))
                  other-scode (:final-score/scode other-raw)
                  audit-now (Date.)
                  audit2-id (UUID/randomUUID)]
              (if scores-match?
                (do
                  (db/submit! [[::xt/put (assoc new-fs-raw :final-score/status :final-score.status/accepted)]
                               [::xt/put (assoc other-raw :final-score/status :final-score.status/accepted)]
                               [::xt/put {:xt/id audit2-id
                                          :audit/id audit2-id
                                          :audit/action :final-score/dual-accepted
                                          :audit/actor (or actor-uid "")
                                          :audit/entity-type :final-score
                                          :audit/entity-id fs-id
                                          :audit/tenant tenant-id
                                          :audit/at audit-now}]])
                  (scode/accept-final! scode-id actor-uid)
                  (scode/accept-final! other-scode actor-uid))
                (do
                  (db/submit! [[::xt/put (assoc new-fs-raw :final-score/status :final-score.status/disputed)]
                               [::xt/put (assoc other-raw :final-score/status :final-score.status/disputed)]
                               [::xt/put {:xt/id audit2-id
                                          :audit/id audit2-id
                                          :audit/action :final-score/dual-disputed
                                          :audit/actor (or actor-uid "")
                                          :audit/entity-type :final-score
                                          :audit/entity-id fs-id
                                          :audit/tenant tenant-id
                                          :audit/at audit-now}]])
                  (log/warn "Dual-scorekeeper mismatch" {:fixture-id fixture-id
                                                         :a-score a :b-score b
                                                         :other-a (:final-score/team-a-score other-pulled)
                                                         :other-b (:final-score/team-b-score other-pulled)})
                  (scode/dispute-final! scode-id actor-uid "Score mismatch between scorekeepers")
                  (scode/dispute-final! other-scode actor-uid "Score mismatch between scorekeepers")))))))
      (db/pull pull-pattern fs-id))))

(defn find-by-fixture
  "Return all final-score submissions for a fixture, sorted by submitted-at."
  [fixture-id]
  (let [ids (map first (db/q '{:find [?fsid]
                               :in [?fid]
                               :where [[?e :final-score/fixture ?fid]
                                       [?e :final-score/id ?fsid]]}
                             fixture-id))]
    (->> ids
         (mapv #(db/pull pull-pattern %))
         (filter :final-score/id)
         (sort-by :final-score/submitted-at))))

(defn find-by-scode
  "Return the final-score submission for a specific scorekeeper code, or nil."
  [scode-id]
  (when-let [fs-id (ffirst (db/q '{:find [?fsid]
                                   :in [?scid]
                                   :where [[?e :final-score/scode ?scid]
                                           [?e :final-score/id ?fsid]]}
                                 scode-id))]
    (db/pull pull-pattern fs-id)))

(defn compare-submissions
  "Compare final-score submissions for a fixture.
  Returns {:submissions [...] :match? bool :status keyword}."
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
  (let [ids (map first (db/q '{:find [?fsid]
                               :in [?tid]
                               :where [[?e :final-score/status :final-score.status/disputed]
                                       [?e :final-score/tenant ?tid]
                                       [?e :final-score/id ?fsid]]}
                             tenant-id))]
    (->> ids
         (mapv #(db/pull pull-pattern %))
         (filter :final-score/id)
         (sort-by :final-score/submitted-at #(compare %2 %1)))))

(defn resolve-dispute!
  "Admin overrides a disputed fixture with a confirmed final score."
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
        audit-id (UUID/randomUUID)
        tenant-id (:final-score/tenant (first disputed))
        updates (map-indexed
                 (fn [i sub]
                   (let [raw (db/entity (:final-score/id sub))]
                     [::xt/put (cond-> (assoc raw :final-score/status :final-score.status/accepted)
                                 (zero? i) (assoc :final-score/team-a-score confirmed-a
                                                  :final-score/team-b-score confirmed-b))]))
                 disputed)
        audit [::xt/put {:xt/id audit-id
                         :audit/id audit-id
                         :audit/action :final-score/dispute-resolved
                         :audit/actor (or actor-uid "")
                         :audit/entity-type :final-score
                         :audit/entity-id first-id
                         :audit/reason reason
                         :audit/tenant tenant-id
                         :audit/at now}]]
    (db/submit! (into (vec updates) [audit]))
    (log/info "Dispute resolved" {:fixture-id fixture-id :actor actor-uid
                                  :a confirmed-a :b confirmed-b})
    (find-by-fixture fixture-id)))
