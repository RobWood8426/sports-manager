(ns sports-manager.routes.admin.events
  "Event, participant, team, and venue/field handlers."
  (:require [ring.util.response :as resp]
            [sports-manager.event :as event]
            [sports-manager.event-dashboard :as event-dashboard]
            [sports-manager.event-field :as event-field]
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
  "Resolve the sport-template records for an event's sport codes.
  `:event/sport-templates` is a set of sport-code keywords (e.g. :sport/rugby),
  so filter the templates whose :sport-template/code is in that set."
  [ev]
  (let [codes (set (:event/sport-templates ev))]
    (filter #(contains? codes (:sport-template/code %)) (sport-template/list-all))))

(defn event-new-page
  "GET /events/new — show the create-event form."
  [request]
  (let [[user-or-redirect tenant-id _] (shared/require-tenant request)]
    (if-not tenant-id
      user-or-redirect
      (shared/html (views.events/event-new-form
                    (sport-template/list-all)
                    (sport-template/selected-codes tenant-id)
                    {:lang (shared/current-lang request)})))))

(defn event-create
  "POST /events — validate and create a draft event, then continue to
  wizard step 2 (select fields)."
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
                        {:errors errors :values parsed :lang (shared/current-lang request)}))
          (let [ev (event/create! tenant-id
                                  (:user/firebase-uid current-user)
                                  parsed
                                  (:event/sports parsed))]
            (resp/redirect (str "/events/" (:event/id ev) "/wizard/fields"))))))))

(defn event-detail-page
  "GET /events/:id — show event detail with participants, teams, and fixtures."
  [request]
  (shared/with-tenant-event
    request
    (fn [_user _tenant-id ev _m]
      (let [event-id (:event/id ev)
            qp (or (:query-params request) {})
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
            venues (event-field/list-for-event event-id)
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
                                                 :new-code-fixture-id new-code-fixture
                                                 :lang (shared/current-lang request)}))))))

(defn dashboard-page [request]
  (shared/with-tenant-event
    request
    (fn [_user _tenant-id ev _m]
      (shared/html (views.events/event-dashboard
                    ev (event-dashboard/dashboard-data (:event/id ev))
                    {:lang (shared/current-lang request)})))))

(defn event-sport-config-save
  "POST /events/:id/sports/:sport/config — update per-sport overrides."
  [request]
  (shared/with-tenant-event
    request
    (fn [current-user _tenant-id ev _m]
      (let [event-id (:event/id ev)
            sport-str (get-in request [:path-params :sport])
            sport-code (when sport-str (keyword "sport" sport-str))
            params (shared/form-params request)
            vm-raw (get params "validation-model")
            vm (when (seq vm-raw) (keyword vm-raw))]
        (if-not sport-code
          {:status 400 :body "Bad request"}
          (do
            (event-sport/configure! event-id sport-code
                                    {:event-sport/validation-model vm}
                                    (:user/firebase-uid current-user))
            (resp/redirect (str "/events/" event-id))))))))

(defn event-publish
  "POST /events/:id/publish — transition a draft event to published."
  [request]
  (shared/with-tenant-event
    request
    (fn [current-user _tenant-id ev _m]
      (event/publish! (:event/id ev) (:user/firebase-uid current-user))
      (resp/redirect (str "/events/" (:event/id ev))))))

(defn event-qr
  "GET /events/:id/qr — render an HTML page with the event QR code and details."
  [request]
  (shared/with-tenant-event
    request
    (fn [_user _tenant-id ev _m]
      (let [spectator-url (str (shared/request->base-url request) "/e/" (:event/code ev))
            png (event/qr-png-for-text spectator-url)
            b64 (.encodeToString (java.util.Base64/getEncoder) png)
            fmt (java.text.SimpleDateFormat. "d MMM yyyy")]
        (shared/html
         (views.events/event-qr-page ev spectator-url b64 fmt
                                     {:lang (shared/current-lang request)}))))))

(defn participant-add
  "POST /events/:id/participants — add a participating school."
  [request]
  (shared/with-tenant-event
    request
    (fn [current-user _tenant-id ev _m]
      (let [event-id (:event/id ev)
            params (shared/form-params request)
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
                                                   :add-phone (get params "participant-phone")
                                                   :lang (shared/current-lang request)}))
          (do
            (participant/add-to-event! event-id
                                       (:user/firebase-uid current-user)
                                       data
                                       nil)
            (resp/redirect (str "/events/" event-id))))))))

(defn participant-remove
  "POST /events/:id/participants/:pid/remove — remove a participating school."
  [request]
  (shared/with-tenant-event
    request
    (fn [current-user _tenant-id ev _m]
      (let [event-id (:event/id ev)
            participant-id (shared/parse-event-id (get-in request [:path-params :pid]))]
        (when participant-id
          (participant/remove-from-event! event-id participant-id
                                          (:user/firebase-uid current-user)))
        (resp/redirect (str "/events/" event-id))))))

