(ns sports-manager.routes.admin.sports
  "Sport template selection and custom sport handlers."
  (:require [ring.util.response :as resp]
            [sports-manager.routes.shared :as shared]
            [sports-manager.sport-template :as sport-template]
            [sports-manager.views.admin :as views.admin]))

(defn sports-page
  "GET /school/sports — sport template selection checklist including custom sports."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (shared/html (views.admin/sport-templates
                    (sport-template/list-for-tenant tenant-id)
                    (sport-template/selected-codes tenant-id))))))

(defn sports-submit
  "POST /school/sports — persist the tenant's sport template selection."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [current-user user-or-redirect
            raw (get (shared/form-params request) "sports")
            codes (->> (if (string? raw) [raw] (or raw []))
                       (map #(keyword "sport" %))
                       set)]
        (sport-template/set-selection! tenant-id codes (:user/firebase-uid current-user))
        (resp/redirect "/school/sports")))))

(defn custom-sport-create
  "POST /school/sports/custom — create a tenant custom sport."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [params (shared/form-params request)
            data (sport-template/parse-custom-form params)
            errors (sport-template/validate-custom data)]
        (if (seq errors)
          (shared/html (views.admin/sport-templates
                        (sport-template/list-for-tenant tenant-id)
                        (sport-template/selected-codes tenant-id)
                        {:custom-errors errors :custom-data data}))
          (do
            (sport-template/create-custom! tenant-id data)
            (resp/redirect "/school/sports")))))))

(defn custom-sport-delete
  "POST /school/sports/custom/:sid/delete — delete a tenant custom sport."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [sid-str (get-in request [:path-params :sid])
            sid (when sid-str (try (java.util.UUID/fromString sid-str) (catch Exception _ nil)))]
        (if-not sid
          {:status 400 :body "Bad request"}
          (do
            (try
              (sport-template/delete-custom! tenant-id sid)
              (catch clojure.lang.ExceptionInfo _))
            (resp/redirect "/school/sports")))))))
