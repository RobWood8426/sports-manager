(ns sports-manager.routes.admin.imports
  "CSV fixture import wizard handlers."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ring.util.response :as resp]
            [sports-manager.event :as event]
            [sports-manager.fixture-import :as fixture-import]
            [sports-manager.participant :as participant]
            [sports-manager.routes.shared :as shared]
            [sports-manager.views.fixtures :as views.fixtures]))

(defn fixture-import-page
  "GET /events/:id/import — step 1: upload form."
  [request]
  (let [[user-or-redirect _tid] (shared/require-tenant request)]
    (if-not _tid
      user-or-redirect
      (let [event-id (shared/parse-event-id (get-in request [:path-params :id]))
            ev (when event-id (event/find-by-id event-id))]
        (if-not ev
          {:status 404 :body "Event not found"}
          (shared/html (views.fixtures/fixture-import-upload ev)))))))

(defn fixture-import-upload
  "POST /events/:id/import/upload — parse CSV, store to temp file, redirect to mapping."
  [request]
  (let [[user-or-redirect _tid] (shared/require-tenant request)]
    (if-not _tid
      user-or-redirect
      (let [event-id (shared/parse-event-id (get-in request [:path-params :id]))
            ev (when event-id (event/find-by-id event-id))
            file-part (get-in request [:multipart-params "csv-file"])]
        (cond
          (not ev)
          {:status 404 :body "Event not found"}

          (or (nil? file-part) (str/blank? (get file-part :filename "")))
          (shared/html (views.fixtures/fixture-import-upload ev {:error "Please select a CSV file."}))

          :else
          (let [parsed (fixture-import/parse-csv (io/input-stream (:tempfile file-part)))]
            (if (nil? parsed)
              (shared/html (views.fixtures/fixture-import-upload ev {:error "Could not parse the file. Ensure it is a valid UTF-8 CSV."}))
              (let [tmp-path (fixture-import/write-import-temp! parsed)]
                (-> (resp/redirect (str "/events/" event-id "/import/map"))
                    (assoc :session (assoc (:session request) :import-tmp tmp-path)))))))))))

(defn fixture-import-map-page
  "GET /events/:id/import/map — step 2: column mapping form."
  [request]
  (let [[user-or-redirect _tid] (shared/require-tenant request)]
    (if-not _tid
      user-or-redirect
      (let [event-id (shared/parse-event-id (get-in request [:path-params :id]))
            ev (when event-id (event/find-by-id event-id))
            tmp-path (get-in request [:session :import-tmp])
            parsed (fixture-import/read-import-temp tmp-path)]
        (cond
          (not ev) {:status 404 :body "Event not found"}
          (not parsed) (resp/redirect (str "/events/" event-id "/import"))
          :else (shared/html (views.fixtures/fixture-import-map ev (:headers parsed) fixture-import/importable-fields)))))))

(defn fixture-import-map-submit
  "POST /events/:id/import/map — apply column mapping, validate, show preview."
  [request]
  (let [[user-or-redirect _tid] (shared/require-tenant request)]
    (if-not _tid
      user-or-redirect
      (let [event-id (shared/parse-event-id (get-in request [:path-params :id]))
            ev (when event-id (event/find-by-id event-id))
            tmp-path (get-in request [:session :import-tmp])
            parsed (fixture-import/read-import-temp tmp-path)]
        (cond
          (not ev) {:status 404 :body "Event not found"}
          (not parsed) (resp/redirect (str "/events/" event-id "/import"))
          :else
          (let [params (shared/form-params request)
                mapping (into {}
                              (keep (fn [f]
                                      (let [v (get params (name (:key f)) "")]
                                        (when-not (str/blank? v)
                                          [(:key f) v])))
                                    fixture-import/importable-fields))
                mapped-rows (fixture-import/apply-mapping parsed mapping)
                participants (participant/list-by-event event-id)
                p-names (into #{} (map #(str/lower-case (:participant/name %))) participants)
                result (fixture-import/validate-rows mapped-rows p-names)
                preview-path (fixture-import/write-import-temp!
                              {:valid (:valid result) :errors (:errors result) :event-id event-id})]
            (-> (resp/redirect (str "/events/" event-id "/import/preview"))
                (assoc :session (assoc (:session request)
                                       :import-preview-tmp preview-path)))))))))

(defn fixture-import-preview-page
  "GET /events/:id/import/preview — step 3: show validated rows and confirm button."
  [request]
  (let [[user-or-redirect _tid] (shared/require-tenant request)]
    (if-not _tid
      user-or-redirect
      (let [event-id (shared/parse-event-id (get-in request [:path-params :id]))
            ev (when event-id (event/find-by-id event-id))
            tmp-path (get-in request [:session :import-preview-tmp])
            preview (fixture-import/read-import-temp tmp-path)]
        (cond
          (not ev) {:status 404 :body "Event not found"}
          (not preview) (resp/redirect (str "/events/" event-id "/import"))
          :else (shared/html (views.fixtures/fixture-import-preview ev (:valid preview) (:errors preview))))))))

(defn fixture-import-confirm
  "POST /events/:id/import/confirm — create draft fixtures, clean up temp files, redirect."
  [request]
  (let [[user-or-redirect _tid] (shared/require-tenant request)]
    (if-not _tid
      user-or-redirect
      (let [event-id (shared/parse-event-id (get-in request [:path-params :id]))
            ev (when event-id (event/find-by-id event-id))
            tmp-path (get-in request [:session :import-preview-tmp])
            preview (fixture-import/read-import-temp tmp-path)]
        (cond
          (not ev) {:status 404 :body "Event not found"}
          (not preview) (resp/redirect (str "/events/" event-id "/import"))
          :else
          (let [uid (:uid request)
                participants (participant/list-by-event event-id)
                _count (fixture-import/import! event-id uid (:valid preview) participants)]
            (fixture-import/delete-import-temp! tmp-path)
            (fixture-import/delete-import-temp! (get-in request [:session :import-tmp]))
            (-> (resp/redirect (str "/events/" event-id))
                (assoc :session (dissoc (:session request) :import-tmp :import-preview-tmp)))))))))
