(ns sports-manager.routes.core
  "Ring handler and Reitit route table. Entry point for the HTTP layer."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [reitit.ring :as ring]
            [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]
            [ring.middleware.defaults :refer [site-defaults wrap-defaults]]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [ring.util.response :as resp]
            [sports-manager.config :as config]
            [sports-manager.routes.admin.events :as admin.events]
            [sports-manager.routes.admin.fixtures :as admin.fixtures]
            [sports-manager.routes.admin.imports :as admin.imports]
            [sports-manager.routes.admin.settings :as admin.settings]
            [sports-manager.routes.admin.sports :as admin.sports]
            [sports-manager.routes.admin.users :as admin.users]
            [sports-manager.routes.auth :as auth]
            [sports-manager.routes.public.scorekeeper :as pub.scorekeeper]
            [sports-manager.routes.public.spectator :as pub.spectator]
            [sports-manager.routes.shared :as routes.shared]
            [sports-manager.session :as session]
            [sports-manager.views.shared :as views.shared]))

(def routes
  [;; --- auth & onboarding ---
   ["/" {:get auth/root}]
   ["/login" {:get auth/login-page}]
   ["/select-tenant" {:get auth/select-tenant-page :post auth/select-tenant-submit}]
   ["/switch-tenant" {:post auth/switch-tenant}]
   ["/school/setup" {:get auth/school-setup-page :post auth/school-setup-submit}]
   ["/auth/session" {:post auth/session-exchange}]
   ["/auth/logout" {:post auth/logout}]
   ["/lang" {:post routes.shared/switch-lang}]

   ;; --- admin: users ---
   ["/users" {:get admin.users/users-page}]
   ["/users/add" {:post admin.users/users-add}]
   ["/users/:uid/roles" {:post admin.users/users-set-roles}]
   ["/users/:uid/remove" {:post admin.users/users-remove}]

   ;; --- admin: sports ---
   ["/school/sports" {:get admin.sports/sports-page :post admin.sports/sports-submit}]
   ["/school/sports/custom" {:post admin.sports/custom-sport-create}]
   ["/school/sports/custom/:sid/delete" {:post admin.sports/custom-sport-delete}]

   ;; --- admin: settings & branding ---
   ["/school/settings" {:get admin.settings/settings-page :post admin.settings/profile-submit}]
   ["/school/settings/logo" {:post admin.settings/logo-upload}]
   ["/school/settings/logo/clear" {:post admin.settings/logo-clear}]
   ["/school/settings/delete" {:post admin.settings/school-delete}]
   ["/school/venues" {:post admin.settings/venue-create}]
   ["/school/venues/:vid/delete" {:post admin.settings/venue-delete}]

   ;; --- admin: events ---
   ["/events/create" {:get admin.events/event-new-page}]
   ["/events" {:post admin.events/event-create}]
   ["/events/:id" {:get admin.events/event-detail-page}]
   ["/events/:id/dashboard" {:get admin.events/dashboard-page}]
   ["/events/:id/publish" {:post admin.events/event-publish}]
   ["/events/:id/qr" {:get admin.events/event-qr}]
   ["/events/:id/participants" {:post admin.events/participant-add}]
   ["/events/:id/participants/:pid/remove" {:post admin.events/participant-remove}]
   ["/events/:id/teams" {:post admin.events/team-create}]
   ["/events/:id/teams/:tid/delete" {:post admin.events/team-delete}]
   ["/events/:id/venues" {:post admin.events/venue-create}]
   ["/events/:id/venues/:vid/delete" {:post admin.events/venue-delete}]
   ["/events/:id/wizard/fields" {:get admin.events/event-wizard-fields-page :post admin.events/event-wizard-fields-save}]
   ["/events/:id/wizard/fields/new" {:post admin.events/event-wizard-fields-add}]
   ["/events/:id/wizard/age-groups" {:get admin.events/event-wizard-age-groups-page :post admin.events/event-wizard-age-groups-save}]
   ["/events/:id/sports/:sport/config" {:post admin.events/event-sport-config-save}]

   ;; --- admin: fixtures ---
   ["/disputes" {:get admin.fixtures/disputes-page}]
   ["/audit" {:get admin.fixtures/audit-log-page}]
   ["/events/:id/fixtures" {:post admin.fixtures/fixture-create}]
   ["/events/:id/fixtures/export" {:get admin.fixtures/fixture-export-csv}]
   ["/events/:id/results/export" {:get admin.fixtures/fixture-results-export-csv}]
   ["/events/:id/score-audit/export" {:get admin.fixtures/fixture-audit-export-csv}]
   ["/events/:id/fixtures/:fid" {:post admin.fixtures/fixture-edit}]
   ["/events/:id/fixtures/:fid/publish" {:post admin.fixtures/fixture-publish}]
   ["/events/:id/fixtures/:fid/codes" {:post admin.fixtures/fixture-code-generate}]
   ["/events/:id/fixtures/:fid/codes/:cid/revoke" {:post admin.fixtures/fixture-code-revoke}]
   ["/events/:id/fixtures/:fid/codes/:cid/qr" {:get admin.fixtures/fixture-code-qr}]
   ["/events/:id/fixtures/:fid/assign" {:post admin.fixtures/fixture-assign-scorekeeper}]
   ["/events/:id/fixtures/:fid/comparison" {:get admin.fixtures/fixture-comparison-page}]
   ["/events/:id/fixtures/:fid/resolve" {:post admin.fixtures/fixture-dispute-resolve}]

   ;; --- admin: imports ---
   ["/events/:id/import" {:get admin.imports/fixture-import-page}]
   ["/events/:id/import/upload" {:post admin.imports/fixture-import-upload}]
   ["/events/:id/import/map" {:get admin.imports/fixture-import-map-page :post admin.imports/fixture-import-map-submit}]
   ["/events/:id/import/preview" {:get admin.imports/fixture-import-preview-page}]
   ["/events/:id/import/confirm" {:post admin.imports/fixture-import-confirm}]

   ;; --- public: scorekeeper ---
   ["/score" {:get pub.scorekeeper/scorekeeper-entry-page :post pub.scorekeeper/scorekeeper-code-submit}]
   ["/score/:fixture-id/confirm" {:post pub.scorekeeper/scorekeeper-confirm-submit}]
   ["/score/:fixture-id/live" {:get pub.scorekeeper/scorekeeper-live-page}]
   ["/score/:fixture-id/start" {:post pub.scorekeeper/scorekeeper-start-game}]
   ["/score/:fixture-id/submit" {:post pub.scorekeeper/scorekeeper-submit-final}]
   ["/score/:fixture-id/event" {:post pub.scorekeeper/scorekeeper-record-event}]
   ["/score/:fixture-id/status" {:get pub.scorekeeper/scorekeeper-fixture-status}]

   ;; --- public: spectator ---
   ["/e" {:get pub.spectator/spectator-landing}]
   ["/e/:code" {:get pub.spectator/spectator-event-page}]
   ["/e/fixture/:fid" {:get pub.spectator/spectator-fixture-page}]
   ["/e/fixture/:fid/score" {:get pub.spectator/spectator-fixture-score}]

   ;; --- public: uploaded media (logos, banners) ---
   ["/media/:tenant-id/:kind/:filename" {:get admin.settings/media}]])

