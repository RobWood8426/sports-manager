#!/usr/bin/env bash
# Lint src and test with clj-kondo.
set -euo pipefail
cd "$(dirname "$0")/.."
if command -v clj-kondo >/dev/null 2>&1; then
  exec clj-kondo --lint src test
else
  exec clojure -M:lint
fi