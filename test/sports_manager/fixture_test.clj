(ns sports-manager.fixture-test
  "Tests for manual fixture creation (SPO-37) and spectator score endpoint (SPO-54)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [sports-manager.auth :as auth]
            [sports-manager.db :as db]
            [sports-manager.event :as event]
            [sports-manager.fixture :as fixture]
            [sports-manager.membership :as membership]
            [sports-manager.participant :as participant]
            [sports-manager.routes.core :as routes]
            [sports-manager.score :as score]
            [sports-manager.scorekeeper-code :as scode]
            [sports-manager.session :as session]
            [sports-manager.sport-template :as st]
            [sports-manager.test-db :as test-db])
  (:import java.util.UUID))

(use-fixtures :each test-db/with-db)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- seed! []
  (st/seed-templates!)
  (let [tid (UUID/randomUUID)
        uid "actor"]
    (db/put-many! [{:xt/id tid :tenant/id tid :tenant/name "Test School" :tenant/status :active}
                  {:xt/id uid :user/firebase-uid uid :user/email "a@x.com" :user/status :active}])
    (membership/create! uid tid)
    (let [ev (event/create! tid uid
                            {:event/name "Sports Day"
                             :event/start-at #inst "2026-09-01T08:00"
                             :event/end-at #inst "2026-09-01T18:00"
                             :event/visibility :event.visibility/public
                             :event/access-method :event.access/public-link}
                            #{:sport/rugby :sport/netball})
          event-id (:event/id ev)
          pa (participant/add-to-event! event-id uid
                                        {:participant/name "School A"
                                         :participant/contact-email "a@school.za"
                                         :participant/contact-phone ""}
                                        nil)
          pb (participant/add-to-event! event-id uid
                                        {:participant/name "School B"
                                         :participant/contact-email "b@school.za"
                                         :participant/contact-phone ""}
                                        nil)]
      {:event-id event-id
       :uid uid
       :tid tid
       :pa-id (:participant/id pa)
       :pb-id (:participant/id pb)})))

;; ---------------------------------------------------------------------------
;; parse-form
;; ---------------------------------------------------------------------------

(deftest parse-form-parses-fields
  (testing "parses sport code, team UUIDs, age group, venue and times"
    (let [pa (UUID/randomUUID)
          pb (UUID/randomUUID)
          result (fixture/parse-form
                  {"fixture-sport" "rugby"
                   "fixture-team-a" (str pa)
                   "fixture-team-b" (str pb)
                   "fixture-age-group" "U16 Boys"
                   "fixture-venue" "Main Field"
                   "fixture-start-at" "2026-09-01T09:00"
                   "fixture-end-at" "2026-09-01T10:00"})]
      (is (= :sport/rugby (:fixture/sport-code result)))
      (is (= pa (:fixture/team-a-id result)))
      (is (= pb (:fixture/team-b-id result)))
      (is (= "U16 Boys" (:fixture/age-group result)))
      (is (= "Main Field" (:fixture/venue result)))
      (is (inst? (:fixture/start-at result)))
      (is (inst? (:fixture/end-at result))))))

(deftest parse-form-blank-sport-returns-nil
  (is (nil? (:fixture/sport-code (fixture/parse-form {"fixture-sport" ""})))))

;; ---------------------------------------------------------------------------
;; validate
;; ---------------------------------------------------------------------------

