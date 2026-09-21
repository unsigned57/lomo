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

mode="${LOMO_DETEKT_MODE:-light}"
case "$mode" in
  light) bash "$script_dir/test/detekt_activation_contract_test.sh" ;;
  full) bash "$script_dir/test/kotlin_analysis_input_contract_test.sh" ;;
  *) echo "kotlin-detekt-check: unknown analysis mode $mode" >&2; exit 1 ;;
esac
IFS=',' read -r -a modules <<< "${LOMO_DETEKT_MODULES:-app,domain,data,ui-components}"

declare -A module_config=(
  [app]="quality/detekt/config/app.yml"
  [domain]="quality/detekt/config/domain.yml"
  [data]="quality/detekt/config/data.yml"
  [ui-components]="quality/detekt/config/ui-components.yml"
)

failed=0
for module in "${modules[@]}"; do
  if [ -z "${module_config[$module]+configured}" ]; then
    echo "kotlin-detekt-check: unknown module $module" >&2
    exit 1
  fi
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
  report="$report_root/${module}-${mode}.html"

  echo "kotlin-detekt-check: analyzing $module ($input)"
  args=(
    --input "$input"
    --config "$config"
    --build-upon-default-config
    --report "html:$report"
    --report "checkstyle:$report_root/${module}-${mode}.xml"
  )
  if [ "$mode" = full ]; then
    analysis_args="$report_root/${module}-analysis-args"
    python3 "$script_dir/kotlin_analysis_input.py" detekt-args "$module" > "$analysis_args"
    mapfile -d '' -t compiler_args < "$analysis_args"
    args+=("${compiler_args[@]}")
    mkdir -p "$report_root/symbols"
    export LOMO_SYMBOL_FACTS_DIR
    LOMO_SYMBOL_FACTS_DIR="$(mktemp -d "$report_root/symbols/${module}.XXXXXX")"
    export LOMO_BINDINGS_SOURCE="$repo_root/apps/android/native-bindings/src/LomoNativeBridge.kt"
  else
    args+=(--analysis-mode light)
  fi
  if ! lomo_detekt_run "${args[@]}"; then
    echo "kotlin-detekt-check: $module failed" >&2
    failed=1
  fi
  if [ "$mode" = full ]; then
    python3 - "$report_root/symbols/index-$module.json" "$LOMO_SYMBOL_FACTS_DIR" <<'PY'
import json
from pathlib import Path
import sys
target = Path(sys.argv[1])
temporary = target.with_suffix('.partial')
temporary.write_text(json.dumps({'schema_version': 1, 'directory': sys.argv[2]}))
temporary.replace(target)
PY
  fi
done

if [ "$failed" -ne 0 ]; then
  echo "kotlin-detekt-check: architecture/style detekt failed" >&2
  exit 1
fi

echo "kotlin-detekt-check: ok"
