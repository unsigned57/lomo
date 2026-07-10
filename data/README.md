# Data Module Index

> Responsibilities and boundaries: see [ARCHITECTURE.md](../ARCHITECTURE.md). Contribution rules: see [AGENTS.md](../AGENTS.md).
> This file is a task map for locating code, not a source of truth — verify paths and APIs against the codebase before acting.

This module implements domain contracts and owns persistence, sync, background execution, and platform integration. If a task involves Room, file I/O, sync engines, DataStore-backed settings, or Android storage/network glue, it usually starts here.

## Start Here

- `src/di/DataModule.kt`
  - DI root for Room, data sources, repository bindings, and integration dependencies.
- `src/repository/MemoRepositoryImpl.kt`
  - Facade only. Useful for discovering the public memo surface, but not the place where most behavior lives.
- `src/git/GitSyncEngine.kt`
  - Serialized Git sync workflow, state publication, conflict recovery, and command-phase transitions.
- `src/local/MemoDatabase.kt`
  - Room schema root and DAO registry.

## Key Subdirectories

- `repository/`
  - Main implementation surface. Memo CRUD, refresh, search, trash, version history, workspace access, settings, update, and sync repositories all live here.
- `local/dao`, `local/entity`, `local/datastore`
  - Room persistence model and preference storage.
- `git/`
  - Git workflow primitives, init/clone/commit/sync coordination, conflict recovery.
- `s3/`
  - S3 client factories, crypto compatibility, endpoint helpers.
- `webdav/`
  - WebDAV integration support and remote file access helpers.
- `share/`
  - LAN sharing transport and related storage/integration helpers.
- `worker/`
  - Background work entrypoints and scheduling support.
- `source/`
  - File-system and SAF-backed storage abstractions.
- `parser/`, `memo/`, `media/`, `security/`, `util/`
  - Lower-level helpers used by the repository and sync layers.

## Common Tasks

### Memo CRUD bug

Start with the specialized repository classes in `repository/`, not `MemoRepositoryImpl` itself:

- `MemoQueryRepositoryImpl`
- `MemoMutationRepositoryImpl` support files
- `MemoSearchRepositoryImpl`
- trash/version helpers if the bug involves deletion or history

Look for supporting files such as `MemoMutationHandler`, `MemoMutationDbTransactions`, `MemoMutationFileOps`, and outbox delegates.

### Refresh or reindex issue

Start in:

- `MemoRefreshEngine`
- `MemoRefreshPlanner`
- `MemoRefreshDbApplier`
- `MemoRefreshParserWorker`

These files explain how markdown files are scanned, parsed, planned, and applied into Room.

### Version history issue

Start in:

- `MemoVersionJournal`
- `MemoVersionJournalAppendSupport`
- `MemoVersionJournalPreviewSupport`
- `RestoreMemoRevisionMutationDelegate` for lifecycle/outbox restore execution
- `MemoVersionRepositoryImpl` and `RoomMemoVersionStore` for version history, blobs,
  queries, and restore snapshot handoff

### Workspace path or media issue

Start in:

- `WorkspaceStateResolver`
- `WorkspaceTransitionRepositoryImpl`
- `WorkspaceMediaAccess`
- `WorkspaceMediaDirectAccess`
- `WorkspaceMediaSafAccess`

If the issue crosses sync/storage boundaries, also inspect the relevant file bridge.

### Git sync issue

Start in:

- `repository/GitSyncRepositoryImpl`
- `git/GitSyncEngine`
- supporting `GitSync*` repository/context/support files

Use `GitSyncEngine` to understand serialized state and phase transitions. Use repository classes to understand app-facing contract handling.

### S3 sync issue

Start in:

- `repository/S3SyncRepositoryImpl`
- `repository/S3SyncExecutor`
- `repository/S3SyncPlanner`
- `repository/S3SyncFileBridge*`

S3 sync is intentionally decomposed across planner, executor, reconcile, file bridge, and metadata support files. Expect the behavior to be spread out.

### WebDAV sync issue

Start in:

- `repository/WebDavSyncRepositoryImpl`
- `repository/WebDavSyncPlanner`
- `repository/WebDavSyncFileBridge`

### Room migration or corruption issue

Start in:

- `local/DatabaseTransitionStrategy.kt`
- `local/MemoDatabase.kt`
- the relevant DAO/entity pair

`DataModule` also matters because it contains the open-and-recover behavior for migration failure paths.

### DI or binding issue

Start in `di/DataModule.kt` and confirm the relevant repository, DAO, or storage source is actually provided and bound there.

If the real change is a contract or business rule rather than an implementation detail, switch to `domain/README.md` first.

## High-Risk Areas

- sync code
  - Git, S3, and WebDAV flows are branch-heavy and regression-prone.
- workspace and file bridge code
  - SAF paths, file mirroring, and remote/local reconciliation can drift subtly.
- refresh pipeline
  - planner/parser/db-apply boundaries can create stale-index bugs.
- Room transitions
  - migration and recovery behavior has user-data implications.
