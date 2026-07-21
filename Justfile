set shell := ["bash", "-euo", "pipefail", "-c"]

# Pinned rustup channel from rust/rust-toolchain.toml (evaluated when Justfile loads).
rust_channel := `awk '
  /^\[toolchain\]/ { in_tc = 1; next }
  /^\[/ { in_tc = 0 }
  in_tc && $1 == "channel" {
    gsub(/"/, "", $3)
    print $3
    exit
  }
' rust/rust-toolchain.toml`

# Repository-local cargo home + absolute target dir so nested boltffi/cargo never inherits a
# relative CARGO_TARGET_DIR into rust/native/rust/target (multi-GB accidental trees).
# RUSTUP_TOOLCHAIN is forced from the pin because this invocation runs from the repository root
# (rustup would otherwise ignore rust/rust-toolchain.toml and use the host default).
xtask := "CARGO_HOME=\"$PWD/.cache/cargo-home\" CARGO_TARGET_DIR=\"$PWD/rust/target/xtask-host\" RUSTUP_TOOLCHAIN=\"" + rust_channel + "\" cargo run --manifest-path rust/Cargo.toml --locked -p lomo-xtask --"

# Show the canonical Lomo command surface.
default:
    @just --list

# Install the pinned Rust tools, targets, and Android NDK.
bootstrap:
    {{xtask}} bootstrap

# Rewrite the repository Rust pin (channel + msrv + docs/CI keys). Does not claim gates green.
# Example: `just rust-toolchain-bump 1.97` or `just rust-toolchain-bump 1.97 --dry-run`
rust-toolchain-bump channel *flags:
    {{xtask}} rust-toolchain-bump {{channel}} {{flags}}

# Format staged/all sources or verify formatting.
fmt mode="staged":
    {{xtask}} fmt {{mode}}

# Run Rust and Kotlin host tests.
test:
    {{xtask}} test

# Path-aware commit gate (fmt/meaningful-tests are handled by the git hook).
preflight:
    {{xtask}} preflight

# Run the iterative Rust + Kotlin quality gate.
check:
    {{xtask}} check

# Generate release native libraries and UniFFI Kotlin bindings.
native:
    {{xtask}} native

# Build and validate an Android debug or signed release APK.
android variant="debug":
    {{xtask}} android {{variant}}

# Run the complete local/CI quality gate (coverage + fat-LTO release native).
ci:
    {{xtask}} ci

# Install and execute the native planner smoke app on an attached API 26 x86_64 device.
device-smoke:
    {{xtask}} device-smoke

# Check or explicitly update dependencies.
deps mode="check":
    {{xtask}} deps {{mode}}

# Run planner, binary-size, and LLVM line diagnostics.
perf:
    {{xtask}} perf

# Audit or clean repository-owned generated state.
cache mode="audit":
    {{xtask}} cache {{mode}}
