(ns sports-manager.participant
  "Participating schools on an event (SPO-27)."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [sports-manager.db :as db])
  (:import java.util.UUID))

(def ^:private pull-pattern
  [:participant/id :participant/name :participant/contact-email
   :participant/contact-phone :participant/status
   {:participant/tenant [:tenant/id :tenant/name]}])

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn validate
  "Returns a map of field → error string. Empty map means valid."
  [{:participant/keys [name]}]
  (cond-> {}
    (str/blank? name) (assoc :participant/name "Required")))

;; ---------------------------------------------------------------------------
;; Query
;; ---------------------------------------------------------------------------

(defn list-by-event
  "Return all participants for the given event UUID, sorted by name."
  [event-id]
  (let [eids (map first
                  (db/q '[:find ?p
                          :in $ ?eid
                          :where
                          [?e :event/id ?eid]
                          [?e :event/participants ?p]]
                        event-id))]
    (->> eids
         (mapv #(db/pull pull-pattern %))
         (filter :participant/id)
         (sort-by :participant/name))))

(defn find-by-id
  "Pull a participant entity by UUID, or nil."
  [participant-id]
  (let [e (db/pull pull-pattern [:participant/id participant-id])]
    (when (:participant/id e) e)))

;; ---------------------------------------------------------------------------
;; Mutation
;; ---------------------------------------------------------------------------

(defn add-to-event!
  "Create a participant and link it to the event. Returns the participant map.
  `tenant-id` is an optional UUID — when provided, links to an existing tenant."
  [event-id actor-uid {:participant/keys [name contact-email contact-phone]} tenant-id]
  (let [pid (UUID/randomUUID)
        tx-map (cond-> {:db/id "new-participant"
                        :participant/id pid
                        :participant/name name
                        :participant/status :participant.status/confirmed}
                 (not (str/blank? contact-email)) (assoc :participant/contact-email contact-email)
                 (not (str/blank? contact-phone)) (assoc :participant/contact-phone contact-phone)
                 tenant-id (assoc :participant/tenant [:tenant/id tenant-id]))
        tx-data [tx-map
                 {:db/id [:event/id event-id]
                  :event/participants "new-participant"}]]
    (log/info "Adding participant" name "to event" event-id "by" actor-uid)
    (db/transact! tx-data)
    (find-by-id pid)))

(defn remove-from-event!
  "Retract a participant's link from the event and remove the participant entity."
  [event-id participant-id actor-uid]
  (let [event-eid [:event/id event-id]
        participant-eid [:participant/id participant-id]]
    (log/info "Removing participant" participant-id "from event" event-id "by" actor-uid)
    (db/transact! [[:db/retract event-eid :event/participants participant-eid]
                   [:db/retractEntity participant-eid]])))
