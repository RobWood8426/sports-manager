(ns sports-manager.routes.admin.events
  "Event, participant, team, and venue handlers."
  (:require [ring.util.response :as resp]
            [sports-manager.event :as event]
            [sports-manager.event-dashboard :as event-dashboard]
            [sports-manager.event-sport :as event-sport]
            [sports-manager.fixture :as fixture]
            [sports-manager.participant :as participant]
            [sports-manager.routes.shared :as shared]
            [sports-manager.scorekeeper-code :as scode]
            [sports-manager.sport-template :as sport-template]
            [sports-manager.team :as team]
            [sports-manager.venue :as venue]
            [sports-manager.views.events :as views.events]))

(defn- event-detail-sports
  "Resolve the sport-template records for an event's sport codes."
  [ev]
  (let [codes (into #{} (map :sport-template/code) (:event/sport-templates ev))]
    (filter #(contains? codes (:sport-template/code %)) (sport-template/list-all))))

(defn event-new-page
  "GET /events/new — show the create-event form."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (shared/html (views.events/event-new-form
                    (sport-template/list-all)
                    (sport-template/selected-codes tenant-id))))))

(defn event-create
  "POST /events — validate and create a draft event, then redirect home."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [current-user user-or-redirect
            params (shared/form-params request)
            parsed (event/parse-form params)
            errors (event/validate parsed)]
        (if (seq errors)
          (shared/html (views.events/event-new-form
                        (sport-template/list-all)
                        (sport-template/selected-codes tenant-id)
                        {:errors errors :values parsed}))
          (do
            (event/create! tenant-id
                           (:user/firebase-uid current-user)
                           parsed
                           (:event/sports parsed))
            (resp/redirect "/")))))))

(defn event-detail-page
  "GET /events/:id — show event detail with participants, teams, and fixtures."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [event-id (shared/parse-event-id (get-in request [:path-params :id]))
            ev (when event-id (event/find-by-id event-id))]
        (if-not ev
          {:status 404 :body "Event not found"}
          (let [qp (or (:query-params request) {})
                fixture-filters {:sport-code (get qp "sport")
                                 :team-name (get qp "team")
                                 :age-group (get qp "age-group")
                                 :venue (get qp "venue")
                                 :status (get qp "status")
                                 :date (get qp "date")}
                new-code (get qp "new-code")
                new-code-fixture (some-> (get qp "new-code-fixture") parse-uuid)
                participants (participant/list-by-event event-id)
                all-fixtures (fixture/list-by-event event-id)
                fixtures (fixture/filter-fixtures all-fixtures fixture-filters)
                sport-configs (event-sport/list-by-event event-id)
                venues (venue/list-by-event event-id)
                teams (team/list-by-event event-id)
                sports (event-detail-sports ev)
                codes-by-fixture (into {}
                                       (map (fn [f]
                                              [(:fixture/id f)
                                               (scode/list-by-fixture (:fixture/id f))]))
                                       all-fixtures)]
            (shared/html (views.events/event-detail ev participants fixtures sports
                                                    {:fixture-filters fixture-filters
                                                     :sport-configs sport-configs
                                                     :venues venues
                                                     :teams teams
                                                     :codes-by-fixture codes-by-fixture
                                                     :new-code new-code
                                                     :new-code-fixture-id new-code-fixture}))))))))

(defn dashboard-page [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [event-id (shared/parse-event-id (get-in request [:path-params :id]))]
        (if-not event-id
          (resp/not-found "Event not found")
          (let [ev (event/find-by-id event-id)]
            (if-not ev
              (resp/not-found "Event not found")
              (shared/html (views.events/event-dashboard ev (event-dashboard/dashboard-data event-id))))))))))

(defn event-sport-config-save
  "POST /events/:id/sports/:sport/config — update per-sport overrides."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [current-user user-or-redirect
            event-id (shared/parse-event-id (get-in request [:path-params :id]))
            sport-str (get-in request [:path-params :sport])
            sport-code (when sport-str (keyword "sport" sport-str))
            params (shared/form-params request)
            vm-raw (get params "validation-model")
            vm (when (seq vm-raw) (keyword vm-raw))]
        (if-not (and event-id sport-code)
          {:status 400 :body "Bad request"}
          (do
            (event-sport/configure! event-id sport-code
                                    {:event-sport/validation-model vm}
                                    (:user/firebase-uid current-user))
            (resp/redirect (str "/events/" event-id))))))))

