# Quality Contract

Read this file for command output, gate selection, canonical build inputs, and failure triage.
[AGENTS.md](../AGENTS.md) owns task scope and completion; [ARCHITECTURE.md](../ARCHITECTURE.md) owns
module authority; [Meaningful tests](testing/meaningful-tests.md) owns test evidence;
[Release](release.md) owns signing and publishing.

## Command protocol

Run commands from the repository root. `just` and `just commands` emit the canonical command catalog
generated from `lomo-xtask`. Every xtask invocation writes exactly one JSON document to stdout:

```json
{"schema_version":1,"command":"cache","status":"succeeded","data":{"paths":{"cargo_target":"/checkout/target/cargo"}}}
```

- `schema_version` identifies the result envelope; consumers must reject unsupported versions.
- `status` is `succeeded` or `failed`. Exit `0` does not promote a `--plan`, diagnostic, or scoped run
  into an integration handoff.
- `data` is command-specific (`null` when empty or failed before producing evidence). Failed commands
  populate `error.message` and `error.causes`, exit nonzero, and retain any produced `data`.
- Progress and child-tool diagnostics go to stderr; child commands cannot read interactive stdin.
  Keep stdout and stderr separate (never merge with `2>&1`).
- Missing or malformed stdout JSON (e.g., Cargo failed to build xtask, or process killed) is
  incomplete, never success.

| Operation | `data` |
| --- | --- |
| `just` / `just commands` | command names, arguments, purposes, and protocol location |
| `just dev --plan` | task graph, selected scope, `complete_worktree`, `excluded_owners` |
| `just dev`, `just check` | `plan`, `results`, `report_path`, task-id-to-file `logs` |
| push hook (`_preflight`) | `policy_only: true`, comparison `base`, formatting/policy results (not test evidence) |
| `just ci` | completed `verification` evidence and published `apk` |
| `just cache paths` / `audit` / `prune` / `clean` | resolved absolute paths, byte counts (`present`/`absent`), sweep/prune counts, or removed paths |
| `just build android\|tui` / `native` / `bindings` | published APK, `lomo` binary, or generated output directory |
| `just build android benchmark` / `just perf device` | see [Android performance](android-performance.md) |
| `just uitest` / `just uishot` | `android-uitest.v1.json` or `uisnap.v1.json` + screenshots/hierarchies/logs ([Android UI test](android-uitest.md)) |
| `just tuitest` | `tui-uitest.v1.json` + PTY screen snapshots/logs ([TUI UI test](tui-uitest.md)) |
| `just perf` | feasibility baseline JSON paths and measured conclusion |

Task statuses are `Passed`, `Failed`, `Cancelled`, and `NotScheduled`. `report_path` records the
durable evidence of that run, not a reusable success cache.

## Gate selection and evidence

Classify the actual change before running checks:

