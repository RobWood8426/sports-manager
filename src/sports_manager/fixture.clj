(ns sports-manager.fixture
  "Fixture (match/game) creation and query within an event (SPO-37)."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [sports-manager.db :as db]
            [xtdb.api :as xt])
  (:import java.time.Instant
           java.time.LocalDateTime
           java.time.ZoneOffset
           java.time.format.DateTimeParseException
           java.util.Date
           java.util.UUID))

(def ^:private pull-pattern
  [:fixture/id :fixture/match-number :fixture/age-group :fixture/venue
   :fixture/start-at :fixture/end-at :fixture/status :fixture/created-at
   :fixture/tenant :fixture/event
   {:fixture/sport-template [:sport-template/code :sport-template/name]}
   {:fixture/team-a [:participant/id :participant/name :participant/contact-email]}
   {:fixture/team-b [:participant/id :participant/name :participant/contact-email]}])

(defn- parse-datetime [s]
  (when-not (str/blank? s)
    (try
      (-> (LocalDateTime/parse s)
          (.toInstant ZoneOffset/UTC)
          Date/from)
      (catch DateTimeParseException _ nil))))

(defn- next-match-number [event-id]
  (let [n (count (db/q '{:find [?mn]
                         :in [?eid]
                         :where [[?f :fixture/event ?eid]
                                 [?f :fixture/match-number ?mn]]}
                       event-id))]
    (format "M%03d" (inc n))))

(defn parse-form [params]
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

(defn validate [{:fixture/keys [sport-code team-a-id team-b-id start-at end-at]}]
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
    (assoc :fixture/start-at "Required -- use YYYY-MM-DDThh:mm")
    (nil? end-at)
    (assoc :fixture/end-at "Required -- use YYYY-MM-DDThh:mm")
    (and start-at end-at (.after start-at end-at))
    (assoc :fixture/end-at "Must be after start time")))

(defn find-by-id [fixture-id]
  (db/pull pull-pattern fixture-id))

(defn list-by-event [event-id]
  (let [ids (map first (db/q '{:find [?fid]
                               :in [?eid]
                               :where [[?f :fixture/event ?eid]
                                       [?f :fixture/id ?fid]]}
                             event-id))]
    (->> ids
         (mapv #(db/pull pull-pattern %))
         (filter :fixture/id)
         (sort-by :fixture/start-at))))

