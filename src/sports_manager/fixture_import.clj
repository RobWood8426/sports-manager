(ns sports-manager.fixture-import
  "CSV fixture import: parse, column mapping, validation, and bulk create (SPO-38/39).

  Import flow:
    1. parse-csv      — bytes → {:headers [...] :rows [[...] ...]}
    2. apply-mapping  — column mapping → seq of raw param maps
    3. validate-rows  — validate each row, detect duplicates
    4. import!        — create draft fixtures from valid rows"
  (:require [clojure.data.csv :as csv]
            [clojure.string :as str]
            [sports-manager.fixture :as fixture])
  (:import java.io.InputStreamReader
           java.nio.charset.StandardCharsets))

;; ---------------------------------------------------------------------------
;; Field definitions — the target fields an import can populate
;; ---------------------------------------------------------------------------

(def importable-fields
  "Ordered list of fields the admin can map CSV columns onto."
  [{:key :sport-code :label "Sport" :required? true}
   {:key :team-a :label "Team A (name)" :required? true}
   {:key :team-b :label "Team B (name)" :required? true}
   {:key :start-at :label "Start time" :required? true}
   {:key :end-at :label "End time" :required? false}
   {:key :age-group :label "Age group" :required? false}
   {:key :venue :label "Venue" :required? false}
   {:key :match-number :label "Match number" :required? false}])

;; ---------------------------------------------------------------------------
;; Step 1 — Parse CSV bytes
;; ---------------------------------------------------------------------------

(defn parse-csv
  "Parse CSV bytes (from a Ring multipart upload) into {:headers :rows}.
  Returns nil if the input is blank or unparseable."
  [input-stream]
  (try
    (let [reader (InputStreamReader. input-stream StandardCharsets/UTF_8)
          rows (csv/read-csv reader)]
      (when (seq rows)
        {:headers (mapv str/trim (first rows))
         :rows (mapv (fn [row] (mapv str/trim row)) (rest rows))}))
    (catch Exception _
      nil)))

;; ---------------------------------------------------------------------------
;; Step 2 — Apply column mapping
;; ---------------------------------------------------------------------------

