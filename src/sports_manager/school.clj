(ns sports-manager.school
  "School (tenant) creation and profile management (SPO-22, §6A.1–9).

  A school is a tenant. The first admin who creates the school is linked to it
  and granted the school-admin role automatically."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [sports-manager.db :as db]
            [sports-manager.membership :as membership]
            [sports-manager.rbac :as rbac])
  (:import java.time.Instant
           java.util.Date
           java.util.UUID))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(def ^:private required-fields
  [:tenant/name :tenant/contact-email])

(defn validate
  "Returns a map of field → error string for any validation failures.
  Empty map means the data is valid."
  [profile]
  (reduce (fn [errs field]
            (if (str/blank? (get profile field))
              (assoc errs field "Required")
              errs))
          {}
          required-fields))

;; ---------------------------------------------------------------------------
;; Query
;; ---------------------------------------------------------------------------

(defn find-by-id
  "Pull a tenant entity by UUID, or nil."
  [tenant-id]
  (let [e (db/pull [:tenant/id :tenant/name :tenant/status
                    :tenant/address :tenant/city :tenant/province
                    :tenant/country :tenant/contact-email
                    :tenant/contact-phone :tenant/website
                    :tenant/latitude :tenant/longitude
                    :tenant/created-at]
                   tenant-id)]
    (when (:tenant/id e) e)))

;; ---------------------------------------------------------------------------
;; Mutation
;; ---------------------------------------------------------------------------

(defn create!
  "Create a new school tenant from `profile` and link `user-uid` (Firebase uid)
  to it as school-admin. Returns the tenant entity map.

  `profile` keys: :tenant/name (required), :tenant/contact-email (required),
  and any optional :tenant/* profile attributes."
  [user-uid profile]
  (let [tenant-id (UUID/randomUUID)
        now (Date/from (Instant/now))
        doc (merge {:xt/id tenant-id
                    :tenant/id tenant-id
                    :tenant/status :active
                    :tenant/created-at now}
                   (select-keys profile [:tenant/name
                                         :tenant/address
                                         :tenant/city
                                         :tenant/province
                                         :tenant/country
                                         :tenant/contact-email
                                         :tenant/contact-phone
                                         :tenant/website
                                         :tenant/latitude
                                         :tenant/longitude]))]
    (log/info "Creating school tenant" (:tenant/name profile) "for user" user-uid)
    (db/put! doc)
    (membership/create! user-uid tenant-id :actor user-uid)
    (let [tenant (find-by-id tenant-id)]
      (rbac/grant-role! user-uid :role.name/school-admin :actor user-uid)
      (log/info "School created:" tenant-id)
      tenant)))

(defn update-profile!
  "Update mutable profile fields on an existing tenant. Returns the updated entity."
  [tenant-id profile]
  (let [updatable [:tenant/name :tenant/address :tenant/city :tenant/province
                   :tenant/country :tenant/contact-email :tenant/contact-phone
                   :tenant/website :tenant/latitude :tenant/longitude]]
    (db/merge! tenant-id (select-keys profile updatable))
    (find-by-id tenant-id)))
