#!/usr/bin/env bash
# Start the clojure-mcp server. It connects to the nREPL on port 7888, so run
# scripts/dev.sh (or scripts/run.sh, which also boots an embedded nREPL) first.
set -euo pipefail
cd "$(dirname "$0")/.."
exec clojure -X:mcp