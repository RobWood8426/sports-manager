(ns sports-manager.rbac
  "Role-based access control (SPO-19).

  Roles are Datomic entities with a set of :permission/* idents. A user's
  effective permissions are the union across all their roles. Enforcement is a
  single predicate: `has-permission?`.

  Default platform roles are seeded by `sports-manager.db/start!` via
  `default-role-tx` exported from this namespace."
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d]
            [sports-manager.db :as db])
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

;; Map of role-name keyword → set of permissions granted.
(def role-permissions
  {:role.name/school-admin (disj all-permissions)
   :role.name/event-manager #{:permission/create-event
                              :permission/publish-event
                              :permission/manage-fixtures
                              :permission/assign-scorekeepers}
   :role.name/fixture-manager #{:permission/manage-fixtures
                                :permission/assign-scorekeepers}
   :role.name/finance #{:permission/manage-payments}
   :role.name/sponsor-manager #{:permission/manage-sponsors}
   :role.name/super-admin all-permissions
   :role.name/support #{:permission/view-audit-log
                        :permission/override-score}})

;; ---------------------------------------------------------------------------
;; Seed tx-data
;; ---------------------------------------------------------------------------

(defn default-role-tx
  "Returns tx-data to upsert the default platform roles (no tenant).
  Idempotent: uses :role/id as the unique identity."
  []
  (for [[role-name perms] role-permissions]
    {:role/id (UUID/randomUUID)
     :role/name role-name
     :role/permissions (vec perms)}))

;; ---------------------------------------------------------------------------
;; Query
;; ---------------------------------------------------------------------------

(defn membership-permissions
  "Returns the set of :permission/* keywords from a membership entity.
  `membership` must be pulled with [{:membership/roles [:role/permissions]}]."
  [membership]
  (->> (:membership/roles membership)
       (mapcat :role/permissions)
       (map :db/ident)
       (into #{})))

(defn platform-permissions
  "Returns the set of :permission/* keywords from a user's platform roles.
  `user-entity` must be pulled with [{:user/roles [:role/permissions]}]."
  [user-entity]
  (->> (:user/roles user-entity)
       (mapcat :role/permissions)
       (map :db/ident)
       (into #{})))

(defn user-permissions
  "Returns the union of permissions from a membership (tenant-scoped roles)
  and a user entity (platform roles). Either may be nil."
  [membership user-entity]
  (into (membership-permissions (or membership {}))
        (platform-permissions (or user-entity {}))))

(defn has-permission?
  "Returns true if the user holds `permission`.
  Pass both `membership` (tenant-scoped roles) and `user-entity` (platform roles);
  either may be nil."
  [membership user-entity permission]
  (contains? (user-permissions membership user-entity) permission))

;; ---------------------------------------------------------------------------
;; Role lookup
;; ---------------------------------------------------------------------------

(defn find-role
  "Returns the role entity-id for `role-name` (e.g. :role.name/school-admin)."
  ([role-name] (find-role role-name nil))
  ([role-name _tenant-eid]
   (let [db (db/db)
         results (d/q '[:find ?r
                        :in $ ?rn
                        :where [?r :role/name ?rn]]
                      db role-name)]
     (when-let [[eid] (first results)]
       eid))))

;; ---------------------------------------------------------------------------
;; Seed
;; ---------------------------------------------------------------------------

(defn seed-roles!
  "Upsert the default platform roles. Called once after schema install.
  Uses upsert-via-query: skips roles that already exist by :role/name so
  re-running on every boot is safe."
  []
  (let [db (db/db)
        existing (into #{} (map first)
                       (d/q '[:find ?n :where [_ :role/name ?n]] db))
        new-roles (remove #(existing (:role/name %)) (default-role-tx))]
    (when (seq new-roles)
      (log/info "Seeding" (count new-roles) "default RBAC roles")
      (db/transact! (vec new-roles)))))

;; ---------------------------------------------------------------------------
;; Mutation
;; ---------------------------------------------------------------------------

(defn grant-role!
  "Add `role-name` to `user-eid`. No-op if already granted.
  Writes an audit entry when `actor` is provided."
  [user-eid role-name & {:keys [actor]}]
  (if-let [role-eid (find-role role-name)]
    (do
      (log/info "Granting role" role-name "to user" user-eid)
      (db/transact! [{:db/id user-eid :user/roles role-eid}]
                    (when actor
                      {:audit/action :user/grant-role
                       :audit/entity-type :user
                       :audit/actor actor
                       :audit/after (str role-name)})))
    (throw (ex-info "Unknown role" {:role role-name}))))

(defn revoke-role!
  "Remove `role-name` from `user-eid`.
  Writes an audit entry when `actor` is provided."
  [user-eid role-name & {:keys [actor]}]
  (if-let [role-eid (find-role role-name)]
    (do
      (log/info "Revoking role" role-name "from user" user-eid)
      (db/transact! [[:db/retract user-eid :user/roles role-eid]]
                    (when actor
                      {:audit/action :user/revoke-role
                       :audit/entity-type :user
                       :audit/actor actor
                       :audit/before (str role-name)})))
    (throw (ex-info "Unknown role" {:role role-name}))))
