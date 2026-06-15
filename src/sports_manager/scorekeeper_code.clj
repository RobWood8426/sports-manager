(ns sports-manager.scorekeeper-code
  "Secure per-game scoring codes (SPO-42).

  A plaintext code is generated, shown once to the admin, then discarded.
  Only the SHA-256 hex digest is stored. Verification hashes the candidate
  and compares digests — constant-time via MessageDigest.isEqual."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [sports-manager.db :as db])
  (:import java.security.MessageDigest
           java.security.SecureRandom
           java.util.Date
           java.util.UUID))

;; ---------------------------------------------------------------------------
;; Rate limiting (in-memory, per IP, resets on restart)
;; ---------------------------------------------------------------------------

(def ^:private failed-attempts
  "Map of IP → {:count n :window-start inst}"
  (atom {}))

(def ^:private max-attempts 5)
(def ^:private window-ms (* 15 60 1000))

(defn- record-failure! [ip]
  (swap! failed-attempts
         (fn [m]
           (let [now (System/currentTimeMillis)
                 entry (get m ip {:count 0 :window-start now})
                 entry (if (> (- now (:window-start entry)) window-ms)
                         {:count 1 :window-start now}
                         (update entry :count inc))]
             (assoc m ip entry)))))

(defn- clear-failures! [ip]
  (swap! failed-attempts dissoc ip))

(defn rate-limited?
  "Returns true if this IP has exceeded max-attempts within the window."
  [ip]
  (let [now (System/currentTimeMillis)
        {:keys [count window-start]} (get @failed-attempts ip {:count 0 :window-start now})]
    (and (>= count max-attempts)
         (<= (- now window-start) window-ms))))

;; ---------------------------------------------------------------------------
;; Crypto helpers
;; ---------------------------------------------------------------------------

(defn- random-code
  "Generate a cryptographically random alphanumeric code of `len` chars."
  [len]
  (let [alphabet "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        rng (SecureRandom.)
        sb (StringBuilder.)]
    (dotimes [_ len]
      (.append sb (.charAt alphabet (mod (Math/abs (.nextInt rng)) (count alphabet)))))
    (str sb)))

