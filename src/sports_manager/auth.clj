(ns sports-manager.auth
  "Firebase authentication: initialise the Admin SDK, verify ID tokens, and map
  a verified uid to an XTDB user. We use the real Firebase Admin SDK
  (com.google.firebase) for token verification — initialised from this
  project's service-account JSON.

  Token transport is a session cookie (see sports-manager.session): the client
  posts an ID token once, we verify it here, then issue a signed cookie."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [sports-manager.config :as config]
            [sports-manager.db :as db]
            [sports-manager.invite :as invite])
  (:import (com.google.auth.oauth2 GoogleCredentials)
           (com.google.firebase FirebaseApp FirebaseOptions)
           (com.google.firebase.auth FirebaseAuth)))

(defonce ^:private initialised? (atom false))

(defn init!
  "Initialise the Firebase Admin SDK from the service-account JSON pointed to by
  GOOGLE_APPLICATION_CREDENTIALS. Idempotent. No-op (with a warning) when
  Firebase isn't configured yet, so the app still boots with placeholder creds."
  []
  (cond
    @initialised? true

    (not config/firebase-configured?)
    (log/warn "Firebase not configured (FIREBASE_PROJECT_ID unset/placeholder) — auth disabled")

    :else
    (try
      (with-open [sa (io/input-stream config/google-application-credentials)]
        (let [opts (-> (FirebaseOptions/builder)
                       (.setCredentials (GoogleCredentials/fromStream sa))
                       (.setProjectId config/firebase-project-id)
                       (.build))]
          ;; Guard against double-init across REPL reloads.
          (when (empty? (FirebaseApp/getApps))
            (FirebaseApp/initializeApp opts))
          (reset! initialised? true)
          (log/info "Firebase initialised for project" config/firebase-project-id)))
      (catch Exception e
        (log/error e "Firebase init failed — auth will reject tokens")
        false))))

(defn verify-token
  "Verify a Firebase ID token. Returns a claims map {:uid :email :name} on
  success, or nil if invalid / Firebase not initialised."
  [^String token]
  (when (and token @initialised?)
    (try
      (let [decoded (-> (FirebaseAuth/getInstance) (.verifyIdToken token))]
        {:uid (.getUid decoded)
         :email (.getEmail decoded)
         :name (.getName decoded)})
      (catch Exception e
        (log/debug e "ID token verification failed")
        nil))))

(defn find-user
  "Pull the user entity for a Firebase uid, or nil.
  :xt/id IS the firebase-uid string — pull directly by uid.
  Includes tenant UUID and role UUIDs (flat scalars, not nested joins)."
  [uid]
  (when uid
    (let [e (db/pull [:user/firebase-uid :user/email :user/name
                      :user/status
                      :user/tenant
                      :user/roles]
                     uid)]
      (when (:user/firebase-uid e) e))))

(defn upsert-user!
  "Create the user on first sign-in, or update email/name from token claims.
  Uses db/put! for new users, db/merge! for existing.
  Tenant assignment is handled separately (invite/onboarding flow)."
  [{:keys [uid email name]}]
  (let [attrs (cond-> {:user/firebase-uid uid
                       :user/status :active}
                email (assoc :user/email email)
                name (assoc :user/name name))
        new-user? (not (db/exists? uid))]
    (if new-user?
      (db/put! (assoc attrs :xt/id uid))
      (db/merge! uid attrs))
    (when new-user?
      (invite/redeem-pending! uid email))
    (find-user uid)))
