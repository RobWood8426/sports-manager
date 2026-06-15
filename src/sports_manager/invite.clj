(ns sports-manager.invite
  "Pending invites — allow admins to invite users who haven't signed up yet.

  An invite stores an email + tenant-id. On first sign-in, upsert-user! calls
  redeem-pending! which converts any matching invites into memberships."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [sports-manager.db :as db]
            [sports-manager.membership :as membership]
            [xtdb.api :as xt])
  (:import java.util.Date
           java.util.UUID))

(defn create!
  "Create a pending invite for email to join tenant-id. Idempotent — if a
  pending invite already exists for this email+tenant pair, returns nil."
  [email tenant-id actor-uid]
  (let [email (str/lower-case (str/trim email))
        existing (ffirst (db/q '{:find [?e]
                                 :in [?email ?tid]
                                 :where [[?e :invite/email ?email]
                                         [?e :invite/tenant ?tid]
                                         [?e :invite/status :invite.status/pending]]}
                               email tenant-id))]
    (when-not existing
      (let [id (UUID/randomUUID)]
        (log/info "Creating invite for" email "to tenant" tenant-id)
        (db/put! {:xt/id id
                  :invite/id id
                  :invite/email email
                  :invite/tenant tenant-id
                  :invite/status :invite.status/pending
                  :invite/created-by actor-uid
                  :invite/created-at (Date.)})
        id))))

(defn find-pending-by-email
  "Return all pending invites for a given email address."
  [email]
  (let [email (str/lower-case (str/trim email))
        ids (map first (db/q '{:find [?id]
                               :in [?email]
                               :where [[?e :invite/email ?email]
                                       [?e :invite/status :invite.status/pending]
                                       [?e :invite/id ?id]]}
                             email))]
    (mapv #(db/entity %) ids)))

(defn find-pending-by-tenant
  "Return all pending invites for a given tenant."
  [tenant-id]
  (let [ids (map first (db/q '{:find [?id]
                               :in [?tid]
                               :where [[?e :invite/tenant ?tid]
                                       [?e :invite/status :invite.status/pending]
                                       [?e :invite/id ?id]]}
                             tenant-id))]
    (mapv #(db/entity %) ids)))

(defn redeem-pending!
  "Called on sign-in: find all pending invites for this email, create
  memberships for each, and mark them accepted."
  [uid email]
  (let [email (str/lower-case (str/trim (or email "")))]
    (when-not (str/blank? email)
      (doseq [invite (find-pending-by-email email)]
        (let [tenant-id (:invite/tenant invite)
              invite-id (:invite/id invite)]
          (log/info "Redeeming invite" invite-id "for" email "->" tenant-id)
          (membership/create! uid tenant-id :actor "system")
          (db/submit! [[::xt/put (assoc invite
                                        :invite/status :invite.status/accepted
                                        :invite/accepted-at (Date.)
                                        :invite/accepted-by uid)]]))))))
