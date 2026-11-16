#!/usr/bin/env bash
set -euo pipefail

# Behavior Contract
# Capability: prove xtask is the only public Rust/Kotlin/native/Android quality orchestrator.
# Scenarios:
# - Given no project-local Gradle cache, when formatting, then the verified official wrapper loads.
# - Given public commands, when Justfile and hooks are inspected, then they call only lomo-xtask.
# - Given native inputs, when configuration is inspected, then the Rust channel pin
#   (rust-toolchain.toml + matching rust-version), NDK 29, BoltFFI JNI library identity,
#   four Android ABIs, and ignored generated outputs are fixed at the owning boundary.
# - Given old workflow tails, when the repository is inspected, then none remain.
# - Given detekt ships a fat ktlint wrapper, when formatting resolves plugins, then the wrapper is
#   sufficient without an obsolete separately packaged ktlint artifact.
# Observable outcomes: missing canonical wiring, retained legacy orchestration, or rejected current
# detekt packaging fails this script.
# Cleanup regression RED: just fmt all failed because the ktlint wrapper was only in a deleted project cache.
# GREEN: same command resolves the official plugin in the standard user cache.
# TDD proof: failed before xtask because the old Kotlin/Rust shell gates and NDK 28 remained; RED on
# 2026-08-09 because formatting required ktlint-repackage 2.0.0-alpha.6, which was never published.
# Excludes: compiling product code and device runtime behavior.
# Test Change Justification (signing owner):
# Reason category: signing belongs to the toolchain module configuration.
# Old behavior/assertion being replaced: android.rs must contain env:LOMO_APK_STORE_PASSWORD.
# Why old assertion is no longer correct: Amper signs from keystore.properties via module.yaml.
# Coverage preserved by: signing configuration and post-build signature validation pins, plus
# the runtime android_signing_contract matrix for missing/incomplete/valid configuration.
# Why this is not fitting the test to the implementation: the release contract already assigns
# signing to settings.android.signing; retaining an independent env signing path violates it.
# Test Change Justification:
# Reason category: unified application build entrypoint (`just build <app>`).
# Old behavior/assertion being replaced: a top-level `android` recipe is required.
# Why old assertion is no longer correct: per-app recipes retired; `build` now dispatches to
# android and tui targets through the single xtask registry.
# Coverage preserved by: `build` is required in the recipe list and every other quality
# command remains required; retired per-app recipes stay rejected.
# Why this is not fitting the test to the implementation: the contract still proves the
# Justfile exposes each public command and delegates only to lomo-xtask.

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

# Repository text search through Git itself, so the probe exists wherever the checkout does.
# `git grep` exits 0 on a match and 1 on none; any other status is a broken probe, never a pass.
source_matches() {
  local status=0
  git grep --untracked -q -E "$@" || status=$?
  case "$status" in
    0) return 0 ;;
    1) return 1 ;;
    *) fail "repository search failed (exit $status): $*" ;;
  esac
}

for file in \
  Justfile \
  Cargo.toml \
  rust-toolchain.toml \
  quality/tools.toml \
  crates/lomo-xtask/src/quality.rs \
  crates/lomo-xtask/src/native.rs \
  crates/lomo-xtask/src/android.rs \
  .githooks/pre-commit \
  .githooks/pre-push; do
  [ -f "$file" ] || fail "required file missing: $file"
done

require_text Justfile 'cargo run --manifest-path Cargo.toml --locked -p lomo-xtask --'
for command in commands bootstrap fmt dev check bindings native build ci deps perf cache rust-toolchain-bump; do
  grep -Eq -- "^${command}([[:space:]].*)?:$" Justfile || fail "Justfile recipe missing: $command"
done
require_text Justfile '_preflight'
for retired in install-tui test tui package-linux mutants check-linux usecase-reachability \
  device-smoke sync-provider-smoke; do
  if grep -Eq -- "^${retired}([[:space:]].*)?:$" Justfile; then
    fail "retired recipe remains in Justfile: ${retired}"
  fi
done
if grep -Fq 'native-smoke' Justfile apps/android/project.yaml .gitignore; then
  fail "native-smoke composition root remains in the public command or module surface"
fi
reject_path apps/android/native-smoke
if source_matches -e 'repo_root/build/(reports|jacoco|apk|dist|corpora)' -- quality/scripts \
  ':(exclude)quality/scripts/test/kotlin_quality_check_contract_test.sh'; then
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
require_text crates/lomo-xtask/src/workspace.rs '30.0.16248370'
require_text crates/lomo-xtask/src/workspace.rs 'fn lomo_output_dir'
require_text crates/lomo-xtask/src/native.rs 'liblomo_native_jni.so'
require_text crates/lomo-xtask/src/native.rs 'Abi::ALL'
require_text crates/lomo-xtask/src/native.rs 'ReleaseCi'
require_text crates/lomo-xtask/src/android.rs 'assets/dexopt/baseline.prof'
require_text apps/android/app/module.yaml 'signing:'
require_text crates/lomo-xtask/src/android.rs 'validate_signing_config'
require_text crates/lomo-xtask/src/android.rs 'verify_signature'
require_text crates/lomo-xtask/src/android.rs 'keystore.properties'
require_text crates/lomo-xtask/src/quality.rs 'pub fn preflight'
require_text apps/android/native-bindings/module.yaml 'namespace: com.lomo.nativebridge'
require_text apps/android/native-bindings/module.yaml 'allWarningsAsErrors: true'
require_text .gitignore '/apps/android/native-bindings/src/'
require_text .gitignore '/apps/android/app/jniLibs/'
require_text .githooks/pre-commit 'preflight'
require_text .githooks/pre-push 'preflight'
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
  quality/scripts/verified_batch_commit.sh \
  quality/scripts/kotlin_compose_static_analysis.sh; do
  reject_path "$legacy"
