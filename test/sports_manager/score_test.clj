(ns sports-manager.score-test
  "Tests for the append-only score-event log (SPO-45) and sync engine (SPO-47)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [sports-manager.db :as db]
            [sports-manager.event :as event]
            [sports-manager.fixture :as fixture]
            [sports-manager.membership :as membership]
            [sports-manager.participant :as participant]
            [sports-manager.score :as score]
            [sports-manager.scorekeeper-code :as scode]
            [sports-manager.sport-template :as st]
            [sports-manager.test-db :as test-db])
  (:import java.util.UUID))

(use-fixtures :each test-db/with-db)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- seed! []
  (st/seed-templates!)
  (let [tid (UUID/randomUUID)
        uid "actor"]
    (db/put-many! [{:xt/id tid :tenant/id tid :tenant/name "Score School"
                   :tenant/status :active}
                  {:xt/id uid :user/firebase-uid uid :user/email "s@x.com"
                   :user/status :active}])
    (membership/create! uid tid)
    (let [ev (event/create! tid uid
                            {:event/name "Score Day"
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
       :uid uid})))

;; ---------------------------------------------------------------------------
;; record-event!
;; ---------------------------------------------------------------------------

(deftest record-event-returns-entity
  (testing "record-event! persists a score delta and returns the entity"
    (let [{:keys [fixture-id scode-id]} (seed!)
          e (score/record-event! fixture-id scode-id :a 5 nil)]
      (is (uuid? (:score-event/id e)))
      (is (= :score-event.team/a (:score-event/team e)))
      (is (= 5 (:score-event/delta e))))))

(deftest record-event-stores-period
  (testing "record-event! stores the period label when provided"
    (let [{:keys [fixture-id scode-id]} (seed!)
          e (score/record-event! fixture-id scode-id :b 3 "Half 1")]
      (is (= "Half 1" (:score-event/period e))))))

(deftest record-event-unknown-fixture-throws
  (testing "record-event! throws when fixture not found"
    (let [{:keys [scode-id]} (seed!)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Fixture not found"
                            (score/record-event! (UUID/randomUUID) scode-id :a 1 nil))))))

(deftest record-event-unknown-scode-throws
  (testing "record-event! throws when scode not found"
    (let [{:keys [fixture-id]} (seed!)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Scorekeeper code not found"
                            (score/record-event! fixture-id (UUID/randomUUID) :a 1 nil))))))

;; ---------------------------------------------------------------------------
;; score-history
;; ---------------------------------------------------------------------------

(deftest score-history-returns-events-sorted
  (testing "score-history returns events in recorded-at order"
    (let [{:keys [fixture-id scode-id]} (seed!)
          _ (score/record-event! fixture-id scode-id :a 5 nil)
          _ (score/record-event! fixture-id scode-id :b 3 nil)
          history (score/score-history fixture-id)]
      (is (= 2 (count history)))
      (is (= :score-event.team/a (:score-event/team (first history))))
      (is (= :score-event.team/b (:score-event/team (second history)))))))

(deftest score-history-empty-for-new-fixture
  (testing "score-history is empty before any events"
    (let [{:keys [fixture-id]} (seed!)]
      (is (empty? (score/score-history fixture-id))))))

;; ---------------------------------------------------------------------------
;; current-score
;; ---------------------------------------------------------------------------

(deftest current-score-sums-deltas
  (testing "current-score correctly sums positive deltas"
    (let [{:keys [fixture-id scode-id]} (seed!)
          _ (score/record-event! fixture-id scode-id :a 5 nil)
          _ (score/record-event! fixture-id scode-id :a 3 nil)
          _ (score/record-event! fixture-id scode-id :b 7 nil)]
      (is (= {:a 8 :b 7} (score/current-score fixture-id))))))

(deftest current-score-handles-corrections
  (testing "current-score handles negative deltas (corrections)"
    (let [{:keys [fixture-id scode-id]} (seed!)
          _ (score/record-event! fixture-id scode-id :a 5 nil)
          _ (score/record-event! fixture-id scode-id :a -5 nil)]
      (is (= {:a 0 :b 0} (score/current-score fixture-id))))))

(deftest current-score-zero-for-new-fixture
  (testing "current-score is {:a 0 :b 0} before any events"
    (let [{:keys [fixture-id]} (seed!)]
      (is (= {:a 0 :b 0} (score/current-score fixture-id))))))

;; ---------------------------------------------------------------------------
;; Deduplication via client-id (SPO-47)
;; ---------------------------------------------------------------------------

(deftest record-event-deduplicates-by-client-id
  (testing "record-event! with same client-id returns existing entity without double-counting"
    (let [{:keys [fixture-id scode-id]} (seed!)
          client-id (str (UUID/randomUUID))
          e1 (score/record-event! fixture-id scode-id :a 5 nil {:client-id client-id})
          e2 (score/record-event! fixture-id scode-id :a 5 nil {:client-id client-id})]
      (is (= (:score-event/id e1) (:score-event/id e2)))
      (is (= {:a 5 :b 0} (score/current-score fixture-id))))))

(deftest record-event-stores-client-ts
  (testing "record-event! persists the client timestamp when provided"
    (let [{:keys [fixture-id scode-id]} (seed!)
          ts (java.util.Date.)
          e (score/record-event! fixture-id scode-id :a 3 nil {:client-ts ts})]
      (is (= ts (:score-event/client-ts e))))))

;; ---------------------------------------------------------------------------
;; Conflict detection (SPO-47)
;; ---------------------------------------------------------------------------

(deftest detect-conflicts-flags-two-scodes
  (testing "detect-conflicts! flags events when two different scodes submit within the window"
    (let [{:keys [fixture-id uid]} (seed!)
          {:keys [entity]} (scode/generate! fixture-id uid)
          sc2 (scode/generate! fixture-id uid)
          scode-id-1 (:scode/id entity)
          scode-id-2 (:scode/id (:entity sc2))]
      (score/record-event! fixture-id scode-id-1 :a 5 nil)
      (score/record-event! fixture-id scode-id-2 :b 3 nil)
      (let [n (score/detect-conflicts! fixture-id)]
        (is (pos? n))
        (is (some :score-event/conflict (score/score-history fixture-id)))))))

(deftest detect-conflicts-no-flag-single-scode
  (testing "detect-conflicts! returns 0 when only one scode is active"
    (let [{:keys [fixture-id scode-id]} (seed!)]
      (score/record-event! fixture-id scode-id :a 5 nil)
      (score/record-event! fixture-id scode-id :a 3 nil)
      (is (= 0 (score/detect-conflicts! fixture-id))))))

;; ---------------------------------------------------------------------------
;; fixture-sync-status (SPO-47)
;; ---------------------------------------------------------------------------

(deftest fixture-sync-status-returns-summary
  (testing "fixture-sync-status returns score, event count and conflict flag"
    (let [{:keys [fixture-id scode-id]} (seed!)
          _ (score/record-event! fixture-id scode-id :a 5 nil)
          _ (score/record-event! fixture-id scode-id :b 3 nil)
          s (score/fixture-sync-status fixture-id)]
      (is (= {:a 5 :b 3} (:score s)))
      (is (= 2 (:event-count s)))
      (is (false? (:conflict? s)))
      (is (some? (:last-recorded-at s))))))

(deftest fixture-sync-status-empty-fixture
  (testing "fixture-sync-status with no events returns zero score"
    (let [{:keys [fixture-id]} (seed!)
          s (score/fixture-sync-status fixture-id)]
      (is (= {:a 0 :b 0} (:score s)))
      (is (= 0 (:event-count s)))
      (is (nil? (:last-recorded-at s))))))
