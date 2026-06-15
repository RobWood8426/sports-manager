(ns sports-manager.scorekeeper-code-test
  "Tests for secure per-game scoring codes (SPO-42), scorekeeper assignments (SPO-43),
  and game-status lifecycle (SPO-44)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [sports-manager.db :as db]
            [sports-manager.event :as event]
            [sports-manager.fixture :as fixture]
            [sports-manager.participant :as participant]
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
    (db/put-many! [{:xt/id tid :tenant/id tid :tenant/name "Test School" :tenant/status :active}
                   {:xt/id uid :user/firebase-uid uid :user/email "a@x.com" :user/status :active}])
    (let [ev (event/create! tid uid
                            {:event/name "Sports Day"
                             :event/start-at #inst "2026-09-01T08:00"
                             :event/end-at #inst "2026-09-01T18:00"
                             :event/visibility :event.visibility/public
                             :event/access-method :event.access/public-link}
                            #{:sport/rugby})
          event-id (:event/id ev)
          pa (participant/add-to-event! event-id uid
                                        {:participant/name "School A"
                                         :participant/contact-email "a@school.za"
                                         :participant/contact-phone ""}
                                        nil)
          pb (participant/add-to-event! event-id uid
                                        {:participant/name "School B"
                                         :participant/contact-email "b@school.za"
                                         :participant/contact-phone ""}
                                        nil)
          f (fixture/create! event-id uid
                             {:fixture/sport-code :sport/rugby
                              :fixture/team-a-id (:participant/id pa)
                              :fixture/team-b-id (:participant/id pb)
                              :fixture/start-at #inst "2026-09-01T09:00"
                              :fixture/end-at #inst "2026-09-01T10:00"})]
      {:event-id event-id
       :uid uid
       :fixture-id (:fixture/id f)})))

;; ---------------------------------------------------------------------------
;; generate!
;; ---------------------------------------------------------------------------

(deftest generate-returns-plaintext-and-entity
  (testing "generate! returns a plaintext code and a stored entity"
    (let [{:keys [fixture-id uid]} (seed!)
          {:keys [code entity]} (scode/generate! fixture-id uid)]
      (is (string? code))
      (is (= 8 (count code)))
      (is (uuid? (:scode/id entity)))
      (is (= :scode.status/active (:scode/status entity))))))

(deftest generate-does-not-store-plaintext
  (testing "the stored entity has no plaintext code field"
    (let [{:keys [fixture-id uid]} (seed!)
          {:keys [code entity]} (scode/generate! fixture-id uid)
          stored (scode/find-by-id (:scode/id entity))]
      (is (nil? (get stored :scode/code)))
      (is (nil? (get stored :scode/plaintext)))
      (is (not= code (str stored))))))

(deftest generate-unknown-fixture-throws
  (testing "throws ex-info when fixture not found"
    (st/seed-templates!)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Fixture not found"
                          (scode/generate! (UUID/randomUUID) "actor")))))

(deftest list-by-fixture-returns-codes
  (testing "list-by-fixture returns generated codes sorted newest first"
    (let [{:keys [fixture-id uid]} (seed!)
          _ (scode/generate! fixture-id uid)
          _ (scode/generate! fixture-id uid)
          codes (scode/list-by-fixture fixture-id)]
      (is (= 2 (count codes)))
      (is (every? #(= :scode.status/active (:scode/status %)) codes)))))

;; ---------------------------------------------------------------------------
;; verify-code
;; ---------------------------------------------------------------------------

(deftest verify-code-correct-returns-entity
  (testing "correct plaintext code verifies successfully"
    (let [{:keys [fixture-id uid]} (seed!)
          {:keys [code]} (scode/generate! fixture-id uid)
          result (scode/verify-code fixture-id code "127.0.0.1")]
      (is (some? result))
      (is (= :scode.status/active (:scode/status result))))))

(deftest verify-code-wrong-returns-nil
  (testing "wrong code returns nil"
    (let [{:keys [fixture-id uid]} (seed!)
          _ (scode/generate! fixture-id uid)]
      (is (nil? (scode/verify-code fixture-id "WRONGCOD" "127.0.0.1"))))))

(deftest verify-code-rate-limits-after-max-attempts
  (testing "after max-attempts failures, rate-limited? returns true"
    (let [{:keys [fixture-id uid]} (seed!)
          ip "10.0.0.1"]
      (scode/generate! fixture-id uid)
      (dotimes [_ 5]
        (scode/verify-code fixture-id "WRONGCOD" ip))
      (is (scode/rate-limited? ip))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Too many failed attempts"
                            (scode/verify-code fixture-id "WRONGCOD" ip))))))

;; ---------------------------------------------------------------------------
;; revoke!
;; ---------------------------------------------------------------------------

(deftest revoke-sets-status-revoked
  (testing "revoke! changes status to revoked"
    (let [{:keys [fixture-id uid]} (seed!)
          {:keys [entity]} (scode/generate! fixture-id uid)
          revoked (scode/revoke! (:scode/id entity) uid)]
      (is (= :scode.status/revoked (:scode/status revoked))))))

(deftest revoke-already-revoked-throws
  (testing "revoking an already-revoked code throws"
    (let [{:keys [fixture-id uid]} (seed!)
          {:keys [entity]} (scode/generate! fixture-id uid)]
      (scode/revoke! (:scode/id entity) uid)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already revoked"
                            (scode/revoke! (:scode/id entity) uid))))))

(deftest revoke-unknown-code-throws
  (testing "revoking an unknown code throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Code not found"
                          (scode/revoke! (UUID/randomUUID) "actor")))))

