(ns sports-manager.final-score-test
  "Tests for final score submission and validation (SPO-48/49/50/51)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [sports-manager.db :as db]
            [sports-manager.event :as event]
            [sports-manager.event-sport :as event-sport]
            [sports-manager.final-score :as fs]
            [sports-manager.fixture :as fixture]
            [sports-manager.participant :as participant]
            [sports-manager.score :as score]
            [sports-manager.scorekeeper-code :as scode]
            [sports-manager.sport-template :as st]
            [sports-manager.test-db :as test-db])
  (:import java.util.UUID))

(use-fixtures :each test-db/with-db)

(defn- seed!
  "Creates tenant, event (rugby, single validation model by default), fixture, and one scode."
  []
  (st/seed-templates!)
  (let [tid (UUID/randomUUID)
        uid "actor"]
    (db/put-many! [{:xt/id tid :tenant/id tid :tenant/name "Final School"
                    :tenant/status :active}
                   {:xt/id uid :user/firebase-uid uid :user/email "f@x.com"
                    :user/status :active}])
    (let [ev (event/create! tid uid
                            {:event/name "Final Day"
                             :event/start-at #inst "2026-09-01T08:00"
                             :event/end-at #inst "2026-09-01T18:00"
                             :event/visibility :event.visibility/public
                             :event/access-method :event.access/public-link}
                            #{:sport/rugby})
          event-id (:event/id ev)
          pa (participant/add-to-event! event-id uid
                                        {:participant/name "A" :participant/contact-email "a@a.za"
                                         :participant/contact-phone ""} nil)
          pb (participant/add-to-event! event-id uid
                                        {:participant/name "B" :participant/contact-email "b@b.za"
                                         :participant/contact-phone ""} nil)
          f (fixture/create! event-id uid
                             {:fixture/sport-code :sport/rugby
                              :fixture/team-a-id (:participant/id pa)
                              :fixture/team-b-id (:participant/id pb)
                              :fixture/start-at #inst "2026-09-01T09:00"
                              :fixture/end-at #inst "2026-09-01T10:00"})
          {:keys [entity]} (scode/generate! (:fixture/id f) uid)]
      {:fixture-id (:fixture/id f)
       :scode-id (:scode/id entity)
       :event-id event-id
       :uid uid})))

(deftest submit-returns-entity
  (let [{:keys [fixture-id scode-id uid]} (seed!)
        result (fs/submit! fixture-id scode-id uid)]
    (is (uuid? (:final-score/id result)))
    (is (= fixture-id (get-in result [:final-score/fixture :fixture/id])))
    (is (= scode-id (get-in result [:final-score/scode :scode/id])))))

(deftest submit-single-model-accepted-immediately
  (let [{:keys [fixture-id scode-id uid]} (seed!)
        result (fs/submit! fixture-id scode-id uid)]
    (is (= :final-score.status/accepted (:final-score/status result)))))

(deftest submit-captures-current-score
  (let [{:keys [fixture-id scode-id uid]} (seed!)]
    (score/record-event! fixture-id scode-id :a 3 nil)
    (score/record-event! fixture-id scode-id :b 1 nil)
    (let [result (fs/submit! fixture-id scode-id uid)]
      (is (= 3 (:final-score/team-a-score result)))
      (is (= 1 (:final-score/team-b-score result))))))

(deftest submit-zero-scores-when-no-events
  (let [{:keys [fixture-id scode-id uid]} (seed!)
        result (fs/submit! fixture-id scode-id uid)]
    (is (= 0 (:final-score/team-a-score result)))
    (is (= 0 (:final-score/team-b-score result)))))

(deftest submit-idempotent-per-scode
  (let [{:keys [fixture-id scode-id uid]} (seed!)
        first-result (fs/submit! fixture-id scode-id uid)
        second-result (fs/submit! fixture-id scode-id uid)]
    (is (= (:final-score/id first-result) (:final-score/id second-result)))))

(deftest submit-unknown-fixture-throws
  (let [{:keys [scode-id uid]} (seed!)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Fixture not found"
                          (fs/submit! (UUID/randomUUID) scode-id uid)))))

(deftest submit-unknown-scode-throws
  (let [{:keys [fixture-id uid]} (seed!)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Scorekeeper code not found"
                          (fs/submit! fixture-id (UUID/randomUUID) uid)))))

(deftest find-by-fixture-returns-submissions
  (let [{:keys [fixture-id scode-id uid]} (seed!)
        _ (fs/submit! fixture-id scode-id uid)
        result (fs/find-by-fixture fixture-id)]
    (is (= 1 (count result)))
    (is (= fixture-id (get-in (first result) [:final-score/fixture :fixture/id])))))

(deftest find-by-fixture-empty-for-new-fixture
  (let [{:keys [fixture-id]} (seed!)]
    (is (empty? (fs/find-by-fixture fixture-id)))))

(deftest find-by-scode-returns-submission
  (let [{:keys [fixture-id scode-id uid]} (seed!)
        _ (fs/submit! fixture-id scode-id uid)
        result (fs/find-by-scode scode-id)]
    (is (some? result))
    (is (= scode-id (get-in result [:final-score/scode :scode/id])))))

(deftest find-by-scode-nil-before-submission
  (let [{:keys [scode-id]} (seed!)]
    (is (nil? (fs/find-by-scode scode-id)))))

;; ---------------------------------------------------------------------------
;; SPO-50 — dual-model comparison tests
;; ---------------------------------------------------------------------------

(defn- seed-dual!
  "Like seed! but sets validation model to :dual and generates two scodes.
  Returns :tid so dispute-resolution tests can call list-disputed-by-tenant."
  []
  (st/seed-templates!)
  (let [tid (UUID/randomUUID)
        uid "actor"]
    (db/put-many! [{:xt/id tid :tenant/id tid :tenant/name "Dual School"
                    :tenant/status :active}
                   {:xt/id uid :user/firebase-uid uid :user/email "d@x.com"
                    :user/status :active}])
    (let [ev (event/create! tid uid
                            {:event/name "Dual Day"
                             :event/start-at #inst "2026-09-01T08:00"
                             :event/end-at #inst "2026-09-01T18:00"
                             :event/visibility :event.visibility/public
                             :event/access-method :event.access/public-link}
                            #{:sport/rugby})
          event-id (:event/id ev)
          _ (event-sport/configure! event-id :sport/rugby
                                    {:event-sport/validation-model :validation.model/dual}
                                    uid)
          pa (participant/add-to-event! event-id uid
                                        {:participant/name "A" :participant/contact-email "a@a.za"
                                         :participant/contact-phone ""} nil)
          pb (participant/add-to-event! event-id uid
                                        {:participant/name "B" :participant/contact-email "b@b.za"
                                         :participant/contact-phone ""} nil)
          f (fixture/create! event-id uid
                             {:fixture/sport-code :sport/rugby
                              :fixture/team-a-id (:participant/id pa)
                              :fixture/team-b-id (:participant/id pb)
                              :fixture/start-at #inst "2026-09-01T09:00"
                              :fixture/end-at #inst "2026-09-01T10:00"})
          fix-id (:fixture/id f)
          {:keys [entity]} (scode/generate! fix-id uid)
          sc1 (:scode/id entity)
          {:keys [entity]} (scode/generate! fix-id uid)
          sc2 (:scode/id entity)]
      {:fixture-id fix-id :scode1 sc1 :scode2 sc2 :uid uid :tid tid})))

(deftest dual-match-both-accepted
  (let [{:keys [fixture-id scode1 scode2 uid]} (seed-dual!)]
    (score/record-event! fixture-id scode1 :a 3 nil)
    (score/record-event! fixture-id scode1 :b 1 nil)
    (score/record-event! fixture-id scode2 :a 3 nil)
    (score/record-event! fixture-id scode2 :b 1 nil)
    (fs/submit! fixture-id scode1 uid)
    (fs/submit! fixture-id scode2 uid)
    (let [subs (fs/find-by-fixture fixture-id)]
      (is (every? #(= :final-score.status/accepted (:final-score/status %)) subs)))))

(deftest dual-mismatch-both-disputed
  ;; scode1 submits with score 3-0, then more events happen,
  ;; scode2 submits with score 5-0. The two submissions disagree.
  (let [{:keys [fixture-id scode1 scode2 uid]} (seed-dual!)]
    (score/record-event! fixture-id scode1 :a 3 nil)
    (fs/submit! fixture-id scode1 uid)
    ;; More events recorded after scode1 submitted
    (score/record-event! fixture-id scode2 :a 2 nil)
    (fs/submit! fixture-id scode2 uid)
    (let [subs (fs/find-by-fixture fixture-id)]
      (is (every? #(= :final-score.status/disputed (:final-score/status %)) subs)))))

(deftest compare-no-submissions
  (let [{:keys [fixture-id]} (seed-dual!)
        result (fs/compare-submissions fixture-id)]
    (is (= :no-submissions (:status result)))
    (is (empty? (:submissions result)))))

(deftest compare-one-pending
  (let [{:keys [fixture-id scode1 uid]} (seed-dual!)
        _ (fs/submit! fixture-id scode1 uid)
        result (fs/compare-submissions fixture-id)]
    (is (= :one-pending (:status result)))
    (is (= 1 (count (:submissions result))))))

(deftest compare-match-status
  (let [{:keys [fixture-id scode1 scode2 uid]} (seed-dual!)]
    (score/record-event! fixture-id scode1 :a 2 nil)
    (score/record-event! fixture-id scode2 :a 2 nil)
    (fs/submit! fixture-id scode1 uid)
    (fs/submit! fixture-id scode2 uid)
    (let [result (fs/compare-submissions fixture-id)]
      (is (true? (:match? result)))
      (is (= :accepted (:status result))))))

(deftest compare-mismatch-status
  (let [{:keys [fixture-id scode1 scode2 uid]} (seed-dual!)]
    (score/record-event! fixture-id scode1 :a 2 nil)
    (fs/submit! fixture-id scode1 uid)
    ;; Additional event shifts the score seen by scode2
    (score/record-event! fixture-id scode2 :a 2 nil)
    (fs/submit! fixture-id scode2 uid)
    (let [result (fs/compare-submissions fixture-id)]
      (is (false? (:match? result)))
      (is (= :disputed (:status result))))))

;; ---------------------------------------------------------------------------
;; SPO-51 — dispute resolution tests
;; ---------------------------------------------------------------------------

(deftest resolve-dispute-blank-reason-throws
  (let [{:keys [fixture-id scode1 scode2 uid]} (seed-dual!)]
    (score/record-event! fixture-id scode1 :a 2 nil)
    (fs/submit! fixture-id scode1 uid)
    (score/record-event! fixture-id scode2 :a 2 nil)
    (fs/submit! fixture-id scode2 uid)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Reason is required"
                          (fs/resolve-dispute! fixture-id uid 2 0 "")))))

(deftest resolve-dispute-no-disputed-throws
  (let [{:keys [fixture-id uid]} (seed-dual!)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No disputed submissions"
                          (fs/resolve-dispute! fixture-id uid 1 0 "Admin override")))))

(deftest resolve-dispute-marks-all-accepted
  (let [{:keys [fixture-id scode1 scode2 uid]} (seed-dual!)]
    (score/record-event! fixture-id scode1 :a 2 nil)
    (fs/submit! fixture-id scode1 uid)
    (score/record-event! fixture-id scode2 :a 2 nil)
    (fs/submit! fixture-id scode2 uid)
    (fs/resolve-dispute! fixture-id uid 3 1 "Verified by admin")
    (let [subs (fs/find-by-fixture fixture-id)]
      (is (every? #(= :final-score.status/accepted (:final-score/status %)) subs)))))

(deftest resolve-dispute-writes-confirmed-scores-on-first-submission
  (let [{:keys [fixture-id scode1 scode2 uid]} (seed-dual!)]
    (score/record-event! fixture-id scode1 :a 2 nil)
    (fs/submit! fixture-id scode1 uid)
    (score/record-event! fixture-id scode2 :a 2 nil)
    (fs/submit! fixture-id scode2 uid)
    (fs/resolve-dispute! fixture-id uid 5 2 "Verified by admin")
    (let [subs (fs/find-by-fixture fixture-id)
          first-sub (first subs)]
      (is (= 5 (:final-score/team-a-score first-sub)))
      (is (= 2 (:final-score/team-b-score first-sub))))))

(deftest resolve-dispute-compare-shows-accepted
  (let [{:keys [fixture-id scode1 scode2 uid]} (seed-dual!)]
    (score/record-event! fixture-id scode1 :a 2 nil)
    (fs/submit! fixture-id scode1 uid)
    (score/record-event! fixture-id scode2 :a 2 nil)
    (fs/submit! fixture-id scode2 uid)
    (fs/resolve-dispute! fixture-id uid 4 0 "Verified by admin")
    (let [result (fs/compare-submissions fixture-id)]
      (is (= :accepted (:status result))))))

(deftest list-disputed-by-tenant-returns-disputed
  (let [{:keys [fixture-id scode1 scode2 uid tid]} (seed-dual!)]
    (score/record-event! fixture-id scode1 :a 2 nil)
    (fs/submit! fixture-id scode1 uid)
    (score/record-event! fixture-id scode2 :a 2 nil)
    (fs/submit! fixture-id scode2 uid)
    (let [disputed (fs/list-disputed-by-tenant tid)]
      (is (= 2 (count disputed)))
      (is (every? #(= :final-score.status/disputed (:final-score/status %)) disputed)))))

(deftest list-disputed-by-tenant-empty-when-none
  (let [{:keys [tid]} (seed-dual!)]
    (is (empty? (fs/list-disputed-by-tenant tid)))))

(deftest list-disputed-by-tenant-excludes-other-tenant
  (let [{:keys [fixture-id scode1 scode2 uid]} (seed-dual!)
        other-tid (UUID/randomUUID)]
    (score/record-event! fixture-id scode1 :a 2 nil)
    (fs/submit! fixture-id scode1 uid)
    (score/record-event! fixture-id scode2 :a 2 nil)
    (fs/submit! fixture-id scode2 uid)
    (is (empty? (fs/list-disputed-by-tenant other-tid)))))