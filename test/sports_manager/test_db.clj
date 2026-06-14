(ns sports-manager.test-db
  "Test fixture: a fresh in-memory Datomic db per test, with schema installed.
  Mirrors the stub-server approach but isolates each test with a unique mem://
  db so tests don't share state.

  Usage:
    (use-fixtures :each test-db/with-db)
    ;; inside a test, (db/transact! ...) / (db/q ...) work against the temp db."
  (:require [datomic.api :as d]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [ring.middleware.session.store :as store]
            [sports-manager.config :as config]
            [sports-manager.db :as db]
            [sports-manager.schema :as schema]))

(defn with-db
  "clojure.test fixture. Stands up a fresh mem:// db, installs the schema,
  rebinds the db connection for the duration of the test, and tears it down.

  Because the connection lives in a private atom in sports-manager.db, we drive
  it through that namespace's own lifecycle against a per-test database name."
  [f]
  (let [uri (str "datomic:mem://sports-manager-test-" (java.util.UUID/randomUUID))]
    (d/create-database uri)
    (let [conn (d/connect uri)]
      @(d/transact conn schema/schema)
      (with-redefs [db/conn* (constantly conn)
                    db/db (fn [] (d/db conn))]
        (try
          (f)
          (finally
            (d/release conn)
            (d/delete-database uri)))))))

(defn- session-store []
  (let [b (.getBytes ^String config/session-secret "UTF-8")
        k (byte-array 16)]
    (System/arraycopy b 0 k 0 (min 16 (alength b)))
    (cookie-store {:key k})))

(defn session-cookie
  "Encode a session map into a URL-encoded signed cookie value that survives
  wrap-defaults cookie parsing. Pass the result as the 'ring-session' cookie."
  [session-map]
  (java.net.URLEncoder/encode
   (store/write-session (session-store) nil session-map)
   "UTF-8"))
