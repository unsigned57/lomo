#!/usr/bin/env bash
# Compose-focused static analysis:
# - Hard gate: Slack compose-lint-checks are loaded by kotlin_android_lint_check.sh
# - Soft/required-optional: Compose compiler metrics presence under build outputs
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=quality/scripts/kotlin_toolchain_env.sh
source "$script_dir/kotlin_toolchain_env.sh"

lomo_kotlin_prepare_env "kotlin-compose-static-analysis"
repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

report_root="$repo_root/build/reports/compose-compiler"
mkdir -p "$report_root"

# Ensure Compose modules compile (cheap when already built by prior gate stages).
echo "kotlin-compose-static-analysis: ensuring Compose modules compile"
lomo_kotlin_run build --module app --platform android --variant debug
lomo_kotlin_run build --module ui-components --platform android --variant debug

mapfile -t compose_reports < <(
  find "$repo_root/build" "$repo_root/.kotlin" \
    \( -name 'compose-compiler*' -o -path '*/compose-compiler/*' -o -name '*compose*metrics*' \) \
    2>/dev/null | head -50 || true
)

if [ "${#compose_reports[@]}" -eq 0 ]; then
  cat >"$report_root/missing-metrics.txt" <<EOF
Compose compiler metrics were not emitted by the current Kotlin Toolchain Android bridge.
Hard Compose static checks run via Android Lint + compose-lint-checks in kotlin_android_lint_check.sh.
EOF
  echo "kotlin-compose-static-analysis: no Compose compiler metrics (documented; not a hard failure by default)"
  if [ "${LOMO_REQUIRE_COMPOSE_COMPILER_REPORTS:-false}" = "true" ]; then
    exit 1
  fi
else
  printf '%s\n' "${compose_reports[@]}" >"$report_root/found-paths.txt"
  echo "kotlin-compose-static-analysis: found ${#compose_reports[@]} compose report path(s)"
fi

# Prove compose lint checks jar is available for the lint stage (parity with composeStaticAnalysisCheck deps).
compose_lint_jar="$(
  find "$repo_root/.gradle" "$repo_root/.cache" -path '*compose-lint-checks*.jar' 2>/dev/null | head -1 || true
)"
if [ -z "$compose_lint_jar" ]; then
  echo "kotlin-compose-static-analysis: missing compose-lint-checks jar under .gradle/.cache" >&2
  echo "kotlin-compose-static-analysis: downloading compose-lint-checks 1.4.3" >&2
  mkdir -p "$repo_root/.cache/lint-checks"
  curl -fsSL -o "$repo_root/.cache/lint-checks/compose-lint-checks-1.4.3.jar" \
    "https://repo1.maven.org/maven2/com/slack/lint/compose/compose-lint-checks/1.4.3/compose-lint-checks-1.4.3.jar"
  compose_lint_jar="$repo_root/.cache/lint-checks/compose-lint-checks-1.4.3.jar"
fi
echo "kotlin-compose-static-analysis: compose-lint-checks ready: $compose_lint_jar"
echo "kotlin-compose-static-analysis: ok"
