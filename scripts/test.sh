#!/usr/bin/env bash
# Run the test suite (kaocha).
set -euo pipefail
cd "$(dirname "$0")/.."
exec clojure -M:test "$@"