#!/usr/bin/env bash
# PairRename release 构建入口：相对项目目录定位 Gradle，签名缺失时由 Gradle 门禁失败。
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT" || exit 9

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

"${GRADLE[@]}" -p "$ROOT" :app:assembleRelease \
  --no-daemon --console=plain --stacktrace \
  > "$ROOT/build2.log" 2>&1
status=$?
echo "BUILD_EXIT=$status"
tail -30 "$ROOT/build2.log"
exit "$status"