(defn list-by-event-public [event-id]
  (->> (list-by-event event-id)
       (filter #(= :fixture.status/published (:fixture/status %)))))

(defn filter-fixtures
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
      (filter #(when-let [^Date d (:fixture/start-at %)]
                 (= date (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") d)))))))

(defn create!
  [event-id actor-uid
   {:fixture/keys [sport-code team-a-id team-b-id age-group venue start-at end-at]}]
  (let [event (db/entity event-id)
        _ (when-not event
            (throw (ex-info "Event not found" {:event/id event-id})))
        _ (when-not (db/exists? sport-code)
            (throw (ex-info "Sport template not found" {:sport-template/code sport-code})))
        _ (when-not (db/exists? team-a-id)
            (throw (ex-info "Participant not found" {:participant/id team-a-id :team :a})))
        _ (when-not (db/exists? team-b-id)
            (throw (ex-info "Participant not found" {:participant/id team-b-id :team :b})))
        tenant-id (:event/tenant event)
        fixture-id (UUID/randomUUID)
        match-number (next-match-number event-id)
        now (Date/from (Instant/now))
        doc (cond-> {:xt/id fixture-id
                     :fixture/id fixture-id
                     :fixture/match-number match-number
                     :fixture/event event-id
                     :fixture/sport-template sport-code
                     :fixture/team-a team-a-id
                     :fixture/team-b team-b-id
                     :fixture/status :fixture.status/draft
                     :fixture/tenant tenant-id
                     :fixture/created-at now}
              (not (str/blank? age-group)) (assoc :fixture/age-group age-group)
              (not (str/blank? venue)) (assoc :fixture/venue venue)
              start-at (assoc :fixture/start-at start-at)
              end-at (assoc :fixture/end-at end-at))]
    (log/info "Creating fixture" match-number "for event" event-id "by" actor-uid)
    (db/put! doc)
    (find-by-id fixture-id)))

(defn update! [fixture-id actor-uid changes]
  (let [check (find-by-id fixture-id)]
    (when-not check
      (throw (ex-info "Fixture not found" {:fixture/id fixture-id})))
    (let [existing (db/entity fixture-id)
          removable [:fixture/age-group :fixture/venue]
          to-remove (filterv #(and (contains? changes %) (nil? (get changes %))) removable)
          asserts (cond-> {}
                    (:fixture/sport-code changes)
                    (assoc :fixture/sport-template
                           (let [c (:fixture/sport-code changes)]
                             (when-not (db/exists? c)
                               (throw (ex-info "Sport template not found" {:sport-template/code c})))
                             c))
                    (:fixture/team-a-id changes)
                    (assoc :fixture/team-a
                           (let [p (:fixture/team-a-id changes)]
                             (when-not (db/exists? p)
                               (throw (ex-info "Participant not found" {:participant/id p :team :a})))
                             p))
                    (:fixture/team-b-id changes)
                    (assoc :fixture/team-b
                           (let [p (:fixture/team-b-id changes)]
                             (when-not (db/exists? p)
                               (throw (ex-info "Participant not found" {:participant/id p :team :b})))
                             p))
                    (and (contains? changes :fixture/age-group) (some? (:fixture/age-group changes)))
                    (assoc :fixture/age-group (:fixture/age-group changes))
                    (and (contains? changes :fixture/venue) (some? (:fixture/venue changes)))
                    (assoc :fixture/venue (:fixture/venue changes))
                    (:fixture/start-at changes) (assoc :fixture/start-at (:fixture/start-at changes))
                    (:fixture/end-at changes) (assoc :fixture/end-at (:fixture/end-at changes)))
          now (Date/from (Instant/now))
          audit-id (UUID/randomUUID)
          audit {:xt/id audit-id
                 :audit/id audit-id
                 :audit/action :fixture/edit
                 :audit/entity-type :fixture
                 :audit/entity-id fixture-id
                 :audit/actor actor-uid
                 :audit/before (pr-str (select-keys existing
                                                    [:fixture/sport-template :fixture/team-a
                                                     :fixture/team-b :fixture/age-group
                                                     :fixture/venue :fixture/start-at :fixture/end-at]))
                 :audit/tenant (:fixture/tenant existing)
                 :audit/at now}
          updated (apply dissoc (merge existing asserts) to-remove)]
      (log/info "Updating fixture" fixture-id "by" actor-uid)
      (db/submit! [[::xt/put updated] [::xt/put audit]])
      (find-by-id fixture-id))))

(defn publish! [fixture-id actor-uid]
  (let [check (find-by-id fixture-id)]
    (when-not check
      (throw (ex-info "Fixture not found" {:fixture/id fixture-id})))
    (when-not (= :fixture.status/draft (:fixture/status check))
      (throw (ex-info "Fixture is not in draft status"
                      {:fixture/id fixture-id :fixture/status (:fixture/status check)})))
    (let [existing (db/entity fixture-id)
          now (Date/from (Instant/now))
          audit-id (UUID/randomUUID)
          audit {:xt/id audit-id
                 :audit/id audit-id
                 :audit/action :fixture/publish
                 :audit/entity-type :fixture
                 :audit/entity-id fixture-id
                 :audit/actor actor-uid
                 :audit/tenant (:fixture/tenant existing)
                 :audit/at now}]
      (log/info "Publishing fixture" fixture-id "by" actor-uid)
      (db/submit! [[::xt/put (assoc existing :fixture/status :fixture.status/published)]
                   [::xt/put audit]])
      (find-by-id fixture-id))))
