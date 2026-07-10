#!/usr/bin/env bash
# Format Kotlin sources with detekt ktlint wrappers (parity with detektFormat / detektFormatStaged).
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=quality/scripts/kotlin_toolchain_env.sh
source "$script_dir/kotlin_toolchain_env.sh"
# shellcheck source=quality/scripts/kotlin_detekt_env.sh
source "$script_dir/kotlin_detekt_env.sh"

lomo_kotlin_prepare_env "kotlin-detekt-format"
repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"
build_dir="${LOMO_KOTLIN_BUILD_DIR:-$repo_root/.kotlin/toolchain-build/format-gate}"

if [ ! -f "$build_dir/tasks/_detekt-rules_jarJvm/detekt-rules-jvm.jar" ]; then
  lomo_kotlin_run build --module detekt-rules --build-dir "$build_dir"
fi

config="quality/detekt/config/formatting.yml"
mode="${1:-all}"
shift || true

inputs=()
case "$mode" in
  all)
    for module in app domain data ui-components quality/detekt-rules; do
      [ -d "$module/src" ] && inputs+=("$module/src")
      [ -d "$module/test" ] && inputs+=("$module/test")
      [ -d "$module/test@android" ] && inputs+=("$module/test@android")
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

wrapper_jar="$(
  find "$repo_root/.gradle" \
    -path "*/dev.detekt/detekt-rules-ktlint-wrapper/${DETEKT_VERSION}/*" \
    -name "detekt-rules-ktlint-wrapper-${DETEKT_VERSION}.jar" \
    2>/dev/null | head -1 || true
)"
ktlint_jar="$(
  find "$repo_root/.gradle" \
    -path "*/dev.detekt/ktlint-repackage/${DETEKT_VERSION}/*" \
    -name "ktlint-repackage-${DETEKT_VERSION}-all.jar" \
    2>/dev/null | head -1 || true
)"
if [ -z "$wrapper_jar" ] || [ -z "$ktlint_jar" ]; then
  echo "kotlin-detekt-format: ktlint plugin jars not cached under .gradle; run a Toolchain build once or download detekt ktlint artifacts" >&2
  exit 1
fi

export LOMO_DETEKT_INCLUDE_CUSTOM_RULES=0
export LOMO_DETEKT_EXTRA_PLUGINS="${wrapper_jar}:${ktlint_jar}"

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
