(ns sports-manager.user-test
  "Unit tests for sports-manager.user (SPO-24).
  Covers validation, query, and mutation functions against a fresh in-memory db."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [sports-manager.db :as db]
            [sports-manager.membership :as membership]
            [sports-manager.rbac :as rbac]
            [sports-manager.test-db :as test-db]
            [sports-manager.user :as user])
  (:import java.util.UUID))

(use-fixtures :each test-db/with-db)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- seed-tenant! [name]
  (let [tid (UUID/randomUUID)]
    (db/transact! [{:tenant/id tid :tenant/name name :tenant/status :active}])
    tid))

(defn- seed-user! [uid email tenant-id]
  (db/transact! [{:user/firebase-uid uid :user/email email :user/status :active}])
  (when tenant-id
    (membership/create! uid tenant-id))
  uid)

;; ---------------------------------------------------------------------------
;; validate-add
;; ---------------------------------------------------------------------------

(deftest validate-add-blank-email
  (testing "blank email is required"
    (is (= {:email "Required"} (user/validate-add {:email ""})))))

(deftest validate-add-invalid-email
  (testing "malformed email is rejected"
    (is (= {:email "Enter a valid email address"} (user/validate-add {:email "notanemail"})))))

(deftest validate-add-valid-email
  (testing "well-formed email passes"
    (is (empty? (user/validate-add {:email "coach@school.ac.za"})))))

;; ---------------------------------------------------------------------------
;; find-by-email
;; ---------------------------------------------------------------------------

(deftest find-by-email-not-found
  (testing "returns nil for an unknown email"
    (is (nil? (user/find-by-email "ghost@nowhere.com")))))

(deftest find-by-email-found
  (testing "returns user entity when email exists"
    (seed-user! "uid-1" "found@school.ac.za" nil)
    (let [u (user/find-by-email "found@school.ac.za")]
      (is (= "uid-1" (:user/firebase-uid u)))
      (is (= "found@school.ac.za" (:user/email u))))))

;; ---------------------------------------------------------------------------
;; list-by-tenant
;; ---------------------------------------------------------------------------

(deftest list-by-tenant-empty
  (testing "returns empty vec when tenant has no users"
    (let [tid (seed-tenant! "Empty School")]
      (is (= [] (user/list-by-tenant tid))))))

(deftest list-by-tenant-returns-members
  (testing "returns only users belonging to the given tenant"
    (let [tid1 (seed-tenant! "School A")
          tid2 (seed-tenant! "School B")]
      (seed-user! "u-a1" "a1@school.ac.za" tid1)
      (seed-user! "u-a2" "a2@school.ac.za" tid1)
      (seed-user! "u-b1" "b1@school.ac.za" tid2)
      (let [a-users (user/list-by-tenant tid1)
            b-users (user/list-by-tenant tid2)]
        (is (= 2 (count a-users)))
        (is (every? #{"u-a1" "u-a2"} (map :user/firebase-uid a-users)))
        (is (= 1 (count b-users)))
        (is (= "u-b1" (:user/firebase-uid (first b-users))))))))

;; ---------------------------------------------------------------------------
;; add-to-tenant!
;; ---------------------------------------------------------------------------

(deftest add-to-tenant-links-user
  (testing "links a tenant-less user to the tenant"
    (let [tid (seed-tenant! "Linking School")]
      (seed-user! "u-new" "new@school.ac.za" nil)
      (let [found (user/find-by-email "new@school.ac.za")]
        (user/add-to-tenant! found tid "actor-uid")
        (is (= 1 (count (user/list-by-tenant tid))))
        (is (= "u-new" (:user/firebase-uid (first (user/list-by-tenant tid)))))))))

(deftest add-to-tenant-idempotent
  (testing "adding a user already in the same tenant does not throw and keeps them linked"
    (let [tid (seed-tenant! "Idempotent School")]
      (seed-user! "u-idem" "idem@school.ac.za" tid)
      (let [found (user/find-by-email "idem@school.ac.za")]
        (is (do (user/add-to-tenant! found tid "actor-uid") true))
        (is (= 1 (count (user/list-by-tenant tid))))))))

(deftest add-to-tenant-allows-multi-tenant
  (testing "a user can belong to multiple tenants"
    (let [tid1 (seed-tenant! "School One")
          tid2 (seed-tenant! "School Two")]
      (seed-user! "u-multi" "multi@school.ac.za" tid1)
      (let [found (user/find-by-email "multi@school.ac.za")]
        (user/add-to-tenant! found tid2 "actor-uid")
        (is (= 1 (count (user/list-by-tenant tid1))))
        (is (= 1 (count (user/list-by-tenant tid2))))))))

;; ---------------------------------------------------------------------------
;; remove-from-tenant!
;; ---------------------------------------------------------------------------

(deftest remove-from-tenant-unlinks-user
  (testing "removes user from the tenant; list-by-tenant no longer returns them"
    (let [tid (seed-tenant! "Removal School")]
      (seed-user! "u-rem" "rem@school.ac.za" tid)
      (is (= 1 (count (user/list-by-tenant tid))))
      (user/remove-from-tenant! "u-rem" tid "actor-uid")
      (is (= 0 (count (user/list-by-tenant tid)))))))

;; ---------------------------------------------------------------------------
;; Role grant/revoke via rbac (integration with user management)
;; ---------------------------------------------------------------------------

(deftest grant-and-revoke-role
  (testing "can grant a role then revoke it"
    (rbac/seed-roles!)
    (let [tid (seed-tenant! "Role School")]
      (seed-user! "u-role" "role@school.ac.za" tid)
      (rbac/grant-role! [:user/firebase-uid "u-role"] :role.name/event-manager :actor "actor-uid")
      (let [members (user/list-by-tenant tid)
            roles (map :role/name (:user/roles (first members)))]
        (is (some #{:role.name/event-manager} roles)))
      (rbac/revoke-role! [:user/firebase-uid "u-role"] :role.name/event-manager :actor "actor-uid")
      (let [members (user/list-by-tenant tid)
            roles (map :role/name (:user/roles (first members)))]
        (is (not (some #{:role.name/event-manager} roles)))))))
