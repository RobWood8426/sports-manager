(ns sports-manager.fixture
  "Fixture (match/game) creation and query within an event (SPO-37)."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [sports-manager.db :as db])
  (:import java.time.Instant
           java.time.LocalDateTime
           java.time.ZoneOffset
           java.time.format.DateTimeParseException
           java.util.Date
           java.util.UUID))

;; ---------------------------------------------------------------------------
;; Pull pattern reused across queries
;; ---------------------------------------------------------------------------

(def ^:private pull-pattern
  [:fixture/id :fixture/match-number :fixture/age-group :fixture/venue
   :fixture/start-at :fixture/end-at :fixture/status :fixture/created-at
   {:fixture/sport-template [:sport-template/code :sport-template/name]}
   {:fixture/team-a [:participant/id :participant/name]}
   {:fixture/team-b [:participant/id :participant/name]}])

;; ---------------------------------------------------------------------------
;; Parsing helpers
;; ---------------------------------------------------------------------------

(defn- parse-datetime [s]
  (when-not (str/blank? s)
    (try
      (-> (LocalDateTime/parse s)
          (.toInstant ZoneOffset/UTC)
          Date/from)
      (catch DateTimeParseException _ nil))))

(defn- next-match-number
  "Generate the next sequential match number for an event (e.g. \"M001\")."
  [db event-id]
  (let [existing (d/q '[:find [?mn ...]
                        :in $ ?eid
                        :where
                        [?e :event/id ?eid]
                        [?f :fixture/event ?e]
                        [?f :fixture/match-number ?mn]]
                      db event-id)
        n (count existing)]
    (format "M%03d" (inc n))))

;; ---------------------------------------------------------------------------
;; Parsing / validation
;; ---------------------------------------------------------------------------

(defn parse-form
  "Convert raw string form-params into a :fixture/* keyed map."
  [params]
  {:fixture/sport-code (let [v (get params "fixture-sport")]
                         (when-not (str/blank? v) (keyword "sport" v)))
   :fixture/team-a-id (let [v (get params "fixture-team-a")]
                        (when-not (str/blank? v)
                          (try (UUID/fromString v) (catch IllegalArgumentException _ nil))))
   :fixture/team-b-id (let [v (get params "fixture-team-b")]
                        (when-not (str/blank? v)
                          (try (UUID/fromString v) (catch IllegalArgumentException _ nil))))
   :fixture/age-group (str/trim (get params "fixture-age-group" ""))
   :fixture/venue (str/trim (get params "fixture-venue" ""))
   :fixture/start-at (parse-datetime (str/trim (get params "fixture-start-at" "")))
   :fixture/end-at (parse-datetime (str/trim (get params "fixture-end-at" "")))})

(defn validate
  "Returns a map of field → error string. Empty map means valid."
  [{:fixture/keys [sport-code team-a-id team-b-id start-at end-at]}]
  (cond-> {}
    (nil? sport-code)
    (assoc :fixture/sport-code "Required")
    (nil? team-a-id)
    (assoc :fixture/team-a-id "Required")
    (nil? team-b-id)
    (assoc :fixture/team-b-id "Required")
    (and team-a-id team-b-id (= team-a-id team-b-id))
    (assoc :fixture/team-b-id "Team B must differ from Team A")
    (nil? start-at)
    (assoc :fixture/start-at "Required — use YYYY-MM-DDThh:mm")
    (nil? end-at)
    (assoc :fixture/end-at "Required — use YYYY-MM-DDThh:mm")
    (and start-at end-at (.after start-at end-at))
    (assoc :fixture/end-at "Must be after start time")))

;; ---------------------------------------------------------------------------
;; Query
;; ---------------------------------------------------------------------------

(defn find-by-id
  "Pull a fixture by UUID, or nil."
  [fixture-id]
  (let [e (db/pull pull-pattern [:fixture/id fixture-id])]
    (when (:fixture/id e) e)))

(defn list-by-event
  "Return all fixtures for an event, sorted by start-at ascending."
  [event-id]
  (let [eids (d/q '[:find [?f ...]
                    :in $ ?eid
                    :where
                    [?e :event/id ?eid]
                    [?f :fixture/event ?e]]
                  (db/db) event-id)]
    (->> eids
         (mapv #(db/pull pull-pattern %))
         (filter :fixture/id)
         (sort-by :fixture/start-at))))

