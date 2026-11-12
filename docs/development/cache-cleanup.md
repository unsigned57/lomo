# Build cache cleanup decision

The build hook lives in `lomo-xtask`, the existing build owner. `just build android` and
`just build tui` invoke the same operation as `just cache prune` before compiling.
Reports live outside disposable output at repository-root `reports/` and remain ignored by Git.

## Dependency decision

Requirement: trim unused Cargo outputs while retaining recent dependency builds, honor the
configured Cargo target, preserve packages/reports, and avoid racing an active compiler.
Research on 2026-11-09 covered upstream documentation and source, followed by real CLI tests
under the repository's pinned Rust 1.98. No product dependency is added.

| Candidate | Decision |
| --- | --- |
| [Cargo clean](https://doc.rust-lang.org/cargo/commands/cargo-clean.html), supplied by the pinned toolchain | Use for disposable coverage build trees and explicit full cleanup. A whole/profile clean does not provide age-based retention. |
| [cargo-sweep 0.8.0](https://github.com/holmgr/cargo-sweep/tree/v0.8.0) | Use the existing artifact classifier and fingerprint access-time retention with `--time 7`. Pin through `quality/tools.toml` and install via `just bootstrap`. |
| [cargo-clean-all](https://github.com/dnlmlr/cargo-clean-all) | Reject for this hook: project-level target deletion cannot trim old artifacts inside an actively developed workspace while preserving its recent cache. |
| [cargo-reclaim 0.6.1](https://github.com/ml-rust/cargo-reclaim) | Defer for this invocation model: its active-process protection treats the enclosing `cargo run -p lomo-xtask` as an active project process, so a pre-build run cannot trim the target without bypassing that protection. |

The cargo-sweep upstream currently declares itself unmaintained. This is a deliberate limitation
of the selected integration, not a claim of ongoing upstream support. Its narrow required mode
is exercised against the current Cargo layout. Reassess it when changing Cargo's build layout or
toolchain. Do not enable toolchain-hash pruning or assume compatibility from its exit status alone.

## Enforcement

The adapter acquires existing Cargo profile locks before running the tool, never unlinks those
locks, and stops on contention. It checks the exact binary version and rejects cargo-sweep's
`[ERROR]` diagnostics even when upstream exits zero. There is no custom Cargo-artifact deletion
fallback. Child output goes to stderr; xtask stdout remains one JSON result.

Only Lomo's command scratch retention is local code: files beneath the configured `tmp/` root
expire after seven days without modification. Directory symlinks are not followed. Recent scratch,
packaged deliverables, generated corpora, installed SDKs and user dependency caches remain.
Cargo's own global-cache GC retains its normal toolchain policy.

`just cache clean` is the explicit full build cleanup. It preserves repository-root reports.
Direct `cargo build` and `./kotlin` invocations do not run the xtask hook.

Kotlin formatting similarly resolves the existing Detekt ktlint wrapper from its
[official publication](https://detekt.dev/docs/rules/ktlint/), using the shared Detekt version,
a pinned artifact digest and the standard user cache. It no longer searches retired project
Gradle caches or requires a separately packaged ktlint engine.
