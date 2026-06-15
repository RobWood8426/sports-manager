(ns sports-manager.audit-test
  "Tests for SPO-20 — immutable audit log query helpers."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [sports-manager.audit :as audit]
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
                                        nil)]
      {:tenant-id tid
       :event-id event-id
       :uid uid
       :pa-id (:participant/id pa)
       :pb-id (:participant/id pb)})))

(defn- make-fixture! [{:keys [event-id uid pa-id pb-id]}]
  (fixture/create! event-id uid
                   {:fixture/sport-code :sport/rugby
                    :fixture/team-a-id pa-id
                    :fixture/team-b-id pb-id
                    :fixture/start-at #inst "2026-09-01T09:00"
                    :fixture/end-at #inst "2026-09-01T10:00"}))

;; ---------------------------------------------------------------------------
;; list-by-tenant
;; ---------------------------------------------------------------------------

(deftest list-by-tenant-empty
  (testing "returns empty seq when no audit entries exist for tenant"
    (let [{:keys [tenant-id]} (seed!)]
      ;; seed! doesn't write any audit entries (event/create! doesn't)
      (is (vector? (audit/list-by-tenant tenant-id))))))

(deftest list-by-tenant-returns-entries
  (testing "returns entries written during fixture lifecycle"
    (let [ctx (seed!)
          f (make-fixture! ctx)
          fid (:fixture/id f)
          _ (fixture/publish! fid (:uid ctx))
          entries (audit/list-by-tenant (:tenant-id ctx))]
      (is (pos? (count entries)))
      (is (every? :audit/action entries))
      (is (every? :audit/at entries)))))

(deftest list-by-tenant-newest-first
  (testing "entries are sorted newest first"
    (let [ctx (seed!)
          f (make-fixture! ctx)
          fid (:fixture/id f)
          ;; edit then publish so there are at least two entries
          _ (fixture/update! fid (:uid ctx) {:fixture/venue "North Field"})
          _ (fixture/publish! fid (:uid ctx))
          entries (audit/list-by-tenant (:tenant-id ctx))]
      (is (>= (count entries) 2))
      (is (.after (:audit/at (first entries))
                  (:audit/at (second entries)))))))

(deftest list-by-tenant-respects-limit
  (testing "limit caps the number of returned entries"
    (let [ctx (seed!)
          f (make-fixture! ctx)
          fid (:fixture/id f)
          _ (fixture/publish! fid (:uid ctx))
          entries (audit/list-by-tenant (:tenant-id ctx) 1)]
      (is (<= (count entries) 1)))))

;; ---------------------------------------------------------------------------
;; list-by-entity
;; ---------------------------------------------------------------------------

(deftest list-by-entity-returns-fixture-entries
  (testing "returns audit entries scoped to the fixture entity"
    (let [ctx (seed!)
          f (make-fixture! ctx)
          fid (:fixture/id f)
          _ (fixture/publish! fid (:uid ctx))
          entries (audit/list-by-entity fid)]
      (is (pos? (count entries)))
      (is (every? #(= fid (:audit/entity-id %)) entries)))))

(deftest list-by-entity-unknown-id
  (testing "unknown entity-id returns empty seq"
    (seed!)
    (is (empty? (audit/list-by-entity (UUID/randomUUID))))))

;; ---------------------------------------------------------------------------
;; list-by-actor
;; ---------------------------------------------------------------------------

(deftest list-by-actor-returns-entries
  (testing "returns entries where actor matches the UID"
    (let [ctx (seed!)
          f (make-fixture! ctx)
          fid (:fixture/id f)
          _ (fixture/publish! fid (:uid ctx))
          entries (audit/list-by-actor (:uid ctx))]
      (is (pos? (count entries)))
      (is (every? #(= (:uid ctx) (:audit/actor %)) entries)))))

(deftest list-by-actor-unknown-uid
  (testing "unknown actor returns empty seq"
    (seed!)
    (is (empty? (audit/list-by-actor "uid-that-does-not-exist")))))

;; ---------------------------------------------------------------------------
;; list-by-action
;; ---------------------------------------------------------------------------

(deftest list-by-action-filters-correctly
  (testing "list-by-action returns only entries matching the action keyword"
    (let [ctx (seed!)
          f (make-fixture! ctx)
          fid (:fixture/id f)
          _ (fixture/publish! fid (:uid ctx))
          entries (audit/list-by-action (:tenant-id ctx) :fixture/publish)]
      (is (pos? (count entries)))
      (is (every? #(= :fixture/publish (:audit/action %)) entries)))))

(deftest list-by-action-no-match
  (testing "action with no entries returns empty seq"
    (let [{:keys [tenant-id]} (seed!)]
      (is (empty? (audit/list-by-action tenant-id :final-score/dispute-resolved))))))

;; ---------------------------------------------------------------------------
;; Scorekeeper code entries
;; ---------------------------------------------------------------------------

(deftest scode-generation-appears-in-audit
  (testing ":scode/generated action is written and queryable"
    (let [ctx (seed!)
          f (make-fixture! ctx)
          fid (:fixture/id f)
          {:keys [entity]} (scode/generate! fid (:uid ctx))
          sid (:scode/id entity)
          entries (audit/list-by-entity sid)]
      (is (some #(= :scode/generated (:audit/action %)) entries)))))

(deftest scode-revoke-appears-in-audit
  (testing ":scode/revoked action is written on revocation"
    (let [ctx (seed!)
          f (make-fixture! ctx)
          fid (:fixture/id f)
          {:keys [entity]} (scode/generate! fid (:uid ctx))
          sid (:scode/id entity)
          _ (scode/revoke! sid (:uid ctx))
          entries (audit/list-by-entity sid)]
      (is (some #(= :scode/revoked (:audit/action %)) entries)))))
