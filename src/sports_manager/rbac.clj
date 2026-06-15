(ns sports-manager.rbac
  "Role-based access control (SPO-19).

  Roles are XTDB documents with a set of :permission/* keywords. A user's
  effective permissions are the union across all their roles. Enforcement is a
  single predicate: has-permission?.

  Default platform roles are seeded by sports-manager.db/start! via seed-roles!."
  (:require [clojure.tools.logging :as log]
            [sports-manager.db :as db]
            [xtdb.api :as xt])
  (:import java.util.UUID))

;; ---------------------------------------------------------------------------
;; Role definitions
;; ---------------------------------------------------------------------------

(def all-permissions
  #{:permission/create-event
    :permission/publish-event
    :permission/manage-fixtures
    :permission/assign-scorekeepers
    :permission/override-score
    :permission/manage-payments
    :permission/manage-sponsors
    :permission/view-audit-log
    :permission/manage-users
    :permission/manage-tenant})

(def role-permissions
  {:role.name/school-admin   all-permissions
   :role.name/event-manager  #{:permission/create-event
                               :permission/publish-event
                               :permission/manage-fixtures
                               :permission/assign-scorekeepers}
   :role.name/fixture-manager #{:permission/manage-fixtures
                                :permission/assign-scorekeepers}
   :role.name/finance        #{:permission/manage-payments}
   :role.name/sponsor-manager #{:permission/manage-sponsors}
   :role.name/super-admin    all-permissions
   :role.name/support        #{:permission/view-audit-log
                               :permission/override-score}})

;; ---------------------------------------------------------------------------
;; Query / permission helpers
;; ---------------------------------------------------------------------------

(defn membership-permissions
  "Returns the set of :permission/* keywords from a membership entity.
  membership must carry :membership/roles as a collection of role :xt/ids."
  [membership]
  (->> (:membership/roles membership)
       (mapcat (fn [role-id]
                 (:role/permissions (db/entity role-id))))
       (into #{})))

(defn platform-permissions
  "Returns the set of :permission/* keywords from a user's platform roles.
  user-entity must carry :user/roles as a collection of role :xt/ids."
  [user-entity]
  (->> (:user/roles user-entity)
       (mapcat (fn [role-id]
                 (:role/permissions (db/entity role-id))))
       (into #{})))

(defn user-permissions
  "Returns the union of permissions from a membership (tenant-scoped roles)
  and a user entity (platform roles). Either may be nil."
  [membership user-entity]
  (into (membership-permissions (or membership {}))
        (platform-permissions (or user-entity {}))))

(defn has-permission?
  "Returns true if the user holds permission.
  Pass both membership (tenant-scoped roles) and user-entity (platform roles);
  either may be nil."
  [membership user-entity permission]
  (contains? (user-permissions membership user-entity) permission))

;; ---------------------------------------------------------------------------
;; Role lookup
;; ---------------------------------------------------------------------------

(defn find-role-id
  "Returns the :xt/id (UUID) for role-name (e.g. :role.name/school-admin), or nil."
  [role-name]
  (ffirst (db/q '{:find  [?id]
                  :in    [?rn]
                  :where [[?r :role/name ?rn]
                          [?r :role/id ?id]]}
                role-name)))

;; ---------------------------------------------------------------------------
;; Seed
;; ---------------------------------------------------------------------------

(defn seed-roles!
  "Upsert the default platform roles. Idempotent -- skips roles that already exist."
  []
  (let [existing (into #{} (map first)
                       (db/q '{:find  [?n]
                               :where [[_ :role/name ?n]]}))
        new-roles (remove #(existing (key %)) role-permissions)]
    (when (seq new-roles)
      (log/info "Seeding" (count new-roles) "default RBAC roles")
      (db/put-many!
       (mapv (fn [[role-name perms]]
               (let [id (UUID/randomUUID)]
                 {:xt/id id
                  :role/id id
                  :role/name role-name
                  :role/permissions (set perms)}))
             new-roles)))))

;; ---------------------------------------------------------------------------
;; Mutation
;; ---------------------------------------------------------------------------

(defn grant-role!
  "Add role-name to user-id (firebase-uid). No-op if already granted."
  [user-id role-name & {:keys [actor]}]
  (if-let [role-id (find-role-id role-name)]
    (let [user (db/entity user-id)
          _ (when-not user
              (throw (ex-info "User not found" {:user/firebase-uid user-id})))
          current-roles (set (:user/roles user))]
      (when-not (current-roles role-id)
        (log/info "Granting role" role-name "to user" user-id)
        (db/submit!
         [[::xt/put (assoc user :user/roles (conj current-roles role-id))]
          [::xt/put (cond-> {:xt/id (UUID/randomUUID)
                             :audit/id (UUID/randomUUID)
                             :audit/action :user/grant-role
                             :audit/entity-type :user
                             :audit/actor (or actor "")
                             :audit/after (str role-name)}
                      actor (assoc :audit/actor actor))]]))
      nil)
    (throw (ex-info "Unknown role" {:role role-name}))))

(defn revoke-role!
  "Remove role-name from user-id (firebase-uid)."
  [user-id role-name & {:keys [actor]}]
  (if-let [role-id (find-role-id role-name)]
    (let [user (db/entity user-id)
          _ (when-not user
              (throw (ex-info "User not found" {:user/firebase-uid user-id})))
          current-roles (set (:user/roles user))]
      (log/info "Revoking role" role-name "from user" user-id)
      (db/submit!
       [[::xt/put (assoc user :user/roles (disj current-roles role-id))]
        [::xt/put (cond-> {:xt/id (UUID/randomUUID)
                           :audit/id (UUID/randomUUID)
                           :audit/action :user/revoke-role
                           :audit/entity-type :user
                           :audit/actor (or actor "")
                           :audit/before (str role-name)}
                    actor (assoc :audit/actor actor))]]))
    (throw (ex-info "Unknown role" {:role role-name}))))
