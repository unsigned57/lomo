set shell := ["bash", "-euo", "pipefail", "-c"]

# Repository-local cargo home + absolute target dir so nested boltffi/cargo never inherits a
# relative CARGO_TARGET_DIR into rust/native/rust/target (multi-GB accidental trees).
xtask := "CARGO_HOME=\"$PWD/.cache/cargo-home\" CARGO_TARGET_DIR=\"$PWD/rust/target/xtask-host\" cargo run --manifest-path rust/Cargo.toml --locked -p lomo-xtask --"

# Show the canonical Lomo command surface.
default:
    @just --list

# Install the pinned Rust tools, targets, and Android NDK.
bootstrap:
    {{xtask}} bootstrap

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
