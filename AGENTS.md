# Lomo Agent Guide

This is the AI-first repository entrypoint. Read it first, open only the task-specific document
needed next, and stop descending once the current layer is sufficient.

## 1. First-Principles Gate

Before any non-trivial bug fix, refactor, architecture change, or behavior change, state:

1. **Fundamental invariant**: the irreducible type law, state transition, domain constraint, or
   resource property that must hold.
2. **Axiom violation**: the input, boundary, missing type, or code path that allowed it to become
   false.
3. **Rebuild from truth**: the type, parser, state machine, permission boundary, or canonical
   workflow that makes the violation structurally impossible.
4. **Edge enforcement**: how invalid state is rejected at the furthest boundary before domain logic.
5. **Tail deletion**: which old fallbacks, flags, duplicate validations, compatibility paths, and
   null-vs-empty ambiguities must disappear in the same change.

Do not edit until these are answerable. Scope search is evidence for this gate, not the goal.

Rejected by default:

- one-off conditionals or defensive fallbacks that compensate for a broken invariant
- duplicate helpers where the underlying property is unmodeled
- compatibility parameters, overloads, deprecated paths, feature flags, TODO migrations, or
  parallel implementations
- `NoOp`, `Disabled`, `Empty`, or sentinel placeholders for undefined state
- `@Suppress`, `@SuppressLint`, or `@SuppressWarnings` used to silence a structural failure
- copying an existing pattern without first proving that the pattern follows the invariant

Invalid upstream state must be modeled, rejected, or surfaced. Do not hide it with swallowed
`Throwable`, `runCatching { ... }.getOrNull()`, zero/empty `getOrDefault` or `getOrElse`, or Elvis
fallbacks to empty strings, collections, or numbers. A default is valid only when it is a real domain
state documented by a Behavior Contract. Mark an intentional silent `runCatching` result with
`// behavior-contract: silent-result-ok: <reason>`.

Mechanical non-behavioral edits are exempt from the design gate. An explicitly requested emergency
hotfix must be labeled temporary and name the first-principles replacement.

## 2. Architecture Gate

[ARCHITECTURE.md](ARCHITECTURE.md) is the sole source of truth for module responsibilities,
dependency direction, and change routing. Read it before architecture-sensitive work, apply the fix
at the owning layer, and never use an existing violation as precedent.

If ownership or dependency direction changes, update the architecture document in the same change.
Every architecture-sensitive handoff must include an `Architecture Impact` note naming the owner,
boundary effect, and any exception.

## 3. Task Routing

- Build, lint, coverage, generated state, and quality gates: [quality/README.md](quality/README.md)
- Release builds, signing, and release resource review: [quality/release.md](quality/release.md)
- Any test authoring, editing, or review: [AI Meaningful Tests](quality/testing/ai-meaningful-tests.md)
- Kotlin tests after the common test contract: [AI Kotlin Test Style](quality/testing/ai-kotlin-test-style.md)
- Rust tests after the common test contract: [AI Rust Test Style](quality/testing/ai-rust-test-style.md)

Concrete paths, APIs, and implementation details are code facts. Verify them with repository search;
do not rely on hand-maintained module file inventories.

## 4. Behavior And Tests

- Use BDD + TDD for features, bug fixes, contract changes, and behavior-affecting test edits.
- State capability, Given/When/Then scenarios, observable outcomes, and exclusions first.
- Write or update the narrowest failing test before production code and observe a real RED failure.
- Implement the first-principles fix to reach GREEN, then refactor under GREEN.
- Kotlin tests use one `FunSpec({ ... })` or one `init { ... }`, fake-first stateful collaborators,
  and observable behavior rather than interaction-only assertions.
- Rust tests stay outside production sources and assert observable behavior without adding test-only
  dependencies to the production graph.

## 5. Verification

Run commands from the repository root. `Justfile` delegates to repository-owned `lomo-xtask`, so
local and CI orchestration share one graph.

### 5.1 Done means gates are green

A coding turn is **not complete** when only source edits land. Before reporting success, handoff,
or moving to the next package, the agent must run the gates that cover the changed surface and
record real command output. “Looks correct” / “tests should pass” is not verification.

Mandatory minimum after production or test code changes:

1. **Targeted RED/GREEN first** while implementing (narrowest failing then passing tests for the
   package under change).
2. **Surface gate before claiming the package done**:
   - Rust production/tests/clippy: `cargo clippy -p <crate> --all-targets --locked -- -D warnings`
     and the relevant `cargo test -p <crate> … --locked`.
   - Kotlin production/tests: `./kotlin test --include-module=<module> --include-classes='…'`
     for the changed specs, or the module suite when the change is broad.
   - Native/FFI/device: regenerate/pack as required, then `just device-smoke` when engine, lock,
     packaging, or smoke surface changed and a device/emulator is available.
3. **Repository iterative gate before push/handoff**: `just check`.
4. **Full handoff gate before merge / shared-branch delivery**: `just ci`.
5. **Device when applicable**: `just device-smoke` on attached **API ≥ 26** with a packaged ABI.
   Stage-1/2 entry hard device gate is API ≥ 26 arm64 when that is the available device line; a
   fixed API 26 x86_64 AVD is optional `pending_env`/non-claim and must not be marked GREEN without
   a real run. Product `minSdk`/NDK API 26 remains mandatory.

If a required gate cannot run (no device, missing secret, tool outage), say so explicitly, keep the
package **open**, and do not mark STAGE evidence GREEN for that gate.

Do **not**:

- claim GREEN from compilation alone when behavior tests exist;
- skip Clippy when workspace `unsafe_code = "deny"` / pedantic Clippy is the contract;
- leave `#[allow(unsafe_code)]` or first-party `unsafe` without an explicit architecture exception
  and a same-change plan to remove it;
- treat `just preflight` as a substitute for `just check` on handoff.

### 5.2 Command menu

- **Bootstrap**: `just bootstrap`
- **Lightweight commit hook**: format staged sources + staged meaningful-test contracts only
- **Path-aware iteration**: `just preflight` (manual; not on every commit; never the final handoff)
- **Iterative Check**: `just check` (pre-push hook and local iterative validation)
- **Full Gate**: `just ci` (coverage + fat-LTO release native; PR/merge handoff and local confirmation)
- **Device Smoke**: `just device-smoke`
- **Android Build**: `just android debug` or `just android release`
- **Commit Rule**: pre-commit stays cheap (fmt + contracts); pre-push runs `just check`; before merge
  or shared-branch handoff run `just ci` (GitHub Actions enforces the PR surface). Use
  `just preflight` while iterating when you want a path-aware subset without a full check.

## 6. Repository Facts

- `minSdk` and native Android API are `26`; Rust is `1.96`; Android NDK is `29.0.14206865`.
- i18n changes update both `values` and `values-zh-rCN`.
- Version-controlled Kotlin modules use Amper roots such as `src/`, `test/`, `resources/`, and
  Android/Compose resource roots. Never add Maven/Java source hierarchies or common package-root
  directories on disk; keep full `package com.lomo.*` declarations.
- Baseline Profile packaging is the sole source-layout exception: keep
  `app/src/main/baseline-prof.txt` and `app/src/main/baselineProfiles/generated.txt`; regenerate the
  latter with `quality/scripts/generate_static_baseline_profile.py --build-dir <build-dir>`.
- Assume others may be editing the tree. Preserve unrelated changes and work with overlapping ones.