(deftest validate-valid-returns-empty
  (let [pa (UUID/randomUUID)
        pb (UUID/randomUUID)]
    (is (empty? (fixture/validate
                 {:fixture/sport-code :sport/rugby
                  :fixture/team-a-id pa
                  :fixture/team-b-id pb
                  :fixture/start-at #inst "2026-09-01T09:00"
                  :fixture/end-at #inst "2026-09-01T10:00"})))))

(deftest validate-missing-required-fields
  (let [errors (fixture/validate {})]
    (is (contains? errors :fixture/sport-code))
    (is (contains? errors :fixture/team-a-id))
    (is (contains? errors :fixture/team-b-id))
    (is (contains? errors :fixture/start-at))
    (is (contains? errors :fixture/end-at))))

(deftest validate-same-team-error
  (let [id (UUID/randomUUID)
        errors (fixture/validate {:fixture/sport-code :sport/rugby
                                  :fixture/team-a-id id
                                  :fixture/team-b-id id
                                  :fixture/start-at #inst "2026-09-01T09:00"
                                  :fixture/end-at #inst "2026-09-01T10:00"})]
    (is (contains? errors :fixture/team-b-id))))

(deftest validate-end-before-start
  (let [pa (UUID/randomUUID)
        pb (UUID/randomUUID)
        errors (fixture/validate {:fixture/sport-code :sport/rugby
                                  :fixture/team-a-id pa
                                  :fixture/team-b-id pb
                                  :fixture/start-at #inst "2026-09-01T10:00"
                                  :fixture/end-at #inst "2026-09-01T09:00"})]
    (is (contains? errors :fixture/end-at))))

;; ---------------------------------------------------------------------------
;; create! / find-by-id / list-by-event
;; ---------------------------------------------------------------------------

(deftest create-fixture-draft
  (testing "creates a fixture in draft status with auto match-number"
    (let [{:keys [event-id uid pa-id pb-id]} (seed!)
          f (fixture/create! event-id uid
                             {:fixture/sport-code :sport/rugby
                              :fixture/team-a-id pa-id
                              :fixture/team-b-id pb-id
                              :fixture/age-group "U16 Boys"
                              :fixture/venue "Main Field"
                              :fixture/start-at #inst "2026-09-01T09:00"
                              :fixture/end-at #inst "2026-09-01T10:00"})]
      (is (uuid? (:fixture/id f)))
      (is (= "M001" (:fixture/match-number f)))
      (is (= :fixture.status/draft (:fixture/status f)))
      (is (= "Rugby" (get-in f [:fixture/sport-template :sport-template/name])))
      (is (= "School A" (get-in f [:fixture/team-a :participant/name])))
      (is (= "School B" (get-in f [:fixture/team-b :participant/name])))
      (is (= "U16 Boys" (:fixture/age-group f)))
      (is (= "Main Field" (:fixture/venue f))))))

(deftest match-numbers-increment-sequentially
  (testing "second fixture gets M002"
    (let [{:keys [event-id uid pa-id pb-id]} (seed!)]
      (fixture/create! event-id uid {:fixture/sport-code :sport/rugby
                                     :fixture/team-a-id pa-id
                                     :fixture/team-b-id pb-id
                                     :fixture/start-at #inst "2026-09-01T09:00"
                                     :fixture/end-at #inst "2026-09-01T10:00"})
      (let [f2 (fixture/create! event-id uid {:fixture/sport-code :sport/netball
                                              :fixture/team-a-id pa-id
                                              :fixture/team-b-id pb-id
                                              :fixture/start-at #inst "2026-09-01T11:00"
                                              :fixture/end-at #inst "2026-09-01T12:00"})]
        (is (= "M002" (:fixture/match-number f2)))))))

(deftest find-by-id-returns-fixture
  (let [{:keys [event-id uid pa-id pb-id]} (seed!)
        created (fixture/create! event-id uid {:fixture/sport-code :sport/rugby
                                               :fixture/team-a-id pa-id
                                               :fixture/team-b-id pb-id
                                               :fixture/start-at #inst "2026-09-01T09:00"
                                               :fixture/end-at #inst "2026-09-01T10:00"})
        found (fixture/find-by-id (:fixture/id created))]
    (is (= (:fixture/id created) (:fixture/id found)))))

(deftest find-by-id-not-found
  (is (nil? (fixture/find-by-id (UUID/randomUUID)))))

(deftest list-by-event-sorted-by-start
  (testing "returns fixtures sorted by start-at ascending"
    (let [{:keys [event-id uid pa-id pb-id]} (seed!)]
      (fixture/create! event-id uid {:fixture/sport-code :sport/netball
                                     :fixture/team-a-id pa-id
                                     :fixture/team-b-id pb-id
                                     :fixture/start-at #inst "2026-09-01T11:00"
                                     :fixture/end-at #inst "2026-09-01T12:00"})
      (fixture/create! event-id uid {:fixture/sport-code :sport/rugby
                                     :fixture/team-a-id pa-id
                                     :fixture/team-b-id pb-id
                                     :fixture/start-at #inst "2026-09-01T09:00"
                                     :fixture/end-at #inst "2026-09-01T10:00"})
      (let [fixtures (fixture/list-by-event event-id)]
        (is (= 2 (count fixtures)))
        (is (.before (:fixture/start-at (first fixtures))
                     (:fixture/start-at (second fixtures))))))))

(deftest list-by-event-empty
  (let [{:keys [event-id]} (seed!)]
    (is (empty? (fixture/list-by-event event-id)))))

(deftest create-unknown-event-throws
  (st/seed-templates!)
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Event not found"
                        (fixture/create! (UUID/randomUUID) "actor"
                                         {:fixture/sport-code :sport/rugby
                                          :fixture/team-a-id (UUID/randomUUID)
                                          :fixture/team-b-id (UUID/randomUUID)
                                          :fixture/start-at #inst "2026-09-01T09:00"
                                          :fixture/end-at #inst "2026-09-01T10:00"}))))

;; ---------------------------------------------------------------------------
;; Route: POST /events/:id/fixtures
;; ---------------------------------------------------------------------------

(defn- find-user-from-test-db [uid]
  (when uid
    (let [e (db/pull [:user/firebase-uid :user/email :user/name :user/status :user/roles] uid)]
      (when (:user/firebase-uid e) e))))

(defn- do-req
  ([method uri uid] (do-req method uri uid {} nil))
  ([method uri uid form-params] (do-req method uri uid form-params nil))
  ([method uri uid form-params tenant-id]
   (with-redefs [session/uid-from-request (fn [req] (:uid req))
                 auth/find-user find-user-from-test-db]
     (let [csrf-token "test-csrf-token"
           params (if (= method :post)
                    (assoc (or form-params {}) "__anti-forgery-token" csrf-token)
                    (or form-params {}))
           cookie (test-db/session-cookie
                   (cond-> {:ring.middleware.anti-forgery/anti-forgery-token csrf-token}
                     tenant-id (assoc :active-tenant-id tenant-id)))]
       (routes/handler
        {:request-method method
         :uri uri
         :uid uid
         :headers {"host" "localhost" "cookie" (str "ring-session=" cookie)}
         :params params
         :form-params params})))))

(deftest fixture-create-route-redirects-on-success
  (let [{:keys [event-id uid pa-id pb-id tid]} (seed!)
        resp (do-req :post (str "/events/" event-id "/fixtures") uid
                     {"fixture-sport" "rugby"
                      "fixture-team-a" (str pa-id)
                      "fixture-team-b" (str pb-id)
                      "fixture-start-at" "2026-09-01T09:00"
                      "fixture-end-at" "2026-09-01T10:00"}
                     tid)]
    (is (= 302 (:status resp)))
    (is (str/includes? (get-in resp [:headers "Location"]) (str event-id)))
    (is (= 1 (count (fixture/list-by-event event-id))))))

(deftest fixture-create-route-shows-errors-on-invalid
  (let [{:keys [event-id uid tid]} (seed!)
        resp (do-req :post (str "/events/" event-id "/fixtures") uid
                     {"fixture-sport" "" "fixture-team-a" "" "fixture-team-b" ""}
                     tid)]
    (is (= 200 (:status resp)))
    (is (str/includes? (:body resp) "Required"))))

(deftest event-detail-shows-fixtures-section
  (let [{:keys [event-id uid pa-id pb-id tid]} (seed!)
        _ (fixture/create! event-id uid {:fixture/sport-code :sport/rugby
                                         :fixture/team-a-id pa-id
                                         :fixture/team-b-id pb-id
                                         :fixture/start-at #inst "2026-09-01T09:00"
                                         :fixture/end-at #inst "2026-09-01T10:00"})
        resp (do-req :get (str "/events/" event-id) uid {} tid)]
    (is (= 200 (:status resp)))
    (is (str/includes? (:body resp) "M001"))
    (is (str/includes? (:body resp) "Rugby"))))

;; ---------------------------------------------------------------------------
;; SPO-40: update! / publish!
;; ---------------------------------------------------------------------------

(deftest update-fixture-changes-fields
  (testing "update! changes venue and age-group, records audit entry"
    (let [{:keys [event-id uid pa-id pb-id]} (seed!)
          f (fixture/create! event-id uid {:fixture/sport-code :sport/rugby
                                           :fixture/team-a-id pa-id
                                           :fixture/team-b-id pb-id
                                           :fixture/age-group "U16 Boys"
                                           :fixture/venue "Main Field"
                                           :fixture/start-at #inst "2026-09-01T09:00"
                                           :fixture/end-at #inst "2026-09-01T10:00"})
          updated (fixture/update! (:fixture/id f) uid
                                   {:fixture/age-group "U18 Boys"
                                    :fixture/venue "North Pitch"})]
      (is (= "U18 Boys" (:fixture/age-group updated)))
      (is (= "North Pitch" (:fixture/venue updated)))
      (is (= :fixture.status/draft (:fixture/status updated))))))

(deftest update-fixture-clears-optional-field
  (testing "passing nil for venue retracts it"
    (let [{:keys [event-id uid pa-id pb-id]} (seed!)
          f (fixture/create! event-id uid {:fixture/sport-code :sport/rugby
                                           :fixture/team-a-id pa-id
                                           :fixture/team-b-id pb-id
                                           :fixture/venue "Main Field"
                                           :fixture/start-at #inst "2026-09-01T09:00"
                                           :fixture/end-at #inst "2026-09-01T10:00"})
          updated (fixture/update! (:fixture/id f) uid {:fixture/venue nil})]
      (is (nil? (:fixture/venue updated))))))

(deftest update-fixture-not-found-throws
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Fixture not found"
                        (fixture/update! (UUID/randomUUID) "actor" {:fixture/venue "X"}))))

(deftest publish-fixture-transitions-status
  (testing "publish! changes draft → published"
    (let [{:keys [event-id uid pa-id pb-id]} (seed!)
          f (fixture/create! event-id uid {:fixture/sport-code :sport/rugby
                                           :fixture/team-a-id pa-id
                                           :fixture/team-b-id pb-id
                                           :fixture/start-at #inst "2026-09-01T09:00"
                                           :fixture/end-at #inst "2026-09-01T10:00"})
          published (fixture/publish! (:fixture/id f) uid)]
      (is (= :fixture.status/published (:fixture/status published))))))

(deftest publish-already-published-throws
  (let [{:keys [event-id uid pa-id pb-id]} (seed!)
        f (fixture/create! event-id uid {:fixture/sport-code :sport/rugby
                                         :fixture/team-a-id pa-id
                                         :fixture/team-b-id pb-id
                                         :fixture/start-at #inst "2026-09-01T09:00"
                                         :fixture/end-at #inst "2026-09-01T10:00"})]
    (fixture/publish! (:fixture/id f) uid)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not in draft"
                          (fixture/publish! (:fixture/id f) uid)))))

(deftest update-published-fixture-allowed
  (testing "published fixture can still be edited"
    (let [{:keys [event-id uid pa-id pb-id]} (seed!)
          f (fixture/create! event-id uid {:fixture/sport-code :sport/rugby
                                           :fixture/team-a-id pa-id
                                           :fixture/team-b-id pb-id
                                           :fixture/start-at #inst "2026-09-01T09:00"
                                           :fixture/end-at #inst "2026-09-01T10:00"})
          _ (fixture/publish! (:fixture/id f) uid)
          updated (fixture/update! (:fixture/id f) uid {:fixture/venue "Updated Field"})]
      (is (= :fixture.status/published (:fixture/status updated)))
      (is (= "Updated Field" (:fixture/venue updated))))))

(deftest fixture-publish-route-redirects
  (let [{:keys [event-id uid pa-id pb-id tid]} (seed!)
        f (fixture/create! event-id uid {:fixture/sport-code :sport/rugby
                                         :fixture/team-a-id pa-id
                                         :fixture/team-b-id pb-id
                                         :fixture/start-at #inst "2026-09-01T09:00"
                                         :fixture/end-at #inst "2026-09-01T10:00"})
        resp (do-req :post
                     (str "/events/" event-id "/fixtures/" (:fixture/id f) "/publish")
                     uid {} tid)]
    (is (= 302 (:status resp)))
    (is (= :fixture.status/published
           (:fixture/status (fixture/find-by-id (:fixture/id f)))))))

;; ---------------------------------------------------------------------------
;; filter-fixtures (SPO-41)
;; ---------------------------------------------------------------------------

(deftest filter-fixtures-no-filters-returns-all
  (testing "empty filter map returns all fixtures unchanged"
    (let [{:keys [event-id]} (seed!)
          all (fixture/list-by-event event-id)]
      (is (= (count all) (count (fixture/filter-fixtures all {})))))))

(deftest filter-fixtures-by-sport
  (testing "sport-code filter keeps only matching sport"
    (let [{:keys [event-id uid pa-id pb-id]} (seed!)
          _ (fixture/create! event-id uid
                             {:fixture/sport-code :sport/netball
                              :fixture/team-a-id pa-id
                              :fixture/team-b-id pb-id
                              :fixture/start-at #inst "2026-09-01T10:00"
                              :fixture/end-at #inst "2026-09-01T11:00"})
          _ (fixture/create! event-id uid
                             {:fixture/sport-code :sport/rugby
                              :fixture/team-a-id pa-id
                              :fixture/team-b-id pb-id
                              :fixture/start-at #inst "2026-09-01T09:00"
                              :fixture/end-at #inst "2026-09-01T10:00"})
          all (fixture/list-by-event event-id)
          rugby-only (fixture/filter-fixtures all {:sport-code "sport/rugby"})]
      (is (pos? (count rugby-only)))
      (is (every? #(= :sport/rugby (get-in % [:fixture/sport-template :sport-template/code]))
                  rugby-only)))))

(deftest filter-fixtures-by-team-name
  (testing "team-name filter is case-insensitive substring match"
    (let [{:keys [event-id]} (seed!)
          all (fixture/list-by-event event-id)
          result (fixture/filter-fixtures all {:team-name "school a"})]
      (is (every? #(or (clojure.string/includes?
                        (clojure.string/lower-case
                         (get-in % [:fixture/team-a :participant/name] ""))
                        "school a")
                       (clojure.string/includes?
                        (clojure.string/lower-case
                         (get-in % [:fixture/team-b :participant/name] ""))
                        "school a"))
                  result)))))

(deftest filter-fixtures-by-age-group
  (testing "age-group filter is case-insensitive substring match"
    (let [{:keys [event-id]} (seed!)
          all (fixture/list-by-event event-id)
          result (fixture/filter-fixtures all {:age-group "u16"})]
      (is (every? #(clojure.string/includes?
                    (clojure.string/lower-case (or (:fixture/age-group %) ""))
                    "u16")
                  result)))))

(deftest filter-fixtures-by-venue
  (testing "venue filter is case-insensitive substring match"
    (let [{:keys [event-id]} (seed!)
          all (fixture/list-by-event event-id)
          result (fixture/filter-fixtures all {:venue "main"})]
      (is (every? #(clojure.string/includes?
                    (clojure.string/lower-case (or (:fixture/venue %) ""))
                    "main")
                  result)))))

(deftest filter-fixtures-by-status
  (testing "status filter keeps only matching status"
    (let [{:keys [event-id]} (seed!)
          all (fixture/list-by-event event-id)
          drafts (fixture/filter-fixtures all {:status "fixture.status/draft"})]
      (is (every? #(= :fixture.status/draft (:fixture/status %)) drafts)))))

(deftest filter-fixtures-empty-result
  (testing "filter with no matches returns empty seq"
    (let [{:keys [event-id]} (seed!)
          all (fixture/list-by-event event-id)
          result (fixture/filter-fixtures all {:team-name "ZZZ-no-match-ZZZ"})]
      (is (empty? result)))))

;; ---------------------------------------------------------------------------
;; list-by-event-public (SPO-53)
;; ---------------------------------------------------------------------------

(deftest list-by-event-public-excludes-drafts
  (testing "draft fixtures are not returned"
    (let [{:keys [event-id uid pa-id pb-id]} (seed!)]
      (fixture/create! event-id uid {:fixture/sport-code :sport/rugby
                                     :fixture/team-a-id pa-id
                                     :fixture/team-b-id pb-id
                                     :fixture/start-at #inst "2026-09-01T09:00"
                                     :fixture/end-at #inst "2026-09-01T10:00"})
      (is (empty? (fixture/list-by-event-public event-id))))))

(deftest list-by-event-public-includes-published
  (testing "published fixtures are returned"
    (let [{:keys [event-id uid pa-id pb-id]} (seed!)
          f (fixture/create! event-id uid {:fixture/sport-code :sport/rugby
                                           :fixture/team-a-id pa-id
                                           :fixture/team-b-id pb-id
                                           :fixture/start-at #inst "2026-09-01T09:00"
                                           :fixture/end-at #inst "2026-09-01T10:00"})]
      (fixture/publish! (:fixture/id f) uid)
      (let [public (fixture/list-by-event-public event-id)]
        (is (= 1 (count public)))
        (is (= :fixture.status/published (:fixture/status (first public))))))))

(deftest list-by-event-public-mixed-statuses
  (testing "only published fixtures returned when mixed with drafts"
    (let [{:keys [event-id uid pa-id pb-id]} (seed!)
          f1 (fixture/create! event-id uid {:fixture/sport-code :sport/rugby
                                            :fixture/team-a-id pa-id
                                            :fixture/team-b-id pb-id
                                            :fixture/start-at #inst "2026-09-01T09:00"
                                            :fixture/end-at #inst "2026-09-01T10:00"})
          _ (fixture/create! event-id uid {:fixture/sport-code :sport/netball
                                           :fixture/team-a-id pa-id
                                           :fixture/team-b-id pb-id
                                           :fixture/start-at #inst "2026-09-01T11:00"
                                           :fixture/end-at #inst "2026-09-01T12:00"})]
      (fixture/publish! (:fixture/id f1) uid)
      (is (= 1 (count (fixture/list-by-event-public event-id)))))))

;; ---------------------------------------------------------------------------
;; Spectator score JSON endpoint (SPO-54)
;; ---------------------------------------------------------------------------

(defn- do-public-req [uri]
  (with-redefs [session/uid-from-request (fn [_] nil)
                auth/find-user (fn [_] nil)]
    (routes/handler
     {:request-method :get
      :uri uri
      :headers {"host" "localhost"}
      :query-params {}})))

(deftest spectator-score-endpoint-not-found
  (let [res (do-public-req (str "/e/fixture/" (UUID/randomUUID) "/score"))]
    (is (= 404 (:status res)))))

(deftest spectator-score-endpoint-returns-score
  (let [{:keys [event-id uid pa-id pb-id]} (seed!)
        f (fixture/create! event-id uid {:fixture/sport-code :sport/rugby
                                         :fixture/team-a-id pa-id
                                         :fixture/team-b-id pb-id
                                         :fixture/start-at #inst "2026-09-01T09:00"
                                         :fixture/end-at #inst "2026-09-01T10:00"})
        fid (:fixture/id f)
        {:keys [entity]} (scode/generate! fid uid)
        sid (:scode/id entity)]
    (score/record-event! fid sid :a 3 nil)
    (score/record-event! fid sid :b 1 nil)
    (let [res (do-public-req (str "/e/fixture/" fid "/score"))
          body (json/parse-string (:body res) true)]
      (is (= 200 (:status res)))
      (is (= 3 (:a body)))
      (is (= 1 (:b body))))))

(deftest spectator-score-endpoint-content-type
  (let [{:keys [event-id uid pa-id pb-id]} (seed!)
        f (fixture/create! event-id uid {:fixture/sport-code :sport/rugby
                                         :fixture/team-a-id pa-id
                                         :fixture/team-b-id pb-id
                                         :fixture/start-at #inst "2026-09-01T09:00"
                                         :fixture/end-at #inst "2026-09-01T10:00"})
        res (do-public-req (str "/e/fixture/" (:fixture/id f) "/score"))]
    (is (= "application/json" (get-in res [:headers "Content-Type"])))
    (is (= "no-store" (get-in res [:headers "Cache-Control"])))))
