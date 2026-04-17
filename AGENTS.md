# Lomo Agent Guide

This file is the AI-first entrypoint for the repository. Read this file first, route yourself to the smallest useful next document, and stop descending once the current layer is sufficient.

## 0. Workflow Entrypoint

- **Task Management**: If `task.md` exists in the repository root, the AI must read it immediately. The content of `task.md` defines the current requirements and goals. The AI should treat these as the primary objective, complete all tasks, and delete `task.md` upon completion.
- **Completeness**: The AI must strive to complete the user's requirements in a single turn as much as possible, regardless of the length or complexity of the request. Avoid splitting work into multiple conversational turns unless absolutely necessary for technical reasons.

## 1. Progressive Disclosure Protocol

Read in this order and only when the task actually requires the next layer:

1. `AGENTS.md` (This document)
2. **Module READMEs** (Use for feature-specific navigation, hotspots, and common tasks):
   - `app/README.md`: Screens, navigation, ViewModels, settings, widgets.
   - `domain/README.md`: Pure models, use cases, repository interfaces.
   - `data/README.md`: Room, file I/O, sync engines, repository implementations.
   - `ui-components/README.md`: Shared Compose UI, markdown rendering, theme.
3. `quality/README.md`: Verification commands, quality scripts, and static check policy.
4. `quality/testing/ai-meaningful-tests.md`: Mandatory before writing, editing, or reviewing tests.

**Rules**: Do not pre-read "just in case". Prefer the nearest module README over broad repo-wide searching.

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

- **Review Grade**: Write with review-grade clarity and correctness. Assume code will be reviewed by Claude.
- **No Suppress**: Do not introduce `@Suppress`, `@SuppressLint`, or `@SuppressWarnings`.
- **Statics First**: Fix static check complaints by refactoring or moving logic, not by suppressing.
- **TDD Requirement**: AI must follow Test-Driven Development (TDD): write the test first to establish red proof before touching production code, then implement, then refactor. Refer to `quality/testing/ai-meaningful-tests.md` for full policy.

## 4. Verification & Commands

Run commands from the repository root using `GRADLE_USER_HOME="$PWD/.gradle/task-inspect"`.

- **Iterative Check**: `./gradlew fastQualityCheck`
- **Full Gate**: `./gradlew qualityCheck`
- **AI Scripts**: Use `quality/scripts/` (e.g., `ai_static_quality_check.sh`) for automated verification.
- **Commit Rule**: Run a full `qualityCheck` before creating any commit.

Refer to `quality/README.md` for the full command matrix and triage guide.

## 5. Project Context

- **SDK**: `minSdk` is `26`.
- **i18n**: Update `values` and `values-zh-rCN`.
- **Baseline Profile**: release-critical. Inspect `benchmark/` and `app/src/main/baseline-prof.txt` if performance or startup logic changes.
- **Ownership**: Assume other people may be editing the tree; do not overwrite their changes.
