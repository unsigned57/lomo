#!/usr/bin/env bash
set -euo pipefail

# Behavior Contract
# Capability: prove xtask is the only public Rust/Kotlin/native/Android quality orchestrator.
# Scenarios:
# - Given public commands, when Justfile and hooks are inspected, then they call only lomo-xtask.
# - Given native inputs, when configuration is inspected, then the Rust channel pin
#   (rust-toolchain.toml + matching rust-version), NDK 29, BoltFFI JNI library identity,
#   four Android ABIs, and ignored generated outputs are fixed at the owning boundary.
# - Given old workflow tails, when the repository is inspected, then none remain.
# Observable outcomes: missing canonical wiring or retained legacy orchestration fails this script.
# TDD proof: failed before xtask because the old Kotlin/Rust shell gates and NDK 28 remained.
# Excludes: executing external tools, compiling product code, and device runtime behavior.

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

fail() {
  echo "xtask-contract: $*" >&2
  exit 1
}

require_text() {
  local file="$1"
  local text="$2"
  grep -Fq -- "$text" "$file" || fail "$file is missing: $text"
}

reject_path() {
  [ ! -e "$1" ] || fail "legacy path remains: $1"
}

for file in \
  Justfile \
  rust/Cargo.toml \
  rust/rust-toolchain.toml \
  rust/tools.toml \
  rust/xtask/src/quality.rs \
  rust/xtask/src/native.rs \
  rust/xtask/src/android.rs \
  .githooks/pre-commit \
  .githooks/pre-push; do
  [ -f "$file" ] || fail "required file missing: $file"
done

require_text Justfile 'cargo run --manifest-path rust/Cargo.toml --locked -p lomo-xtask --'
for command in bootstrap fmt test preflight check native android ci device-smoke deps perf cache rust-toolchain-bump; do
  grep -Eq -- "^${command}([[:space:]].*)?:$" Justfile || fail "Justfile recipe missing: $command"
done

channel="$(
  awk '
    /^\[toolchain\]/ { in_tc = 1; next }
    /^\[/ { in_tc = 0 }
    in_tc && $1 == "channel" {
      gsub(/"/, "", $3)
      print $3
      exit
    }
  ' rust/rust-toolchain.toml
)"
[ -n "${channel}" ] || fail "rust/rust-toolchain.toml missing channel"
msrv="$(printf '%s' "${channel}" | awk -F. '{ print $1 "." $2 }')"
[ -n "${msrv}" ] || fail "unable to derive msrv from channel ${channel}"
case "${channel}" in
  stable|beta|nightly|stable-*|beta-*|nightly-*)
    fail "floating Rust channel is forbidden: ${channel}"
    ;;
esac

require_text rust/Cargo.toml "rust-version = \"${msrv}\""
require_text rust/Cargo.toml 'license = "GPL-3.0-only"'
require_text rust/Cargo.toml 'warnings = "deny"'
require_text rust/Cargo.toml 'pedantic = "deny"'
require_text rust/Cargo.toml '[profile.release-ci]'
require_text rust/rust-toolchain.toml "channel = \"${channel}\""
require_text rust/xtask/src/rust_pin.rs 'rust-toolchain.toml'
require_text rust/xtask/src/rust_pin.rs 'pub fn bump'
require_text rust/xtask/src/tools.rs 'rust_pin::load'
if grep -Eq 'command\.args\(\[[[:space:]]*"\+[0-9]' rust/xtask/src/tools.rs; then
  fail "rust/xtask/src/tools.rs must not hard-code cargo +channel literals"
fi
require_text rust/xtask/src/workspace.rs '29.0.14206865'
require_text rust/xtask/src/native.rs 'liblomo_native_jni.so'
require_text rust/xtask/src/native.rs 'Abi::ALL'
require_text rust/xtask/src/native.rs 'ReleaseCi'
require_text rust/xtask/src/android.rs 'assets/dexopt/baseline.prof'
require_text rust/xtask/src/android.rs 'env:LOMO_APK_STORE_PASSWORD'
require_text rust/xtask/src/quality.rs 'pub fn preflight'
require_text native-bindings/module.yaml 'namespace: com.lomo.nativebridge'
require_text native-bindings/module.yaml 'allWarningsAsErrors: true'
require_text .gitignore '/native-bindings/src/'
require_text .gitignore '/app/jniLibs/'
require_text .githooks/pre-commit 'preflight'
require_text .githooks/pre-push 'just check'
if grep -Eq 'just ci' .githooks/pre-commit .githooks/pre-push; then
  fail "hooks must not invoke full just ci"
fi

for legacy in \
  quality/scripts/kotlin_fast_quality_check.sh \
  quality/scripts/kotlin_static_quality_check.sh \
  quality/scripts/kotlin_quality_check.sh \
  quality/scripts/kotlin_toolchain_env.sh \
  quality/scripts/rust_sync_core_check.sh \
  quality/scripts/generate_rust_sync_bindings.sh \
  quality/scripts/generate_rust_sync_android_libs.sh \
  quality/scripts/check_rust_sync_apk_packaging.sh \
  quality/scripts/ai_local_maintenance_check.sh \
  quality/scripts/verified_batch_commit.sh; do
  reject_path "$legacy"
done

if rg -n '28\.2\.13676358|liblomo_sync_ffi|com\.lomo\.rustsync' \
  --glob '!quality/scripts/test/kotlin_quality_check_contract_test.sh' \
  --glob '!rust/target/**' --glob '!build/**' --glob '!.git/**' . >/dev/null; then
  fail "old NDK, native library, or Kotlin package reference remains"
fi

for script in \
  quality/scripts/kotlin_detekt_check.sh \
  quality/scripts/kotlin_test_style_check.sh \
  quality/scripts/kotlin_android_lint_check.sh \
  quality/scripts/kotlin_compose_static_analysis.sh \
  quality/scripts/kotlin_coverage_check.sh \
  quality/scripts/kotlin_detekt_format.sh \
  .githooks/pre-commit \
  .githooks/pre-push; do
  bash -n "$script"
done

if command -v just >/dev/null 2>&1; then
  just --list >/dev/null
fi

echo "xtask-contract: ok"
