(ns sports-manager.schema
  "Entity attribute documentation for the sports-manager domain.

  XTDB is schema-free -- nothing here is transacted. This namespace serves as:
    1. The authoritative reference for what attributes each entity carries.
    2. The source of entity-type metadata used by seed and migration code.

  Identity model: each entity has one business-key attribute that becomes its
  :xt/id. Refs store the target's :xt/id value directly (no numeric pointers).

  Enums are plain Clojure keywords stored as values -- no ident entities needed.

  ## Entity :xt/id keys
    :tenant/id          uuid
    :user/firebase-uid  string
    :role/id            uuid
    :membership/id      uuid  (composite: [firebase-uid tenant-id] recommended for uniqueness)
    :audit/id           uuid
    :sport-template/code  keyword  (e.g. :sport/rugby)
    :event/id           uuid
    :participant/id     uuid
    :event-sport/id     uuid
    :fixture/id         uuid
    :venue/id           uuid
    :team/id            uuid
    :scode/id           uuid
    :assignment/id      uuid
    :score-event/id     uuid
    :final-score/id     uuid

  ## Multi-tenancy
  Every tenant-scoped entity carries a :<entity>/tenant attribute whose value
  is the tenant's :xt/id (a UUID). All tenant-scoped queries must filter on it.")
