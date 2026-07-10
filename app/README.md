# App Module Index

> Responsibilities and boundaries: see [ARCHITECTURE.md](../ARCHITECTURE.md). Contribution rules: see [AGENTS.md](../AGENTS.md).
> This file is a task map for locating code, not a source of truth — verify paths and APIs against the codebase before acting.

This module owns app-level UI orchestration. It wires screens, navigation, ViewModels, settings flows, widgets, and app startup behavior on top of `domain` contracts and shared UI from `ui-components`.

## Start Here

- `src/LomoApplication.kt`
  - Application startup, WorkManager configuration, theme resync, sync bootstrap.
- `src/navigation/LomoNavHost.kt`
  - Top-level navigation graph, route composition, and cross-screen payload routing.
- `src/feature/main/MainViewModel.kt`
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

Most bugs here are state-shaping or event-consumption issues, not storage-layer issues. If the change forces a business-rule decision, switch to `domain/README.md` first.

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
