# Lomo Agent Guide

This file is the AI-first entrypoint for the repository. Read this file first, route yourself to the smallest useful next document, and stop descending once the current layer is sufficient.

## 1. Progressive Disclosure Protocol

Read in this order and only when the task actually requires the next layer:

1. `AGENTS.md`
2. One module index README if the task is clearly scoped to a module:
   - `app/README.md`
   - `data/README.md`
   - `domain/README.md`
   - `ui-components/README.md`
   - `quality/README.md`
3. `quality/README.md` only when choosing a verification command, debugging the quality chain, or changing quality/prompt/build policy
4. `quality/testing/ai-meaningful-tests.md` only before writing, editing, or reviewing tests

Default navigation rules:

- Do not pre-read every document "just in case".
- Do not load deeper docs if the current file already tells you where to work.
- Prefer the nearest module README over broad repo-wide searching when the task is module-scoped.
- If you already know the target files from the current layer, go read code directly instead of opening more docs.
- Treat module READMEs as navigation and boundary docs, not as substitutes for reading the code you will change.

## 2. Fast Path

- Product: local-first Markdown memo app with offline-first behavior, LAN sharing, and Git backup/sync.
- Source of truth: `.md` files. Room exists for indexing, cache, and supporting state.
- Runtime flow: `UI -> Domain -> Data`
- Compile-time dependencies: `app -> domain` and `data -> domain`
- Default iteration verifier for pure test/docs changes:
  - `env GRADLE_USER_HOME="$PWD/.gradle/task-inspect" ./quality/scripts/ai_fast_quality_check.sh`
- Required iteration verifier for any `src/main`, Gradle, quality, detekt, workflow, or AGENTS/prompt change:
  - `env GRADLE_USER_HOME="$PWD/.gradle/task-inspect" ./quality/scripts/ai_static_quality_check.sh`
- Final verifier before handoff:
  - `env GRADLE_USER_HOME="$PWD/.gradle/task-inspect" ./gradlew --no-daemon --no-configuration-cache --console=plain qualityCheck`
- Run repository commands from the repo root, or pass `--project-dir "$repo_root"` explicitly.
- Do not run Gradle from `/tmp` or another throwaway working directory.
- Prefer repo-local Gradle state such as `GRADLE_USER_HOME="$PWD/.gradle/task-inspect"` so wrapper distributions and caches are reused across runs.
- Do not claim a production fix is complete after only running `fastQualityCheck`; production changes must pass a detekt-capable gate first.
- Run the full `qualityCheck` immediately after any coherent edit batch that changes:
  - Gradle/build logic
  - quality tasks or scripts
  - coverage wiring
  - dependency or plugin wiring
- Docs-only changes may skip verification, but the AI must say that it intentionally skipped it.
- Never run multiple Gradle invocations in parallel in this repository.
- Assume other people may be editing the tree. Do not overwrite, revert, or reformat their changes unless explicitly asked.

## 3. Directory Index

Use these local indexes before doing broad source search:

- `app/README.md`
  - Use for screens, navigation, ViewModels, settings orchestration, widgets, and app-level wiring.
- `data/README.md`
  - Use for repository implementations, Room, file I/O, sync engines, workers, storage integration, and platform adapters.
- `domain/README.md`
  - Use for pure models, use cases, repository interfaces, and business rules that should remain platform-free.
- `ui-components/README.md`
  - Use for shared Compose UI, markdown rendering, memo cards, input surfaces, theme, and text infrastructure.
- `quality/README.md`
  - Use for Gradle verification choices, quality-task intent, Detekt/Lint/test policy, and prompt-related verification.

## 4. Module Map

- `:app`
  - UI orchestration only: screens, navigation, ViewModels, widgets, settings, app wiring.
- `:domain`
  - Pure use cases, domain models, validators, and repository interfaces.
  - No Android framework types and no concrete data implementations.
- `:data`
  - Repository implementations, Room, DataStore, file I/O, Git sync, LAN sharing, workers, platform integrations.
- `:ui-components`
  - Reusable UI, theming, markdown rendering, shared components.
- `quality/`
  - Quality tooling, meaningful-test policy, Detekt config, custom architecture rules.
