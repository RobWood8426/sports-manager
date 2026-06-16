(ns sports-manager.scorekeeper-code
  "Secure per-game scoring codes (SPO-42).

  A plaintext code is generated, shown once to the admin, then discarded.
  Only the SHA-256 hex digest is stored. Verification hashes the candidate
  and compares digests -- constant-time via MessageDigest.isEqual."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [sports-manager.db :as db]
            [xtdb.api :as xt])
  (:import java.security.MessageDigest
           java.security.SecureRandom
           java.util.Date
           java.util.UUID))

;; ---------------------------------------------------------------------------
;; Rate limiting (in-memory, per IP, resets on restart)
;; ---------------------------------------------------------------------------

(def ^:private failed-attempts (atom {}))
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

(defn rate-limited? [ip]
  (let [now (System/currentTimeMillis)
        {:keys [count window-start]} (get @failed-attempts ip {:count 0 :window-start now})]
    (and (>= count max-attempts)
         (<= (- now window-start) window-ms))))

;; ---------------------------------------------------------------------------
;; Crypto helpers
;; ---------------------------------------------------------------------------

(defn- random-code [len]
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
  [:scode/id :scode/status :scode/game-status :scode/created-at
   :scode/created-by :scode/expires-at :scode/tenant
   {:scode/fixture [:fixture/id :fixture/match-number :fixture/event]}])

;; ---------------------------------------------------------------------------
;; Query
;; ---------------------------------------------------------------------------

(defn find-by-id [code-id]
  (db/pull pull-pattern code-id))

(defn list-by-fixture [fixture-id]
  (let [ids (map first (db/q '{:find [?cid]
                               :in [?fid]
                               :where [[?c :scode/fixture ?fid]
                                       [?c :scode/id ?cid]]}
                             fixture-id))]
    (->> ids
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
  (let [fixture (db/entity fixture-id)
        _ (when-not fixture
            (throw (ex-info "Fixture not found" {:fixture/id fixture-id})))
        tenant-id (:fixture/tenant fixture)
        plaintext (random-code 8)
        code-hash (sha256-hex plaintext)
        code-id (UUID/randomUUID)
        now (Date.)
        audit-id (UUID/randomUUID)]
    (log/info "Generating scorekeeper code for fixture" fixture-id "by" actor-uid)
    (db/submit! [[::xt/put {:xt/id code-id
                            :scode/id code-id
                            :scode/fixture fixture-id
                            :scode/code-hash code-hash
                            :scode/status :scode.status/active
                            :scode/tenant tenant-id
                            :scode/created-at now
                            :scode/created-by actor-uid
                            :scode/expires-at (:fixture/end-at fixture)}]
                 [::xt/put {:xt/id audit-id
                            :audit/id audit-id
                            :audit/action :scode/generated
                            :audit/entity-type :scode
                            :audit/entity-id code-id
                            :audit/actor actor-uid
                            :audit/tenant tenant-id
                            :audit/at now}]])
    {:code plaintext
     :entity (find-by-id code-id)}))

(defn verify-code
  "Check a plaintext candidate against active codes for a fixture.
  Returns the matching scode entity on success, nil on failure."
  [fixture-id candidate ip]
  (when (rate-limited? ip)
    (throw (ex-info "Too many failed attempts" {:type :rate-limited})))
  (let [now (Date.)
        ids (map first (db/q '{:find [?cid]
                               :in [?fid]
                               :where [[?c :scode/fixture ?fid]
                                       [?c :scode/status :scode.status/active]
                                       [?c :scode/id ?cid]]}
                             fixture-id))
        active (mapv #(db/pull [:scode/id :scode/code-hash :scode/expires-at] %) ids)
        cand-hash (sha256-hex candidate)
        match (first (filter (fn [e]
                               (and (constant-time-equal? (:scode/code-hash e) cand-hash)
                                    (or (nil? (:scode/expires-at e))
                                        (.after (:scode/expires-at e) now))))
                             active))]
    (if match
      (do (clear-failures! ip) (find-by-id (:scode/id match)))
      (do (record-failure! ip) nil))))

(defn revoke!
  "Revoke an active scorekeeper code. Returns the updated entity."
  [code-id actor-uid]
  (let [check (find-by-id code-id)]
    (when-not check
      (throw (ex-info "Code not found" {:scode/id code-id})))
    (when (= :scode.status/revoked (:scode/status check))
      (throw (ex-info "Code already revoked" {:scode/id code-id})))
    (let [existing (db/entity code-id)
          now (Date.)
          audit-id (UUID/randomUUID)]
      (log/info "Revoking scorekeeper code" code-id "by" actor-uid)
      (db/submit! [[::xt/put (assoc existing :scode/status :scode.status/revoked)]
                   [::xt/put {:xt/id audit-id
                              :audit/id audit-id
                              :audit/action :scode/revoked
                              :audit/entity-type :scode
                              :audit/entity-id code-id
                              :audit/actor actor-uid
                              :audit/tenant (:scode/tenant existing)
                              :audit/at now}]]))
    (find-by-id code-id)))

(defn regenerate!
  "Revoke all active codes for a fixture and generate a fresh one."
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
   :assignment/fixture
   {:assignment/scode [:scode/id :scode/status :scode/game-status
                       :scode/created-at :scode/expires-at]}])

