#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
gradlew="$repo_root/gradlew"
ai_gradle_user_home="${GRADLE_USER_HOME:-$repo_root/.gradle/task-inspect}"
ai_home="${LOMO_AI_HOME:-$repo_root/.home}"
ai_android_user_home="${LOMO_AI_ANDROID_USER_HOME:-$repo_root/.android}"
ai_xdg_data_home="${LOMO_AI_XDG_DATA_HOME:-$repo_root/.local/share}"
ai_xdg_config_home="${LOMO_AI_XDG_CONFIG_HOME:-$repo_root/.config}"
ai_gradle_opts="${GRADLE_OPTS:-}"

if [[ "$ai_gradle_opts" != *"-Duser.home="* ]]; then
  ai_gradle_opts="${ai_gradle_opts:+$ai_gradle_opts }-Duser.home=$ai_home"
fi

if [ ! -x "$gradlew" ]; then
  echo "ai-static-quality-check: gradle wrapper not found or not executable: $gradlew" >&2
  exit 1
fi

mkdir -p "$ai_gradle_user_home" "$ai_home" "$ai_android_user_home" "$ai_xdg_data_home" "$ai_xdg_config_home"
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

echo "ai-static-quality-check: running ./gradlew staticQualityCheck"
echo "ai-static-quality-check: using GRADLE_USER_HOME at $ai_gradle_user_home"
echo "ai-static-quality-check: using HOME at $ai_home"

exec env HOME="$ai_home" \
  ANDROID_USER_HOME="$ai_android_user_home" \
  XDG_DATA_HOME="$ai_xdg_data_home" \
  XDG_CONFIG_HOME="$ai_xdg_config_home" \
  GRADLE_OPTS="$ai_gradle_opts" \
  GRADLE_USER_HOME="$ai_gradle_user_home" \
  "$gradlew" \
  --project-dir "$repo_root" \
  --no-daemon \
  --no-configuration-cache \
  --console=plain \
  staticQualityCheck \
  "$@"
