#!/usr/bin/env bash
# Lightweight iterative gate: model validation, build, and host tests.
# Intentionally omits Detekt, Lint, Compose static, shell contracts, and coverage.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=quality/scripts/kotlin_toolchain_env.sh
source "$script_dir/kotlin_toolchain_env.sh"
# shellcheck source=quality/scripts/kotlin_toolchain_test_args.sh
source "$script_dir/kotlin_toolchain_test_args.sh"

lomo_kotlin_prepare_env "kotlin-fast-quality-check"
fast_build_dir="$(lomo_kotlin_quality_build_dir fast-gate "$@")"
toolchain_args=("$@")
if [ -z "$(lomo_kotlin_explicit_build_dir "$@")" ]; then
  toolchain_args+=(--build-dir "$fast_build_dir")
fi

echo "kotlin-fast-quality-check: validating Kotlin Toolchain project model"
lomo_kotlin_run show modules

echo "kotlin-fast-quality-check: running Kotlin Toolchain build"
lomo_kotlin_run build "${toolchain_args[@]}"

echo "kotlin-fast-quality-check: running host tests"
lomo_kotlin_run test "${toolchain_test_modules[@]}" "${toolchain_args[@]}"

echo "kotlin-fast-quality-check: ok"
