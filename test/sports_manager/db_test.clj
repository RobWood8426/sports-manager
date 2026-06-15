(ns sports-manager.db-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [sports-manager.db :as db]
            [sports-manager.test-db :as test-db]))

(use-fixtures :each test-db/with-db)

(deftest tenant-isolation
  (testing "tenant-scoped only returns entities belonging to the given tenant"
    (let [t1 (java.util.UUID/randomUUID)
          t2 (java.util.UUID/randomUUID)
          e1 (java.util.UUID/randomUUID)
          e2 (java.util.UUID/randomUUID)]
      ;; Two events, each owned by a different tenant
      (db/put-many! [{:xt/id e1 :event/id e1 :event/name "Alpha Day" :event/tenant t1}
                     {:xt/id e2 :event/id e2 :event/name "Beta Day" :event/tenant t2}])
      (let [t1-names (db/tenant-scoped :event/tenant t1 '[?n] '[[?e :event/name ?n]])
            t2-names (db/tenant-scoped :event/tenant t2 '[?n] '[[?e :event/name ?n]])]
        (is (= #{["Alpha Day"]} (set t1-names)))
        (is (= #{["Beta Day"]} (set t2-names)))))))
