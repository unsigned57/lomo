#!/usr/bin/env bash
# Format (all|staged|files) or verify (check) Kotlin sources with the detekt ktlint wrapper.
# Every run first proves the ktlint rule set is active: an unformatted canary must be reported.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=quality/scripts/kotlin_detekt_env.sh
source "$script_dir/kotlin_detekt_env.sh"

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"
config="quality/detekt/config/formatting.yml"
mode="${1:-all}"
shift || true

inputs=()
case "$mode" in
  all|check)
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
    echo "Usage: quality/scripts/kotlin_detekt_format.sh [all|check|staged|files <paths...>]" >&2
    exit 1
    ;;
esac

echo "kotlin-detekt-format: formatting ${#inputs[@]} path(s) ($mode)"

wrapper_jar="$(lomo_detekt_ktlint_jar)"
format_plugins=("$wrapper_jar")

export LOMO_DETEKT_INCLUDE_CUSTOM_RULES=0
# The ktlint wrapper/repackage jars are this script's own resolved plugins — passed
# as explicit detekt CLI arguments, not through an ambient environment channel.
#
# Resolve and verify the pinned engine AND every plugin jar once up front: the
# per-batch `|| true` below exists for detekt's residual-finding exit codes only
# — engine/plugin integrity is not a finding. Without this, a jar that fails
# verification inside `lomo_detekt_run` would make every tolerated batch a
# silent no-op instead of aborting the format run.
# (`lomo_detekt_extra_plugin_jars` is verified too because `lomo_detekt_run`
# still loads that channel per batch even with custom rules disabled; the
# rules jar is NOT on this script's load path and stays unverified here.)
lomo_detekt_cli_jar >/dev/null
lomo_detekt_extra_plugin_jars >/dev/null

format_plugin_args=()
for plugin in "${format_plugins[@]}"; do
  format_plugin_args+=(--plugins "$plugin")
done

# The ktlint rules ship in the wrapper's own default config; without building upon it no rule
# is active and every run is a silent no-op.
detekt_format_args=(--config "$config" --build-upon-default-config --disable-default-rulesets)

# Detekt exit status 2 means findings were reported and 0 means none. Anything else is an
# engine/config failure, never a formatting outcome.
canary_dir="$(mktemp -d)"
trap 'rm -rf -- "$canary_dir"' EXIT
printf 'package canary\n\nfun  unformatted( ) =1\n' > "$canary_dir/Canary.kt"
canary_status=0
lomo_detekt_run --input "$canary_dir/Canary.kt" "${detekt_format_args[@]}" "${format_plugin_args[@]}" \
  --report "checkstyle:$canary_dir/canary.xml" > "$canary_dir/canary.log" 2>&1 || canary_status=$?
if [ "$canary_status" -ne 2 ] || ! grep -q 'source="detekt.FunKeywordSpacing"' "$canary_dir/canary.xml"; then
  cat "$canary_dir/canary.log" >&2
  echo "kotlin-detekt-format: unformatted canary was not reported (exit $canary_status); ktlint rules are inactive" >&2
  exit 1
fi

correct_args=(--auto-correct)
if [ "$mode" = check ]; then
  correct_args=()
fi

# Detekt CLI separates multiple --input paths with ':' on Unix. Batch to avoid ARG_MAX.
batch_size=80
total=${#inputs[@]}
start=0
failed=0
while [ "$start" -lt "$total" ]; do
  end=$((start + batch_size))
  if [ "$end" -gt "$total" ]; then
    end=$total
  fi
  batch=("${inputs[@]:$start:$((end - start))}")
  input_joined="$(IFS=:; echo "${batch[*]}")"
  echo "kotlin-detekt-format: batch $((start + 1))-${end}/${total}"
  status=0
  lomo_detekt_run \
    --input "$input_joined" \
    "${detekt_format_args[@]}" \
    "${format_plugin_args[@]}" \
    "${correct_args[@]}" \
    || status=$?
  case "$status" in
    0) ;;
    2) failed=1 ;;
    *) echo "kotlin-detekt-format: detekt failed with exit $status" >&2; exit "$status" ;;
  esac
  start=$end
done

if [ "$failed" -ne 0 ]; then
  echo "kotlin-detekt-format: formatting findings remain ($mode); fix the reported lines" >&2
  exit 1
fi
echo "kotlin-detekt-format: done ($mode)"
