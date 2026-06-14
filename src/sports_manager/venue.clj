(ns sports-manager.venue
  "Venue management per event (SPO-35).

  Venues are scoped to an event and tenant. Each venue has a name, type,
  optional map coordinates, and optional display order. Fixtures reference
  venues via :fixture/venue-ref (the legacy :fixture/venue string is retained
  for backwards compatibility)."
  (:require [clojure.string :as str]
            [datomic.api :as d]
            [sports-manager.db :as db])
  (:import java.util.UUID))

(def venue-types
  "Ordered list of [keyword label] pairs for UI dropdowns."
  [[:venue.type/field "Field"]
   [:venue.type/court "Court"]
   [:venue.type/pool "Pool"]
   [:venue.type/track "Track"]
   [:venue.type/pitch "Pitch"]
   [:venue.type/astro "Astroturf"]
   [:venue.type/hall "Hall"]
   [:venue.type/other "Other"]])

(def ^:private pull-pattern
  [:venue/id
   :venue/name
   :venue/display-order
   :venue/lat
   :venue/lng
   {:venue/type [:db/ident]}
   {:venue/event [:event/id :event/name]}])

(defn find-by-id
  "Return a venue entity by UUID, or nil."
  [venue-id]
  (let [e (db/pull pull-pattern [:venue/id venue-id])]
    (when (:venue/id e) e)))

(defn list-by-event
  "Return all venues for an event, sorted by display-order then name."
  [event-id]
  (let [eids (d/q '[:find [?v ...]
                    :in $ ?eid
                    :where
                    [?e :event/id ?eid]
                    [?v :venue/event ?e]]
                  (db/db) event-id)]
    (->> eids
         (mapv #(db/pull pull-pattern %))
         (filter :venue/id)
         (sort-by (juxt #(or (:venue/display-order %) Long/MAX_VALUE)
                        :venue/name)))))

(defn validate
  "Return a map of field-key → error string for any validation failures."
  [{:venue/keys [name type]}]
  (cond-> {}
    (str/blank? name) (assoc :venue/name "Name is required")
    (nil? type) (assoc :venue/type "Type is required")))

(defn parse-form
  "Parse raw form params into a :venue/* keyed map."
  [params]
  (let [name-val (get params "venue-name")
        type-str (get params "venue-type")
        lat-str (get params "venue-lat")
        lng-str (get params "venue-lng")
        order-str (get params "venue-order")]
    (cond-> {:venue/name (str/trim (or name-val ""))}
      (not (str/blank? type-str))
      (assoc :venue/type (keyword type-str))
      (not (str/blank? lat-str))
      (assoc :venue/lat (parse-double lat-str))
      (not (str/blank? lng-str))
      (assoc :venue/lng (parse-double lng-str))
      (not (str/blank? order-str))
      (assoc :venue/display-order (parse-long order-str)))))

(defn create!
  "Create a venue for an event. Returns the created venue entity.
  `data` is the output of parse-form (or equivalent :venue/* keyed map)."
  [event-id {:venue/keys [name type lat lng display-order]}]
  (let [db (db/db)
        event-eid (d/q '[:find ?e . :in $ ?eid :where [?e :event/id ?eid]] db event-id)
        _ (when-not event-eid
            (throw (ex-info "Event not found" {:event/id event-id})))
        tenant-eid (d/q '[:find ?t . :in $ ?e :where [?e :event/tenant ?t]] db event-eid)
        venue-id (UUID/randomUUID)
        tx (cond-> {:venue/id venue-id
                    :venue/name name
                    :venue/event event-eid
                    :venue/tenant tenant-eid}
             type (assoc :venue/type type)
             lat (assoc :venue/lat lat)
             lng (assoc :venue/lng lng)
             display-order (assoc :venue/display-order display-order))]
    (db/transact! [tx])
    (find-by-id venue-id)))

(defn update!
  "Update mutable fields on an existing venue. Returns the updated entity.
  `changes` is a :venue/* keyed map of only the fields to change."
  [venue-id {:venue/keys [name type lat lng display-order] :as changes}]
  (let [existing (find-by-id venue-id)
        _ (when-not existing
            (throw (ex-info "Venue not found" {:venue/id venue-id})))
        db (db/db)
        v-eid (d/q '[:find ?v . :in $ ?vid :where [?v :venue/id ?vid]] db venue-id)
        retractable [:venue/lat :venue/lng :venue/display-order]
        retracts (for [k retractable
                       :when (and (contains? changes k) (nil? (get changes k)))
                       :let [v (get existing k)]
                       :when v]
                   [:db/retract v-eid k v])
        asserts (cond-> {:db/id v-eid}
                  name (assoc :venue/name name)
                  type (assoc :venue/type type)
                  lat (assoc :venue/lat lat)
                  lng (assoc :venue/lng lng)
                  display-order (assoc :venue/display-order display-order))
        tx (into (vec retracts) [asserts])]
    (db/transact! tx)
    (find-by-id venue-id)))

(defn delete!
  "Retract a venue entity. Does not cascade to fixture venue-refs.
  Throws if the venue is not found."
  [venue-id]
  (let [existing (find-by-id venue-id)
        _ (when-not existing
            (throw (ex-info "Venue not found" {:venue/id venue-id})))
        db (db/db)
        v-eid (d/q '[:find ?v . :in $ ?vid :where [?v :venue/id ?vid]] db venue-id)]
    (db/transact! [[:db/retractEntity v-eid]])))
