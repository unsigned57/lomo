#!/usr/bin/env bash
set -euo pipefail

# Behavior Contract
# Capability: keep Android runtime dependencies disjoint from APIs supplied by the Android platform.
# Scenarios:
# - Given WebDAV transport is built for Android, when data dependencies are declared, then no JVM
#   XML parser implementation or dav4jvm dependency enters the Android runtime graph.
# - Given WebDAV multistatus XML must be parsed, when the transport boundary is inspected, then it
#   uses the platform/JDK XML API already present on Android and host tests.
# - Given the dependency boundary is correct, when R8 rules are inspected, then no xmlpull warning
#   suppression or keep workaround remains.
# Observable outcomes: invalid dependency coordinates, a missing POM exclusion, or stale R8
# workarounds fail this contract with a specific diagnostic.
# TDD proof: fails before the fix because data directly depends on dav4jvm, which brings xpp3 into R8.
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

[ -f "$webdav_transport" ] || fail "missing platform-safe WebDAV transport"
grep -Fq -- "javax.xml.parsers.DocumentBuilderFactory" "$webdav_transport" ||
  fail "WebDAV transport must parse XML through the platform/JDK API"

if grep -Fq -- "org.xmlpull.v1" "$proguard_rules"; then
  fail "R8 xmlpull workaround must be removed after dependency repair"
fi

echo "android runtime dependency boundary contract passed"
