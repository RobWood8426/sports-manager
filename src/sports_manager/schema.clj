(ns sports-manager.schema
  "Datomic schema, defined as plain attribute maps (the stub-server convention).

  Schemas are additive and idempotent: transacting an attribute that already
  exists is a no-op, so `install!` is safe to run on every boot. As domain
  areas are built (events, fixtures, scores, …) add a `defn`-free schema vector
  in its own namespace and `concat` it into `schema` below.

  ## Multi-tenancy
  Logical isolation via a per-entity tenant attribute. Every tenant-scoped
  entity carries `:tenant/id`; all tenant-scoped queries MUST filter on it.
  See `sports-manager.db` for the `tenant-scoped` query helper that enforces
  this so callers can't forget. (SPO-17)")

(def tenant
  "The tenant (school) itself, plus the tenant marker every scoped entity carries.
  Profile fields added in SPO-22 (§6A.1–9)."
  [{:db/ident :tenant/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Stable tenant (school) identifier. Also stamped on every tenant-scoped entity for isolation."}
   {:db/ident :tenant/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Display name of the school."}
   {:db/ident :tenant/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Lifecycle status, e.g. :active :suspended."}
   {:db/ident :tenant/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When the tenant was created."}
   ;; --- Profile fields (SPO-22) ---
   {:db/ident :tenant/address
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Street address of the school."}
   {:db/ident :tenant/city
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :tenant/province
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :tenant/country
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :tenant/contact-email
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Primary contact email for the school."}
   {:db/ident :tenant/contact-phone
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :tenant/website
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :tenant/latitude
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Map latitude stored as string to avoid float precision issues."}
   {:db/ident :tenant/longitude
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Map longitude stored as string to avoid float precision issues."}])

(def permission-enums
  "Enum idents for every granular permission (SPO-19). Installed as datoms so
  invalid permission keywords fail at the DB layer."
  [{:db/ident :permission/create-event}
   {:db/ident :permission/publish-event}
   {:db/ident :permission/manage-fixtures}
   {:db/ident :permission/assign-scorekeepers}
   {:db/ident :permission/override-score}
   {:db/ident :permission/manage-payments}
   {:db/ident :permission/manage-sponsors}
   {:db/ident :permission/view-audit-log}
   {:db/ident :permission/manage-users}
   {:db/ident :permission/manage-tenant}])

(def role
  "A named set of permissions, scoped to a tenant (or nil for platform roles).
  Roles are many-to-many with users via :user/roles. (SPO-19)"
  [{:db/ident :role/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :role/name
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Role identifier, e.g. :role.name/school-admin. Unique within a tenant."}
   {:db/ident :role/tenant
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Owning tenant. Nil for platform-level roles (super-admin, support)."}
   {:db/ident :role/permissions
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "Set of :permission/* idents granted by this role."}])

(def user
  "Authenticated users, keyed by their Firebase uid. A user belongs to a tenant
  (school) and holds one or more roles. (SPO-18, SPO-19)"
  [{:db/ident :user/firebase-uid
    :db/valueType :db.type/string
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Firebase Auth uid — the stable identity from the verified ID token."}
   {:db/ident :user/email
    :db/valueType :db.type/string
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Email address. Unique across all users; used for lookup when adding to a tenant."}
   {:db/ident :user/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :user/tenant
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "The tenant (school) this user belongs to."}
   {:db/ident :user/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "e.g. :active :invited :disabled."}
   {:db/ident :user/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :user/roles
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "Roles assigned to this user. Permission union across all roles is the effective permission set."}])

(def audit
  "Append-only audit log. Entries are never edited or retracted. (SPO-20)
   Transaction-level provenance also rides on the tx entity via :audit/* below."
  [{:db/ident :audit/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :audit/tenant
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Tenant the action belongs to."}
   {:db/ident :audit/action
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Action type, e.g. :fixture/publish :score/override."}
   {:db/ident :audit/entity-type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :audit/entity-id
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one}
   {:db/ident :audit/actor
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "User/session responsible for the action."}
   {:db/ident :audit/before
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Serialized prior value, where relevant."}
   {:db/ident :audit/after
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Serialized new value, where relevant."}
   {:db/ident :audit/reason
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Required reason for overrides/corrections."}
   {:db/ident :audit/at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}])

(def sport-template
  "Platform-level sport template reference data (SPO-25, SPO-32).
  Templates are seeded at boot and shared across all tenants.
  Tenant selections stored via :tenant/sport-templates.
  Custom sports (SPO-34) carry :sport-template/tenant and :sport-template/is-template false."
  [{:db/ident :sport-template/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :sport-template/code
    :db/valueType :db.type/keyword
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Stable keyword identifier, e.g. :sport/rugby."}
   {:db/ident :sport-template/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Display name, e.g. \"Rugby\"."}
   {:db/ident :sport-template/is-template
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "true = platform template; false = tenant-created custom sport."}
   {:db/ident :sport-template/scoring-increments
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "EDN vector string of valid score increments, e.g. \"[5 3 2 1]\". nil means free-form entry."}
   {:db/ident :sport-template/venue-type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Default venue type: :venue.type/field :venue.type/court :venue.type/pool :venue.type/track :venue.type/other"}
   {:db/ident :sport-template/period-labels
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Comma-separated period labels, e.g. \"Half,Half\" or \"Quarter,Quarter,Quarter,Quarter\"."}
   {:db/ident :sport-template/tenant
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Owning tenant for custom sports (SPO-34). Nil for platform templates."}
   ;; Venue type enum idents
   {:db/ident :venue.type/field}
   {:db/ident :venue.type/court}
   {:db/ident :venue.type/pool}
   {:db/ident :venue.type/track}
   {:db/ident :venue.type/other}
   {:db/ident :tenant/sport-templates
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "Sport templates this tenant has selected."}])

(def event
  "An event (sports day). Tenant-scoped, starts in draft status. (SPO-26, SPO-27, SPO-28, SPO-29, SPO-30)"
  [{:db/ident :event/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :event/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Display name of the event / sports day."}
   {:db/ident :event/description
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :event/start-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When the event starts."}
   {:db/ident :event/end-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When the event ends."}
   {:db/ident :event/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Lifecycle: :event.status/draft :event.status/published :event.status/cancelled."}
   {:db/ident :event/visibility
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc ":event.visibility/public or :event.visibility/private."}
   {:db/ident :event/access-method
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "How spectators gain access. :event.access/public-link :event.access/code-gated :event.access/paid :event.access/sponsor-funded"}
   {:db/ident :event/code
    :db/valueType :db.type/string
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Short alphanumeric code for spectator access (e.g. \"ABC123\"). (SPO-29)"}
   {:db/ident :event/published-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When the event was published. (SPO-30)"}
   {:db/ident :event/tenant
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Owning tenant."}
   {:db/ident :event/sport-templates
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "Sport templates featured in this event."}
   {:db/ident :event/participants
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "Schools participating in this event."}
   {:db/ident :event/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   ;; Status enum idents
   {:db/ident :event.status/draft}
   {:db/ident :event.status/published}
   {:db/ident :event.status/cancelled}
   ;; Visibility enum idents (SPO-28)
   {:db/ident :event.visibility/public}
   {:db/ident :event.visibility/private}
   ;; Access method enum idents (SPO-28) — paid/sponsor-funded scaffolded for future use
   {:db/ident :event.access/public-link}
   {:db/ident :event.access/code-gated}
   {:db/ident :event.access/paid}
   {:db/ident :event.access/sponsor-funded}
   ;; Participant entity (SPO-27)
   {:db/ident :participant/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :participant/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Display name of the participating school."}
   {:db/ident :participant/contact-email
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :participant/contact-phone
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :participant/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc ":participant.status/confirmed :participant.status/invited :participant.status/withdrawn"}
   {:db/ident :participant/tenant
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Optional link to the tenant (school) if they are onboarded on the platform."}
   ;; Participant status enums
   {:db/ident :participant.status/confirmed}
   {:db/ident :participant.status/invited}
   {:db/ident :participant.status/withdrawn}])

(def event-sport-config
  "Per-event sport configuration overrides (SPO-33).
  Sits between an event and a sport-template; only overridden fields are stored.
  Callers use effective-config to merge with template defaults."
  [{:db/ident :event-sport/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :event-sport/event
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "The event this config belongs to."}
   {:db/ident :event-sport/sport-template
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "The sport template being configured for this event."}
   {:db/ident :event-sport/scoring-increments
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "EDN vector string override, e.g. \"[5 3 2 1]\". Overrides template default."}
   {:db/ident :event-sport/period-labels
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Comma-separated period labels override, e.g. \"Half,Half\"."}
   {:db/ident :event-sport/venue-label
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Custom venue label for this event-sport (e.g. \"Main Field\")."}
   {:db/ident :event-sport/validation-model
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Score validation approach. Falls back to :validation.model/single if not set."}
   {:db/ident :event-sport/track-standings
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "Whether to compute standings for this sport in this event."}
   ;; Validation model enum idents
   {:db/ident :validation.model/single}
   {:db/ident :validation.model/single-pending}
   {:db/ident :validation.model/dual}
   {:db/ident :validation.model/admin-approval}
   {:db/ident :validation.model/consensus}])

(def fixture
  "Match/game fixtures within an event (SPO-37).
  Each fixture links two participants, a sport, and a time slot."
  [{:db/ident :fixture/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :fixture/match-number
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Short auto-generated identifier, e.g. \"M001\". Scoped to an event."}
   {:db/ident :fixture/event
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "The event this fixture belongs to."}
   {:db/ident :fixture/sport-template
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "The sport being played."}
   {:db/ident :fixture/team-a
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Participant entity for the first team."}
   {:db/ident :fixture/team-b
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Participant entity for the second team."}
   {:db/ident :fixture/age-group
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Age group label, e.g. \"U16 Boys\". Free-text; no learner PII."}
   {:db/ident :fixture/venue
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Venue label for this fixture. Free-text until SPO-35."}
   {:db/ident :fixture/start-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :fixture/end-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :fixture/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Lifecycle: :fixture.status/draft :fixture.status/published :fixture.status/cancelled"}
   {:db/ident :fixture/tenant
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Owning tenant for isolation."}
   {:db/ident :fixture/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   ;; Status enum idents
   {:db/ident :fixture.status/draft}
   {:db/ident :fixture.status/published}
   {:db/ident :fixture.status/cancelled}])

(def scorekeeper-code
  "Secure per-game scoring codes (SPO-42).
  The plaintext code is never stored; only its SHA-256 hex hash is persisted."
  [{:db/ident :scode/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :scode/fixture
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "The fixture this code grants access to."}
   {:db/ident :scode/code-hash
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "SHA-256 hex digest of the plaintext code. Never store plaintext."}
   {:db/ident :scode/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Lifecycle: :scode.status/active :scode.status/used :scode.status/revoked :scode.status/expired"}
   {:db/ident :scode/game-status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Scorekeeper game-flow status (SPO-44). Set on first access; advances through the scoring lifecycle."}
   {:db/ident :scode/tenant
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Owning tenant for isolation."}
   {:db/ident :scode/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :scode/created-by
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Firebase UID of the admin who generated the code."}
   {:db/ident :scode/expires-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When this code expires (set to end-at of the fixture)."}
   ;; Status enum idents
   {:db/ident :scode.status/active}
   {:db/ident :scode.status/used}
   {:db/ident :scode.status/revoked}
   {:db/ident :scode.status/expired}
   ;; Game-status enum idents (SPO-44)
   {:db/ident :scode.game-status/accessed}
   {:db/ident :scode.game-status/started}
   {:db/ident :scode.game-status/live}
   {:db/ident :scode.game-status/final-submitted}
   {:db/ident :scode.game-status/final-pending}
   {:db/ident :scode.game-status/final-accepted}
   {:db/ident :scode.game-status/final-disputed}])

(def scorekeeper-assignment
  "Links a scorekeeper label to a scoring code for a fixture (SPO-43).
  No platform account is required — access is code-only."
  [{:db/ident :assignment/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :assignment/fixture
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "The fixture this assignment belongs to."}
   {:db/ident :assignment/scode
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "The scoring code that grants access."}
   {:db/ident :assignment/label
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Human label for this scorekeeper slot, e.g. \"Scorekeeper 1\" or a name."}
   {:db/ident :assignment/tenant
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Owning tenant for isolation."}
   {:db/ident :assignment/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :assignment/created-by
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

(def score-event
  "Append-only log of score increments/decrements during a live game (SPO-45).
  Current score is derived by summing deltas. Never retracted — corrections are
  new events with negative deltas. SPO-47 adds client-id/ts for deduplication
  and a conflict flag for multi-scorekeeper detection."
  [{:db/ident :score-event/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :score-event/client-id
    :db/valueType :db.type/string
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Client-generated UUID for deduplication across retries and offline sync."}
   {:db/ident :score-event/client-ts
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "Device timestamp at the moment the event was recorded by the client."}
   {:db/ident :score-event/scode
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "The scorekeeper code that produced this event."}
   {:db/ident :score-event/fixture
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Denormalised fixture ref for efficient querying."}
   {:db/ident :score-event/team
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc ":score-event.team/a or :score-event.team/b"}
   {:db/ident :score-event/delta
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Score change: positive = increment, negative = correction/decrement."}
   {:db/ident :score-event/period
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Period label at time of event, e.g. \"Half 1\". Nil for sports without periods."}
   {:db/ident :score-event/conflict
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "True when this event conflicts with a concurrent submission from a different scorekeeper."}
   {:db/ident :score-event/recorded-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "Server timestamp when the event was received."}
   {:db/ident :score-event/tenant
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   ;; Team enum idents
   {:db/ident :score-event.team/a}
   {:db/ident :score-event.team/b}])

(def final-score
  "Submitted final score for a fixture (SPO-48). One per scode submission.
  Validation status is set immediately based on the event-sport validation model."
  [{:db/ident :final-score/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :final-score/fixture
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :final-score/scode
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "The scorekeeper code that submitted this final score."}
   {:db/ident :final-score/team-a-score
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :final-score/team-b-score
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :final-score/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Validation status: accepted / pending / disputed."}
   {:db/ident :final-score/validation-model
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "The validation model that was in effect at time of submission."}
   {:db/ident :final-score/submitted-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :final-score/tenant
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   ;; Status enum idents
   {:db/ident :final-score.status/accepted}
   {:db/ident :final-score.status/pending}
   {:db/ident :final-score.status/disputed}])

(def venue
  "Venue entities scoped to an event (SPO-35).
  Venues are assigned to fixtures via :fixture/venue-ref.
  The existing :fixture/venue string is kept for backwards compat."
  [;; Additional venue type enums (field/court/pool/track/other already exist on sport-template)
   {:db/ident :venue.type/pitch}
   {:db/ident :venue.type/astro}
   {:db/ident :venue.type/hall}
   ;; Venue entity
   {:db/ident :venue/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :venue/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :venue/type
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Reference to a :venue.type/* enum."}
   {:db/ident :venue/lat
    :db/valueType :db.type/double
    :db/cardinality :db.cardinality/one
    :db/doc "Latitude (optional)."}
   {:db/ident :venue/lng
    :db/valueType :db.type/double
    :db/cardinality :db.cardinality/one
    :db/doc "Longitude (optional)."}
   {:db/ident :venue/display-order
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Sort order for display (optional)."}
   {:db/ident :venue/event
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "The event this venue belongs to."}
   {:db/ident :venue/tenant
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Owning tenant for isolation."}
   ;; Fixture → venue ref (replaces free-text string for new fixtures)
   {:db/ident :fixture/venue-ref
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Optional reference to a venue entity. Supersedes :fixture/venue string (SPO-35)."}])

(def team
  "Label-only team entities scoped to an event and sport (SPO-36).
  No learner names, profiles, or individual stats — POPIA compliant."
  [{:db/ident :team/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident :team/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Display label for this team, e.g. \"Rondebosch U16 Boys\"."}
   {:db/ident :team/age-group
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Age group label, e.g. \"U16\". Free-text; no PII."}
   {:db/ident :team/gender
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Optional category: :team.gender/boys :team.gender/girls :team.gender/mixed"}
   {:db/ident :team/event
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "The event this team belongs to."}
   {:db/ident :team/participant
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "The participating school this team represents."}
   {:db/ident :team/sport
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "The sport template this team competes in."}
   {:db/ident :team/tenant
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Owning tenant for isolation."}
   ;; Gender enum idents
   {:db/ident :team.gender/boys}
   {:db/ident :team.gender/girls}
   {:db/ident :team.gender/mixed}])

(def membership
  "Join entity linking a user to a tenant. Replaces the single :user/tenant ref.
  Tenant-scoped roles live here; platform roles stay on :user/roles."
  [{:db/ident :membership/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Stable membership identifier."}
   {:db/ident :membership/user
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "The user in this membership."}
   {:db/ident :membership/tenant
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "The tenant (school) in this membership."}
   {:db/ident :membership/roles
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "Tenant-scoped roles for this user in this tenant."}
   {:db/ident :membership/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Lifecycle: :membership.status/active :membership.status/disabled"}
   {:db/ident :membership/joined-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :membership/user+tenant
    :db/valueType :db.type/tuple
    :db/tupleAttrs [:membership/user :membership/tenant]
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Composite unique — one membership per (user, tenant)."}
   ;; Status enum idents
   {:db/ident :membership.status/active}
   {:db/ident :membership.status/disabled}])

(def schema
  "The full schema, concatenated from each domain area. Add new areas here."
  (vec (concat tenant
               permission-enums
               role
               user
               membership
               audit
               sport-template
               event
               event-sport-config
               fixture
               venue
               team
               scorekeeper-code
               scorekeeper-assignment
               score-event
               final-score)))

