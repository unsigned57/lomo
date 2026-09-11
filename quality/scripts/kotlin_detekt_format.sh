#!/usr/bin/env bash
# Format Kotlin sources with detekt ktlint wrappers (parity with detektFormat / detektFormatStaged).
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=quality/scripts/kotlin_detekt_env.sh
source "$script_dir/kotlin_detekt_env.sh"

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"
build_dir="${LOMO_KOTLIN_BUILD_DIR:-$repo_root/.kotlin/toolchain-build/shared}"

if [ ! -f "$build_dir/tasks/_detekt-rules_jarJvm/detekt-rules-jvm.jar" ]; then
  "${LOMO_KOTLIN_WRAPPER:?xtask must provide LOMO_KOTLIN_WRAPPER}" --log-level=warn \
    build --module detekt-rules --build-dir "$build_dir"
fi

config="quality/detekt/config/formatting.yml"
mode="${1:-all}"
shift || true

inputs=()
case "$mode" in
  all)
    for module in app domain data ui-components quality/detekt-rules; do
      mod_dir="apps/android/$module"
      if [ ! -d "$mod_dir" ]; then
        echo "kotlin-detekt-format: missing expected module directory: $mod_dir" >&2
        exit 1
      fi
      [ -d "$mod_dir/src" ] && inputs+=("$mod_dir/src")
      [ -d "$mod_dir/test" ] && inputs+=("$mod_dir/test")
      [ -d "$mod_dir/test@android" ] && inputs+=("$mod_dir/test@android")
    done
    ;;
  staged)
    while IFS= read -r file; do
      [ -n "$file" ] || continue
      [ -f "$file" ] || continue
      inputs+=("$file")
    done < <(git -C "$repo_root" diff --cached --name-only --diff-filter=ACMR -- '*.kt' '*.kts')
    if [ "${#inputs[@]}" -eq 0 ]; then
      echo "kotlin-detekt-format: no staged Kotlin files"
      exit 0
    fi
    ;;
  files)
    inputs=("$@")
    if [ "${#inputs[@]}" -eq 0 ]; then
      echo "kotlin-detekt-format: no files provided" >&2
      exit 1
    fi
    ;;
  *)
    echo "Usage: quality/scripts/kotlin_detekt_format.sh [all|staged|files <paths...>]" >&2
    exit 1
    ;;
esac

echo "kotlin-detekt-format: formatting ${#inputs[@]} path(s) ($mode)"

# xtask policy scripts may set HOME to a repo-local Kotlin home. Search real Gradle
# caches first (GRADLE_USER_HOME, host home, repo-local Toolchain caches).
gradle_search_roots=()
if [ -n "${GRADLE_USER_HOME:-}" ]; then
  gradle_search_roots+=("$GRADLE_USER_HOME")
fi
if [ -n "${HOME:-}" ]; then
  gradle_search_roots+=("$HOME/.gradle")
fi
# Host user Gradle cache (not rewritten when LOMO sets HOME to .home).
if [ -n "${USER:-}" ] && [ -d "/home/${USER}/.gradle" ]; then
  gradle_search_roots+=("/home/${USER}/.gradle")
fi
gradle_search_roots+=("$repo_root/.gradle" "$repo_root/.gradle/kotlin-toolchain")

find_detekt_jar() {
  local path_glob="$1"
  local name_glob="$2"
  local root
  for root in "${gradle_search_roots[@]}"; do
    [ -d "$root" ] || continue
    find "$root" -path "$path_glob" -name "$name_glob" 2>/dev/null | head -1
  done | head -1
}

wrapper_jar="$(
  find_detekt_jar \
    "*/dev.detekt/detekt-rules-ktlint-wrapper/${DETEKT_VERSION}/*" \
    "detekt-rules-ktlint-wrapper-${DETEKT_VERSION}.jar"
)"
if [ -z "$wrapper_jar" ]; then
  echo "kotlin-detekt-format: ktlint wrapper is not cached under Gradle roots; run a Toolchain build once" >&2
  exit 1
fi

wrapper_bundles_ktlint() {
  jar tf "$1" 2>/dev/null |
    awk '$0 == "com/pinterest/ktlint/rule/engine/core/api/Rule.class" { found = 1 } END { exit !found }'
}

format_plugins=("$wrapper_jar")
if ! wrapper_bundles_ktlint "$wrapper_jar"; then
  ktlint_jar="$(
    find_detekt_jar \
      "*/dev.detekt/ktlint-repackage/${DETEKT_VERSION}/*" \
      "ktlint-repackage-${DETEKT_VERSION}-all.jar"
  )"
  if [ -z "$ktlint_jar" ]; then
    echo "kotlin-detekt-format: ktlint wrapper has no bundled engine and no matching ktlint-repackage is cached" >&2
    exit 1
  fi
  format_plugins+=("$ktlint_jar")
fi

export LOMO_DETEKT_INCLUDE_CUSTOM_RULES=0
LOMO_DETEKT_EXTRA_PLUGINS="$(IFS=:; printf '%s' "${format_plugins[*]}")"
export LOMO_DETEKT_EXTRA_PLUGINS

# Detekt CLI separates multiple --input paths with ':' on Unix. Batch to avoid ARG_MAX.
batch_size=80
total=${#inputs[@]}
start=0
while [ "$start" -lt "$total" ]; do
  end=$((start + batch_size))
  if [ "$end" -gt "$total" ]; then
    end=$total
  fi
  batch=("${inputs[@]:$start:$((end - start))}")
  input_joined="$(IFS=:; echo "${batch[*]}")"
  echo "kotlin-detekt-format: batch $((start + 1))-${end}/${total}"
  # Auto-correct may still exit non-zero on residual findings; formatting success is the goal.
  lomo_detekt_run \
    --input "$input_joined" \
    --config "$config" \
    --auto-correct \
    --disable-default-rulesets \
    || true
  start=$end
done

echo "kotlin-detekt-format: done"
