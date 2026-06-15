(ns sports-manager.participant-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [sports-manager.auth :as auth]
            [sports-manager.db :as db]
            [sports-manager.event :as event]
            [sports-manager.membership :as membership]
            [sports-manager.participant :as participant]
            [sports-manager.rbac :as rbac]
            [sports-manager.routes.core :as routes]
            [sports-manager.session :as session]
            [sports-manager.test-db :as test-db]
            [sports-manager.sport-template :as sport-template])
  (:import java.util.UUID))

(use-fixtures :each test-db/with-db)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- seed-tenant+user! []
  (rbac/seed-roles!)
  (let [tid (UUID/randomUUID)
        uid "test-uid-par"]
    (db/put-many!
     [{:xt/id tid :tenant/id tid :tenant/name "Test School"
       :tenant/status :active :tenant/contact-email "a@b.com"
       :tenant/created-at (java.util.Date.)}
      {:xt/id uid :user/firebase-uid uid :user/email "par@test.com"
       :user/status :active
       :user/created-at (java.util.Date.)}])
    (membership/create! uid tid)
    (rbac/grant-role! uid :role.name/school-admin :actor uid)
    {:tenant-id tid :uid uid}))

(defn- seed-event! [tenant-id uid]
  (sport-template/seed-templates!)
  (event/create! tenant-id uid
                 {:event/name "Test Event"
                  :event/start-at #inst "2026-08-01T09:00"
                  :event/end-at #inst "2026-08-01T17:00"}
                 #{}))

;; ---------------------------------------------------------------------------
;; validate
;; ---------------------------------------------------------------------------

(deftest validate-missing-name
  (is (contains? (participant/validate {:participant/name ""}) :participant/name)))

(deftest validate-valid
  (is (empty? (participant/validate {:participant/name "Rondebosch High"}))))

;; ---------------------------------------------------------------------------
;; add-to-event! / list-by-event / find-by-id
;; ---------------------------------------------------------------------------

(deftest add-participant-to-event
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        ev (seed-event! tenant-id uid)
        data {:participant/name "Bishops"
              :participant/contact-email "sport@bishops.co.za"
              :participant/contact-phone "+27 21 000 0000"}
        result (participant/add-to-event! (:event/id ev) uid data nil)]
    (is (uuid? (:participant/id result)))
    (is (= "Bishops" (:participant/name result)))
    (is (= :participant.status/confirmed (:participant/status result)))
    (is (= "sport@bishops.co.za" (:participant/contact-email result)))))

(deftest add-participant-minimal
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        ev (seed-event! tenant-id uid)
        result (participant/add-to-event! (:event/id ev) uid
                                          {:participant/name "SACS"} nil)]
    (is (= "SACS" (:participant/name result)))
    (is (nil? (:participant/contact-email result)))))

(deftest list-by-event-sorted
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        ev (seed-event! tenant-id uid)]
    (participant/add-to-event! (:event/id ev) uid {:participant/name "Zululand High"} nil)
    (participant/add-to-event! (:event/id ev) uid {:participant/name "Bishops"} nil)
    (participant/add-to-event! (:event/id ev) uid {:participant/name "SACS"} nil)
    (let [names (map :participant/name (participant/list-by-event (:event/id ev)))]
      (is (= ["Bishops" "SACS" "Zululand High"] names)))))

(deftest list-by-event-empty
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        ev (seed-event! tenant-id uid)]
    (is (empty? (participant/list-by-event (:event/id ev))))))

(deftest find-by-id-not-found
  (is (nil? (participant/find-by-id (UUID/randomUUID)))))

(deftest remove-from-event
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        ev (seed-event! tenant-id uid)
        p (participant/add-to-event! (:event/id ev) uid
                                     {:participant/name "Bishops"} nil)]
    (participant/remove-from-event! (:event/id ev) (:participant/id p) uid)
    (is (empty? (participant/list-by-event (:event/id ev))))
    (is (nil? (participant/find-by-id (:participant/id p))))))

;; ---------------------------------------------------------------------------
;; Route integration tests
;; ---------------------------------------------------------------------------

(defn- find-user-from-test-db [uid]
  (db/pull
   [:user/firebase-uid :user/email :user/name :user/status :user/roles]
   uid))

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

(deftest event-detail-redirects-unauthenticated
  (let [resp (do-req :get "/events/some-id" nil)]
    (is (= 302 (:status resp)))
    (is (= "/login" (get-in resp [:headers "Location"])))))

(deftest event-detail-renders-page
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        ev (seed-event! tenant-id uid)
        resp (do-req :get (str "/events/" (:event/id ev)) uid {} tenant-id)]
    (is (= 200 (:status resp)))
    (is (re-find #"Test Event" (:body resp)))
    (is (re-find #"Participating schools" (:body resp)))
    (is (re-find #"No schools added yet" (:body resp)))))

(deftest event-detail-404-bad-id
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        resp (do-req :get (str "/events/" (UUID/randomUUID)) uid {} tenant-id)]
    (is (= 404 (:status resp)))))

(deftest participant-add-validation-error
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        ev (seed-event! tenant-id uid)
        resp (do-req :post (str "/events/" (:event/id ev) "/participants") uid
                     {"participant-name" ""} tenant-id)]
    (is (= 200 (:status resp)))
    (is (re-find #"Required" (:body resp)))))

(deftest participant-add-success
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        ev (seed-event! tenant-id uid)
        resp (do-req :post (str "/events/" (:event/id ev) "/participants") uid
                     {"participant-name" "Bishops"
                      "participant-email" "sport@bishops.co.za"
                      "participant-phone" ""} tenant-id)]
    (is (= 302 (:status resp)))
    (is (= (str "/events/" (:event/id ev)) (get-in resp [:headers "Location"])))
    (is (= 1 (count (participant/list-by-event (:event/id ev)))))))

(deftest participant-remove-success
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        ev (seed-event! tenant-id uid)
        p (participant/add-to-event! (:event/id ev) uid
                                     {:participant/name "Bishops"} nil)
        resp (do-req :post
                     (str "/events/" (:event/id ev)
                          "/participants/" (:participant/id p) "/remove")
                     uid {} tenant-id)]
    (is (= 302 (:status resp)))
    (is (empty? (participant/list-by-event (:event/id ev))))))

(deftest event-detail-shows-participants
  (let [{:keys [tenant-id uid]} (seed-tenant+user!)
        ev (seed-event! tenant-id uid)
        _ (participant/add-to-event! (:event/id ev) uid
                                     {:participant/name "Bishops"} nil)
        resp (do-req :get (str "/events/" (:event/id ev)) uid {} tenant-id)]
    (is (= 200 (:status resp)))
    (is (re-find #"Bishops" (:body resp)))))
