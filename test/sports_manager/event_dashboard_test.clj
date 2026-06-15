(ns sports-manager.event-dashboard-test
  "Tests for SPO-58 — dashboard-data bucket classification."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [sports-manager.db :as db]
            [sports-manager.event :as event]
            [sports-manager.event-dashboard :as dashboard]
            [sports-manager.event-sport :as event-sport]
            [sports-manager.final-score :as final-score]
            [sports-manager.fixture :as fixture]
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
      {:event-id event-id
       :uid uid
       :pa-id (:participant/id pa)
       :pb-id (:participant/id pb)})))

(defn- make-fixture! [{:keys [event-id uid pa-id pb-id]}
                      & [{:keys [start-at end-at]
                          :or {start-at #inst "2026-09-01T09:00"
                               end-at #inst "2026-09-01T10:00"}}]]
  (fixture/create! event-id uid
                   {:fixture/sport-code :sport/rugby
                    :fixture/team-a-id pa-id
                    :fixture/team-b-id pb-id
                    :fixture/start-at start-at
                    :fixture/end-at end-at}))

;; ---------------------------------------------------------------------------
;; Empty event
;; ---------------------------------------------------------------------------

(deftest dashboard-empty-event
  (testing "event with no fixtures returns zeros in all buckets"
    (let [{:keys [event-id]} (seed!)
          d (dashboard/dashboard-data event-id)]
      (is (empty? (:fixtures d)))
      (is (= 0 (:total (:counts d))))
      (is (every? zero? (map #(get (:counts d) %)
                             [:live :completed :disputed :pending :no-activity]))))))

;; ---------------------------------------------------------------------------
;; :no-activity — fixture with no codes or scoring
;; ---------------------------------------------------------------------------

(deftest dashboard-no-activity-bucket
  (testing "fixture with no scorekeeper activity → :no-activity"
    (let [ctx (seed!)
          _f (make-fixture! ctx)
          d (dashboard/dashboard-data (:event-id ctx))
          enriched (first (:fixtures d))]
      (is (= 1 (:total (:counts d))))
      (is (= 1 (get-in d [:counts :no-activity])))
      (is (= :no-activity (:dashboard/bucket enriched)))
      (is (nil? (:dashboard/score enriched)))
      (is (false? (:dashboard/conflict? enriched))))))

;; ---------------------------------------------------------------------------
;; :live — scorekeeper session in progress
;; ---------------------------------------------------------------------------

(deftest dashboard-live-bucket
  (testing "fixture with a scorekeeper recording events → :live"
    (let [ctx (seed!)
          f (make-fixture! ctx)
          fid (:fixture/id f)
          {:keys [entity]} (scode/generate! fid (:uid ctx))
          sid (:scode/id entity)]
      (scode/go-live! sid (:uid ctx))
      (score/record-event! fid sid :a 3 nil)
      (let [d (dashboard/dashboard-data (:event-id ctx))
            enriched (first (:fixtures d))]
        (is (= 1 (get-in d [:counts :live])))
        (is (= :live (:dashboard/bucket enriched)))
        (is (= {:a 3 :b 0} (:dashboard/score enriched)))))))

;; ---------------------------------------------------------------------------
;; :completed — accepted final score (single model accepts immediately)
;; ---------------------------------------------------------------------------

(deftest dashboard-completed-bucket
  (testing "fixture with accepted final score → :completed"
    (let [ctx (seed!)
          f (make-fixture! ctx)
          fid (:fixture/id f)
          {:keys [entity]} (scode/generate! fid (:uid ctx))
          sid (:scode/id entity)]
      (score/record-event! fid sid :a 5 nil)
      (score/record-event! fid sid :b 2 nil)
      ;; default model is :single → submit! transitions to :accepted immediately
      (final-score/submit! fid sid (:uid ctx))
      (let [d (dashboard/dashboard-data (:event-id ctx))
            enriched (first (:fixtures d))]
        (is (= 1 (get-in d [:counts :completed])))
        (is (= :completed (:dashboard/bucket enriched)))))))

;; ---------------------------------------------------------------------------
;; :pending — final score awaiting admin confirmation
;; ---------------------------------------------------------------------------

(deftest dashboard-pending-bucket
  (testing "fixture with pending final score → :pending"
    (let [ctx (seed!)
          event-id (:event-id ctx)
          _ (event-sport/configure! event-id :sport/rugby
                                    {:event-sport/validation-model :validation.model/single-pending}
                                    (:uid ctx))
          f (make-fixture! ctx)
          fid (:fixture/id f)
          {:keys [entity]} (scode/generate! fid (:uid ctx))
          sid (:scode/id entity)]
      (score/record-event! fid sid :a 1 nil)
      (final-score/submit! fid sid (:uid ctx))
      (let [d (dashboard/dashboard-data event-id)
            enriched (first (:fixtures d))]
        (is (= 1 (get-in d [:counts :pending])))
        (is (= :pending (:dashboard/bucket enriched)))))))

;; ---------------------------------------------------------------------------
;; :disputed — dual model with mismatched scores
;; ---------------------------------------------------------------------------

(deftest dashboard-disputed-bucket
  (testing "dual-model fixture with score mismatch → :disputed"
    (let [ctx (seed!)
          event-id (:event-id ctx)
          _ (event-sport/configure! event-id :sport/rugby
                                    {:event-sport/validation-model :validation.model/dual}
                                    (:uid ctx))
          f (make-fixture! ctx)
          fid (:fixture/id f)
          {:keys [entity]} (scode/generate! fid (:uid ctx))
          sid1 (:scode/id entity)
          {:keys [entity]} (scode/generate! fid (:uid ctx))
          sid2 (:scode/id entity)]
      ;; First scorer: A scores 3
      (score/record-event! fid sid1 :a 3 nil)
      (final-score/submit! fid sid1 (:uid ctx))
      ;; Second scorer: A scores 1 — mismatch triggers :disputed
      (score/record-event! fid sid2 :a 1 nil)
      (final-score/submit! fid sid2 (:uid ctx))
      (let [d (dashboard/dashboard-data event-id)
            enriched (first (:fixtures d))]
        (is (= 1 (get-in d [:counts :disputed])))
        (is (= :disputed (:dashboard/bucket enriched)))))))

;; ---------------------------------------------------------------------------
;; :total count
;; ---------------------------------------------------------------------------

(deftest dashboard-counts-total
  (testing "total count matches number of fixtures regardless of bucket"
    (let [ctx (seed!)
          _f1 (make-fixture! ctx)
          _f2 (make-fixture! ctx {:start-at #inst "2026-09-01T11:00"
                                  :end-at #inst "2026-09-01T12:00"})
          d (dashboard/dashboard-data (:event-id ctx))]
      (is (= 2 (:total (:counts d))))
      (is (= 2 (count (:fixtures d)))))))

;; ---------------------------------------------------------------------------
;; :conflicts — conflict? flag
;; ---------------------------------------------------------------------------

(deftest dashboard-no-conflict-single-scorer
  (testing "single active scorer produces no conflict"
    (let [ctx (seed!)
          f (make-fixture! ctx)
          fid (:fixture/id f)
          {:keys [entity]} (scode/generate! fid (:uid ctx))
          sid (:scode/id entity)]
      (scode/go-live! sid (:uid ctx))
      (score/record-event! fid sid :a 1 nil)
      (let [d (dashboard/dashboard-data (:event-id ctx))]
        (is (empty? (:conflicts d)))
        (is (false? (:dashboard/conflict? (first (:fixtures d)))))))))

;; ---------------------------------------------------------------------------
;; :by-bucket grouping
;; ---------------------------------------------------------------------------

(deftest dashboard-by-bucket-grouping
  (testing "by-bucket groups fixtures into the correct bucket key"
    (let [ctx (seed!)
          _f (make-fixture! ctx)
          d (dashboard/dashboard-data (:event-id ctx))]
      (is (= 1 (count (get (:by-bucket d) :no-activity))))
      (is (empty? (get (:by-bucket d) :live []))))))
