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

# Verify every JDK/SDK/NDK/serialization copy equals its declaration site
# (apps/android/app/module.yaml + crates/lomo-xtask/src/workspace.rs).
kotlin-toolchain-check:
    {{xtask}} kotlin-toolchain-check

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

# Build and validate an application target: `just build android` produces the APK
# (debug arm64 by default; `release` signs), `just build tui` produces the desktop `lomo`
# binary (debug by default; `release` uses the shipping profile).
build *args:
    {{xtask}} build {{args}}

# Run the complete local/CI quality gate (coverage + fat-LTO release native).
ci:
    {{xtask}} ci

# Check or explicitly update dependencies.
deps mode="check":
    {{xtask}} deps {{mode}}

# Run committed Maestro UI flows on an attached device (or --redroid provisions
# the pinned container).
uitest *args:
    {{xtask}} uitest {{args}}

# Snapshot the current device screen: PNG + view hierarchy + component index
# (name optional: `just uishot settings` or `just uishot --redroid`).
uishot *args:
    {{xtask}} uishot {{args}}

# Manage the pinned redroid container: `just redroid up|down|status`.
# `--redroid` on uitest/uishot/benchmark/perf provisions it implicitly per run;
# this command owns the standing state (ANDROID_SERIAL selects a device when
# several are attached; LOMO_REDROID_GPU_MODE=guest on GPU-less hosts).
redroid *args:
    {{xtask}} redroid {{args}}

# Run committed PTY interaction flows against the lomo TUI binary.
tuitest *args:
    {{xtask}} tuitest {{args}}

# Run planner, binary-size, and LLVM line diagnostics. `just perf device [startup|cuj|engine|
# system|profile|release|all]... [--corpus device] [--backend saf|direct] [--redroid] [--trace]`
# measures an attached device (emulator+debuggable relative evidence, never release truth).
perf *args:
    {{xtask}} perf {{args}}

# Audit, prune stale Cargo artifacts, or clean repository-owned generated state.
# `prune` runs automatically inside `_preflight` before every push.
cache mode="audit":
    {{xtask}} cache {{mode}}