- `:benchmark`
  - Baseline profile generation and benchmark code.

## 5. Hard Boundaries

- `app` must not import `com.lomo.data.*`.
- `ui-components` must not import `com.lomo.data.*`.
- `domain` must not depend on Android, Compose, Lifecycle, Room, Hilt/Dagger, Ktor, JGit, or any `com.lomo.data.*` type.
- ViewModels must not depend directly on DAO, DataSource, RoomDatabase, repository implementations, Git/WebDAV engines, `DocumentFile`, or direct file-system helpers.
- New business behavior belongs in `domain` first, then is consumed from `app`, then implemented in `data`.
- New repository implementations belong in `data/repository`.
- New DAOs belong in `data/local/dao`.
- New entities belong in `data/local/entity`.
- Do not treat an existing architecture violation as precedent.
- If architecture-sensitive code changes, include an `Architecture Impact` note with:
  - owning layer
  - exposed domain use case or repository interface
  - any boundary exception introduced

## 6. AI Code Rules

- Do not introduce `@Suppress`, `@SuppressLint`, or `@SuppressWarnings` in newly generated or modified code.
- Existing suppressions are not precedent for new code.
- If a static check complains, prefer one of:
  - refactor the code
  - move logic to the correct layer
  - tighten or centralize Detekt/Lint configuration when that is the real fix
- Do not bypass a warning by adding source-level suppression just to get the build green.

## 7. Testing Defaults

- Read `quality/testing/ai-meaningful-tests.md` before editing any test.
- For new features, bug fixes, and contract changes, establish red proof before touching production code.
- Changed test files must include the required metadata, including `Red phase`.
- Existing tests are locked behavior contracts by default. Do not delete, weaken, or rewrite them just to fit the implementation.
- When an existing test fails during behavior-changing work, treat that as evidence the production code may be wrong first.
- AI must not:
  - delete an existing failing test to unblock a change
  - weaken assertions or remove failure-path coverage
  - change test inputs purely to sidestep the original scenario
  - rewrite `Red phase` to `Not applicable` when production behavior changed
- AI may modify an existing test only when:
  - the product or domain contract changed
  - the old assertion is factually wrong
  - the old test is nondeterministic or environment-coupled
  - a pure refactor preserved behavior but requires mechanical reshaping
- Before modifying an existing test, include a `Test Change Justification` covering:
  - reason category
  - exact behavior or assertion being replaced
  - why the previous assertion is no longer correct
  - what retained or new coverage preserves the original risk
  - why this is not "changing the test to fit the implementation"

## 8. Project Defaults And Hotspots

- `minSdk` is `26`.
- If strings change, update at least `values` and `values-zh-rCN`.
- Baseline profile is release-critical. If startup or baseline generation changes, inspect:
  - `benchmark/src/main/java/com/lomo/benchmark/BaselineProfileGenerator.kt`
  - `app/src/main/baseline-prof.txt`
- Settings work:
  - `PreferencesRepository`
  - `DirectorySettingsRepository`
  - `SettingsRepositoryImpl`
- Sync work:
  - `GitSyncRepositoryImpl`
  - `GitSyncEngine`
  - `GitSyncScheduler`
  - `GitSyncWorker`
- High-value entry points:
  - `app/src/main/java/com/lomo/app/LomoApplication.kt`
  - `app/src/main/java/com/lomo/app/navigation/LomoNavHost.kt`
  - `app/src/main/java/com/lomo/app/feature/main/MainViewModel.kt`
  - `data/src/main/java/com/lomo/data/di/DataModule.kt`
  - `data/src/main/java/com/lomo/data/repository/MemoRepositoryImpl.kt`
  - `data/src/main/java/com/lomo/data/git/GitSyncEngine.kt`

## 9. Common Commands

- `./gradlew compileGateCheck`
- `./gradlew unitTestCheck`
- `./gradlew fastQualityCheck`
- `./gradlew staticQualityCheck`
- `./gradlew qualityCheck`
- `./gradlew dependencyAnalysisCheck`
- `./gradlew dependencyVulnerabilityCheck`
- `./gradlew :app:assembleRelease`
