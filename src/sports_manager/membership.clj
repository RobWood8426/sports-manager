(ns sports-manager.membership
  "Membership entities — the join between a user and a tenant.

  A user can belong to multiple tenants. This namespace owns:
  - Creating memberships (replaces user/add-to-tenant!)
  - Querying memberships for a user or tenant
  - Removing memberships

  Tenant-scoped roles live on the membership, not on the user directly.
  Platform roles (super-admin, support) stay on :user/roles."
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d]
            [sports-manager.db :as db])
  (:import java.util.Date
           java.util.UUID))

(def pull-pattern
  [:membership/id
   :membership/status
   :membership/joined-at
   {:membership/tenant [:tenant/id :tenant/name :tenant/city :tenant/country]}
   {:membership/roles [:role/name {:role/permissions [:db/ident]}]}])

(defn find-by-user-and-tenant
  "Returns the membership entity for (uid, tenant-id), or nil."
  [uid tenant-id]
  (let [db (db/db)
        result (d/q '[:find ?m
                      :in $ ?uid ?tid
                      :where
                      [?u :user/firebase-uid ?uid]
                      [?t :tenant/id ?tid]
                      [?m :membership/user ?u]
                      [?m :membership/tenant ?t]]
                    db uid tenant-id)]
    (when-let [[eid] (first result)]
      (db/pull pull-pattern eid))))

(defn list-active-by-user
  "Returns all active memberships for a user (by firebase uid)."
  [uid]
  (let [db (db/db)
        eids (d/q '[:find [?m ...]
                    :in $ ?uid
                    :where
                    [?u :user/firebase-uid ?uid]
                    [?m :membership/user ?u]
                    [?m :membership/status :membership.status/active]]
                  db uid)]
    (mapv #(db/pull pull-pattern %) eids)))

(defn list-active-by-tenant
  "Returns all active memberships for a tenant (by tenant-id UUID)."
  [tenant-id]
  (let [db (db/db)
        eids (d/q '[:find [?m ...]
                    :in $ ?tid
                    :where
                    [?t :tenant/id ?tid]
                    [?m :membership/tenant ?t]
                    [?m :membership/status :membership.status/active]]
                  db tenant-id)]
    (mapv (fn [eid]
            (merge (db/pull pull-pattern eid)
                   (db/pull [:user/firebase-uid :user/email :user/name :user/status]
                            (first (first (d/q '[:find ?u :in $ ?m :where [?m :membership/user ?u]] (db/db) eid))))))
          eids)))

(defn create!
  "Create a membership linking uid to tenant-id. Idempotent:
  - If an active membership already exists, no-op.
  - If a disabled membership exists, re-activates it.
  - Otherwise creates a new membership."
  [uid tenant-id & {:keys [actor]}]
  (let [db (db/db)
        user-eid (d/entid db [:user/firebase-uid uid])
        tenant-eid (d/entid db [:tenant/id tenant-id])]
    (when-not user-eid
      (throw (ex-info "User not found" {:uid uid})))
    (when-not tenant-eid
      (throw (ex-info "Tenant not found" {:tenant-id tenant-id})))
    (let [existing (find-by-user-and-tenant uid tenant-id)]
      (cond
        (= :membership.status/active (:membership/status existing))
        nil

        (some? existing)
        (do
          (log/info "Re-activating membership" uid "->" tenant-id)
          (db/transact! [{:db/id [:membership/id (:membership/id existing)]
                          :membership/status :membership.status/active}]
                        (when actor
                          {:audit/action :user/add-to-tenant
                           :audit/entity-type :user
                           :audit/actor actor
                           :audit/after (str tenant-id)})))

        :else
        (do
          (log/info "Creating membership" uid "->" tenant-id)
          (db/transact! [{:membership/id (UUID/randomUUID)
                          :membership/user [:user/firebase-uid uid]
                          :membership/tenant [:tenant/id tenant-id]
                          :membership/status :membership.status/active
                          :membership/joined-at (Date.)}]
                        (when actor
                          {:audit/action :user/add-to-tenant
                           :audit/entity-type :user
                           :audit/actor actor
                           :audit/after (str tenant-id)})))))))

(defn disable!
  "Mark a membership as disabled (soft delete)."
  [uid tenant-id & {:keys [actor]}]
  (let [db (db/db)
        result (d/q '[:find ?m
                      :in $ ?uid ?tid
                      :where
                      [?u :user/firebase-uid ?uid]
                      [?t :tenant/id ?tid]
                      [?m :membership/user ?u]
                      [?m :membership/tenant ?t]]
                    db uid tenant-id)]
    (if-let [[eid] (first result)]
      (do
        (log/info "Disabling membership" uid "->" tenant-id)
        (db/transact! [{:db/id eid :membership/status :membership.status/disabled}]
                      (when actor
                        {:audit/action :user/remove-from-tenant
                         :audit/entity-type :user
                         :audit/actor actor
                         :audit/after (str tenant-id)})))
      (throw (ex-info "Membership not found" {:uid uid :tenant-id tenant-id})))))

(defn active?
  "Returns the membership map if uid has an active membership in tenant-id, else nil."
  [uid tenant-id]
  (when-let [m (find-by-user-and-tenant uid tenant-id)]
    (when (= :membership.status/active (:membership/status m)) m)))
