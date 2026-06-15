(ns sports-manager.event-sport
  "Per-event sport configuration overrides (SPO-33).

  An event-sport-config sits between an event and a sport-template. Only
  fields the admin has explicitly overridden are stored; callers use
  effective-config to resolve the full config with template defaults filled in."
  (:require [clojure.tools.logging :as log]
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
  (let [scoring (or (:event-sport/scoring-increments override)
                    (:sport-template/scoring-increments template))
        periods (or (:event-sport/period-labels override)
                    (:sport-template/period-labels template))
        venue-type (:sport-template/venue-type template)
        venue-lbl (:event-sport/venue-label override)
        val-model (or (:event-sport/validation-model override)
                      :validation.model/single)
        standings (if (contains? override :event-sport/track-standings)
                    (:event-sport/track-standings override)
                    false)]
    (cond-> (merge template override)
      scoring (assoc :effective/scoring-increments scoring)
      periods (assoc :effective/period-labels periods)
      venue-type (assoc :effective/venue-type venue-type)
      venue-lbl (assoc :effective/venue-label venue-lbl)
      :always (assoc :effective/validation-model val-model
                     :effective/track-standings standings))))

;; ---------------------------------------------------------------------------
;; Query
;; ---------------------------------------------------------------------------

(defn find-config
  "Return the event-sport-config entity for a given event and sport-template code,
  or nil if no override exists."
  [event-id sport-code]
  (when-let [[config-id] (first (db/q '{:find [?cid]
                                        :in [?eid ?scode]
                                        :where [[?c :event-sport/event ?eid]
                                                [?c :event-sport/sport-template ?scode]
                                                [?c :event-sport/id ?cid]]}
                                      event-id sport-code))]
    (db/pull [:xt/id
              :event-sport/id
              :event-sport/scoring-increments
              :event-sport/period-labels
              :event-sport/venue-label
              :event-sport/validation-model
              :event-sport/track-standings
              :event-sport/sport-template]
             config-id)))

(defn list-by-event
  "Return effective configs for all sports attached to an event.
  Each entry is the template merged with any override via effective-config."
  [event-id]
  (let [event-doc (db/entity event-id)
        sport-codes (get event-doc :event/sport-templates #{})
        templates (mapv db/entity sport-codes)
        overrides (->> (db/q '{:find [?scode ?cid]
                               :in [?eid]
                               :where [[?c :event-sport/event ?eid]
                                       [?c :event-sport/sport-template ?scode]
                                       [?c :event-sport/id ?cid]]}
                             event-id)
                       (map (fn [[scode cid]]
                              [scode (db/pull [:xt/id
                                               :event-sport/id
                                               :event-sport/scoring-increments
                                               :event-sport/period-labels
                                               :event-sport/venue-label
                                               :event-sport/validation-model
                                               :event-sport/track-standings
                                               :event-sport/sport-template]
                                              cid)]))
                       (into {}))]
    (->> templates
         (map (fn [tmpl]
                (effective-config tmpl (get overrides (:xt/id tmpl) {}))))
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
  (when-not (db/exists? event-id)
    (throw (ex-info "Event not found" {:event/id event-id})))
  (when-not (db/exists? sport-code)
    (throw (ex-info "Sport template not found" {:sport-template/code sport-code})))
  (let [existing-row (first (db/q '{:find [?cid]
                                    :in [?eid ?scode]
                                    :where [[?c :event-sport/event ?eid]
                                            [?c :event-sport/sport-template ?scode]
                                            [?c :event-sport/id ?cid]]}
                                  event-id sport-code))
        config-id (or (first existing-row) (UUID/randomUUID))
        retractable-keys [:event-sport/scoring-increments
                          :event-sport/period-labels
                          :event-sport/venue-label
                          :event-sport/validation-model
                          :event-sport/track-standings]
        nil-keys (filterv (fn [k]
                            (and (contains? overrides k) (nil? (get overrides k))))
                          retractable-keys)
        asserts (reduce (fn [m [k v]]
                          (if (and (contains? overrides k) (some? v))
                            (assoc m k v)
                            m))
                        {}
                        overrides)]
    (log/info "Configuring event-sport" event-id sport-code "by" actor-uid)
    (if existing-row
      (do
        (when (seq nil-keys)
          (db/retract-attrs! config-id nil-keys))
        (when (seq asserts)
          (db/merge! config-id asserts)))
      (db/put! (merge {:xt/id config-id
                       :event-sport/id config-id
                       :event-sport/event event-id
                       :event-sport/sport-template sport-code}
                      asserts)))
    (find-config event-id sport-code)))
