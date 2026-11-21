#!/usr/bin/env bash
set -euo pipefail

# Contract test: lomo-xtask and Justfile own quality orchestration, Detekt rejects
# unverified plugin jars, and policy scripts keep outputs out of repository-root build/.

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"
# shellcheck source=quality/scripts/kotlin_detekt_env.sh
source "$repo_root/quality/scripts/kotlin_detekt_env.sh"

fail() {
  echo "xtask-contract: $*" >&2
  exit 1
}

require_text() {
  local file="$1"
  local text="$2"
  grep -Fq -- "$text" "$file" || fail "$file is missing: $text"
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
if grep -REq 'repo_root/build/(reports|jacoco|apk|dist|corpora)' quality/scripts \
  --exclude='kotlin_quality_check_contract_test.sh'; then
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
require_text apps/android/app/module.yaml 'signing:'
require_text apps/android/native-bindings/module.yaml 'namespace: com.lomo.nativebridge'
require_text apps/android/native-bindings/module.yaml 'allWarningsAsErrors: true'
require_text .gitignore '/apps/android/native-bindings/src/'
require_text .gitignore '/apps/android/app/jniLibs/'
require_text .githooks/pre-commit 'fmt staged'
require_text .githooks/pre-push '_preflight'
if grep -Eq 'just ci' .githooks/pre-commit .githooks/pre-push; then
  fail "hooks must not invoke full just ci"
fi

for script in \
  quality/scripts/check_string_resource_parity.sh \
  quality/scripts/kotlin_detekt_env.sh \
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
  local dir="$1" real_wrapper real_coroutines real_cli_jar
  mkdir -p "$dir/cache/lomo/detekt" "$dir/home"
  real_wrapper="$(lomo_detekt_ktlint_jar)"
  cp "$real_wrapper" "$dir/cache/lomo/detekt/detekt-rules-ktlint-wrapper-${DETEKT_VERSION}.jar"
  real_coroutines="$(lomo_detekt_coroutines_jar)"
  cp "$real_coroutines" "$dir/cache/lomo/detekt/detekt-rules-coroutines-${DETEKT_VERSION}.jar"
  # The engine jar must carry the pinned digest — format resolves and verifies it
  # before any batch. Seed the real verified jar (resolved under the normal environment)
  # as a symlink; the run executes it for real, because the formatter's canary must be
  # reported by the actual ktlint rules (a stubbed JVM would make the contract vacuous).
  real_cli_jar="$(lomo_detekt_cli_jar)" || fail "cannot resolve the pinned detekt CLI jar"
  ln -s "$real_cli_jar" \
    "$dir/cache/lomo/detekt/detekt-cli-${DETEKT_VERSION}-all.jar"
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
    "$tamper_dir/cache/lomo/detekt/detekt-rules-ktlint-wrapper-${DETEKT_VERSION}.jar"
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
