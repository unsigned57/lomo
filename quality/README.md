# Quality Contract

Read this file for command output, gate selection, build inputs or failure triage.
[AGENTS.md](../AGENTS.md) owns the task workflow and completion requirements;
[ARCHITECTURE.md](../ARCHITECTURE.md) owns module authority. Test authoring starts at
[Meaningful Tests](testing/ai-meaningful-tests.md); signing and publishing use
[Release](release.md). Read only the contract needed for the current task.

## Command protocol

Run commands from the repository root. `just` and `just commands` return the command catalog.
`Justfile` delegates to `lomo-xtask`; its dispatch registry also generates discovery. There is no
separate agent command implementation or manually maintained command inventory.

Every invocation that reaches xtask writes exactly one JSON document to stdout:

```json
{"schema_version":1,"command":"cache","status":"succeeded","data":{"paths":{"cargo_target":"/checkout/target"}}}
```

- `schema_version` identifies the result envelope. Consumers must reject unsupported versions.
- `command` is the requested xtask command; no arguments means `commands`.
- `status` is `succeeded` or `failed`. Exit 0 means that operation succeeded; it does not promote
  a plan, diagnostic or scoped iteration to a completed handoff gate.
- `data` is command-specific. `null` means the command has no additional payload, or a failure
  occurred before evidence was produced. Never treat it as an empty successful plan.
- Failed operations include `error.message` and `error.causes`, return nonzero, and retain
  available evidence in `data`. A failed verification includes its results and report path.
- Progress and child-tool diagnostics go to stderr. Child commands cannot read interactive stdin.
  Keep stdout and stderr separate when saving evidence; do not merge them with `2>&1`.
- If Cargo cannot build xtask, Just rejects a recipe, or a process is killed before completion,
  there may be no final JSON. A missing or malformed result is incomplete, never success.

| Operation | `data` |
| --- | --- |
| `just` / `just commands` | command names, arguments, purposes and protocol location |
| `just dev --plan` | task graph, selected scope, `complete_worktree`, excluded owners |
| `just dev`, `just check`, push hook | `plan`, `results`, `report_path`, task-id-to-file `logs` |
| `just ci` | completed `verification` evidence and published `apk` |
| `just cache paths` | resolved absolute `paths` |
| `just cache audit` | entries with `present` plus bytes, or `absent` without invented bytes |
| `just cache prune` / `clean` | removed-file counts or removed paths |
| `just android ...` / `native ...` / `bindings` | published APK or generated output directory |
| `just perf` | baseline/performance JSON paths and the measured conclusion |

Verification task statuses are `Passed`, `Failed`, `Cancelled` and `NotScheduled`. Read `logs`
for captured subprocess output; tasks executed inside xtask may have no separate log file.
`report_path` is durable evidence for that invocation, not a reusable success cache.
Planning starts no compiler/test tasks, but still reads Git and Cargo metadata.

## Gate selection and evidence

This is the sole command and applicability matrix. AGENTS owns authorization and task completion;
the word "review" alone does not require a build. Classify the actual change before selecting gates.

| Task or changed surface | Required evidence |
| --- | --- |
| Read-only review or investigation | Findings and inspected evidence; run tests only when needed to establish a finding |
| Non-behavior documentation or mechanical prose change | Content, local links, referenced paths/commands and `git diff --check`; no invented RED or full build |
| Feature, fix or executable contract/quality-rule change | Narrow behavior RED/GREEN, followed by the applicable package and code-delivery checks |
| Rust package code or tests | `cargo clippy -p <crate> --all-targets --locked -- -D warnings` and relevant `cargo test -p <crate> … --locked` |
| Kotlin code or behavior-affecting tests | `./kotlin test --include-module=<module> --include-classes='…'` for changed specs, or the module suite for a broad change |
| Native, FFI, lock or packaging inputs | Regenerate and validate the affected generated/pack surface |
| Dependency manifests | `just deps check`; explicit task-related updates follow [Dependency Selection](dependencies.md) |
| Code changes delivered for review or push | `just check`; `just dev` is an iteration aid, not a substitute |
| Code merge/shared-branch delivery | `just ci`; pre-push also runs `just _preflight`, including applicable diff-scoped Rust mutation tests |

Documentation that changes an executable rule or a fixture's expected behavior is not a prose-only
change. Use narrow tests during implementation; do not run the full release gate after each edit.
`just dev --tests-only` is the worktree test sweep without static analysis. `just perf` and
`just cache audit` are manual diagnostics; a full mutation sweep uses `cargo mutants` directly.

| Gate | Includes | Intentionally omits |
| --- | --- | --- |
| pre-commit | staged formatting and meaningful-test contracts | compile, test and native gates |
| `just dev` | affected worktree owners, including untracked/deleted files; diff-scoped mutation tests after Rust suites | coverage, fat LTO |
| `just dev --tests-only` | test tasks from the same worktree plan | static analysis, coverage, mutation tests |
| `just _preflight` (pre-push) | pushed commits vs remote base; Rust-only pushes omit Kotlin; no remote base uses the iterative surface without mutation tasks | coverage, fat LTO |
| `just check` | Rust fmt, strict Clippy, nextest/doc and architecture tests, machete; dev bindings/native graph; Kotlin model/build, Detekt, test style, Android Lint, shell contracts and host tests | cargo-deny, coverage, Compose static, fat-LTO release native, device install |
| `just ci` | check plus cargo-deny, Rust/Kotlin coverage, Compose static, four-ABI release native and APK/ELF/dependency validation | device execution |

