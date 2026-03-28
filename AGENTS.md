# Lomo Agent Guide

This file gives AI agents the minimum project context needed to work effectively without re-discovering the same repository structure on every task.

## 1. Project Summary

- `Lomo` is an Android app built with Jetpack Compose and Material 3.
- Product goal: local-first Markdown memo app with offline-first behavior, LAN sharing, and Git backup/sync.
- `.md` files are the source of truth. Room exists for indexing, cache, and supporting state.
- Architecture is MVVM + Clean Architecture with `app`, `domain`, `data`, and `ui-components`.

## 2. Module Ownership

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

## 3. Critical Flows

- Memo write path:
  - `app` calls `MemoRepository` contracts.
  - `data` handles writes through `MemoRepositoryImpl`, `MemoSynchronizer`, and `MemoMutationHandler`.
  - Current behavior is DB-first with async outbox-based file flush.
- File storage:
  - `FileDataSourceImpl` switches between `SafStorageBackend` and `DirectStorageBackend`.
- Git sync:
  - `GitSyncRepositoryImpl` delegates to `GitSyncEngine` and related bridge/scheduler/worker code.
- LAN share:
  - `ShareServiceManager` implements `LanShareService`; sharing code lives under `data/share`.

## 4. Working Defaults

- Build and version source of truth lives in module Gradle files, especially `app/build.gradle.kts`.
- `minSdk` is `26`.
- If strings change, update at least `values` and `values-zh-rCN`.
- Baseline profile is release-critical. If baseline generation or release startup behavior changes, inspect:
  - `benchmark/src/main/java/com/lomo/benchmark/BaselineProfileGenerator.kt`
  - `app/src/main/baseline-prof.txt`
- Useful commands:
  - `./gradlew testDebugUnitTest`
  - `./gradlew architectureCheck`
  - `./gradlew meaningfulTestCheck`
  - `./gradlew qualityCheck`
  - `env GRADLE_USER_HOME="$PWD/.gradle/task-inspect" ./gradlew --no-daemon --no-configuration-cache --console=plain qualityCheck`
  - `./gradlew :app:assembleRelease`
- Pre-commit formats staged Kotlin and runs meaningful-test metadata checks.

## 4.1 Verification Discipline

- For any code, build-script, or test change, AI must immediately run `env GRADLE_USER_HOME="$PWD/.gradle/task-inspect" ./gradlew --no-daemon --no-configuration-cache --console=plain qualityCheck` after each coherent edit batch.
- Do not wait for the user to remind you to verify changes.
- Docs-only changes may skip this script, but the AI must say that it intentionally skipped verification.
- If the tree is intentionally left broken mid-refactor, the AI must say so explicitly and run the quality command as soon as the code returns to a coherent state, before continuing with more substantial edits and before the final handoff.
- Targeted tests are still encouraged while iterating, but they do not replace the mandatory quality command run after code-edit batches.

## 5. Mandatory Architecture Rules

- Runtime business flow must be `UI -> Domain -> Data`.
- Compile-time dependency direction must be `app -> domain` and `data -> domain`.
- `app` must not import `com.lomo.data.*`.
- `ui-components` must not import `com.lomo.data.*`.
- `domain` must not depend on Android, Compose, Lifecycle, Room, Hilt/Dagger, Ktor, JGit, or any `com.lomo.data.*` type.
- ViewModels must not depend directly on DAO, DataSource, RoomDatabase, repository implementations, Git/WebDAV engines, `DocumentFile`, or direct file-system helpers.
- New business behavior must be modeled in `domain` first, then consumed from `app`, then implemented in `data`.
- New repository implementations belong in `data/repository`.
- New DAOs belong in `data/local/dao`.
- New entities belong in `data/local/entity`.
- Do not treat existing violations as precedent. Contain or refactor them instead of copying the pattern.
- If architecture-sensitive code changes, include an `Architecture Impact` note with:
  - owning layer
  - exposed domain use case or repository interface
  - any boundary exception introduced

## 6. Test-First Rules

- Read `quality/testing/ai-meaningful-tests.md` before writing or editing any test.
- Prefer meaningful behavior-bearing tests over line coverage.
- For new features, bug fixes, and contract changes, write or modify the relevant test before related production code.
- For new features, bug fixes, and contract changes, establish red proof before touching production code.
- If the task is truly test-only, state that explicitly and keep production files unchanged.
- Changed test files must include the required contract metadata, including `Red phase`.
- Existing tests are locked behavior contracts by default. Do not delete, weaken, or rewrite them just to match a new implementation.
- When an existing test fails during behavior-changing work, treat that failure as evidence the production code may be wrong first. Do not assume the test should change.
- AI must not:
  - delete an existing failing test to unblock a change
  - weaken assertions, remove edge or failure-path coverage, or replace observable outcome assertions with mock-call-only checks
  - change test inputs purely to sidestep the original scenario under test
  - rewrite `Red phase` to `Not applicable` when production behavior changed
- AI may modify an existing test only when at least one of these is true:
  - the product or domain contract explicitly changed
  - the old assertion is factually wrong
  - the old test is nondeterministic or environment-coupled
  - a pure refactor preserved behavior but requires mechanical test reshaping
- Before modifying an existing test, AI must provide a `Test Change Justification` that states:
  - reason category
  - exact behavior or assertion being replaced
  - why the previous assertion is no longer correct
  - what retained or new test preserves the original risk coverage
  - why this is not “changing the test to fit the implementation”

## 7. Hotspots To Inspect First

- Settings changes:
  - `PreferencesRepository`
  - `DirectorySettingsRepository`
  - `SettingsRepositoryImpl`
- Sync changes:
  - `GitSyncRepositoryImpl`
  - `GitSyncEngine`
  - `GitSyncScheduler`
  - `GitSyncWorker`

## 8. High-Value Entry Points

- App entry: `app/src/main/java/com/lomo/app/LomoApplication.kt`
- Navigation host: `app/src/main/java/com/lomo/app/navigation/LomoNavHost.kt`
- Main ViewModel: `app/src/main/java/com/lomo/app/feature/main/MainViewModel.kt`
- Data DI: `data/src/main/java/com/lomo/data/di/DataModule.kt`
- Memo repository implementation: `data/src/main/java/com/lomo/data/repository/MemoRepositoryImpl.kt`
- Git sync engine: `data/src/main/java/com/lomo/data/git/GitSyncEngine.kt`
- Quality guide: `quality/README.md`
- Meaningful test policy: `quality/testing/ai-meaningful-tests.md`
- Meaningful test prompt: `quality/testing/ai-meaningful-test-prompt.md`
