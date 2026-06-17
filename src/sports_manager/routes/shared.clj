(ns sports-manager.routes.shared
  "Cross-cutting helpers shared by all route namespaces."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [ring.util.response :as resp]
            [sports-manager.auth :as auth]
            [sports-manager.event :as event]
            [sports-manager.i18n :as i18n]
            [sports-manager.membership :as membership]
            [sports-manager.session :as session]))

(defn html
  "Build a 200 text/html response from a string body.

  Sends `Cache-Control: no-store` so the browser never serves a stale render.
  These pages are session-dependent (the language override, signed-in user,
  active tenant) — a cached copy would show the wrong language after a
  language switch, or stale data after any session change."
  [body]
  (-> (resp/response body)
      (resp/content-type "text/html; charset=utf-8")
      (resp/header "Cache-Control" "no-store")))

(defn json-body
  "Parse a JSON request body into a keyword-keyed map (nil on failure)."
  [request]
  (when-let [b (:body request)]
    (try (json/parse-string (slurp (io/reader b)) true)
         (catch Exception _ nil))))

(defn form-params
  "Extract form-encoded params as keyword-keyed map."
  [request]
  (or (:form-params request) {}))

(defn request->base-url
  "Derive the base URL from the incoming request's Host header so that QR codes
  and links work regardless of hostname (dev, LAN IP, production domain, etc.)."
  [request]
  (let [host (or (get-in request [:headers "x-forwarded-host"])
                 (get-in request [:headers "host"])
                 "localhost:3000")
        scheme (or (get-in request [:headers "x-forwarded-proto"])
                   (name (get request :scheme :http)))]
    (str scheme "://" host)))

(defn parse-event-id
  "Parse a UUID from a path param string, or nil on failure."
  [s]
  (when s
    (try (java.util.UUID/fromString s)
         (catch IllegalArgumentException _ nil))))

(defn switch-lang
  "POST /lang — set the viewer's language override in the session and redirect
  back to where they were. Works for both admin and public visitors (no auth
  required). Unsupported codes fall back to English via normalize-lang.

  Seeds the response session from the incoming request session so the active
  tenant and the anti-forgery token survive — returning a response whose
  :session held only {:lang ...} would replace the whole session and drop them
  (causing a CSRF 403 and loss of the tenant selection on the next request)."
  [request]
  (let [lang (i18n/normalize-lang (get (form-params request) "lang"))
        back (or (get-in request [:headers "referer"]) "/")]
    (-> (resp/redirect back :see-other)
        (assoc :session (:session request))
        (session/set-lang lang))))

(defn current-lang
  "Resolve the language for this request: the viewer's session override if set,
  otherwise `default` (a supported code, falling back to English). Pass the
  page/event default as `default` on event-scoped pages; admin chrome passes
  none and gets English."
  ([request] (current-lang request "en"))
  ([request default]
   (i18n/normalize-lang (or (session/lang-override request) default))))

(defn require-tenant
  "Returns [user tenant-id membership] if request has an authenticated user with
  an active tenant selection, otherwise returns a redirect response as the first
  element with nil tenant-id."
  [request]
  (if-let [uid (:uid request)]
    (let [active-tid (session/active-tenant-id request)]
      (if-not active-tid
        [(resp/redirect "/select-tenant") nil nil]
        (let [m (membership/active? uid active-tid)]
          (if-not m
            [(resp/redirect "/select-tenant") nil nil]
            [(auth/find-user uid) active-tid m]))))
    [(resp/redirect "/login") nil nil]))

(defn with-tenant-event
  "Tenant-isolation wrapper for event-scoped admin handlers.

  Resolves the `:id` path param to an event owned by the active tenant, then
  calls `(f user tenant-id event)`. Short-circuits with:
    - the require-tenant redirect when unauthenticated / no active tenant,
    - a 404 when the event is missing OR belongs to another tenant.

  This is the single guard for cross-tenant event access — every
  `/events/:id/...` admin handler should route through it (SPO-61)."
  [request f]
  (let [[user-or-redirect tenant-id membership] (require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [event-id (parse-event-id (get-in request [:path-params :id]))
            ev (when event-id (event/find-by-id event-id))]
        (if-not (and ev (= tenant-id (:event/tenant ev)))
          {:status 404 :body "Event not found"}
          (f user-or-redirect tenant-id ev membership))))))
