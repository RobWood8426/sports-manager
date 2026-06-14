(ns sports-manager.routes.admin.users
  "User management and RBAC handlers."
  (:require [ring.util.response :as resp]
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
      (shared/html (views.admin/users-list user-or-redirect
                                           (user/list-by-tenant tenant-id)
                                           (keys rbac/role-permissions))))))

(defn users-add
  "POST /users/add — add an existing user by email to this tenant."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [current-user user-or-redirect
            email (get (shared/form-params request) "email")
            errors (user/validate-add {:email email})]
        (if (seq errors)
          (shared/html (views.admin/users-list current-user
                                               (user/list-by-tenant tenant-id)
                                               (keys rbac/role-permissions)
                                               {:add-errors errors :add-email email}))
          (let [found (user/find-by-email email)]
            (cond
              (nil? found)
              (shared/html (views.admin/users-list current-user
                                                   (user/list-by-tenant tenant-id)
                                                   (keys rbac/role-permissions)
                                                   {:add-errors {:not-found (str "No account found for " email ". They need to sign in first.")}
                                                    :add-email email}))
              :else
              (try
                (user/add-to-tenant! found tenant-id (:user/firebase-uid current-user))
                (resp/redirect "/users")
                (catch clojure.lang.ExceptionInfo e
                  (if (= "User already belongs to another tenant" (ex-message e))
                    (shared/html (views.admin/users-list current-user
                                                         (user/list-by-tenant tenant-id)
                                                         (keys rbac/role-permissions)
                                                         {:add-errors {:other-tenant (str email " belongs to a different organisation and cannot be added.")}
                                                          :add-email email}))
                    (throw e)))))))))))

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
            all-roles (set (map #(name %) (keys rbac/role-permissions)))
            target-eid [:user/firebase-uid target-uid]]
        (doseq [rn all-roles]
          (let [kw (keyword "role.name" rn)]
            (if (contains? submitted rn)
              (rbac/grant-role! target-eid kw :actor (:user/firebase-uid current-user))
              (rbac/revoke-role! target-eid kw :actor (:user/firebase-uid current-user)))))
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
