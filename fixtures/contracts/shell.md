# Shell Contract

Capability: expose one pinned developer and CI entry through `Justfile` and `lomo-xtask`.

- Given a command invoked from the repository root, When Cargo starts xtask, Then `RUSTUP_TOOLCHAIN` is the exact channel from `rust/rust-toolchain.toml`.
- Given missing required metrics or unstable repeated measurements, When `just perf` runs, Then it exits non-zero.

Observable outcomes: Rust 1.97, one command graph, and fail-closed quality results.
Excludes: credentials and unavailable device/provider environments, which remain `pending_env`.
