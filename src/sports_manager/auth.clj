(ns sports-manager.auth
  "Firebase authentication: initialise the Admin SDK, verify ID tokens, and map
  a verified uid to a Datomic user. We use the real Firebase Admin SDK
  (com.google.firebase) for token verification — initialised from this
  project's service-account JSON.

  Token transport is a session cookie (see sports-manager.session): the client
  posts an ID token once, we verify it here, then issue a signed cookie."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [sports-manager.config :as config]
            [sports-manager.db :as db])
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
        {:uid   (.getUid decoded)
         :email (.getEmail decoded)
         :name  (.getName decoded)})
      (catch Exception e
        (log/debug e "ID token verification failed")
        nil))))

(defn find-user
  "Pull the user entity for a Firebase uid, or nil.
  Includes roles and their permissions so callers can use rbac/has-permission?."
  [uid]
  (when uid
    (let [e (db/pull [:user/firebase-uid :user/email :user/name
                      :user/status
                      {:user/tenant [:tenant/id :tenant/name]}
                      {:user/roles [:role/name {:role/permissions [:db/ident]}]}]
                     [:user/firebase-uid uid])]
      (when (:user/firebase-uid e) e))))

(defn upsert-user!
  "Create the user on first sign-in (idempotent via :user/firebase-uid identity),
  updating email/name from the token claims. Tenant assignment is handled
  separately (invite/onboarding flow) — a brand-new user has no tenant yet."
  [{:keys [uid email name]}]
  (db/transact! [(cond-> {:user/firebase-uid uid
                          :user/status       :active}
                   email (assoc :user/email email)
                   name  (assoc :user/name name))])
  (find-user uid))
