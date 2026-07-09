#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=quality/scripts/kotlin_toolchain_env.sh
source "$script_dir/kotlin_toolchain_env.sh"

if [ "${1:-}" = "--help" ]; then
  cat <<'EOF'
Usage: quality/scripts/ai_local_maintenance_check.sh [kotlin-toolchain-build-args...]

Runs the local maintenance gate:
- audits explicit module.yaml Maven coordinates against repository metadata
- builds the app release variant through Kotlin Toolchain
- regenerates the static baseline profile from the Toolchain build output
- writes build/reports/ai/local-maintenance/summary.md

Set LOMO_LOCAL_MAINTENANCE_BUILD_DIR to override the release build directory.
EOF
  exit 0
fi

lomo_kotlin_prepare_env "ai-local-maintenance-check"

report_dir="$repo_root/build/reports/ai/local-maintenance"
summary_file="$report_dir/summary.md"
dependency_log="$report_dir/module-dependency-update-check.log"
release_log="$report_dir/toolchain-app-release-build.log"
baseline_log="$report_dir/static-baseline-profile.log"
release_build_dir="${LOMO_LOCAL_MAINTENANCE_BUILD_DIR:-$repo_root/.kotlin/toolchain-build/local-maintenance-release}"

mkdir -p "$report_dir"

run_capture() {
  local log_file="$1"
  shift

  set +e
  "$@" 2>&1 | tee "$log_file"
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

metadata_latest_version() {
  local group="$1"
  local artifact="$2"
  local group_path="${group//.//}"
  local metadata_path="$group_path/$artifact/maven-metadata.xml"
  local metadata=""
  local repository

  for repository in \
    "https://dl.google.com/dl/android/maven2" \
    "https://repo1.maven.org/maven2" \
    "https://jitpack.io"
  do
    if metadata="$(curl -fsSL --connect-timeout 10 --max-time 20 "$repository/$metadata_path" 2>/dev/null)"; then
      printf '%s\n' "$metadata" |
        sed -n 's:.*<release>\(.*\)</release>.*:\1:p; s:.*<latest>\(.*\)</latest>.*:\1:p' |
        head -n 1
      return 0
    fi
  done

  return 1
}

list_declared_maven_coordinates() {
  find "$repo_root" \
    -path "$repo_root/.git" -prune -o \
    -path "$repo_root/.gradle" -prune -o \
    -path "$repo_root/.kotlin" -prune -o \
    -path "$repo_root/build" -prune -o \
    -name module.yaml -type f -print |
    sort |
    while IFS= read -r module_file; do
      awk -v module_file="$module_file" '
        /^[[:space:]]*-[[:space:]]*/ {
          line = $0
          sub(/[[:space:]]*#.*/, "", line)
          sub(/^[[:space:]]*-[[:space:]]*/, "", line)
          gsub(/^[[:space:]]+|[[:space:]]+$/, "", line)
          if (line ~ /^\/\//) next
          if (line ~ /^bom:[[:space:]]*/) sub(/^bom:[[:space:]]*/, "", line)
          split(line, parts, ":")
          if (length(parts[1]) == 0 || length(parts[2]) == 0 || length(parts[3]) == 0) next
          if (parts[3] ~ /[[:space:]]/) next
          if (parts[3] == "runtime-only" || parts[3] == "exported") next
          if (parts[1] !~ /^[A-Za-z0-9_.-]+$/ || parts[2] !~ /^[A-Za-z0-9_.-]+$/) next
          print module_file "|" parts[1] "|" parts[2] "|" parts[3]
        }
      ' "$module_file"
    done
}

audit_dependencies() {
  {
    printf '# Module dependency update check\n\n'
    printf '| Module file | Coordinate | Declared | Repository latest | Status |\n'
    printf '| --- | --- | --- | --- | --- |\n'
  } > "$dependency_log"

  if ! command -v curl >/dev/null 2>&1; then
    {
      printf '| all | curl | unavailable | unavailable | metadata-check-unavailable |\n'
    } >> "$dependency_log"
    return 0
  fi

  local module_file group artifact declared latest status
  while IFS='|' read -r module_file group artifact declared; do
    if latest="$(metadata_latest_version "$group" "$artifact")"; then
      if [ "$latest" = "$declared" ]; then
        status="current"
      else
        status="review"
      fi
    else
      latest="unresolved"
      status="metadata-unavailable"
    fi
    printf '| `%s` | `%s:%s` | `%s` | `%s` | %s |\n' \
      "${module_file#$repo_root/}" \
      "$group" \
      "$artifact" \
      "$declared" \
      "$latest" \
      "$status" >> "$dependency_log"
  done < <(list_declared_maven_coordinates)
}

echo "ai-local-maintenance-check: auditing explicit module.yaml dependency coordinates"
audit_dependencies

echo "ai-local-maintenance-check: running Kotlin Toolchain app release build"
if run_capture "$release_log" \
  lomo_kotlin_run build --module app --platform android --variant release --build-dir="$release_build_dir" "$@"; then
  release_status=0
else
  release_status=$?
fi

release_artifacts_file="$report_dir/release-artifacts.txt"
mapping_artifacts_file="$report_dir/release-mapping-artifacts.txt"
find "$release_build_dir" -type f \( -name '*.apk' -o -name '*.aab' \) 2>/dev/null | sort > "$release_artifacts_file" || true
find "$release_build_dir" -type f \( -name 'mapping.txt' -o -name 'usage.txt' -o -name 'seeds.txt' -o -name 'configuration.txt' -o -name 'missing_rules.txt' \) 2>/dev/null | sort > "$mapping_artifacts_file" || true

if [ "$release_status" -eq 0 ] && [ ! -s "$release_artifacts_file" ]; then
  release_status=1
fi

if [ "$release_status" -eq 0 ]; then
  echo "ai-local-maintenance-check: regenerating static baseline profile from Toolchain build"
  if run_capture "$baseline_log" "$script_dir/generate_static_baseline_profile.py" --build-dir "$release_build_dir"; then
    baseline_status=0
  else
    baseline_status=$?
  fi
else
  {
    printf 'Skipped because release build failed or produced no APK/AAB artifact.\n'
  } > "$baseline_log"
  baseline_status=1
fi

cat > "$summary_file" <<EOF
# Local maintenance summary

| Check | Status | Notes |
| --- | --- | --- |
| Module dependency metadata audit | advisory | log: \`$dependency_log\` |
| Kotlin Toolchain app release build | $( [ "$release_status" -eq 0 ] && printf 'ready' || printf 'failed-or-incomplete' ) | log: \`$release_log\` |
| Static baseline profile regeneration | $( [ "$baseline_status" -eq 0 ] && printf 'ready' || printf 'failed-or-incomplete' ) | log: \`$baseline_log\` |

EOF

append_report_section "Dependency metadata audit" "$dependency_log" 120
append_report_section "Release build output" "$release_log" 160
append_report_section "Release package artifacts" "$release_artifacts_file" 80
append_report_section "Release mapping artifacts" "$mapping_artifacts_file" 80
append_report_section "Static baseline profile regeneration" "$baseline_log" 80
append_report_section "Static baseline profile report" "$repo_root/build/reports/ai/static-baseline-profile/static-baseline-profile-report.txt" 120

cat "$summary_file"

if [ "$release_status" -ne 0 ] || [ "$baseline_status" -ne 0 ]; then
  exit 1
fi
