#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=quality/scripts/kotlin_toolchain_env.sh
source "$script_dir/kotlin_toolchain_env.sh"
# shellcheck source=quality/scripts/kotlin_toolchain_test_args.sh
source "$script_dir/kotlin_toolchain_test_args.sh"

lomo_kotlin_prepare_env "kotlin-static-quality-check"
static_build_dir="$(lomo_kotlin_quality_build_dir static-gate "$@")"
toolchain_args=("$@")
if [ -z "$(lomo_kotlin_explicit_build_dir "$@")" ]; then
  toolchain_args+=(--build-dir "$static_build_dir")
fi

echo "kotlin-static-quality-check: validating Kotlin Toolchain project model"
lomo_kotlin_run show modules

echo "kotlin-static-quality-check: running Kotlin Toolchain build"
lomo_kotlin_run build "${toolchain_args[@]}"

echo "kotlin-static-quality-check: running architecture Detekt"
LOMO_KOTLIN_BUILD_DIR="$static_build_dir" "$script_dir/kotlin_detekt_check.sh"

echo "kotlin-static-quality-check: running test-style Detekt"
LOMO_KOTLIN_BUILD_DIR="$static_build_dir" "$script_dir/kotlin_test_style_check.sh"

echo "kotlin-static-quality-check: running Android Lint"
LOMO_KOTLIN_BUILD_DIR="$static_build_dir" LOMO_LINT_BUILD_DIR="$static_build_dir" "$script_dir/kotlin_android_lint_check.sh"

echo "kotlin-static-quality-check: running repository shell quality contracts"
"$script_dir/check_meaningful_tests.sh"
"$script_dir/check_string_resource_parity.sh"
"$script_dir/test/kotlin_quality_check_contract_test.sh"

echo "kotlin-static-quality-check: running Kotlin Toolchain tests"
lomo_kotlin_run test "${toolchain_test_modules[@]}" "${toolchain_args[@]}"
