(ns sports-manager.users-routes-test
  "Integration tests for the /users route family (SPO-24).
  Uses a real in-memory Datomic db. Two redefs make the compiled handler testable:
    - session/uid-from-request: returns the :uid we inject directly on the request
    - auth/find-user: reads from the test db via the fixture's redefd db/pull"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [sports-manager.auth :as auth]
            [sports-manager.db :as db]
            [sports-manager.membership :as membership]
            [sports-manager.rbac :as rbac]
            [sports-manager.routes.core :as routes]
            [sports-manager.session :as session]
            [sports-manager.test-db :as test-db]
            [sports-manager.user :as user])
  (:import java.util.UUID))

(use-fixtures :each test-db/with-db)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- seed-tenant! [tenant-name]
  (let [tid (UUID/randomUUID)]
    (db/put-many! [{:xt/id tid :tenant/id tid :tenant/name tenant-name :tenant/status :active}])
    tid))

(defn- seed-user! [uid email tenant-id]
  (db/put-many! [{:xt/id uid :user/firebase-uid uid :user/email email :user/status :active}])
  (when tenant-id
    (membership/create! uid tenant-id))
  uid)

(defn- find-user-from-test-db [uid]
  (when uid
    (let [e (db/pull [:user/firebase-uid :user/email :user/name :user/status :user/roles]
                     uid)]
      (when (:user/firebase-uid e) e))))

(defn- do-req
  ([method uri uid form-params] (do-req method uri uid form-params nil))
  ([method uri uid form-params tenant-id]
   (with-redefs [session/uid-from-request (fn [req] (:uid req))
                 auth/find-user find-user-from-test-db]
     (let [csrf-token "test-csrf-token"
           params (if (= method :post)
                    (assoc (or form-params {}) "__anti-forgery-token" csrf-token)
                    (or form-params {}))
           cookie (test-db/session-cookie
                   (cond-> {:ring.middleware.anti-forgery/anti-forgery-token csrf-token}
                     tenant-id (assoc :active-tenant-id tenant-id)))]
       (routes/handler (cond-> {:request-method method :uri uri :uid uid
                                :headers {"cookie" (str "ring-session=" cookie)}
                                :params params :form-params params}
                         (nil? form-params) (assoc :form-params {})))))))

(defn- GET
  ([uri uid] (do-req :get uri uid {} nil))
  ([uri uid tid] (do-req :get uri uid {} tid)))
(defn- POST
  ([uri uid params] (do-req :post uri uid params nil))
  ([uri uid params tid] (do-req :post uri uid params tid)))
(defn- location [resp] (get-in resp [:headers "Location"]))

;; ---------------------------------------------------------------------------
;; GET /users
;; ---------------------------------------------------------------------------

(deftest users-page-redirects-when-not-logged-in
  (testing "unauthenticated request redirects to /login"
    (let [resp (GET "/users" nil)]
      (is (= 302 (:status resp)))
      (is (= "/login" (location resp))))))

(deftest users-page-redirects-when-no-tenant
  (testing "user with no active tenant session is redirected to select-tenant"
    (seed-user! "uid-notenant" "notable@x.com" nil)
    (let [resp (GET "/users" "uid-notenant")]
      (is (= 302 (:status resp)))
      (is (= "/select-tenant" (location resp))))))

