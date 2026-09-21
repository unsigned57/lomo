#!/usr/bin/env bash
set -euo pipefail

# Behavior Contract
# Capability: prove the real CLI, service loader, plugin jar and production configurations enforce
# native ownership even when a Kotlin file attempts to suppress every Detekt finding.
# Scenarios:
# - Given every production config, when legal source is checked, then the CLI succeeds.
# - Given a handwritten native declaration and a file-level suppression, when the same CLI runs,
#   then native, mutable-flow and suppression ownership findings appear and the CLI fails.
# Observable outcomes: CLI exit codes and rule IDs in the machine-readable checkstyle report.
# TDD proof: before activation/unsuppressible policy, the real CLI omits these required rule IDs.
# Excludes: product compilation, Android execution and type-resolution-dependent lint rules.

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"
# shellcheck source=quality/scripts/kotlin_detekt_env.sh
source "$repo_root/quality/scripts/kotlin_detekt_env.sh"

fixture_root="$(mktemp -d /tmp/lomo-detekt-activation.XXXXXX)"
trap 'rm -rf -- "$fixture_root"' EXIT

failed=0
for module in app domain data ui-components; do
  case "$module" in
    ui-components) package="com.lomo.ui" ;;
    *) package="com.lomo.$module" ;;
  esac
  source_root="$fixture_root/$module/src/model"
  mkdir -p "$source_root"
  printf 'package %s.model\n\nimport kotlinx.coroutines.flow.MutableStateFlow\nimport kotlinx.coroutines.flow.StateFlow\nimport kotlinx.coroutines.flow.asStateFlow\n/**\n * A legal read-only state owner.\n */\nclass RuleProbe {\n  private val source = MutableStateFlow(0)\n  val state: StateFlow<Int> = source.asStateFlow()\n}\n' \
    "$package" > "$source_root/RuleProbe.kt"
  printf 'package %s.model\n\nexternal fun unexpectedNativeBoundary()\n' "$package" > "$source_root/NativeProbe.kt"
  printf 'package %s.model\n\nimport kotlinx.coroutines.flow.MutableStateFlow\nclass StreamProbe { val writer = MutableStateFlow(0) }\n' \
    "$package" > "$source_root/StreamProbe.kt"
  printf '@file:Suppress("all")\n\npackage %s.model\n\ndata class SuppressedProbe(val value: String)\n' \
    "$package" > "$source_root/SuppressedProbe.kt"

  config="$repo_root/quality/detekt/config/$module.yml"
  report="$fixture_root/$module.xml"
  # One JVM per configuration checks the good and bad controls together; inspect each file's
  # diagnostics so a forbidden fixture cannot conceal a false positive on the legal control.
  if lomo_detekt_run --input "$source_root" --config "$config" --build-upon-default-config \
    --report "checkstyle:$report" > "$fixture_root/$module.log" 2>&1; then
    echo "detekt-activation: $module accepted forbidden source" >&2
    failed=1
  fi
  if ! python3 - "$report" "$module" <<'CHECK_REPORT'
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

report, module = sys.argv[1:]
expected = {
    "NativeProbe.kt": {"NoHandwrittenNativeDeclaration"},
    "StreamProbe.kt": {"NoMutableFlowExposure"},
    "SuppressedProbe.kt": {"NoSourceSuppressions"},
    "RuleProbe.kt": set(),
}
observed = {name: set() for name in expected}
for file in ET.parse(report).getroot().findall("file"):
    name = Path(file.attrib["name"]).name
    if name not in observed:
        raise SystemExit(f"detekt-activation: {module} unexpected diagnostic input {name}")
    for error in file.findall("error"):
        observed[name].add(error.attrib["source"].rsplit(".", 1)[-1])
for name, required in expected.items():
    missing = required - observed[name]
    if missing:
        raise SystemExit(f"detekt-activation: {module}/{name} missing findings: {sorted(missing)}")
if observed["RuleProbe.kt"]:
    raise SystemExit(f"detekt-activation: {module} rejected legal control: {sorted(observed['RuleProbe.kt'])}")
CHECK_REPORT
  then
    cat "$fixture_root/$module.log" >&2
    failed=1
  fi

done

if [ "$failed" -ne 0 ]; then
  exit 1
fi
echo "detekt-activation: all 4 production configurations rejected forbidden source and accepted legal source"
