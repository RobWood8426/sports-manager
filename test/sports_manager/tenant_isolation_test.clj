(ns sports-manager.tenant-isolation-test
  "Regression tests for SPO-61: event-scoped admin handlers must reject access
  when the requested event belongs to a different tenant than the active one.

  Drives the full routes/handler so the assertions cover the wired routes
  (every /events/:id/... handler should go through shared/with-tenant-event)."
  (:require [clojure.test :refer [deftest is use-fixtures testing]]
            [sports-manager.auth :as auth]
            [sports-manager.db :as db]
            [sports-manager.event :as event]
            [sports-manager.membership :as membership]
            [sports-manager.rbac :as rbac]
            [sports-manager.routes.core :as routes]
            [sports-manager.session :as session]
            [sports-manager.test-db :as test-db])
  (:import java.util.Date
           java.util.UUID))

(use-fixtures :each test-db/with-db)

(defn- seed-tenant! [name email]
  (let [tid (UUID/randomUUID)]
    (db/put-many! [{:xt/id tid :tenant/id tid :tenant/name name
                    :tenant/status :active :tenant/contact-email email
                    :tenant/created-at (Date.)}])
    tid))

(defn- seed-two-tenants!
  "Create tenants A (active) and B (other), a user who is an admin in BOTH,
  and a published event owned by tenant B. Returns the relevant ids."
  []
  (rbac/seed-roles!)
  (let [tid-a (seed-tenant! "Tenant A" "a@b.com")
        tid-b (seed-tenant! "Tenant B" "b@b.com")
        uid "iso-uid"]
    (db/put-many! [{:xt/id uid :user/firebase-uid uid :user/email "iso@test.com"
                    :user/status :active :user/created-at (Date.)}])
    (membership/create! uid tid-a)
    (membership/create! uid tid-b)
    (rbac/grant-role! uid :role.name/school-admin :actor uid)
    (let [ev (event/create! tid-b uid
                            {:event/name "B's Event"
                             :event/start-at #inst "2026-08-01T09:00"
                             :event/end-at #inst "2026-08-01T17:00"}
                            #{})
          ev-a (event/create! tid-a uid
                              {:event/name "A's Event"
                               :event/start-at #inst "2026-08-01T09:00"
                               :event/end-at #inst "2026-08-01T17:00"}
                              #{})]
      {:tid-a tid-a :tid-b tid-b :uid uid
       :event-b (:event/id ev) :event-a (:event/id ev-a)})))

(defn- find-user-from-test-db [uid]
  (db/pull [:user/firebase-uid :user/email :user/name :user/status :user/roles] uid))

(defn- do-req
  "Issue a request through routes/handler with `uid` authenticated and
  `active-tenant-id` set in the session."
  [method uri uid active-tenant-id]
  (with-redefs [session/uid-from-request (fn [req] (:uid req))
                auth/find-user find-user-from-test-db]
    (let [csrf "test-csrf-token"
          params (when (= method :post) {"__anti-forgery-token" csrf})
          cookie (test-db/session-cookie
                  (cond-> {:ring.middleware.anti-forgery/anti-forgery-token csrf}
                    active-tenant-id (assoc :active-tenant-id active-tenant-id)))]
      (routes/handler
       {:request-method method
        :uri uri
        :query-string ""
        :query-params {}
        :uid uid
        :headers {"host" "localhost" "cookie" (str "ring-session=" cookie)}
        :params (or params {})
        :form-params (or params {})}))))

;; ---------------------------------------------------------------------------
;; Cross-tenant: tenant A active, requesting tenant B's event → 404
;; ---------------------------------------------------------------------------

(deftest cross-tenant-event-access-is-blocked
  (let [{:keys [tid-a uid event-b]} (seed-two-tenants!)
        b (str event-b)
        bogus (str (UUID/randomUUID))
        ;; [method uri-relative-to-/events/:id]
        cases [[:get  (str "/events/" b)]
               [:get  (str "/events/" b "/dashboard")]
               [:get  (str "/events/" b "/qr")]
               [:post (str "/events/" b "/publish")]
               [:post (str "/events/" b "/participants")]
               [:post (str "/events/" b "/participants/" bogus "/remove")]
               [:post (str "/events/" b "/teams")]
               [:post (str "/events/" b "/teams/" bogus "/delete")]
               [:post (str "/events/" b "/venues")]
               [:post (str "/events/" b "/venues/" bogus "/delete")]
               [:post (str "/events/" b "/sports/rugby/config")]
               [:post (str "/events/" b "/fixtures")]
               [:post (str "/events/" b "/fixtures/" bogus)]
               [:post (str "/events/" b "/fixtures/" bogus "/publish")]
               [:post (str "/events/" b "/fixtures/" bogus "/codes")]
               [:post (str "/events/" b "/fixtures/" bogus "/codes/" bogus "/revoke")]
               [:get  (str "/events/" b "/fixtures/" bogus "/codes/" bogus "/qr")]
               [:post (str "/events/" b "/fixtures/" bogus "/assign")]
               [:get  (str "/events/" b "/fixtures/" bogus "/comparison")]
               [:post (str "/events/" b "/fixtures/" bogus "/resolve")]
               [:get  (str "/events/" b "/fixtures/export")]
               [:get  (str "/events/" b "/results/export")]
               [:get  (str "/events/" b "/score-audit/export")]
               [:get  (str "/events/" b "/import")]
               [:post (str "/events/" b "/import/upload")]
               [:get  (str "/events/" b "/import/map")]
               [:post (str "/events/" b "/import/map")]
               [:get  (str "/events/" b "/import/preview")]
               [:post (str "/events/" b "/import/confirm")]]]
    (doseq [[method uri] cases]
      (testing (str method " " uri)
        (let [resp (do-req method uri uid tid-a)]
          (is (= 404 (:status resp))
              (str method " " uri " should 404 cross-tenant, got " (:status resp))))))
    ;; The cross-tenant publish attempt must NOT have mutated B's event —
    ;; it was created as a draft and should still be a draft.
    (is (= :event.status/draft (:event/status (event/find-by-id event-b)))
        "cross-tenant publish must not transition B's event")))

;; ---------------------------------------------------------------------------
;; Same-tenant control: tenant A active, requesting A's own event → not 404
;; ---------------------------------------------------------------------------

(deftest same-tenant-event-access-is-allowed
  (let [{:keys [tid-a uid event-a]} (seed-two-tenants!)
        resp (do-req :get (str "/events/" event-a) uid tid-a)]
    (is (= 200 (:status resp)))
    ;; apostrophe is HTML-escaped (A&apos;s Event), so match a stable substring
    (is (re-find #"Event — Sports Manager" (:body resp)))))

;; ---------------------------------------------------------------------------
;; A non-member's session cannot reach the event either (no active tenant).
;; ---------------------------------------------------------------------------

(deftest no-active-tenant-redirects
  (let [{:keys [uid event-b]} (seed-two-tenants!)
        resp (do-req :get (str "/events/" event-b) uid nil)]
    (is (= 302 (:status resp)))
    (is (= "/select-tenant" (get-in resp [:headers "Location"])))))
