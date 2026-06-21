(ns sports-manager.venue
  "Venue (field) management, owned by the school/tenant (SPO-35).

  Venues are a reusable pool scoped to a tenant -- not to a single event.
  Events select a subset of the pool via sports-manager.event-field. Each
  venue has a name, type, optional map coordinates, and optional display
  order. Fixtures reference venues via :fixture/venue-ref (the legacy
  :fixture/venue string is retained for backwards compatibility)."
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
   :venue/tenant])

(defn find-by-id
  "Return a venue entity by UUID, or nil."
  [venue-id]
  (db/pull pull-pattern venue-id))

(defn list-by-tenant
  "Return all venues owned by a tenant (school), sorted by display-order then name."
  [tenant-id]
  (let [ids (map first (db/q '{:find [?vid]
                               :in [?tid]
                               :where [[?v :venue/tenant ?tid]
                                       [?v :venue/id ?vid]]}
                             tenant-id))]
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
  "Create a venue in a tenant's field pool. Returns the created venue entity."
  [tenant-id {:venue/keys [name type lat lng display-order]}]
  (when-not (db/exists? tenant-id)
    (throw (ex-info "Tenant not found" {:tenant/id tenant-id})))
  (let [venue-id (UUID/randomUUID)
        doc (cond-> {:xt/id venue-id
                     :venue/id venue-id
                     :venue/name name
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
