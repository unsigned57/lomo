#!/usr/bin/env bash
set -euo pipefail
# Behavior Contract:
# Capability: full Detekt resolves actual cross-file types and method identities on the real
# compile model of every analyzed module; owner: quality; P0.
# Given a factory-inferred mutable flow and an aliased forbidden function, when full analysis runs
# with a module's own compiler arguments, then both target diagnostics appear; the same
# type-dependent rules are absent in light mode.
# Given an Android module, when a forbidden call is reached only through an android.jar type, then
# full analysis still reports it (the module's Android boot classpath resolves).
# Given a read-only projection, when full analysis runs, then no mutable-flow diagnostic appears.
# Observable outcomes: checkstyle rule identities per fixture, using each production module config.
# TDD proof: RED omits NoInferredMutableFlowExposure before semantic registration; GREEN same script.
# The per-module scenarios were added after an audit found only domain (pure JVM) was proven.
# Excludes: proving arbitrary program reachability or concurrency by syntax alone.

repo_root="$(git rev-parse --show-toplevel)"
source "$repo_root/quality/scripts/kotlin_detekt_env.sh"
fixture_root="$(mktemp -d /tmp/lomo-detekt-full.XXXXXX)"
trap 'rm -rf -- "$fixture_root"' EXIT

# The canary proves the modules the caller is about to analyze; standalone it proves all of them.
IFS=',' read -r -a modules <<< "${LOMO_DETEKT_CANARY_MODULES:-domain,data,app,ui-components}"
if [ "${#modules[@]}" -eq 0 ]; then
  echo "detekt-full-analysis: no modules to prove" >&2
  exit 1
fi

run_detekt() {
  local output="$1"
  shift
  local status=0
  lomo_detekt_run "$@" --report "checkstyle:$output.xml" > "$output.log" 2>&1 || status=$?
  if [ ! -f "$output.xml" ]; then
    cat "$output.log" >&2
    exit "$status"
  fi
}

for module in "${modules[@]}"; do
  package="com.lomo.${module//-/}.detektcanary"
  source_root="$fixture_root/$module/src"
  mkdir -p "$source_root"
  cat > "$source_root/Factory.kt" <<KOTLIN
package $package
import kotlinx.coroutines.flow.MutableStateFlow
internal object Factory { fun writer() = MutableStateFlow(0) }
KOTLIN
  cat > "$source_root/Bad.kt" <<KOTLIN
package $package
class WriterHolder { val changes = Factory.writer() }
fun forbiddenCase(value: String): String = value.lowercase()
KOTLIN
  cat > "$source_root/Good.kt" <<KOTLIN
package $package
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
class ReaderHolder {
    private val writer = Factory.writer()
    val changes: StateFlow<Int> = writer.asStateFlow()
}
KOTLIN
  android=false
  if [ "$module" != domain ]; then
    android=true
    cat > "$source_root/AndroidBad.kt" <<KOTLIN
package $package
fun androidForbidden(context: android.content.Context): String = context.packageName.lowercase()
KOTLIN
  fi
  config="$repo_root/quality/detekt/config/$module.yml"
  python3 "$repo_root/quality/scripts/kotlin_analysis_input.py" detekt-args "$module" > "$fixture_root/$module.args"
  mapfile -d '' -t compiler_args < "$fixture_root/$module.args"
  run_detekt "$fixture_root/$module-full" --input "$source_root" --config "$config" \
    --build-upon-default-config "${compiler_args[@]}"
  if [ "$module" = domain ]; then
    run_detekt "$fixture_root/$module-light" --input "$source_root" --config "$config" \
      --build-upon-default-config --analysis-mode light
  fi
  python3 - "$fixture_root" "$module" "$android" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
root, module, android = Path(sys.argv[1]), sys.argv[2], sys.argv[3] == "true"
def findings(mode, name):
    return {error.attrib['source'].rsplit('.', 1)[-1]
            for file in ET.parse(root / f'{module}-{mode}.xml').getroot().findall('file')
            if Path(file.attrib['name']).name == name for error in file.findall('error')}
required = {'NoInferredMutableFlowExposure', 'ForbiddenMethodCall'}
if (root / f'{module}-light.xml').exists():
    assert not required.intersection(findings('light', 'Bad.kt')), 'light falsely claims full semantic coverage'
missing = required - findings('full', 'Bad.kt')
assert not missing, f"{module}: full missing type diagnostics: {missing}"
assert 'NoInferredMutableFlowExposure' not in findings('full', 'Good.kt'), f"{module}: read-only alias rejected"
if android:
    assert 'ForbiddenMethodCall' in findings('full', 'AndroidBad.kt'), \
        f"{module}: android.jar types do not resolve in full analysis"
print(f'detekt-full-analysis: semantic canary passed for {module}')
PY
done
