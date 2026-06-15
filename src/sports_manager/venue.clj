(ns sports-manager.venue
  "Venue management per event (SPO-35).

  Venues are scoped to an event and tenant. Each venue has a name, type,
  optional map coordinates, and optional display order. Fixtures reference
  venues via :fixture/venue-ref (the legacy :fixture/venue string is retained
  for backwards compatibility)."
  (:require [clojure.string :as str]
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
   :venue/type
   :venue/event])

(defn find-by-id
  "Return a venue entity by UUID, or nil."
  [venue-id]
  (db/pull pull-pattern venue-id))

(defn list-by-event
  "Return all venues for an event, sorted by display-order then name."
  [event-id]
  (let [ids (map first (db/q '{:find [?vid]
                               :in [?eid]
                               :where [[?v :venue/event ?eid]
                                       [?v :venue/id ?vid]]}
                             event-id))]
    (->> ids
         (mapv #(db/pull pull-pattern %))
         (filter :venue/id)
         (sort-by (juxt #(or (:venue/display-order %) Long/MAX_VALUE)
                        :venue/name)))))

(defn validate
  "Return a map of field-key -> error string for any validation failures."
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
      (not (str/blank? type-str)) (assoc :venue/type (keyword type-str))
      (not (str/blank? lat-str)) (assoc :venue/lat (parse-double lat-str))
      (not (str/blank? lng-str)) (assoc :venue/lng (parse-double lng-str))
      (not (str/blank? order-str)) (assoc :venue/display-order (parse-long order-str)))))

(defn create!
  "Create a venue for an event. Returns the created venue entity."
  [event-id {:venue/keys [name type lat lng display-order]}]
  (let [event (db/entity event-id)
        _ (when-not event
            (throw (ex-info "Event not found" {:event/id event-id})))
        tenant-id (:event/tenant event)
        venue-id (UUID/randomUUID)
        doc (cond-> {:xt/id venue-id
                     :venue/id venue-id
                     :venue/name name
                     :venue/event event-id
                     :venue/tenant tenant-id}
              type (assoc :venue/type type)
              lat (assoc :venue/lat lat)
              lng (assoc :venue/lng lng)
              display-order (assoc :venue/display-order display-order))]
    (db/put! doc)
    (find-by-id venue-id)))

(defn update!
  "Update mutable fields on an existing venue. Returns the updated entity.
  Passing nil for an optional field (lat, lng, display-order) removes it."
  [venue-id {:venue/keys [name type lat lng display-order] :as changes}]
  (let [existing (db/entity venue-id)
        _ (when-not existing
            (throw (ex-info "Venue not found" {:venue/id venue-id})))
        removable [:venue/lat :venue/lng :venue/display-order]
        to-remove (filterv #(and (contains? changes %) (nil? (get changes %))) removable)
        merged (merge existing
                      (cond-> {}
                        name (assoc :venue/name name)
                        type (assoc :venue/type type)
                        lat (assoc :venue/lat lat)
                        lng (assoc :venue/lng lng)
                        display-order (assoc :venue/display-order display-order)))
        updated (apply dissoc merged to-remove)]
    (db/put! updated)
    (find-by-id venue-id)))

(defn delete!
  "Delete a venue entity. Throws if the venue is not found."
  [venue-id]
  (when-not (db/exists? venue-id)
    (throw (ex-info "Venue not found" {:venue/id venue-id})))
  (db/delete! venue-id))
