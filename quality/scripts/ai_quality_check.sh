#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
gradlew="$repo_root/gradlew"
ai_gradle_user_home="${GRADLE_USER_HOME:-$repo_root/.gradle/task-inspect}"

if [ ! -x "$gradlew" ]; then
  echo "ai-quality-check: gradle wrapper not found or not executable: $gradlew" >&2
  exit 1
fi

mkdir -p "$ai_gradle_user_home"
wrapper_dists_dir="$ai_gradle_user_home/wrapper/dists"

clear_stale_wrapper_locks() {
  local lock_file

  while IFS= read -r -d '' lock_file; do
    local ok_file="${lock_file%.lck}.ok"
    if [ -f "$ok_file" ]; then
      rm -f "$lock_file"
    fi
  done < <(find "$wrapper_dists_dir" -type f -name '*.zip.lck' -print0 2>/dev/null)
}

clear_stale_wrapper_locks

echo "ai-quality-check: running ./gradlew qualityCheck"
echo "ai-quality-check: using GRADLE_USER_HOME at $ai_gradle_user_home"

exec env GRADLE_USER_HOME="$ai_gradle_user_home" "$gradlew" \
  --project-dir "$repo_root" \
  --no-daemon \
  --no-configuration-cache \
  --console=plain \
  qualityCheck \
  "$@"
