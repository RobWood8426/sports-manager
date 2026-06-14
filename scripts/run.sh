#!/usr/bin/env bash
# Run the web server for real. -main also boots an embedded nREPL on port 7888,
# so clojure-mcp (scripts/mcp.sh) can attach to a running server.
#   PORT       web server port   (default 3000)
#   NREPL_PORT embedded nREPL     (default 7888)
set -euo pipefail
cd "$(dirname "$0")/.."
exec clojure -M:run