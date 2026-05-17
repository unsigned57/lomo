# Lomo Agent Guide

This file is the AI-first entrypoint for the repository. Read this file first, route yourself to the smallest useful next document, and stop descending once the current layer is sufficient.

## 0. Workflow Entrypoint

- **Task Management**: If `task.md` exists in the repository root, the AI must read it immediately. The content of `task.md` defines the current requirements and goals. The AI should treat these as the primary objective, complete all tasks, and delete `task.md` upon completion.
- **Completeness**: Prefer finishing independently verifiable slices in one turn. Do not stop at partial analysis or leave half-wired changes behind.

## 1. Progressive Disclosure Protocol

Read in this order and only when the task actually requires the next layer:

1. `AGENTS.md` (This document)
2. `ARCHITECTURE.md`: Stable module responsibilities, dependency direction, and change routing.
3. `quality/README.md`: Verification commands, quality scripts, and static check policy.
4. `quality/testing/ai-meaningful-tests.md`: Mandatory before writing, editing, or reviewing tests.

**Rules**: Do not pre-read "just in case". Verify current paths, files, and APIs against the repository with `rg`, `glob`, or direct code reads before acting. Module READMEs may exist for human orientation, but they are not the source of truth.

## 2. Hard Boundaries

- `app` must not import `com.lomo.data.*`.
- `ui-components` must not import `com.lomo.data.*`.
- `domain` must not depend on Android, Compose, Lifecycle, Room, Hilt/Dagger, Ktor, JGit, or any `com.lomo.data.*` type.
- ViewModels must not depend directly on DAO, DataSource, RoomDatabase, repository implementations, Git/WebDAV engines, `DocumentFile`, or direct file-system helpers.
- New business behavior belongs in `domain` first, then is consumed from `app`, then implemented in `data`.
- New repository implementations belong in `data/repository`.
- Do not treat an existing architecture violation as precedent.
- If architecture-sensitive code changes, include an `Architecture Impact` note with the owning layer and any boundary exception.

## 3. AI Code Rules

- **Root-Cause Over Patch**: Prefer solutions that eliminate the root cause rather than work around symptoms. A patch is acceptable for an isolated, low-risk bug fix. For architectural issues—cross-cutting duplication, missing abstractions, design-level inconsistencies—the fix must address the structural problem itself. Adding a workaround layer, an extra `if`, or a NoOp fallback on top of a broken abstraction is not an acceptable remedy. When an audit or review identifies an architectural debt, the response must be a structural refactor, not a patch.
- **Review Grade**: Write with review-grade clarity and correctness. Assume code will be reviewed by Claude.
- **No Suppress**: Do not introduce `@Suppress`, `@SuppressLint`, or `@SuppressWarnings`.
- **Statics First**: Fix static check complaints by refactoring or moving logic, not by suppressing.
- **TDD Requirement**: AI must follow Test-Driven Development (TDD) **as a strict four-step loop, with explicit red proof**, never collapsing the loop into "write test + implementation back-to-back":

  1. **Write the failing test first.** State the contract in the test body and a Test Contract comment before touching production code.
  2. **Run the test and confirm RED.** Execute `quality/scripts/ai_static_quality_check.sh` or the targeted `./gradlew :<module>:testDebugUnitTest --tests <FQN>` command and **paste / observe a real failure**: either a compilation failure naming the missing symbol, an assertion failure, or an explicit "X tests failed". A green run at this stage means the test is a no-op — go back and tighten it. Do not write a single line of production code until you have seen the failure for *this specific test*.
  3. **Write the minimum implementation to flip the test to GREEN.** Re-run the same command and confirm "BUILD SUCCESSFUL" *with* the test name shown as passing.
  4. **Refactor under green.** Re-run the test after each non-trivial refactor to keep the green proof.

  Writing the test, the implementation, and running tests only at the end of the session is **not** TDD even if the final run is green — it loses the red proof that the test actually pins the contract. If multiple tests/implementations are needed for a single change, repeat the loop per behavior; do **not** batch.

  When authoring or editing Kotlin tests, follow `quality/testing/ai-kotlin-test-style.md` to the letter — in particular the **Spec Form** rule: a Kotest file uses a single `FunSpec({ ... })` constructor block (preferred) or a single `init { ... }` block; **never** open one `init` per `test(...)`. Treat old specs with the per-`test` `init` pattern as JUnit4-migration debt: whenever you have to edit one of those files for any reason, refactor it into the single-block form in the same change ("opportunistic migration"). Do not preserve the anti-pattern out of inertia, and do not invent a new anti-pattern by copying it into a new file.

  Refer to `quality/testing/ai-meaningful-tests.md` for the repository policy and `quality/testing/ai-kotlin-test-style.md` for Kotlin authoring guidance.
- **Reuse Before Rewrite**: Before adding any new helper or utility, search the owning module and the shared `domain` / `ui-components` layers for an existing implementation. Reuse or extract shared logic instead of duplicating it.
- **Delete Dead Paths**: After refactors, remove obsolete helpers, old entry points, stale DI wiring, and unused resources. Do not keep dead code "just in case".

## 4. Verification & Commands

Run commands from the repository root. Prefer `quality/scripts/` for AI verification because they already set repo-local Gradle and Android homes correctly.

- **Iterative Check**: `quality/scripts/ai_static_quality_check.sh`
- **Local Maintenance**: `quality/scripts/ai_local_maintenance_check.sh` for dependency updates, dependency-analysis advice, CVE scanning, and R8 release diagnostics.
- **Full Gate**: `quality/scripts/ai_quality_check.sh`
- **AI Scripts**: Use `quality/scripts/` for automated verification because they also produce AI-readable local maintenance reports.
- **Commit Rule**: Run a full `qualityCheck` before creating any commit. For one unchanged
  working tree split into multiple consecutive commits, use `quality/scripts/verified_batch_commit.sh`
  so the batch runs `qualityCheck` before the first commit and after the final commit while the
  intermediate commits use `git commit --no-verify` through the script.

Refer to `quality/README.md` for the full command matrix and triage guide.

## 5. Project Context

- **SDK**: `minSdk` is `26`.
- **i18n**: Update `values` and `values-zh-rCN`.
- **Baseline Profile**: release-critical. Inspect `app/baseline-rules.txt`, run `:app:generateReleaseStaticBaselineProfile` to refresh `app/src/main/baselineProfiles/generated.txt`, and keep `app/src/main/baseline-prof.txt` for manual/high-priority coverage gaps (it is not the generated output) when performance or startup logic changes.
- **Ownership**: Assume other people may be editing the tree; do not overwrite their changes.
