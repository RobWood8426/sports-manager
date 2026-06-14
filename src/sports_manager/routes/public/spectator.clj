(ns sports-manager.routes.public.spectator
  "Public spectator routes — no auth required."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [ring.util.response :as resp]
            [sports-manager.event :as event]
            [sports-manager.event-sport :as event-sport]
            [sports-manager.final-score :as final-score]
            [sports-manager.fixture :as fixture]
            [sports-manager.participant :as participant]
            [sports-manager.routes.shared :as shared]
            [sports-manager.score :as score]
            [sports-manager.views.spectator :as views.spectator]))

(defn spectator-landing
  "GET /e — public code-entry page, or redirect to event if ?code= is provided."
  [request]
  (let [code (get (:query-params request) "code")]
    (if (str/blank? code)
      (shared/html (views.spectator/spectator-code-entry))
      (let [ev (event/find-by-code code)]
        (cond
          (nil? ev)
          (shared/html (views.spectator/spectator-code-entry {:error (str "No event found for code \"" code "\"")}))
          (not= :event.status/published (:event/status ev))
          (shared/html (views.spectator/spectator-code-entry {:error "This event is not yet published."}))
          :else
          (resp/redirect (str "/e/" (str/upper-case code))))))))

(defn spectator-event-page
  "GET /e/:code — public event landing page with filterable fixture list."
  [request]
  (let [code (get-in request [:path-params :code])
        ev (when code (event/find-by-code code))]
    (cond
      (nil? ev)
      {:status 404 :body "Event not found"}
      (not= :event.status/published (:event/status ev))
      {:status 404 :body "Event not found"}
      :else
      (let [event-id (:event/id ev)
            qp (:query-params request)
            sport-filter (get qp "sport" "")
            team-filter (get qp "team" "")
            filters {:sport-code sport-filter :team-name team-filter}
            all-fixtures (fixture/list-by-event-public event-id)
            filtered (fixture/filter-fixtures all-fixtures filters)
            enriched (map (fn [f]
                            (let [fid (:fixture/id f)
                                  live-score (score/current-score fid)
                                  final (->> (final-score/find-by-fixture fid)
                                             (filter #(= :final-score.status/accepted
                                                         (:final-score/status %)))
                                             first)]
                              (cond-> (assoc f :fixture/live-score live-score)
                                final (assoc :fixture/final-score-status
                                             (:final-score/status final)))))
                          filtered)
            sports (event-sport/list-by-event event-id)
            participants (participant/list-by-event event-id)]
        (shared/html (views.spectator/spectator-event ev participants enriched
                                                      {:filters filters :sports sports}))))))

(defn spectator-fixture-page
  "GET /e/fixture/:fid — public live score detail page for a single fixture."
  [request]
  (let [fid-str (get-in request [:path-params :fid])
        fid (shared/parse-event-id fid-str)
        f (when fid (fixture/find-by-id fid))]
    (if-not f
      {:status 404 :body "Fixture not found"}
      (let [live-score (score/current-score fid)
            final (->> (final-score/find-by-fixture fid)
                       (filter #(= :final-score.status/accepted (:final-score/status %)))
                       first)
            enriched (cond-> (assoc f :fixture/live-score live-score)
                       final (assoc :fixture/final-score-status (:final-score/status final)))]
        (shared/html (views.spectator/spectator-fixture enriched))))))

(defn spectator-fixture-score
  "GET /e/fixture/:fid/score — public JSON polling endpoint for live score."
  [request]
  (let [fid-str (get-in request [:path-params :fid])
        fid (shared/parse-event-id fid-str)
        f (when fid (fixture/find-by-id fid))]
    (if-not f
      {:status 404 :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:error "Fixture not found"})}
      (let [status (score/fixture-sync-status fid)
            final (->> (final-score/find-by-fixture fid)
                       (filter #(= :final-score.status/accepted (:final-score/status %)))
                       first)]
        {:status 200
         :headers {"Content-Type" "application/json"
                   "Cache-Control" "no-store"}
         :body (json/generate-string
                {:a (get-in status [:score :a])
                 :b (get-in status [:score :b])
                 :finalStatus (some-> (:final-score/status final) name)
                 :lastRecordedAt (some-> (:last-recorded-at status) .toInstant str)})}))))
