#!/usr/bin/env bash
set -euo pipefail

# Behavior Contract:
# - Unit under test: Kotlin Toolchain quality workflow scripts.
# - Owning layer: quality
# - Priority tier: P0
# - Capability: keep high-frequency quality verification deterministic with a single Toolchain CLI family.
#
# Scenarios:
# - Given a developer runs kotlin_quality_check, when the script body is inspected,
#   then it must orchestrate model/build/detekt/lint/compose/tests/coverage without Gradle entrypoints.
# - Given kotlin_static_quality_check is used for iteration, when the script body is inspected,
#   then it must run model/build/detekt/test-style/lint/shell contracts/tests without coverage requirement.
# - Given Kotlin Toolchain invokes Android compilation, when the shared environment is inspected,
#   then repo-local home, cache, SDK, Android user home, bootstrap cache, and Gradle bridge home are set.
# - Given a quality gate invokes sub-checks, when build directories are resolved,
#   then all Toolchain sub-checks share the gate build directory unless explicitly overridden.
# - Given local generated state grows over time, when cleanup is requested,
#   then dry-run is non-mutating and apply is limited to documented generated-state roots.
# - Given a human developer needs a memorable command surface, when the Justfile is inspected,
#   then it is only a thin menu over quality/scripts and the Kotlin Toolchain wrapper.
# - Given zero-tail policy, when quality/scripts is listed, then ai_quality/ai_static/ai_fast/ai_compose shims are absent.
#
# Observable outcomes:
# - Missing Toolchain orchestration, stale Gradle runner calls, non-canonical build directories,
#   unsafe cleanup scope, duplicated Justfile build logic, residual ai_* entrypoints, or missing
#   writable homes fail this script.
#
# TDD proof:
# - Fails before the Justfile fix because no human command menu exists.
#
# Excludes:
# - Toolchain command execution and external dependency resolution.

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

kotlin_quality_script="quality/scripts/kotlin_quality_check.sh"
kotlin_static_script="quality/scripts/kotlin_static_quality_check.sh"
kotlin_env_script="quality/scripts/kotlin_toolchain_env.sh"
kotlin_test_args_script="quality/scripts/kotlin_toolchain_test_args.sh"
kotlin_detekt_script="quality/scripts/kotlin_detekt_check.sh"
kotlin_test_style_script="quality/scripts/kotlin_test_style_check.sh"
kotlin_format_script="quality/scripts/kotlin_detekt_format.sh"
kotlin_lint_script="quality/scripts/kotlin_android_lint_check.sh"
kotlin_compose_script="quality/scripts/kotlin_compose_static_analysis.sh"
kotlin_coverage_script="quality/scripts/kotlin_coverage_check.sh"
cache_audit_script="quality/scripts/kotlin_cache_audit.sh"
cache_cleanup_script="quality/scripts/kotlin_cache_cleanup.sh"
local_maintenance_script="quality/scripts/ai_local_maintenance_check.sh"
justfile="Justfile"
root_build="project.yaml"
quality_readme="quality/README.md"
quality_workflow=".github/workflows/architecture_checks.yml"
release_workflow=".github/workflows/android_release.yml"
pre_commit=".githooks/pre-commit"

fail() {
  echo "kotlin-quality-check-contract: $1" >&2
  exit 1
}

require_file() {
  local file="$1"
  [ -f "$file" ] || fail "missing file: $file"
}

