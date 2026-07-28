# Stage-5 sync owner inventory (P5-00)

> Language-agnostic inventory of current production owners, dependencies, and tail-deletion targets.
> Paths are repository-relative. Status is **production Kotlin-owned until P5-13**.

## 1. Rust owners (current)

| Package / path | Role today | Stage-5 target |
| --- | --- | --- |
| `rust/sync-core` (`lomo-sync-core`) | Frozen sync-v1 binary planner (S3/WebDAV envelope) | Absorbed/replaced by `lomo-sync`; crate deleted at cutover |
| `rust/core` (`lomo-core`) | Job actor / journal / lock | Extend actor-external native task + secret vault (P5-02) |
| `rust/workspace` (`lomo-workspace`) | Markdown/path owner | Lift generic `.lomo` record codec; path policy |
| `rust/store` (`lomo-store`) | Local data loop | Add sync snapshot + expected-revision apply ports (P5-04) |
| `rust/media` (`lomo-media`) | Media identity | Committed+verified media only at sync edge |
| `rust/native` (`lomo-native`) | BoltFFI conversion facade | Dark then production sync FFI conversion only |
| `rust/sync` (`lomo-sync`) | **dark host (P5-03)** | Sole sync owner (P5-03+); not in production DI until P5-13 |
| `rust/git` (`lomo-git`) | **dark host (P5-07)** | Sole git2 adapter; not in production DI until P5-13 |

Public frozen sync-v1 surface (must not grow consumers before cutover):

- `plan`, `plan_envelope`, `encode_request`, `decode_plan`, `validate_*`
- models: `Backend`, `Request`, `Plan`, `LocalSnapshot`, `RemoteSnapshot`, `Action`, …

## 2. Kotlin production owners (current)

### 2.1 Domain contracts

- `domain/src/model/SyncBackendType.kt`, `UnifiedSyncModels.kt`, `SyncEngineState.kt`
- `domain/src/model/S3SyncModels.kt`, `WebDavSyncModels.kt`, `SyncConflictModels.kt`, …
- `domain/src/repository/{Git,S3,WebDav}SyncRepository.kt`, `UnifiedSyncProvider.kt`, …
- `domain/src/usecase/SyncProviderRegistry.kt`, `UnifiedSyncProviders.kt`, settings/conflict use cases

### 2.2 Data — Git (JGit)

`data/src/git/` (16 files): engine, workflow, coordinators, media planner, SAF mirror, credentials, primitives.

Key: `GitSyncEngine.kt`, `GitSyncWorkflow.kt`, `SafGitMirrorBridge.kt`, `GitRepositoryPrimitives.kt`.

### 2.3 Data — S3 (AWS Kotlin SDK + rclone crypt)

`data/src/s3/` (4 files): `LomoS3Client.kt`, `S3RcloneCryptCompatCodec.kt`, credentials, Base32768.

`data/src/repository/S3*.kt` (~56 files): planner, executor, file bridge, reconcile, index/shard, conflict, apply, …

### 2.4 Data — WebDAV (OkHttp)

`data/src/webdav/` (9 files): client, endpoint, credential, local media sync store family.

`data/src/repository/WebDav*.kt` (~16 files): planner, operation/conflict repos, file bridge, caches, …

### 2.5 Data — shared sync / workers / durable metadata

- `data/src/sync/` — Rust sync-v1 encoder/decoder/client, work policy, layout migration, conflict merge
- `data/src/worker/{Git,S3,WebDav}Sync{Worker,Scheduler}.kt`, `SyncWorker*.kt`, periodic scheduling
- `data/src/local/FileBackedSyncDatabase.kt` + DAO/entity family for pending conflict/review, S3 index/shard/journal, WebDAV fingerprint/journal/metadata
- `data/src/repository/PendingSync*.kt`, `RemoteSyncLifecycle*.kt`, `RemoteSyncPlannerCore.kt`, `SyncInbox*`, `SyncStateReset*`, `WorkspaceScopedSyncMetadataStores.kt`, generation provider
- `data/src/network/SyncHttpClientProvider.kt`, `SyncHttpRetryPolicy.kt`
- `data/src/di/SyncDataModule.kt`

