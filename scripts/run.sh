#!/usr/bin/env bash
# Run the web server for real. The embedded nREPL is opt-in: set
# ENABLE_NREPL=true to boot it (e.g. so clojure-mcp via scripts/mcp.sh can
# attach to this running server). It stays off by default — including in prod.
#   PORT         web server port   (default 8080)
#   ENABLE_NREPL boot embedded nREPL when "true" (default off)
#   NREPL_PORT   embedded nREPL port when enabled (default 7888)
set -euo pipefail
cd "$(dirname "$0")/.."
exec clojure -M:run