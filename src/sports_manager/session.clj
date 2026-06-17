(ns sports-manager.session
  "Signed session cookies + Ring session state.

  Identity (uid) rides in a signed JWS cookie (sm_session) — tamper-proof,
  14-day lifetime. The active tenant selection rides in the Ring cookie-store
  session map — mutable per-session state, cleared on sign-out."
  (:require [buddy.sign.jwt :as jwt]
            [clojure.tools.logging :as log]
            [sports-manager.config :as config]))

(def ^:private cookie-name "sm_session")
(def ^:private max-age-seconds (* 60 60 24 14)) ; 14 days

(defn- sign [claims]
  (jwt/sign claims config/session-secret))

(defn- unsign [token]
  (try (jwt/unsign token config/session-secret)
       (catch Exception e (log/debug e "bad session cookie") nil)))

(defn cookie
  "Build a Set-Cookie map for the given uid."
  [uid]
  {cookie-name {:value (sign {:uid uid})
                :http-only true
                :same-site :lax
                :path "/"
                :secure config/prod?
                :max-age max-age-seconds}})

(def clear-cookie
  {cookie-name {:value "" :path "/" :max-age 0}})

(defn uid-from-request
  "Read and verify the session cookie, returning the uid or nil."
  [request]
  (some-> request :cookies (get cookie-name) :value unsign :uid))

(defn wrap-session-identity
  "Attach :uid (from the signed cookie) to the request when present. Does not
  reject — that's wrap-authenticated's job — so public routes still see it."
  [handler]
  (fn [request]
    (handler (assoc request :uid (uid-from-request request)))))

(defn wrap-authenticated
  "Gate a handler: 401 (or redirect to /login for GETs) unless a valid session
  cookie is present."
  [handler]
  (fn [request]
    (if (:uid request)
      (handler request)
      (if (= :get (:request-method request))
        {:status 302 :headers {"Location" "/login"} :body ""}
        {:status 401 :body "Unauthorized"}))))

;; ---------------------------------------------------------------------------
;; Active tenant (Ring session map)
;; ---------------------------------------------------------------------------

(defn active-tenant-id
  "Read the active tenant UUID from the Ring session, or nil."
  [request]
  (get-in request [:session :active-tenant-id]))

(defn set-active-tenant
  "Assoc the active tenant UUID into the response session."
  [response tenant-id]
  (assoc-in response [:session :active-tenant-id] tenant-id))

(defn clear-active-tenant
  "Remove the active tenant from the response session."
  [response]
  (update response :session dissoc :active-tenant-id))

;; ---------------------------------------------------------------------------
;; Language override (Ring session map)
;; ---------------------------------------------------------------------------

(defn lang-override
  "Read the viewer's chosen language override from the Ring session, or nil.
  When set, it takes precedence over any page/event default."
  [request]
  (get-in request [:session :lang]))

(defn set-lang
  "Assoc the chosen language code into the response session."
  [response lang]
  (assoc-in response [:session :lang] lang))
