(ns sports-manager.routes.admin.users
  "User management and RBAC handlers."
  (:require [ring.util.response :as resp]
            [sports-manager.invite :as invite]
            [sports-manager.rbac :as rbac]
            [sports-manager.routes.shared :as shared]
            [sports-manager.user :as user]
            [sports-manager.views.admin :as views.admin]
            [sports-manager.views.shared :as views.shared]))

(defn users-page
  "GET /users — list team members with role management."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [invited? (= "1" (get (:query-params request) "invited"))]
        (shared/html (views.admin/users-list user-or-redirect
                                             (user/list-by-tenant tenant-id)
                                             (keys rbac/role-permissions)
                                             {:pending-invites (invite/find-pending-by-tenant tenant-id)
                                              :invited? invited?
                                              :lang (shared/current-lang request)}))))))

(defn users-add
  "POST /users/add — add an existing user by email to this tenant."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [current-user user-or-redirect
            lang (shared/current-lang request)
            email (get (shared/form-params request) "email")
            errors (user/validate-add {:email email})]
        (if (seq errors)
          (shared/html (views.admin/users-list current-user
                                               (user/list-by-tenant tenant-id)
                                               (keys rbac/role-permissions)
                                               {:add-errors errors :add-email email :lang lang}))
          (let [found (user/find-by-email email)
                actor-uid (:user/firebase-uid current-user)]
            (if found
              (try
                (user/add-to-tenant! found tenant-id actor-uid)
                (resp/redirect "/users")
                (catch clojure.lang.ExceptionInfo e
                  (if (= "User already belongs to another tenant" (ex-message e))
                    (shared/html (views.admin/users-list current-user
                                                         (user/list-by-tenant tenant-id)
                                                         (keys rbac/role-permissions)
                                                         {:add-errors {:other-tenant (str email " belongs to a different organisation and cannot be added.")}
                                                          :add-email email
                                                          :lang lang}))
                    (throw e))))
              (do
                (invite/create! email tenant-id actor-uid)
                (resp/redirect "/users?invited=1")))))))))

(defn users-set-roles
  "POST /users/:uid/roles — replace this user's role set."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [current-user user-or-redirect
            target-uid (get-in request [:path-params :uid])
            submitted (let [v (get (shared/form-params request) "roles")]
                        (if (string? v) #{v} (set v)))
            all-roles (set (map #(name %) (keys rbac/role-permissions)))]
        (doseq [rn all-roles]
          (let [kw (keyword "role.name" rn)]
            (if (contains? submitted rn)
              (rbac/grant-role! target-uid kw :actor (:user/firebase-uid current-user))
              (rbac/revoke-role! target-uid kw :actor (:user/firebase-uid current-user)))))
        (shared/html (views.shared/toast-fragment "Roles saved"))))))

(defn users-remove
  "POST /users/:uid/remove — unlink a user from this tenant."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [current-user user-or-redirect
            target-uid (get-in request [:path-params :uid])]
        (user/remove-from-tenant! target-uid tenant-id (:user/firebase-uid current-user))
        (resp/redirect "/users")))))
