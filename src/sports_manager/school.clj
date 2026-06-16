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
                    :tenant/logo-key :tenant/logo-content-type
                    :tenant/brand-primary :tenant/brand-secondary
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

(defn set-colours!
  "Set/clear a tenant's brand colours. Each colour is a hex string (e.g.
  \"#2e6bf0\") or nil/blank to clear. Returns the updated entity."
  [tenant-id {:keys [primary secondary]}]
  (let [clean (fn [c] (when-not (str/blank? c) (str/trim c)))
        asserts (cond-> {}
                  (clean primary) (assoc :tenant/brand-primary (clean primary))
                  (clean secondary) (assoc :tenant/brand-secondary (clean secondary)))
        retract (cond-> []
                  (str/blank? primary) (conj :tenant/brand-primary)
                  (str/blank? secondary) (conj :tenant/brand-secondary))]
    (when (seq retract) (db/retract-attrs! tenant-id retract))
    (when (seq asserts) (db/merge! tenant-id asserts))
    (find-by-id tenant-id)))

(defn set-logo!
  "Record the storage key + content-type of a tenant's uploaded logo.
  Returns the updated entity."
  [tenant-id logo-key content-type]
  (db/merge! tenant-id {:tenant/logo-key logo-key
                        :tenant/logo-content-type content-type})
  (find-by-id tenant-id))

(defn clear-logo!
  "Remove the logo reference from a tenant. Returns the updated entity.
  Does not delete the stored object — the caller handles storage cleanup."
  [tenant-id]
  (db/retract-attrs! tenant-id [:tenant/logo-key :tenant/logo-content-type])
  (find-by-id tenant-id))