(defn list-by-event-public
  "Return published fixtures for an event, sorted by start-at ascending.
  Only :fixture.status/published fixtures are included — used by the spectator view."
  [event-id]
  (->> (list-by-event event-id)
       (filter #(= :fixture.status/published (:fixture/status %)))))

(defn filter-fixtures
  "Filter a seq of fixture maps by the given criteria. All criteria are optional;
  only non-blank / non-nil values are applied. Filters combine (AND semantics).
  Filter keys:
    :sport-code   — keyword or string matching :sport-template/code
    :team-name    — case-insensitive substring match on either team name
    :age-group    — case-insensitive substring match on :fixture/age-group
    :venue        — case-insensitive substring match on :fixture/venue
    :status       — keyword or string matching :fixture/status (e.g. :fixture.status/draft)
    :date         — string \"YYYY-MM-DD\"; keeps fixtures whose :fixture/start-at falls on that date"
  [fixtures {:keys [sport-code team-name age-group venue status date]}]
  (let [ci-contains? (fn [haystack needle]
                       (when (and haystack (not (str/blank? needle)))
                         (str/includes? (str/lower-case haystack)
                                        (str/lower-case needle))))
        sport-kw (when sport-code
                   (cond
                     (keyword? sport-code) sport-code
                     (str/includes? (str sport-code) "/") (keyword sport-code)
                     (not (str/blank? (str sport-code))) (keyword "sport" (str sport-code))))
        status-kw (when status
                    (if (keyword? status) status (keyword status)))]
    (cond->> fixtures
      sport-kw
      (filter #(= sport-kw (get-in % [:fixture/sport-template :sport-template/code])))
      (not (str/blank? team-name))
      (filter #(or (ci-contains? (get-in % [:fixture/team-a :participant/name]) team-name)
                   (ci-contains? (get-in % [:fixture/team-b :participant/name]) team-name)))
      (not (str/blank? age-group))
      (filter #(ci-contains? (:fixture/age-group %) age-group))
      (not (str/blank? venue))
      (filter #(ci-contains? (:fixture/venue %) venue))
      status-kw
      (filter #(= status-kw (:fixture/status %)))
      (not (str/blank? date))
      (filter #(when-let [^java.util.Date d (:fixture/start-at %)]
                 (= date (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") d)))))))

;; ---------------------------------------------------------------------------
;; Mutation
;; ---------------------------------------------------------------------------

(defn create!
  "Create a draft fixture within an event. Returns the created fixture entity.
  `params` is the output of parse-form (or equivalent :fixture/* keyed map)."
  [event-id actor-uid
   {:fixture/keys [sport-code team-a-id team-b-id age-group venue start-at end-at]}]
  (let [db (db/db)
        event-eid (d/q '[:find ?e . :in $ ?eid :where [?e :event/id ?eid]] db event-id)
        _ (when-not event-eid
            (throw (ex-info "Event not found" {:event/id event-id})))
        tenant-eid (d/q '[:find ?t . :in $ ?e :where [?e :event/tenant ?t]] db event-eid)
        sport-eid (d/q '[:find ?s . :in $ ?code :where [?s :sport-template/code ?code]] db sport-code)
        _ (when-not sport-eid
            (throw (ex-info "Sport template not found" {:sport-template/code sport-code})))
        team-a-eid (d/q '[:find ?p . :in $ ?pid :where [?p :participant/id ?pid]] db team-a-id)
        _ (when-not team-a-eid
            (throw (ex-info "Participant not found" {:participant/id team-a-id :team :a})))
        team-b-eid (d/q '[:find ?p . :in $ ?pid :where [?p :participant/id ?pid]] db team-b-id)
        _ (when-not team-b-eid
            (throw (ex-info "Participant not found" {:participant/id team-b-id :team :b})))
        fixture-id (UUID/randomUUID)
        match-number (next-match-number db event-id)
        now (Date/from (Instant/now))
        tx-data [(cond-> {:fixture/id fixture-id
                          :fixture/match-number match-number
                          :fixture/event event-eid
                          :fixture/sport-template sport-eid
                          :fixture/team-a team-a-eid
                          :fixture/team-b team-b-eid
                          :fixture/status :fixture.status/draft
                          :fixture/tenant tenant-eid
                          :fixture/created-at now}
                   (not (str/blank? age-group)) (assoc :fixture/age-group age-group)
                   (not (str/blank? venue)) (assoc :fixture/venue venue)
                   start-at (assoc :fixture/start-at start-at)
                   end-at (assoc :fixture/end-at end-at))]]
    (log/info "Creating fixture" match-number "for event" event-id "by" actor-uid)
    (db/transact! tx-data)
    (find-by-id fixture-id)))

(defn update!
  "Edit a fixture's mutable fields. Both draft and published fixtures may be
  edited; every call appends an audit entry recording before/after state.
  `changes` is a :fixture/* keyed map of only the fields to change — unspecified
  fields are left untouched. Editable keys:
    :fixture/sport-code  (keyword)
    :fixture/team-a-id   (UUID)
    :fixture/team-b-id   (UUID)
    :fixture/age-group   (string, nil to clear)
    :fixture/venue       (string, nil to clear)
    :fixture/start-at    (Date)
    :fixture/end-at      (Date)
  Returns the updated fixture entity map."
  [fixture-id actor-uid changes]
  (let [existing (find-by-id fixture-id)]
    (when-not existing
      (throw (ex-info "Fixture not found" {:fixture/id fixture-id})))
    (let [db (db/db)
          fix-eid (d/q '[:find ?f . :in $ ?fid :where [?f :fixture/id ?fid]] db fixture-id)
          tenant-eid (d/q '[:find ?t . :in $ ?f :where [?f :fixture/tenant ?t]] db fix-eid)
          before-str (pr-str (select-keys existing
                                          [:fixture/sport-template :fixture/team-a :fixture/team-b
                                           :fixture/age-group :fixture/venue
                                           :fixture/start-at :fixture/end-at]))
          resolve-sport (fn [code]
                          (when code
                            (or (d/q '[:find ?s . :in $ ?c :where [?s :sport-template/code ?c]] db code)
                                (throw (ex-info "Sport template not found" {:sport-template/code code})))))
          resolve-part (fn [pid team]
                         (when pid
                           (or (d/q '[:find ?p . :in $ ?pid :where [?p :participant/id ?pid]] db pid)
                               (throw (ex-info "Participant not found" {:participant/id pid :team team})))))
          asserts (cond-> {:db/id fix-eid}
                    (:fixture/sport-code changes)
                    (assoc :fixture/sport-template (resolve-sport (:fixture/sport-code changes)))
                    (:fixture/team-a-id changes)
                    (assoc :fixture/team-a (resolve-part (:fixture/team-a-id changes) :a))
                    (:fixture/team-b-id changes)
                    (assoc :fixture/team-b (resolve-part (:fixture/team-b-id changes) :b))
                    (and (contains? changes :fixture/age-group) (some? (:fixture/age-group changes)))
                    (assoc :fixture/age-group (:fixture/age-group changes))
                    (and (contains? changes :fixture/venue) (some? (:fixture/venue changes)))
                    (assoc :fixture/venue (:fixture/venue changes))
                    (:fixture/start-at changes)
                    (assoc :fixture/start-at (:fixture/start-at changes))
                    (:fixture/end-at changes)
                    (assoc :fixture/end-at (:fixture/end-at changes)))
          retracts (for [k [:fixture/age-group :fixture/venue]
                         :when (and (contains? changes k) (nil? (get changes k)))
                         :let [v (get existing k)]
                         :when v]
                     [:db/retract fix-eid k v])
          now (Date/from (Instant/now))
          audit-entry (cond-> {:audit/id (UUID/randomUUID)
                               :audit/action :fixture/edit
                               :audit/entity-type :fixture
                               :audit/entity-id fixture-id
                               :audit/actor actor-uid
                               :audit/before before-str
                               :audit/at now}
                        tenant-eid (assoc :audit/tenant tenant-eid))
          tx-data (into (vec retracts) [asserts audit-entry])]
      (log/info "Updating fixture" fixture-id "by" actor-uid)
      (db/transact! tx-data)
      (find-by-id fixture-id))))

(defn publish!
  "Transition a draft fixture to published. Returns the updated fixture.
  Throws ex-info if the fixture is not in draft status."
  [fixture-id actor-uid]
  (let [existing (find-by-id fixture-id)]
    (when-not existing
      (throw (ex-info "Fixture not found" {:fixture/id fixture-id})))
    (when-not (= :fixture.status/draft (:fixture/status existing))
      (throw (ex-info "Fixture is not in draft status"
                      {:fixture/id fixture-id :fixture/status (:fixture/status existing)})))
    (let [db (db/db)
          fix-eid (d/q '[:find ?f . :in $ ?fid :where [?f :fixture/id ?fid]] db fixture-id)
          tenant-eid (d/q '[:find ?t . :in $ ?f :where [?f :fixture/tenant ?t]] db fix-eid)
          now (Date/from (Instant/now))
          audit-entry (cond-> {:audit/id (UUID/randomUUID)
                               :audit/action :fixture/publish
                               :audit/entity-type :fixture
                               :audit/entity-id fixture-id
                               :audit/actor actor-uid
                               :audit/at now}
                        tenant-eid (assoc :audit/tenant tenant-eid))]
      (log/info "Publishing fixture" fixture-id "by" actor-uid)
      (db/transact! [{:db/id [:fixture/id fixture-id]
                      :fixture/status :fixture.status/published}
                     audit-entry])
      (find-by-id fixture-id))))