(defn team-create
  "POST /events/:id/teams — create a team label for this event."
  [request]
  (shared/with-tenant-event
    request
    (fn [_user _tenant-id ev _m]
      (let [event-id (:event/id ev)
            params (shared/form-params request)
            data (team/parse-form params)
            errors (team/validate data)]
        (if (seq errors)
          (resp/redirect (str "/events/" event-id))
          (do
            (team/create! event-id data)
            (resp/redirect (str "/events/" event-id))))))))

(defn team-delete
  "POST /events/:id/teams/:tid/delete — retract a team."
  [request]
  (shared/with-tenant-event
    request
    (fn [_user _tenant-id ev _m]
      (let [event-id (:event/id ev)
            team-id (shared/parse-event-id (get-in request [:path-params :tid]))]
        (when team-id
          (try (team/delete! team-id)
               (catch clojure.lang.ExceptionInfo _ nil)))
        (resp/redirect (str "/events/" event-id))))))

(defn venue-create
  "POST /events/:id/venues — add a new field to the school's pool and select
  it for this event in one step (used by the inline form on the event
  detail page; the wizard's step 2 has its own equivalent route)."
  [request]
  (shared/with-tenant-event
    request
    (fn [_user tenant-id ev _m]
      (let [event-id (:event/id ev)
            params (shared/form-params request)
            data (venue/parse-form params)
            errors (venue/validate data)]
        (if (seq errors)
          (resp/redirect (str "/events/" event-id))
          (let [v (venue/create! tenant-id data)]
            (event-field/add! event-id (:venue/id v))
            (resp/redirect (str "/events/" event-id))))))))

(defn venue-delete
  "POST /events/:id/venues/:vid/delete — deselect a field from this event.
  The field itself stays in the school's pool for other events to use."
  [request]
  (shared/with-tenant-event
    request
    (fn [_user _tenant-id ev _m]
      (let [event-id (:event/id ev)
            venue-id (shared/parse-event-id (get-in request [:path-params :vid]))]
        (when venue-id
          (event-field/remove! event-id venue-id))
        (resp/redirect (str "/events/" event-id))))))

(defn event-wizard-fields-page
  "GET /events/:id/wizard/fields — wizard step 2: select which of the
  school's fields are in use for this event."
  [request]
  (shared/with-tenant-event
    request
    (fn [_user tenant-id ev _m]
      (let [event-id (:event/id ev)]
        (shared/html (views.events/event-wizard-fields-page
                      event-id
                      (venue/list-by-tenant tenant-id)
                      (event-field/selected-venue-ids event-id)
                      {:lang (shared/current-lang request)}))))))

(defn event-wizard-fields-save
  "POST /events/:id/wizard/fields — replace the event's selected fields,
  then continue to wizard step 3."
  [request]
  (shared/with-tenant-event
    request
    (fn [_user _tenant-id ev _m]
      (let [event-id (:event/id ev)
            params (shared/form-params request)
            ids-raw (get params "field-id")
            venue-ids (->> (cond (nil? ids-raw) [] (string? ids-raw) [ids-raw] :else ids-raw)
                          (keep shared/parse-event-id))]
        (event-field/set-fields! event-id venue-ids)
        (resp/redirect (str "/events/" event-id "/wizard/age-groups"))))))

(defn event-wizard-fields-add
  "POST /events/:id/wizard/fields/new — add a new field to the school's
  pool and select it for this event, then stay on wizard step 2."
  [request]
  (shared/with-tenant-event
    request
    (fn [_user tenant-id ev _m]
      (let [event-id (:event/id ev)
            params (shared/form-params request)
            data (venue/parse-form params)
            errors (venue/validate data)]
        (if (seq errors)
          (shared/html (views.events/event-wizard-fields-page
                        event-id
                        (venue/list-by-tenant tenant-id)
                        (event-field/selected-venue-ids event-id)
                        {:errors errors :lang (shared/current-lang request)}))
          (let [v (venue/create! tenant-id data)]
            (event-field/add! event-id (:venue/id v))
            (resp/redirect (str "/events/" event-id "/wizard/fields"))))))))

(defn event-wizard-age-groups-page
  "GET /events/:id/wizard/age-groups — wizard step 3: select which age
  groups are competing in this event."
  [request]
  (shared/with-tenant-event
    request
    (fn [_user _tenant-id ev _m]
      (shared/html (views.events/event-wizard-age-groups-page
                    (:event/id ev)
                    (set (:event/age-groups ev))
                    {:lang (shared/current-lang request)})))))

(defn event-wizard-age-groups-save
  "POST /events/:id/wizard/age-groups — set the event's age groups, then
  finish the wizard at the event detail page."
  [request]
  (shared/with-tenant-event
    request
    (fn [_user _tenant-id ev _m]
      (let [event-id (:event/id ev)
            params (shared/form-params request)
            codes-raw (get params "age-group")
            codes (->> (cond (nil? codes-raw) [] (string? codes-raw) [codes-raw] :else codes-raw)
                      (map keyword))]
        (event/set-age-groups! event-id codes)
        (resp/redirect (str "/events/" event-id))))))
