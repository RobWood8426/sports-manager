(ns sports-manager.venue-test
  "Tests for SPO-35 — venue management per event."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [sports-manager.db :as db]
            [sports-manager.event :as event]
            [sports-manager.sport-template :as st]
            [sports-manager.test-db :as test-db]
            [sports-manager.venue :as venue])
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
                            #{:sport/rugby})]
      {:tenant-id tid :event-id (:event/id ev) :uid uid})))

;; ---------------------------------------------------------------------------
;; create! / find-by-id
;; ---------------------------------------------------------------------------

(deftest create-returns-entity
  (testing "create! returns a venue entity with the provided fields"
    (let [{:keys [event-id]} (seed!)
          v (venue/create! event-id {:venue/name "Main Field"
                                     :venue/type :venue.type/field})]
      (is (uuid? (:venue/id v)))
      (is (= "Main Field" (:venue/name v)))
      (is (= :venue.type/field (:venue/type v))))))

(deftest find-by-id-returns-nil-for-unknown
  (testing "find-by-id returns nil for a non-existent UUID"
    (seed!)
    (is (nil? (venue/find-by-id (UUID/randomUUID))))))

(deftest find-by-id-round-trips
  (testing "find-by-id retrieves a venue just created"
    (let [{:keys [event-id]} (seed!)
          v (venue/create! event-id {:venue/name "Court 1"
                                     :venue/type :venue.type/court})]
      (is (= (:venue/id v)
             (:venue/id (venue/find-by-id (:venue/id v))))))))

;; ---------------------------------------------------------------------------
;; list-by-event
;; ---------------------------------------------------------------------------

(deftest list-by-event-empty
  (testing "list-by-event returns empty seq when no venues exist"
    (let [{:keys [event-id]} (seed!)]
      (is (empty? (venue/list-by-event event-id))))))

(deftest list-by-event-returns-all-venues
  (testing "list-by-event returns all venues for an event"
    (let [{:keys [event-id]} (seed!)
          _ (venue/create! event-id {:venue/name "Pool" :venue/type :venue.type/pool})
          _ (venue/create! event-id {:venue/name "Track" :venue/type :venue.type/track})
          vs (venue/list-by-event event-id)]
      (is (= 2 (count vs)))
      (is (every? :venue/name vs)))))

(deftest list-by-event-sorted-by-order-then-name
  (testing "venues are sorted by display-order ascending, then name"
    (let [{:keys [event-id]} (seed!)
          _ (venue/create! event-id {:venue/name "B Field" :venue/type :venue.type/field
                                     :venue/display-order 2})
          _ (venue/create! event-id {:venue/name "A Field" :venue/type :venue.type/field
                                     :venue/display-order 1})
          _ (venue/create! event-id {:venue/name "No Order" :venue/type :venue.type/court})
          vs (venue/list-by-event event-id)
          names (map :venue/name vs)]
      (is (= "A Field" (first names)))
      (is (= "B Field" (second names))))))

(deftest list-by-event-isolated-across-events
  (testing "venues from one event are not returned for another"
    (let [{:keys [event-id tenant-id uid]} (seed!)
          ev2 (event/create! tenant-id uid
                             {:event/name "Other Event"
                              :event/start-at #inst "2026-10-01T08:00"
                              :event/end-at #inst "2026-10-01T18:00"
                              :event/visibility :event.visibility/public
                              :event/access-method :event.access/public-link}
                             #{:sport/rugby})]
      (venue/create! event-id {:venue/name "Pitch 1" :venue/type :venue.type/pitch})
      (is (empty? (venue/list-by-event (:event/id ev2)))))))

;; ---------------------------------------------------------------------------
;; validate / parse-form
;; ---------------------------------------------------------------------------

(deftest validate-missing-name
  (testing "validate returns error when name is blank"
    (let [errors (venue/validate {:venue/name "" :venue/type :venue.type/field})]
      (is (contains? errors :venue/name)))))

(deftest validate-missing-type
  (testing "validate returns error when type is nil"
    (let [errors (venue/validate {:venue/name "Field" :venue/type nil})]
      (is (contains? errors :venue/type)))))

(deftest validate-passes-valid
  (testing "validate returns empty map for a valid venue"
    (let [errors (venue/validate {:venue/name "Main Field" :venue/type :venue.type/field})]
      (is (empty? errors)))))

(deftest parse-form-basic
  (testing "parse-form extracts name and type keyword"
    (let [result (venue/parse-form {"venue-name" "Track 1"
                                    "venue-type" "venue.type/track"})]
      (is (= "Track 1" (:venue/name result)))
      (is (= :venue.type/track (:venue/type result))))))

(deftest parse-form-optional-fields
  (testing "parse-form extracts lat, lng, and display-order when present"
    (let [result (venue/parse-form {"venue-name" "Field"
                                    "venue-type" "venue.type/field"
                                    "venue-lat" "-33.9"
                                    "venue-lng" "18.4"
                                    "venue-order" "3"})]
      (is (= -33.9 (:venue/lat result)))
      (is (= 18.4 (:venue/lng result)))
      (is (= 3 (:venue/display-order result))))))

(deftest parse-form-ignores-blank-optionals
  (testing "parse-form omits optional keys when fields are blank"
    (let [result (venue/parse-form {"venue-name" "Court"
                                    "venue-type" "venue.type/court"
                                    "venue-lat" ""
                                    "venue-lng" ""
                                    "venue-order" ""})]
      (is (not (contains? result :venue/lat)))
      (is (not (contains? result :venue/lng)))
      (is (not (contains? result :venue/display-order))))))

;; ---------------------------------------------------------------------------
;; update!
;; ---------------------------------------------------------------------------

(deftest update-changes-name
  (testing "update! changes the venue name"
    (let [{:keys [event-id]} (seed!)
          v (venue/create! event-id {:venue/name "Old Name" :venue/type :venue.type/field})
          vid (:venue/id v)
          updated (venue/update! vid {:venue/name "New Name"})]
      (is (= "New Name" (:venue/name updated))))))

(deftest update-throws-for-unknown-venue
  (testing "update! throws ex-info for a non-existent venue"
    (seed!)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Venue not found"
                          (venue/update! (UUID/randomUUID) {:venue/name "X"})))))

;; ---------------------------------------------------------------------------
;; delete!
;; ---------------------------------------------------------------------------

(deftest delete-removes-venue
  (testing "delete! removes the venue so find-by-id returns nil"
    (let [{:keys [event-id]} (seed!)
          v (venue/create! event-id {:venue/name "Gone" :venue/type :venue.type/hall})
          vid (:venue/id v)]
      (venue/delete! vid)
      (is (nil? (venue/find-by-id vid))))))

(deftest delete-throws-for-unknown-venue
  (testing "delete! throws ex-info for a non-existent venue"
    (seed!)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Venue not found"
                          (venue/delete! (UUID/randomUUID))))))

(deftest delete-does-not-affect-other-venues
  (testing "deleting one venue does not remove others in the same event"
    (let [{:keys [event-id]} (seed!)
          v1 (venue/create! event-id {:venue/name "Keep" :venue/type :venue.type/field})
          v2 (venue/create! event-id {:venue/name "Remove" :venue/type :venue.type/court})]
      (venue/delete! (:venue/id v2))
      (is (= 1 (count (venue/list-by-event event-id))))
      (is (= "Keep" (:venue/name (venue/find-by-id (:venue/id v1))))))))
