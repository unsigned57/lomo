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
# - Given detekt ships a fat ktlint wrapper, when formatting resolves plugins, then the wrapper is
#   sufficient without an obsolete separately packaged ktlint artifact.
# Observable outcomes: missing canonical wiring, retained legacy orchestration, or rejected current
# detekt packaging fails this script.
# TDD proof: failed before xtask because the old Kotlin/Rust shell gates and NDK 28 remained; RED on
# 2026-08-09 because formatting required ktlint-repackage 2.0.0-alpha.6, which was never published.
# Excludes: compiling product code and device runtime behavior.

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
  Cargo.toml \
  rust-toolchain.toml \
  tools.toml \
  crates/lomo-xtask/src/quality.rs \
  crates/lomo-xtask/src/native.rs \
  crates/lomo-xtask/src/android.rs \
  .githooks/pre-commit \
  .githooks/pre-push; do
  [ -f "$file" ] || fail "required file missing: $file"
done

require_text Justfile 'cargo run --manifest-path Cargo.toml --locked -p lomo-xtask --'
for command in bootstrap fmt test preflight check check-linux tui package-linux native android ci deps perf cache rust-toolchain-bump; do
  grep -Eq -- "^${command}([[:space:]].*)?:$" Justfile || fail "Justfile recipe missing: $command"
done
if grep -Eq '^(device-smoke|sync-provider-smoke)([[:space:]].*)?:$' Justfile; then
  fail "retired smoke recipes remain in Justfile"
fi
if grep -Fq 'native-smoke' Justfile apps/android/project.yaml .gitignore; then
  fail "native-smoke composition root remains in the public command or module surface"
fi
reject_path apps/android/native-smoke
if rg -n --glob '!quality/scripts/test/kotlin_quality_check_contract_test.sh' \
  --glob '!target/**' --glob '!.git/**' \
  'repo_root/build/(reports|jacoco|apk|dist|corpora)' quality/scripts >/dev/null; then
  fail "Kotlin policy scripts still write generated reports under repository-root build/"
fi

channel="$(
  awk '
    /^\[toolchain\]/ { in_tc = 1; next }
    /^\[/ { in_tc = 0 }
    in_tc && $1 == "channel" {
      gsub(/"/, "", $3)
      print $3
      exit
    }
  ' rust-toolchain.toml
)"
[ -n "${channel}" ] || fail "rust-toolchain.toml missing channel"
msrv="$(printf '%s' "${channel}" | awk -F. '{ print $1 "." $2 }')"
[ -n "${msrv}" ] || fail "unable to derive msrv from channel ${channel}"
case "${channel}" in
  stable|beta|nightly|stable-*|beta-*|nightly-*)
    fail "floating Rust channel is forbidden: ${channel}"
    ;;
esac

require_text Cargo.toml "rust-version = \"${msrv}\""
require_text Cargo.toml 'license = "GPL-3.0-only"'
require_text Cargo.toml 'warnings = "deny"'
require_text Cargo.toml 'pedantic = "deny"'
require_text Cargo.toml '[profile.release-ci]'
require_text rust-toolchain.toml "channel = \"${channel}\""
require_text crates/lomo-xtask/src/rust_pin.rs 'rust-toolchain.toml'
require_text crates/lomo-xtask/src/rust_pin.rs 'pub fn bump'
require_text crates/lomo-xtask/src/tools.rs 'rust_pin::load'
if grep -Eq 'command\.args\(\[[[:space:]]*"\+[0-9]' crates/lomo-xtask/src/tools.rs; then
  fail "crates/lomo-xtask/src/tools.rs must not hard-code cargo +channel literals"
fi
require_text crates/lomo-xtask/src/workspace.rs '29.0.14206865'
require_text crates/lomo-xtask/src/workspace.rs 'fn lomo_output_dir'
require_text crates/lomo-xtask/src/native.rs 'liblomo_native_jni.so'
require_text crates/lomo-xtask/src/native.rs 'Abi::ALL'
require_text crates/lomo-xtask/src/native.rs 'ReleaseCi'
require_text crates/lomo-xtask/src/android.rs 'assets/dexopt/baseline.prof'
require_text crates/lomo-xtask/src/android.rs 'env:LOMO_APK_STORE_PASSWORD'
require_text crates/lomo-xtask/src/quality.rs 'pub fn preflight'
require_text apps/android/native-bindings/module.yaml 'namespace: com.lomo.nativebridge'
require_text apps/android/native-bindings/module.yaml 'allWarningsAsErrors: true'
require_text .gitignore '/apps/android/native-bindings/src/'
require_text .gitignore '/apps/android/app/jniLibs/'
require_text .githooks/pre-commit 'preflight'
require_text .githooks/pre-push 'preflight push'
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
  --glob '!target/**' --glob '!build/**' --glob '!.git/**' . >/dev/null; then
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

(
  format_contract_dir="$(mktemp -d /tmp/lomo-format-contract.XXXXXX)"
  trap 'rm -rf -- "$format_contract_dir"' EXIT
  wrapper_dir="$format_contract_dir/gradle/caches/modules-2/files-2.1/dev.detekt/detekt-rules-ktlint-wrapper/2.0.0-alpha.6/fat"
  wrapper_classes="$format_contract_dir/wrapper-classes"
  build_dir="$format_contract_dir/build"
  cache_dir="$format_contract_dir/cache"
  mkdir -p \
    "$wrapper_dir" \
    "$wrapper_classes/com/pinterest/ktlint/rule/engine/core/api" \
    "$build_dir/tasks/_detekt-rules_jarJvm" \
    "$cache_dir/lomo/detekt" \
    "$format_contract_dir/bin" \
    "$format_contract_dir/home"
  : > "$wrapper_classes/com/pinterest/ktlint/rule/engine/core/api/Rule.class"
  jar cf \
    "$wrapper_dir/detekt-rules-ktlint-wrapper-2.0.0-alpha.6.jar" \
    -C "$wrapper_classes" .
  : > "$build_dir/tasks/_detekt-rules_jarJvm/detekt-rules-jvm.jar"
  : > "$cache_dir/lomo/detekt/detekt-cli-2.0.0-alpha.6-all.jar"
  printf 'package contract\n' > "$format_contract_dir/Sample.kt"
  ln -s "$(type -P true)" "$format_contract_dir/bin/java"

  PATH="$format_contract_dir/bin:$PATH" \
    HOME="$format_contract_dir/home" \
    USER=lomo-format-contract-no-host \
    GRADLE_USER_HOME="$format_contract_dir/gradle" \
    XDG_CACHE_HOME="$cache_dir" \
    LOMO_KOTLIN_BUILD_DIR="$build_dir" \
    LOMO_KOTLIN_WRAPPER=/bin/false \
    quality/scripts/kotlin_detekt_format.sh files "$format_contract_dir/Sample.kt" >/dev/null
) || fail "detekt formatting must accept a fat ktlint wrapper without ktlint-repackage"

echo "xtask-contract: ok"
