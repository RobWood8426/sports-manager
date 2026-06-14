# Datomic setup

We use **Datomic Pro (peer)** — the same flavour as `stub-server`. Dev and tests
run against an in-memory db (`datomic:mem://`); production uses a SQL-backed
transactor configured via env vars.

## Resolving the dependency

`com.datomic/peer` is published to the `my.datomic.com` Maven repo (configured in
`deps.edn` under `:mvn/repos`), **not** Maven Central. You need Datomic Pro
license credentials to download it.

Add them to `~/.clojure/deps.edn` (user-level, never commit):

```clojure
{:mvn/repos
 {"my.datomic.com"
  {:url "https://my.datomic.com/repo"}}
 ;; credentials picked up from ~/.m2/settings.xml, or set in CI as a secret
}
```

The license email + key live in your `~/.m2/settings.xml` server entry for
`my.datomic.com` (same as the stub-server machine setup).

## Running locally

By default **no setup is needed** — `scripts/run.sh` boots an in-memory db named
`sports-manager-dev`, installs the schema, and is ready. Data is lost on restart.

To persist locally via a dev transactor instead:

```sh
export SM_DATOMIC_USE_TRANSACTOR=true
export SM_DATOMIC_DATABASE=sports-manager
# ...with a transactor running on localhost:4334
```

## Configuration (env vars)

| Env var                      | Default               | Purpose                                            |
|------------------------------|-----------------------|----------------------------------------------------|
| `SM_DATOMIC_URI`             | —                     | Full connection string; overrides everything below |
| `SM_DATOMIC_DATABASE`        | `sports-manager-dev`  | Logical db name (name containing `prod` ⇒ prod mode)|
| `SM_DATOMIC_USE_TRANSACTOR`  | —                     | `true` ⇒ connect via `dev://` transactor            |
| `SM_DATOMIC_TRANSACTOR_HOST` | `localhost`           | dev transactor host                                |
| `SM_DATOMIC_TRANSACTOR_PORT` | `4334`                | dev transactor port                                |
| `SM_DATOMIC_HOST`            | — (prod)              | PostgreSQL host                                    |
| `SM_DATOMIC_PORT`            | `5432`                | PostgreSQL port                                    |
| `SM_DATOMIC_DB`              | `datomic`             | PostgreSQL database name                           |
| `SM_DATOMIC_USER`            | — (prod)              | PostgreSQL user                                    |
| `SM_DATOMIC_PASSWORD`        | — (prod)              | PostgreSQL password                                |

## Code layout

- `sports_manager/config.clj` — env-var config.
- `sports_manager/schema.clj` — schema as attribute maps; `schema` concats each
  domain area. Idempotent install (Datomic schema is additive).
- `sports_manager/db.clj` — connection lifecycle (`start!`/`stop!`), thin
  `q`/`pull`/`transact!`/`entity` wrappers, and `tenant-scoped`.
- `test/sports_manager/test_db.clj` — `with-db` fixture: fresh mem:// db per test.

## Multi-tenancy (SPO-17)

Logical isolation via a per-entity tenant attribute: every tenant-scoped entity
carries `:tenant/id`, and all tenant-scoped queries go through `db/tenant-scoped`,
which splices in the `[?e :tenant/id ?tid]` clause so the filter can't be
forgotten. There is one shared database; this is the same query-scoping style as
stub-server, extended with an enforcement helper.
