(ns sports-manager.config
  "Environment-driven configuration. Mirrors the env-var approach used in
  stub-server, scoped with an SM_ prefix. All keys are optional in dev; the
  defaults give an in-memory Datomic db so a bare `clojure -M:run` boots.

  Values come from `dotenv/get-env` (real process env wins over ./.env)."
  (:require [sports-manager.dotenv :refer [get-env]]))

(def datomic-uri
  "Full Datomic connection string. If set, overrides every other datomic-* key.
   e.g. datomic:mem://sports-manager-dev
        datomic:dev://localhost:4334/sports-manager
        datomic:sql://sports-manager?jdbc:postgresql://host:5432/datomic?user=..&password=..&ssl=true"
  (get-env "SM_DATOMIC_URI"))

(def datomic-database
  "Logical database name. Defaults to a dev db."
  (or (get-env "SM_DATOMIC_DATABASE") "sports-manager-dev"))

(def datomic-use-transactor?
  "When true, connect via the dev:// transactor protocol instead of mem://."
  (= "true" (get-env "SM_DATOMIC_USE_TRANSACTOR")))

(def datomic-transactor-host (or (get-env "SM_DATOMIC_TRANSACTOR_HOST") "localhost"))
(def datomic-transactor-port (or (get-env "SM_DATOMIC_TRANSACTOR_PORT") "4334"))

;; --- Production SQL storage (PostgreSQL-backed transactor) ---
(def datomic-host (get-env "SM_DATOMIC_HOST"))
(def datomic-port (or (get-env "SM_DATOMIC_PORT") "5432"))
(def datomic-db (or (get-env "SM_DATOMIC_DB") "datomic"))
(def datomic-user (get-env "SM_DATOMIC_USER"))
(def datomic-password (get-env "SM_DATOMIC_PASSWORD"))

(def prod?
  "Treat anything whose database name contains \"prod\" as production."
  (boolean (and datomic-database (.contains ^String datomic-database "prod"))))

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
