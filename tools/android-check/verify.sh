#!/usr/bin/env bash
# Fast checks that need no Android SDK: manifest/resource references and dependency coverage.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
fail=0
echo "── manifest & resource references"
python3 "$ROOT/tools/android-check/check_refs.py" "$ROOT" || fail=1
echo "── dependencies vs imports"
python3 "$ROOT/tools/android-check/check_deps.py" "$ROOT" || fail=1
echo "── engine logic self-test (needs JAVA_HOME + KOTLIN_HOME)"
if [ -n "${KOTLIN_HOME:-}" ] && [ -n "${JAVA_HOME:-}" ]; then
  bash "$ROOT/tools/jvmtest/run_selftest.sh" || fail=1
else
  echo "   skipped (set JAVA_HOME and KOTLIN_HOME to run it)"
fi
exit $fail
