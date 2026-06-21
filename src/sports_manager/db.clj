(ns sports-manager.db
  "XTDB v1 node lifecycle and thin query/transaction helpers.

  Identity model: every entity's existing business key (:fixture/id,
  :user/firebase-uid, :sport-template/code, etc.) IS its :xt/id. Refs store
  the target's :xt/id value directly — no numeric eids.

  RocksDB-backed under SM_XTDB_DATA_DIR (default: data/xtdb) in dev and prod.
  Pass {:in-memory? true} to start! for an isolated in-memory node — used by
  the test suite so test runs never touch the dev data dir or its lock file."
  (:require [clojure.tools.logging :as log]
            [sports-manager.config :as config]
            [xtdb.api :as xt])
  (:import [java.io Closeable]
           [java.time Duration]))

(defonce ^:private node (atom nil))

;; ---------------------------------------------------------------------------
;; Node configuration
;; ---------------------------------------------------------------------------

(defn- node-config [{:keys [in-memory?]}]
  (if in-memory?
    (let [kv {:xtdb/module 'xtdb.mem-kv/->kv-store}]
      {:xtdb/tx-log {:kv-store kv}
       :xtdb/document-store {:kv-store kv}
       :xtdb/index-store {:kv-store kv}})
    (let [dir config/xtdb-data-dir
          kv (fn [sub] {:xtdb/module 'xtdb.rocksdb/->kv-store
                        :db-dir (str dir "/" sub)})]
      {:xtdb/tx-log {:kv-store (kv "tx-log")}
       :xtdb/document-store {:kv-store (kv "docs")}
       :xtdb/index-store {:kv-store (kv "index")}})))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn start!
  "Start the XTDB node. Idempotent. Optional seed-fns (zero-arg) run once
  after node start -- used to seed reference data without circular deps.
  Optional opts map supports {:in-memory? true} for a throwaway node."
  ([] (start! nil nil))
  ([seed-fns] (start! seed-fns nil))
  ([seed-fns opts]
   (when (nil? @node)
     (reset! node (xt/start-node (node-config opts)))
     (log/info "Started XTDB node" (if (:in-memory? opts)
                                      "(in-memory)"
                                      (str "(rocksdb) " config/xtdb-data-dir)))
     (doseq [f seed-fns] (f)))
   @node))

(defn stop!
  "Close the node. Safe when nothing is running."
  []
  (when-let [^Closeable n @node]
    (.close n)
    (reset! node nil)))

(defn node* "The live node (throws if not started)." []
  (or @node (throw (ex-info "XTDB not started -- call (start!) first" {}))))

(defn db "A current database snapshot." [] (xt/db (node*)))

;; ---------------------------------------------------------------------------
;; Query helpers
;; ---------------------------------------------------------------------------

(defn q
  "Run a Datalog query against the current db. Extra args bound via :in."
  [query & args]
  (apply xt/q (db) query args))

(defn q-on
  "Run a Datalog query against a specific db snapshot."
  [db-val query & args]
  (apply xt/q db-val query args))

;; ---------------------------------------------------------------------------
;; Entity / pull helpers
;; ---------------------------------------------------------------------------

(defn entity
  "Fetch the full document for :xt/id `id`, or nil if absent."
  [id]
  (xt/entity (db) id))

(defn entity-on
  "Fetch document by :xt/id against a specific db snapshot."
  [db-val id]
  (xt/entity db-val id))

(defn pull
  "EQL pull of `selector` for the entity with :xt/id `id`.
  Always includes :xt/id so pulled docs can safely be re-put.
  Returns the projected map, or nil if the entity doesn't exist."
  [selector id]
  (when id
    (let [sel (if (some #{:xt/id} selector)
                selector
                (into [:xt/id] selector))]
      (xt/pull (db) sel id))))

(defn pull-many
  "EQL pull of `selector` for each id in `ids`."
  [selector ids]
  (xt/pull-many (db) selector ids))

(defn exists?
  "True if an entity with :xt/id `id` currently exists."
  [id]
  (some? (xt/entity (db) id)))

;; ---------------------------------------------------------------------------
;; Write helpers
;; ---------------------------------------------------------------------------

(defn- await-tx [tx]
  (xt/await-tx (node*) tx (Duration/ofSeconds 30))
  tx)

(defn submit!
  "Submit raw XTDB tx-ops and block until indexed.
  ops is a vector of XTDB ops: [[::xt/put doc] [::xt/delete id] ...]"
  [ops]
  (await-tx (xt/submit-tx (node*) ops)))

(defn put!
  "Upsert a full document. doc must contain :xt/id. Blocks until indexed."
  [doc]
  (submit! [[::xt/put doc]]))

(defn put-many!
  "Upsert multiple documents in a single transaction."
  [docs]
  (submit! (mapv (fn [d] [::xt/put d]) docs)))

(defn merge!
  "Read-modify-write: merge attrs onto the existing doc with :xt/id id.
  Throws if the entity doesn't exist. Use put! for upsert-or-create."
  [id attrs]
  (let [cur (xt/entity (db) id)]
    (when-not cur
      (throw (ex-info "Entity not found for update" {:xt/id id})))
    (submit! [[::xt/put (merge cur attrs)]])))

(defn retract-attrs!
  "Remove attributes ks from the document with :xt/id id."
  [id ks]
  (when-let [cur (xt/entity (db) id)]
    (submit! [[::xt/put (apply dissoc cur ks)]])))

(defn delete!
  "Delete the entity with :xt/id id."
  [id]
  (submit! [[::xt/delete id]]))

(defn delete-many!
  "Delete multiple entities by id in a single transaction."
  [ids]
  (submit! (mapv (fn [id] [::xt/delete id]) ids)))

;; ---------------------------------------------------------------------------
;; Multi-tenancy
;; ---------------------------------------------------------------------------

(defn tenant-scoped
  "Run a Datalog query scoped to tenant-id. tenant-attr is the attribute
  linking entities to their tenant (e.g. :event/tenant, :fixture/tenant).
  find defaults to [?e].

  Example:
    (tenant-scoped :event/tenant tenant-id '[[?e :event/name ?n]])"
  ([tenant-attr tenant-id where]
   (tenant-scoped tenant-attr tenant-id '[?e] where))
  ([tenant-attr tenant-id find-spec where]
   (let [query {:find (vec find-spec)
                :in '[?tid]
                :where (into [['?e tenant-attr '?tid]] where)}]
     (xt/q (db) query tenant-id))))
