(ns sports-manager.sport-template
  "Platform sport templates (SPO-25).

  Templates are seeded platform-level entities (no tenant). A school selects
  which sports they run; the selection is stored as :tenant/sport-templates refs.
  Templates are immutable reference data — schools never edit them directly."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [sports-manager.db :as db])
  (:import java.util.UUID))

;; ---------------------------------------------------------------------------
;; Seed data — common South African school sports
;; ---------------------------------------------------------------------------

(def ^:private platform-templates
  [{:code :sport/rugby :name "Rugby"
    :scoring-increments "[5 3 2 1]"
    :venue-type :venue.type/field
    :period-labels "Half,Half"}
   {:code :sport/cricket :name "Cricket"
    :scoring-increments nil
    :venue-type :venue.type/field
    :period-labels "Innings,Innings"}
   {:code :sport/hockey :name "Hockey"
    :scoring-increments "[1]"
    :venue-type :venue.type/field
    :period-labels "Half,Half"}
   {:code :sport/soccer :name "Soccer"
    :scoring-increments "[1]"
    :venue-type :venue.type/field
    :period-labels "Half,Half"}
   {:code :sport/netball :name "Netball"
    :scoring-increments "[1]"
    :venue-type :venue.type/court
    :period-labels "Quarter,Quarter,Quarter,Quarter"}
   {:code :sport/tennis :name "Tennis"
    :scoring-increments nil
    :venue-type :venue.type/court
    :period-labels "Set,Set,Set"}
   {:code :sport/swimming :name "Swimming"
    :scoring-increments nil
    :venue-type :venue.type/pool
    :period-labels "Race"}
   {:code :sport/athletics :name "Athletics"
    :scoring-increments nil
    :venue-type :venue.type/track
    :period-labels "Event"}
   {:code :sport/squash :name "Squash"
    :scoring-increments nil
    :venue-type :venue.type/court
    :period-labels "Game,Game,Game,Game,Game"}
   {:code :sport/basketball :name "Basketball"
    :scoring-increments "[3 2 1]"
    :venue-type :venue.type/court
    :period-labels "Quarter,Quarter,Quarter,Quarter"}
   {:code :sport/volleyball :name "Volleyball"
    :scoring-increments "[1]"
    :venue-type :venue.type/court
    :period-labels "Set,Set,Set,Set,Set"}
   {:code :sport/water-polo :name "Water Polo"
    :scoring-increments "[1]"
    :venue-type :venue.type/pool
    :period-labels "Quarter,Quarter,Quarter,Quarter"}])

(defn seed-templates!
  "Upsert platform sport templates. Idempotent — uses :sport-template/code as identity.
  On first seed, creates all templates. On subsequent calls, patches any templates
  that are missing the new SPO-32 fields (scoring-increments, venue-type, period-labels)."
  []
  (let [db (db/db)
        existing (into {}
                       (d/q '[:find ?code ?e
                              :where [?e :sport-template/code ?code]]
                            db))
        by-code (into {} (map (juxt :code identity)) platform-templates)
        new-codes (remove existing (keys by-code))
        new-tmpl (map by-code new-codes)
        patch-tmpl (for [[code eid] existing
                         :let [tmpl (by-code code)]
                         :when (and tmpl
                                    (nil? (d/q '[:find ?v . :in $ ?e :where [?e :sport-template/venue-type ?v]]
                                               db eid)))]
                     (assoc tmpl :db/id [:sport-template/code code]))]
    (when (seq new-tmpl)
      (log/info "Seeding" (count new-tmpl) "new sport templates")
      (db/transact! (mapv (fn [{:keys [code name scoring-increments venue-type period-labels]}]
                            (cond-> {:sport-template/id (UUID/randomUUID)
                                     :sport-template/code code
                                     :sport-template/name name
                                     :sport-template/is-template true
                                     :sport-template/period-labels period-labels
                                     :sport-template/venue-type venue-type}
                              scoring-increments
                              (assoc :sport-template/scoring-increments scoring-increments)))
                          new-tmpl)))
    (when (seq patch-tmpl)
      (log/info "Patching" (count patch-tmpl) "existing sport templates with SPO-32 fields")
      (db/transact! (mapv (fn [{:keys [db/id scoring-increments venue-type period-labels]}]
                            (cond-> {:db/id id
                                     :sport-template/is-template true
                                     :sport-template/venue-type venue-type
                                     :sport-template/period-labels period-labels}
                              scoring-increments
                              (assoc :sport-template/scoring-increments scoring-increments)))
                          patch-tmpl)))))

