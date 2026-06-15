(ns sports-manager.sport-template-test
  "Tests for sport template seeding, query, and selection (SPO-25)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [sports-manager.auth :as auth]
            [sports-manager.db :as db]
            [sports-manager.membership :as membership]
            [sports-manager.routes.core :as routes]
            [sports-manager.session :as session]
            [sports-manager.sport-template :as st]
            [sports-manager.test-db :as test-db])
  (:import java.util.UUID))

(use-fixtures :each test-db/with-db)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- seed-tenant! [tenant-name]
  (let [tid (UUID/randomUUID)]
    (db/put-many! [{:xt/id tid :tenant/id tid :tenant/name tenant-name :tenant/status :active}])
    tid))

(defn- seed-user! [uid email tenant-id]
  (db/put-many! [{:xt/id uid :user/firebase-uid uid :user/email email :user/status :active}])
  (when tenant-id
    (membership/create! uid tenant-id))
  uid)

(defn- find-user-from-test-db [uid]
  (when uid
    (let [e (db/pull [:user/firebase-uid :user/email :user/name :user/status :user/roles]
                     uid)]
      (when (:user/firebase-uid e) e))))

(defn- GET
  ([uri uid] (GET uri uid nil))
  ([uri uid tid]
   (with-redefs [session/uid-from-request (fn [req] (:uid req))
                 auth/find-user find-user-from-test-db]
     (let [cookie (test-db/session-cookie
                   (cond-> {:ring.middleware.anti-forgery/anti-forgery-token "test-csrf-token"}
                     tid (assoc :active-tenant-id tid)))]
       (routes/handler {:request-method :get :uri uri :uid uid
                        :headers {"cookie" (str "ring-session=" cookie)}
                        :params {} :form-params {}})))));; end GET

(defn- POST
  ([uri uid params] (POST uri uid params nil))
  ([uri uid params tid]
   (with-redefs [session/uid-from-request (fn [req] (:uid req))
                 auth/find-user find-user-from-test-db]
     (let [csrf-token "test-csrf-token"
           p (assoc (or params {}) "__anti-forgery-token" csrf-token)
           cookie (test-db/session-cookie
                   (cond-> {:ring.middleware.anti-forgery/anti-forgery-token csrf-token}
                     tid (assoc :active-tenant-id tid)))]
       (routes/handler {:request-method :post :uri uri :uid uid
                        :headers {"cookie" (str "ring-session=" cookie)}
                        :params p :form-params p})))));; end POST

(defn- location [resp] (get-in resp [:headers "Location"]))

;; ---------------------------------------------------------------------------
;; seed-templates!
;; ---------------------------------------------------------------------------

(deftest seed-templates-populates-all-sports
  (testing "seeds all 12 platform templates"
    (st/seed-templates!)
    (is (= 12 (count (st/list-all))))))

(deftest seed-templates-is-idempotent
  (testing "seeding twice does not duplicate templates"
    (st/seed-templates!)
    (st/seed-templates!)
    (is (= 12 (count (st/list-all))))))

(deftest list-all-returns-sorted-by-name
  (testing "templates come back in alphabetical order"
    (st/seed-templates!)
    (let [names (map :sport-template/name (st/list-all))]
      (is (= names (sort names))))))

;; ---------------------------------------------------------------------------
;; selected-codes
;; ---------------------------------------------------------------------------

(deftest selected-codes-empty-initially
  (testing "new tenant has no sport templates selected"
    (st/seed-templates!)
    (let [tid (seed-tenant! "No Sports School")]
      (is (empty? (st/selected-codes tid))))))

;; ---------------------------------------------------------------------------
;; set-selection!
;; ---------------------------------------------------------------------------