(deftest users-page-renders-for-admin
  (testing "admin with tenant sees the users page with members and add-user form"
    (let [tid (seed-tenant! "Test School")]
      (seed-user! "uid-admin" "admin@x.com" tid)
      (let [resp (GET "/users" "uid-admin" tid)]
        (is (= 200 (:status resp)))
        (is (re-find #"Team members" (:body resp)))
        (is (re-find #"Add a team member" (:body resp)))
        (is (re-find #"admin@x\.com" (:body resp)))))))

;; ---------------------------------------------------------------------------
;; POST /users/add
;; ---------------------------------------------------------------------------

(deftest add-user-blank-email-shows-error
  (testing "blank email re-renders form with validation error"
    (let [tid (seed-tenant! "Validation School")]
      (seed-user! "uid-val" "val@x.com" tid)
      (let [resp (POST "/users/add" "uid-val" {"email" ""} tid)]
        (is (= 200 (:status resp)))
        (is (re-find #"Required" (:body resp)))))))

(deftest add-user-invalid-email-shows-error
  (testing "malformed email re-renders form with validation error"
    (let [tid (seed-tenant! "Validation School 2")]
      (seed-user! "uid-val2" "val2@x.com" tid)
      (let [resp (POST "/users/add" "uid-val2" {"email" "notvalid"} tid)]
        (is (= 200 (:status resp)))
        (is (re-find #"valid email" (:body resp)))))))

(deftest add-user-not-found-shows-error
  (testing "email not in the system shows not-found message"
    (let [tid (seed-tenant! "Not Found School")]
      (seed-user! "uid-nf" "nf@x.com" tid)
      (let [resp (POST "/users/add" "uid-nf" {"email" "nobody@x.com"} tid)]
        (is (= 200 (:status resp)))
        (is (re-find #"No account found" (:body resp)))))))

(deftest add-user-success-redirects
  (testing "adding an existing user redirects to /users and links them to the tenant"
    (let [tid (seed-tenant! "Success School")]
      (seed-user! "uid-admin2" "admin2@x.com" tid)
      (seed-user! "uid-newbie" "newbie@x.com" nil)
      (let [resp (POST "/users/add" "uid-admin2" {"email" "newbie@x.com"} tid)]
        (is (= 302 (:status resp)))
        (is (= "/users" (location resp))))
      (is (= 2 (count (user/list-by-tenant tid)))))))

(deftest add-user-already-in-tenant-is-silent
  (testing "adding a user already in the tenant silently redirects with no error"
    (let [tid (seed-tenant! "Already-in School")]
      (seed-user! "uid-adm3" "adm3@x.com" tid)
      (seed-user! "uid-already" "already@x.com" tid)
      (let [resp (POST "/users/add" "uid-adm3" {"email" "already@x.com"} tid)]
        (is (= 302 (:status resp)))
        (is (= "/users" (location resp)))
        (is (= 2 (count (user/list-by-tenant tid))))))))

;; ---------------------------------------------------------------------------
;; POST /users/:uid/roles
;; ---------------------------------------------------------------------------

(deftest set-roles-grants-checked-roles
  (testing "checked roles are granted; unchecked roles are revoked"
    (rbac/seed-roles!)
    (let [tid (seed-tenant! "Roles School")]
      (seed-user! "uid-adm4" "adm4@x.com" tid)
      (seed-user! "uid-target" "target@x.com" tid)
      (rbac/grant-role! "uid-target" :role.name/event-manager :actor "uid-adm4")
      (let [resp (POST "/users/uid-target/roles" "uid-adm4" {"roles" "finance"} tid)]
        (is (= 200 (:status resp))))
      (let [target (->> (user/list-by-tenant tid)
                        (filter #(= "uid-target" (:user/firebase-uid %)))
                        first)
            role-names (set (map :role/name (:user/roles target)))]
        (is (contains? role-names :role.name/finance))
        (is (not (contains? role-names :role.name/event-manager)))))))

(deftest set-roles-with-no-boxes-revokes-all
  (testing "submitting no checkboxes revokes all roles"
    (rbac/seed-roles!)
    (let [tid (seed-tenant! "Empty Roles School")]
      (seed-user! "uid-adm5" "adm5@x.com" tid)
      (seed-user! "uid-tgt2" "tgt2@x.com" tid)
      (rbac/grant-role! "uid-tgt2" :role.name/finance :actor "uid-adm5")
      (POST "/users/uid-tgt2/roles" "uid-adm5" {} tid)
      (let [target (->> (user/list-by-tenant tid)
                        (filter #(= "uid-tgt2" (:user/firebase-uid %)))
                        first)]
        (is (empty? (:user/roles target)))))))

;; ---------------------------------------------------------------------------
;; POST /users/:uid/remove
;; ---------------------------------------------------------------------------

(deftest remove-user-unlinks-and-redirects
  (testing "removing a user unlinks them from the tenant and redirects to /users"
    (let [tid (seed-tenant! "Remove School")]
      (seed-user! "uid-adm6" "adm6@x.com" tid)
      (seed-user! "uid-tgt3" "tgt3@x.com" tid)
      (is (= 2 (count (user/list-by-tenant tid))))
      (let [resp (POST "/users/uid-tgt3/remove" "uid-adm6" {} tid)]
        (is (= 302 (:status resp)))
        (is (= "/users" (location resp))))
      (is (= 1 (count (user/list-by-tenant tid)))))))
