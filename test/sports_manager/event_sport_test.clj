(ns sports-manager.event-sport-test
  "Tests for per-event sport configuration overrides (SPO-33)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [sports-manager.db :as db]
            [sports-manager.event :as event]
            [sports-manager.event-sport :as es]
            [sports-manager.sport-template :as st]
            [sports-manager.test-db :as test-db])
  (:import java.util.UUID))

(use-fixtures :each test-db/with-db)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- seed-event! []
  (st/seed-templates!)
  (let [tid (UUID/randomUUID)
        uid "actor-uid"]
    (db/transact! [{:db/id "t" :tenant/id tid :tenant/name "Test School" :tenant/status :active}
                   {:user/firebase-uid uid :user/email "a@x.com" :user/status :active
                    :user/tenant "t"}])
    (let [ev (event/create! tid uid
                             {:event/name "Test Day"
                              :event/start-at #inst "2026-09-01T09:00"
                              :event/end-at #inst "2026-09-01T17:00"
                              :event/visibility :event.visibility/public
                              :event/access-method :event.access/public-link}
                             #{:sport/rugby :sport/netball})]
      {:event-id (:event/id ev) :uid uid})))

;; ---------------------------------------------------------------------------
;; effective-config (pure)
;; ---------------------------------------------------------------------------

(deftest effective-config-uses-template-defaults
  (testing "returns template values when no overrides present"
    (let [tmpl {:sport-template/code :sport/rugby
                :sport-template/scoring-increments "[5 3 2 1]"
                :sport-template/period-labels "Half,Half"
                :sport-template/venue-type :venue.type/field}
          result (es/effective-config tmpl {})]
      (is (= "[5 3 2 1]" (:effective/scoring-increments result)))
      (is (= "Half,Half" (:effective/period-labels result)))
      (is (= :venue.type/field (:effective/venue-type result)))
      (is (= :validation.model/single (:effective/validation-model result)))
      (is (false? (:effective/track-standings result))))))

(deftest effective-config-override-wins
  (testing "override values replace template defaults"
    (let [tmpl {:sport-template/scoring-increments "[5 3 2 1]"
                :sport-template/period-labels "Half,Half"
                :sport-template/venue-type :venue.type/field}
          override {:event-sport/scoring-increments "[7 5 3 2 1]"
                    :event-sport/period-labels "Quarter,Quarter,Quarter,Quarter"
                    :event-sport/venue-label "North Field"
                    :event-sport/validation-model :validation.model/dual
                    :event-sport/track-standings true}
          result (es/effective-config tmpl override)]
      (is (= "[7 5 3 2 1]" (:effective/scoring-increments result)))
      (is (= "Quarter,Quarter,Quarter,Quarter" (:effective/period-labels result)))
      (is (= "North Field" (:effective/venue-label result)))
      (is (= :validation.model/dual (:effective/validation-model result)))
      (is (true? (:effective/track-standings result))))))

(deftest effective-config-partial-override
  (testing "only specified fields are overridden; others fall back to template"
    (let [tmpl {:sport-template/scoring-increments "[5 3 2 1]"
                :sport-template/period-labels "Half,Half"
                :sport-template/venue-type :venue.type/field}
          override {:event-sport/venue-label "South Pitch"}
          result (es/effective-config tmpl override)]
      (is (= "[5 3 2 1]" (:effective/scoring-increments result)))
      (is (= "Half,Half" (:effective/period-labels result)))
      (is (= "South Pitch" (:effective/venue-label result))))))

(deftest effective-config-nil-scoring-increments
  (testing "nil template scoring-increments produces no :effective/scoring-increments key"
    (let [tmpl {:sport-template/scoring-increments nil
                :sport-template/period-labels "Race"
                :sport-template/venue-type :venue.type/pool}
          result (es/effective-config tmpl {})]
      (is (nil? (:effective/scoring-increments result))))))

;; ---------------------------------------------------------------------------
;; find-config / configure!
;; ---------------------------------------------------------------------------

(deftest find-config-returns-nil-when-no-override
  (testing "returns nil when no event-sport-config exists"
    (let [{:keys [event-id]} (seed-event!)]
      (is (nil? (es/find-config event-id :sport/rugby))))))

(deftest configure-creates-override
  (testing "configure! creates an event-sport-config and find-config returns it"
    (let [{:keys [event-id uid]} (seed-event!)]
      (es/configure! event-id :sport/rugby
                     {:event-sport/scoring-increments "[7 5 3 2 1]"
                      :event-sport/period-labels "Quarter,Quarter,Quarter,Quarter"
                      :event-sport/validation-model :validation.model/dual
                      :event-sport/track-standings true}
                     uid)
      (let [cfg (es/find-config event-id :sport/rugby)]
        (is (some? cfg))
        (is (= "[7 5 3 2 1]" (:event-sport/scoring-increments cfg)))
        (is (= "Quarter,Quarter,Quarter,Quarter" (:event-sport/period-labels cfg)))
        (is (= :validation.model/dual (:event-sport/validation-model cfg)))
        (is (true? (:event-sport/track-standings cfg)))))))

(deftest configure-is-idempotent
  (testing "calling configure! again updates the same entity"
    (let [{:keys [event-id uid]} (seed-event!)]
      (es/configure! event-id :sport/rugby
                     {:event-sport/scoring-increments "[5 3 2 1]"}
                     uid)
      (es/configure! event-id :sport/rugby
                     {:event-sport/scoring-increments "[7 5 3 2 1]"}
                     uid)
      (let [cfg (es/find-config event-id :sport/rugby)]
        (is (= "[7 5 3 2 1]" (:event-sport/scoring-increments cfg)))
        ;; Only one config entity should exist
        (is (uuid? (:event-sport/id cfg)))))))

(deftest configure-unknown-event-throws
  (testing "throws ex-info when event does not exist"
    (st/seed-templates!)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Event not found"
                          (es/configure! (UUID/randomUUID) :sport/rugby {} "actor")))))

