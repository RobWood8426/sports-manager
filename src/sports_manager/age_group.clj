(ns sports-manager.age-group
  "Fixed global age-group list (SPO event-wizard).

  Age groups are a single shared list across every tenant -- not per-school
  configuration. Events select a subset via checklist; this namespace is the
  pure source of truth for what's selectable.")

(def all
  "Ordered list of [keyword label] pairs for UI checklists."
  [[:age-group/u7 "U7"]
   [:age-group/u8 "U8"]
   [:age-group/u9 "U9"]
   [:age-group/u10 "U10"]
   [:age-group/u11 "U11"]
   [:age-group/u12 "U12"]
   [:age-group/u13 "U13"]
   [:age-group/u14 "U14"]
   [:age-group/u15 "U15"]
   [:age-group/u16 "U16"]
   [:age-group/u17 "U17"]
   [:age-group/u18 "U18"]
   [:age-group/open "Open"]])

(def ^:private valid-codes (into #{} (map first) all))

(defn valid?
  "True if `code` is a recognised age-group keyword."
  [code]
  (contains? valid-codes code))
