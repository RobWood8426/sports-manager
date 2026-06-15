(ns sports-manager.sport-template
  "Platform sport templates (SPO-25).

  Templates are seeded platform-level entities (no tenant). A school selects
  which sports they run; the selection is stored as :tenant/sport-templates, a
  set of sport-template :xt/id keywords. Templates are immutable reference data."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [sports-manager.db :as db]
            [xtdb.api :as xt])
  (:import java.util.UUID))

;; ---------------------------------------------------------------------------
;; Seed data -- common South African school sports
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
  "Upsert platform sport templates. Idempotent -- :xt/id is the sport code keyword.
  New templates are inserted; existing ones are patched if they are missing SPO-32 fields."
  []
  (let [by-code (into {} (map (juxt :code identity)) platform-templates)
        existing (into #{} (map first)
                       (db/q '{:find [?code]
                               :where [[?e :sport-template/code ?code]
                                       [?e :sport-template/is-template true]]}))
        new-tmpl (remove #(existing (:code %)) (vals by-code))
        patch-tmpl (for [code existing
                         :let [tmpl (by-code code)
                               doc (db/entity code)]
                         :when (and tmpl doc (nil? (:sport-template/venue-type doc)))]
                     tmpl)]
    (when (seq new-tmpl)
      (log/info "Seeding" (count new-tmpl) "new sport templates")
      (db/put-many!
       (mapv (fn [{:keys [code name scoring-increments venue-type period-labels]}]
               (cond-> {:xt/id code
                        :sport-template/id (UUID/randomUUID)
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
      (doseq [{:keys [code scoring-increments venue-type period-labels]} patch-tmpl]
        (db/merge! code (cond-> {:sport-template/venue-type venue-type
                                 :sport-template/period-labels period-labels}
                          scoring-increments
                          (assoc :sport-template/scoring-increments scoring-increments)))))))

;; ---------------------------------------------------------------------------
;; Query
;; ---------------------------------------------------------------------------

(defn list-all
  "Returns all platform sport templates (is-template=true), sorted by name."
  []
  (->> (db/q '{:find [?code]
               :where [[?e :sport-template/code ?code]
                       [?e :sport-template/is-template true]]})
       (map (comp #(db/entity %) first))
       (filter some?)
       (sort-by :sport-template/name)))

(defn list-for-tenant
  "Returns platform templates plus this tenant's custom sports, sorted by name."
  [tenant-id]
  (let [platform (list-all)
        custom-codes (map first
                          (db/q '{:find [?code]
                                  :in [?tid]
                                  :where [[?e :sport-template/tenant ?tid]
                                          [?e :sport-template/is-template false]
                                          [?e :sport-template/code ?code]]}
                                tenant-id))
        custom (mapv #(db/entity %) custom-codes)]
    (->> (concat platform custom)
         (filter some?)
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
  Returns the new sport's code keyword (:xt/id)."
  [tenant-id {:sport-template/keys [name scoring-increments venue-type period-labels]}]
  (let [sport-id (UUID/randomUUID)
        sport-code (keyword "sport" (-> name
                                        str/lower-case
                                        (str/replace #"\s+" "-")
                                        (str/replace #"[^a-z0-9-]" "")
                                        (str "-" (subs (str sport-id) 0 8))))
        doc (cond-> {:xt/id sport-code
                     :sport-template/id sport-id
                     :sport-template/code sport-code
                     :sport-template/name name
                     :sport-template/is-template false
                     :sport-template/tenant tenant-id}
              scoring-increments (assoc :sport-template/scoring-increments scoring-increments)
              venue-type (assoc :sport-template/venue-type venue-type)
              period-labels (assoc :sport-template/period-labels period-labels))
        tenant (db/entity tenant-id)
        _ (when-not tenant
            (throw (ex-info "Tenant not found" {:tenant/id tenant-id})))
        current-sports (set (:tenant/sport-templates tenant))]
    (db/submit! [[::xt/put doc]
                 [::xt/put (assoc tenant :tenant/sport-templates
                                  (conj current-sports sport-code))]])
    sport-code))

(defn delete-custom!
  "Deletes a tenant's custom sport. Throws if not found or belongs to another tenant."
  [tenant-id sport-code]
  (let [doc (db/entity sport-code)]
    (when-not (and doc
                   (not (:sport-template/is-template doc))
                   (= tenant-id (:sport-template/tenant doc)))
      (throw (ex-info "Custom sport not found" {:sport-template/code sport-code
                                                :tenant/id tenant-id})))
    (db/delete! sport-code)))

(defn selected-codes
  "Returns the set of :sport-template/code keywords selected by tenant-id."
  [tenant-id]
  (set (:tenant/sport-templates (db/entity tenant-id))))

;; ---------------------------------------------------------------------------
;; Mutation
;; ---------------------------------------------------------------------------

(defn set-selection!
  "Replace the tenant's sport template selection with `codes` (a set of
  sport-template code keywords / :xt/ids). Unknown codes are silently dropped."
  [tenant-id codes actor]
  (let [tenant (db/entity tenant-id)
        _ (when-not tenant
            (throw (ex-info "Tenant not found" {:tenant/id tenant-id})))
        valid (set (filter db/exists? codes))]
    (log/info "Setting sport templates for tenant" tenant-id ":" valid)
    (db/merge! tenant-id {:tenant/sport-templates valid})
    (db/put! {:xt/id (UUID/randomUUID)
              :audit/id (UUID/randomUUID)
              :audit/action :tenant/set-sport-templates
              :audit/entity-type :tenant
              :audit/entity-id tenant-id
              :audit/actor actor
              :audit/tenant tenant-id
              :audit/after (str valid)})))
