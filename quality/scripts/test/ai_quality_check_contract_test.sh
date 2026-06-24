#!/usr/bin/env bash
set -euo pipefail

# Behavior Contract:
# - Unit under test: AI quality workflow scripts and vulnerability-scan routing.
# - Owning layer: quality
# - Priority tier: P0
# - Capability: keep high-frequency quality verification deterministic while preserving an explicit CVE scan.
#
# Scenarios:
# - Given a developer runs ai_quality_check after a fix, when no maintenance opt-in is set, then the
#   script must run qualityCheck without invoking the local maintenance pass or OWASP download path.
# - Given a maintainer wants advisory maintenance output, when LOMO_RUN_LOCAL_MAINTENANCE=true is set,
#   then ai_quality_check may delegate to ai_local_maintenance_check.
# - Given dependencyVulnerabilityCheck is still supported, when it runs, then Dependency-Check uses
#   repo/CI-cacheable Gradle state and supports NVD_API_KEY.
# - Given CI needs CVE scanning, when workflow routing is checked, then vulnerability scanning has a
#   dedicated scheduled/manual workflow instead of the PR quality gate.
#
# Observable outcomes:
# - Missing opt-in routing, stale skip flags, missing cache/key configuration, or missing dedicated
#   workflow fail this script.
#
# TDD proof:
# - Fails before ai_quality_check is changed from default-maintenance to opt-in maintenance because
#   LOMO_RUN_LOCAL_MAINTENANCE and the default skip message are absent.
#
# Excludes:
# - Gradle task execution, NVD network behavior, and Dependency-Check vulnerability findings.

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

ai_quality_script="quality/scripts/ai_quality_check.sh"
local_maintenance_script="quality/scripts/ai_local_maintenance_check.sh"
root_build="build.gradle.kts"
quality_readme="quality/README.md"
vulnerability_workflow=".github/workflows/dependency_vulnerability.yml"

fail() {
  echo "ai-quality-check-contract: $1" >&2
  exit 1
}

require_file() {
  local file="$1"
  [ -f "$file" ] || fail "missing file: $file"
}

require_text() {
  local file="$1"
  local expected="$2"
  grep -Fq -- "$expected" "$file" ||
    fail "source file $file is missing required text: $expected"
}

reject_text() {
  local file="$1"
  local rejected="$2"
  if grep -Fq -- "$rejected" "$file"; then
    fail "source file $file still contains rejected text: $rejected"
  fi
}

require_file "$ai_quality_script"
require_file "$local_maintenance_script"
require_file "$vulnerability_workflow"

bash -n "$ai_quality_script"
bash -n "$local_maintenance_script"

require_text "$ai_quality_script" "lomo_ai_run_gradle qualityCheck"
require_text "$ai_quality_script" "LOMO_RUN_LOCAL_MAINTENANCE"
require_text "$ai_quality_script" "skipping local maintenance by default"
require_text "$ai_quality_script" 'bash "$script_dir/ai_local_maintenance_check.sh"'
reject_text "$ai_quality_script" "LOMO_SKIP_LOCAL_MAINTENANCE"

require_text "$local_maintenance_script" "dependencyVulnerabilityCheck"
require_text "$local_maintenance_script" "LOMO_DEPENDENCY_VULNERABILITY_TIMEOUT_SECONDS"

require_text "$root_build" "dependency-check-data"
require_text "$root_build" "NVD_API_KEY"
require_text "$root_build" "validForHours = 24"

require_text "$quality_readme" "LOMO_RUN_LOCAL_MAINTENANCE=true"
require_text "$quality_readme" "Dependency-Check data"
reject_text "$quality_readme" "LOMO_SKIP_LOCAL_MAINTENANCE"

require_text "$vulnerability_workflow" "dependencyVulnerabilityCheck"
require_text "$vulnerability_workflow" "NVD_API_KEY"
require_text "$vulnerability_workflow" "schedule:"
require_text "$vulnerability_workflow" "workflow_dispatch:"

echo "ai-quality-check-contract: ok"