`--scope <owner>` deliberately excludes other owners. Inspect `complete_worktree` and
`excluded_owners`; a passed scoped plan does not close the whole worktree.
Zero/unreported tests in a required test task, changes to its verification inputs, failed
prerequisites, missing outputs and unavailable required tools cannot pass. A prose-only task with
no required test is not a zero-test failure. Missed diff-scoped mutants fail `dev`/pre-push.

GitHub PR jobs select Rust/native/Kotlin surfaces by path. Native PR jobs use `release-ci`;
shipping native outputs use the release pack path. The `quality` aggregation job governs branch
protection. Workflows invoke xtask rather than implementing another build graph.

## Canonical build inputs

Read current values from their owners; do not add pins to agent configuration or copy them here.

| Fact | Authoritative input |
| --- | --- |
| Rust channel and components | [`rust-toolchain.toml`](../rust-toolchain.toml); MSRV in [`Cargo.toml`](../Cargo.toml); update via `just rust-toolchain-bump` |
| JDK and Android SDK levels | Owning `apps/android/**/module.yaml`; for the app, [`module.yaml`](../apps/android/app/module.yaml) |
| Cargo tools and BoltFFI CLI | [`tools.toml`](../tools.toml), installed by `just bootstrap` |
| Crate/module dependencies and app version | Cargo manifests/lock and `apps/android/**/module.yaml` |
| External dependency capabilities | [`dependency-capabilities.toml`](dependency-capabilities.toml); classification workflow in [Dependency Selection](dependencies.md#capability-boundaries-instead-of-library-allowlists) |
| NDK, native API and standard paths | [`workspace.rs`](../crates/lomo-xtask/src/workspace.rs) |
| ABI selectors, library identity and native profiles | [`native.rs`](../crates/lomo-xtask/src/native.rs) |
| Rust coverage threshold | `RUST_COVERAGE_MINIMUM` in [`quality.rs`](../crates/lomo-xtask/src/quality.rs); xtask/architecture tests are excluded |
| Kotlin analysis configuration | [`quality/detekt/config`](detekt/config) and the owning single-purpose scripts |

`just native` and `just android debug/release` default to arm64 at the Just entrypoint; pass `all`
for a universal/four-ABI build. Native release packaging uses the canonical release-android
profile and xtask's immediate-abort/build-std policy. Dependency version changes follow
[Dependency Selection](dependencies.md#version-changes). No production dual stack, compatibility
alias or UniFFI fallback is permitted.

## Policy coverage and limits

Rust denies warnings, unsafe code, unused must-use values and the configured Clippy groups.
Cargo-deny checks advisories/sources/duplicates; machete checks unused dependencies.
[`lomo-architecture-tests`](../crates/lomo-architecture-tests/tests) checks Cargo metadata,
parsed Amper scopes and Rust syntax, including conditional/optional edges and untracked files.
Malformed policy input and unknown owners fail closed.

External libraries are classified by capability, independently of version. The architecture gate
reads the shared catalogue and current manifests; it does not keep a second per-owner library list.
All declared capabilities must fit the owner. This covers Cargo production/build declarations even
when optional or target-specific, and Amper production dependency scopes. Internal direction/path
locks, actual host/JNI closure checks and test-only dependency separation remain in force.

Kotlin scripts remain single-purpose implementations invoked by xtask. Detekt activation fixtures
prove legal and forbidden inputs reach the real CLI. Source suppressions cannot disable ownership
checks; module configuration parity is architecture-locked. The CLI currently uses light/syntax
analysis: listing a type-dependent rule in YAML does not establish full semantic coverage.

The recurring policy invariants are: owner-issued identity; typed failures; one publishing owner
per business fact; required collaborators without placeholders; bounded reads before allocation;
secret handles rather than plaintext work payloads; checked wire constructors; exact identity for
destructive selection; injected domain time; and immutable, single-owner UDF state.
Their enforcement lives in the Detekt rule sources and architecture policy tests, rather than an
audit-history inventory. [UDF Contract](udf-contract.md) owns presentation semantics and exceptions.

A `// behavior-contract: <id>: <reason>` exception must use a registered marker beside its owner.
A syntactically accepted marker does not prove its reason: the Behavior Contract and tests must.
Cryptographic nonce/AAD domains, crash ordering, mount/GC TOCTOU, lock-held network IO and peak-byte
budgets still require task-specific behavior evidence; static checks do not prove them.

## Generated state and failure triage

`just cache paths` reports resolved paths. Reuse caller-owned Cargo, Gradle, Kotlin and XDG caches;
explicit `LOMO_KOTLIN_*` overrides retain precedence. Every Kotlin gate uses the same configured
shared build directory. Do not create task-specific cache/build trees or another bootstrap script.

Generated reports, APKs, corpora and temporary files live under `target/lomo` in the configured
Cargo target; Cargo's own build graph remains alongside it. Kotlin/binding/native outputs remain
ignored. Builds must be reproducible from a clean checkout; an existing dirty worktree is also a
valid development input. Preserve other edits and record the inputs actually verified. Small golden
format fixtures belong in `fixtures/`; large seeded corpora are generated by `just perf`.

`prune` removes stale Cargo artifacts, debug-symbol packages and coverage-instrumented outputs;
pre-push invokes it. `clean` deletes the existing allowlisted repository outputs, never global
user caches. Neither command is a repair for incorrect ownership or stale source configuration.
Baseline profiles are regenerated by the gate from the shared Kotlin build via
`generate_static_baseline_profile.py`; do not hand-edit generated profiles.

On failure, inspect the structured error and the first failed task's log. Distinguish task-related
failures from existing or unrelated worktree failures. Fix authorized tool/pin/NDK/binding or pack
failures at their owner; do not silently expand into unrelated repairs. Preserve failed evidence
and report which required verification remains open while continuing independent work.
Do not add compatibility recipes, tracked generated artifacts or fallback build paths.
