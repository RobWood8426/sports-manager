(ns sports-manager.storage
  "Object storage for user uploads — school logos, sponsor banners (SPO-23+).

  A small `Storage` protocol with a local-disk implementation backed by the
  Fly volume. Keeping consumers behind the protocol means swapping to S3/R2
  later is a new record, not a rewrite.

  Keys are relative POSIX-style paths, e.g. \"<tenant-id>/logo/<uuid>.png\".
  Callers build keys (see `object-key`); the storage layer never trusts a
  client-supplied filename and never interprets the key's tenant segment for
  access control — that is the route's job.

  Layer: this is the side-effect / orchestration ring (it does disk I/O).
  Pure helpers (`validate-upload`, `ext-for`, `object-key`) carry no I/O and
  belong to the inner ring."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sports-manager.config :as config])
  (:import java.util.UUID))

;; ---------------------------------------------------------------------------
;; Pure helpers (inner ring — no I/O)
;; ---------------------------------------------------------------------------

(def allowed-content-types
  "Image content-types we accept for uploads → file extension.
  SVG is intentionally excluded: inline SVG is an XSS vector and not worth the
  sanitisation burden for v1."
  {"image/png" "png"
   "image/jpeg" "jpg"
   "image/webp" "webp"})

(defn ext-for
  "File extension for an allowed content-type, or nil if not allowed."
  [content-type]
  (get allowed-content-types content-type))

(defn validate-upload
  "Validate an upload's content-type and size. Returns a map of field→error
  (empty = valid). Pure."
  [{:keys [content-type size]}]
  (cond-> {}
    (not (contains? allowed-content-types content-type))
    (assoc :file "Must be a PNG, JPEG, or WebP image")

    (and size (> size config/max-upload-bytes))
    (assoc :file (str "File too large (max "
                      (quot config/max-upload-bytes (* 1024 1024)) " MB)"))

    (and size (zero? size))
    (assoc :file "File is empty")))

(defn object-key
  "Build a storage key for a tenant's object of `kind` (a keyword like :logo)
  with the given content-type. Generates a fresh UUID filename — the client
  filename is never used. Returns nil for a disallowed content-type."
  [tenant-id kind content-type]
  (when-let [ext (ext-for content-type)]
    (str tenant-id "/" (name kind) "/" (UUID/randomUUID) "." ext)))

(defn- safe-key?
  "True when a key is a plain relative path with no traversal or absolute
  segments — guards the disk impl against path escapes."
  [k]
  (and (string? k)
       (not (str/blank? k))
       (not (str/starts-with? k "/"))
       (not (str/includes? k ".."))
       (not (str/includes? k "\\"))))

;; ---------------------------------------------------------------------------
;; Storage protocol
;; ---------------------------------------------------------------------------

(defprotocol Storage
  (put-object [this key bytes content-type]
    "Persist `bytes` under `key`. Returns the key on success.")
  (get-object [this key]
    "Return {:bytes byte-array :content-type s} for `key`, or nil if absent.")
  (delete-object [this key]
    "Delete the object at `key`. No-op if absent. Returns nil."))

;; ---------------------------------------------------------------------------
;; Local-disk implementation (Fly volume)
;; ---------------------------------------------------------------------------

(defn- ct-file
  "Sidecar file holding an object's content-type (kept next to the blob so the
  serving route can set the right header without a metadata store)."
  [^java.io.File f]
  (io/file (str (.getPath f) ".ct")))

(defrecord LocalStorage [root]
  Storage
  (put-object [_ key bytes content-type]
    (when-not (safe-key? key)
      (throw (ex-info "Unsafe storage key" {:key key})))
    (let [f (io/file root key)]
      (io/make-parents f)
      (with-open [out (io/output-stream f)]
        (.write out ^bytes bytes))
      (spit (ct-file f) (or content-type ""))
      key))

  (get-object [_ key]
    (when (safe-key? key)
      (let [f (io/file root key)]
        (when (.exists f)
          (let [ct-f (ct-file f)
                ct (when (.exists ct-f) (str/trim (slurp ct-f)))]
            {:bytes (with-open [in (io/input-stream f)
                                bos (java.io.ByteArrayOutputStream.)]
                      (io/copy in bos)
                      (.toByteArray bos))
             :content-type (if (str/blank? ct) "application/octet-stream" ct)})))))

  (delete-object [_ key]
    (when (safe-key? key)
      (let [f (io/file root key)]
        (when (.exists f) (.delete f))
        (let [ct-f (ct-file f)]
          (when (.exists ct-f) (.delete ct-f)))))
    nil))

(defn local-storage
  "Construct a LocalStorage rooted at `dir` (defaults to config/upload-dir)."
  ([] (local-storage config/upload-dir))
  ([dir] (->LocalStorage dir)))

(def default-storage
  "The process-wide storage instance. Swap this binding to change backends."
  (local-storage))