;; ---------------------------------------------------------------------------
;; Query
;; ---------------------------------------------------------------------------

(defn list-all
  "Returns all platform sport templates (is-template=true), sorted by name."
  []
  (->> (d/q '[:find ?id ?code ?name ?incr ?vtype ?periods
              :where
              [?e :sport-template/id ?id]
              [?e :sport-template/code ?code]
              [?e :sport-template/name ?name]
              [?e :sport-template/is-template true]
              [(get-else $ ?e :sport-template/scoring-increments "") ?incr]
              [(get-else $ ?e :sport-template/venue-type :none) ?vtype]
              [(get-else $ ?e :sport-template/period-labels "") ?periods]]
            (db/db))
       (map (fn [[id code name incr vtype periods]]
              (cond-> {:sport-template/id id
                       :sport-template/code code
                       :sport-template/name name
                       :sport-template/is-template true}
                (seq incr) (assoc :sport-template/scoring-increments incr)
                (not= :none vtype) (assoc :sport-template/venue-type vtype)
                (seq periods) (assoc :sport-template/period-labels periods))))
       (sort-by :sport-template/name)))

(defn list-for-tenant
  "Returns platform templates plus this tenant's custom sports, sorted by name."
  [tenant-id]
  (let [platform (list-all)
        custom (->> (d/q '[:find ?id ?code ?name ?incr ?vtype ?periods
                           :in $ ?tid
                           :where
                           [?t :tenant/id ?tid]
                           [?e :sport-template/tenant ?t]
                           [?e :sport-template/is-template false]
                           [?e :sport-template/id ?id]
                           [?e :sport-template/code ?code]
                           [?e :sport-template/name ?name]
                           [(get-else $ ?e :sport-template/scoring-increments "") ?incr]
                           [(get-else $ ?e :sport-template/venue-type :none) ?vtype]
                           [(get-else $ ?e :sport-template/period-labels "") ?periods]]
                         (db/db) tenant-id)
                    (map (fn [[id code name incr vtype periods]]
                           (cond-> {:sport-template/id id
                                    :sport-template/code code
                                    :sport-template/name name
                                    :sport-template/is-template false}
                             (seq incr) (assoc :sport-template/scoring-increments incr)
                             (not= :none vtype) (assoc :sport-template/venue-type vtype)
                             (seq periods) (assoc :sport-template/period-labels periods)))))]
    (->> (concat platform custom)
         (sort-by :sport-template/name))))

(defn validate-custom
  "Returns a map of field -> error string, or empty map if valid."
  [{:sport-template/keys [name]}]
  (cond-> {}
    (or (nil? name) (str/blank? name))
    (assoc :sport-template/name "Sport name is required")))

(defn parse-custom-form
  "Parses ring form params into a sport-template map for create-custom!."
  [params]
  (let [raw-incr (get params "sport-scoring-increments")
        raw-vtype (get params "sport-venue-type")
        raw-periods (get params "sport-period-labels")]
    (cond-> {:sport-template/name (str/trim (or (get params "sport-name") ""))}
      (seq raw-incr) (assoc :sport-template/scoring-increments raw-incr)
      (seq raw-vtype) (assoc :sport-template/venue-type (keyword raw-vtype))
      (seq raw-periods) (assoc :sport-template/period-labels raw-periods))))