(deftest revoked-code-does-not-verify
  (testing "a revoked code fails verification"
    (let [{:keys [fixture-id uid]} (seed!)
          {:keys [code entity]} (scode/generate! fixture-id uid)]
      (scode/revoke! (:scode/id entity) uid)
      (is (nil? (scode/verify-code fixture-id code "127.0.0.2"))))))

;; ---------------------------------------------------------------------------
;; regenerate!
;; ---------------------------------------------------------------------------

(deftest regenerate-revokes-old-and-creates-new
  (testing "regenerate! revokes existing active code and creates fresh one"
    (let [{:keys [fixture-id uid]} (seed!)
          {:keys [entity]} (scode/generate! fixture-id uid)
          old-id (:scode/id entity)
          {:keys [code]} (scode/regenerate! fixture-id uid)]
      (is (string? code))
      (is (= :scode.status/revoked (:scode/status (scode/find-by-id old-id))))
      (let [active (filter #(= :scode.status/active (:scode/status %))
                           (scode/list-by-fixture fixture-id))]
        (is (= 1 (count active)))))))

;; ---------------------------------------------------------------------------
;; assign! / list-assignments-by-fixture (SPO-43)
;; ---------------------------------------------------------------------------

(deftest assign-creates-assignment-with-label
  (testing "assign! creates an assignment entity linked to a generated code"
    (let [{:keys [fixture-id uid]} (seed!)
          a (scode/assign! fixture-id "Scorekeeper 1" uid)]
      (is (uuid? (:assignment/id a)))
      (is (= "Scorekeeper 1" (:assignment/label a)))
      (is (= :scode.status/active (get-in a [:assignment/scode :scode/status]))))))

(deftest assign-unknown-fixture-throws
  (testing "assign! throws when fixture not found"
    (st/seed-templates!)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Fixture not found"
                          (scode/assign! (UUID/randomUUID) "SK 1" "actor")))))

(deftest list-assignments-returns-sorted
  (testing "list-assignments-by-fixture returns all assignments sorted by created-at asc"
    (let [{:keys [fixture-id uid]} (seed!)
          _ (scode/assign! fixture-id "SK 1" uid)
          _ (scode/assign! fixture-id "SK 2" uid)
          assignments (scode/list-assignments-by-fixture fixture-id)]
      (is (= 2 (count assignments)))
      (is (= "SK 1" (:assignment/label (first assignments))))
      (is (= "SK 2" (:assignment/label (second assignments)))))))

;; ---------------------------------------------------------------------------
;; find-fixture-by-code (SPO-43)
;; ---------------------------------------------------------------------------

(deftest find-fixture-by-code-returns-fixture-for-active-code
  (testing "find-fixture-by-code returns fixture map for an active non-expired code"
    (let [{:keys [fixture-id uid]} (seed!)
          {:keys [entity]} (scode/generate! fixture-id uid)
          f (scode/find-fixture-by-code (:scode/id entity))]
      (is (some? f))
      (is (= fixture-id (:fixture/id f))))))

