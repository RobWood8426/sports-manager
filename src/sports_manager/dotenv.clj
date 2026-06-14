(ns sports-manager.dotenv
  "Tiny .env reader. Loads ./.env into an in-memory map once and exposes a
  `get-env` accessor with precedence: real process env var > .env file value.

  We deliberately do NOT go through `environ` for this: environ snapshots
  System/getenv + properties into a `defonce` at load time, so values injected
  after it loads are invisible. Reading our own map sidesteps that ordering
  trap and keeps prod (no .env present, real env vars only) working unchanged."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(defn- parse-line [line]
  (let [line (str/trim line)]
    (when-not (or (str/blank? line) (str/starts-with? line "#"))
      (when-let [idx (str/index-of line "=")]
        [(str/trim (subs line 0 idx))
         (str/trim (subs line (inc idx)))]))))

(defn- read-file
  "Parse path into a {\"VAR\" \"value\"} map. Missing file → empty map (prod)."
  [path]
  (let [f (io/file path)]
    (if (.exists f)
      (let [m (into {} (keep parse-line (str/split-lines (slurp f))))]
        (log/info "Loaded" (count m) "entries from" path)
        m)
      {})))

(defonce ^{:doc "Parsed .env contents, loaded once."}
  dotenv (read-file ".env"))

(defn get-env
  "Look up an env var by its UPPER_SNAKE name. Real process env wins over .env."
  [k]
  (or (System/getenv k) (get dotenv k)))
