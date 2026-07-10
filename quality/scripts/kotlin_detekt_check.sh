#!/usr/bin/env bash
# Architecture + style Detekt for product modules (parity with old architectureCheck).
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=quality/scripts/kotlin_toolchain_env.sh
source "$script_dir/kotlin_toolchain_env.sh"
# shellcheck source=quality/scripts/kotlin_detekt_env.sh
source "$script_dir/kotlin_detekt_env.sh"

lomo_kotlin_prepare_env "kotlin-detekt-check"
repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"
build_dir="${LOMO_KOTLIN_BUILD_DIR:-$repo_root/.kotlin/toolchain-build/detekt-gate}"

report_root="$repo_root/build/reports/detekt"
mkdir -p "$report_root"

echo "kotlin-detekt-check: building custom detekt-rules"
lomo_kotlin_run build --module detekt-rules --build-dir "$build_dir"

declare -A module_config=(
  [app]="quality/detekt/config/app.yml"
  [domain]="quality/detekt/config/domain.yml"
  [data]="quality/detekt/config/data.yml"
  [ui-components]="quality/detekt/config/ui-components.yml"
)

declare -A module_input=(
  [app]="app/src"
  [domain]="domain/src"
  [data]="data/src"
  [ui-components]="ui-components/src"
)

declare -A module_baseline=(
  [app]="app/detekt-baseline.xml"
  [domain]="domain/detekt-baseline.xml"
  [data]="data/detekt-baseline.xml"
  [ui-components]="ui-components/detekt-baseline.xml"
)

failed=0
for module in app domain data ui-components; do
  input="${module_input[$module]}"
  config="${module_config[$module]}"
  baseline="${module_baseline[$module]}"
  report="$report_root/${module}.html"

  if [ ! -d "$input" ]; then
    echo "kotlin-detekt-check: missing input directory $input" >&2
    failed=1
    continue
  fi

  echo "kotlin-detekt-check: analyzing $module ($input)"
  args=(
    --input "$input"
    --config "$config"
    --build-upon-default-config
    --report "html:$report"
  )
  if [ -f "$baseline" ]; then
    args+=(--baseline "$baseline")
  fi

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