(defn event-publish
  "POST /events/:id/publish — transition a draft event to published."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [current-user user-or-redirect
            event-id (shared/parse-event-id (get-in request [:path-params :id]))]
        (if-not event-id
          {:status 404 :body "Event not found"}
          (do
            (event/publish! event-id (:user/firebase-uid current-user))
            (resp/redirect (str "/events/" event-id))))))))

(defn event-qr
  "GET /events/:id/qr — render an HTML page with the event QR code and details."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [event-id (shared/parse-event-id (get-in request [:path-params :id]))
            ev (when event-id (event/find-by-id event-id))]
        (if-not ev
          {:status 404 :body "Event not found"}
          (let [spectator-url (str (shared/request->base-url request) "/e/" (:event/code ev))
                png (event/qr-png-for-text spectator-url)
                b64 (.encodeToString (java.util.Base64/getEncoder) png)
                fmt (java.text.SimpleDateFormat. "d MMM yyyy")]
            (shared/html
             (views.events/event-qr-page ev spectator-url b64 fmt))))))))

(defn participant-add
  "POST /events/:id/participants — add a participating school."
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
                data {:participant/name (get params "participant-name" "")
                      :participant/contact-email (get params "participant-email" "")
                      :participant/contact-phone (get params "participant-phone" "")}
                errors (participant/validate data)]
            (if (seq errors)
              (shared/html (views.events/event-detail ev
                                                      (participant/list-by-event event-id)
                                                      (fixture/list-by-event event-id)
                                                      (event-detail-sports ev)
                                                      {:errors errors
                                                       :add-name (get params "participant-name")
                                                       :add-email (get params "participant-email")
                                                       :add-phone (get params "participant-phone")}))
              (do
                (participant/add-to-event! event-id
                                           (:user/firebase-uid current-user)
                                           data
                                           nil)
                (resp/redirect (str "/events/" event-id))))))))))

(defn participant-remove
  "POST /events/:id/participants/:pid/remove — remove a participating school."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [current-user user-or-redirect
            event-id (shared/parse-event-id (get-in request [:path-params :id]))
            participant-id (shared/parse-event-id (get-in request [:path-params :pid]))]
        (when (and event-id participant-id)
          (participant/remove-from-event! event-id participant-id
                                          (:user/firebase-uid current-user)))
        (resp/redirect (str "/events/" event-id))))))

(defn team-create
  "POST /events/:id/teams — create a team label for this event."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [event-id (shared/parse-event-id (get-in request [:path-params :id]))
            ev (when event-id (event/find-by-id event-id))]
        (if-not ev
          {:status 404 :body "Event not found"}
          (let [params (shared/form-params request)
                data (team/parse-form params)
                errors (team/validate data)]
            (if (seq errors)
              (resp/redirect (str "/events/" event-id))
              (do
                (team/create! event-id data)
                (resp/redirect (str "/events/" event-id))))))))))

(defn team-delete
  "POST /events/:id/teams/:tid/delete — retract a team."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [event-id (shared/parse-event-id (get-in request [:path-params :id]))
            team-id (shared/parse-event-id (get-in request [:path-params :tid]))]
        (when team-id
          (try (team/delete! team-id)
               (catch clojure.lang.ExceptionInfo _ nil)))
        (resp/redirect (str "/events/" event-id))))))

(defn venue-create
  "POST /events/:id/venues — create a venue for this event."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [event-id (shared/parse-event-id (get-in request [:path-params :id]))
            ev (when event-id (event/find-by-id event-id))]
        (if-not ev
          {:status 404 :body "Event not found"}
          (let [params (shared/form-params request)
                data (venue/parse-form params)
                errors (venue/validate data)]
            (if (seq errors)
              (resp/redirect (str "/events/" event-id))
              (do
                (venue/create! event-id data)
                (resp/redirect (str "/events/" event-id))))))))))

(defn venue-delete
  "POST /events/:id/venues/:vid/delete — retract a venue."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (let [event-id (shared/parse-event-id (get-in request [:path-params :id]))
            venue-id (shared/parse-event-id (get-in request [:path-params :vid]))]
        (when venue-id
          (try (venue/delete! venue-id)
               (catch clojure.lang.ExceptionInfo _ nil)))
        (resp/redirect (str "/events/" event-id))))))
