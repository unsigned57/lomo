# Shell Contract

Capability: expose one pinned developer and CI entry through `Justfile` and `lomo-xtask`.

- Given a command invoked from the repository root, When Cargo starts xtask, Then `RUSTUP_TOOLCHAIN` is the exact channel from `rust-toolchain.toml`.
- Given missing required metrics or unstable repeated measurements, When `just perf` runs, Then it exits non-zero.

Observable outcomes: the declared Rust toolchain, one command graph, and fail-closed quality results.
Excludes: live credentials and device/provider environments. Report an unavailable required environment
as a verification blocker under [Quality](../../quality/README.md#gate-selection-and-evidence), not an invented success or status code.
