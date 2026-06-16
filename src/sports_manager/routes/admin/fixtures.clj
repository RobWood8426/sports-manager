(ns sports-manager.routes.admin.fixtures
  "Fixture, scorekeeper-code, dispute, and audit handlers."
  (:require [clojure.string :as str]
            [ring.util.response :as resp]
            [sports-manager.audit :as audit]
            [sports-manager.event :as event]
            [sports-manager.final-score :as final-score]
            [sports-manager.fixture :as fixture]
            [sports-manager.fixture-export :as fixture-export]
            [sports-manager.participant :as participant]
            [sports-manager.routes.shared :as shared]
            [sports-manager.score :as score]
            [sports-manager.scorekeeper-code :as scode]
            [sports-manager.sport-template :as sport-template]
            [sports-manager.venue :as venue]
            [sports-manager.views.events :as views.events]
            [sports-manager.views.fixtures :as views.fixtures]))

(defn- event-detail-sports [ev]
  (let [codes (into #{} (map :sport-template/code) (:event/sport-templates ev))]
    (filter #(contains? codes (:sport-template/code %)) (sport-template/list-all))))

(declare with-tenant-event csv-download)

(defn fixture-export-csv
  "GET /events/:id/fixtures/export — download the event's fixtures as a CSV
  whose columns round-trip through the import wizard. Tenant-isolated."
  [request]
  (with-tenant-event
    request
    (fn [_tenant-id ev]
      (let [fixtures (fixture/list-by-event (:event/id ev))]
        (csv-download (fixture-export/fixtures->csv fixtures)
                      (fixture-export/filename (:event/name ev)))))))

(defn- csv-download
  "Build a CSV download response with the given filename."
  [csv fname]
  (-> (resp/response csv)
      (resp/content-type "text/csv; charset=utf-8")
      (resp/header "Content-Disposition"
                   (str "attachment; filename=\"" fname "\""))))

(defn- with-tenant-event
  "Resolve the path :id to an event owned by the active tenant, then call
  (f tenant-id event). Returns a redirect/404 when unauthenticated or the event
  is missing / belongs to another tenant (tenant isolation)."
  [request f]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [event-id (shared/parse-event-id (get-in request [:path-params :id]))
            ev (when event-id (event/find-by-id event-id))]
        (if-not (and ev (= tenant-id (:event/tenant ev)))
          {:status 404 :body "Event not found"}
          (f tenant-id ev))))))

(defn fixture-results-export-csv
  "GET /events/:id/results/export — download accepted final scores as CSV."
  [request]
  (with-tenant-event
    request
    (fn [_tenant-id ev]
      (let [fixtures (fixture/list-by-event (:event/id ev))
            enriched (->> fixtures
                          (map (fn [f]
                                 (let [final (->> (final-score/find-by-fixture (:fixture/id f))
                                                  (filter #(= :final-score.status/accepted
                                                              (:final-score/status %)))
                                                  first)]
                                   (when final {:fixture f :final final}))))
                          (remove nil?))]
        (csv-download (fixture-export/results->csv enriched)
                      (fixture-export/results-filename (:event/name ev)))))))

(defn fixture-audit-export-csv
  "GET /events/:id/score-audit/export — download the append-only score-event log
  for the event as CSV."
  [request]
  (with-tenant-event
    request
    (fn [_tenant-id ev]
      (let [fixtures (fixture/list-by-event (:event/id ev))
            enriched (->> fixtures
                          (map (fn [f]
                                 {:fixture f
                                  :events (score/score-history (:fixture/id f))}))
                          (filter #(seq (:events %))))]
        (csv-download (fixture-export/audit->csv enriched)
                      (fixture-export/audit-filename (:event/name ev)))))))

(defn fixture-create
  "POST /events/:id/fixtures — validate and create a draft fixture."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [current-user user-or-redirect
            event-id (shared/parse-event-id (get-in request [:path-params :id]))
            ev (when event-id (event/find-by-id event-id))]
        (if-not ev
          {:status 404 :body "Event not found"}
          (let [params (shared/form-params request)
                parsed (fixture/parse-form params)
                errors (fixture/validate parsed)]
            (if (seq errors)
              (shared/html (views.events/event-detail ev
                                                      (participant/list-by-event event-id)
                                                      (fixture/list-by-event event-id)
                                                      (event-detail-sports ev)
                                                      {:fixture-errors errors
                                                       :venues (venue/list-by-event event-id)}))
              (do
                (fixture/create! event-id (:user/firebase-uid current-user) parsed)
                (resp/redirect (str "/events/" event-id))))))))))

(defn fixture-edit
  "POST /events/:id/fixtures/:fid — update a fixture's fields."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [current-user user-or-redirect
            event-id (shared/parse-event-id (get-in request [:path-params :id]))
            fixture-id (shared/parse-event-id (get-in request [:path-params :fid]))
            ev (when event-id (event/find-by-id event-id))]
        (if-not (and ev fixture-id)
          {:status 404 :body "Not found"}
          (let [params (shared/form-params request)
                parsed (fixture/parse-form params)
                errors (fixture/validate parsed)]
            (if (seq errors)
              (shared/html (views.events/event-detail ev
                                                      (participant/list-by-event event-id)
                                                      (fixture/list-by-event event-id)
                                                      (event-detail-sports ev)
                                                      {:fixture-errors errors}))
              (do
                (fixture/update! fixture-id (:user/firebase-uid current-user) parsed)
                (resp/redirect (str "/events/" event-id))))))))))