(defn assign!
  "Create a labelled scorekeeper assignment for a fixture. Generates a fresh
  code and links it to the given label. Returns the assignment entity map."
  [fixture-id label actor-uid]
  (let [fixture (db/entity fixture-id)
        _ (when-not fixture
            (throw (ex-info "Fixture not found" {:fixture/id fixture-id})))
        tenant-id (:fixture/tenant fixture)
        {:keys [entity]} (generate! fixture-id actor-uid)
        scode-id (:scode/id entity)
        assignment-id (UUID/randomUUID)
        now (Date.)]
    (log/info "Assigning scorekeeper" label "to fixture" fixture-id "by" actor-uid)
    (db/put! {:xt/id assignment-id
              :assignment/id assignment-id
              :assignment/fixture fixture-id
              :assignment/scode scode-id
              :assignment/label label
              :assignment/tenant tenant-id
              :assignment/created-at now
              :assignment/created-by actor-uid})
    (db/pull assignment-pull-pattern assignment-id)))

(defn list-assignments-by-fixture [fixture-id]
  (let [ids (map first (db/q '{:find [?aid]
                               :in [?fid]
                               :where [[?a :assignment/fixture ?fid]
                                       [?a :assignment/id ?aid]]}
                             fixture-id))]
    (->> ids
         (mapv #(db/pull assignment-pull-pattern %))
         (filter :assignment/id)
         (sort-by :assignment/created-at))))

(defn find-fixture-by-code
  "Look up the fixture for a valid (active, non-expired) scode id.
  Joins the sport-template and both team participants so callers can read
  nested names/period-labels (the confirm + live scoring views expect these)."
  [scode-id]
  (when-let [sc (find-by-id scode-id)]
    (when (= :scode.status/active (:scode/status sc))
      (let [now (Date.)]
        (when (or (nil? (:scode/expires-at sc))
                  (.after (:scode/expires-at sc) now))
          (db/pull [:fixture/id :fixture/match-number :fixture/age-group :fixture/venue
                    :fixture/start-at :fixture/end-at :fixture/status
                    {:fixture/sport-template [:sport-template/code :sport-template/name
                                              :sport-template/period-labels]}
                    {:fixture/team-a [:participant/id :participant/name]}
                    {:fixture/team-b [:participant/id :participant/name]}]
                   (get-in sc [:scode/fixture :fixture/id])))))))

