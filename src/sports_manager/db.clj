(ns sports-manager.db
  "Datomic connection lifecycle, schema install, and thin query/transaction
  helpers. Adapted from the stub-server pattern (com.datomic/peer) for this
  deps.edn repo: defaults to an in-memory db so a bare boot needs no transactor.

  Connection is held in a `defonce` atom and is idempotent to start. Prod uses a
  SQL-backed transactor configured entirely through env vars (see config.clj)."
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d]
            [sports-manager.config :as config]
            [sports-manager.schema :as schema]))

(defonce ^:private conn (atom nil))

(defn uri
  "Build the Datomic connection URI from config.
   Precedence: explicit SM_DATOMIC_URI > SQL (prod) > dev transactor > mem://."
  []
  (cond
    config/datomic-uri
    config/datomic-uri

    config/prod?
    (format "datomic:sql://%s?jdbc:postgresql://%s:%s/%s?user=%s&password=%s&ssl=true&sslmode=require"
            config/datomic-database
            config/datomic-host config/datomic-port config/datomic-db
            config/datomic-user config/datomic-password)

    config/datomic-use-transactor?
    (format "datomic:dev://%s:%s/%s"
            config/datomic-transactor-host config/datomic-transactor-port
            config/datomic-database)

    :else
    (str "datomic:mem://" config/datomic-database)))

(defn install-schema!
  "Transact the schema. Idempotent — Datomic schema is additive, so re-running
  on every boot is safe and is how we 'migrate'."
  []
  (log/info "Installing/updating Datomic schema…")
  @(d/transact @conn schema/schema)
  (log/info "Schema install complete."))

(defn start!
  "Create the database if needed, connect, install the schema. Idempotent:
  a no-op if already connected. Returns the connection.

  Pass optional `seed-fns` — zero-arg fns called after schema install, used to
  seed reference data (e.g. default RBAC roles) without creating a circular dep
  between db and domain namespaces."
  ([] (start! nil))
  ([seed-fns]
   (when (nil? @conn)
     (let [u (uri)]
       (when (d/create-database u)
         (log/info "Created Datomic database:" config/datomic-database))
       (reset! conn (d/connect u))
       (log/info "Connected to Datomic:" u)
       (install-schema!)
       (doseq [f seed-fns] (f))))
   @conn))

(defn stop!
  "Release the connection. For mem:// also deletes the database so a restart is
  clean. Safe to call when nothing is connected."
  []
  (when @conn
    (let [u (uri)]
      (d/release @conn)
      (when (.startsWith ^String u "datomic:mem://")
        (d/delete-database u)))
    (reset! conn nil)))

(defn conn* "The live connection (throws if not started)." []
  (or @conn (throw (ex-info "Datomic not started — call (start!) first" {}))))

(defn db "A current database value." [] (d/db (conn*)))

;; --- Thin wrappers (stub-server style) so callers don't import datomic.api ---

(defn q       [query & args] (apply d/q query (db) args))
(defn q-on    [db query & args] (apply d/q query db args))
(defn pull    [selector eid] (d/pull (db) selector eid))
(defn entity  [eid] (d/entity (db) eid))

(defn transact!
  "Transact tx-data against the live connection; deref so callers get the
  completed tx report. Pass an `:audit` map to also write provenance onto the
  transaction entity."
  ([tx-data] @(d/transact (conn*) tx-data))
  ([tx-data audit]
   @(d/transact (conn*) (into [(assoc audit :db/id "datomic.tx")] tx-data))))

;; --- Multi-tenancy (SPO-17) -------------------------------------------------
;; Per-entity isolation: every tenant-scoped query goes through `tenant-scoped`
;; so the :tenant/id filter can't be forgotten. The query's :find/:where are
;; spliced in; the entity bound to `?e` is constrained to the given tenant.

(defn tenant-scoped
  "Run a query already constrained to a tenant. `where` is the where-clauses for
  your query; every result entity `?e` is forced to belong to `tenant-id`.
  `find` is the :find spec (defaults to pulling entity ids).

  (tenant-scoped tenant-id
    '[?e]
    '[[?e :event/name ?n]])"
  ([tenant-id where] (tenant-scoped tenant-id '[?e] where))
  ([tenant-id find where]
   (let [query (vec (concat [:find] find
                            '[:in $ ?tid]
                            [:where '[?e :tenant/id ?tid]]
                            where))]
     (d/q query (db) tenant-id))))