(deftest find-fixture-by-code-nil-for-revoked
  (testing "find-fixture-by-code returns nil for a revoked code"
    (let [{:keys [fixture-id uid]} (seed!)
          {:keys [entity]} (scode/generate! fixture-id uid)]
      (scode/revoke! (:scode/id entity) uid)
      (is (nil? (scode/find-fixture-by-code (:scode/id entity)))))))

(deftest find-fixture-by-code-nil-for-unknown
  (testing "find-fixture-by-code returns nil for an unknown code id"
    (is (nil? (scode/find-fixture-by-code (UUID/randomUUID))))))

;; ---------------------------------------------------------------------------
;; find-active-by-plaintext (SPO-43)
;; ---------------------------------------------------------------------------

(deftest find-active-by-plaintext-returns-scode-for-correct-code
  (testing "find-active-by-plaintext returns scode entity when code matches"
    (let [{:keys [fixture-id uid]} (seed!)
          {:keys [code]} (scode/generate! fixture-id uid)
          result (scode/find-active-by-plaintext code "192.168.1.1")]
      (is (some? result))
      (is (= :scode.status/active (:scode/status result))))))

(deftest find-active-by-plaintext-returns-nil-for-wrong-code
  (testing "find-active-by-plaintext returns nil for wrong plaintext"
    (let [{:keys [fixture-id uid]} (seed!)
          _ (scode/generate! fixture-id uid)]
      (is (nil? (scode/find-active-by-plaintext "WRONGCOD" "192.168.1.2"))))))

(deftest find-active-by-plaintext-rate-limits
  (testing "find-active-by-plaintext rate-limits after max failures"
    (let [{:keys [fixture-id uid]} (seed!)
          ip "10.1.2.3"]
      (scode/generate! fixture-id uid)
      (dotimes [_ 5]
        (scode/find-active-by-plaintext "WRONGCOD" ip))
      (is (scode/rate-limited? ip))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Too many failed attempts"
                            (scode/find-active-by-plaintext "WRONGCOD" ip))))))

;; ---------------------------------------------------------------------------
;; Game-status lifecycle (SPO-44)
;; ---------------------------------------------------------------------------

(deftest mark-accessed-sets-game-status
  (testing "mark-accessed! sets game-status to :accessed"
    (let [{:keys [fixture-id uid]} (seed!)
          {:keys [entity]} (scode/generate! fixture-id uid)
          result (scode/mark-accessed! (:scode/id entity))]
      (is (= :scode.game-status/accessed (:scode/game-status result))))))

(deftest start-game-sets-game-status
  (testing "start-game! sets game-status to :started"
    (let [{:keys [fixture-id uid]} (seed!)
          {:keys [entity]} (scode/generate! fixture-id uid)
          result (scode/start-game! (:scode/id entity) uid)]
      (is (= :scode.game-status/started (:scode/game-status result))))))

(deftest go-live-sets-game-status
  (testing "go-live! sets game-status to :live"
    (let [{:keys [fixture-id uid]} (seed!)
          {:keys [entity]} (scode/generate! fixture-id uid)
          result (scode/go-live! (:scode/id entity) uid)]
      (is (= :scode.game-status/live (:scode/game-status result))))))

(deftest submit-final-sets-game-status
  (testing "submit-final! sets game-status to :final-submitted"
    (let [{:keys [fixture-id uid]} (seed!)
          {:keys [entity]} (scode/generate! fixture-id uid)
          result (scode/submit-final! (:scode/id entity) uid)]
      (is (= :scode.game-status/final-submitted (:scode/game-status result))))))

(deftest pend-final-sets-game-status
  (testing "pend-final! sets game-status to :final-pending"
    (let [{:keys [fixture-id uid]} (seed!)
          {:keys [entity]} (scode/generate! fixture-id uid)
          result (scode/pend-final! (:scode/id entity) uid)]
      (is (= :scode.game-status/final-pending (:scode/game-status result))))))

