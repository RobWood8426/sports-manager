# sports-manager

A small Clojure web server rendering HTMX-driven pages. Built on `deps.edn`
(no Leiningen). If HTMX runs out of road we can layer in Rum for a richer SPA.

## Stack

- **Ring + Jetty** — HTTP server
- **Reitit** — routing
- **Hiccup** — server-rendered HTML
- **HTMX** — interactivity via HTML-over-the-wire (loaded from CDN in the page head)
- **kaocha** — tests
- **clojure-mcp** — REPL-driven AI tooling, attaches over nREPL (port 7888)

## Layout

```
src/sports_manager/
  core.clj     entry point: boots Jetty + embedded nREPL
  routes.clj   reitit route table + ring handler
  views.clj    pure HTML (pages + HTMX fragments)
dev/user.clj   REPL helpers: (go) (stop) (restart)
resources/public/  static assets (css/, js/)
scripts/       dev / run / mcp / test / lint
```

## Running

| Script              | What it does                                                    |
|---------------------|-----------------------------------------------------------------|
| `scripts/run.sh`    | Run the web server (also opens nREPL :7888 for clojure-mcp)      |
| `scripts/dev.sh`    | Dev nREPL :7888 only; call `(go)` in the REPL to start the web server |
| `scripts/mcp.sh`    | Start clojure-mcp (attaches to the :7888 nREPL)                 |
| `scripts/test.sh`   | Run tests                                                       |
| `scripts/lint.sh`   | clj-kondo over src + test                                       |

Web server defaults to `http://localhost:3000` (override with `PORT`).

### Connecting clojure-mcp

1. `scripts/run.sh` (or `scripts/dev.sh`) — starts an nREPL on **7888**.
2. In another terminal, `scripts/mcp.sh` — clojure-mcp connects to 7888.

The `:mcp` alias pins clojure-mcp `v0.3.1`, matching the global setup.