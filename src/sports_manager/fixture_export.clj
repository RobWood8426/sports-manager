(ns sports-manager.fixture-export
  "CSV exports for an event (SPO-59): fixture list, results, and score audit.

  - Fixture list: columns match `fixture-import/importable-fields`, so an
    exported file round-trips cleanly back through the import wizard.
  - Results: accepted final scores per fixture.
  - Score audit: the append-only score-event log per fixture.

  All builders are pure (data-in, data-out). Callers (routes) fetch the
  tenant-scoped fixtures / finals / score-events and supply them."
  (:require [clojure.data.csv :as csv]
            [clojure.string :as str])
  (:import java.text.SimpleDateFormat
           java.util.TimeZone))

;; ---------------------------------------------------------------------------
;; Column headers — must match fixture-import/importable-fields labels so the
;; output re-imports without re-mapping.
;; ---------------------------------------------------------------------------

(def headers
  ["Sport" "Team A (name)" "Team B (name)"
   "Start time" "End time" "Age group" "Venue" "Match number"])

(defn- fmt-with
  "Format a java.util.Date with the given pattern in UTC. Returns \"\" for nil."
  [pattern ^java.util.Date d]
  (if d
    (.format (doto (SimpleDateFormat. pattern)
               (.setTimeZone (TimeZone/getTimeZone "UTC")))
             d)
    ""))

(defn- fmt-datetime
  "Format a Date as the importer-parseable \"yyyy-MM-dd'T'HH:mm\" string in UTC.
  Returns \"\" for nil."
  [d]
  (fmt-with "yyyy-MM-dd'T'HH:mm" d))

(defn- fmt-timestamp
  "Format a Date as a readable \"yyyy-MM-dd HH:mm:ss\" UTC timestamp (for results
  and audit exports, which are read-only reports rather than round-trip data)."
  [d]
  (fmt-with "yyyy-MM-dd HH:mm:ss" d))

(defn fixture->row
  "Convert one fixture entity (as returned by fixture/list-by-event, with nested
  sport-template / team maps) into a vector of string cells matching `headers`.
  The sport cell uses the template NAME (e.g. \"Rugby\"), which the importer
  parses back to the same :sport/* code."
  [f]
  [(get-in f [:fixture/sport-template :sport-template/name] "")
   (get-in f [:fixture/team-a :participant/name] "")
   (get-in f [:fixture/team-b :participant/name] "")
   (fmt-datetime (:fixture/start-at f))
   (fmt-datetime (:fixture/end-at f))
   (or (:fixture/age-group f) "")
   (or (:fixture/venue f) "")
   (or (:fixture/match-number f) "")])

(defn fixtures->rows
  "Return the full row matrix (header row + one row per fixture), sorted by
  match number for stable output."
  [fixtures]
  (into [headers]
        (map fixture->row)
        (sort-by :fixture/match-number fixtures)))

(defn fixtures->csv
  "Render fixtures as a CSV string with a header row. Quoting/escaping of cells
  containing commas or quotes is handled by clojure.data.csv."
  [fixtures]
  (with-out-str
    (csv/write-csv *out* (fixtures->rows fixtures))))

(defn filename
  "A safe download filename for an event's fixture export, e.g.
  \"fixtures-michaelhouse-inter-house-gala.csv\"."
  [event-name]
  (let [slug (-> (or event-name "event")
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"^-+|-+$" ""))]
    (str "fixtures-" (if (str/blank? slug) "event" slug) ".csv")))

;; ---------------------------------------------------------------------------
;; Results export — accepted final scores per fixture (SPO-59)
;; ---------------------------------------------------------------------------

(def results-headers
  ["Match number" "Sport" "Team A (name)" "Team B (name)"
   "Team A score" "Team B score" "Status" "Validation model" "Submitted at"])

(defn- enum-name
  "Render the last segment of a namespaced status/model keyword, or \"\"."
  [kw]
  (if kw (name kw) ""))

(defn result->row
  "Convert an enriched fixture {:fixture f :final fs} into a results row.
  `final` is a final-score entity (or nil if the fixture has no accepted final)."
  [{:keys [fixture final]}]
  [(or (:fixture/match-number fixture) "")
   (get-in fixture [:fixture/sport-template :sport-template/name] "")
   (get-in fixture [:fixture/team-a :participant/name] "")
   (get-in fixture [:fixture/team-b :participant/name] "")
   (str (or (:final-score/team-a-score final) ""))
   (str (or (:final-score/team-b-score final) ""))
   (enum-name (:final-score/status final))
   (enum-name (:final-score/validation-model final))
   (fmt-timestamp (:final-score/submitted-at final))])

(defn results->csv
  "Render accepted results as CSV. `enriched` is a seq of {:fixture :final},
  one per fixture that has an accepted final score. Sorted by match number."
  [enriched]
  (with-out-str
    (csv/write-csv *out*
                   (into [results-headers]
                         (map result->row)
                         (sort-by #(get-in % [:fixture :fixture/match-number]) enriched)))))

(defn results-filename [event-name]
  (str/replace (filename event-name) #"^fixtures-" "results-"))

;; ---------------------------------------------------------------------------
;; Score audit export — append-only score-event log per fixture (SPO-59)
;; ---------------------------------------------------------------------------

(def audit-headers
  ["Match number" "Sport" "Team A (name)" "Team B (name)"
   "Scored for" "Delta" "Period" "Recorded at" "Scorekeeper code"])

(defn- team-label
  "Map a :score-event.team/* keyword to the corresponding team name on the fixture."
  [fixture team-kw]
  (case team-kw
    :score-event.team/a (get-in fixture [:fixture/team-a :participant/name] "Team A")
    :score-event.team/b (get-in fixture [:fixture/team-b :participant/name] "Team B")
    ""))

(defn audit-event->row
  "Convert one score-event (with its parent fixture) into an audit row."
  [fixture e]
  [(or (:fixture/match-number fixture) "")
   (get-in fixture [:fixture/sport-template :sport-template/name] "")
   (get-in fixture [:fixture/team-a :participant/name] "")
   (get-in fixture [:fixture/team-b :participant/name] "")
   (team-label fixture (:score-event/team e))
   (str (:score-event/delta e))
   (or (:score-event/period e) "")
   (fmt-timestamp (:score-event/recorded-at e))
   (str (or (:score-event/scode e) ""))])

(defn audit->csv
  "Render the score-event audit log as CSV. `enriched` is a seq of
  {:fixture f :events [score-event ...]}; events are emitted in recorded-at
  order, grouped by fixture (match-number order)."
  [enriched]
  (with-out-str
    (csv/write-csv *out*
                   (into [audit-headers]
                         (mapcat (fn [{:keys [fixture events]}]
                                   (->> events
                                        (sort-by :score-event/recorded-at)
                                        (map #(audit-event->row fixture %)))))
                         (sort-by #(get-in % [:fixture :fixture/match-number]) enriched)))))

(defn audit-filename [event-name]
  (str/replace (filename event-name) #"^fixtures-" "score-audit-"))
