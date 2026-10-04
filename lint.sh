#!/usr/bin/env bash
# PairRename lint：项目相对路径，不依赖原作者电脑上的绝对路径。
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT" || exit 9

if "$ROOT/verify_all.sh"; then
  :
else
  status=$?
  echo "VERIFY_EXIT=$status" >&2
  exit "$status"
fi

if [[ -x "$ROOT/gradlew" ]]; then
  GRADLE=("$ROOT/gradlew")
elif [[ -n "${GRADLE_BIN:-}" ]]; then
  GRADLE=("$GRADLE_BIN")
elif command -v gradle >/dev/null 2>&1; then
  GRADLE=("$(command -v gradle)")
else
  echo "Gradle unavailable: add the Gradle Wrapper (gradlew + gradle-wrapper.jar) or set GRADLE_BIN." >&2
  exit 127
fi

"${GRADLE[@]}" -p "$ROOT" :app:lintRelease \
  --no-daemon --console=plain -Dorg.gradle.jvmargs=-Xmx2g \
  > "$ROOT/lint.log" 2>&1
status=$?
echo "LINT_EXIT=$status"
tail -20 "$ROOT/lint.log"
exit "$status"
