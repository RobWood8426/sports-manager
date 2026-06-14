(ns sports-manager.db-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [sports-manager.db :as db]
            [sports-manager.test-db :as test-db]))

(use-fixtures :each test-db/with-db)

(deftest schema-installed
  (testing "tenant + audit attributes are present after install"
    (is (some? (db/entity :tenant/id)))
    (is (some? (db/entity :audit/action)))))

(deftest tenant-isolation
  (testing "tenant-scoped only returns entities of the given tenant"
    (let [t1 (java.util.UUID/randomUUID)
          t2 (java.util.UUID/randomUUID)]
      (db/transact! [{:tenant/id t1 :tenant/name "Alpha High"}
                     {:tenant/id t2 :tenant/name "Beta College"}])
      ;; A query scoped to t1 must never surface t2's entity.
      (let [t1-names (db/tenant-scoped t1 '[?n] '[[?e :tenant/name ?n]])
            t2-names (db/tenant-scoped t2 '[?n] '[[?e :tenant/name ?n]])]
        (is (= #{["Alpha High"]} (set t1-names)))
        (is (= #{["Beta College"]} (set t2-names)))))))
