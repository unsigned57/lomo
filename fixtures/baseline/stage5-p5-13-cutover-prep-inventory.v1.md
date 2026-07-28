# Stage-5 P5-13 cutover prep inventory (host-closeable, no cutover)

> Status: **HISTORICAL PREP (Wave-12).** **HOST CUTOVER LANDED 2026-07-24 (P5-13 PASS_WITH_RESIDUAL).** This inventory remains as the checklist evidence of what was deleted; production DI is now Rust-only.
>
> Former status: **PREP_ONLY (Wave-12, 2026-07-24).** This document is the versioned checklist for the
> **future** atomic P5-13 production cutover. It does **not** authorize flipping production DI,
> `workerOf`, Settings dual-wire, navigation, or scheduler enqueue. Dual-stack remains fail-closed.
>
> **Do not flip production DI.** Dark Rust surfaces stay unregistered until the single atomic P5-13
> wave deletes Kotlin sync business owners in the same change.

## 1. Purpose and hard ban

| Rule | Status |
| --- | --- |
| Production dual-stack sync (Kotlin + Rust) | **FORBIDDEN** before and after cutover (after cutover only Rust owners remain) |
| `use_rust_sync` / `USE_RUST_SYNC` / dual-write flags | **FORBIDDEN** at all times |
| Progressive dual DI / dual-wire Settings | **FORBIDDEN** |
| This inventory as evidence of P5-13 GREEN | **FORBIDDEN** — prep only |
| Atomic P5-13 + same-change Kotlin tail deletion | **REQUIRED** at cutover |

## 2. Production Kotlin surfaces to delete / replace at P5-13

### 2.1 Koin / `SyncDataModule` binds (`data/src/di/SyncDataModule.kt`)

Live production module wires **only** Kotlin Git / WebDAV / S3 owners today. At P5-13, replace the
business graph with Rust-backed ports; do **not** dual-bind.

| Surface (current) | Fate at P5-13 |
| --- | --- |
| `workerOf(::GitSyncWorker)` | Delete; replace with single `workerOf(::RustSyncWorker)` (or equivalent generic runner) |
| `workerOf(::S3SyncWorker)` | Delete |
| `workerOf(::WebDavSyncWorker)` | Delete |
| `workerOf(::SyncWorker)` | Re-evaluate; keep only if non-provider generic |
| `GitSyncScheduler` / `S3SyncScheduler` / `WebDavSyncScheduler` | Delete provider business schedulers; one shared enqueue path |
| `GitWorkManagerScheduledSyncWorkEnqueuer` / `WebDavWorkManagerScheduledSyncWorkEnqueuer` / `S3ScheduledSyncWorkEnqueuer` | Collapse to one enqueuer for Rust job |
| `GitSyncEngine` + Git repository impl family | Delete with Git Kotlin owner |
| `S3SyncRepositoryImpl` + planner/executor/file-bridge/index/shard/journal family | Delete with S3 Kotlin owner |
| `WebDavSyncRepositoryImpl` + planner/executor/file-bridge/fingerprint family | Delete with WebDAV Kotlin owner |
| `GitUnifiedSyncProvider` / `S3UnifiedSyncProvider` / `WebDavUnifiedSyncProvider` | Replace with Rust-backed unified provider(s); **keep** `InboxUnifiedSyncProvider` product capability |
| `RustSyncEnvelopePlanner` / `BoltFfiRustSyncEnvelopePlanner` / `RustSyncPlannerClient` | Delete frozen sync-v1 consumers after absorption |
| `AwsSdkS3ClientFactory` / `OkHttpWebDavClientFactory` (sync-only) | Delete with provider Kotlin owners |
| `DefaultRemoteSyncLifecycleRunner` / provider lifecycle owners | Replace with Rust session/lifecycle mapping |

**Cutover ban today:** `SyncDataModule` must **not** import or bind
`RustSyncWorker`, `RemoteSyncRustWorkExecutor`, `BoltFfiRemoteSyncRepository`,
`RemoteSyncCenterRepositoryAdapter`, or any `use_rust_sync` flag.

### 2.2 WorkManager `workerOf` + scheduler enqueue

| Surface | Path | P5-13 action |
| --- | --- | --- |
| Production workers | `data/src/worker/{Git,S3,WebDav}SyncWorker.kt`, `SyncWorker.kt` | Delete provider workers after quiescence |
| Production schedulers | `data/src/worker/{Git,S3,WebDav}SyncScheduler.kt` | Delete / collapse |
| Dark worker (ready, unregistered) | `data/src/worker/RustSyncWorker.kt` | **Register once** at cutover (`workerOf(::RustSyncWorker)` only then) |
| Dark executor (ready, unregistered) | `data/src/worker/RustSyncWorkExecutor.kt`, `RemoteSyncRustWorkExecutor.kt` | Bind as sole work executor at cutover |
| Shared enqueue | settings coordinators + scheduled enqueuers | Point to single Rust work name; no dual enqueue |

