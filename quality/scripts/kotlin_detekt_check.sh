#!/usr/bin/env bash
# Architecture + style Detekt for product modules (parity with old architectureCheck).
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=quality/scripts/kotlin_detekt_env.sh
source "$script_dir/kotlin_detekt_env.sh"

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"
build_dir="${LOMO_KOTLIN_BUILD_DIR:-$repo_root/.kotlin/toolchain-build/shared}"

cargo_target="${CARGO_TARGET_DIR:-$repo_root/target}"
report_root="${LOMO_GENERATED_ROOT:-$cargo_target/lomo}"
case "$report_root" in
  /*) ;;
  *) report_root="$repo_root/$report_root" ;;
esac
report_root="$report_root/reports/detekt"
mkdir -p "$report_root"

echo "kotlin-detekt-check: building custom detekt-rules"
"${LOMO_KOTLIN_WRAPPER:?xtask must provide LOMO_KOTLIN_WRAPPER}" --log-level=warn \
  build --module detekt-rules --build-dir "$build_dir"

bash "$script_dir/test/detekt_activation_contract_test.sh"

declare -A module_config=(
  [app]="quality/detekt/config/app.yml"
  [domain]="quality/detekt/config/domain.yml"
  [data]="quality/detekt/config/data.yml"
  [ui-components]="quality/detekt/config/ui-components.yml"
)

failed=0
for module in app domain data ui-components; do
  input="apps/android/$module/src"
  if [ ! -d "$input" ]; then
    echo "kotlin-detekt-check: missing input directory $input" >&2
    failed=1
    continue
  fi
  config="${module_config[$module]}"
  baseline="apps/android/$module/detekt-baseline.xml"
  if [ -f "$baseline" ]; then
    echo "kotlin-detekt-check: baselines cannot exempt architecture violations: $baseline" >&2
    failed=1
    continue
  fi
  report="$report_root/${module}.html"

  echo "kotlin-detekt-check: analyzing $module ($input)"
  args=(
    --input "$input"
    --config "$config"
    --build-upon-default-config
    --report "html:$report"
  )
  if ! lomo_detekt_run "${args[@]}"; then
    echo "kotlin-detekt-check: $module failed" >&2
    failed=1
  fi
done

if [ "$failed" -ne 0 ]; then
  echo "kotlin-detekt-check: architecture/style detekt failed" >&2
  exit 1
fi

echo "kotlin-detekt-check: ok"