(defn find-active-by-plaintext
  "Look up an active, non-expired scode entity by its plaintext code string."
  [plaintext ip]
  (when (rate-limited? ip)
    (throw (ex-info "Too many failed attempts" {:type :rate-limited})))
  (let [cand-hash (sha256-hex plaintext)
        now (Date.)
        ids (map first (db/q '{:find [?cid]
                               :in [?h]
                               :where [[?c :scode/code-hash ?h]
                                       [?c :scode/status :scode.status/active]
                                       [?c :scode/id ?cid]]}
                             cand-hash))
        active (mapv #(db/pull [:scode/id :scode/expires-at] %) ids)
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

(defn- advance-game-status! [code-id new-status audit-action actor-uid reason]
  (let [check (find-by-id code-id)]
    (when-not check
      (throw (ex-info "Code not found" {:scode/id code-id})))
    (when-not (= :scode.status/active (:scode/status check))
      (throw (ex-info "Code is not active" {:scode/id code-id
                                            :scode/status (:scode/status check)})))
    (let [existing (db/entity code-id)
          now (Date.)
          audit-id (UUID/randomUUID)
          audit (cond-> {:xt/id audit-id
                         :audit/id audit-id
                         :audit/action audit-action
                         :audit/entity-type :scode
                         :audit/entity-id code-id
                         :audit/actor (or actor-uid "scorekeeper")
                         :audit/tenant (:scode/tenant existing)
                         :audit/at now}
                  reason (assoc :audit/reason reason))]
      (db/submit! [[::xt/put (assoc existing :scode/game-status new-status)]
                   [::xt/put audit]])))
  (find-by-id code-id))

(defn mark-accessed! [code-id]
  (advance-game-status! code-id :scode.game-status/accessed :scode/accessed nil nil))

(defn start-game! [code-id actor-uid]
  (advance-game-status! code-id :scode.game-status/started :scode/started actor-uid nil))

(defn go-live! [code-id actor-uid]
  (advance-game-status! code-id :scode.game-status/live :scode/live actor-uid nil))

(defn submit-final! [code-id actor-uid]
  (advance-game-status! code-id :scode.game-status/final-submitted :scode/final-submitted actor-uid nil))

(defn pend-final! [code-id actor-uid]
  (advance-game-status! code-id :scode.game-status/final-pending :scode/final-pending actor-uid nil))

(defn accept-final! [code-id actor-uid]
  (advance-game-status! code-id :scode.game-status/final-accepted :scode/final-accepted actor-uid nil))

(defn dispute-final! [code-id actor-uid reason]
  (when (str/blank? reason)
    (throw (ex-info "Dispute reason is required" {:scode/id code-id})))
  (advance-game-status! code-id :scode.game-status/final-disputed :scode/final-disputed actor-uid reason))

(defn expire-codes!
  "Set status to :expired for all active codes on a fixture whose expires-at has passed."
  [fixture-id]
  (let [now (Date.)
        ids (map first (db/q '{:find [?cid]
                               :in [?fid]
                               :where [[?c :scode/fixture ?fid]
                                       [?c :scode/status :scode.status/active]
                                       [?c :scode/id ?cid]]}
                             fixture-id))
        docs (mapv #(db/pull [:scode/id :scode/expires-at] %) ids)
        expired (filter (fn [e]
                          (when-let [exp (:scode/expires-at e)]
                            (.after now exp)))
                        docs)]
    (when (seq expired)
      (log/info "Expiring" (count expired) "code(s) for fixture" fixture-id)
      (db/submit! (mapcat (fn [{:scode/keys [id]}]
                            (let [audit-id (UUID/randomUUID)
                                  doc (db/entity id)]
                              [[::xt/put (assoc doc :scode/status :scode.status/expired)]
                               [::xt/put {:xt/id audit-id
                                          :audit/id audit-id
                                          :audit/action :scode/expired
                                          :audit/entity-type :scode
                                          :audit/entity-id id
                                          :audit/actor "system"
                                          :audit/tenant (:scode/tenant doc)
                                          :audit/at now}]]))
                          expired)))
    (count expired)))
