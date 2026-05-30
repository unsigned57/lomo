# Architecture Overview

This document is the stable architecture entrypoint for the repository. It describes module boundaries and dependency direction only. It intentionally avoids volatile directory inventories so it stays useful as the tree changes.

## Modules

### `domain`

- Pure business layer.
- Owns domain models, repository contracts, and use cases.
- Must stay free of Android, Compose, Lifecycle, Room, Hilt/Dagger, Ktor, JGit, and any `com.lomo.data.*` type.

### `data`

- Infrastructure and integration layer.
- Implements `domain` contracts and owns persistence, sync engines, file access, network/storage glue, and background work support.
- New repository implementations belong here, typically under `data/repository`.

### `app`

- Android application and feature orchestration layer.
- Owns screens, navigation, ViewModels, settings orchestration, widgets, and app startup behavior.
- Consumes `domain` contracts and shared UI from `ui-components`.
- Must not import `com.lomo.data.*`.

### `ui-components`

- Shared UI infrastructure layer.
- Owns reusable Compose components, markdown/text rendering, input surfaces, and theme primitives.
- Must not import `com.lomo.data.*`.
- Keep feature orchestration and repository logic out of this module.

## Dependency Direction

- `domain` sits at the center and should not depend on other project modules.
- `data` depends on `domain`.
- `app` depends on `domain` and `ui-components`.
- `ui-components` may depend on stable presentation-safe types, but must not depend on `data` and should avoid feature/business orchestration concerns.

## Change Routing

- New business behavior starts in `domain`, then gets implemented in `data`, and finally consumed from `app`.
- New repository implementations belong in `data`.
- ViewModels should depend on `domain` contracts and use cases, never on DAO, RoomDatabase, repository implementations, sync engines, `DocumentFile`, or direct filesystem helpers.

## Enforcement

- `AGENTS.md` carries the AI workflow and architecture gate.
- `quality/detekt-rules` enforces repository-specific architecture rules.
- `quality/README.md` is the verification entrypoint for build, lint, detekt, coverage, and quality scripts.

## Documentation Trust Model

- Treat this file and `AGENTS.md` as the durable architecture source.
- Treat module README files as best-effort orientation only.
- When a decision depends on a concrete path, API, or current implementation detail, verify it against the codebase before acting.