reject_file() {
  local file="$1"
  [ ! -e "$file" ] || fail "file should not exist under zero-tail policy: $file"
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

require_file "$kotlin_quality_script"
require_file "$kotlin_static_script"
require_file "$kotlin_env_script"
require_file "$kotlin_test_args_script"
require_file "$kotlin_detekt_script"
require_file "$kotlin_test_style_script"
require_file "$kotlin_format_script"
require_file "$kotlin_lint_script"
require_file "$kotlin_compose_script"
require_file "$kotlin_coverage_script"
require_file "$cache_audit_script"
require_file "$cache_cleanup_script"
require_file "$local_maintenance_script"
require_file "$justfile"
require_file "$pre_commit"
require_file "$quality_workflow"
require_file "$release_workflow"

reject_file "quality/scripts/ai_quality_check.sh"
reject_file "quality/scripts/ai_static_quality_check.sh"
reject_file "quality/scripts/ai_fast_quality_check.sh"
reject_file "quality/scripts/ai_compose_static_analysis.sh"
reject_file "quality/scripts/ai_gradle_env.sh"
reject_file "gradlew"
reject_file "settings.gradle.kts"

bash -n "$kotlin_quality_script"
bash -n "$kotlin_static_script"
bash -n "$kotlin_env_script"
bash -n "$kotlin_test_args_script"
bash -n "$kotlin_detekt_script"
bash -n "$kotlin_test_style_script"
bash -n "$kotlin_format_script"
bash -n "$kotlin_lint_script"
bash -n "$kotlin_compose_script"
bash -n "$kotlin_coverage_script"
bash -n "$cache_audit_script"
bash -n "$cache_cleanup_script"
bash -n "$local_maintenance_script"
bash -n "$pre_commit"
if command -v just >/dev/null 2>&1; then
  just --list >/dev/null
fi

require_text "$kotlin_quality_script" "lomo_kotlin_run show modules"
require_text "$kotlin_quality_script" "lomo_kotlin_run build"
require_text "$kotlin_quality_script" "lomo_kotlin_run test"
require_text "$kotlin_quality_script" "kotlin_detekt_check.sh"
require_text "$kotlin_quality_script" "kotlin_test_style_check.sh"
require_text "$kotlin_quality_script" "kotlin_android_lint_check.sh"
require_text "$kotlin_quality_script" "kotlin_compose_static_analysis.sh"
require_text "$kotlin_quality_script" "kotlin_coverage_check.sh"
require_text "$kotlin_quality_script" "check_meaningful_tests.sh"
require_text "$kotlin_quality_script" "lomo_kotlin_quality_build_dir quality-gate"
require_text "$kotlin_quality_script" 'LOMO_KOTLIN_BUILD_DIR="$quality_build_dir"'
require_text "$kotlin_quality_script" 'LOMO_LINT_BUILD_DIR="$quality_build_dir"'
require_text "$kotlin_quality_script" 'LOMO_COMPOSE_BUILD_DIR="$quality_build_dir"'
require_text "$kotlin_quality_script" 'LOMO_COVERAGE_BUILD_DIR="$quality_build_dir"'
reject_text "$kotlin_quality_script" "./gradlew"
reject_text "$kotlin_quality_script" "gradlew"

require_text "$kotlin_static_script" "lomo_kotlin_run show modules"
require_text "$kotlin_static_script" "lomo_kotlin_run build"
require_text "$kotlin_static_script" "kotlin_detekt_check.sh"
require_text "$kotlin_static_script" "kotlin_test_style_check.sh"
require_text "$kotlin_static_script" "kotlin_android_lint_check.sh"
require_text "$kotlin_static_script" "lomo_kotlin_run test"
require_text "$kotlin_static_script" "lomo_kotlin_quality_build_dir static-gate"
require_text "$kotlin_static_script" 'LOMO_KOTLIN_BUILD_DIR="$static_build_dir"'
require_text "$kotlin_static_script" 'LOMO_LINT_BUILD_DIR="$static_build_dir"'
reject_text "$kotlin_static_script" "./gradlew"

require_text "$kotlin_test_args_script" "toolchain_test_modules"
reject_text "$kotlin_test_args_script" "toolchain_android_instrumentation_excludes"
reject_text "$kotlin_test_args_script" "--exclude-classes"

require_function_text "$kotlin_env_script" "lomo_kotlin_prepare_env" "kotlin_android_sdk="
require_function_text "$kotlin_env_script" "lomo_kotlin_prepare_env" "kotlin_gradle_user_home="
require_function_text "$kotlin_env_script" "lomo_kotlin_quality_build_dir" 'LOMO_QUALITY_BUILD_ROOT'
require_function_text "$kotlin_env_script" "lomo_kotlin_explicit_build_dir" '--build-dir'
require_function_text "$kotlin_env_script" "lomo_kotlin_run" 'HOME="$kotlin_home"'
require_function_text "$kotlin_env_script" "lomo_kotlin_run" 'ANDROID_USER_HOME="$kotlin_android_home"'
require_function_text "$kotlin_env_script" "lomo_kotlin_run" 'ANDROID_HOME="$kotlin_android_sdk"'
require_function_text "$kotlin_env_script" "lomo_kotlin_run" 'GRADLE_USER_HOME="$kotlin_gradle_user_home"'
require_function_text "$kotlin_env_script" "lomo_kotlin_run" '"$kotlin_wrapper"'
reject_text "$kotlin_env_script" "gradlew"

require_text "$local_maintenance_script" "lomo_kotlin_run build --module app --platform android --variant release"
require_text "$local_maintenance_script" "generate_static_baseline_profile.py"
reject_text "$local_maintenance_script" "./gradlew"

require_text "$kotlin_detekt_script" 'build_dir="${LOMO_KOTLIN_BUILD_DIR:-$repo_root/.kotlin/toolchain-build/detekt-gate}"'
require_text "$kotlin_test_style_script" 'build_dir="${LOMO_KOTLIN_BUILD_DIR:-$repo_root/.kotlin/toolchain-build/test-style-gate}"'
require_text "$kotlin_format_script" 'build_dir="${LOMO_KOTLIN_BUILD_DIR:-$repo_root/.kotlin/toolchain-build/format-gate}"'
require_text "$kotlin_format_script" 'lomo_kotlin_run build --module detekt-rules --build-dir "$build_dir"'
require_text "$kotlin_compose_script" 'build_dir="${LOMO_COMPOSE_BUILD_DIR:-${LOMO_KOTLIN_BUILD_DIR:-$repo_root/.kotlin/toolchain-build/compose-gate}}"'
require_text "$kotlin_compose_script" 'lomo_kotlin_run build --module app --platform android --variant debug --build-dir "$build_dir"'
require_text "$kotlin_lint_script" 'build_dir="${LOMO_LINT_BUILD_DIR:-${LOMO_KOTLIN_BUILD_DIR:-$repo_root/.kotlin/toolchain-build/lint-gate}}"'
require_text "$kotlin_coverage_script" 'build_dir="${LOMO_COVERAGE_BUILD_DIR:-${LOMO_KOTLIN_BUILD_DIR:-$repo_root/.kotlin/toolchain-build/coverage-gate}}"'

require_text "$cache_audit_script" "du -h -d 1"
require_text "$cache_cleanup_script" "--dry-run"
require_text "$cache_cleanup_script" "--apply"
require_text "$cache_cleanup_script" ".kotlin/toolchain-build"
require_text "$cache_cleanup_script" "build/reports/configuration-cache"
reject_text "$cache_cleanup_script" ".git"

require_text "$justfile" "quality/scripts/kotlin_quality_check.sh"
require_text "$justfile" "quality/scripts/kotlin_static_quality_check.sh"
require_text "$justfile" "quality/scripts/kotlin_detekt_format.sh staged"
require_text "$justfile" "quality/scripts/kotlin_cache_audit.sh"
require_text "$justfile" "quality/scripts/kotlin_cache_cleanup.sh --dry-run"
require_text "$justfile" "quality/scripts/kotlin_cache_cleanup.sh --apply"
require_text "$justfile" "source quality/scripts/kotlin_toolchain_env.sh"
require_text "$justfile" "lomo_kotlin_prepare_env"
require_text "$justfile" "lomo_kotlin_run test --build-dir .kotlin/toolchain-build/test"
require_text "$justfile" "lomo_kotlin_run build --module app --platform android --variant debug --build-dir .kotlin/toolchain-build/app-debug"
require_text "$justfile" "lomo_kotlin_run build --module app --platform android --variant release --build-dir .kotlin/toolchain-build/app-release"
reject_text "$justfile" "ANDROID_HOME="
reject_text "$justfile" "GRADLE_USER_HOME="
reject_text "$justfile" "HOME="
reject_text "$justfile" "gradlew"
reject_text "$justfile" "Makefile"

reject_text "$quality_workflow" "just "
reject_text "$release_workflow" "just "

require_text "$pre_commit" "kotlin_detekt_format.sh"
require_text "$pre_commit" "kotlin_quality_check.sh"
require_text "$pre_commit" "kotlin_toolchain_env.sh"
reject_text "$pre_commit" "gradlew"
reject_text "$pre_commit" "ai_gradle_env"
reject_text "$pre_commit" "detektFormatStaged"

require_text "$root_build" "modules:"
require_text "$quality_readme" "Kotlin Toolchain"
require_text "$quality_readme" "just --list"
require_text "$quality_readme" "just quality"
reject_text "$quality_readme" "LOMO_SKIP_LOCAL_MAINTENANCE"

echo "kotlin-quality-check-contract: ok"