### 2.3 Settings / nav dual-wire (must stay single-stack)

| Surface | Path | Today | P5-13 |
| --- | --- | --- | --- |
| Domain sync settings use cases | `app/src/di/DomainSyncBindingsModule.kt` | Kotlin provider settings | Rebind to Rust-backed config/status ports |
| Settings Git / S3 / WebDAV coordinators | `app/src/feature/settings/Settings*Coordinator*.kt`, `*SyncSections.kt` | Production Kotlin engines | Present Rust diagnostics / config only — **no** second engine dual-wire |
| Conflict UI (legacy) | `app/src/feature/conflict/*`, `ViewModelModule` `SyncConflict*ViewModel` | Production | Replace or route to Sync Center |
| Nav routes | `app/src/navigation/NavRoute.kt`, `LomoNavHost.kt` | **No** `SyncCenter` route | Add single Sync Center route; **no** dual Settings+Center engines |
| Sync Center shell (dark) | `app/src/feature/synccenter/*` | Unregistered | Register ViewModel + route once |

### 2.4 Kotlin engine / repository tail-delete list (business owners)

Execute only in the atomic P5-13 change (see also `stage5-sync-owner-inventory.v1.md` §5):

1. `data/src/git/**` engine/workflow/primitives/media planner/SAF mirror and related repository impls.
2. `data/src/s3/**` + `data/src/repository/S3*.kt` planner/executor/client/rclone/file bridge/index/shard/journal.
3. `data/src/webdav/**` + `data/src/repository/WebDav*.kt` client/planner/operation/conflict/file bridge/fingerprint.
4. Provider-specific WorkManager business rules; retain generic Rust job runner scheduling only.
5. `data/src/sync/` Rust sync-v1 encoder/decoder/wire ordinal / envelope consumers.
6. Pending remote conflict/review descriptors after old workers/journals are quiescent; **never** delete independent Sync Inbox.
7. File-backed sync DAO/entity remnants under `data/src/local` that encode remote sync authority.
8. JGit, AWS Kotlin SDK, BouncyCastle if only sync-owned, and frozen `lomo-sync-core` after absorption.
9. Provider-specific conflict models/UI branch semantics that re-interpret completion outside Rust events.
10. Force/reset Git paths and any Git-only SAF user-file mirror.

Primary cutover probe files (architecture `stage_five_sync_cutover_complete`):

- `data/src/git/GitSyncEngine.kt` — must be **gone** after cutover
- `data/src/repository/S3SyncRepositoryImpl.kt` — must be **gone** after cutover
- `data/src/repository/WebDavSyncRepositoryImpl.kt` — must be **gone** after cutover

## 3. Dark Rust / Kotlin surfaces ready (do not register yet)

| Surface | Path / crate | Readiness | Registration |
| --- | --- | --- | --- |
| Durable sync core | `rust/sync` (`lomo-sync`) | Host hermetic GREEN (P5-03…P5-12 + Wave-14 durable multipart / crash deepen) | Not production DI |
| Streaming cycle | `run_sync_cycle_streaming` + `list_remote_pages` | Host residual GREEN | Not production DI |
| S3 adapter multi-page list | `S3Adapter::list_remote_pages` | Host residual (Wave-12) | Not production DI |
| WebDAV adapter | `rust/sync` webdav | Host hermetic; default one-page pages fallback | Not production DI |
| Git adapter | `rust/git` (`lomo-git`) | Host hermetic + Wave-14 dual-parent merge-commit after resolve; off native | Not production DI / native |
| Actor-external task + secret lease | `lomo-core` | Host residual | Not production WorkManager |
| BoltFFI conversion | `lomo-native` free-functions | Conversion/inspect only | No planner re-impl |
| Dark repo | `data/src/engine/sync/BoltFfiRemoteSyncRepository.kt`, `RemoteSyncRepository.kt`, `SyncNativeBridge.kt`, `RustSyncSecretSupplier.kt` | Host tests GREEN | Unregistered |
| Dark Sync Center data | `RemoteSyncCenterRepositoryAdapter.kt` + domain ports | Host tests GREEN | Unregistered |
| Dark worker / executor | `RustSyncWorker`, `RustSyncWorkExecutor`, `RemoteSyncRustWorkExecutor` | Host composition GREEN | **No** `workerOf` |
| Dark Sync Center UI | `app/src/feature/synccenter/*` | Host Compose/reducer/VM GREEN | **No** nav / ViewModelModule |

