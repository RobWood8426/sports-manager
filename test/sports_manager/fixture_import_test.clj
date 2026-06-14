(ns sports-manager.fixture-import-test
  "Tests for CSV fixture import (SPO-38/39)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [sports-manager.db :as db]
            [sports-manager.event :as event]
            [sports-manager.fixture :as fixture]
            [sports-manager.fixture-import :as fi]
            [sports-manager.participant :as participant]
            [sports-manager.sport-template :as st]
            [sports-manager.test-db :as test-db])
  (:import java.io.ByteArrayInputStream
           java.util.UUID))

(use-fixtures :each test-db/with-db)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- csv-stream [s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(defn- seed! []
  (st/seed-templates!)
  (let [tid (UUID/randomUUID)
        uid "actor"]
    (db/transact! [{:db/id "t" :tenant/id tid :tenant/name "Test School" :tenant/status :active}
                   {:user/firebase-uid uid :user/email "a@x.com" :user/status :active
                    :user/tenant "t"}])
    (let [ev (event/create! tid uid
                            {:event/name "Sports Day"
                             :event/start-at #inst "2026-09-01T08:00"
                             :event/end-at #inst "2026-09-01T18:00"
                             :event/visibility :event.visibility/public
                             :event/access-method :event.access/public-link}
                            #{:sport/rugby :sport/netball})
          event-id (:event/id ev)
          pa (participant/add-to-event! event-id uid
                                        {:participant/name "Lions" :participant/type :team}
                                        nil)
          pb (participant/add-to-event! event-id uid
                                        {:participant/name "Tigers" :participant/type :team}
                                        nil)]
      {:tid tid :uid uid :event-id event-id
       :pa pa :pb pb})))

;; ---------------------------------------------------------------------------
;; parse-csv
;; ---------------------------------------------------------------------------

(deftest parse-csv-basic
  (testing "returns headers and rows"
    (let [csv "sport,team-a,team-b,start\nrugby,Lions,Tigers,2026-09-01T09:00"
          result (fi/parse-csv (csv-stream csv))]
      (is (= ["sport" "team-a" "team-b" "start"] (:headers result)))
      (is (= [["rugby" "Lions" "Tigers" "2026-09-01T09:00"]] (:rows result))))))

(deftest parse-csv-trims-whitespace
  (testing "trims whitespace from headers and cells"
    (let [csv " sport , team-a , team-b \n rugby , Lions , Tigers "
          result (fi/parse-csv (csv-stream csv))]
      (is (= ["sport" "team-a" "team-b"] (:headers result)))
      (is (= [["rugby" "Lions" "Tigers"]] (:rows result))))))

(deftest parse-csv-multiple-rows
  (testing "parses multiple data rows"
    (let [csv "s,a,b\nrugby,Lions,Tigers\nnetball,Eagles,Hawks"
          result (fi/parse-csv (csv-stream csv))]
      (is (= 2 (count (:rows result)))))))

(deftest parse-csv-empty-stream
  (testing "returns nil for empty input"
    (is (nil? (fi/parse-csv (csv-stream ""))))))

;; ---------------------------------------------------------------------------
;; apply-mapping
;; ---------------------------------------------------------------------------

(deftest apply-mapping-basic
  (testing "maps columns to field keywords"
    (let [parsed {:headers ["s" "a" "b" "start"]
                  :rows [["rugby" "Lions" "Tigers" "2026-09-01T09:00"]]}
          mapping {:sport-code "0" :team-a "1" :team-b "2" :start-at "3"}
          result (fi/apply-mapping parsed mapping)]
      (is (= 1 (count result)))
      (is (= "rugby" (:sport-code (first result))))
      (is (= "Lions" (:team-a (first result))))
      (is (= "Tigers" (:team-b (first result)))))))

(deftest apply-mapping-skips-blank-rows
  (testing "skips rows where all cells are blank"
    (let [parsed {:headers ["s" "a" "b"]
                  :rows [["rugby" "Lions" "Tigers"]
                         ["" "" ""]
                         ["netball" "Eagles" "Hawks"]]}
          mapping {:sport-code "0" :team-a "1" :team-b "2"}
          result (fi/apply-mapping parsed mapping)]
      (is (= 2 (count result))))))

(deftest apply-mapping-ignores-invalid-column-indices
  (testing "ignores mappings with out-of-range column indices"
    (let [parsed {:headers ["s"] :rows [["rugby"]]}
          mapping {:sport-code "0" :team-a "99"}
          result (fi/apply-mapping parsed mapping)]
      (is (= "rugby" (:sport-code (first result))))
      (is (nil? (:team-a (first result)))))))

;; ---------------------------------------------------------------------------
;; validate-rows
;; ---------------------------------------------------------------------------

(def ^:private participants #{"lions" "tigers" "eagles" "hawks"})

(deftest validate-rows-valid
  (testing "accepts a fully valid row"
    (let [rows [{:sport-code "rugby"
                 :team-a "Lions"
                 :team-b "Tigers"
                 :start-at "2026-09-01T09:00"
                 :end-at "2026-09-01T10:00"}]
          {:keys [valid errors]} (fi/validate-rows rows participants)]
      (is (= 1 (count valid)))
      (is (empty? errors)))))

(deftest validate-rows-missing-required-fields
  (testing "errors on missing sport, team-a, team-b, start-at"
    (let [rows [{}]
          {:keys [valid errors]} (fi/validate-rows rows participants)]
      (is (empty? valid))
      (is (= 1 (count errors)))
      (let [msgs (get-in errors [0 :messages])]
        (is (some #(str/includes? % "Sport") msgs))
        (is (some #(str/includes? % "Team A") msgs))
        (is (some #(str/includes? % "Team B") msgs))
        (is (some #(str/includes? % "Start time") msgs))))))

(deftest validate-rows-same-team
  (testing "errors when team-a equals team-b"
    (let [rows [{:sport-code "rugby" :team-a "Lions" :team-b "Lions"
                 :start-at "2026-09-01T09:00"}]
          {:keys [errors]} (fi/validate-rows rows participants)]
      (is (= 1 (count errors)))
      (is (some #(str/includes? % "different") (get-in errors [0 :messages]))))))

(deftest validate-rows-unknown-participant
  (testing "errors when team name not in participant-names set"
    (let [rows [{:sport-code "rugby" :team-a "Lions" :team-b "Ghosts"
                 :start-at "2026-09-01T09:00"}]
          {:keys [errors]} (fi/validate-rows rows participants)]
      (is (= 1 (count errors)))
      (is (some #(str/includes? % "Ghosts") (get-in errors [0 :messages]))))))

(deftest validate-rows-end-before-start
  (testing "errors when end-at is before start-at"
    (let [rows [{:sport-code "rugby" :team-a "Lions" :team-b "Tigers"
                 :start-at "2026-09-01T10:00"
                 :end-at "2026-09-01T09:00"}]
          {:keys [errors]} (fi/validate-rows rows participants)]
      (is (= 1 (count errors)))
      (is (some #(str/includes? % "after") (get-in errors [0 :messages]))))))

(deftest validate-rows-duplicate-detection
  (testing "flags duplicate fixture (same teams, sport, start time)"
    (let [row {:sport-code "rugby" :team-a "Lions" :team-b "Tigers"
               :start-at "2026-09-01T09:00"}
          rows [row row]
          {:keys [valid errors]} (fi/validate-rows rows participants)]
      (is (= 1 (count valid)))
      (is (= 1 (count errors)))
      (is (some #(str/includes? % "Duplicate") (get-in errors [0 :messages]))))))

(deftest validate-rows-mixed
  (testing "separates valid rows from error rows"
    (let [rows [{:sport-code "rugby" :team-a "Lions" :team-b "Tigers"
                 :start-at "2026-09-01T09:00"}
                {:sport-code "" :team-a "Eagles" :team-b "Hawks"
                 :start-at "2026-09-01T11:00"}]
          {:keys [valid errors]} (fi/validate-rows rows participants)]
      (is (= 1 (count valid)))
      (is (= 1 (count errors))))))

;; ---------------------------------------------------------------------------
;; import! (integration — requires DB)
;; ---------------------------------------------------------------------------

(deftest import-creates-draft-fixtures
  (testing "import! creates one draft fixture per valid row"
    (let [{:keys [event-id uid pa pb]} (seed!)
          valid-rows [{:row-index 0
                       :params {:fixture/sport-code :sport/rugby
                                :fixture/start-at #inst "2026-09-01T09:00"
                                :fixture/end-at nil
                                :fixture/age-group nil
                                :fixture/venue nil
                                :_team-a-name (:participant/name pa)
                                :_team-b-name (:participant/name pb)}}]
          n (fi/import! event-id uid valid-rows [pa pb])]
      (is (= 1 n))
      (let [fixtures (fixture/list-by-event event-id)]
        (is (= 1 (count fixtures)))
        (is (= :fixture.status/draft (:fixture/status (first fixtures))))))))

(deftest import-skips-unresolvable-participant
  (testing "import! skips rows where participant name can't be resolved"
    (let [{:keys [event-id uid pa pb]} (seed!)
          valid-rows [{:row-index 0
                       :params {:fixture/sport-code :sport/rugby
                                :fixture/start-at #inst "2026-09-01T09:00"
                                :fixture/end-at nil
                                :fixture/age-group nil
                                :fixture/venue nil
                                :_team-a-name "Unknown Team"
                                :_team-b-name (:participant/name pb)}}]
          n (fi/import! event-id uid valid-rows [pa pb])]
      (is (= 1 n))
      (is (empty? (fixture/list-by-event event-id))))))

;; ---------------------------------------------------------------------------
;; Temp file round-trip
;; ---------------------------------------------------------------------------

(deftest temp-file-round-trip
  (testing "write-import-temp! / read-import-temp round-trips EDN data"
    (let [data {:headers ["a" "b"] :rows [["1" "2"]]}
          path (fi/write-import-temp! data)
          read-back (fi/read-import-temp path)]
      (is (= data read-back))
      (fi/delete-import-temp! path)
      (is (nil? (fi/read-import-temp path))))))
