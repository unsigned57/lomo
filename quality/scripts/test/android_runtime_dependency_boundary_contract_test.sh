#!/usr/bin/env bash
set -euo pipefail

# Behavior Contract
# Capability: keep Android runtime dependencies disjoint from APIs supplied by the Android platform.
# Scenarios:
# - Given remote sync cutover owns WebDAV in Rust, when data dependencies are declared, then no JVM
#   XML parser implementation or dav4jvm dependency enters the Android runtime graph.
# - Given the dependency boundary is correct, when R8 rules are inspected, then no xmlpull warning
#   suppression or keep workaround remains.
# Observable outcomes: invalid dependency coordinates and R8 workarounds fail with specific
# diagnostics.
# Excludes: WebDAV protocol behavior and Kotlin Toolchain execution.

repo_root="$(git rev-parse --show-toplevel)"
data_module="$repo_root/apps/android/data/module.yaml"
proguard_rules="$repo_root/apps/android/app/proguard-rules.pro"

fail() {
  echo "android-runtime-dependency-boundary: $1" >&2
  exit 1
}

[ -f "$data_module" ] || fail "missing $data_module"
[ -f "$proguard_rules" ] || fail "missing $proguard_rules"

if grep -Eq -- "dav4jvm|org\.ogce:xpp3" "$data_module"; then
  fail "data Android dependencies must not include dav4jvm or xpp3"
fi

if grep -Fq -- "org.xmlpull.v1" "$proguard_rules"; then
  fail "R8 xmlpull workaround must be removed after dependency repair"
fi

echo "android runtime dependency boundary contract passed"
