(ns sports-manager.event-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [sports-manager.auth :as auth]
            [sports-manager.db :as db]
            [sports-manager.event :as event]
            [sports-manager.membership :as membership]
            [sports-manager.rbac :as rbac]
            [sports-manager.routes.core :as routes]
            [sports-manager.session :as session]
            [sports-manager.sport-template :as sport-template]
            [sports-manager.test-db :as test-db])
  (:import java.util.UUID))

(use-fixtures :each test-db/with-db)

;; ---------------------------------------------------------------------------
;; parse-form
;; ---------------------------------------------------------------------------

(deftest parse-form-strings
  (let [result (event/parse-form {"event-name" "Sports Day"
                                  "event-description" "Annual event"
                                  "event-start-at" "2026-08-01T09:00"
                                  "event-end-at" "2026-08-01T17:00"
                                  "event-visibility" "public"
                                  "event-access-method" "code-gated"})]
    (is (= "Sports Day" (:event/name result)))
    (is (= "Annual event" (:event/description result)))
    (is (inst? (:event/start-at result)))
    (is (inst? (:event/end-at result)))
    (is (= #{} (:event/sports result)))
    (is (= :event.visibility/public (:event/visibility result)))
    (is (= :event.access/code-gated (:event/access-method result)))))

(deftest parse-form-sports-single
  (let [result (event/parse-form {"event-name" "Day"
                                  "event-start-at" "2026-08-01T09:00"
                                  "event-end-at" "2026-08-01T17:00"
                                  "event-sports" "rugby"})]
    (is (= #{:sport/rugby} (:event/sports result)))))

(deftest parse-form-sports-multi
  (let [result (event/parse-form {"event-name" "Day"
                                  "event-start-at" "2026-08-01T09:00"
                                  "event-end-at" "2026-08-01T17:00"
                                  "event-sports" ["rugby" "cricket"]})]
    (is (= #{:sport/rugby :sport/cricket} (:event/sports result)))))

(deftest parse-form-bad-date
  (let [result (event/parse-form {"event-name" "Day"
                                  "event-start-at" "not-a-date"
                                  "event-end-at" ""})]
    (is (nil? (:event/start-at result)))
    (is (nil? (:event/end-at result)))))

;; ---------------------------------------------------------------------------
;; validate
;; ---------------------------------------------------------------------------

(deftest validate-valid
  (is (empty? (event/validate {:event/name "Day"
                               :event/start-at #inst "2026-08-01T09:00"
                               :event/end-at #inst "2026-08-01T17:00"
                               :event/visibility :event.visibility/public}))))

(deftest validate-missing-name
  (let [errs (event/validate {:event/name ""
                              :event/start-at #inst "2026-08-01T09:00"
                              :event/end-at #inst "2026-08-01T17:00"})]
    (is (contains? errs :event/name))))

(deftest validate-missing-dates
  (let [errs (event/validate {:event/name "Day" :event/start-at nil :event/end-at nil})]
    (is (contains? errs :event/start-at))
    (is (contains? errs :event/end-at))))

(deftest validate-missing-visibility
  (let [errs (event/validate {:event/name "Day"
                              :event/start-at #inst "2026-08-01T09:00"
                              :event/end-at #inst "2026-08-01T17:00"
                              :event/visibility nil})]
    (is (contains? errs :event/visibility))))

(deftest validate-end-before-start
  (let [errs (event/validate {:event/name "Day"
                              :event/start-at #inst "2026-08-01T17:00"
                              :event/end-at #inst "2026-08-01T09:00"})]
    (is (contains? errs :event/end-at))))

;; ---------------------------------------------------------------------------
;; create! / list-by-tenant / find-by-id
;; ---------------------------------------------------------------------------

(defn- seed-tenant+user! []
  (rbac/seed-roles!)
  (let [tid (UUID/randomUUID)
        uid "test-uid-evt"]
    (db/transact!
     [{:tenant/id tid :tenant/name "Test School"
       :tenant/status :active :tenant/contact-email "a@b.com"
       :tenant/created-at (java.util.Date.)}
      {:user/firebase-uid uid :user/email "evt@test.com"
       :user/status :active
       :user/created-at (java.util.Date.)}])
    (membership/create! uid tid)
    (rbac/grant-role! [:user/firebase-uid uid] :role.name/school-admin :actor uid)
    {:tenant-id tid :uid uid}))

(deftest create-event-draft
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        _ (sport-template/seed-templates!)
        parsed {:event/name "Sports Day 2026"
                :event/description "Annual event"
                :event/start-at #inst "2026-08-01T09:00"
                :event/end-at #inst "2026-08-01T17:00"}
        result (event/create! tenant-id uid parsed #{:sport/rugby :sport/cricket})]
    (is (uuid? (:event/id result)))
    (is (= "Sports Day 2026" (:event/name result)))
    (is (= :event.status/draft (:event/status result)))
    (is (= 2 (count (:event/sport-templates result))))
    (is (string? (:event/code result)))
    (is (= 6 (count (:event/code result))))))

(deftest create-event-no-sports
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        parsed {:event/name "Simple Day"
                :event/start-at #inst "2026-09-01T09:00"
                :event/end-at #inst "2026-09-01T17:00"}
        result (event/create! tenant-id uid parsed #{})]
    (is (= :event.status/draft (:event/status result)))
    (is (empty? (:event/sport-templates result)))))

(deftest list-by-tenant-returns-events
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        _ (sport-template/seed-templates!)
        parsed1 {:event/name "Day A" :event/start-at #inst "2026-08-02T09:00"
                 :event/end-at #inst "2026-08-02T17:00"}
        parsed2 {:event/name "Day B" :event/start-at #inst "2026-07-01T09:00"
                 :event/end-at #inst "2026-07-01T17:00"}]
    (event/create! tenant-id uid parsed1 #{})
    (event/create! tenant-id uid parsed2 #{})
    (let [events (event/list-by-tenant tenant-id)]
      (is (= 2 (count events)))
      ;; most-recent first (Day A starts after Day B)
      (is (= "Day A" (:event/name (first events)))))))

(deftest list-by-tenant-empty
  (let [{:keys [tenant-id]} (seed-tenant+user!)]
    (is (empty? (event/list-by-tenant tenant-id)))))

(deftest find-by-id-not-found
  (is (nil? (event/find-by-id (UUID/randomUUID)))))

(deftest find-by-code-returns-event
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        _ (sport-template/seed-templates!)
        parsed {:event/name "Code Day"
                :event/start-at #inst "2026-08-01T09:00"
                :event/end-at #inst "2026-08-01T17:00"}
        created (event/create! tenant-id uid parsed #{})
        code (:event/code created)]
    (is (some? code))
    (let [found (event/find-by-code code)]
      (is (= (:event/id created) (:event/id found))))
    (is (nil? (event/find-by-code "NOPE99")))))

(deftest publish-event-transitions-status
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        parsed {:event/name "Pub Day"
                :event/start-at #inst "2026-08-01T09:00"
                :event/end-at #inst "2026-08-01T17:00"}
        created (event/create! tenant-id uid parsed #{})
        event-id (:event/id created)
        result (event/publish! event-id uid)]
    (is (= :event.status/published (:event/status result)))
    (is (inst? (:event/published-at result)))))

(deftest publish-already-published-throws
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        parsed {:event/name "Pub Day 2"
                :event/start-at #inst "2026-08-01T09:00"
                :event/end-at #inst "2026-08-01T17:00"}
        created (event/create! tenant-id uid parsed #{})
        event-id (:event/id created)]
    (event/publish! event-id uid)
    (is (thrown? clojure.lang.ExceptionInfo
                 (event/publish! event-id uid)))))

;; ---------------------------------------------------------------------------
;; Route integration tests
;; ---------------------------------------------------------------------------

(defn- find-user-from-test-db [uid]
  (db/pull
   [:user/firebase-uid :user/email :user/name :user/status
    {:user/roles [{:role/permissions [:db/ident]}
                  :role/name]}]
   [:user/firebase-uid uid]))

(defn- parse-query-string [qs]
  (if (str/blank? qs)
    {}
    (into {} (for [pair (str/split qs #"&")
                   :let [[k v] (str/split pair #"=" 2)]
                   :when (seq k)]
               [k (or v "")]))))

(defn- do-req
  ([method uri uid] (do-req method uri uid {} nil))
  ([method uri uid form-params] (do-req method uri uid form-params nil))
  ([method uri uid form-params tenant-id]
   (with-redefs [session/uid-from-request (fn [req] (:uid req))
                 auth/find-user find-user-from-test-db]
     (let [[path qs] (str/split uri #"\?" 2)
           query-params (parse-query-string qs)
           csrf-token "test-csrf-token"
           params (if (= method :post)
                    (assoc (or form-params {}) "__anti-forgery-token" csrf-token)
                    (or form-params {}))
           cookie (test-db/session-cookie
                   (cond-> {:ring.middleware.anti-forgery/anti-forgery-token csrf-token}
                     tenant-id (assoc :active-tenant-id tenant-id)))]
       (routes/handler
        {:request-method method
         :uri path
         :query-string (or qs "")
         :query-params query-params
         :uid uid
         :headers {"host" "localhost" "cookie" (str "ring-session=" cookie)}
         :params params
         :form-params params})))))

(deftest event-new-page-redirects-unauthenticated
  (let [resp (do-req :get "/events/create" nil)]
    (is (= 302 (:status resp)))
    (is (= "/login" (get-in resp [:headers "Location"])))))

(deftest event-new-page-renders-form
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        _ (sport-template/seed-templates!)
        resp (do-req :get "/events/create" uid {} tenant-id)]
    (is (= 200 (:status resp)))
    (is (re-find #"Create event" (:body resp)))
    (is (re-find #"event-name" (:body resp)))
    (is (re-find #"Rugby" (:body resp)))))

(deftest event-create-validation-errors
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        resp (do-req :post "/events" uid
                     {"event-name" ""
                      "event-start-at" ""
                      "event-end-at" ""}
                     tenant-id)]
    (is (= 200 (:status resp)))
    (is (re-find #"Required" (:body resp)))))

(deftest event-create-success-redirects-home
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        _ (sport-template/seed-templates!)
        resp (do-req :post "/events" uid
                     {"event-name" "Sports Day"
                      "event-start-at" "2026-08-01T09:00"
                      "event-end-at" "2026-08-01T17:00"
                      "event-visibility" "public"}
                     tenant-id)]
    (is (= 302 (:status resp)))
    (is (= "/" (get-in resp [:headers "Location"])))
    (is (= 1 (count (event/list-by-tenant tenant-id))))))

(deftest home-shows-event-list
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        _ (sport-template/seed-templates!)
        parsed {:event/name "Gala Day"
                :event/start-at #inst "2026-08-01T09:00"
                :event/end-at #inst "2026-08-01T17:00"}
        _ (event/create! tenant-id uid parsed #{})
        resp (do-req :get "/" uid {} tenant-id)]
    (is (= 200 (:status resp)))
    (is (re-find #"Gala Day" (:body resp)))))

(deftest event-detail-shows-code-and-publish-button
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        _ (sport-template/seed-templates!)
        parsed {:event/name "Show Day"
                :event/start-at #inst "2026-08-01T09:00"
                :event/end-at #inst "2026-08-01T17:00"}
        created (event/create! tenant-id uid parsed #{})
        resp (do-req :get (str "/events/" (:event/id created)) uid {} tenant-id)]
    (is (= 200 (:status resp)))
    (is (re-find #"Publish" (:body resp)))
    (is (re-find (re-pattern (:event/code created)) (:body resp)))
    (is (re-find #"Download QR" (:body resp)))))

(deftest event-publish-route-transitions-and-redirects
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        _ (sport-template/seed-templates!)
        parsed {:event/name "Route Pub Day"
                :event/start-at #inst "2026-08-01T09:00"
                :event/end-at #inst "2026-08-01T17:00"}
        created (event/create! tenant-id uid parsed #{})
        event-id (:event/id created)
        resp (do-req :post (str "/events/" event-id "/publish") uid {} tenant-id)]
    (is (= 302 (:status resp)))
    (is (str/includes? (get-in resp [:headers "Location"]) (str event-id)))
    (is (= :event.status/published (:event/status (event/find-by-id event-id))))))

(deftest spectator-landing-blank-code-shows-form
  (let [resp (do-req :get "/e" nil)]
    (is (= 200 (:status resp)))
    (is (str/includes? (:body resp) "Find your event"))))

(deftest spectator-landing-unknown-code-shows-error
  (let [resp (do-req :get "/e?code=XXXXXX" nil)]
    (is (= 200 (:status resp)))
    (is (str/includes? (:body resp) "No event found"))))

(deftest spectator-landing-unpublished-code-shows-error
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        _ (sport-template/seed-templates!)
        parsed {:event/name "Draft Spectator"
                :event/start-at #inst "2026-09-01T09:00"
                :event/end-at #inst "2026-09-01T17:00"
                :event/visibility :event.visibility/public
                :event/access-method :event.access/public-link}
        created (event/create! tenant-id uid parsed #{})
        code (:event/code created)
        resp (do-req :get (str "/e?code=" code) nil)]
    (is (= 200 (:status resp)))
    (is (str/includes? (:body resp) "not yet published"))))

(deftest spectator-landing-published-redirects
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        _ (sport-template/seed-templates!)
        parsed {:event/name "Live Spectator"
                :event/start-at #inst "2026-09-01T09:00"
                :event/end-at #inst "2026-09-01T17:00"
                :event/visibility :event.visibility/public
                :event/access-method :event.access/public-link}
        created (event/create! tenant-id uid parsed #{})
        event-id (:event/id created)
        code (:event/code created)
        _ (event/publish! event-id uid)
        resp (do-req :get (str "/e?code=" code) nil)]
    (is (= 302 (:status resp)))
    (is (str/includes? (get-in resp [:headers "Location"]) (str/upper-case code)))))

(deftest spectator-event-page-published-renders
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        _ (sport-template/seed-templates!)
        parsed {:event/name "Public Show"
                :event/start-at #inst "2026-09-01T09:00"
                :event/end-at #inst "2026-09-01T17:00"
                :event/visibility :event.visibility/public
                :event/access-method :event.access/public-link}
        created (event/create! tenant-id uid parsed #{})
        event-id (:event/id created)
        code (:event/code created)
        _ (event/publish! event-id uid)
        resp (do-req :get (str "/e/" code) nil)]
    (is (= 200 (:status resp)))
    (is (str/includes? (:body resp) "Public Show"))))

(deftest spectator-event-page-draft-returns-404
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        _ (sport-template/seed-templates!)
        parsed {:event/name "Hidden Draft"
                :event/start-at #inst "2026-09-01T09:00"
                :event/end-at #inst "2026-09-01T17:00"
                :event/visibility :event.visibility/public
                :event/access-method :event.access/public-link}
        created (event/create! tenant-id uid parsed #{})
        code (:event/code created)
        resp (do-req :get (str "/e/" code) nil)]
    (is (= 404 (:status resp)))))

(deftest spectator-event-page-unknown-code-returns-404
  (let [resp (do-req :get "/e/XXXXXX" nil)]
    (is (= 404 (:status resp)))))