(deftest configure-unknown-sport-throws
  (testing "throws ex-info when sport template does not exist"
    (let [{:keys [event-id uid]} (seed-event!)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Sport template not found"
                            (es/configure! event-id :sport/underwater-chess {} uid))))))

;; ---------------------------------------------------------------------------
;; list-by-event
;; ---------------------------------------------------------------------------

(deftest list-by-event-returns-effective-configs
  (testing "returns one entry per sport on the event, merged with template defaults"
    (let [{:keys [event-id]} (seed-event!)
          configs (es/list-by-event event-id)]
      (is (= 2 (count configs)))
      (is (every? :effective/validation-model configs))
      (is (every? :sport-template/name configs)))))

(deftest list-by-event-override-appears-in-effective
  (testing "override values flow through to :effective/* keys"
    (let [{:keys [event-id uid]} (seed-event!)]
      (es/configure! event-id :sport/rugby
                     {:event-sport/scoring-increments "[7 5 3 2 1]"
                      :event-sport/track-standings true}
                     uid)
      (let [rugby (->> (es/list-by-event event-id)
                       (filter #(= :sport/rugby (:sport-template/code %)))
                       first)]
        (is (= "[7 5 3 2 1]" (:effective/scoring-increments rugby)))
        (is (true? (:effective/track-standings rugby)))))))

(deftest list-by-event-empty-when-no-sports
  (testing "returns empty list when event has no sports"
    (st/seed-templates!)
    (let [tid (UUID/randomUUID)
          uid "u2"]
      (db/transact! [{:db/id "t2" :tenant/id tid :tenant/name "No Sport School" :tenant/status :active}
                     {:user/firebase-uid uid :user/email "b@x.com" :user/status :active
                      :user/tenant "t2"}])
      (let [ev (event/create! tid uid
                               {:event/name "Empty Day"
                                :event/start-at #inst "2026-10-01T09:00"
                                :event/end-at #inst "2026-10-01T17:00"
                                :event/visibility :event.visibility/public
                                :event/access-method :event.access/public-link}
                               #{})]
        (is (empty? (es/list-by-event (:event/id ev))))))))