### 2.6 App UI (settings/conflict; not sole business owner)

- `app/src/feature/settings/*Sync*`, `*Git*`, `*S3*`, `*WebDav*`
- `app/src/feature/conflict/*`
- `app/src/di/DomainSyncBindingsModule.kt`

Sync Inbox remains a **product capability** decoupled from remote-sync registry at cutover.

## 3. Production dependencies (tail candidates)

| Dependency | Owner module | Stage-5 fate |
| --- | --- | --- |
| `org.eclipse.jgit:org.eclipse.jgit:7.6.0…` | `data` | Delete with Git Kotlin owner |
| `aws.sdk.kotlin:s3` + BOM `1.6.73` | `data` | Delete with S3 Kotlin owner |
| `aws.smithy.kotlin:http-client-engine-okhttp` | `data` | Delete if only sync-used |
| `org.bouncycastle:bcprov-jdk18on:1.84` | `data` | Delete if only rclone/S3 crypto |
| OkHttp/Ktor client pieces used solely for WebDAV/S3 | `data` | Re-evaluate; keep only non-sync needs |
| WorkManager | `data`/`app` | Keep generic runner only |
| Android Keystore / DataStore | Kotlin | Keep config + secret decrypt |

Rust candidates (dark/production after gates):

| Crate | Role | Notes |
| --- | --- | --- |
| `reqwest` + `rustls` | WebDAV/HTTP | Already workspace-pinned for feasibility |
| `git2` vendored | Git adapter | Already workspace-pinned; only via `lomo-git` |
| AWS Rust SDK (minimal S3 features) | S3 adapter | Stage-5 spike; pin compatible with Rust 1.97 |
| Audited crypto primitives for rclone crypt | S3 filename/data | No custom cipher; golden vs `fixtures/remote/rclone-crypt-vectors.json` |

## 4. Remote layout fixtures (language-agnostic)

- `fixtures/remote/s3-layout.json` — existing remote object roots (`lomo/memo|media|voice|.index`)
- `fixtures/remote/webdav-layout.json` — collection paths
- `fixtures/remote/rclone-crypt-vectors.json` — password/password2, standard filename + dir encryption vectors
- `fixtures/git/` — Git probe/corpus assets (tooling)

## 5. Tail deletion list (P5-13/P5-14 only — do not execute in P5-00)

1. Kotlin Git engine/workflow/primitives/media planner/SAF mirror and related repository impls.
2. Kotlin S3 planner/executor/client/rclone codec/file bridge/index/shard/journal family.
3. Kotlin WebDAV client/planner/operation/conflict/file bridge/fingerprint family.
4. Provider-specific WorkManager business rules; retain only generic Rust job runner scheduling.
5. `RustSyncRequestEncoder` / decoder / wire ordinal / sync-v1 envelope consumers.
6. Pending remote conflict/review descriptors **after** old workers/journals are quiescent; **never**
   delete independent Sync Inbox.
7. File-backed sync Room-style DAO/entity remnants under `data/src/local` that encode remote sync
   authority (replace with `.lomo/sync/v1` durability).
8. JGit, AWS Kotlin SDK, BouncyCastle if only sync-owned, and frozen `lomo-sync-core` after absorption.
9. Provider-specific conflict models/UI branch semantics that re-interpret completion outside Rust events.
10. Force/reset Git paths and any Git-only SAF user-file mirror.

## 6. Production wiring rule

- **P5-00…P5-12:** production DI/registry/scheduler remain Kotlin owners; dark Rust may compile/test.
- **P5-13:** atomic switch; same change deletes tails in section 5.
- **No** `use_rust_sync`, dual-write, or progressive dual DI flags at any time.

## 7. P5-13 cutover prep inventory (Wave-12)

Detailed host-closeable checklist (Koin/`SyncDataModule`, `workerOf`, scheduler enqueue, Settings/nav
dual-wire, dark readiness, dual-stack ban, inheritance OPEN gates):

- `fixtures/baseline/stage5-p5-13-cutover-prep-inventory.v1.md` (**PREP_ONLY** — does not authorize DI flip)