(defn- wrap-not-found
  "Log every 404 response with the request method and URI."
  [handler]
  (fn [request]
    (let [response (handler request)]
      (when (= 404 (:status response))
        (log/warn "404 Not Found" {:method (-> request :request-method name str/upper-case)
                                   :uri (:uri request)}))
      response)))

(defn- wrap-exception
  "Catch unhandled exceptions and return a structured error response."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [http-status]} (ex-data e)
              status (or http-status 500)
              message (ex-message e)]
          (log/error e "Unhandled ExceptionInfo" {:uri (:uri request) :status status})
          (-> (resp/response (views.shared/error-page status message))
              (resp/content-type "text/html; charset=utf-8")
              (resp/status status))))
      (catch Throwable e
        (log/error e "Unhandled exception" {:uri (:uri request)})
        (-> (resp/response (views.shared/error-page 500 "An unexpected error occurred."))
            (resp/content-type "text/html; charset=utf-8")
            (resp/status 500))))))

(defn- json-request? [request]
  (some-> (get-in request [:headers "content-type"])
          (str/starts-with? "application/json")))

(defn- wrap-csrf
  "Apply CSRF protection, skipping JSON requests (fetch-based API calls),
  the logout endpoint (logout-via-CSRF is harmless), and scorekeeper POST
  routes (code-gated by scode-id, CSRF adds no value)."
  [handler]
  (let [csrf-error (-> (resp/response (views.shared/error-page 403 "Invalid anti-forgery token. Please go back and try again."))
                       (resp/content-type "text/html; charset=utf-8")
                       (resp/status 403))
        protected (wrap-anti-forgery handler {:error-response csrf-error})]
    (fn [request]
      (if (or (json-request? request)
              (= (:uri request) "/auth/logout")
              (str/starts-with? (:uri request) "/score/"))
        (handler request)
        (protected request)))))

(def handler
  "Ring handler: routes + static resources + session identity from the cookie."
  (let [session-key (let [b (.getBytes ^String config/session-secret "UTF-8")
                          k (byte-array 16)]
                      (System/arraycopy b 0 k 0 (min 16 (alength b)))
                      k)]
    (-> (ring/ring-handler
         (ring/router routes {:conflicts nil})
         (ring/routes
          (ring/create-resource-handler {:path "/"})
          (ring/create-default-handler
           {:not-found (fn [_]
                         (-> (resp/response (views.shared/error-page 404 "Page not found."))
                             (resp/content-type "text/html; charset=utf-8")
                             (resp/status 404)))})))
        session/wrap-session-identity
        wrap-csrf
        (wrap-defaults (-> site-defaults
                           (assoc-in [:security :anti-forgery] false)
                           (assoc-in [:session :store] (cookie-store {:key session-key}))))
        wrap-exception
        wrap-not-found)))