## 4. Dual-stack ban checklist (must stay GREEN pre-cutover)

- [ ] No `use_rust_sync` / `USE_RUST_SYNC` / `dualWriteSync` / `rustSyncEnabled` in `app`/`data`/`domain`/`ui-components`
- [ ] No `workerOf(::RustSyncWorker)` in `SyncDataModule` (or any production module)
- [ ] No Koin `single`/`factory` bind of dark `BoltFfiRemoteSyncRepository` / `RemoteSyncCenterRepositoryAdapter` / `RustSyncSecretSupplier`
- [ ] No `NavRoute` / `LomoNavHost` production Sync Center dual-wire beside live Kotlin conflict engines
- [ ] No Settings coordinator calling both Kotlin engine **and** Rust cycle in one user action
- [ ] `stage_five_dark_build_must_not_wire_production_dual_stack` architecture test GREEN
- [ ] Production Kotlin still owns `GitSyncEngine` / `S3SyncRepositoryImpl` / `WebDavSyncRepositoryImpl` until cutover
- [ ] Evidence table keeps `| P5-13 | **OPEN** |` until atomic cutover actually lands

## 5. Inheritance / env gates (still OPEN — never invent GREEN)

| Gate | Status |
| --- | --- |
| Stage-3 P3-10 store cutover (host) | **GREEN** (entry prerequisite) |
| Stage-4 P4-10A/B media/archive host cutover | **GREEN** (entry prerequisite) |
| API ≥ 26 arm64 `just device-smoke` | **OPEN / `pending_env`** (inherited) |
| Six real provider smokes (`just sync-provider-smoke`) | **OPEN / `pending_env`** |
| Formal compressed APK × 1.15 / Stage-5 native ceiling measure | **OPEN / `pending_env`** |
| Full multi-process crash-at-every-transition graph | **OPEN** (host suite only) |
| Real provider FirstTakeover / Migration | **OPEN / `pending_env`** |
| `just check` / `just ci` as Stage-5 formal-exit GREEN | **OPEN** (not claimed by prep) |
| P5-13 production cutover | **OPEN** |
| P5-14 formal Stage-5 exit | **OPEN** (blocked on P5-13 + env gates) |

## 6. Atomic cutover checklist (execute only at P5-13)

1. Confirm hermetic packages P5-00…P5-12 host residuals accepted; dual-stack ban GREEN.
2. Confirm inheritance arm64 + six-provider gates are real GREEN or explicitly waived by product — **never invent**.
3. Single PR / atomic change:
   - Bind dark Rust ports into production DI once.
   - `workerOf(::RustSyncWorker)` only; delete `workerOf` for Git/S3/WebDAV provider workers.
   - Point shared scheduler enqueue at Rust work name only.
   - Register Sync Center nav + ViewModel; remove dual Settings conflict authority.
   - Delete Kotlin tails in §2.4 in the **same** change.
   - Absorb/remove frozen `lomo-sync-core` consumers.
4. Architecture: `stage_five_sync_cutover_complete() == true`; dual-stack flags still absent.
5. Evidence: flip P5-13 table row only after real gates; record RED/GREEN commands.
6. P5-14 only after fresh `just check` / `just ci` + arm64 + providers.

## 7. Explicit non-claims (this inventory)

- Does **not** flip production DI / `workerOf` / Settings dual-wire / nav.
- Does **not** mark P5-13 or P5-14 GREEN.
- Does **not** invent arm64, six-provider, APK, or `just ci` formal-exit GREEN.
- Does **not** re-implement Kotlin business planner in Rust host residual packages.
- Adapter multi-page / streaming apply host residuals remain separate from production wire.

## 8. Wave-14 host residual readiness (prep only — not cutover)

Host residuals closed on 2026-07-24 Wave-14 and available for future atomic cutover composition:

| Surface | Host status | Still not production |
| --- | --- | --- |
| S3 durable multipart process-death resume | **GREEN** host (`s3_adapter_contract` **26**) | Real R2/S3 smoke OPEN / `pending_env` |
| Git dual-parent merge-commit after resolve | **GREEN** host (`git_adapter_contract` **15**) | Real HTTPS smoke OPEN / `pending_env` |
| Conflict crash-at-transition host matrix | **GREEN** host (`conflict_recovery_contract` **42**) | Full multi-process OS-kill graph OPEN |

These readiness rows do **not** authorize `workerOf`, Settings dual-wire, Sync Center nav, or
production DI flip. P5-13 remains **OPEN** until atomic cutover + env gates.
