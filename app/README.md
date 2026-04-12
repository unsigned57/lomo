# App Module Index

This module owns app-level UI orchestration. It wires screens, navigation, ViewModels, settings flows, widgets, and app startup behavior on top of `domain` contracts and shared UI from `ui-components`.

## What Belongs Here

- screens and screen-specific ViewModels
- navigation graph and route wiring
- app startup, theme application, and WorkManager bootstrap
- settings orchestration and feature coordinators
- widgets, share-card presentation, and Android-facing app adapters

## What Does Not Belong Here

- repository implementations
- DAO, Room, file-system, or sync-engine code
- durable business rules that should live in `domain`
- reusable cross-feature UI primitives that belong in `ui-components`

## Start Here

- `src/main/java/com/lomo/app/LomoApplication.kt`
  - Application startup, WorkManager configuration, theme resync, sync bootstrap.
- `src/main/java/com/lomo/app/navigation/LomoNavHost.kt`
  - Top-level navigation graph, route composition, and cross-screen payload routing.
- `src/main/java/com/lomo/app/feature/main/MainViewModel.kt`
  - Highest-signal ViewModel in the module. Aggregates root directory state, memo list state, gallery state, shared content events, startup flow, and mutation coordination.

## Key Subdirectories

- `feature/main`
  - Main feed, input sheet coordination, list animation, startup and workspace flow.
- `feature/memo`
  - Memo editing, memo-card interaction, version-history sheet, memo action menus.
- `feature/settings`
  - Largest orchestration surface in the module. Contains settings screens, dialog state, and Git/WebDAV/S3/LAN share coordinators.
- `feature/share`
  - Share screen state, LAN share coordination, device discovery, transfer banners, share error handling.
- `feature/search`, `feature/tag`, `feature/trash`, `feature/review`, `feature/gallery`, `feature/image`
  - Specialized screen entrypoints and feature-local presentation logic.
- `feature/common`
  - Shared UI coordinators, flow-sharing helpers, deletion animation helpers, user-facing error mapping.
- `navigation`
  - Navigation graph, route types, transition wiring, route payload stores.
- `presentation`
  - Presentation-only helpers such as share-card formatting/render preparation.
- `provider`
  - Android-facing providers and content access helpers used by orchestration code.
- `widget`
  - Home screen widget entrypoints and widget-specific glue.
- `theme`, `util`
  - App-scoped theme integration and Android utility helpers that should not move into shared UI.

## Common Tasks

### Add a new screen

Start in `navigation/` to see how destinations are declared and wired. Then place the screen and ViewModel under the nearest `feature/<name>/` package. If the screen needs new behavior, define the contract in `domain` first.

### Change main feed behavior

Start in `feature/main/MainViewModel.kt`, then inspect nearby coordinators such as `MainWorkspaceCoordinator`, `MainStartupCoordinator`, `MainMemoMutationCoordinator`, and `MemoUiMapper`. If the issue is really a business rule, move into `domain` instead of piling logic into the ViewModel.

### Change settings behavior

Start in `feature/settings/SettingsScreen.kt` and `feature/settings/SettingsViewModel.kt`, then open the specific coordinator family for the integration you are touching:

- `SettingsGit*`
- `SettingsWebDav*`
- `SettingsS3*`
- `SettingsLanShare*`
- `SettingsAppConfigCoordinator`

### Wire a new domain use case into UI

Locate the screen/ViewModel that owns the user-facing state transition, inject the use case there, and keep the ViewModel focused on state orchestration. Do not import a `data` implementation directly.

### Fix a ViewModel state bug

Start with the feature ViewModel, then inspect:

- feature-local coordinator classes
- `feature/common` flow-sharing helpers
- mappers that turn domain models into UI models

Most bugs here are state-shaping or event-consumption issues, not storage-layer issues.

### Add or adjust navigation

Start in `navigation/LomoNavHost.kt` and the route definitions. Follow the existing pattern for payload stores when navigating large memo/image payloads between screens.

### Touch widgets or share-card rendering

Start in `widget/` or `presentation/sharecard/` first. These are app-specific presentation surfaces, not shared reusable UI components.

## High-Risk Areas

- `feature/main`
  - Rich state aggregation, event queues, startup timing, and animation/state retention make regressions easy.
- `feature/settings`
  - Multiple remote-sync integrations and dialog flows converge here.
- `navigation`
  - Route/payload mistakes create broken back-stack or payload-loss bugs.
- widget/share-card code
  - Android integration and rendering behavior can diverge from main-screen expectations.

## How This Module Connects To Other Modules

- Uses `domain` for business contracts and use cases.
- Uses `ui-components` for reusable UI primitives, markdown rendering, input, cards, and theme building blocks.
- Must not import `com.lomo.data.*`.

## Rules For AI Contributors

- Do not place repository or Room logic here.
- Do not bypass architecture problems by importing `data` types.
- Do not introduce `@Suppress`, `@SuppressLint`, or `@SuppressWarnings` in new or modified code.
- If a composable hides branch-heavy behavior, prefer extracting that behavior into a ViewModel or coordinator instead of adding more inline state logic.

## When To Read Deeper Files

- Read deeper when a task is clearly inside one feature package.
- Stop descending once you have the owning ViewModel/coordinator/screen and can identify the code to change.
- If a feature change starts forcing business-rule decisions, switch to `domain/README.md` before continuing.