(defn fixture-publish
  "POST /events/:id/fixtures/:fid/publish — transition a fixture to published."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [current-user user-or-redirect
            event-id (shared/parse-event-id (get-in request [:path-params :id]))
            fixture-id (shared/parse-event-id (get-in request [:path-params :fid]))]
        (if-not (and event-id fixture-id)
          {:status 404 :body "Not found"}
          (do
            (fixture/publish! fixture-id (:user/firebase-uid current-user))
            (resp/redirect (str "/events/" event-id "#fixture-" fixture-id))))))))

(defn fixture-code-generate
  "POST /events/:id/fixtures/:fid/codes — generate a new scorekeeper code."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [current-user user-or-redirect
            event-id (shared/parse-event-id (get-in request [:path-params :id]))
            fixture-id (shared/parse-event-id (get-in request [:path-params :fid]))]
        (if-not (and event-id fixture-id)
          {:status 404 :body "Not found"}
          (let [{:keys [code]} (scode/generate! fixture-id (:user/firebase-uid current-user))]
            (resp/redirect (str "/events/" event-id "?new-code=" code
                                "&new-code-fixture=" fixture-id
                                "#fixture-" fixture-id))))))))

(defn fixture-code-revoke
  "POST /events/:id/fixtures/:fid/codes/:cid/revoke — revoke a scorekeeper code."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [current-user user-or-redirect
            event-id (shared/parse-event-id (get-in request [:path-params :id]))
            fixture-id (shared/parse-event-id (get-in request [:path-params :fid]))
            code-id (shared/parse-event-id (get-in request [:path-params :cid]))]
        (if-not (and event-id fixture-id code-id)
          {:status 404 :body "Not found"}
          (do
            (scode/revoke! code-id (:user/firebase-uid current-user))
            (resp/redirect (str "/events/" event-id "#fixture-" fixture-id))))))))

(defn fixture-code-qr
  "GET /events/:id/fixtures/:fid/codes/:cid/qr — QR code PNG for a scorekeeper code URL."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [event-id (shared/parse-event-id (get-in request [:path-params :id]))
            fixture-id (shared/parse-event-id (get-in request [:path-params :fid]))
            code-id (shared/parse-event-id (get-in request [:path-params :cid]))
            sc (when code-id (scode/find-by-id code-id))]
        (if-not (and event-id fixture-id sc)
          {:status 404 :body "Not found"}
          (let [url (str (shared/request->base-url request) "/score")
                png (event/qr-png-for-text url)]
            (-> (resp/response (java.io.ByteArrayInputStream. png))
                (resp/content-type "image/png")
                (resp/header "Content-Disposition" "inline")
                (resp/header "Content-Length" (str (alength png))))))))))

