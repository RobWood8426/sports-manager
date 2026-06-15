(ns sports-manager.team
  "Label-only team entities per event and sport (SPO-36).

  Teams are scoped to an event, participant (school), and sport template.
  No learner names, profiles, or individual stats are stored -- POPIA compliant."
  (:require [clojure.string :as str]
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
   :team/event
   :team/participant
   :team/sport])

(defn find-by-id
  "Return a team entity by UUID, or nil."
  [team-id]
  (db/pull pull-pattern team-id))

(defn list-by-event
  "Return all teams for an event, sorted by participant then team name."
  [event-id]
  (let [ids (map first (db/q '{:find  [?tid]
                               :in    [?eid]
                               :where [[?t :team/event ?eid]
                                       [?t :team/id ?tid]]}
                              event-id))]
    (->> ids
         (mapv #(db/pull pull-pattern %))
         (filter :team/id)
         (sort-by :team/name))))

(defn validate
  "Return a map of field-key -> error string for any validation failures."
  [{:team/keys [name participant-id sport-code]}]
  (cond-> {}
    (str/blank? name)      (assoc :team/name "Name is required")
    (nil? participant-id)  (assoc :team/participant "School is required")
    (nil? sport-code)      (assoc :team/sport "Sport is required")))

(defn parse-form
  "Parse raw form params into a :team/* keyed map."
  [params]
  (let [name-val    (get params "team-name")
        part-str    (get params "team-participant")
        sport-str   (get params "team-sport")
        age-val     (get params "team-age-group")
        gender-str  (get params "team-gender")]
    (cond-> {:team/name          (str/trim (or name-val ""))
             :team/participant-id (when-not (str/blank? part-str)
                                    (try (UUID/fromString part-str)
                                         (catch IllegalArgumentException _ nil)))
             :team/sport-code    (when-not (str/blank? sport-str)
                                   (keyword "sport" sport-str))}
      (not (str/blank? age-val))    (assoc :team/age-group (str/trim age-val))
      (not (str/blank? gender-str)) (assoc :team/gender (keyword gender-str)))))

(defn create!
  "Create a team for an event. Returns the created team entity."
  [event-id {:team/keys [name participant-id sport-code age-group gender]}]
  (let [event (db/entity event-id)
        _ (when-not event
            (throw (ex-info "Event not found" {:event/id event-id})))
        tenant-id (:event/tenant event)
        _ (when-not (db/exists? participant-id)
            (throw (ex-info "Participant not found" {:participant/id participant-id})))
        _ (when-not (db/exists? sport-code)
            (throw (ex-info "Sport template not found" {:sport-template/code sport-code})))
        team-id (UUID/randomUUID)
        doc (cond-> {:xt/id team-id
                     :team/id team-id
                     :team/name name
                     :team/event event-id
                     :team/participant participant-id
                     :team/sport sport-code
                     :team/tenant tenant-id}
              age-group (assoc :team/age-group age-group)
              gender    (assoc :team/gender gender))]
    (db/put! doc)
    (find-by-id team-id)))

(defn delete!
  "Delete a team entity. Throws if the team is not found."
  [team-id]
  (when-not (db/exists? team-id)
    (throw (ex-info "Team not found" {:team/id team-id})))
  (db/delete! team-id))
