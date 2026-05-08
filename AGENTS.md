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
- **TDD Requirement**: AI must follow Test-Driven Development (TDD): write the test first to establish red proof before touching production code, then implement, then refactor. Refer to `quality/testing/ai-meaningful-tests.md` for full policy.
- **Reuse Before Rewrite**: Before adding any new helper or utility, search the owning module and the shared `domain` / `ui-components` layers for an existing implementation. Reuse or extract shared logic instead of duplicating it.
- **Delete Dead Paths**: After refactors, remove obsolete helpers, old entry points, stale DI wiring, and unused resources. Do not keep dead code "just in case".

## 4. Verification & Commands

Run commands from the repository root. Prefer `quality/scripts/` for AI verification because they already set repo-local Gradle and Android homes correctly.

- **Iterative Check**: `quality/scripts/ai_static_quality_check.sh`
- **Local Maintenance**: `quality/scripts/ai_local_maintenance_check.sh` for dependency updates, dependency-analysis advice, CVE scanning, and R8 release diagnostics.
- **Full Gate**: `quality/scripts/ai_quality_check.sh`
- **AI Scripts**: Use `quality/scripts/` for automated verification because they also produce AI-readable local maintenance reports.
- **Commit Rule**: Run a full `qualityCheck` before creating any commit.

Refer to `quality/README.md` for the full command matrix and triage guide.

## 5. Project Context

- **SDK**: `minSdk` is `26`.
- **i18n**: Update `values` and `values-zh-rCN`.
- **Baseline Profile**: release-critical. Inspect `app/baseline-rules.txt`, run `:app:generateReleaseStaticBaselineProfile` to refresh `app/src/main/baselineProfiles/generated.txt`, and keep `app/src/main/baseline-prof.txt` for manual/high-priority coverage gaps (it is not the generated output) when performance or startup logic changes.
- **Ownership**: Assume other people may be editing the tree; do not overwrite their changes.
