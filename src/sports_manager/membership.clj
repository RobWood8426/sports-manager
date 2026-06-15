(ns sports-manager.membership
  "Membership entities — the join between a user and a tenant.

  A user can belong to multiple tenants. This namespace owns:
  - Creating memberships (replaces user/add-to-tenant!)
  - Querying memberships for a user or tenant
  - Removing memberships

  :xt/id for a membership is the string \"<uid>|<tenant-id>\".
  Tenant-scoped roles live on the membership, not on the user directly.
  Platform roles (super-admin, support) stay on :user/roles."
  (:require [clojure.tools.logging :as log]
            [sports-manager.db :as db])
  (:import java.util.Date
           java.util.UUID))

(defn- membership-id [uid tenant-id]
  (str uid "|" tenant-id))

(def pull-pattern
  "Flat pull pattern — :membership/tenant is a UUID scalar,
  :membership/roles is a set of role UUIDs. No nested join maps."
  [:membership/id
   :membership/status
   :membership/joined-at
   :membership/tenant
   :membership/roles])

(defn find-by-user-and-tenant
  "Returns the membership entity for (uid, tenant-id), or nil."
  [uid tenant-id]
  (db/entity (membership-id uid tenant-id)))

(defn list-active-by-user
  "Returns all active memberships for a user (by firebase uid)."
  [uid]
  (let [pairs (db/q '{:find [?uid ?tid]
                      :in [?uid]
                      :where [[?m :membership/user ?uid]
                              [?m :membership/tenant ?tid]
                              [?m :membership/status :membership.status/active]]}
                    uid)]
    (mapv (fn [[u t]] (db/entity (membership-id u t))) pairs)))

(defn list-active-by-tenant
  "Returns all active memberships for a tenant (by tenant-id UUID),
  each merged with the user's profile attributes."
  [tenant-id]
  (let [pairs (db/q '{:find [?uid ?tid]
                      :in [?tid]
                      :where [[?m :membership/user ?uid]
                              [?m :membership/tenant ?tid]
                              [?m :membership/status :membership.status/active]]}
                    tenant-id)]
    (mapv (fn [[uid tid]]
            (merge (db/entity (membership-id uid tid))
                   (db/pull [:user/firebase-uid :user/email :user/name :user/status] uid)))
          pairs)))

(defn- write-audit!
  "Persist an audit entry as its own XTDB document."
  [action actor entity-type after]
  (when actor
    (db/put! {:xt/id (UUID/randomUUID)
              :audit/action action
              :audit/actor actor
              :audit/entity-type entity-type
              :audit/after after})))

(defn create!
  "Create a membership linking uid to tenant-id. Idempotent:
  - If an active membership already exists, no-op.
  - If a disabled membership exists, re-activates it.
  - Otherwise creates a new membership."
  [uid tenant-id & {:keys [actor]}]
  (when-not (db/exists? uid)
    (throw (ex-info "User not found" {:uid uid})))
  (when-not (db/exists? tenant-id)
    (throw (ex-info "Tenant not found" {:tenant-id tenant-id})))
  (let [mid (membership-id uid tenant-id)
        existing (find-by-user-and-tenant uid tenant-id)]
    (cond
      (= :membership.status/active (:membership/status existing))
      nil

      (some? existing)
      (do
        (log/info "Re-activating membership" uid "->" tenant-id)
        (db/merge! mid {:membership/status :membership.status/active})
        (write-audit! :user/add-to-tenant actor :user (str tenant-id)))

      :else
      (do
        (log/info "Creating membership" uid "->" tenant-id)
        (db/put! {:xt/id mid
                  :membership/id (UUID/randomUUID)
                  :membership/user uid
                  :membership/tenant tenant-id
                  :membership/status :membership.status/active
                  :membership/joined-at (Date.)})
        (write-audit! :user/add-to-tenant actor :user (str tenant-id))))))

(defn disable!
  "Mark a membership as disabled (soft delete)."
  [uid tenant-id & {:keys [actor]}]
  (let [mid (membership-id uid tenant-id)]
    (if (db/exists? mid)
      (do
        (log/info "Disabling membership" uid "->" tenant-id)
        (db/merge! mid {:membership/status :membership.status/disabled})
        (write-audit! :user/remove-from-tenant actor :user (str tenant-id)))
      (throw (ex-info "Membership not found" {:uid uid :tenant-id tenant-id})))))

(defn active?
  "Returns the membership map if uid has an active membership in tenant-id, else nil."
  [uid tenant-id]
  (when-let [m (find-by-user-and-tenant uid tenant-id)]
    (when (= :membership.status/active (:membership/status m)) m)))
