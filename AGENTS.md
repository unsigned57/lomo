# Lomo Agent Guide

This is the AI-first entrypoint for the repository. Read it first, route to the smallest useful next document, and stop descending once the current layer is sufficient.

## 0. First-Principles Fix Mandate

The default fix is a first-principles fix: decompose the failure to its fundamental invariants, then rebuild the solution from those truths upward.

Do not start from the smallest code edit. Do not start from design patterns or precedent. Start from the axioms — the irreducible constraints, types, and invariants the system must satisfy.

### Decomposition protocol

Before any production code change, decompose:

1. **First truth** — What fundamental invariant, type law, domain constraint, or resource property must hold? (e.g. "balance must never be negative", "the ID exists before any reference", "write order to the log is append-only")
2. **Violation** — Which code path allowed the invariant to be false? Identify at the axiom level, not the symptom level.
3. **Rebuild** — Encode the invariant so that the violation becomes structurally impossible (types, state machines, parsers, canonical workflows, permission boundaries).

### Rejected by default

Any change that compensates for a broken axiom instead of restoring it:

- no one-off conditionals that paper over an invariant gap
- no defensive fallback that swallows an axiom violation
- no duplicate helper or local utility when a fundamental property is unmodeled
- no compatibility parameter or optional argument added to avoid correcting callers
- no deprecated old path, feature flag, TODO migration, or parallel implementation
- no `NoOp`, `Disabled`, `Empty`, or sentinel placeholder for an undefined state
- no `@Suppress`, `@SuppressLint`, `@SuppressWarnings` that silence a structural failure
- no pattern copied from existing code unless the pattern itself was derived from first principles

If the fix requires a new type, a stricter state machine, a parser that rejects invalid input at the boundary, or a policy layer that enforces the axiom centrally, build that. Do not patch around the gap.

## 1. First-Principles Design Gate

Before any non-trivial bug fix, refactor, architecture change, or behavior change, state and satisfy this gate:

1. **Fundamental invariant**: What is the simplest irreducible truth the system relies on? State it as a type law, a state transition rule, or an algebraic property.
2. **Axiom violation**: Exactly which code path let the invariant become false? Trace to the input, boundary, or missing type — not to the observed symptom.
3. **Rebuild from truth**: What type, parser, state machine, permission boundary, or canonical workflow makes the violation structurally impossible?
4. **Enforcement at the edge**: How does the system reject invalid state at the furthest boundary — before it reaches any domain logic?
5. **Tail deletion**: Which old code, fallback branches, feature flags, workarounds, duplicate validations, and null-vs-empty ambiguities relied on the missing axiom and must be removed in the same change?

If these cannot be answered, do not edit code. Investigate until the violated first principle is clear.

Scope search is evidence for the design gate, not the goal. The point is to find the missing fundamental invariant and encode it once at the correct boundary.

## 2. Patch Exception

A local patch is allowed only for mechanical non-behavioral edits, or when the user explicitly asks for an emergency hotfix.

When applying an emergency hotfix, label it as temporary in the response and state the first-principles fix that should replace it. Do not use emergency hotfix logic as precedent for normal work.

## 3. Architecture Gate

`ARCHITECTURE.md` is the source of truth for module responsibilities, dependency direction, and change routing.

Before any architecture-sensitive change:

1. Read `ARCHITECTURE.md`.
2. Identify the owning layer and boundary being changed.
3. Apply the first-principles fix at the owning layer, not at the caller.
4. Do not treat existing architecture violations as precedent.
5. Include an `Architecture Impact` note naming the owning layer, boundary effect, and any exception.

If the first-principles fix changes module ownership, dependency direction, or change routing, update `ARCHITECTURE.md` in the same change.

## 4. Contracts, Fallbacks, And Migration Tails

- Invalid upstream state must be modeled, rejected, or surfaced; do not hide it with `runCatching { ... }.getOrNull()`, `.getOrDefault(...)`, `.getOrElse { <constant> }`, swallowed `Throwable`, `?: emptyList()`, `?: ""`, or `?: 0`.
- A default or empty branch is valid only when it is a real domain state documented by a `Behavior Contract`.
- For intentional silent-result `runCatching`, place `// behavior-contract: silent-result-ok: <reason>` immediately before or on the same line.
- Replacements must be complete in the same change: migrate every caller, update DI/factories/providers, remove the old API, and delete stale tests/resources.
- `@Deprecated`, compatibility overloads, optional parameters added only to avoid callers, temporary feature flags, and TODO removals are migration tails and are rejected.

## 5. Progressive Disclosure

Read in this order only when the task needs the next layer:

1. `AGENTS.md`: workflow, first-principles fix rules, and architecture gate.
2. `ARCHITECTURE.md`: stable module responsibilities, dependency direction, and change routing.
3. `quality/README.md`: verification commands, quality scripts, and static check policy.
4. `quality/testing/ai-meaningful-tests.md`: mandatory before writing, editing, or reviewing tests.
5. `quality/testing/ai-kotlin-test-style.md`: mandatory before authoring or editing Kotlin tests.

Verify concrete paths, files, and APIs against the repository before acting. Module READMEs are orientation only, not source of truth.

## 6. Behavior And Tests

- Use BDD + TDD for feature work, bug fixes, contract changes, and behavior-affecting test edits.
- State the behavior contract first: capability, Given/When/Then scenarios, observable outcomes, and exclusions.
- Write or update the failing test before production code, run the targeted test or `quality/scripts/ai_static_quality_check.sh`, and observe a real RED failure for that behavior.
- Implement the first-principles fix needed to reach GREEN, then refactor under GREEN.
- Kotlin tests must follow `quality/testing/ai-kotlin-test-style.md`: single `FunSpec({ ... })` or single `init { ... }`, fake-first stateful collaborators, and no interaction-only tests without observable behavior.

## 7. Verification

Run commands from the repository root. Prefer `quality/scripts/` because they set repo-local Gradle and Android homes.

- **Iterative Check**: `quality/scripts/ai_static_quality_check.sh`
- **Local Maintenance**: `quality/scripts/ai_local_maintenance_check.sh`
- **Full Gate**: `quality/scripts/ai_quality_check.sh`
- **Commit Rule**: Run full `qualityCheck` before committing. For multiple commits from one unchanged tree, use `quality/scripts/verified_batch_commit.sh`.

## 8. Project Context

- `minSdk` is `26`.
- i18n changes must update `values` and `values-zh-rCN`.
- Baseline Profile is release-critical. For performance or startup changes, inspect `app/baseline-rules.txt`, refresh `app/src/main/baselineProfiles/generated.txt` with `:app:generateReleaseStaticBaselineProfile`, and keep `app/src/main/baseline-prof.txt` for manual/high-priority gaps.
- Assume others may be editing the tree. Do not overwrite unrelated changes.
