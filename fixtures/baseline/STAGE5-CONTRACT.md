# Stage-5 unified sync core behavior contract

> Status: **locked behavior contract for Stage 5 dark construction (P5-00 entry).**
>
> This document fixes the behavior and evidence required to close stage 5. It is not evidence that
> every exit bar is green. Actual RED/GREEN commands and results are recorded in
> `STAGE5-EVIDENCE.md` alongside the implementation that produces them.
>
> **Stage-3 store cutover (P3-10) and Stage-4 media/archive host cutover (P4-10A/B) are hard entry
> prerequisites.** Formal Stage-3/4 exit may remain open on API ≥ 26 arm64 (`pending_env`); Stage 5
> dark construction may proceed on host, but **P5-13 production cutover and Stage-5 formal exit
> inherit the API ≥ 26 arm64 hard device gate**, fresh `just check` / `just ci`, and
> `just sync-provider-smoke` (six real providers). Do not claim GREEN while those gates are open.

## Behavior Contract

- **Unit under test:** unified remote sync ownership across Git, WebDAV, and S3 — identity
  (`WorkspaceGenerationId` + `RemoteDatasetId` + `RemoteIdentityDigest`), durable
  `.lomo/sync/v1` baseline/session/tombstone/conflict state, provider-neutral
  `RemoteSnapshot → ProviderNeutralIntent → PreparedRemoteBatch → PublishReceipt →
  VerifiedRemoteState` pipeline, history/state v2, first-takeover read-only preflight, conflict
  resolution with expected revision, cancel/retry recovery, and atomic production cutover that
  deletes Kotlin sync business owners in the same wave.
- **Owning layer (target after P5-13):** `lomo-sync` for all sync decisions, transport orchestration,
  baseline/tombstone/session/conflict durability; `lomo-git` for typed `git2` adapter only;
  `lomo-store` / `lomo-workspace` for local expected-revision mutation and codec/path facts;
  `lomo-core` for actor-external native task + ephemeral secret lease; `lomo-native` for conversion-only
  FFI; Kotlin for DataStore non-secret config, Keystore, WorkManager runner, SAF action executor,
  notifications, and Compose (including independent Sync Center UI).
- **Owning layer (until P5-13):** production remains Kotlin Git/WebDAV/S3 owners + frozen
  `lomo-sync-core` sync-v1 planner. Dark-build Rust stacks must not enter production registry,
  navigation, or scheduler.
- **Priority tier:** P0 for ownership/invariant packages; P1 for Sync Center presentation.
- **Capability:** one Rust job graph owns snapshot/plan/apply/verify/baseline/tombstone/conflict/
  recovery/cancel/retry for all three backends; provider adapters only implement protocol ports;
  migration/reset/takeover never generate user-file delete actions; only verified apply advances
  baseline; secrets never enter journal/log/WorkManager input.

## Fundamental invariants

1. **Generation fence:** every sync decision and local/remote write belongs to one
   `WorkspaceGenerationId + RemoteDatasetId + RemoteIdentityDigest`. Mismatched durable state is
   rejected, never clean-slated.
2. **No unproven delete:** remote path absence participates in delete intent only when remote
   snapshot `completeness = Complete`, durable baseline exists, durable tombstone rules pass, and
   the session is not first-takeover. Partial listing → no delete.
3. **Verify before baseline:** baseline advances only after apply + re-read `VerifiedRemoteState`
   success for that path/control record. Cancel, crash, or verify failure leave baseline unchanged.
4. **Conditional write / replan:** precondition failures (ETag, ref CAS, version token) force replan;
   never unconditional overwrite of concurrent remote change; never force-push.
5. **Conflict never silent:** both-modified, same-time different digest, unproven delete, failed
   conditional write, or history/baseline mismatch enter durable conflict with expected conflict
   revision; resolution is idempotent only with that revision.
6. **Migration safety:** migration, upgrade, takeover, reset, baseline rebuild, and recovery types
   cannot emit user-file delete/overwrite branches. Normal user-initiated deletes still propagate
   under product semantics with tombstones.
7. **Provider-neutral core:** Git/WebDAV/S3 share one state machine and durable operation model.
   Adapters compile intents; they do not own direction, conflict, baseline, tombstone, or retry policy.
