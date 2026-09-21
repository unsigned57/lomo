#!/usr/bin/env bash
set -euo pipefail
# Behavior Contract:
# Capability: full Detekt resolves actual cross-file types and method identities; owner: quality; P0.
# Given a factory-inferred mutable flow and an aliased forbidden function, when full analysis runs,
# then both target diagnostics appear; the same type-dependent rules are absent in light mode.
# Given a read-only projection, when full analysis runs, then no mutable-flow diagnostic appears.
# Observable outcomes: checkstyle rule identities per fixture, using the production domain config.
# TDD proof: RED omits NoInferredMutableFlowExposure before semantic registration; GREEN same script.
# Excludes: proving arbitrary program reachability or concurrency by syntax alone.

repo_root="$(git rev-parse --show-toplevel)"
source "$repo_root/quality/scripts/kotlin_detekt_env.sh"
fixture_root="$(mktemp -d /tmp/lomo-detekt-full.XXXXXX)"
trap 'rm -rf -- "$fixture_root"' EXIT
source_root="$fixture_root/domain/src/model"
mkdir -p "$source_root"
cat > "$source_root/Factory.kt" <<'KOTLIN'
package com.lomo.domain.model
import kotlinx.coroutines.flow.MutableStateFlow
internal object Factory { fun writer() = MutableStateFlow(0) }
KOTLIN
cat > "$source_root/Bad.kt" <<'KOTLIN'
package com.lomo.domain.model
class WriterHolder { val changes = Factory.writer() }
fun forbiddenCase(value: String): String = value.lowercase()
KOTLIN
cat > "$source_root/Good.kt" <<'KOTLIN'
package com.lomo.domain.model
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
class ReaderHolder {
    private val writer = Factory.writer()
    val changes: StateFlow<Int> = writer.asStateFlow()
}
KOTLIN
python3 "$repo_root/quality/scripts/kotlin_analysis_input.py" detekt-args domain > "$fixture_root/compiler-args"
mapfile -d '' -t compiler_args < "$fixture_root/compiler-args"
for mode in light full; do
  args=(--analysis-mode light)
  if [ "$mode" = full ]; then args=("${compiler_args[@]}"); fi
  if lomo_detekt_run --input "$source_root" --config "$repo_root/quality/detekt/config/domain.yml" \
    --build-upon-default-config "${args[@]}" --report "checkstyle:$fixture_root/$mode.xml" > "$fixture_root/$mode.log" 2>&1; then
    status=0
  else
    status=$?
  fi
  if [ ! -f "$fixture_root/$mode.xml" ]; then cat "$fixture_root/$mode.log" >&2; exit "$status"; fi
done
python3 - "$fixture_root" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
root = Path(sys.argv[1])
def findings(mode, name):
    return {error.attrib['source'].rsplit('.', 1)[-1]
            for file in ET.parse(root / f'{mode}.xml').getroot().findall('file')
            if Path(file.attrib['name']).name == name for error in file.findall('error')}
required = {'NoInferredMutableFlowExposure', 'ForbiddenMethodCall'}
assert not required.intersection(findings('light', 'Bad.kt')), 'light falsely claims full semantic coverage'
assert required <= findings('full', 'Bad.kt'), f"full missing type diagnostics: {required - findings('full', 'Bad.kt')}"
assert 'NoInferredMutableFlowExposure' not in findings('full', 'Good.kt'), 'read-only alias rejected'
print('detekt-full-analysis: semantic canary passed')
PY
