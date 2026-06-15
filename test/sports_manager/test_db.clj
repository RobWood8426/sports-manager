(ns sports-manager.test-db
  "Test fixture: a fresh in-memory XTDB node per test.

  Usage:
    (use-fixtures :each test-db/with-db)
    ;; inside a test, (db/put-many! ...) / (db/q ...) work against the temp node."
  (:require [ring.middleware.session.cookie :refer [cookie-store]]
            [ring.middleware.session.store :as store]
            [sports-manager.config :as config]
            [sports-manager.db :as db]))

(defn with-db
  "clojure.test fixture. Starts a fresh in-memory XTDB node, runs the test,
  then stops and clears the node."
  [f]
  (db/start!)
  (try
    (f)
    (finally
      (db/stop!))))

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
