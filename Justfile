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

# Discover the canonical command protocol as JSON.
default:
    @{{xtask}} commands

commands:
    @{{xtask}} commands

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

# Verify the worktree diff; --plan prints the task graph, --tests-only skips static analysis.
dev *args:
    {{xtask}} dev {{args}}

# Hidden: path-aware push gate, invoked by .githooks/pre-push only.
_preflight remote="origin":
    {{xtask}} preflight push {{remote}}

# Run the iterative Rust + Kotlin quality gate.
check:
    {{xtask}} check

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

# Audit, prune stale Cargo artifacts, or clean repository-owned generated state.
# `prune` runs automatically inside `_preflight` before every push.
cache mode="audit":
    {{xtask}} cache {{mode}}

# Note: the `_preflight` push gate (run by .githooks/pre-push) already runs diff-scoped
# cargo-mutants on touched Rust crates. A full-workspace sweep is `cargo mutants` directly.
