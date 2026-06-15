(ns sports-manager.config
  "Environment-driven configuration. All keys are optional in dev; the defaults
  give an in-memory XTDB node so a bare `clojure -M:run` boots without setup.

  Values come from `dotenv/get-env` (real process env wins over ./.env)."
  (:require [sports-manager.dotenv :refer [get-env]]))

(def xtdb-data-dir
  "Path to the XTDB RocksDB data directory (prod only).
  Defaults to ./data/xtdb — override with SM_XTDB_DATA_DIR."
  (or (get-env "SM_XTDB_DATA_DIR") "data/xtdb"))

(def prod?
  "True when SM_ENV=production."
  (= "production" (get-env "SM_ENV")))

;; --- Firebase auth ---
(def google-application-credentials
  "Path to the Firebase service account JSON. Prefer the app-specific
  SM_FIREBASE_CREDENTIALS so we don't collide with a stale machine-wide
  GOOGLE_APPLICATION_CREDENTIALS (e.g. an old gcloud path)."
  (or (get-env "SM_FIREBASE_CREDENTIALS")
      (get-env "GOOGLE_APPLICATION_CREDENTIALS")))

(def firebase-project-id
  "Firebase project id; verified ID tokens must carry this as their `aud` claim."
  (get-env "FIREBASE_PROJECT_ID"))

;; Public web-app config, rendered into the sign-in page.
(def firebase-api-key (get-env "FIREBASE_API_KEY"))
(def firebase-auth-domain (get-env "FIREBASE_AUTH_DOMAIN"))
(def firebase-app-id (get-env "FIREBASE_APP_ID"))

(def session-secret
  "Secret used to sign the session cookie."
  (or (get-env "SM_SESSION_SECRET") "dev-only-insecure-secret-change-me"))

(def firebase-configured?
  "True when real Firebase creds appear present (not the placeholder)."
  (boolean (and firebase-project-id
                (not= firebase-project-id "REPLACE_ME"))))

(def base-url
  "Public base URL used in QR codes and email links. Defaults to localhost for dev."
  (or (get-env "SM_BASE_URL") "http://localhost:3000"))
