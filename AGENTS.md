# Lomo Agent Guide

This file helps future AI agents build project context quickly and avoid repeating repository discovery work.

## 1. Project Summary

- Name: `Lomo`
- Platform: Android (Jetpack Compose + Material 3)
- Goal: Local-first Markdown memo app with offline-first behavior, LAN sharing, and Git backup/sync
- Data model: `.md` files are the source of truth; Room is used for indexing, cache, and supporting state

## 2. Module Layout

- `:app`
  - App entrypoint and UI orchestration (Compose screens, ViewModels, navigation, widgets, updates, settings)
  - Key entry files:
    - `app/src/main/java/com/lomo/app/LomoApplication.kt`
    - `app/src/main/java/com/lomo/app/MainActivity.kt`
    - `app/src/main/java/com/lomo/app/navigation/LomoNavHost.kt`
- `:domain`
  - Pure contracts and models (repository interfaces, use cases, domain models, validators)
  - No concrete data-source implementation
- `:data`
  - Data implementation layer (Room, DataStore, file I/O, Git sync, LAN sharing, workers)
  - Main DI entrypoint: `data/src/main/java/com/lomo/data/di/DataModule.kt`
- `:ui-components`
  - Reusable UI components and theme system (Markdown renderers, cards, menus, navigation UI, theming)
- `:benchmark`
  - Baseline Profile generation and benchmark module (Macrobenchmark)

## 3. Architecture and Critical Flows

- Architecture: MVVM + Clean Architecture (`domain` / `data` / `app` + `ui-components`)
- Memo write path (important):
  - `app` calls `MemoRepository` interfaces
  - `data` implements via `MemoRepositoryImpl` + `MemoSynchronizer` + `MemoMutationHandler`
  - Current strategy is DB-first with async outbox-based file flush
- File storage path:
  - `FileDataSource` abstraction
  - `FileDataSourceImpl` switches between `SafStorageBackend` and `DirectStorageBackend`
- Git sync path:
  - `GitSyncRepositoryImpl` -> `GitSyncEngine` / `SafGitMirrorBridge`
  - Periodic scheduling via `GitSyncWorker` + `GitSyncScheduler`
- LAN share path:
  - `ShareServiceManager` (implements `LanShareService`)
  - Ktor + NSD + auth/crypto utilities under `data/share`

## 4. Versioning and Build Facts

- AGP: `9.0.1`
- Kotlin: `2.3.10`
- JDK toolchain: `25`
- `compileSdk/targetSdk`: `36`
- `minSdk`: `26` (authoritative source: `app/build.gradle.kts`)
- App version source:
  - `app/build.gradle.kts` `defaultConfig.versionCode/versionName`

## 5. Baseline Profile (Release-Critical)

- Enabled in app module via `androidxBaselineProfile` plugin
- Baseline inputs:
  - `app/src/release/generated/baselineProfiles/baseline-prof.txt`
  - `app/src/release/generated/baselineProfiles/startup-prof.txt`
  - `app/src/main/baseline-prof.txt` (supplement/fallback)
- Generation logic:
  - `benchmark/src/main/java/com/lomo/benchmark/BaselineProfileGenerator.kt`
  - Current parameters: `maxIterations = 10`, `stableIterations = 3`
- CI release checks:
  - Baseline files exist and are non-empty
  - Release APK contains `assets/dexopt/baseline.prof` and `assets/dexopt/baseline.profm`

## 6. Common Commands

- Debug build: `./gradlew assembleDebug`
- Unit tests: `./gradlew testDebugUnitTest`
- Release Kotlin compile only: `./gradlew :app:compileReleaseKotlin`
- Release build: `./gradlew :app:assembleRelease`
- Generate baseline profile: `./gradlew :app:generateBaselineProfile`

## 7. Commit and CI Constraints

- Git hook exists at `.githooks/pre-commit`
  - Runs `ktlintFormat` on staged `*.kt` / `*.kts`
  - Re-stages files modified by formatting
- GitHub release workflow: `.github/workflows/android_release.yml`
  - Release build is triggered on `v*` tags
  - Includes baseline file checks and APK embedded-profile checks

## 8. AI Coding Guidelines for This Repo

- Respect layer ownership:
  - contracts/models in `domain`
  - implementations in `data`
  - screen/business orchestration in `app`
  - reusable UI in `ui-components`
- Prefer existing abstractions; avoid cross-layer shortcuts
- For settings changes, inspect first:
  - `PreferencesRepository`
  - `DirectorySettingsRepository`
  - `SettingsRepositoryImpl`
- For sync-related changes, inspect first:
  - `GitSyncRepositoryImpl`
  - `GitSyncEngine`
  - `GitSyncScheduler` / `GitSyncWorker`
- If strings change, sync localization (at least `values` and `values-zh-rCN`)
- If baseline flow changes, regenerate profile and verify release APK embedding

## 9. MVVM + Clean Architecture Rules (Mandatory)

- Layer responsibilities (must follow)
  - UI layer (`app`: Activity/Fragment/Compose/ViewModel) is limited to UI state handling, interaction orchestration, navigation, and UseCase invocation. Business rules must not be implemented here.
  - Domain layer (`domain`) contains only UseCases / Entities / Repository interfaces / pure domain rules. It must not depend on Android framework types or concrete data implementations.
  - Data layer (`data`) contains only Repository implementations / DataSources / DAO / DTO / mappers / external system adapters. It must not contain UI logic.
- Dependency and call direction (both required)
  - Runtime business call chain must be: `UI -> Domain -> Data`.
  - Compile-time dependencies must be: `app -> domain`, `data -> domain`; `domain -> app` and `domain -> data` are forbidden.
- Cross-layer access bans (zero tolerance)
  - ViewModels must not access DataSource / DAO / Retrofit / file system / Git engine or other Data-layer details directly.
  - UI layer must not directly implement domain rules such as transactions, sync strategy, conflict merge policy, or permission policy.
  - Data layer must not depend on UI types (for example: `ViewModel`, `UiState`, Compose types).
- Implementation requirements
  - New business rules must first be modeled in `domain` as a UseCase or domain rule, then invoked by `app` and implemented by `data`.
  - New data sources must be exposed upward through Repository interfaces; DataSource must not be exposed directly to upper layers.
  - Code review must check God Classes, business-logic placement, cross-layer dependencies, and responsibility boundaries; any violation blocks merge.

## 10. Quick Navigation Index

- App entry: `app/src/main/java/com/lomo/app/LomoApplication.kt`
- Navigation host: `app/src/main/java/com/lomo/app/navigation/LomoNavHost.kt`
- Main VM: `app/src/main/java/com/lomo/app/feature/main/MainViewModel.kt`
- Data DI: `data/src/main/java/com/lomo/data/di/DataModule.kt`
- Memo repository impl: `data/src/main/java/com/lomo/data/repository/MemoRepositoryImpl.kt`
- Git sync engine: `data/src/main/java/com/lomo/data/git/GitSyncEngine.kt`
- Baseline generator: `benchmark/src/main/java/com/lomo/benchmark/BaselineProfileGenerator.kt`
