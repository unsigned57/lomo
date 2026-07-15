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

- **Bootstrap**: `just bootstrap`
- **Commit preflight**: `just preflight` (path-aware; also run by the pre-commit hook)
- **Iterative Check**: `just check` (also run by the pre-push hook)
- **Full Gate**: `just ci` (coverage + fat-LTO release native; PR/merge handoff and local confirmation)
- **Device Smoke**: `just device-smoke`
- **Android Build**: `just android debug` or `just android release`
- **Commit Rule**: hooks run path-aware `just preflight` on commit and `just check` on push;
  before merge or shared-branch handoff run `just ci` (GitHub Actions enforces the PR surface)

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