(deftest accept-final-sets-game-status
  (testing "accept-final! sets game-status to :final-accepted"
    (let [{:keys [fixture-id uid]} (seed!)
          {:keys [entity]} (scode/generate! fixture-id uid)
          result (scode/accept-final! (:scode/id entity) uid)]
      (is (= :scode.game-status/final-accepted (:scode/game-status result))))))

(deftest dispute-final-sets-game-status
  (testing "dispute-final! sets game-status to :final-disputed"
    (let [{:keys [fixture-id uid]} (seed!)
          {:keys [entity]} (scode/generate! fixture-id uid)
          result (scode/dispute-final! (:scode/id entity) uid "Score was incorrect")]
      (is (= :scode.game-status/final-disputed (:scode/game-status result))))))

(deftest dispute-final-requires-reason
  (testing "dispute-final! throws when reason is blank"
    (let [{:keys [fixture-id uid]} (seed!)
          {:keys [entity]} (scode/generate! fixture-id uid)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Dispute reason is required"
                            (scode/dispute-final! (:scode/id entity) uid ""))))))

(deftest advance-game-status-throws-for-revoked-code
  (testing "lifecycle functions throw when the code is revoked"
    (let [{:keys [fixture-id uid]} (seed!)
          {:keys [entity]} (scode/generate! fixture-id uid)]
      (scode/revoke! (:scode/id entity) uid)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Code is not active"
                            (scode/mark-accessed! (:scode/id entity)))))))

(deftest advance-game-status-throws-for-unknown-code
  (testing "lifecycle functions throw for unknown code id"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Code not found"
                          (scode/mark-accessed! (UUID/randomUUID))))))

(deftest expire-codes-expires-past-codes
  (testing "expire-codes! returns count of expired codes and sets status"
    (st/seed-templates!)
    (let [tid (UUID/randomUUID)
          uid "actor"]
      (db/put-many! [{:xt/id tid :tenant/id tid :tenant/name "Past School" :tenant/status :active}
                     {:xt/id uid :user/firebase-uid uid :user/email "past@x.com" :user/status :active}])
      (let [ev (event/create! tid uid
                              {:event/name "Past Day"
                               :event/start-at #inst "2020-01-01T08:00"
                               :event/end-at #inst "2020-01-01T18:00"
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
                                :fixture/start-at #inst "2020-01-01T09:00"
                                :fixture/end-at #inst "2020-01-01T10:00"})
            fixture-id (:fixture/id f)
            {:keys [entity]} (scode/generate! fixture-id uid)
            n (scode/expire-codes! fixture-id)]
        (is (= 1 n))
        (is (= :scode.status/expired (:scode/status (scode/find-by-id (:scode/id entity)))))))))

(deftest expire-codes-ignores-already-expired
  (testing "expire-codes! is idempotent — already-expired codes are not re-processed"
    (st/seed-templates!)
    (let [tid (UUID/randomUUID)
          uid "actor"]
      (db/put-many! [{:xt/id tid :tenant/id tid :tenant/name "Past School 2" :tenant/status :active}
                     {:xt/id uid :user/firebase-uid uid :user/email "past2@x.com" :user/status :active}])
      (let [ev (event/create! tid uid
                              {:event/name "Past Day 2"
                               :event/start-at #inst "2020-02-01T08:00"
                               :event/end-at #inst "2020-02-01T18:00"
                               :event/visibility :event.visibility/public
                               :event/access-method :event.access/public-link}
                              #{:sport/rugby})
            event-id (:event/id ev)
            pa (participant/add-to-event! event-id uid
                                          {:participant/name "A" :participant/contact-email "a2@a.za"
                                           :participant/contact-phone ""} nil)
            pb (participant/add-to-event! event-id uid
                                          {:participant/name "B" :participant/contact-email "b2@b.za"
                                           :participant/contact-phone ""} nil)
            f (fixture/create! event-id uid
                               {:fixture/sport-code :sport/rugby
                                :fixture/team-a-id (:participant/id pa)
                                :fixture/team-b-id (:participant/id pb)
                                :fixture/start-at #inst "2020-02-01T09:00"
                                :fixture/end-at #inst "2020-02-01T10:00"})
            fixture-id (:fixture/id f)
            _ (scode/generate! fixture-id uid)]
        (is (= 1 (scode/expire-codes! fixture-id)))
        (is (= 0 (scode/expire-codes! fixture-id)))))))
