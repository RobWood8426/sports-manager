(ns sports-manager.routes.admin.settings
  "School settings: profile, brand colours, and logo upload (SPO-23)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ring.util.response :as resp]
            [sports-manager.routes.shared :as shared]
            [sports-manager.school :as school]
            [sports-manager.session :as session]
            [sports-manager.storage :as storage]
            [sports-manager.views.admin :as views.admin]))

(defn settings-page
  "GET /school/settings — profile + branding form."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (shared/html (views.admin/school-settings (school/find-by-id tenant-id)
                                                {:lang (shared/current-lang request)})))))

(defn profile-submit
  "POST /school/settings — save profile fields and brand colours."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [params (shared/form-params request)
            profile {:tenant/name (str/trim (get params "tenant-name" ""))
                     :tenant/contact-email (str/trim (get params "tenant-contact-email" ""))
                     :tenant/website (str/trim (get params "tenant-website" ""))
                     :tenant/city (str/trim (get params "tenant-city" ""))
                     :tenant/province (str/trim (get params "tenant-province" ""))}
            errors (school/validate profile)]
        (if (seq errors)
          (shared/html (views.admin/school-settings
                        (merge (school/find-by-id tenant-id) profile)
                        {:errors errors :lang (shared/current-lang request)}))
          (do
            (school/update-profile! tenant-id profile)
            (school/set-colours! tenant-id {:primary (get params "brand-primary")
                                            :secondary (get params "brand-secondary")})
            (resp/redirect "/school/settings")))))))

(defn logo-upload
  "POST /school/settings/logo — upload a new logo image."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [file-part (get-in request [:multipart-params "logo-file"])
            content-type (:content-type file-part)
            size (:size file-part)
            errors (storage/validate-upload {:content-type content-type :size size})]
        (cond
          (or (nil? file-part) (str/blank? (:filename file-part "")))
          (shared/html (views.admin/school-settings (school/find-by-id tenant-id)
                                                    {:errors {:file "Please choose an image."}
                                                     :lang (shared/current-lang request)}))

          (seq errors)
          (shared/html (views.admin/school-settings (school/find-by-id tenant-id)
                                                    {:errors errors
                                                     :lang (shared/current-lang request)}))

          :else
          (let [tenant (school/find-by-id tenant-id)
                old-key (:tenant/logo-key tenant)
                object-key (storage/object-key tenant-id :logo content-type)
                object-bytes (with-open [in (io/input-stream (:tempfile file-part))
                                         bos (java.io.ByteArrayOutputStream.)]
                               (io/copy in bos)
                               (.toByteArray bos))]
            (storage/put-object storage/default-storage object-key object-bytes content-type)
            (school/set-logo! tenant-id object-key content-type)
            (when (and old-key (not= old-key object-key))
              (storage/delete-object storage/default-storage old-key))
            (resp/redirect "/school/settings")))))))

(defn logo-clear
  "POST /school/settings/logo/clear — remove the current logo."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [old-key (:tenant/logo-key (school/find-by-id tenant-id))]
        (school/clear-logo! tenant-id)
        (when old-key (storage/delete-object storage/default-storage old-key))
        (resp/redirect "/school/settings")))))

(defn school-delete
  "POST /school/settings/delete — permanently delete the school and all its data."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [logo-key (:tenant/logo-key (school/find-by-id tenant-id))]
        (school/delete! tenant-id)
        (when logo-key (storage/delete-object storage/default-storage logo-key))
        (-> (resp/redirect "/")
            (session/clear-active-tenant))))))

(defn media
  "GET /media/:tenant-id/:kind/:filename — serve an uploaded object.
  Public read (logos/banners render on public event pages); the URL embeds the
  tenant id. Returns 404 if absent. Path components are validated to prevent
  traversal."
  [request]
  (let [{:keys [tenant-id kind filename]} (:path-params request)
        object-key (str tenant-id "/" kind "/" filename)]
    (if-let [{:keys [content-type] object-bytes :bytes} (storage/get-object storage/default-storage object-key)]
      (-> (resp/response (java.io.ByteArrayInputStream. object-bytes))
          (resp/content-type content-type)
          (resp/header "Cache-Control" "public, max-age=3600")
          (resp/header "Content-Length" (str (alength ^bytes object-bytes))))
      {:status 404 :body "Not found"})))