done

if source_matches -e '28\.2\.13676358|liblomo_sync_ffi|com\.lomo\.rustsync' -- . \
  ':(exclude)quality/scripts/test/kotlin_quality_check_contract_test.sh' \
  ':(exclude)target' ':(exclude)build'; then
  fail "old NDK, native library, or Kotlin package reference remains"
fi

for script in \
  quality/scripts/kotlin_detekt_check.sh \
  quality/scripts/kotlin_test_style_check.sh \
  quality/scripts/kotlin_android_lint_check.sh \
  quality/scripts/kotlin_coverage_check.sh \
  quality/scripts/kotlin_detekt_format.sh \
  .githooks/pre-commit \
  .githooks/pre-push; do
  bash -n "$script"
done

if command -v just >/dev/null 2>&1; then
  just --dump --dump-format json >/dev/null
fi

# Seed verified published plugins into the standard cache; no project Gradle cache is needed.
seed_format_fixture() {
  local dir="$1" real_wrapper real_coroutines
  mkdir -p "$dir/cache/lomo/detekt" "$dir/home"
  real_wrapper="$(REPO_ROOT="$repo_root" bash -c \
    'source "$REPO_ROOT/quality/scripts/kotlin_detekt_env.sh" && lomo_detekt_ktlint_jar')"
  cp "$real_wrapper" "$dir/cache/lomo/detekt/detekt-rules-ktlint-wrapper-2.0.0-alpha.6.jar"
  real_coroutines="$(REPO_ROOT="$repo_root" bash -c \
    'source "$REPO_ROOT/quality/scripts/kotlin_detekt_env.sh" && lomo_detekt_coroutines_jar')"
  cp "$real_coroutines" "$dir/cache/lomo/detekt/detekt-rules-coroutines-2.0.0-alpha.6.jar"
  # The engine jar must carry the pinned digest — format resolves and verifies it
  # before any batch. Seed the real verified jar (resolved under the normal environment)
  # as a symlink; the run executes it for real, because the formatter's canary must be
  # reported by the actual ktlint rules (a stubbed JVM would make the contract vacuous).
  local real_cli_jar
  real_cli_jar="$(REPO_ROOT="$repo_root" bash -c \
    'source "$REPO_ROOT/quality/scripts/kotlin_detekt_env.sh" && lomo_detekt_cli_jar')" \
    || fail "cannot resolve the pinned detekt CLI jar"
  ln -s "$real_cli_jar" \
    "$dir/cache/lomo/detekt/detekt-cli-2.0.0-alpha.6-all.jar"
  printf 'package contract\n\nclass Sample\n' > "$dir/Sample.kt"
}

run_format_against_fixture() {
  local dir="$1"
  HOME="$dir/home" \
    USER=lomo-format-contract-no-host \
    GRADLE_USER_HOME="$dir/gradle" \
    XDG_CACHE_HOME="$dir/cache" \
    LOMO_KOTLIN_BUILD_DIR="$dir/build" \
    LOMO_KOTLIN_WRAPPER=/bin/false \
    quality/scripts/kotlin_detekt_format.sh files "$dir/Sample.kt"
}

(
  format_contract_dir="$(mktemp -d /tmp/lomo-format-contract.XXXXXX)"
  trap 'rm -rf -- "$format_contract_dir"' EXIT
  seed_format_fixture "$format_contract_dir"
  run_format_against_fixture "$format_contract_dir" >/dev/null
) || fail "detekt formatting must accept a fat ktlint wrapper without ktlint-repackage"

(
  tamper_dir="$(mktemp -d /tmp/lomo-format-tamper.XXXXXX)"
  trap 'rm -rf -- "$tamper_dir"' EXIT
  seed_format_fixture "$tamper_dir"
  printf 'gate-state garbage, not a zip archive\n' > \
    "$tamper_dir/cache/lomo/detekt/detekt-rules-ktlint-wrapper-2.0.0-alpha.6.jar"
  if run_format_against_fixture "$tamper_dir" >"$tamper_dir/out.log" 2>&1; then
    echo "xtask-contract: formatting accepted an unverifiable ktlint wrapper jar" >&2
    exit 1
  fi
  grep -q "not a readable zip" "$tamper_dir/out.log" || {
    echo "xtask-contract: tampered wrapper rejected for the wrong reason:" >&2
    cat "$tamper_dir/out.log" >&2
    exit 1
  }
) || fail "detekt formatting must reject a plugin jar that fails verification"

echo "xtask-contract: ok"