(deftest set-selection-persists-codes
  (testing "selected codes are retrievable via selected-codes"
    (st/seed-templates!)
    (let [tid (seed-tenant! "Rugby School")]
      (st/set-selection! tid #{:sport/rugby :sport/cricket} "actor")
      (is (= #{:sport/rugby :sport/cricket} (st/selected-codes tid))))))

(deftest set-selection-replaces-previous
  (testing "calling set-selection! again fully replaces the prior selection"
    (st/seed-templates!)
    (let [tid (seed-tenant! "Changing School")]
      (st/set-selection! tid #{:sport/rugby :sport/hockey} "actor")
      (st/set-selection! tid #{:sport/netball} "actor")
      (is (= #{:sport/netball} (st/selected-codes tid))))))

(deftest set-selection-empty-clears-all
  (testing "passing an empty set removes all selections"
    (st/seed-templates!)
    (let [tid (seed-tenant! "Empty Sports School")]
      (st/set-selection! tid #{:sport/tennis} "actor")
      (st/set-selection! tid #{} "actor")
      (is (empty? (st/selected-codes tid))))))

(deftest set-selection-ignores-unknown-codes
  (testing "unknown sport codes are silently ignored"
    (st/seed-templates!)
    (let [tid (seed-tenant! "Unknown Sports School")]
      (st/set-selection! tid #{:sport/underwater-chess} "actor")
      (is (empty? (st/selected-codes tid))))))

;; ---------------------------------------------------------------------------
;; Route: GET /school/sports
;; ---------------------------------------------------------------------------

(deftest sports-page-redirects-unauthenticated
  (testing "unauthenticated request redirects to /login"
    (st/seed-templates!)
    (let [resp (GET "/school/sports" nil)]
      (is (= 302 (:status resp)))
      (is (= "/login" (location resp))))))

(deftest sports-page-renders-all-templates
  (testing "renders a checkbox for every platform template"
    (st/seed-templates!)
    (let [tid (seed-tenant! "View School")]
      (seed-user! "uid-view" "view@x.com" tid)
      (let [resp (GET "/school/sports" "uid-view" tid)]
        (is (= 200 (:status resp)))
        (is (re-find #"Rugby" (:body resp)))
        (is (re-find #"Cricket" (:body resp)))
        (is (re-find #"Netball" (:body resp)))))))

(deftest sports-page-shows-existing-selection
  (testing "already-selected sports have their checkbox marked checked"
    (st/seed-templates!)
    (let [tid (seed-tenant! "Selected School")]
      (seed-user! "uid-sel" "sel@x.com" tid)
      (st/set-selection! tid #{:sport/hockey} "uid-sel")
      (let [body (:body (GET "/school/sports" "uid-sel" tid))]
        (is (re-find #"checked.*hockey|hockey.*checked" body))))))

;; ---------------------------------------------------------------------------
;; Route: POST /school/sports
;; ---------------------------------------------------------------------------

(deftest sports-submit-saves-and-redirects
  (testing "valid submission persists selection and redirects back"
    (st/seed-templates!)
    (let [tid (seed-tenant! "Save School")]
      (seed-user! "uid-save" "save@x.com" tid)
      (let [resp (POST "/school/sports" "uid-save" {"sports" "rugby"} tid)]
        (is (= 302 (:status resp)))
        (is (= "/school/sports" (location resp))))
      (is (= #{:sport/rugby} (st/selected-codes tid))))))

(deftest sports-submit-multiple-selections
  (testing "multiple checkboxes submitted as a vector are all saved"
    (st/seed-templates!)
    (let [tid (seed-tenant! "Multi School")]
      (seed-user! "uid-multi" "multi@x.com" tid)
      (POST "/school/sports" "uid-multi" {"sports" ["rugby" "cricket" "hockey"]} tid)
      (is (= #{:sport/rugby :sport/cricket :sport/hockey} (st/selected-codes tid))))))

(deftest sports-submit-no-selection-clears-all
  (testing "submitting with no sports checked clears the selection"
    (st/seed-templates!)
    (let [tid (seed-tenant! "Clear School")]
      (seed-user! "uid-clear" "clear@x.com" tid)
      (st/set-selection! tid #{:sport/tennis} "uid-clear")
      (POST "/school/sports" "uid-clear" {} tid)
      (is (empty? (st/selected-codes tid))))))

;; ---------------------------------------------------------------------------
;; SPO-32: new fields — scoring-increments, venue-type, period-labels, is-template
;; ---------------------------------------------------------------------------

(deftest templates-have-is-template-flag
  (testing "all seeded templates have is-template=true"
    (st/seed-templates!)
    (is (every? #(true? (:sport-template/is-template %)) (st/list-all)))))

(deftest rugby-template-has-expected-fields
  (testing "rugby template has correct scoring increments, venue type and period labels"
    (st/seed-templates!)
    (let [rugby (->> (st/list-all)
                     (filter #(= :sport/rugby (:sport-template/code %)))
                     first)]
      (is (= "[5 3 2 1]" (:sport-template/scoring-increments rugby)))
      (is (= :venue.type/field (:sport-template/venue-type rugby)))
      (is (= "Half,Half" (:sport-template/period-labels rugby))))))

(deftest basketball-template-has-expected-fields
  (testing "basketball template has correct scoring increments and period labels"
    (st/seed-templates!)
    (let [bb (->> (st/list-all)
                  (filter #(= :sport/basketball (:sport-template/code %)))
                  first)]
      (is (= "[3 2 1]" (:sport-template/scoring-increments bb)))
      (is (= :venue.type/court (:sport-template/venue-type bb)))
      (is (= "Quarter,Quarter,Quarter,Quarter" (:sport-template/period-labels bb))))))

(deftest swimming-template-has-no-scoring-increments
  (testing "swimming has no scoring increments (free-form)"
    (st/seed-templates!)
    (let [sw (->> (st/list-all)
                  (filter #(= :sport/swimming (:sport-template/code %)))
                  first)]
      (is (nil? (:sport-template/scoring-increments sw)))
      (is (= :venue.type/pool (:sport-template/venue-type sw))))))

(deftest seed-templates-is-idempotent-with-new-fields
  (testing "seeding twice does not lose or duplicate the new fields"
    (st/seed-templates!)
    (st/seed-templates!)
    (let [all (st/list-all)]
      (is (= 12 (count all)))
      (is (every? :sport-template/venue-type all))
      (is (every? :sport-template/period-labels all)))))
