(ns sports-manager.i18n
  "Lightweight internationalisation: a `t` lookup over per-language EDN
  dictionaries on the classpath (resources/i18n/<lang>.edn).

  Pure inner-ring code — translation is data in, data out. The dictionaries
  are read once at namespace load. Lookup falls back af -> en -> the key's
  name, so a missing Afrikaans key degrades to English rather than blank.

  Values may contain %s-style placeholders; pass format args to `t` and they
  are applied with `clojure.core/format`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private default-lang "en")

(def supported
  "Supported language codes, in display order."
  ["en" "af"])

(def lang-names
  "Human-readable language names (shown in the switcher)."
  {"en" "English" "af" "Afrikaans"})

(defn- load-dict [lang]
  (if-let [res (io/resource (str "i18n/" lang ".edn"))]
    (edn/read-string (slurp res))
    {}))

(def ^:private dicts
  (into {} (map (fn [l] [l (load-dict l)]) supported)))

(defn normalize-lang
  "Coerce an arbitrary value to a supported language code, defaulting to en."
  [lang]
  (let [l (some-> lang name)]
    (if (contains? (set supported) l) l default-lang)))

(defn t
  "Translate `key` (a keyword) into `lang`. Falls back to English, then to the
  key's name. Trailing args are applied as `format` placeholders."
  [lang key & fmt-args]
  (let [l (normalize-lang lang)
        v (or (get-in dicts [l key])
              (get-in dicts [default-lang key])
              (name key))]
    (if (seq fmt-args)
      (apply format v fmt-args)
      v)))