(defn apply-mapping
  "Given parsed CSV {:headers :rows} and a mapping {field-key col-index-str},
  returns a seq of maps keyed by field keyword.
  Skips entirely blank rows."
  [{:keys [headers rows]} mapping]
  (let [idx-map (into {}
                      (keep (fn [[field-key col-str]]
                              (let [idx (parse-long (str col-str))]
                                (when (and idx (nat-int? idx) (< idx (count headers)))
                                  [field-key idx])))
                            mapping))]
    (->> rows
         (remove #(every? str/blank? %))
         (mapv (fn [row]
                 (into {}
                       (map (fn [[field-key idx]]
                              [field-key (nth row idx nil)])
                            idx-map)))))))

;; ---------------------------------------------------------------------------
;; Step 3 — Validate rows
;; ---------------------------------------------------------------------------

(defn- parse-sport-code [s]
  (when-not (str/blank? s)
    (keyword (str "sport/" (-> s str/trim str/lower-case
                               (str/replace #"\s+" "-"))))))

(defn- parse-datetime-str [s]
  (when-not (str/blank? s)
    (try
      ;; Accept ISO-8601 and "YYYY-MM-DD HH:mm" (space-separated)
      (let [normalized (str/replace (str/trim s) " " "T")]
        (java.util.Date/from
         (.toInstant
          (java.time.LocalDateTime/parse
           normalized
           (java.time.format.DateTimeFormatter/ofPattern
            (if (str/includes? normalized "T")
              "yyyy-MM-dd'T'HH:mm"
              "yyyy-MM-dd")))
          java.time.ZoneOffset/UTC)))
      (catch Exception _
        nil))))

(defn validate-rows
  "Validate mapped rows against participant names in the event.
  Returns {:valid [{:row-index n :params {...}}]
            :errors [{:row-index n :messages [...]}]}

  participant-names is a set of lower-cased names for matching."
  [mapped-rows participant-names]
  (let [seen-pairs (atom #{})]
    (reduce
     (fn [acc [idx row]]
       (let [sport-code (parse-sport-code (:sport-code row))
             team-a-raw (str/trim (or (:team-a row) ""))
             team-b-raw (str/trim (or (:team-b row) ""))
             team-a-lc (str/lower-case team-a-raw)
             team-b-lc (str/lower-case team-b-raw)
             start-at (parse-datetime-str (:start-at row))
             end-at (parse-datetime-str (:end-at row))
             errors (cond-> []
                      (nil? sport-code)
                      (conj "Sport is required")
                      (str/blank? team-a-raw)
                      (conj "Team A is required")
                      (str/blank? team-b-raw)
                      (conj "Team B is required")
                      (= team-a-lc team-b-lc)
                      (conj "Team A and Team B must be different")
                      (nil? start-at)
                      (conj "Start time is required or invalid")
                      (and end-at start-at (.before end-at start-at))
                      (conj "End time must be after start time")
                      (and (not (str/blank? team-a-raw))
                           (not (contains? participant-names team-a-lc)))
                      (conj (str "Team A \"" team-a-raw "\" not found in event participants"))
                      (and (not (str/blank? team-b-raw))
                           (not (contains? participant-names team-b-lc)))
                      (conj (str "Team B \"" team-b-raw "\" not found in event participants")))
             pair-key (when (and sport-code start-at)
                        #{[team-a-lc team-b-lc sport-code start-at]})
             duplicate? (and pair-key (contains? @seen-pairs (first pair-key)))]
         (when pair-key (swap! seen-pairs conj (first pair-key)))
         (if (or (seq errors) duplicate?)
           (update acc :errors conj
                   {:row-index idx
                    :row row
                    :messages (cond-> errors duplicate? (conj "Duplicate fixture (same teams, sport, time)"))})
           (update acc :valid conj
                   {:row-index idx
                    :params {:fixture/sport-code sport-code
                             :fixture/start-at start-at
                             :fixture/end-at end-at
                             :fixture/age-group (when-not (str/blank? (:age-group row)) (:age-group row))
                             :fixture/venue (when-not (str/blank? (:venue row)) (:venue row))
                             :_team-a-name team-a-raw
                             :_team-b-name team-b-raw}}))))
     {:valid [] :errors []}
     (map-indexed vector mapped-rows))))

;; ---------------------------------------------------------------------------
;; Step 4 — Import (create draft fixtures)
;; ---------------------------------------------------------------------------

(defn- find-participant-id [participants name-lc]
  (->> participants
       (filter #(= name-lc (str/lower-case (:participant/name %))))
       first
       :participant/id))

(defn import!
  "Create draft fixtures for all valid rows. Returns count of created fixtures.
  `valid-rows` is the :valid seq from validate-rows."
  [event-id actor-uid valid-rows participants]
  (reduce
   (fn [n {:keys [params]}]
     (let [a-id (find-participant-id participants (str/lower-case (:_team-a-name params)))
           b-id (find-participant-id participants (str/lower-case (:_team-b-name params)))]
       (when (and a-id b-id)
         (fixture/create! event-id actor-uid
                          (-> params
                              (assoc :fixture/team-a-id a-id
                                     :fixture/team-b-id b-id)
                              (dissoc :_team-a-name :_team-b-name))))
       (inc n)))
   0
   valid-rows))

;; ---------------------------------------------------------------------------
;; Temp file store — persists parsed CSV between wizard steps
;; ---------------------------------------------------------------------------

(defn write-import-temp!
  "Write EDN data to a temp file, return the file path string."
  [data]
  (let [f (java.io.File/createTempFile "spo-import-" ".edn")]
    (.deleteOnExit f)
    (spit f (pr-str data))
    (.getAbsolutePath f)))

(defn read-import-temp
  "Read EDN data from a temp file path. Returns nil if file missing."
  [path]
  (when path
    (try
      (let [f (java.io.File. path)]
        (when (.exists f)
          (read-string (slurp f))))
      (catch Exception _ nil))))

(defn delete-import-temp!
  "Delete the temp file for a given path."
  [path]
  (when path
    (try (.delete (java.io.File. path)) (catch Exception _))))
