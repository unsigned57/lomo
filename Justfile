set shell := ["bash", "-euo", "pipefail", "-c"]

# Pinned rustup channel from rust-toolchain.toml (evaluated when Justfile loads).
rust_channel := `awk '
  /^\[toolchain\]/ { in_tc = 1; next }
  /^\[/ { in_tc = 0 }
  in_tc && $1 == "channel" {
    gsub(/"/, "", $3)
    print $3
    exit
  }
' rust-toolchain.toml`

# The command and all child tool invocations use the repository's pinned Rust toolchain.
xtask := "RUSTUP_TOOLCHAIN=\"" + rust_channel + "\" cargo run --manifest-path Cargo.toml --locked -p lomo-xtask --"

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

# Path-aware commit gate (fmt/meaningful-tests are handled by the git hook). `push` mode
# compares pushed commits against the remote base and runs on pre-push.
preflight mode="staged" remote="origin":
    {{xtask}} preflight {{mode}} {{remote}}

# Run the iterative Rust + Kotlin quality gate.
check:
    {{xtask}} check

# Run the Linux host quality gate (independent host packages without Android/JNI dependencies).
check-linux:
    {{xtask}} check-linux

# Run the Linux TUI. Extra arguments are forwarded to lomo-tui (workspace path, --help, --version).
tui *args:
    {{xtask}} tui {{args}}

# Build a generic Linux x86_64 TUI archive under target/lomo/dist/.
package-linux:
    {{xtask}} package-linux

# Regenerate Kotlin bindings only.
bindings:
    {{xtask}} bindings

# Generate release native libraries and canonical Kotlin bindings.
native abi="arm64":
    {{xtask}} native {{abi}}

# Build and validate an Android debug or signed release APK.
android variant="debug" abi="arm64":
    {{xtask}} android {{variant}} {{abi}}

# Run the complete local/CI quality gate (coverage + fat-LTO release native).
ci:
    {{xtask}} ci

# Check or explicitly update dependencies.
deps mode="check":
    {{xtask}} deps {{mode}}

# Run planner, binary-size, and LLVM line diagnostics.
perf:
    {{xtask}} perf

# Audit or clean repository-owned generated state.
cache mode="audit":
    {{xtask}} cache {{mode}}

# Verify parity between Rust Store, StoreHandle FFI facade, and Kotlin StoreNativeBridge.
ffi-parity:
    {{xtask}} ffi-parity

# Verify reachability of domain UseCases from UI/app presentation and production pipelines.
usecase-reachability:
    {{xtask}} usecase-reachability

# Run cargo-mutants mutation testing on Rust storage and workspace core.
mutants *flags:
    {{xtask}} mutants {{flags}}
