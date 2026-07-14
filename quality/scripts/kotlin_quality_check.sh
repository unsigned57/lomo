#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=quality/scripts/kotlin_toolchain_env.sh
source "$script_dir/kotlin_toolchain_env.sh"

lomo_kotlin_prepare_env "kotlin-quality-check"
quality_build_dir="$(lomo_kotlin_quality_build_dir quality-gate "$@")"
toolchain_args=("$@")
if [ -z "$(lomo_kotlin_explicit_build_dir "$@")" ]; then
  toolchain_args+=(--build-dir "$quality_build_dir")
fi

echo "kotlin-quality-check: validating Kotlin Toolchain project model"
lomo_kotlin_run show modules

echo "kotlin-quality-check: running Kotlin Toolchain build"
lomo_kotlin_run build "${toolchain_args[@]}"

echo "kotlin-quality-check: running architecture Detekt"
LOMO_KOTLIN_BUILD_DIR="$quality_build_dir" "$script_dir/kotlin_detekt_check.sh"

echo "kotlin-quality-check: running test-style Detekt"
LOMO_KOTLIN_BUILD_DIR="$quality_build_dir" "$script_dir/kotlin_test_style_check.sh"

echo "kotlin-quality-check: running Android Lint"
LOMO_KOTLIN_BUILD_DIR="$quality_build_dir" LOMO_LINT_BUILD_DIR="$quality_build_dir" "$script_dir/kotlin_android_lint_check.sh"

echo "kotlin-quality-check: running Compose static analysis"
LOMO_KOTLIN_BUILD_DIR="$quality_build_dir" LOMO_COMPOSE_BUILD_DIR="$quality_build_dir" "$script_dir/kotlin_compose_static_analysis.sh"

echo "kotlin-quality-check: running repository shell quality contracts"
"$script_dir/check_meaningful_tests.sh"
"$script_dir/check_string_resource_parity.sh"
"$script_dir/test/android_runtime_dependency_boundary_contract_test.sh"
"$script_dir/test/kotlin_quality_check_contract_test.sh"

# Host tests run once under the JaCoCo agent inside coverage verification.
# A separate uninstrumented test pass would double wall-clock cost without
# additional gate strength (coverage already fails on test failure).
echo "kotlin-quality-check: running host tests under coverage verification"
LOMO_KOTLIN_BUILD_DIR="$quality_build_dir" LOMO_COVERAGE_BUILD_DIR="$quality_build_dir" "$script_dir/kotlin_coverage_check.sh"

echo "kotlin-quality-check: ok"
