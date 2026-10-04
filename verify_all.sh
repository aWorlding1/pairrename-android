#!/usr/bin/env bash
# Source-only verification suite; does not require Android SDK or Gradle.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
python3 static_check.py
python3 brace_check.py
python3 audit_actions.py
python3 scripts/check_docs.py
for test in verify_*.py; do
  echo "=== $test ==="
  python3 "$test"
done
