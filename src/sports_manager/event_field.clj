(ns sports-manager.event-field
  "Join entity selecting which of a school's venues (fields) are in use for
  a given event. Modeled on sports-manager.event-sport: the event picks a
  subset of a tenant-owned pool, here without per-selection overrides."
  (:require [sports-manager.db :as db]
            [sports-manager.venue :as venue])
  (:import java.util.UUID))

(defn- event-field-rows
  "Return [event-field-id venue-id] pairs selected for an event."
  [event-id]
  (db/q '{:find [?efid ?vid]
         :in [?eid]
         :where [[?ef :event-field/event ?eid]
                 [?ef :event-field/id ?efid]
                 [?ef :event-field/venue ?vid]]}
       event-id))

(defn selected-venue-ids
  "Return the set of venue ids currently selected for an event."
  [event-id]
  (into #{} (map second) (event-field-rows event-id)))

(defn in-use?
  "True if `venue-id` is currently selected for any event. Used to guard
  against deleting a venue out of the tenant's pool while an event still
  references it."
  [venue-id]
  (boolean (seq (db/q '{:find [?efid]
                        :in [?vid]
                        :where [[?ef :event-field/venue ?vid]
                                [?ef :event-field/id ?efid]]}
                      venue-id))))

(defn list-for-event
  "Return the venue entities selected for an event, sorted by display-order
  then name."
  [event-id]
  (->> (selected-venue-ids event-id)
       (map venue/find-by-id)
       (filter :venue/id)
       (sort-by (juxt #(or (:venue/display-order %) Long/MAX_VALUE)
                      :venue/name))))

(defn add!
  "Select `venue-id` for `event-id`. No-op if already selected."
  [event-id venue-id]
  (when-not (db/exists? event-id)
    (throw (ex-info "Event not found" {:event/id event-id})))
  (when-not (db/exists? venue-id)
    (throw (ex-info "Venue not found" {:venue/id venue-id})))
  (when-not (contains? (selected-venue-ids event-id) venue-id)
    (let [id (UUID/randomUUID)]
      (db/put! {:xt/id id
               :event-field/id id
               :event-field/event event-id
               :event-field/venue venue-id})))
  (list-for-event event-id))

(defn remove!
  "Deselect `venue-id` for `event-id`. No-op if not selected. Does not
  delete the underlying venue from the tenant's pool."
  [event-id venue-id]
  (doseq [[efid vid] (event-field-rows event-id)
          :when (= vid venue-id)]
    (db/delete! efid))
  (list-for-event event-id))

(defn set-fields!
  "Replace the full set of venues selected for `event-id` with `venue-ids`."
  [event-id venue-ids]
  (when-not (db/exists? event-id)
    (throw (ex-info "Event not found" {:event/id event-id})))
  (let [desired (set venue-ids)
        existing (event-field-rows event-id)
        to-remove (->> existing (remove (fn [[_ vid]] (contains? desired vid))) (map first))
        existing-venues (into #{} (map second) existing)
        to-add (remove existing-venues desired)]
    (doseq [efid to-remove] (db/delete! efid))
    (doseq [vid to-add]
      (let [id (UUID/randomUUID)]
        (db/put! {:xt/id id
                 :event-field/id id
                 :event-field/event event-id
                 :event-field/venue vid})))
    (list-for-event event-id)))
