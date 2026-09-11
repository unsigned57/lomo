#!/usr/bin/env bash
# Test-style Detekt (parity with old testStyleCheck).
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=quality/scripts/kotlin_detekt_env.sh
source "$script_dir/kotlin_detekt_env.sh"

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"
build_dir="${LOMO_KOTLIN_BUILD_DIR:-$repo_root/.kotlin/toolchain-build/shared}"

report_root="$repo_root/build/reports/detekt-test-style"
mkdir -p "$report_root"

echo "kotlin-test-style-check: ensuring detekt-rules jar exists"
if [ ! -f "$build_dir/tasks/_detekt-rules_jarJvm/detekt-rules-jvm.jar" ]; then
  "${LOMO_KOTLIN_WRAPPER:?xtask must provide LOMO_KOTLIN_WRAPPER}" --log-level=warn \
    build --module detekt-rules --build-dir "$build_dir"
fi

config="quality/detekt/config/test-style.yml"
failed=0

for module in app domain data ui-components; do
  inputs=()
  mod_dir="apps/android/$module"
  if [ ! -d "$mod_dir" ]; then
    echo "kotlin-test-style-check: missing expected module directory: $mod_dir" >&2
    exit 1
  fi
  [ -d "$mod_dir/test" ] && inputs+=("$mod_dir/test")
  [ -d "$mod_dir/test@android" ] && inputs+=("$mod_dir/test@android")

  if [ "${#inputs[@]}" -eq 0 ]; then
    echo "kotlin-test-style-check: $module has no test roots under $mod_dir" >&2
    exit 1
  fi

  echo "kotlin-test-style-check: analyzing $module (${inputs[*]})"
  if ! lomo_detekt_run \
    --input "$(IFS=,; echo "${inputs[*]}")" \
    --config "$config" \
    --disable-default-rulesets \
    --report "html:$report_root/${module}.html"; then
    echo "kotlin-test-style-check: $module failed" >&2
    failed=1
  fi
done

if [ "$failed" -ne 0 ]; then
  echo "kotlin-test-style-check: failed" >&2
  exit 1
fi

echo "kotlin-test-style-check: ok"
