#!/usr/bin/env bash
# Start a dev nREPL on port 7888 with cider middleware.
# clojure-mcp (scripts/mcp.sh) attaches to this. From the REPL, call (go) to
# start the web server, (restart) to bounce it, (stop) to halt it.
set -euo pipefail
cd "$(dirname "$0")/.."
echo "Starting nREPL on :7888 (clojure-mcp can attach). Call (go) to start the web server."
exec clojure -M:dev