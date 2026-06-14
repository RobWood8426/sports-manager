(ns sports-manager.event
  "Event (sports day) creation, query, and lifecycle. (SPO-26, SPO-29, SPO-30)"
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [sports-manager.config :as config]
            [sports-manager.db :as db])
  (:import java.io.ByteArrayOutputStream
           java.time.Instant
           java.time.LocalDateTime
           java.time.ZoneOffset
           java.time.format.DateTimeParseException
           java.util.Date
           java.util.UUID))

;; ---------------------------------------------------------------------------
;; Parsing helpers
;; ---------------------------------------------------------------------------

(def ^:private code-chars "ABCDEFGHJKLMNPQRSTUVWXYZ23456789")

(defn- generate-code
  "Return a random 6-character alphanumeric event code (no O/0/1/I confusion)."
  []
  (let [rng (java.util.concurrent.ThreadLocalRandom/current)
        n (count code-chars)]
    (apply str (repeatedly 6 #(nth code-chars (.nextInt rng n))))))

(defn- parse-datetime
  "Parse an HTML datetime-local string (e.g. \"2026-06-20T09:00\") into a
  java.util.Date at UTC, or nil on blank / invalid input."
  [s]
  (when-not (str/blank? s)
    (try
      (-> (LocalDateTime/parse s)
          (.toInstant ZoneOffset/UTC)
          Date/from)
      (catch DateTimeParseException _ nil))))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn validate
  "Returns a map of field → error string. Empty map means valid."
  [{:event/keys [name start-at end-at visibility]}]
  (cond-> {}
    (str/blank? name) (assoc :event/name "Required")
    (nil? start-at) (assoc :event/start-at "Required — use YYYY-MM-DDThh:mm")
    (nil? end-at) (assoc :event/end-at "Required — use YYYY-MM-DDThh:mm")
    (nil? visibility) (assoc :event/visibility "Required")
    (and start-at end-at
         (.after start-at end-at))
    (assoc :event/end-at "Must be after start date/time")))

;; ---------------------------------------------------------------------------
;; Parsing raw form params → domain map
;; ---------------------------------------------------------------------------

(defn parse-form
  "Convert raw string form-params into an :event/* keyed map with parsed dates.
  Field names use dashes (e.g. \"event-name\") to avoid URL-encoding issues
  with slashes in browser form submissions."
  [params]
  (let [vis-raw (get params "event-visibility")
        acc-raw (get params "event-access-method")]
    {:event/name (str/trim (get params "event-name" ""))
     :event/description (str/trim (get params "event-description" ""))
     :event/start-at (parse-datetime (str/trim (get params "event-start-at" "")))
     :event/end-at (parse-datetime (str/trim (get params "event-end-at" "")))
     :event/visibility (when-not (str/blank? vis-raw) (keyword "event.visibility" vis-raw))
     :event/access-method (when-not (str/blank? acc-raw) (keyword "event.access" acc-raw))
     :event/sports (let [v (get params "event-sports")]
                     (cond
                       (nil? v) #{}
                       (string? v) #{(keyword "sport" v)}
                       :else (into #{} (map #(keyword "sport" %)) v)))}))

;; ---------------------------------------------------------------------------
;; Query
;; ---------------------------------------------------------------------------

(defn list-by-tenant
  "Return all events for `tenant-id`, most-recent first."
  [tenant-id]
  (let [eids (db/q '[:find [?e ...]
                     :in $ ?tid
                     :where
                     [?t :tenant/id ?tid]
                     [?e :event/tenant ?t]]
                   tenant-id)]
    (->> eids
         (mapv #(db/pull [:event/id :event/name :event/description
                          :event/start-at :event/end-at :event/status
                          :event/visibility :event/access-method
                          :event/code :event/published-at
                          {:event/sport-templates [:sport-template/code
                                                   :sport-template/name]}]
                         %))
         (filter :event/id)
         (sort-by :event/start-at #(compare %2 %1)))))

(defn find-by-id
  "Pull an event by UUID, or nil."
  [event-id]
  (let [e (db/pull [:event/id :event/name :event/description
                    :event/start-at :event/end-at :event/status
                    :event/visibility :event/access-method
                    :event/code :event/published-at
                    {:event/sport-templates [:sport-template/code
                                             :sport-template/name]}]
                   [:event/id event-id])]
    (when (:event/id e) e)))

(defn find-by-code
  "Pull a published event by its short access code, or nil."
  [code]
  (when-not (str/blank? code)
    (let [e (db/pull [:event/id :event/name :event/description
                      :event/start-at :event/end-at :event/status
                      :event/visibility :event/access-method
                      :event/code :event/published-at
                      {:event/sport-templates [:sport-template/code
                                               :sport-template/name]}]
                     [:event/code (str/upper-case code)])]
      (when (:event/id e) e))))

;; ---------------------------------------------------------------------------
;; Mutation
;; ---------------------------------------------------------------------------

(defn create!
  "Create an event draft. `tenant-id` is a UUID; `sport-codes` is a set of
  :sport/* keywords. Returns the created event entity map."
  [tenant-id actor-uid
   {:event/keys [name description start-at end-at visibility access-method]}
   sport-codes]
  (let [event-id (UUID/randomUUID)
        code (generate-code)
        now (Date/from (Instant/now))
        code->eid (into {}
                        (map (fn [[eid c]] [c eid]))
                        (db/q '[:find ?e ?code
                                :where [?e :sport-template/code ?code]]))
        sport-refs (keep code->eid sport-codes)
        tx-data [(cond-> {:db/id "new-event"
                          :event/id event-id
                          :event/name name
                          :event/code code
                          :event/status :event.status/draft
                          :event/tenant [:tenant/id tenant-id]
                          :event/created-at now}
                   (not (str/blank? description)) (assoc :event/description description)
                   start-at (assoc :event/start-at start-at)
                   end-at (assoc :event/end-at end-at)
                   visibility (assoc :event/visibility visibility)
                   access-method (assoc :event/access-method access-method)
                   (seq sport-refs) (assoc :event/sport-templates (vec sport-refs)))]]
    (log/info "Creating event draft" name "for tenant" tenant-id "by" actor-uid)
    (db/transact! tx-data)
    (find-by-id event-id)))

(defn publish!
  "Transition a draft event to published. Returns the updated event entity map.
  Throws ex-info if the event does not exist or is not in draft status."
  [event-id actor-uid]
  (let [ev (find-by-id event-id)]
    (when-not ev
      (throw (ex-info "Event not found" {:event/id event-id})))
    (when-not (= :event.status/draft (:event/status ev))
      (throw (ex-info "Event is not in draft status"
                      {:event/id event-id :event/status (:event/status ev)})))
    (let [now (Date/from (Instant/now))]
      (db/transact! [{:db/id [:event/id event-id]
                      :event/status :event.status/published
                      :event/published-at now}])
      (log/info "Event published" event-id "by" actor-uid)
      (find-by-id event-id))))

(defn qr-png-for-text
  "Return a byte array containing a 300×300 PNG QR code for an arbitrary URL/text.
  Requires ZXing on the classpath (com.google.zxing/core + javase)."
  [text]
  (let [BarcodeFormat (Class/forName "com.google.zxing.BarcodeFormat")
        EncodeHintType (Class/forName "com.google.zxing.EncodeHintType")
        QRCodeWriter (Class/forName "com.google.zxing.qrcode.QRCodeWriter")
        MatrixToImageWriter (Class/forName "com.google.zxing.client.j2se.MatrixToImageWriter")
        qr-format (.get (.getField BarcodeFormat "QR_CODE") nil)
        margin-hint (.get (.getField EncodeHintType "MARGIN") nil)
        hints (doto (java.util.HashMap.) (.put margin-hint (int 1)))
        writer (.getDeclaredConstructor QRCodeWriter (into-array Class []))
        writer-inst (.newInstance writer (into-array Object []))
        matrix (.invoke (.getMethod QRCodeWriter "encode"
                                    (into-array Class [String BarcodeFormat
                                                       Integer/TYPE Integer/TYPE
                                                       java.util.Map]))
                        writer-inst
                        (into-array Object [text qr-format (int 300) (int 300) hints]))
        baos (ByteArrayOutputStream.)
        write-method (.getMethod MatrixToImageWriter "writeToStream"
                                 (into-array Class [(Class/forName "com.google.zxing.common.BitMatrix")
                                                    String
                                                    java.io.OutputStream]))]
    (.invoke write-method nil (into-array Object [matrix "PNG" baos]))
    (.toByteArray baos)))

(defn qr-png
  "Return a byte array containing a 300×300 PNG QR code for the event's
  spectator URL (base-url/e/<code>). Requires ZXing on the classpath
  (com.google.zxing/core + javase). Throws if ZXing is not available."
  [ev]
  (qr-png-for-text (str config/base-url "/e/" (:event/code ev))))