(defn fixture-assign-scorekeeper
  "POST /events/:id/fixtures/:fid/assign — create a labelled scorekeeper assignment."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [current-user user-or-redirect
            event-id (shared/parse-event-id (get-in request [:path-params :id]))
            fixture-id (shared/parse-event-id (get-in request [:path-params :fid]))
            label (str/trim (get (shared/form-params request) "label" ""))]
        (if-not (and event-id fixture-id)
          {:status 404 :body "Not found"}
          (do
            (scode/assign! fixture-id
                           (if (str/blank? label) "Scorekeeper" label)
                           (:user/firebase-uid current-user))
            (resp/redirect (str "/events/" event-id "#fixture-" fixture-id))))))))

(defn fixture-comparison-page
  "GET /events/:id/fixtures/:fid/comparison — show two scorekeeper submissions side-by-side."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [event-id (shared/parse-event-id (get-in request [:path-params :id]))
            fixture-id (shared/parse-event-id (get-in request [:path-params :fid]))
            f (when fixture-id (fixture/find-by-id fixture-id))]
        (if-not f
          {:status 404 :body "Fixture not found"}
          (let [fixture-with-event (assoc f :fixture/event {:event/id event-id})
                comparison (final-score/compare-submissions fixture-id)]
            (shared/html (views.fixtures/fixture-comparison fixture-with-event comparison))))))))

(defn fixture-dispute-resolve
  "POST /events/:id/fixtures/:fid/resolve — admin confirms scores and resolves dispute."
  [request]
  (let [[user-or-redirect _tenant-id] (shared/require-tenant request)]
    (if-not _tenant-id
      user-or-redirect
      (let [params (shared/form-params request)
            event-id (shared/parse-event-id (get-in request [:path-params :id]))
            fixture-id (shared/parse-event-id (get-in request [:path-params :fid]))
            confirmed-a (some-> (get params "confirmed-a") parse-long)
            confirmed-b (some-> (get params "confirmed-b") parse-long)
            reason (get params "reason" "")
            uid (:uid request)
            f (when fixture-id (fixture/find-by-id fixture-id))]
        (cond
          (not f)
          {:status 404 :body "Fixture not found"}

          (or (nil? confirmed-a) (nil? confirmed-b))
          (let [comparison (final-score/compare-submissions fixture-id)
                fixture-with-event (assoc f :fixture/event {:event/id event-id})]
            (shared/html (views.fixtures/fixture-comparison fixture-with-event comparison
                                                            {:resolve-errors {:form "Both scores are required."}})))

          :else
          (try
            (final-score/resolve-dispute! fixture-id uid confirmed-a confirmed-b reason)
            (resp/redirect (str "/events/" event-id "/fixtures/" fixture-id "/comparison"))
            (catch clojure.lang.ExceptionInfo e
              (let [comparison (final-score/compare-submissions fixture-id)
                    fixture-with-event (assoc f :fixture/event {:event/id event-id})]
                (shared/html (views.fixtures/fixture-comparison fixture-with-event comparison
                                                                {:resolve-errors {:form (ex-message e)}}))))))))))

(defn disputes-page
  "GET /disputes — list all disputed final-score submissions for the tenant."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [disputed (final-score/list-disputed-by-tenant tenant-id)]
        (shared/html (views.fixtures/disputes-page disputed))))))

(defn audit-log-page [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [params (get request :query-params {})
            action-str (get params "action")
            actor-str (get params "actor")
            action (when-not (str/blank? action-str) (keyword action-str))
            actor (when-not (str/blank? actor-str) actor-str)
            entries (cond
                      action (audit/list-by-action tenant-id action)
                      actor (audit/list-by-actor actor)
                      :else (audit/list-by-tenant tenant-id))
            filters {:action action :actor actor}]
        (shared/html (views.fixtures/audit-log-page entries {:filters filters}))))))
