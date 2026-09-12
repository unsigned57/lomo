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
  good="$fixture_root/good/$module/src/model"
  bad="$fixture_root/bad/$module/src/model"
  mkdir -p "$good" "$bad"
  printf 'package %s.model\n\ndata class RuleProbe(val value: String)\n' "$package" > "$good/RuleProbe.kt"
  printf 'package %s.model\n\nexternal fun unexpectedNativeBoundary()\n' "$package" > "$bad/NativeProbe.kt"
  printf 'package %s.model\n\nimport kotlinx.coroutines.flow.MutableStateFlow\nclass StreamProbe { val writer = MutableStateFlow(0) }\n' \
    "$package" > "$bad/StreamProbe.kt"
  printf '@file:Suppress("all")\n\npackage %s.model\n\ndata class SuppressedProbe(val value: String)\n' \
    "$package" > "$bad/SuppressedProbe.kt"

  config="$repo_root/quality/detekt/config/$module.yml"
  if ! lomo_detekt_run --input "$good" --config "$config" --build-upon-default-config \
    > "$fixture_root/$module-good.log" 2>&1; then
    cat "$fixture_root/$module-good.log" >&2
    echo "detekt-activation: $module rejected legal control source" >&2
    failed=1
  fi
  report="$fixture_root/$module.xml"
  if lomo_detekt_run --input "$bad" --config "$config" --build-upon-default-config \
    --report "checkstyle:$report" > "$fixture_root/$module-bad.log" 2>&1; then
    echo "detekt-activation: $module accepted forbidden source" >&2
    failed=1
  fi
  if [ ! -f "$report" ]; then
    cat "$fixture_root/$module-bad.log" >&2
    echo "detekt-activation: $module did not produce a diagnostic report" >&2
    failed=1
    continue
  fi
  for rule in NoHandwrittenNativeDeclaration NoMutableFlowExposure NoSourceSuppressions; do
    if ! rg -Fq "source=\"detekt.$rule\"" "$report"; then
      cat "$fixture_root/$module-bad.log" >&2
      echo "detekt-activation: $module is missing required finding $rule" >&2
      failed=1
    fi
  done
done

if [ "$failed" -ne 0 ]; then
  exit 1
fi
echo "detekt-activation: all 4 production configurations rejected forbidden source and accepted legal source"
