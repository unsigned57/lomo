# Lomo Agent Guide

This is the AI-first entrypoint for the repository. Read it first, route to the smallest useful next document, and stop descending once the current layer is sufficient.

## 0. Systemic Fix Mandate

The default fix is a systemic fix: change the owning model, abstraction, boundary, policy, or workflow so the whole class of failure becomes impossible or centrally handled.

Do not start from the smallest code edit. Start from the durable design.

Patch-style fixes are rejected by default:

- no one-off conditionals for a symptom
- no defensive fallback that hides a broken contract
- no duplicate helper or local utility when a shared concept is missing
- no compatibility parameter to avoid updating callers
- no deprecated old path, feature flag, TODO migration, or parallel implementation
- no `NoOp`, `Disabled`, or `Empty` placeholder for unfinished architecture
- no `@Suppress`, `@SuppressLint`, or `@SuppressWarnings` to silence design/static failures

If the correct fix requires an abstraction, contract, policy, parser, state model, repository boundary, static rule, or canonical workflow, build that instead of patching the caller.

## 1. Systemic Design Gate

Before any non-trivial bug fix, refactor, architecture change, or behavior change, state and satisfy this gate:

1. **Problem class**: What class of failures does this represent, beyond the observed symptom?
2. **Owning concept**: Which model, contract, use case, repository boundary, UI state model, parser/formatter, planner, policy object, or static rule should own the behavior?
3. **Global fix**: What design change makes the behavior ordinary, centralized, or impossible to misuse?
4. **Enforcement**: How will types, interfaces, tests, static checks, or one canonical path prevent recurrence?
5. **Tail deletion**: Which old APIs, duplicate helpers, fallback branches, feature flags, TODOs, DI bindings, tests, and resources must be removed in the same change?

If these cannot be answered, do not edit code. Investigate until the systemic fix is clear.

Scope search is evidence for the design gate, not the goal. The point is not merely to find similar lines; the point is to identify the missing system concept and fix it once at the correct owner.

## 2. Patch Exception

A local patch is allowed only for mechanical non-behavioral edits, or when the user explicitly asks for an emergency hotfix.

When applying an emergency hotfix, label it as temporary in the response and state the systemic fix that should replace it. Do not use emergency hotfix logic as precedent for normal work.

## 3. Architecture Gate

`ARCHITECTURE.md` is the source of truth for module responsibilities, dependency direction, and change routing.

Before any architecture-sensitive change:

1. Read `ARCHITECTURE.md`.
2. Identify the owning layer and boundary being changed.
3. Apply the systemic fix at the owning layer, not at the caller.
4. Do not treat existing architecture violations as precedent.
5. Include an `Architecture Impact` note naming the owning layer, boundary effect, and any exception.

If the systemic fix changes module ownership, dependency direction, or change routing, update `ARCHITECTURE.md` in the same change.

## 4. Contracts, Fallbacks, And Migration Tails

- Invalid upstream state must be modeled, rejected, or surfaced; do not hide it with `runCatching { ... }.getOrNull()`, `.getOrDefault(...)`, `.getOrElse { <constant> }`, swallowed `Throwable`, `?: emptyList()`, `?: ""`, or `?: 0`.
- A default or empty branch is valid only when it is a real domain state documented by a `Behavior Contract`.
- For intentional silent-result `runCatching`, place `// behavior-contract: silent-result-ok: <reason>` immediately before or on the same line.
- Replacements must be complete in the same change: migrate every caller, update DI/factories/providers, remove the old API, and delete stale tests/resources.
- `@Deprecated`, compatibility overloads, optional parameters added only to avoid callers, temporary feature flags, and TODO removals are migration tails and are rejected.

## 5. Progressive Disclosure

Read in this order only when the task needs the next layer:

1. `AGENTS.md`: workflow, systemic-fix rules, and architecture gate.
2. `ARCHITECTURE.md`: stable module responsibilities, dependency direction, and change routing.
3. `quality/README.md`: verification commands, quality scripts, and static check policy.
4. `quality/testing/ai-meaningful-tests.md`: mandatory before writing, editing, or reviewing tests.
5. `quality/testing/ai-kotlin-test-style.md`: mandatory before authoring or editing Kotlin tests.

Verify concrete paths, files, and APIs against the repository before acting. Module READMEs are orientation only, not source of truth.

## 6. Behavior And Tests

- Use BDD + TDD for feature work, bug fixes, contract changes, and behavior-affecting test edits.
- State the behavior contract first: capability, Given/When/Then scenarios, observable outcomes, and exclusions.
- Write or update the failing test before production code, run the targeted test or `quality/scripts/ai_static_quality_check.sh`, and observe a real RED failure for that behavior.
- Implement the systemic fix needed to reach GREEN, then refactor under GREEN.
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
