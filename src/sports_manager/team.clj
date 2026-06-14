(ns sports-manager.team
  "Label-only team entities per event and sport (SPO-36).

  Teams are scoped to an event, participant (school), and sport template.
  No learner names, profiles, or individual stats are stored — POPIA compliant."
  (:require [clojure.string :as str]
            [datomic.api :as d]
            [sports-manager.db :as db])
  (:import java.util.UUID))

(def gender-options
  "Ordered list of [keyword label] pairs for UI dropdowns. nil = not specified."
  [[:team.gender/boys "Boys"]
   [:team.gender/girls "Girls"]
   [:team.gender/mixed "Mixed"]])

(def ^:private pull-pattern
  [:team/id
   :team/name
   :team/age-group
   :team/gender
   {:team/event [:event/id :event/name]}
   {:team/participant [:participant/id :participant/name]}
   {:team/sport [:sport-template/id :sport-template/code :sport-template/name]}])

(defn find-by-id
  "Return a team entity by UUID, or nil."
  [team-id]
  (let [e (db/pull pull-pattern [:team/id team-id])]
    (when (:team/id e) e)))

(defn list-by-event
  "Return all teams for an event, sorted by participant name then team name."
  [event-id]
  (let [eids (d/q '[:find [?t ...]
                    :in $ ?eid
                    :where
                    [?e :event/id ?eid]
                    [?t :team/event ?e]]
                  (db/db) event-id)]
    (->> eids
         (mapv #(db/pull pull-pattern %))
         (filter :team/id)
         (sort-by (juxt #(get-in % [:team/participant :participant/name])
                        :team/name)))))

(defn validate
  "Return a map of field-key → error string for any validation failures."
  [{:team/keys [name participant-id sport-code]}]
  (cond-> {}
    (str/blank? name) (assoc :team/name "Name is required")
    (nil? participant-id) (assoc :team/participant "School is required")
    (nil? sport-code) (assoc :team/sport "Sport is required")))

(defn parse-form
  "Parse raw form params into a :team/* keyed map."
  [params]
  (let [name-val (get params "team-name")
        part-str (get params "team-participant")
        sport-str (get params "team-sport")
        age-val (get params "team-age-group")
        gender-str (get params "team-gender")]
    (cond-> {:team/name (str/trim (or name-val ""))
             :team/participant-id (when-not (str/blank? part-str)
                                    (try (UUID/fromString part-str)
                                         (catch IllegalArgumentException _ nil)))
             :team/sport-code (when-not (str/blank? sport-str)
                                (keyword "sport" sport-str))}
      (not (str/blank? age-val)) (assoc :team/age-group (str/trim age-val))
      (not (str/blank? gender-str)) (assoc :team/gender (keyword gender-str)))))

(defn create!
  "Create a team for an event. Returns the created team entity.
  `data` is the output of parse-form (or equivalent :team/* keyed map)."
  [event-id {:team/keys [name participant-id sport-code age-group gender]}]
  (let [db (db/db)
        event-eid (d/q '[:find ?e . :in $ ?eid :where [?e :event/id ?eid]] db event-id)
        _ (when-not event-eid
            (throw (ex-info "Event not found" {:event/id event-id})))
        tenant-eid (d/q '[:find ?t . :in $ ?e :where [?e :event/tenant ?t]] db event-eid)
        part-eid (d/q '[:find ?p . :in $ ?pid :where [?p :participant/id ?pid]] db participant-id)
        _ (when-not part-eid
            (throw (ex-info "Participant not found" {:participant/id participant-id})))
        sport-eid (d/q '[:find ?s . :in $ ?code :where [?s :sport-template/code ?code]] db sport-code)
        _ (when-not sport-eid
            (throw (ex-info "Sport template not found" {:sport-template/code sport-code})))
        team-id (UUID/randomUUID)
        tx (cond-> {:team/id team-id
                    :team/name name
                    :team/event event-eid
                    :team/participant part-eid
                    :team/sport sport-eid
                    :team/tenant tenant-eid}
             age-group (assoc :team/age-group age-group)
             gender (assoc :team/gender gender))]
    (db/transact! [tx])
    (find-by-id team-id)))

(defn delete!
  "Retract a team entity. Throws if the team is not found."
  [team-id]
  (let [existing (find-by-id team-id)
        _ (when-not existing
            (throw (ex-info "Team not found" {:team/id team-id})))
        db (db/db)
        t-eid (d/q '[:find ?t . :in $ ?tid :where [?t :team/id ?tid]] db team-id)]
    (db/transact! [[:db/retractEntity t-eid]])))