8. **Local commit boundary:** all local user-byte and `.lomo` mutations from sync go through
   `lomo-store` / workspace expected-generation/revision ports (`LocalSyncMutationBatch`). No Git
   checkout/reset, S3 downloader, or WebDAV client may write user files directly.
9. **Secret ephemerality:** credentials exist only as ephemeral native leases for the active task.
   Journals, diagnostics, Timber, tracing, panic, and WorkManager inputs never contain plaintext secrets.
10. **Single production stack after cutover:** after P5-13, production dual-stack sync (Kotlin + Rust)
    is forbidden. Dark-build until atomic cutover. No feature flags, dual-write, or progressive dual DI.
11. **Hard APK gate:** Stage 0 compressed baseline × 1.15 remains the hard universal APK gate and must
    not be raised for Stage 5 native growth. Stage-specific native ceilings are versioned fixtures.
12. **Entry gate:** Stage-3 P3-10 store cutover and Stage-4 P4-10A/B media/archive host cutover are
    required before Stage-5 production cutover claims. Formal Stage-5 exit additionally requires API ≥ 26
    arm64 device smoke and six real provider smokes.

## Resource limits (stage-5 sync surface)

| Surface | Limit / rule | Failure |
| --- | --- | --- |
| Remote snapshot completeness | only `Complete` participates in missing/delete derivation | incomplete → no delete |
| Action page | 512 items per durable page | validation / CorruptState |
| Conflict page | 100 items default | validation / CorruptState |
| History retention | 20 reachable revisions per memo | prune with permanent tombstone |
| Baseline shards | 256 SHA-256 first-byte shards; immutable files + atomic head | crash-consistent commit |
| Large bodies/media | artifact reference + digest + size only in headers | no full-byte inline |
| Network concurrency | explicit bounded concurrency; cancelable | fail closed / replan |
| Memory at 10k–100k paths | streaming snapshot; bounded buffers | architecture / contract fail |
| Secrets | ephemeral lease only | typed missing/expired lease |
| Corrupt durable record | schema/checksum/size limits | `CorruptState` (no clean slate) |

Over-limit handling never clamps, truncates, or returns partial success as clean.

## Scenarios

GWT form below uses explicit Given / When / Then tokens so architecture locks can verify the
scenario contract without relying on prose alone.

### Stage entry and scaffolding (P5-00)

- Given `fixtures/baseline/STAGE5-CONTRACT.md` or `STAGE5-EVIDENCE.md` is missing, When architecture
  tests run, Then they fail with a named missing invariant.
- Given Stage-5 inventory / divergence / feasibility / size fixtures required by the contract are
  missing, When architecture tests run, Then they fail.
- Given dark-build Stage-5 sources exist, When production Kotlin DI/registry is inspected before
  P5-13, Then Kotlin Git/WebDAV/S3 owners + frozen `lomo-sync-core` remain the sole live production
  sync authorities and production dual-stack sync DI / dual-write feature flags are absent.
- Given Stage-3 store cutover or Stage-4 media/archive host cutover is unrecorded, When implementers
  claim Stage-5 production cutover GREEN, Then architecture tests fail closed.
- Given API ≥ 26 arm64 device evidence or six-provider smoke is missing, When formal Stage-5 exit is
  claimed, Then evidence must remain `OPEN` / `pending_env` (never fictional GREEN).

### Identity, codec, history/state v2 (P5-01)

- Given a workspace, When generation is minted, Then `WorkspaceGenerationId` is real random durable
  under `.lomo/local/v1/generation.rec` and is never synced or archived; archive activation mints new.
- Given history/state v1, When one-shot activation migration runs, Then v2 is written in staging,
  count/digest/parent closure validated, atomic head switches only on success; crash keeps user files
  and fails closed; runtime no longer reads/writes v1.
- Given a memo head, When retention applies, Then 20 reachable revisions by generation (RevisionId
  tie-break) remain; pruned revisions receive permanent tombstones; active conflict/session pins.

### Actor-external native task (P5-02)

- Given a long network effect, When the job actor dispatches, Then work runs on a bounded external
  worker with dispatch fence; stale completions are rejected; cancel/shutdown races leave durable
  session consistent; secrets never enter the journal.

### Durable core and provider contract (P5-03)

- Given fake local/remote ports, When the state machine runs first-takeover, Then only read-only
  preflight + safe ensure-present/baseline establishment occur; first round emits no user-file deletes.
