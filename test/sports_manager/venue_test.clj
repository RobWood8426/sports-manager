(ns sports-manager.venue-test
  "Tests for SPO-35 — venue (field) pool owned by the tenant/school."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [sports-manager.db :as db]
            [sports-manager.test-db :as test-db]
            [sports-manager.venue :as venue])
  (:import java.util.UUID))

(use-fixtures :each test-db/with-db)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- seed! []
  (let [tid (UUID/randomUUID)]
    (db/put! {:xt/id tid :tenant/id tid :tenant/name "Test School" :tenant/status :active})
    {:tenant-id tid}))

;; ---------------------------------------------------------------------------
;; create! / find-by-id
;; ---------------------------------------------------------------------------

(deftest create-returns-entity
  (testing "create! returns a venue entity with the provided fields"
    (let [{:keys [tenant-id]} (seed!)
          v (venue/create! tenant-id {:venue/name "Main Field"
                                      :venue/type :venue.type/field})]
      (is (uuid? (:venue/id v)))
      (is (= "Main Field" (:venue/name v)))
      (is (= :venue.type/field (:venue/type v))))))

(deftest create-throws-for-unknown-tenant
  (testing "create! throws ex-info when the tenant does not exist"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Tenant not found"
                          (venue/create! (UUID/randomUUID) {:venue/name "X"
                                                            :venue/type :venue.type/field})))))

(deftest find-by-id-returns-nil-for-unknown
  (testing "find-by-id returns nil for a non-existent UUID"
    (seed!)
    (is (nil? (venue/find-by-id (UUID/randomUUID))))))

(deftest find-by-id-round-trips
  (testing "find-by-id retrieves a venue just created"
    (let [{:keys [tenant-id]} (seed!)
          v (venue/create! tenant-id {:venue/name "Court 1"
                                      :venue/type :venue.type/court})]
      (is (= (:venue/id v)
             (:venue/id (venue/find-by-id (:venue/id v))))))))

;; ---------------------------------------------------------------------------
;; list-by-tenant
;; ---------------------------------------------------------------------------

(deftest list-by-tenant-empty
  (testing "list-by-tenant returns empty seq when no venues exist"
    (let [{:keys [tenant-id]} (seed!)]
      (is (empty? (venue/list-by-tenant tenant-id))))))

(deftest list-by-tenant-returns-all-venues
  (testing "list-by-tenant returns all venues for a tenant"
    (let [{:keys [tenant-id]} (seed!)
          _ (venue/create! tenant-id {:venue/name "Pool" :venue/type :venue.type/pool})
          _ (venue/create! tenant-id {:venue/name "Track" :venue/type :venue.type/track})
          vs (venue/list-by-tenant tenant-id)]
      (is (= 2 (count vs)))
      (is (every? :venue/name vs)))))

(deftest list-by-tenant-sorted-by-order-then-name
  (testing "venues are sorted by display-order ascending, then name"
    (let [{:keys [tenant-id]} (seed!)
          _ (venue/create! tenant-id {:venue/name "B Field" :venue/type :venue.type/field
                                      :venue/display-order 2})
          _ (venue/create! tenant-id {:venue/name "A Field" :venue/type :venue.type/field
                                      :venue/display-order 1})
          _ (venue/create! tenant-id {:venue/name "No Order" :venue/type :venue.type/court})
          vs (venue/list-by-tenant tenant-id)
          names (map :venue/name vs)]
      (is (= "A Field" (first names)))
      (is (= "B Field" (second names))))))

(deftest list-by-tenant-isolated-across-tenants
  (testing "venues from one tenant are not returned for another"
    (let [{:keys [tenant-id]} (seed!)
          other-tid (UUID/randomUUID)]
      (db/put! {:xt/id other-tid :tenant/id other-tid :tenant/name "Other School" :tenant/status :active})
      (venue/create! tenant-id {:venue/name "Pitch 1" :venue/type :venue.type/pitch})
      (is (empty? (venue/list-by-tenant other-tid))))))

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
    (let [{:keys [tenant-id]} (seed!)
          v (venue/create! tenant-id {:venue/name "Old Name" :venue/type :venue.type/field})
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
    (let [{:keys [tenant-id]} (seed!)
          v (venue/create! tenant-id {:venue/name "Gone" :venue/type :venue.type/hall})
          vid (:venue/id v)]
      (venue/delete! vid)
      (is (nil? (venue/find-by-id vid))))))

(deftest delete-throws-for-unknown-venue
  (testing "delete! throws ex-info for a non-existent venue"
    (seed!)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Venue not found"
                          (venue/delete! (UUID/randomUUID))))))

(deftest delete-does-not-affect-other-venues
  (testing "deleting one venue does not remove others in the same tenant"
    (let [{:keys [tenant-id]} (seed!)
          v1 (venue/create! tenant-id {:venue/name "Keep" :venue/type :venue.type/field})
          v2 (venue/create! tenant-id {:venue/name "Remove" :venue/type :venue.type/court})]
      (venue/delete! (:venue/id v2))
      (is (= 1 (count (venue/list-by-tenant tenant-id))))
      (is (= "Keep" (:venue/name (venue/find-by-id (:venue/id v1))))))))
