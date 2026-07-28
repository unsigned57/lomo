#!/usr/bin/env bash
set -euo pipefail

# Behavior Contract
# Capability: keep Android runtime dependencies disjoint from APIs supplied by the Android platform.
# Scenarios:
# - Given remote sync cutover owns WebDAV in Rust, when data dependencies are declared, then no JVM
#   XML parser implementation or dav4jvm dependency enters the Android runtime graph.
# - Given Kotlin WebDAV transport is deleted after P5-13, when the data tree is inspected, then
#   OkHttpWebDavClient must not return as a dual-stack owner.
# - Given the dependency boundary is correct, when R8 rules are inspected, then no xmlpull warning
#   suppression or keep workaround remains.
# Observable outcomes: invalid dependency coordinates, a restored Kotlin WebDAV transport owner, or
# stale R8 workarounds fail this contract with a specific diagnostic.
# Excludes: WebDAV protocol behavior and Kotlin Toolchain execution.

repo_root="$(git rev-parse --show-toplevel)"
data_module="$repo_root/data/module.yaml"
proguard_rules="$repo_root/app/proguard-rules.pro"
webdav_transport="$repo_root/data/src/webdav/OkHttpWebDavClient.kt"

fail() {
  echo "android-runtime-dependency-boundary: $1" >&2
  exit 1
}

if grep -Eq -- "dav4jvm|org\.ogce:xpp3" "$data_module"; then
  fail "data Android dependencies must not include dav4jvm or xpp3"
fi

# Post P5-13: Kotlin WebDAV transport is deleted; Rust owns WebDAV. Restoring the client would re-open
# dual-stack ownership and platform XML-parser dependency risk (stage_five forbids this path).
if [ -f "$webdav_transport" ]; then
  fail "Kotlin WebDAV transport must stay deleted after Rust remote-sync cutover ($webdav_transport)"
fi

if grep -Fq -- "org.xmlpull.v1" "$proguard_rules"; then
  fail "R8 xmlpull workaround must be removed after dependency repair"
fi

echo "android runtime dependency boundary contract passed"
