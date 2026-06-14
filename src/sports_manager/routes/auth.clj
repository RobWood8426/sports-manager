(ns sports-manager.routes.auth
  "Auth, onboarding, and tenant-selection handlers."
  (:require [ring.util.response :as resp]
            [sports-manager.auth :as auth]
            [sports-manager.config :as config]
            [sports-manager.event :as event]
            [sports-manager.membership :as membership]
            [sports-manager.routes.shared :as shared]
            [sports-manager.school :as school]
            [sports-manager.session :as session]
            [sports-manager.views.admin :as views.admin]
            [sports-manager.views.auth :as views.auth]))

(def ^:private profile-fields
  #{:tenant/name :tenant/address :tenant/city :tenant/province :tenant/country
    :tenant/contact-email :tenant/contact-phone :tenant/website
    :tenant/latitude :tenant/longitude})

(defn- parse-profile
  "Convert string-keyed form-params into a namespaced profile map."
  [params]
  (reduce-kv (fn [m k v]
               (let [kw (keyword "tenant" k)]
                 (if (profile-fields kw) (assoc m kw v) m)))
             {}
             params))

(defn login-page [_]
  (shared/html (views.auth/login {:api-key config/firebase-api-key
                                  :auth-domain config/firebase-auth-domain
                                  :project-id config/firebase-project-id
                                  :app-id config/firebase-app-id})))

(defn root
  "GET / — resolve active tenant and show home, or redirect to setup/select."
  [request]
  (if-let [uid (:uid request)]
    (let [active-tid (session/active-tenant-id request)
          memberships (membership/list-active-by-user uid)]
      (cond
        (empty? memberships)
        (resp/redirect "/school/setup")

        (and active-tid (some #(= active-tid (get-in % [:membership/tenant :tenant/id])) memberships))
        (let [user (auth/find-user uid)]
          (shared/html (views.admin/home user (event/list-by-tenant active-tid) memberships active-tid)))

        (= 1 (count memberships))
        (let [tid (get-in (first memberships) [:membership/tenant :tenant/id])
              user (auth/find-user uid)]
          (-> (shared/html (views.admin/home user (event/list-by-tenant tid) memberships tid))
              (session/set-active-tenant tid)))

        :else
        (resp/redirect "/select-tenant")))
    (login-page request)))

(defn school-setup-page [request]
  (if (:uid request)
    (shared/html (views.auth/school-setup))
    (resp/redirect "/login")))

(defn school-setup-submit
  "POST /school/setup — validate and create the school, then redirect home."
  [request]
  (let [uid (:uid request)]
    (if-not uid
      (resp/redirect "/login")
      (let [profile (parse-profile (shared/form-params request))
            errors (school/validate profile)]
        (if (seq errors)
          (shared/html (views.auth/school-setup {:errors errors}))
          (do
            (school/create! uid profile)
            (resp/redirect "/")))))))

(defn session-exchange
  "POST /auth/session — verify a Firebase ID token, upsert the user, set cookie."
  [request]
  (let [token (:token (shared/json-body request))
        claims (auth/verify-token token)]
    (if claims
      (do
        (auth/upsert-user! claims)
        (-> (resp/response "ok")
            (assoc :cookies (session/cookie (:uid claims)))))
      {:status 401 :body "Invalid token"})))

(defn logout [_]
  (-> (resp/redirect "/login")
      (assoc :cookies session/clear-cookie)
      (assoc :session {})))

(defn select-tenant-page
  "GET /select-tenant — show a picker of the user's active memberships."
  [request]
  (if-let [uid (:uid request)]
    (let [memberships (membership/list-active-by-user uid)]
      (if (empty? memberships)
        (resp/redirect "/school/setup")
        (shared/html (views.auth/select-tenant memberships))))
    (resp/redirect "/login")))

(defn select-tenant-submit
  "POST /select-tenant — validate the chosen tenant and set it in session."
  [request]
  (if-let [uid (:uid request)]
    (let [tid-str (get (shared/form-params request) "tenant-id")
          tid (when tid-str (try (java.util.UUID/fromString tid-str) (catch Exception _ nil)))]
      (if (and tid (membership/active? uid tid))
        (-> (resp/redirect "/")
            (session/set-active-tenant tid))
        (resp/redirect "/select-tenant")))
    (resp/redirect "/login")))

(defn switch-tenant
  "POST /switch-tenant — change the active tenant and redirect home."
  [request]
  (if-let [uid (:uid request)]
    (let [tid-str (get (shared/form-params request) "tenant-id")
          tid (when tid-str (try (java.util.UUID/fromString tid-str) (catch Exception _ nil)))]
      (if (and tid (membership/active? uid tid))
        (-> (resp/redirect "/")
            (session/set-active-tenant tid))
        (resp/redirect "/")))
    (resp/redirect "/login")))
