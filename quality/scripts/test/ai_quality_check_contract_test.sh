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
# - Given AI quality scripts run Gradle locally, when the shared Gradle environment is inspected, then
#   the default runner uses a single repo-local Android preferences home and permits configuration cache reuse.
# - Given Gradle wires meaningfulTestCheck, when source is inspected, then the task executes the
#   meaningful-test script instead of bypassing it.
#
# Observable outcomes:
# - Missing opt-in routing, stale skip flags, missing writable homes, duplicate Android preferences
#   injection, disabled default configuration cache, or bypassed meaningful-test wiring fail this script.
#
# TDD proof:
# - Fails before ai_quality_check is changed from default-maintenance to opt-in maintenance because
#   LOMO_RUN_LOCAL_MAINTENANCE and the default skip message are absent.
# - Fails before local script speedups are implemented because the default Gradle runner still passes
#   --no-configuration-cache and meaningfulTestCheck echoes a bypass message.
#
# Excludes:
# - Gradle task execution, NVD network behavior, and Dependency-Check vulnerability findings.

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

ai_quality_script="quality/scripts/ai_quality_check.sh"
ai_gradle_env_script="quality/scripts/ai_gradle_env.sh"
local_maintenance_script="quality/scripts/ai_local_maintenance_check.sh"
gradle_properties="gradle.properties"
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

function_body() {
  local file="$1"
  local function_name="$2"

  awk -v function_name="$function_name" '
    $0 == function_name "() {" {
      in_body = 1
    }

    in_body {
      print
      if ($0 == "}") {
        exit
      }
    }
  ' "$file"
}

require_function_text() {
  local file="$1"
  local function_name="$2"
  local expected="$3"

  function_body "$file" "$function_name" | grep -Fq -- "$expected" ||
    fail "function $function_name in $file is missing required text: $expected"
}

reject_function_text() {
  local file="$1"
  local function_name="$2"
  local rejected="$3"

  if function_body "$file" "$function_name" | grep -Fq -- "$rejected"; then
    fail "function $function_name in $file still contains rejected text: $rejected"
  fi
}

require_file "$ai_quality_script"
require_file "$ai_gradle_env_script"
require_file "$local_maintenance_script"

# Enforce that the scheduled dependency check workflow has been removed
[ ! -f "$vulnerability_workflow" ] || fail "workflow file $vulnerability_workflow still exists"

bash -n "$ai_quality_script"
bash -n "$ai_gradle_env_script"
bash -n "$local_maintenance_script"

require_text "$ai_quality_script" "lomo_ai_run_gradle qualityCheck"
require_text "$ai_quality_script" "LOMO_RUN_LOCAL_MAINTENANCE"
require_text "$ai_quality_script" "skipping local maintenance by default"
require_text "$ai_quality_script" 'bash "$script_dir/ai_local_maintenance_check.sh"'
reject_text "$ai_quality_script" "LOMO_SKIP_LOCAL_MAINTENANCE"

require_text "$ai_gradle_env_script" "ai_android_user_home="
require_function_text "$ai_gradle_env_script" "lomo_ai_run_gradle" 'ANDROID_USER_HOME="$ai_android_user_home"'
require_function_text "$ai_gradle_env_script" "lomo_ai_run_gradle" '-Duser.home=$ai_home'
reject_text "$ai_gradle_env_script" "ANDROID_PREFS_ROOT"
reject_function_text "$ai_gradle_env_script" "lomo_ai_run_gradle" "--no-configuration-cache"
require_function_text "$ai_gradle_env_script" "lomo_ai_run_gradle_no_configuration_cache" '-Duser.home=$ai_home'
require_function_text "$ai_gradle_env_script" "lomo_ai_run_gradle_no_configuration_cache" "--no-configuration-cache"

# Enforce rejection of dependency check and analysis in local maintenance script
reject_text "$local_maintenance_script" "dependencyVulnerabilityCheck"
reject_text "$local_maintenance_script" "dependencyAnalysisCheck"

# Enforce rejection of dependency check and analysis in root build.gradle.kts
reject_text "$root_build" "dependency-check-data"
reject_text "$root_build" "dependencyAnalysis"
reject_text "$root_build" "NVD_API_KEY"
reject_text "$root_build" "Bypassed meaningful test check"
require_text "$root_build" "meaningfulTestCheckScript.absolutePath"
require_text "$root_build" "lomo.test.maxParallelForks"

require_text "$gradle_properties" "systemProp.jdk.tls.client.protocols=TLSv1.2"
require_text "$gradle_properties" "systemProp.https.protocols=TLSv1.2"

require_text "$quality_readme" "LOMO_RUN_LOCAL_MAINTENANCE=true"
reject_text "$quality_readme" "LOMO_SKIP_LOCAL_MAINTENANCE"

echo "ai-quality-check-contract: ok"
