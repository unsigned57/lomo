#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=quality/scripts/ai_gradle_env.sh
source "$script_dir/ai_gradle_env.sh"

lomo_ai_prepare_gradle_env "ai-local-maintenance-check"

report_dir="$repo_root/build/reports/ai/local-maintenance"
summary_file="$report_dir/summary.md"
version_updates_log="$report_dir/version-catalog-update-check.log"
r8_log="$report_dir/r8-minify-release.log"

mkdir -p "$report_dir"

run_gradle_capture() {
  local log_file="$1"
  shift

  set +e
  lomo_ai_run_gradle "$@" 2>&1 | tee "$log_file"
  local status=${PIPESTATUS[0]}
  set -e
  return "$status"
}

append_file_excerpt() {
  local file_path="$1"
  local max_lines="$2"

  if [ ! -f "$file_path" ]; then
    printf '_missing_: `%s`\n' "$file_path"
    return
  fi

  printf '`%s` (%s lines)\n\n' "$file_path" "$(wc -l < "$file_path" | tr -d ' ')"
  printf '```text\n'
  sed -n "1,${max_lines}p" "$file_path"
  printf '\n```\n'
}

append_report_section() {
  local title="$1"
  local file_path="$2"
  local max_lines="$3"

  {
    printf '## %s\n\n' "$title"
    append_file_excerpt "$file_path" "$max_lines"
    printf '\n'
  } >> "$summary_file"
}

echo "ai-local-maintenance-check: running advisory ./gradlew versionCatalogUpdate --check"
if run_gradle_capture "$version_updates_log" versionCatalogUpdate --check "$@"; then
  version_updates_status=0
else
  version_updates_status=$?
fi

echo "ai-local-maintenance-check: running enforced ./gradlew :app:minifyReleaseWithR8"
if run_gradle_capture "$r8_log" :app:minifyReleaseWithR8 "$@"; then
  r8_status=0
else
  r8_status=$?
fi

updates_file="$repo_root/gradle/libs.versions.updates.toml"
version_updates_artifact="$updates_file"
r8_usage_file="$repo_root/app/build/outputs/mapping/release/usage.txt"
r8_seeds_file="$repo_root/app/build/outputs/mapping/release/seeds.txt"
r8_mapping_file="$repo_root/app/build/outputs/mapping/release/mapping.txt"
r8_configuration_file="$repo_root/app/build/outputs/mapping/release/configuration.txt"
r8_missing_rules_file="$repo_root/app/build/outputs/mapping/release/missing_rules.txt"

if [ ! -f "$r8_usage_file" ] || [ ! -f "$r8_seeds_file" ]; then
  r8_status=1
fi

if [ ! -f "$version_updates_artifact" ]; then
  version_updates_artifact="$version_updates_log"
fi

cat > "$summary_file" <<EOF
# Local maintenance summary

| Check | Status | Notes |
| --- | --- | --- |
| Version catalog update check | $( [ "$version_updates_status" -eq 0 ] && printf 'clean' || printf 'updates-or-review-needed' ) | log: \`$version_updates_log\` |
| R8 release diagnostics | $( [ "$r8_status" -eq 0 ] && printf 'ready' || printf 'failed-or-incomplete' ) | log: \`$r8_log\` |

EOF

append_report_section "Version catalog updates" "$version_updates_artifact" 80
append_report_section "R8 usage report" "$r8_usage_file" 120
append_report_section "R8 seeds report" "$r8_seeds_file" 120
append_report_section "R8 mapping file" "$r8_mapping_file" 40
append_report_section "R8 configuration file" "$r8_configuration_file" 40
append_report_section "R8 missing rules" "$r8_missing_rules_file" 80

cat "$summary_file"

if [ "$r8_status" -ne 0 ]; then
  exit 1
fi