(defn create-custom!
  "Creates a tenant-scoped custom sport and adds it to the tenant's selection.
  Returns the new sport's UUID."
  [tenant-id {:sport-template/keys [name scoring-increments venue-type period-labels]}]
  (let [tid (str "custom-sport-" (UUID/randomUUID))
        sport-id (UUID/randomUUID)
        sport-code (keyword "sport" (-> name
                                        str/lower-case
                                        (str/replace #"\s+" "-")
                                        (str/replace #"[^a-z0-9-]" "")
                                        (str "-" (subs (str sport-id) 0 8))))
        tx (cond-> {:db/id tid
                    :sport-template/id sport-id
                    :sport-template/code sport-code
                    :sport-template/name name
                    :sport-template/is-template false
                    :sport-template/tenant [:tenant/id tenant-id]}
             scoring-increments (assoc :sport-template/scoring-increments scoring-increments)
             venue-type (assoc :sport-template/venue-type venue-type)
             period-labels (assoc :sport-template/period-labels period-labels))]
    (db/transact! [tx {:db/id [:tenant/id tenant-id] :tenant/sport-templates tid}])
    sport-id))

(defn delete-custom!
  "Retracts a tenant's custom sport. Throws if not found or belongs to another tenant."
  [tenant-id sport-id]
  (let [db (db/db)
        eid (d/q '[:find ?e .
                   :in $ ?sid ?tid
                   :where
                   [?e :sport-template/id ?sid]
                   [?e :sport-template/is-template false]
                   [?e :sport-template/tenant ?t]
                   [?t :tenant/id ?tid]]
                 db sport-id tenant-id)]
    (when-not eid
      (throw (ex-info "Custom sport not found" {:sport-template/id sport-id :tenant/id tenant-id})))
    (db/transact! [[:db/retractEntity eid]])))

(defn selected-codes
  "Returns the set of :sport-template/code keywords selected by `tenant-id`."
  [tenant-id]
  (into #{}
        (map first)
        (d/q '[:find ?code
               :in $ ?tid
               :where
               [?t :tenant/id ?tid]
               [?t :tenant/sport-templates ?s]
               [?s :sport-template/code ?code]]
             (db/db) tenant-id)))

;; ---------------------------------------------------------------------------
;; Mutation
;; ---------------------------------------------------------------------------

(defn set-selection!
  "Replace the tenant's sport template selection with `codes` (a set of
  :sport-template/code keywords). Only retracts removed sports and only
  asserts added sports, avoiding same-datom retract+assert conflicts."
  [tenant-id codes actor]
  (let [db (db/db)
        code->eid (into {}
                        (map (fn [[eid code]] [code eid]))
                        (d/q '[:find ?e ?code
                               :where [?e :sport-template/code ?code]]
                             db))
        tenant-eid [:tenant/id tenant-id]
        current-eids (into #{}
                           (map first)
                           (d/q '[:find ?s
                                  :in $ ?tid
                                  :where
                                  [?t :tenant/id ?tid]
                                  [?t :tenant/sport-templates ?s]]
                                db tenant-id))
        new-eids (into #{} (keep code->eid) codes)
        to-retract (set/difference current-eids new-eids)
        to-assert (set/difference new-eids current-eids)
        retracts (mapv #(vector :db/retract tenant-eid :tenant/sport-templates %) to-retract)
        asserts (mapv #(hash-map :db/id tenant-eid :tenant/sport-templates %) to-assert)
        tx-data (into retracts asserts)]
    (log/info "Setting sport templates for tenant" tenant-id ":" codes)
    (when (seq tx-data)
      (db/transact! tx-data
                    {:audit/action :tenant/set-sport-templates
                     :audit/entity-type :tenant
                     :audit/actor actor
                     :audit/after (str codes)}))))

