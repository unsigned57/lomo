# Lomo Agent Guide

Read this entrypoint first. Work from the current tree, preserve other edits, and stop reading when
the owning boundary and required checks are clear. Do not read all documentation before every task.

## 1. Read only the relevant contract

| Task | Read next |
| --- | --- |
| Architecture, dependency, authority, state or cross-language change | [ARCHITECTURE.md](ARCHITECTURE.md) |
| Build, lint, coverage, generated outputs or quality commands | [quality/README.md](quality/README.md) |
| Any test authoring, editing or review | [Meaningful Tests](quality/testing/ai-meaningful-tests.md), then the relevant [Kotlin](quality/testing/ai-kotlin-test-style.md) or [Rust](quality/testing/ai-rust-test-style.md) guide |
| ViewModel state, events or paging presentation | [UDF contract](quality/udf-contract.md) |
| Release, signing or release resources | [quality/release.md](quality/release.md) |

`ARCHITECTURE.md` alone defines immutable module authority and dependency direction. Change it only
when those boundaries fundamentally change; never turn it into a migration ledger. Apply fixes at
the owning layer. Architecture-sensitive handoffs name the owner, boundary effect and exceptions in
an **Architecture Impact** note.

Use `rg` to verify paths, APIs and callers. Code and manifests own implementation facts. Read audit
history only for the relevant regression or an explicit investigation; reports do not override the
current architecture. Do not repeatedly reopen an unchanged contract already read in this task.

## 2. Explain the invariant before editing

Before a non-trivial fix, refactor or behavior change, answer these five points concretely:

1. **Fundamental invariant** — the type law, state transition, domain constraint or resource bound.
2. **Axiom violation** — the input, boundary or path that can break it.
3. **Rebuild from truth** — the type, parser, state machine or canonical workflow that prevents it.
4. **Edge enforcement** — where invalid input is rejected before domain logic.
5. **Tail deletion** — the old fallbacks, flags, duplicate checks, compatibility paths and ambiguous
   null/empty states removed in the same change.

Mechanical edits are exempt. An explicitly requested emergency hotfix is temporary and names its
first-principles replacement. Do not copy a pattern until it satisfies the invariant.

Do not add compensating conditionals, duplicate helpers, compatibility overloads, feature flags,
TODO migrations, parallel implementations or `NoOp`/`Disabled`/`Empty` placeholders for undefined
state. Do not suppress structural failures with `@Suppress`, `@SuppressLint` or `@SuppressWarnings`.

Model, reject or surface invalid upstream state. Do not silently swallow `Throwable`, discarded
`runCatching`, `getOrNull`, zero/empty `getOrDefault`/`getOrElse`, or Elvis fallbacks. Defaults must be
real domain states documented by a Behavior Contract. An intentional silent `runCatching` result
requires `// behavior-contract: silent-result-ok: <reason>`; a comment is not proof of correctness.

## 3. Implement with observable evidence

Features, bug fixes, contract changes and behavior-affecting test edits use BDD + TDD:
state capability, Given/When/Then scenarios, observable outcomes and exclusions; write the narrowest
failing test; observe a real RED failure; implement GREEN; refactor under GREEN.

Kotlin tests use one `FunSpec({ ... })` or one `init { ... }`, stateful fakes and observable assertions.
Rust tests live outside production sources and never require test-only production dependencies.
The linked test guides own the detailed conventions. Do not weaken existing behavior locks to make
a change pass.

Keep unrelated and overlapping working-tree edits intact. Kotlin sources use Amper `src/`, `test/`
and resource roots, without Maven/Java or common package-root directories; package declarations
remain `com.lomo.*`. The sole source-layout exception is Android baseline profiles:
`apps/android/app/src/main/baseline-prof.txt` and `apps/android/app/src/main/baselineProfiles/generated.txt` (regenerate
with `quality/scripts/generate_static_baseline_profile.py --build-dir <build-dir>`).
Update both `values` and `values-zh-rCN` for i18n changes. Read version pins from manifests/toolchain
configuration; do not introduce another pin or orchestration entrypoint.

## 4. Verify at the appropriate boundary

Run commands from the repository root. `Justfile` delegates to `lomo-xtask`; gate contents and build
facts are defined in [quality/README.md](quality/README.md).

- **During implementation:** narrow RED/GREEN tests. Do not run the full release gate after each edit.
- **Before closing a Rust package:** `cargo clippy -p <crate> --all-targets --locked -- -D warnings`
  and relevant `cargo test -p <crate> … --locked`.
- **Before closing a Kotlin package:** `./kotlin test --include-module=<module> --include-classes='…'`
  for changed specs, or the module suite for a broad change.
- **Native/FFI/lock/packaging changes:** regenerate and validate the affected generated/pack surface.
- **Review/push handoff:** `just check`. Manual `just preflight` is an iteration aid, not a substitute.
- **Merge/shared-branch delivery:** `just ci`. Pre-push automatically runs `just preflight push`.

Record actual commands and results. Compilation alone is not GREEN when behavior tests exist.
A required failing/unavailable gate keeps the package **open**; report its blocker and never mark
STAGE evidence GREEN. No first-party unsafe or `#[allow(unsafe_code)]` without an explicit
architecture exception and a same-change removal plan.

If a repository-owned command fails because a standard user toolchain/cache/daemon/telemetry path
is not writable in the sandbox, immediately request elevated rerun of the **original command**.
Do not redirect HOME/XDG/Gradle/Cargo/Kotlin caches into the repository as a workaround. Stop and
remove any exact workaround directory created for that failure. Reuse standard dependency caches
and the canonical shared build outputs.