(defn- sha256-hex [s]
  (let [md (MessageDigest/getInstance "SHA-256")
        bytes (.digest md (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn- constant-time-equal? [a b]
  (MessageDigest/isEqual (.getBytes a "UTF-8") (.getBytes b "UTF-8")))

;; ---------------------------------------------------------------------------
;; Pull pattern
;; ---------------------------------------------------------------------------

(def ^:private pull-pattern
  [:scode/id :scode/status :scode/game-status :scode/created-at :scode/created-by :scode/expires-at
   {:scode/fixture [:fixture/id :fixture/match-number]}])

;; ---------------------------------------------------------------------------
;; Query
;; ---------------------------------------------------------------------------

(defn find-by-id
  "Pull a scorekeeper code by UUID, or nil."
  [code-id]
  (let [e (db/pull pull-pattern [:scode/id code-id])]
    (when (:scode/id e) e)))

(defn list-by-fixture
  "Return all scorekeeper codes for a fixture, sorted by created-at desc."
  [fixture-id]
  (let [eids (d/q '[:find [?c ...]
                    :in $ ?fid
                    :where
                    [?f :fixture/id ?fid]
                    [?c :scode/fixture ?f]]
                  (db/db) fixture-id)]
    (->> eids
         (mapv #(db/pull pull-pattern %))
         (filter :scode/id)
         (sort-by :scode/created-at #(compare %2 %1)))))

;; ---------------------------------------------------------------------------
;; Mutation
;; ---------------------------------------------------------------------------

(defn generate!
  "Generate a new active scoring code for a fixture. Returns a map of
  {:code plaintext-code :entity scode-entity-map}.
  The plaintext is returned exactly once and never stored."
  [fixture-id actor-uid]
  (let [db (db/db)
        fix-eid (d/q '[:find ?f . :in $ ?fid :where [?f :fixture/id ?fid]] db fixture-id)
        _ (when-not fix-eid
            (throw (ex-info "Fixture not found" {:fixture/id fixture-id})))
        tenant-eid (d/q '[:find ?t . :in $ ?f :where [?f :fixture/tenant ?t]] db fix-eid)
        plaintext (random-code 8)
        code-hash (sha256-hex plaintext)
        code-id (UUID/randomUUID)
        now (Date.)
        tx-data [{:scode/id code-id
                  :scode/fixture fix-eid
                  :scode/code-hash code-hash
                  :scode/status :scode.status/active
                  :scode/tenant tenant-eid
                  :scode/created-at now
                  :scode/created-by actor-uid}
                 {:audit/id (UUID/randomUUID)
                  :audit/action :scode/generated
                  :audit/entity-type :scode
                  :audit/entity-id code-id
                  :audit/actor actor-uid
                  :audit/at now}]]
    (log/info "Generating scorekeeper code for fixture" fixture-id "by" actor-uid)
    (db/transact! tx-data)
    {:code plaintext
     :entity (find-by-id code-id)}))

(defn verify-code
  "Check a plaintext candidate against active codes for a fixture.
  Returns the matching scode entity on success, nil on failure.
  `ip` is used for rate limiting — pass the remote IP string."
  [fixture-id candidate ip]
  (when (rate-limited? ip)
    (throw (ex-info "Too many failed attempts" {:type :rate-limited})))
  (let [now (Date.)
        eids (d/q '[:find [?c ...]
                    :in $ ?fid
                    :where
                    [?f :fixture/id ?fid]
                    [?c :scode/fixture ?f]
                    [?c :scode/status :scode.status/active]]
                  (db/db) fixture-id)
        active (mapv #(db/pull [:scode/id :scode/code-hash :scode/expires-at] %) eids)
        candidate-hash (sha256-hex candidate)
        match (first (filter (fn [e]
                               (and (constant-time-equal? (:scode/code-hash e) candidate-hash)
                                    (or (nil? (:scode/expires-at e))
                                        (.after (:scode/expires-at e) now))))
                             active))]
    (if match
      (do
        (clear-failures! ip)
        (find-by-id (:scode/id match)))
      (do
        (record-failure! ip)
        nil))))

(defn revoke!
  "Revoke an active scorekeeper code. Returns the updated entity."
  [code-id actor-uid]
  (let [existing (find-by-id code-id)]
    (when-not existing
      (throw (ex-info "Code not found" {:scode/id code-id})))
    (when (= :scode.status/revoked (:scode/status existing))
      (throw (ex-info "Code already revoked" {:scode/id code-id})))
    (let [now (Date.)]
      (log/info "Revoking scorekeeper code" code-id "by" actor-uid)
      (db/transact! [{:db/id [:scode/id code-id]
                      :scode/status :scode.status/revoked}
                     {:audit/id (UUID/randomUUID)
                      :audit/action :scode/revoked
                      :audit/entity-type :scode
                      :audit/entity-id code-id
                      :audit/actor actor-uid
                      :audit/at now}]))
    (find-by-id code-id)))

(defn regenerate!
  "Revoke the most recent active code for a fixture and generate a fresh one.
  Returns {:code plaintext :entity new-scode-entity}."
  [fixture-id actor-uid]
  (let [active (filter #(= :scode.status/active (:scode/status %))
                       (list-by-fixture fixture-id))]
    (doseq [c active]
      (revoke! (:scode/id c) actor-uid)))
  (generate! fixture-id actor-uid))

;; ---------------------------------------------------------------------------
;; Scorekeeper assignment (SPO-43)
;; ---------------------------------------------------------------------------

(def ^:private assignment-pull-pattern
  [:assignment/id :assignment/label :assignment/created-at
   {:assignment/scode [:scode/id :scode/status :scode/expires-at]}
   {:assignment/fixture [:fixture/id :fixture/match-number]}])

(defn assign!
  "Create a labelled scorekeeper assignment for a fixture. Generates a fresh
  code and links it to the given label. Returns the assignment entity map.
  `label` is a human-readable string, e.g. \"Scorekeeper 1\"."
  [fixture-id label actor-uid]
  (let [db (db/db)
        fix-eid (d/q '[:find ?f . :in $ ?fid :where [?f :fixture/id ?fid]] db fixture-id)
        _ (when-not fix-eid
            (throw (ex-info "Fixture not found" {:fixture/id fixture-id})))
        tenant-eid (d/q '[:find ?t . :in $ ?f :where [?f :fixture/tenant ?t]] db fix-eid)
        {:keys [entity]} (generate! fixture-id actor-uid)
        scode-eid (d/q '[:find ?c . :in $ ?cid :where [?c :scode/id ?cid]]
                       (db/db) (:scode/id entity))
        assignment-id (UUID/randomUUID)
        now (Date.)]
    (log/info "Assigning scorekeeper" label "to fixture" fixture-id "by" actor-uid)
    (db/transact! [{:assignment/id assignment-id
                    :assignment/fixture fix-eid
                    :assignment/scode scode-eid
                    :assignment/label label
                    :assignment/tenant tenant-eid
                    :assignment/created-at now
                    :assignment/created-by actor-uid}])
    (let [e (db/pull assignment-pull-pattern [:assignment/id assignment-id])]
      (when (:assignment/id e) e))))

(defn list-assignments-by-fixture
  "Return all assignments for a fixture, sorted by created-at ascending."
  [fixture-id]
  (let [eids (d/q '[:find [?a ...]
                    :in $ ?fid
                    :where
                    [?f :fixture/id ?fid]
                    [?a :assignment/fixture ?f]]
                  (db/db) fixture-id)]
    (->> eids
         (mapv #(db/pull assignment-pull-pattern %))
         (filter :assignment/id)
         (sort-by :assignment/created-at))))

(defn find-fixture-by-code
  "Look up the fixture for a valid (active, non-expired) scode id.
  Returns the fixture entity map or nil."
  [scode-id]
  (when-let [sc (find-by-id scode-id)]
    (when (= :scode.status/active (:scode/status sc))
      (let [now (Date.)]
        (when (or (nil? (:scode/expires-at sc))
                  (.after (:scode/expires-at sc) now))
          (let [fix-eid (d/q '[:find ?f . :in $ ?cid
                               :where [?c :scode/id ?cid] [?c :scode/fixture ?f]]
                             (db/db) scode-id)]
            (when fix-eid
              (db/pull [:fixture/id :fixture/match-number :fixture/age-group :fixture/venue
                        :fixture/start-at :fixture/end-at :fixture/status
                        {:fixture/sport-template [:sport-template/name
                                                  :sport-template/scoring-increments
                                                  :sport-template/period-labels]}
                        {:fixture/team-a [:participant/name]}
                        {:fixture/team-b [:participant/name]}]
                       fix-eid))))))))

(defn find-active-by-plaintext
  "Look up an active, non-expired scode entity by its plaintext code string.
  Returns the scode entity map or nil. Records a failed attempt against `ip`
  if no match is found; clears failures on success."
  [plaintext ip]
  (when (rate-limited? ip)
    (throw (ex-info "Too many failed attempts" {:type :rate-limited})))
  (let [candidate-hash (sha256-hex plaintext)
        now (Date.)
        eids (d/q '[:find [?c ...]
                    :in $ ?h
                    :where
                    [?c :scode/code-hash ?h]
                    [?c :scode/status :scode.status/active]]
                  (db/db) candidate-hash)
        active (mapv #(db/pull [:scode/id :scode/code-hash :scode/expires-at] %) eids)
        match (first (filter (fn [e]
                               (or (nil? (:scode/expires-at e))
                                   (.after (:scode/expires-at e) now)))
                             active))]
    (if match
      (do (clear-failures! ip) (find-by-id (:scode/id match)))
      (do (record-failure! ip) nil))))

;; ---------------------------------------------------------------------------
;; Game-status lifecycle (SPO-44)
;; ---------------------------------------------------------------------------

(defn- advance-game-status!
  "Internal helper: assert a new game-status on an scode and write an audit entry."
  [code-id new-status audit-action actor-uid reason]
  (let [existing (find-by-id code-id)]
    (when-not existing
      (throw (ex-info "Code not found" {:scode/id code-id})))
    (when-not (= :scode.status/active (:scode/status existing))
      (throw (ex-info "Code is not active" {:scode/id code-id
                                            :scode/status (:scode/status existing)})))
    (let [now (Date.)
          audit (cond-> {:audit/id (UUID/randomUUID)
                         :audit/action audit-action
                         :audit/entity-type :scode
                         :audit/entity-id code-id
                         :audit/actor (or actor-uid "scorekeeper")
                         :audit/at now}
                  reason (assoc :audit/reason reason))]
      (db/transact! [{:db/id [:scode/id code-id]
                      :scode/game-status new-status}
                     audit])))
  (find-by-id code-id))

(defn mark-accessed!
  "Record that the scorekeeper has accessed the code (first page load on /live).
  No actor-uid required — this is an anonymous scorekeeper action."
  [code-id]
  (advance-game-status! code-id :scode.game-status/accessed :scode/accessed nil nil))

(defn start-game!
  "Advance game-status to :started when the scorekeeper explicitly begins scoring."
  [code-id actor-uid]
  (advance-game-status! code-id :scode.game-status/started :scode/started actor-uid nil))

(defn go-live!
  "Advance game-status to :live once the first score entry is made."
  [code-id actor-uid]
  (advance-game-status! code-id :scode.game-status/live :scode/live actor-uid nil))

(defn submit-final!
  "Mark final score as submitted by the scorekeeper; awaiting admin acceptance."
  [code-id actor-uid]
  (advance-game-status! code-id :scode.game-status/final-submitted :scode/final-submitted actor-uid nil))

(defn pend-final!
  "Move to final-pending when awaiting a second scorekeeper or admin review."
  [code-id actor-uid]
  (advance-game-status! code-id :scode.game-status/final-pending :scode/final-pending actor-uid nil))

(defn accept-final!
  "Admin accepts the final score submission."
  [code-id actor-uid]
  (advance-game-status! code-id :scode.game-status/final-accepted :scode/final-accepted actor-uid nil))

(defn dispute-final!
  "Admin disputes the final score. `reason` is required."
  [code-id actor-uid reason]
  (when (str/blank? reason)
    (throw (ex-info "Dispute reason is required" {:scode/id code-id})))
  (advance-game-status! code-id :scode.game-status/final-disputed :scode/final-disputed actor-uid reason))

(defn expire-codes!
  "Set status to :expired for all active codes on a fixture whose expires-at has passed.
  Safe to call repeatedly (idempotent on already-expired codes)."
  [fixture-id]
  (let [now (Date.)
        eids (d/q '[:find [?c ...]
                    :in $ ?fid
                    :where
                    [?f :fixture/id ?fid]
                    [?c :scode/fixture ?f]
                    [?c :scode/status :scode.status/active]]
                  (db/db) fixture-id)
        expired (filter (fn [eid]
                          (let [e (db/pull [:scode/id :scode/expires-at] eid)]
                            (when-let [exp (:scode/expires-at e)]
                              (.after now exp))))
                        eids)]
    (when (seq expired)
      (let [tx-data (mapcat (fn [eid]
                              (let [sc (db/pull [:scode/id] eid)]
                                [{:db/id eid :scode/status :scode.status/expired}
                                 {:audit/id (UUID/randomUUID)
                                  :audit/action :scode/expired
                                  :audit/entity-type :scode
                                  :audit/entity-id (:scode/id sc)
                                  :audit/actor "system"
                                  :audit/at now}]))
                            expired)]
        (log/info "Expiring" (count expired) "code(s) for fixture" fixture-id)
        (db/transact! (vec tx-data))))
    (count expired)))
