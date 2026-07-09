#!/usr/bin/env bash
# Test-style Detekt (parity with old testStyleCheck).
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=quality/scripts/kotlin_toolchain_env.sh
source "$script_dir/kotlin_toolchain_env.sh"
# shellcheck source=quality/scripts/kotlin_detekt_env.sh
source "$script_dir/kotlin_detekt_env.sh"

lomo_kotlin_prepare_env "kotlin-test-style-check"
repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

report_root="$repo_root/build/reports/detekt-test-style"
mkdir -p "$report_root"

echo "kotlin-test-style-check: ensuring detekt-rules jar exists"
if [ ! -f "${LOMO_KOTLIN_BUILD_DIR:-$repo_root/build}/tasks/_detekt-rules_jarJvm/detekt-rules-jvm.jar" ]; then
  lomo_kotlin_run build --module detekt-rules
fi

config="quality/detekt/config/test-style.yml"
failed=0

for module in app domain data ui-components; do
  inputs=()
  [ -d "$module/test" ] && inputs+=("$module/test")
  [ -d "$module/test@android" ] && inputs+=("$module/test@android")

  if [ "${#inputs[@]}" -eq 0 ]; then
    echo "kotlin-test-style-check: $module has no test roots; skipping"
    continue
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
