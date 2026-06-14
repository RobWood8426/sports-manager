(ns sports-manager.event-sport
  "Per-event sport configuration overrides (SPO-33).

  An event-sport-config sits between an event and a sport-template. Only
  fields the admin has explicitly overridden are stored; callers use
  effective-config to resolve the full config with template defaults filled in."
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d]
            [sports-manager.db :as db])
  (:import java.util.UUID))

;; ---------------------------------------------------------------------------
;; Pure helpers
;; ---------------------------------------------------------------------------

(defn effective-config
  "Merge a sport-template map with an event-sport override map.
  Override values win; template values fill gaps; nil overrides are ignored.
  The returned map uses :event-sport/* keys for overridden fields and
  :sport-template/* keys for template-only fields, plus a merged set of
  convenience keys under :effective/*."
  [template override]
  (let [scoring    (or (:event-sport/scoring-increments override)
                       (:sport-template/scoring-increments template))
        periods    (or (:event-sport/period-labels override)
                       (:sport-template/period-labels template))
        venue-type (:sport-template/venue-type template)
        venue-lbl  (:event-sport/venue-label override)
        val-model  (or (:event-sport/validation-model override)
                       :validation.model/single)
        standings  (if (contains? override :event-sport/track-standings)
                     (:event-sport/track-standings override)
                     false)]
    (cond-> (merge template override)
      scoring    (assoc :effective/scoring-increments scoring)
      periods    (assoc :effective/period-labels periods)
      venue-type (assoc :effective/venue-type venue-type)
      venue-lbl  (assoc :effective/venue-label venue-lbl)
      :always    (assoc :effective/validation-model val-model
                        :effective/track-standings standings))))

;; ---------------------------------------------------------------------------
;; Query
;; ---------------------------------------------------------------------------

(defn find-config
  "Return the event-sport-config entity for a given event and sport-template code,
  or nil if no override exists."
  [event-id sport-code]
  (when-let [eid (d/q '[:find ?c .
                         :in $ ?eid ?code
                         :where
                         [?e :event/id ?eid]
                         [?s :sport-template/code ?code]
                         [?c :event-sport/event ?e]
                         [?c :event-sport/sport-template ?s]]
                       (db/db) event-id sport-code)]
    (let [e (db/pull [:event-sport/id
                      :event-sport/scoring-increments
                      :event-sport/period-labels
                      :event-sport/venue-label
                      :event-sport/validation-model
                      :event-sport/track-standings
                      {:event-sport/sport-template [:sport-template/code
                                                    :sport-template/name
                                                    :sport-template/scoring-increments
                                                    :sport-template/period-labels
                                                    :sport-template/venue-type
                                                    :sport-template/is-template]}]
                     eid)]
      (when (:event-sport/id e) e))))

(defn list-by-event
  "Return effective configs for all sports attached to an event.
  Each entry is the template merged with any override via effective-config."
  [event-id]
  (let [db (db/db)
        sport-eids (d/q '[:find [?s ...]
                           :in $ ?eid
                           :where
                           [?e :event/id ?eid]
                           [?e :event/sport-templates ?s]]
                         db event-id)
        templates (mapv #(db/pull [:sport-template/code
                                   :sport-template/name
                                   :sport-template/scoring-increments
                                   :sport-template/period-labels
                                   :sport-template/venue-type
                                   :sport-template/is-template]
                                  %)
                        sport-eids)
        overrides (into {}
                        (map (fn [row]
                               [(:sport-template/code (:event-sport/sport-template row))
                                row]))
                        (d/q '[:find [(pull ?c [:event-sport/id
                                                :event-sport/scoring-increments
                                                :event-sport/period-labels
                                                :event-sport/venue-label
                                                :event-sport/validation-model
                                                :event-sport/track-standings
                                                {:event-sport/sport-template
                                                 [:sport-template/code]}]) ...]
                               :in $ ?eid
                               :where
                               [?e :event/id ?eid]
                               [?c :event-sport/event ?e]]
                             db event-id))]
    (->> templates
         (map (fn [tmpl]
                (effective-config tmpl (get overrides (:sport-template/code tmpl) {}))))
         (sort-by :sport-template/name))))

;; ---------------------------------------------------------------------------
;; Mutation
;; ---------------------------------------------------------------------------

(defn configure!
  "Upsert the sport config override for an event-sport pair.
  `overrides` is a map with any subset of:
    :event-sport/scoring-increments  (string or nil to clear)
    :event-sport/period-labels       (string or nil to clear)
    :event-sport/venue-label         (string or nil to clear)
    :event-sport/validation-model    (keyword or nil to clear)
    :event-sport/track-standings     (boolean)
  Passing nil for a key retracts that attribute."
  [event-id sport-code overrides actor-uid]
  (let [db (db/db)
        event-eid (d/q '[:find ?e . :in $ ?eid :where [?e :event/id ?eid]] db event-id)
        sport-eid (d/q '[:find ?s . :in $ ?code :where [?s :sport-template/code ?code]] db sport-code)]
    (when-not event-eid
      (throw (ex-info "Event not found" {:event/id event-id})))
    (when-not sport-eid
      (throw (ex-info "Sport template not found" {:sport-template/code sport-code})))
    (let [existing-eid (d/q '[:find ?c .
                               :in $ ?e ?s
                               :where
                               [?c :event-sport/event ?e]
                               [?c :event-sport/sport-template ?s]]
                             db event-eid sport-eid)
          config-id (or (when existing-eid
                          (:event-sport/id (db/pull [:event-sport/id] existing-eid)))
                        (UUID/randomUUID))
          db-id (if existing-eid existing-eid "new-event-sport")
          retractable-keys [:event-sport/scoring-increments
                            :event-sport/period-labels
                            :event-sport/venue-label
                            :event-sport/validation-model
                            :event-sport/track-standings]
          retracts (when existing-eid
                     (for [k retractable-keys
                           :when (and (contains? overrides k) (nil? (get overrides k)))]
                       [:db/retract existing-eid k (get (db/pull [k] existing-eid) k)]))
          asserts (reduce (fn [m [k v]]
                            (if (and (contains? overrides k) (some? v))
                              (assoc m k v)
                              m))
                          {:db/id db-id
                           :event-sport/id config-id
                           :event-sport/event event-eid
                           :event-sport/sport-template sport-eid}
                          overrides)
          tx-data (concat (filter some? retracts) [asserts])]
      (log/info "Configuring event-sport" event-id sport-code "by" actor-uid)
      (db/transact! (vec tx-data))
      (find-config event-id sport-code))))
