# Build cache cleanup decision

The cleanup hook lives in `lomo-xtask` (`just cache prune`). `just build android` and
`just build tui` run `prune` automatically before compiling; `reports/` lives at the repository root
outside disposable build output and is preserved by `just cache clean`.

## Dependency decision

Requirement: trim stale Cargo artifacts while preserving recent dependency builds, packages, and
reports, without racing an active compiler. Researched 2026-11-09 under pinned Rust; no product
dependency added.

| Candidate | Decision |
| --- | --- |
| [`cargo clean`](https://doc.rust-lang.org/cargo/commands/cargo-clean.html) | Used for disposable `llvm-cov-target` coverage trees and explicit full clean (`just cache clean`). |
| [`cargo-sweep 0.8.0`](https://github.com/holmgr/cargo-sweep/tree/v0.8.0) | Selected for fingerprint access-time pruning (`--time 7`). Pinned in `quality/tools.toml` and installed by `just bootstrap`. |
| `cargo-clean-all` | Rejected: deletes whole project target trees rather than trimming aged artifacts inside an active workspace. |
| `cargo-reclaim 0.6.1` | Deferred: active-process detection treats the enclosing `cargo run -p lomo-xtask` process as active and skips pruning. |

`cargo-sweep` upstream marks itself unmaintained; only its narrow `--time 7` fingerprint mode is
used, and toolchain-hash pruning is disabled. Reassess when changing Cargo's target layout or toolchain.

## Enforcement and retention

- **Locking and error handling**: acquires every Cargo profile `.cargo-lock` under `target/cargo`
  before pruning (never unlinks locks), stops on contention, verifies the exact `cargo-sweep`
  version, and fails on `[ERROR]`/`[WARN] Failed to remove:` diagnostics even on exit `0`.
- **Scratch and report bounds**: `prune` removes files under `target/lomo/tmp/` unmodified for 7 days
  (without following directory symlinks), keeps only the newest 10 `reports/verification/` runs, and
  removes stale unindexed directories under `reports/detekt/symbols/`.
- **Preserved state**: recent builds, deliverables under `target/lomo/artifacts/`, corpora,
  `device-perf-engine-baseline.v2.json`, installed SDKs, and caller global caches stay untouched.
- Direct `cargo build` and `./kotlin` invocations bypass xtask and do not run `prune`.