- Given partial remote listing, When a path is missing, Then no `EnsureAbsent` is generated.
- Given apply then verify failure, When the job ends, Then baseline does not advance.
- Given corrupt session/baseline bytes, When restore runs, Then result is `CorruptState`, not clean slate.

### Unified Direct/SAF local port (P5-04)

- Given Direct or SAF workspace, When sync apply commits, Then the same expected-revision
  `LocalSyncMutationBatch` path is used; SAF projection DB is app-private, generation-bound, rebuildable;
  no provider-specific user-file mirror exists.

### WebDAV / S3 / Git adapters (P5-05..P5-07)

- Given public provider port only, When adapter is implemented, Then no provider-specific planner,
  session, baseline, tombstone, or retry state machine exists outside `lomo-sync`.
- Given rclone crypt configuration, When filename/directory/data modes run, Then hermetic vectors
  match audited fixtures bidirectionally.
- Given Git publish, When multiple path intents apply, Then one non-force CAS push (`WholeBatchRef`)
  publishes; force/reset are absent.

### Conflict, recovery, diagnostics (P5-08)

- Given both-modified Markdown, When conflict is opened, Then base/local/remote candidates are
  durable; resolution requires expected conflict revision; stale revision rejects.

### Dark FFI and Sync Center (P5-09 / P5-10)

- Given dark BoltFFI surfaces, When production registry is inspected before P5-13, Then new sync
  classes are not reachable from production DI/navigation/scheduler.
- Given Sync Center, When opened, Then config/schedule/preflight/session/conflict/recovery live there;
  Settings retains entry + summary only.

### Differential / scale / size (P5-11)

- Given 10k–100k path streaming snapshots, When plan/apply run, Then memory stays bounded and request
  concurrency has explicit caps.
- Given Stage 0 APK hard gate, When size is measured, Then compressed universal APK stays
  ≤ Stage 0 baseline × 1.15.

### Real takeover and atomic cutover (P5-12 / P5-13 / P5-14)

- Given existing remote with overlapping paths, When first Rust session runs, Then read-only preflight
  rules hold; unproven overlaps become durable conflict; no first-round user-file deletes.
- Given all hermetic + six real provider smokes GREEN and inheritance device gates GREEN, When P5-13
  lands, Then production registry points only at Rust and Kotlin sync business tails are deleted in the
  same wave; no feature-flag dual-write remains.
- Given residual Kotlin planner/JGit/AWS Kotlin/sync-v1 wire after claimed cutover, When architecture
  tests run, Then the build fails.

## Observable outcomes

- Architecture-test failures that name missing STAGE5 files, missing inventory/divergence/feasibility
  fixtures, premature production dual-stack sync wiring, or missing Stage-3/4 cutover prerequisites.
- Constrained plan/apply/verify/baseline/conflict results with structured error categories
  (`RetryableFailure`, `FatalFailure`, `CorruptState`, validation, missing/expired secret lease).
- Device-smoke, provider-smoke, and size numbers only when the corresponding package claims them; no
  fictional GREEN. API ≥ 26 arm64 remains the hard gate for cutover/exit. Six real providers remain
  hard gates for formal Stage-5 exit.

## Excludes

- Progressive dual DI, feature flags, or Kotlin fallback after claimed cutover.
- Treating old Kotlin bugs as oracle fixtures (see divergence manifest).
- Multi-target concurrent remote replication (only one active backend).
- Git SSH (HTTPS username/token only for Stage 5).
- Desktop/iOS/LAN (LAN is Stage 6).
- Sync of app-private snooze, credentials, SQLite/WAL, `.lomo/operations`, or temp files.
- Runtime sync-v1 wire compatibility after cutover.
- Raising the Stage 0 compressed APK × 1.15 hard gate.
- Deleting independent Sync Inbox review types with remote-sync tail deletion.
- Fictional GREEN for `just sync-provider-smoke` without credentials/env.

## RED/GREEN evidence format

Every implementation package must record in `STAGE5-EVIDENCE.md`:

1. **RED command** — narrowest command that should fail before the capability exists.
2. **Observed RED** — real failure output summary.
3. **GREEN command** — command that passes after the fix.
4. **Observed GREEN** — real pass summary.
5. **First principles** — invariant / violation / rebuild / edge / tail for non-trivial packages.
6. Honest `pending_env` / `OPEN` when arm64, provider credentials, or other required env is
   unavailable — never mark those GREEN.
