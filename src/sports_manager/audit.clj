(ns sports-manager.audit
  "Query helpers for the immutable audit log (SPO-20).

  Audit entries are written by fixture, scorekeeper-code, final-score, user,
  and rbac namespaces. This namespace provides read-only query access.
  Entries are never retracted or modified."
  (:require [sports-manager.db :as db]))

(def ^:private pull-pattern
  [:audit/id
   :audit/action
   :audit/entity-type
   :audit/entity-id
   :audit/actor
   :audit/before
   :audit/after
   :audit/reason
   :audit/at
   :audit/tenant])

(defn- ids->entries [ids]
  (->> ids
       (mapv #(db/pull pull-pattern %))
       (filter :audit/id)
       (sort-by :audit/at #(compare %2 %1))))

(defn list-by-tenant
  "Return all audit entries for a tenant, newest first.
  `limit` caps the result (default 200) to avoid unbounded pulls."
  ([tenant-id] (list-by-tenant tenant-id 200))
  ([tenant-id limit]
   (let [ids (map first (db/q '{:find  [?a]
                                :in    [?tid]
                                :where [[?a :audit/tenant ?tid]]}
                               tenant-id))]
     (->> (ids->entries ids)
          (take limit)
          vec))))

(defn list-by-entity
  "Return all audit entries for a specific entity UUID, newest first."
  [entity-id]
  (let [ids (map first (db/q '{:find  [?a]
                               :in    [?eid]
                               :where [[?a :audit/entity-id ?eid]]}
                              entity-id))]
    (ids->entries ids)))

(defn list-by-actor
  "Return all audit entries for a specific actor (firebase UID), newest first."
  ([actor-uid] (list-by-actor actor-uid 200))
  ([actor-uid limit]
   (let [ids (map first (db/q '{:find  [?a]
                                :in    [?actor]
                                :where [[?a :audit/actor ?actor]]}
                               actor-uid))]
     (->> (ids->entries ids)
          (take limit)
          vec))))

(defn list-by-action
  "Return all audit entries for a specific action keyword within a tenant, newest first."
  [tenant-id action]
  (let [ids (map first (db/q '{:find  [?a]
                               :in    [?tid ?action]
                               :where [[?a :audit/tenant ?tid]
                                       [?a :audit/action ?action]]}
                              tenant-id action))]
    (ids->entries ids)))
