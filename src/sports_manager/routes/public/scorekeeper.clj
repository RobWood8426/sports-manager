(ns sports-manager.routes.public.scorekeeper
  "Public scorekeeper routes — code-gated, no tenant auth required."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [ring.util.response :as resp]
            [sports-manager.final-score :as final-score]
            [sports-manager.routes.shared :as shared]
            [sports-manager.score :as score]
            [sports-manager.scorekeeper-code :as scode]
            [sports-manager.views.scorekeeper :as views.scorekeeper]))

(defn- period-labels-for-fixture
  "Parse period-labels from the fixture's sport-template.
  e.g. \"Half,Half\" → [\"Half 1\" \"Half 2\"]. Returns nil if no period config."
  [fixture]
  (when-let [raw (get-in fixture [:fixture/sport-template :sport-template/period-labels])]
    (when-not (str/blank? raw)
      (let [parts (str/split raw #",")]
        (map-indexed (fn [i label] (str (str/trim label) " " (inc i))) parts)))))

(defn scorekeeper-entry-page
  "GET /score — public code-entry page for scorekeepers."
  [_request]
  (shared/html (views.scorekeeper/scorekeeper-code-entry)))

(defn scorekeeper-code-submit
  "POST /score — verify the entered code; show confirmation page on success."
  [request]
  (let [code (str/trim (str/upper-case (get (shared/form-params request) "code" "")))
        ip (or (get-in request [:headers "x-forwarded-for"])
               (:remote-addr request)
               "unknown")]
    (if (str/blank? code)
      (shared/html (views.scorekeeper/scorekeeper-code-entry {:error "Please enter a code."}))
      (try
        (let [sc (scode/find-active-by-plaintext code ip)]
          (if sc
            (let [fixture (scode/find-fixture-by-code (:scode/id sc))]
              (if fixture
                (shared/html (views.scorekeeper/scorekeeper-confirm fixture (:scode/id sc)))
                (shared/html (views.scorekeeper/scorekeeper-code-entry {:error "This code is no longer valid."}))))
            (shared/html (views.scorekeeper/scorekeeper-code-entry {:error "Invalid or expired code. Please check and try again."}))))
        (catch clojure.lang.ExceptionInfo e
          (if (= :rate-limited (:type (ex-data e)))
            {:status 429 :body "Too many attempts. Please wait 15 minutes and try again."}
            (throw e)))))))

(defn scorekeeper-confirm-submit
  "POST /score/:fixture-id/confirm — scorekeeper confirms the correct game."
  [request]
  (let [fixture-id (shared/parse-event-id (get-in request [:path-params :fixture-id]))
        scode-id (shared/parse-event-id (get (shared/form-params request) "scode-id"))]
    (if-not (and fixture-id scode-id)
      {:status 400 :body "Bad request"}
      (let [fixture (scode/find-fixture-by-code scode-id)]
        (if (and fixture (= fixture-id (:fixture/id fixture)))
          (resp/redirect (str "/score/" fixture-id "/live?scode=" scode-id))
          (shared/html (views.scorekeeper/scorekeeper-code-entry {:error "Code does not match this fixture."})))))))

(defn scorekeeper-live-page
  "GET /score/:fixture-id/live — main scoring page. Marks the code as accessed on first load."
  [request]
  (let [fixture-id (shared/parse-event-id (get-in request [:path-params :fixture-id]))
        scode-id (shared/parse-event-id (get-in request [:query-params "scode"]))
        current-period (get-in request [:query-params "period"])]
    (if-not (and fixture-id scode-id)
      {:status 400 :body "Bad request"}
      (let [fixture (scode/find-fixture-by-code scode-id)]
        (if-not (and fixture (= fixture-id (:fixture/id fixture)))
          (shared/html (views.scorekeeper/scorekeeper-code-entry {:error "Code does not match this fixture."}))
          (do
            (try (scode/mark-accessed! scode-id) (catch Exception _))
            (let [current-score (score/current-score fixture-id)
                  period-labels (period-labels-for-fixture fixture)]
              (shared/html (views.scorekeeper/scorekeeper-live fixture scode-id current-score period-labels current-period)))))))))

(defn scorekeeper-start-game
  "POST /score/:fixture-id/start — scorekeeper begins scoring."
  [request]
  (let [fixture-id (shared/parse-event-id (get-in request [:path-params :fixture-id]))
        scode-id (shared/parse-event-id (get (shared/form-params request) "scode-id"))]
    (if-not (and fixture-id scode-id)
      {:status 400 :body "Bad request"}
      (do
        (try (scode/start-game! scode-id nil) (catch Exception _))
        (resp/redirect (str "/score/" fixture-id "/live?scode=" scode-id))))))

(defn scorekeeper-submit-final
  "POST /score/:fixture-id/submit — record final score and show confirmation."
  [request]
  (let [fixture-id (shared/parse-event-id (get-in request [:path-params :fixture-id]))
        scode-id (shared/parse-event-id (get (shared/form-params request) "scode-id"))]
    (if-not (and fixture-id scode-id)
      {:status 400 :body "Bad request"}
      (try
        (let [fs (final-score/submit! fixture-id scode-id nil)]
          (shared/html (views.scorekeeper/scorekeeper-submitted fs)))
        (catch clojure.lang.ExceptionInfo e
          (shared/html (views.scorekeeper/scorekeeper-code-entry {:error (ex-message e)})))))))

(defn scorekeeper-record-event
  "POST /score/:fixture-id/event — record a score delta. Returns JSON."
  [request]
  (let [fixture-id (shared/parse-event-id (get-in request [:path-params :fixture-id]))
        params (shared/form-params request)
        scode-id (shared/parse-event-id (get params "scode-id"))
        team-str (get params "team")
        delta (try (Long/parseLong (get params "delta" "0")) (catch Exception _ 0))
        period (let [p (str/trim (get params "period" ""))]
                 (when-not (str/blank? p) p))
        client-id (let [c (str/trim (get params "client-id" ""))]
                    (when-not (str/blank? c) c))
        client-ts (let [ts (str/trim (get params "client-ts" ""))]
                    (when-not (str/blank? ts)
                      (try (java.util.Date/from (java.time.Instant/parse ts))
                           (catch Exception _ nil))))]
    (if-not (and fixture-id scode-id (#{"a" "b"} team-str) (not (zero? delta)))
      {:status 400 :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:status "error" :message "Bad request"})}
      (try
        (let [e (score/record-event! fixture-id scode-id (keyword team-str) delta period
                                     {:client-id client-id :client-ts client-ts})
              _ (try (scode/go-live! scode-id nil) (catch Exception _))
              _ (try (score/detect-conflicts! fixture-id) (catch Exception _))
              current (score/current-score fixture-id)]
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body (json/generate-string {:status "ok"
                                        :clientId (or client-id (str (:score-event/id e)))
                                        :score current})})
        (catch clojure.lang.ExceptionInfo ex
          {:status 422
           :headers {"Content-Type" "application/json"}
           :body (json/generate-string {:status "error" :message (ex-message ex)})})))))

(defn scorekeeper-fixture-status
  "GET /score/:fixture-id/status — JSON polling endpoint for sync status."
  [request]
  (let [fixture-id (shared/parse-event-id (get-in request [:path-params :fixture-id]))]
    (if-not fixture-id
      {:status 400 :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:status "error" :message "Bad request"})}
      (let [status (score/fixture-sync-status fixture-id)]
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string
                {:score (:score status)
                 :conflictDetected (:conflict? status)
                 :eventCount (:event-count status)
                 :lastRecordedAt (some-> (:last-recorded-at status) .toInstant str)})}))))
