(ns sports-manager.user
  "Tenant user management (SPO-24).

  Covers listing users within a tenant, adding an existing user (one who has
  already signed in) by email, and removing them. Role assignment/revocation is
  delegated to sports-manager.rbac.

  add-to-tenant!/remove-from-tenant!/list-by-tenant delegate to
  sports-manager.membership — the membership entity is the source of truth."
  (:require [clojure.string :as str]
            [sports-manager.db :as db]
            [sports-manager.membership :as membership]))

;; ---------------------------------------------------------------------------
;; Query
;; ---------------------------------------------------------------------------

(defn find-by-email
  "Returns the user entity map for `email`, or nil if not found.
  Pulls only identity attributes -- not tenant/roles."
  [email]
  (when-let [uid (ffirst (db/q '{:find [?uid] :in [?e] :where [[?u :user/email ?e] [?u :user/firebase-uid ?uid]]} email))]
    (db/pull [:user/firebase-uid :user/email :user/name :user/status] uid)))

(defn list-by-tenant
  "Returns all users with an active membership in `tenant-id` (a UUID),
  each with their roles pulled as nested maps."
  [tenant-id]
  (mapv (fn [m]
          (db/pull [:user/firebase-uid :user/email :user/name :user/status
                    {:user/roles [:role/id :role/name :role/permissions]}]
                   (:user/firebase-uid m)))
        (membership/list-active-by-tenant tenant-id)))

;; ---------------------------------------------------------------------------
;; Mutation
;; ---------------------------------------------------------------------------

(defn add-to-tenant!
  "Create a membership linking `user` (result of `find-by-email`) to `tenant-id`.
  Idempotent if already a member; throws if membership cannot be created."
  [user tenant-id actor]
  (membership/create! (:user/firebase-uid user) tenant-id :actor actor))

(defn remove-from-tenant!
  "Disable the membership linking `uid` to `tenant-id`."
  [uid tenant-id actor]
  (membership/disable! uid tenant-id :actor actor))

(defn validate-add
  "Returns a map of field → error for the add-user form. Empty = valid."
  [{:keys [email]}]
  (cond-> {}
    (str/blank? email) (assoc :email "Required")
    (and (not (str/blank? email))
         (not (re-matches #".+@.+\..+" email))) (assoc :email "Enter a valid email address")))