| Task or changed surface | Required evidence |
| --- | --- |
| Read-only review or investigation | Findings and inspected evidence; run tests only to establish a finding |
| Prose and documentation | Verify content, local links, referenced paths/commands, and `git diff --check` |
| Routine source or UI change | Direct evidence (resource check, screen snapshot, or targeted compile/smoke check); add a focused test if a consequential risk remains |
| Critical guarantee, consequential behavior, or executable rule | [Behavior evidence](testing/meaningful-tests.md#decide-what-the-change-owes) at the owner; run the smallest relevant target or flow, widening only for additional affected behavior |
| Test cleanup | Run the surviving owning suite and any cited covering scenarios |
| Broad module refactor | Affected suites and static checks, or `just dev` (`--scope <owner>` when isolated) |
| Native, FFI, lock, or packaging inputs | Regenerate and validate the affected generated/packaged surface |
| Dependency manifests | `just deps check`; additions/replacements follow [Dependency selection](dependencies.md) |
| Shared verification graph or toolchain | `just check` plus applicable generated/pack evidence |
| Pre-push / feature branch integration | Pre-push policy checks, then CI `check` for the exact commit |
| Workflow-only path/cache edits | Workflow syntax, targeted selection/cache regression tests, and affected architecture contract |
| Shared-branch merge / delivery | Green GitHub `quality` aggregation for the commit, or local `just ci` when remote CI is unavailable |

Focused commands:
- Rust scenario: `cargo test -p <crate> --locked --test <target> <case-filter>`
- Rust package lint: `cargo clippy -p <crate> --all-targets --locked -- -D warnings`
- Kotlin spec/module: `./kotlin test --include-module=<module> --include-classes=<fqcn>` (see [Kotlin focused runs](testing/kotlin-test-style.md#focused-local-runs))
- Worktree scoping: `--scope <owner>` excludes unrelated owners; inspect `complete_worktree` and `excluded_owners` when reporting scope.

| Gate | Includes | Intentionally omits |
| --- | --- | --- |
| pre-commit | staged `.rs`/`.kt`/`.kts` formatting (skips prose-only commits) | compile, test, native |
| `just dev` | affected worktree owners (including untracked/deleted files); `--tests-only` skips static checks | coverage, fat LTO |
| `just _preflight` (pre-push) | quick policy contracts, string parity, affected-language formatting | product compile/tests, cache prune, coverage, native |
| `just check` | quick contracts, Rust fmt/Clippy/nextest/rustdoc/machete, architecture + FFI contract, dev bindings, Kotlin format, Detekt light/full, test style, Android Lint, shell contracts, host Kotlin tests, baseline profile syntax/receipt | doctests, property fuzz, cargo-deny, coverage, packaged Kotlin build, fat-LTO release native, device execution |
| `just ci` | `check` + doctests, property fuzz, cargo-deny, Rust/Kotlin coverage, 4-ABI release native, packaged Kotlin build, APK/ELF/dependency checks | device execution |

The verification DAG dispatches continuously once prerequisites pass and resource locks (`Cargo`,
`KotlinBuild`, `Bindings`, `Reports`) are free. Policy self-tests run first. Any failure cancels
queued tasks while allowing already-running processes to finish and clean up; the final error names
the failed task rather than cancelled peers. Zero-test results in a required test task, input drift,
and missing outputs fail closed.

### CI and delivery pipeline

| Stage | Trigger / runner | What runs |
| --- | --- | --- |
| Commit / push | `.githooks/pre-commit`, `.githooks/pre-push` | Staged formatting (`fmt staged`), then `_preflight` policy/formatting checks |
| PR / feature branch | `.github/workflows/architecture_checks.yml` | `changes` runs compiler-free parity checks before bootstrap, skips builds on pure prose outside executable paths, otherwise runs `check` + affected delivery lanes |
| Default branch / manual | `.github/workflows/architecture_checks.yml` | Complete `check`, `rust-host` (`ci-rust delivery`), 4-ABI `native` (`release-ci`), `android-kotlin` (`ci-android coverage`), and `quality` aggregation |
| Release tag | `.github/workflows/android_release.yml`, `tui_release.yml` | See [Release](release.md) |

CI caches cargo tools by pin alone and keys build caches by toolchain/manifests plus `run_id`/`run_attempt`,
restoring the newest prefix snapshot and saving with `!cancelled()` so failed attempts retain
completed compilation. Workflows invoke `lomo-xtask` rather than maintaining a second build graph.

## Canonical build inputs

Read versions from their authoritative manifests; never duplicate pins elsewhere:

| Fact | Authoritative input |
| --- | --- |
| Rust channel and MSRV | [`rust-toolchain.toml`](../rust-toolchain.toml) + [`Cargo.toml`](../Cargo.toml); bump via `just rust-toolchain-bump` |
| JDK, Kotlin, Android SDKs, app version | [`apps/android/app/module.yaml`](../apps/android/app/module.yaml); sibling copies checked by `just kotlin-toolchain-check` (including workflow `java-version` / `ndk`) |
| Cargo tools and BoltFFI CLI | [`tools.toml`](tools.toml), installed by `just bootstrap` |
| Dependencies and capability catalogue | Manifests/lockfiles + [`dependency-capabilities.toml`](dependency-capabilities.toml) |
| NDK, native API, ABIs, profiles | [`workspace.rs`](../crates/lomo-xtask/src/workspace.rs) and [`native.rs`](../crates/lomo-xtask/src/native.rs) |
| Rust coverage threshold | `RUST_COVERAGE_MINIMUM` in [`quality.rs`](../crates/lomo-xtask/src/quality.rs) |
| Detekt, Android Lint, Rust deny | [`detekt/config`](detekt/config), [`android/lint.xml`](android/lint.xml), [`rust/deny.toml`](rust/deny.toml) |

`just native` and `just build android debug|release` default to `arm64` (`all` builds four ABIs).
No production dual stack, compatibility alias, or UniFFI fallback is permitted.

## Policy coverage and limits

- **Rust**: denies warnings, `unsafe_code`, unused `must_use`, and workspace Clippy lints; `cargo-deny`
  validates advisories/sources/licenses; `cargo-machete` detects unused dependencies.
- **Architecture locks**: [`lomo-architecture-tests`](../crates/lomo-architecture-tests/tests)
  validates Cargo metadata, Amper dependency scopes, capability boundaries, host/JNI isolation, and
  Rust syntax (including untracked files).
- **Kotlin static analysis**: `kotlin_detekt_check.sh` runs both `light` (syntax) and `full`
  (type-resolved via `kotlin_analysis_input.py`) modes, each guarded by real CLI canary tests
  (`detekt_activation_contract_test.sh`, `detekt_full_analysis_contract_test.sh`). Baselines and
  source `@Suppress` annotations cannot bypass ownership rules. Kotlin formatting resolves the
  official Detekt ktlint wrapper into the standard user cache.
- **Android Lint**: runs against the packaged runtime module closure without widening `app`'s compile
  classpath (`android_lint_runtime_contract_test.py`).
- **Behavior exceptions**: `// behavior-contract: <id>: <reason>` markers require a registered rule
  owner and a valid domain reason backed by tests ([UDF contract](udf-contract.md)); static checks
  cannot prove crypto nonces/AAD, crash ordering, TOCTOU, lock-held IO, or memory budgets without
  behavior tests.

## Generated state and failure triage

`just cache paths` prints resolved paths. Standard user caches (`CARGO_HOME`, `GRADLE_USER_HOME`,
`XDG_*`, Kotlin CLI cache) are always reused across gates; `target/cargo/` holds Cargo artifacts
(`CARGO_TARGET_DIR`) and `target/lomo/` holds Lomo packages, shared Kotlin build outputs
(`LOMO_KOTLIN_BUILD_DIR`), datasets, and temp files (`LOMO_GENERATED_ROOT`). See
[Project layout](../docs/project-layout.md) and [Cache cleanup](../docs/development/cache-cleanup.md).

`reports/` at the repository root (overridable via `LOMO_REPORTS_DIR`) is **strictly tool-owned**
and gitignored:

- `verification/` retains the newest 10 runs; `android-uisnap/` retains the newest 20 timestamped
  snapshots; `detekt/symbols/` keeps only active indexed directories; `jacoco/` holds Kotlin
  coverage; `device-perf-engine-baseline.v2.json` persists engine baselines.
- Never write manual logs, `reports/manual/`, or AI handoff markdown files into `reports/`.

Before APK builds, xtask clears only the selected variant's stale Java resource merger state (Amper
rewrites module archives in place). `just build android|tui` runs `just cache prune` before compiling
using cargo-lock-guarded `cargo-sweep --time 7` and 7-day scratch expiry.

On failure, inspect the structured JSON `error` and the first failed task log under `logs`.
Distinguish task regressions from unrelated worktree state, fix failures at their owner, and preserve
failed evidence while continuing independent work.
