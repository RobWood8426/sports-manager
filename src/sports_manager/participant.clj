(ns sports-manager.participant
  "Participating schools on an event (SPO-27)."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [sports-manager.db :as db]
            [xtdb.api :as xt])
  (:import java.util.UUID))

(def ^:private pull-pattern
  [:participant/id :participant/name :participant/contact-email
   :participant/contact-phone :participant/status
   :participant/tenant])

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn validate
  "Returns a map of field -> error string. Empty map means valid."
  [{:participant/keys [name]}]
  (cond-> {}
    (str/blank? name) (assoc :participant/name "Required")))

;; ---------------------------------------------------------------------------
;; Query
;; ---------------------------------------------------------------------------

(defn list-by-event
  "Return all participants for the given event UUID, sorted by name."
  [event-id]
  (let [event (db/entity event-id)
        pids (:event/participants event)]
    (->> (mapv #(db/pull pull-pattern %) pids)
         (filter :participant/id)
         (sort-by :participant/name))))

(defn find-by-id
  "Pull a participant entity by UUID, or nil."
  [participant-id]
  (db/pull pull-pattern participant-id))

;; ---------------------------------------------------------------------------
;; Mutation
;; ---------------------------------------------------------------------------

(defn add-to-event!
  "Create a participant and link it to the event. Returns the participant map.
  `tenant-id` is an optional UUID -- when provided, links to an existing tenant."
  [event-id actor-uid {:participant/keys [name contact-email contact-phone]} tenant-id]
  (let [pid (UUID/randomUUID)
        event (db/entity event-id)
        _ (when-not event
            (throw (ex-info "Event not found" {:event/id event-id})))
        doc (cond-> {:xt/id pid
                     :participant/id pid
                     :participant/name name
                     :participant/status :participant.status/confirmed}
              (not (str/blank? contact-email)) (assoc :participant/contact-email contact-email)
              (not (str/blank? contact-phone)) (assoc :participant/contact-phone contact-phone)
              tenant-id (assoc :participant/tenant tenant-id))
        updated-event (update event :event/participants (fnil conj #{}) pid)]
    (log/info "Adding participant" name "to event" event-id "by" actor-uid)
    (db/submit! [[::xt/put doc]
                 [::xt/put updated-event]])
    (find-by-id pid)))

(defn remove-from-event!
  "Remove a participant's link from the event and delete the participant entity."
  [event-id participant-id actor-uid]
  (let [event (db/entity event-id)
        _ (when-not event
            (throw (ex-info "Event not found" {:event/id event-id})))
        updated-event (update event :event/participants disj participant-id)]
    (log/info "Removing participant" participant-id "from event" event-id "by" actor-uid)
    (db/submit! [[::xt/put updated-event]
                 [::xt/delete participant-id]])))
