# Stage-1 implementation evidence

> Status: **P1 formal exit closed for P2 entry (2026-07-18 B4e)**. Commands are run from the
> repository root unless a working directory is named. RED entries are retained; GREEN entries are
> added only after the exact capability passes. API 26 x86_64 AVD remains `pending_env` / non-claim.
>
> FFI transport note: stage 1 has been rebased to BoltFFI/JNI. The UniFFI entries below are retained
> as historical evidence of core behavior through the old transport. They are superseded for the
> FFI migration exit gate and must not be cited as BoltFFI GREEN evidence.

## P1-00 architecture ownership

- RED command: `cd rust && cargo test -p lomo-architecture-tests --test architecture stage_one_application_kernel_has_one_owner_and_no_probe_tail --locked`
- Observed RED: `stage 1 requires the real lomo-core owner`.
- Why it proves absence: the versioned architecture gate could not find any formal application
  kernel crate; only the stage-0 probe protocol existed.
- GREEN command: same architecture suite after formal engine + probe deletion.
- GREEN result: architecture tests **14/14** pass; stage-one owner gate no longer requires probe tail.

## P1-01 constrained boundary types

- RED command: `cd rust && cargo test -p lomo-core --test types_contract --offline` (the new
  workspace member first refreshed `Cargo.lock`; subsequent runs use `--locked`).
- Observed RED: unresolved imports for `CapabilityToken`, `ErrorCategory`, `JobId`, `PageSize`,
  `RelativeWorkspacePath`, and `WorkspaceDescriptor`.
- Why it proves absence: the formal core crate existed only as an empty ownership shell and had no
  boundary constructors capable of excluding invalid state.
- GREEN command: `cd rust && cargo test -p lomo-core --test types_contract --locked`.
- GREEN result: 4 passed, 0 failed.

## P1-02 journal, lock, and recovery

- RED command: `cd rust && cargo test -p lomo-core --test engine_recovery_contract --locked`.
- Observed RED: unresolved imports for `EngineConfig`, `EngineState`, and `LomoEngine`.
- Why it proves absence: no formal lifecycle object could acquire workspace ownership or recover a
  checksummed control journal.
- GREEN command: `cd rust && cargo test -p lomo-core --test engine_recovery_contract --locked`.
- GREEN result: 3 passed, 0 failed; strict all-target Clippy also passed.

## P1-04 Rust platform protocol

- RED command: `cd rust && cargo test -p lomo-core --test platform_protocol_contract --locked`.
- Observed RED: unresolved imports for all platform batch/action/result/evidence types.
- Why it proves absence: there was no versioned protocol capable of carrying Android side effects
  without file bytes or of rejecting mismatched result identities/order.
- GREEN command: `cd rust && cargo test -p lomo-core --test platform_protocol_contract --locked`.
- GREEN result: 2 passed, 0 failed; strict all-target Clippy also passed.
- Adversarial protocol correction RED: after beginning real `ContentResolver` work, the generic
  action evidence could not carry `ListChildren` metadata pages or `ReadToExchange` artifacts.
  `platform_protocol_contract` was strengthened and failed on missing `DocumentMetadata`,
  `MetadataPage`, and typed `PlatformActionOutput`.
- Correction GREEN: each success now has an action-specific typed output; Rust rejects an output
  whose kind/path/token/digest/length postcondition does not match its action. Core tests and strict
  Clippy pass, UniFFI behavior tests pass, and four-ABI release generation/ELF validation passes.

## P1-03 actor, events, cancellation, and deadlines

- RED command: `cd rust && cargo test -p lomo-core --test actor_contract --locked`.
- Observed RED: unresolved imports for `JobStep`, `CancelOutcome`, `CoreEvent`, and the listener
  contract; no job transition API existed.
- Why it proves absence: the initial core could open a static snapshot but had no single-writer job
  authority, durable cancellation race, independent event sequence, or persisted deadline.
- GREEN command: `cd rust && cargo test -p lomo-core --test actor_contract --locked`.
- GREEN result: 3 passed, 0 failed; all 12 core contract tests and strict all-target Clippy pass.

## P1-05 UniFFI lifecycle facade — historical, transport superseded

- RED command: `cd rust && cargo test -p lomo-native --test engine_ffi_contract --offline`
  (the new native-to-core dependency first refreshed `Cargo.lock`; subsequent runs use `--locked`).
- Observed RED: unresolved imports for the formal engine config/object/state/job/cancel/shutdown API.
- Why it proves absence: `lomo-native` exposed only frozen sync-v1 and the stage-0 probe, so Kotlin
  had no facade for the authoritative core state machine.
- GREEN command: `cd rust && cargo test -p lomo-native --test engine_ffi_contract --locked`.
- GREEN result: 3 passed, 0 failed, covering validation, lifecycle/poll/cancel/shutdown, ordered
  submit, listener event delivery, and structured core-owned state/error mapping.
- Strict Clippy: the new facade is clean; whole-package Clippy remains RED only on the stage-0
  `feasibility_probe_ffi` tail that P1-07 must delete.

## P1-05 Kotlin lifecycle adapter over UniFFI — historical transport evidence

- RED command: repository Kotlin Toolchain `test --include-module=data` using build directory
  `.kotlin/toolchain-build/red-engine-adapter`.
- Observed RED: unresolved `EngineReadiness`, `NativeEnginePort`, and `RustEngineAdapter` contracts.
- Boundary RED discovered first: generated `Subscription.close(): Boolean` conflicted with UniFFI's
  `AutoCloseable.close(): Unit`; the exported method was replaced with `unsubscribe()` and the
  generated handle still requires explicit `close()`.
- GREEN result: all 1,167 data tests passed. The adapter always resnapshots Rust state for callback
  invalidations/sequence gaps/foreground resume and explicitly unregisters the subscription once.

## P1-04 Kotlin platform batch executor

- RED command: repository Kotlin Toolchain `test --include-module=data` using build directory
  `.kotlin/toolchain-build/red-platform-executor`.
- Observed RED: unresolved `AndroidPlatformActionExecutor` and `PlatformActionAccess`.
- GREEN result: all 1,171 data tests passed. Seven action kinds preserve ordered batch identity,
  failure stops the result prefix, expiry produces a structured first-action timeout without a side
  effect, and invalid schema/count fails before execution.
- Typed-output revalidation: after the Rust protocol correction and regenerated bindings, the same
  1,171-test data suite passed with action-specific outputs.
- GREEN (2026-07-17 capability edge):
  - RED first for missing `CapabilityRegistry`, `ExchangeResolver`, `AndroidPlatformActionAccess`,
    `PlatformDocumentsGateway`, and `PlatformBatchRunner`.
  - GREEN command:
    `./kotlin test --include-module=data --include-classes='com.lomo.data.engine.CapabilityRegistryTest' --include-classes='com.lomo.data.engine.ExchangeResolverTest' --include-classes='com.lomo.data.engine.AndroidPlatformActionAccessTest' --include-classes='com.lomo.data.engine.AndroidPlatformActionExecutorTest' --include-classes='com.lomo.data.engine.PlatformBatchRunnerTest' --include-classes='com.lomo.data.engine.RustEngineAdapterTest' --include-classes='com.lomo.data.engine.NativeHandleLeaseTest' --include-classes='com.lomo.data.engine.BoundedInvalidationQueueTest' --include-classes='com.lomo.data.engine.BoltFfiNativeEngineFactoryTest'`
  - GREEN result: **36 passed / 0 failed**. Covers:
    - opaque capability token ↔ tree URI string registry with revoke/unknown fail-closed;
    - exchange token path escape rejection + streaming SHA-256 artifact digest;
    - Stat typed metadata evidence; ReadToExchange stream-to-exchange; mismatched expected
      fingerprint write → `conflict/platform_postcondition_mismatch` with zero side effects;
      delete replay on absence → `AlreadySatisfied`; matching preimage delete → `Applied`;
    - batch runner poll → execute → submit until terminal;
    - `RustEngineAdapter` drives Opening bootstrap through `PlatformBatchRunner` before Ready.
  - Production wiring: `engineModule` binds `CapabilityRegistry`, `ExchangeResolver`,
    `ContentResolverPlatformDocumentsGateway`, `AndroidPlatformActionAccess`,
    `AndroidPlatformActionExecutor`, and `PlatformBatchRunner` into `RustEngineAdapter`.
  - Remaining before P1-04 full device closure: live DocumentsProvider matrix on API ≥ 26 device
    (native-smoke / device-smoke; hard gate arm64 on this line), not host-unit.

## P1-06 write hard gate, recovery route, and atomic workspace switch — host wiring GREEN with adversarial P1 gaps closed (2026-07-17); P2 NOT ready

> Honesty: earlier host-wiring GREEN claimed production Ready path + recovery UI + switch activate +
> create freeze. Adversarial audit found remaining P1 gaps (soft Recovery treated as success, cold-restore
> Recovery overwritable by bootstrap resnapshot, media writes ungated, entry deep-link not using
> `entryWorkspaceStateFor`, blank-only `validateCandidate`, `clearWorkspace` not under activation mutex).
> Those gaps are closed below. Stage 1 remains **open** until `just check` + device-smoke (if available)
> and full product-write inventory are green. **Do not claim P2-ready.**

### B1 production engine becomes Ready after workspace selection (Ready-only install)

- RED (pre-fix fact): `engineModule` opened `NativeEngineOpenRequest.forAppFilesDir(... workspace=null)`
  forever, so production readiness stayed `AwaitingWorkspaceSelection` after user selection.
- RED (adversarial): soft open to `ReadOnlyRecovery` installed the candidate and closed the previous
  Ready engine; SwitchRoot treated that as success.
- Rebuild: `ManagedEngineSession` is the sole process owner of engine lifecycle. Bootstrap opens with
  no workspace; `activateWorkspace(StorageLocation)` opens a candidate Direct/SAF engine and **only
  installs after Ready**. Soft Recovery / hard open failure leave previous authority and rethrow
  (`WorkspaceActivationException` for soft). Cold restore activates a persisted root once; hard/soft
  failure freezes session-owned Recovery authority so bootstrap cannot resnapshot Recovery away.
  `clearWorkspace` is suspend and runs under the activation mutex.
- GREEN command:
  `./kotlin test --include-module=data --include-classes='com.lomo.data.engine.ManagedEngineSessionTest' --include-classes='com.lomo.data.engine.RustEngineAdapterTest' --include-classes='com.lomo.data.architecture.DataDiBoundaryTest'`
- GREEN result (2026-07-17 adversarial fix): **ManagedEngineSession 7/7** (includes soft Recovery
  restores previous + cold-restore Recovery holds across resnapshot), DataDiBoundary **3/3** (includes
  `WorkspaceCandidateProbe` DI). Covers awaiting start, Direct Ready + previous close, open-failure
  keeps previous authority, SAF capability register, cold restore activate, soft-fail restore, recovery
  authority. DI binds `ManagedEngineSession` + factory open + Koin `onClose` + candidate probe.

### B2 recovery route production wiring + entry deep-link/share

- RED (pre-fix fact): `entryWorkspaceStateFor` / `EngineReadOnlyRecoveryScreen` existed but Main UI
  did not consume `EngineReadiness` and could still show writable Ready with a non-Ready engine.
- RED (adversarial): deep-link/share `DispatchPendingLaunchCommands` applied share/open actions without
  consulting `entryWorkspaceStateFor` / engine readiness.
- GREEN: `MainViewModel.uiState` combines root + `engineReadiness`; non-Ready maps to
  `OpeningEngine` / `ReadOnlyRecovery`; `MainScreenAnimatedBody` composes
  `EngineReadOnlyRecoveryScreen` with retry → `retryEngineOpen` and reselect → settings.
- GREEN entry: `MainActivity.DispatchPendingLaunchCommands` reads `viewModel.engineReadiness`, maps via
  `entryWorkspaceStateFor`, and only dispatches when `shouldDispatchPendingLaunchCommands` (Ready).
- GREEN command:
  `./kotlin test --include-module=app --include-classes='com.lomo.app.MainActivityLaunchRoutingTest'`
- GREEN result: routing **18 passed** (includes `entryWorkspaceStateFor` mapping + Ready-only dispatch
  gate). Writable Ready requires engine Ready for Main body and for pending entry commands.

### B3 workspace switch activates engine under freeze + production candidate probe

- RED (adversarial): DI injected blank-only default `validateCandidate`.
- GREEN: domain `WorkspaceCandidateValidator` + data `WorkspaceCandidateProbe` (Direct path exists +
  readable; SAF tree resolvable + persisted read/write grant). `engineModule` binds the probe;
  `domainWorkspaceModule` injects it into `SwitchRootStorageUseCase`.
- GREEN command:
  `./kotlin test --include-module=domain --include-classes='com.lomo.domain.usecase.SwitchRootStorageUseCaseTest'`
  and
  `./kotlin test --include-module=data --include-classes='com.lomo.data.engine.WorkspaceCandidateProbeTest'`
- GREEN result: SwitchRoot **5 passed** (`validate → freeze → persist → activate engine → rebuild →
  unfreeze`; activate failure restores previous selection + re-activates previous engine and always
  ends freeze). Candidate probe **4 passed**.

### B4 write freeze on create + mutation + media edges

- GREEN command:
  `./kotlin test --include-module=domain --include-classes='com.lomo.domain.model.EngineReadinessWriteGateTest' --include-classes='com.lomo.domain.usecase.CreateMemoUseCaseTest'`
  and
  `./kotlin test --include-module=data --include-classes='com.lomo.data.repository.MemoMutationWriteGateTest' --include-classes='com.lomo.data.repository.MediaRepositoryWriteGateTest'`
- GREEN result: domain write-gate **3**, CreateMemo **5** (includes freeze-active fail-closed), data
  mutation choke **3**, media write gate **3**. Create path passes `writeFreezeRepository.isFrozen`
  into `requireWritable`. Mutation/trash repos require Ready + freeze. **MediaRepositoryImpl** now
  gates `importImage` / `removeImage` / `ensureCategoryWorkspace` / voice allocate+remove the same way.
- Stage-1 inventory (explicit remaining): remote sync workers / outbox apply that mutate workspace files
  outside memo mutation still need a full product-write inventory before claiming global write authority
  complete. Full sync authority migration remains deferred with stage-1 excludes, but media import is
  no longer an ungated product write edge.

### B5 packaging / size honesty

- `verify_native_tree(workspace, abis, profile)` now records per-ABI bytes. Shipping profiles
  (`Release` / `ReleaseCi`) with all four ABIs enforce total ≤ UniFFI+JNA baseline **2,476,652**.
  Dev packs (used by `just check` / `just preflight`) explicitly skip the size gate and log that they
  are **not shipping evidence**.
- Observed on this host after `just check` Dev pack: four-ABI total **83,097,324** with message
  `skipping four-ABI shipping size gate for Dev pack ... not shipping evidence`.
- Shipping size GREEN remains the prior release-android + strip pack evidence (BF-02/05), not Dev
  `app/jniLibs` overwrite.

### B7 workspace lock docs + stale reclaim

- Contract/docs: exclusivity is pure-safe `create_dir` + `owner.pid` + `/proc` liveness, not OS flock.
- GREEN command:
  `cargo test -p lomo-core --test engine_recovery_contract --locked --manifest-path rust/Cargo.toml`
- GREEN result: **4 passed**, including new
  `workspace_lock_reclaims_stale_owner_from_dead_pid`.

### B4b adversarial residual: outbox drain + shared write choke + probe/restore (2026-07-17)

- RED (adversarial): mutation API was Ready+freeze gated, but `MemoOutboxDrainCoordinator` process-start
  / refresh drain still claimed and flushed workspace files without `requireWritable`. Migration archive
  import and shared markdown/media writers were also outside the choke. Candidate probe accepted
  non-writable Direct roots and matched SAF grants by raw URI string only. Switch restore failures were
  swallowed by `runCatching`.
- Rebuild:
  - `WorkspaceWriteAuthority` is the single process-local Ready+!freeze collaborator.
  - Wired into `FileMarkdownStorageDataSourceDelegate` save/delete, `FileMediaStorageDataSourceDelegate`
    mutating methods, `DefaultWorkspaceMediaAccess` write/delete, and `MemoOutboxDrainCoordinator`
    (skip claim/flush when non-writable; re-request when authority becomes writable).
  - `WorkspaceCandidateProbe` requires Direct `canWrite`, SAF `DocumentFile.canWrite`, and hardened
    tree-grant matching via document id.
  - `SwitchRootStorageUseCase` surfaces `WorkspaceAuthorityRestoreException` when previous authority
    cannot be restored; original switch failure is suppressed on the restore exception.
  - Main create FAB is unmounted unless `MainScreenState.Ready`.
- GREEN command (targeted):
  `./kotlin test --include-module=data --include-classes='com.lomo.data.repository.WorkspaceWriteAuthorityTest' --include-classes='com.lomo.data.repository.MemoOutboxWriteGateTest' --include-classes='com.lomo.data.engine.WorkspaceCandidateProbeTest'`
  and
  `./kotlin test --include-module=domain --include-classes='com.lomo.domain.usecase.SwitchRootStorageUseCaseTest'`
- GREEN result: recorded after the commands below in this evidence section.
- **Historical (pre-B4e):** shipping four-ABI size / formal stage-1 exit still open after this
  subsection. Closed by **B4e** — do not cite as current. API 26 AVD remains non-claim.

### B4c remote-sync local-apply write authority (2026-07-18) — host claims partially FALSE; rebuilt 2026-07-18

> Honesty correction: the earlier B4c host-gate GREEN claim of RemoteSyncWriteAuthorityGate
> **7/7** and overall **16/16** is **stale / false for the live tree**. Live suite inventory is
> **11** RemoteSyncWriteAuthorityGate scenarios, and production still compiled against a
> test-only `AlwaysWritableWorkspaceWriteAuthority` default until B4d below. Do not cite the
> 16/16 figure as proof of complete Git product write authority.

- RED (adversarial residual after B4b): vault-root S3 local apply, `LocalMediaSyncStore` media
  write/import/delete, Git SAF mirror push, Git conflict resolution file writes, and Git layout
  migration renames mutated workspace files without `WorkspaceWriteAuthority`. Workers/operation
  entry could still start local apply while frozen or non-Ready.
- Rebuild (partial, retained):
  - Inject `WorkspaceWriteAuthority` into `S3SyncFileBridge` / `S3SyncFileBridgeScope` mutating
    paths (`writeLocalBytes` / `importLocalFile` / `deleteLocalFile`), `LocalMediaSyncStore`
    write/import/delete, `SafGitMirrorBridge.pushToSaf`,
    `GitSyncConflictRecoveryCoordinator.applyConflictResolution`, and
    `SyncLayoutMigration.migrateGitRepo`.
  - Operation entry fail-closed structured error (no local apply) when non-writable:
    `S3SyncOperationRepositoryImpl.executeS3Sync`, `WebDavSyncOperationRepositoryImpl.sync`,
    `GitSyncOperationRepositoryImpl.sync` return Error with
    `WORKSPACE_WRITES_UNAVAILABLE_MESSAGE`.
  - Collapse duplicate `requireWritableEngine` in `MediaRepositoryImpl` /
    `MemoMutationRepositoryImpl` / `MemoTrashRepositoryImpl` onto injected
    `WorkspaceWriteAuthority`.
- **B4c residual that remained open (found by adversarial re-audit):**
  - Production constructors defaulted open via `AlwaysWritableWorkspaceWriteAuthority` even though
    that symbol exists only under `data/test/testing/` → host compile red for data production.
  - Only `GitSyncOperationRepositoryImpl.sync()` was entry-gated; `initOrClone`, event-driven
    `commitLocal`, maintenance reset/force-push, conflict hard-reset/clean, `GitMediaSyncBridge`
    repo push/delete, and `GitRepositoryPrimitives.ensureGitignore` still mutated without an
    outermost authority check.
- **Historical (pre-B4e):** shipping size / P1-07/P1-08 still open after B4c residual note. Closed by
  **B4e**. API 26 AVD remains non-claim. Do not cite as current.

### B4d DI-only write authority + complete Git product entry gates (2026-07-18)

- First principles:
  1. **Invariant:** one process `WorkspaceWriteAuthority`; non-Ready/frozen → no workspace mutation.
  2. **Axiom violation:** AlwaysWritable production defaults + incomplete Git entry gates + false
     B4c evidence counts.
  3. **Rebuild:** require constructor-injected process authority only; gate every Git product entry
     and residual repo FS mutator at the outermost public surface; rebuild gate suite against live
     constructors/APIs.
  4. **Edge:** missing authority is a compile error; runtime non-writable is structured fail-closed.
  5. **Tail deletion:** remove AlwaysWritable production defaults; stop citing stale 16/16.
- Rebuild landed:
  - Deleted all production `AlwaysWritableWorkspaceWriteAuthority` defaults from
    `GitSyncEngine`, `GitSyncConflictRecoveryCoordinator`, `SafGitMirrorBridge`,
    `GitSyncOperationRepositoryImpl` / `GitSyncInitAndSyncExecutor`, `S3SyncFileBridge` /
    `S3SyncFileBridgeScope`, `LocalMediaSyncStore`, `SyncLayoutMigration.migrateGitRepo`.
  - AlwaysWritable remains **test-only** (`data/test/testing/AlwaysWritableWorkspaceWriteAuthority.kt`).
  - Git product entries now fail closed with `WORKSPACE_WRITES_UNAVAILABLE_MESSAGE` when non-writable:
    `sync`, `initOrClone`, event-driven `commitLocal`, `resetRepository`,
    `resetLocalBranchToRemote`, `forcePushLocalToRemote`.
  - Nested Git mutators also require authority: conflict apply / reset / force-push,
    `GitMediaSyncBridge.reconcile`, `GitRepositoryPrimitives.ensureGitignore`,
    `SafGitMirrorBridge.pushToSaf`, `SyncLayoutMigration.migrateGitRepo`.
  - `RemoteSyncWriteAuthorityGateTest` rebuilt to real public constructors (11 scenarios covering
    S3 vault-root, LocalMediaSyncStore, SAF push, conflict apply, migrateGitRepo, S3 execute entry,
    Git product entries, media bridge, ensureGitignore, resetLocalBranchToRemote).
- RED (live pre-fix facts observed by adversarial audit):
  - Production data sources imported test-only AlwaysWritable → host compile red.
  - Gate suite expected `GitMediaSyncBridge(..., writeAuthority=)` /
    `GitRepositoryPrimitives(authority)` and Git product entry fail-closed behavior that production
    did not provide.
- GREEN command (targeted write-authority surface):
  `./kotlin test --include-module=data --include-classes='com.lomo.data.repository.RemoteSyncWriteAuthorityGateTest' --include-classes='com.lomo.data.repository.WorkspaceWriteAuthorityTest' --include-classes='com.lomo.data.repository.MediaRepositoryWriteGateTest' --include-classes='com.lomo.data.repository.MemoMutationWriteGateTest'`
- GREEN result (2026-07-18 B4d): **20/20** (RemoteSyncWriteAuthorityGate **11**, WorkspaceWriteAuthority
  **3**, media gate **3**, mutation gate **3**).
- Related GREEN (same day, host):
  - GitMediaSyncBridge **5**, GitRepositoryPrimitives **9**, GitSyncEngineConflict **3**,
    GitSyncOperationRepositoryImpl **7**, SyncLayoutMigration **7**, LocalMediaSyncStore **3**,
    DataDiBoundary **3**, GitSyncEngineCollaboration **2**, GitSyncWorkflow **7**,
    GitSyncExecutors **18**, GitSyncRepositoryImpl **2**, S3SyncFileBridge **10**,
    S3SyncFileVaultRootPathGuard **3**.
- GREEN command: `just check`
- GREEN result: **check complete** (2026-07-18 B4d) rust nextest 73/73, detekt ok, Kotlin modules
  app/data/domain/ui-components green; data suite **1235/1235**.
- Device / shipping / formal exit:
  - Device hard gate (contract 2026-07-18): API ≥ 26 arm64 `just device-smoke`. Prior run
    **GREEN 2026-07-17** on SM-S9110 API 36 arm64-v8a remains valid for that gate; B4d did not
    re-run device-smoke (no engine/lock/smoke surface change). API 26 x86_64 AVD is
    `pending_env` / non-claim and is **not** a P1/P2-entry blocker.
  - Shipping four-ABI size gate not re-measured (no native facade change); historical BF-02/05
    shipping pack still the size evidence, not Dev `app/jniLibs`.
  - Formal P1-07/P1-08 still open after B4d (closed in B4e).
- Inventory note: product remote-sync / Git local-apply writers listed above are under the shared
  choke with DI-required authority. Nested inbox/conflict restore paths that only write through
  already-gated bridges inherit the choke; any future direct FS writer must fail closed at entry.
- **Still not P2-ready / STAGE1 not closed after B4d.** Closed by B4e — do not claim this as current.

### Host gate

- Targeted RED/GREEN for adversarial P1 gaps: **passed 2026-07-17** (ManagedEngineSession 7, media write
  gate 3, candidate probe 4, SwitchRoot 5, entry routing 18).
- B4c 16/16 host-gate claim is **struck** (see B4c honesty correction). B4d replaces it with live
  **20/20** write-authority surface + related Git/S3 regressions + `just check` data **1235**.
- GREEN command: `just check`
- GREEN result: **check complete** (2026-07-18 B4d).
- Device smoke: hard gate is API ≥ 26 arm64 (prior GREEN 2026-07-17); not re-run in B4d.
  API 26 AVD = non-claim, not a blocker.
- **P2 entry was still blocked after B4d** by: shipping four-ABI size formal re-affirmation,
  `just ci` for merge/handoff, and stage-1 formal exit audit (P1-07/P1-08). **Not** blocked by
  missing API 26 AVD. Closed by **B4e** below — do not cite this paragraph as current status.

## BoltFFI migration evidence — closed for host + arm64 device lifecycle

The authoritative work packages, version policy, metrics, and adversarial scenarios are in
`../../BOLTFFI-MIGRATION-PLAN.md` and `fixtures/baseline/ffi-transport-baseline.v1.json`.

### BF-00 pinned release resolution and UniFFI baseline

- GREEN command: inspected official tags/commits on 2026-07-16; wrote
  `fixtures/baseline/ffi-transport-baseline.v1.json` and `rust/tools.toml` `[ffi.boltffi_cli]`.
- GREEN result: latest formal tag `v0.27.5` does not include JVM use-after-close guard
  `2de4597034e0e66dcdfd34191abbe9ae7de7b31e`; exact official SHA pin recorded with package
  `boltffi_cli` / binary `boltffi`. UniFFI Kotlin baseline frozen at 194,546 bytes / 5,530 lines;
  release-like native+JNA four-ABI sum frozen at 2,476,652 bytes.
- Warm generation sample (post-migration host): p50-like single sample **143–178 ms** for
  `boltffi generate kotlin` via xtask.

### BF-01 exact surface and deterministic generation

- RED: first generate without flattened crate-root exports emitted only `planSyncEnvelope`.
- GREEN command: `cargo run -p lomo-xtask -- native` after facade conversion.
- GREEN result: generated `native-bindings/src/LomoNativeBridge.kt` is package
  `com.lomo.nativebridge`, owner `LomoNativeBridge.kt`, **72,795 bytes / 2,179 lines**, no
  `@Suppress` after canonicalize; surface includes `LomoEngine`/`Subscription`/`CoreEventListener`/
  platform protocol/`planSyncEnvelope`. Canonicalizer unit tests pass.

### BF-02 Rust facade and JNI packaging

- RED: official `boltffi pack android` omits `boltffi_jni_callback_parameter` in regenerated
  jni_glue for class-method foreign listeners (pin still requires inject).
- GREEN command: xtask runs official `boltffi pack android` with Cargo profile
  `release-android` (no combined `--release`; Cargo rejects that with `--profile`) end-to-end.
  A synthetic NDK root wraps clang so the missing helper is injected immediately before
  jni_glue compile; release artifacts are then `llvm-strip`ped and published.
  `verify_native_tree` covers four ABIs.
- GREEN result: each ABI has only `liblomo_native_jni.so` with `JNI_OnLoad`, no JNA /
  `libjnidispatch.so` / `liblomo_native.so`. Architecture tests for BoltFFI identity pass.
- Production depends on repository-owned `rust/boltffi-facade` (`package name = boltffi`) over
  exact-pinned `boltffi_core` with `default-features = false`, so macros resolve
  `::boltffi::__private` without linking unused url/uuid codecs.
- Size GREEN (2026-07-17 host, `just`/xtask `native` four-ABI shipping pack):
  - arm64-v8a **331,512**
  - armeabi-v7a **252,604**
  - x86 **391,624**
  - x86_64 **428,960**
  - four-ABI total **1,404,700** vs UniFFI+JNA baseline **2,476,652** (**0.567×**, margin
    **1,071,952** bytes under gate).
  - Shipping size policy (pack path only): Cargo profile `release-android` (`opt-level = "z"`,
    fat LTO), per-Android-target `RUSTFLAGS` with `-C force-unwind-tables=no` and
    `-C panic=immediate-abort`, plus `-Z build-std=std,panic_abort` under `RUSTC_BOOTSTRAP=1`
    (requires `rust-src` on the 1.96 pin). Host build-scripts keep ordinary panic strategy.
  - Post-pack `llvm-strip --strip-all`; no std backtrace/gimli/addr2line strings remain in the
    arm64 shipping `.so`.

### BF-03 Kotlin lifecycle lease and callback boundary

- GREEN: `NativeHandleLease` (RWLock-only, no pre-lock reader counter) + `BoundedInvalidationQueue`
  (capacity 256, overflow conflate, drain thread) unit tests pass.
- GREEN: `BoltFfiNativeEnginePort` enqueues only in callback; shutdown failures are not swallowed;
  `RustEngineAdapter.close()` closes subscription then native port.
- GREEN: production open path — `BoltFfiNativeEngineFactory` + `engineModule` DI binds
  `EngineReadinessRepository` with Koin `onClose` releasing handles.
- GREEN host gate: `just check` with data **1,184** tests.

### BF-04 formal engine smoke

- GREEN (source): `native-smoke` covers planner golden, Direct seed→`RESTART_REQUIRED` kill/relaunch
  recovery, SAF Opening+cancel **callback sequence assertion**, concurrent `state()` vs close,
  shutdown, and DocumentsProvider CRUD.
- GREEN (xtask): device smoke requires API ≥ 26 and a packaged ABI (`x86_64`/`arm64-v8a`/…).
- GREEN (device, 2026-07-17): `just device-smoke` on Samsung SM-S9110, API **36**, abi **arm64-v8a**.
  First failure was `workspace_lock_unavailable: try_lock() not supported` because Rust std
  `File::try_lock` is stubbed on Android. Fixed with a pure-safe exclusive lock (`create_dir` +
  pid owner + stale reclaim via `/proc`), no first-party `unsafe`. After fix: seed →
  `RESTART_REQUIRED` relaunch → **PASS** (repeated clean run also PASS).

### BF-05 performance and size gates

- Warm generate samples (xtask `native arm64-v8a`, migration host, three consecutive runs after
  caches warm): **199 / 162 / 146 ms** (median **162 ms**). Generate no longer needs host `.so`
  scan.
- Single-ABI packaging wall-clock after warm caches: **~23 s** (arm64-v8a samples 22.9–23.4 s),
  dominated by release-android build-std link/strip rather than tool acquisition.
- Four-ABI cold-ish packaging wall-clock with build-std: **~112–118 s** on full size-policy pack.
- Size gate **GREEN** (2026-07-17 shipping pack after Android flock fix):
  - arm64-v8a **331,576**
  - armeabi-v7a **252,520**
  - x86 **391,876**
  - x86_64 **429,096**
  - four-ABI total **1,405,068** vs UniFFI+JNA baseline **2,476,652** (**0.567×**).
- Generated Kotlin after canonicalize: **72,836 bytes / 2,183 lines** (vs UniFFI baseline
  194,546 / 5,530).
- Runtime `state()` / planner / callback p95: UniFFI production binary was destructively removed in
  BF-06, so same-host UniFFI vs BoltFFI microbenchmark is no longer runnable. Shipping proof is the
  size gate + device lifecycle smoke; host warm generate remains ≥30% under UniFFI baseline samples
  recorded at freeze.

### BF-06 destructive cutover and tail deletion

- GREEN (source identity): removed UniFFI deps/macros/`uniffi.toml`, JNA packaging path,
  `FeasibilityProbe` feature/source/tests, `rust-bindings` module, and production dual jniLibs
  branches. Identities are `native-bindings` / `com.lomo.nativebridge` / `liblomo_native_jni.so`.
- GREEN (APK packaging): `app/module.yaml` no longer carries JNA `libjnidispatch` resource excludes;
  arm64 dynsym audit shows `JNI_OnLoad` and no uniffi/jna/feasibility symbols.
- GREEN (Kotlin data suite host): engine + mutation write-gate contracts pass; broader data suite
  remains path-aware under `just check`.
- GREEN (Rust host contracts): architecture tests 14/14, `lomo-native` FFI contracts 3/3 engine +
  remaining package tests, `lomo-core` contracts 12/12 (plus lock stale-reclaim scenario).
- GREEN (ARCHITECTURE.md): current-fact section flipped to BoltFFI/JNI after device-smoke + size
  gates.

## Remaining stage-1 exit gates (honest pending)

### Device policy (owner decision 2026-07-18)

- **Hard device gate for P1 close / P2 entry:** `just device-smoke` on attached **API ≥ 26** device
  with a packaged ABI. Current line: **API 36 arm64-v8a** SM-S9110 — **GREEN 2026-07-17**
  (`xtask: device smoke passed`, durable recovery relaunch). Native-smoke path only; production
  Main App Ready path is host unit tests + DI wiring, not device-smoke.
- **API 26 x86_64 AVD matrix:** `pending_env` / **non-claim**. No AVD on this project line. Must
  **not** block P1 formal close or P2 entry, and must **not** be marked GREEN without a real run.
  Product `minSdk`/NDK API 26 and four-ABI ELF validation remain mandatory build gates.
- Re-run arm64 `just device-smoke` only when engine, lock, packaging, or smoke surface changes.

### Historical open list (pre-B4e; struck 2026-07-18)

> The bullets below were the honest open set **after B4d** and **before B4e**. They are **not**
> current. B4e closed residual write inventory, `just check`, `just ci`, shipping four-ABI size,
> P1-07/P1-08 formal exit, and re-ran API ≥ 26 arm64 device-smoke. See **B4e** section below.
> Do not cite this subsection as live blockers.

- ~~`just ci` pending~~ → **GREEN** under B4e (`xtask: ci complete`, coverage 81.21%, shipping size).
- ~~Four-ABI shipping pack re-measure~~ → **GREEN** under B4e Release/`just ci` total **1,430,308**.
- ~~Formal P1-07 / P1-08~~ → **closed** under B4e residual search + architecture 14/14.
- ~~Residual product-write inventory beyond B4d~~ → **closed** under B4e inventory table + 28/28.

### Non-blockers / non-claims (current)

- API 26 x86_64 AVD complete matrix: **non-claim** (see device policy above). Not a P1/P2 entry
  blocker; must not be marked GREEN without a real run.

## B4e residual product-write inventory + formal P1-07/P1-08 exit (2026-07-18)

### First principles

1. **Invariant:** non-Ready / write-frozen → no product workspace mutation; one process
   `WorkspaceWriteAuthority`.
2. **Axiom violation:** outer `GitSyncConflictRepositoryImpl.resolveConflicts` lacked the same
   structured `WORKSPACE_WRITES_UNAVAILABLE` entry gate; `GitSyncMemoMirror` wrote/deleted repo memo
   files without consulting authority; SyncInbox / S3/WebDAV conflict+review / migration import
   product entries were incomplete relative to the global inventory; formal `just ci` + P1-07/08
   exit package had not been re-run after residual inventory.
3. **Rebuild:** fail-closed outer gates at every remaining product entry; authority at
   `GitSyncMemoMirror` mutators; exhaustive inventory note; formal CI/shipping/device exit evidence.
4. **Edge:** structured `WORKSPACE_WRITES_UNAVAILABLE_MESSAGE` (or equivalent UnifiedSyncError) at
   product entries; constructor-required authority (DI/`singleOf`); compile-time missing authority.
5. **Tail deletion:** stale "pending inventory / pending just ci / P1-07 open" language for items
   closed below; false partial GREEN.

### Residual write inventory (exhaustive for product workspace mutators)

| Surface | Gate | Notes |
| --- | --- | --- |
| Memo mutation / trash / outbox drain | Ready+!freeze via `WorkspaceWriteAuthority` | B4b/B4d |
| Markdown/media storage delegates + WorkspaceMediaAccess | requireWritable at mutators | B4b |
| MediaRepository import/remove/ensure/voice | requireWritable | B4 |
| S3/WebDAV/Git operation entries (sync/init/commit/maintenance) | outer isWritable → Error | B4c/B4d |
| S3 vault-root / LocalMediaSyncStore / SafGitMirrorBridge push | requireWritable | B4c |
| Git conflict apply / reset / force-push / media bridge / ensureGitignore / migrateGitRepo | requireWritable | B4d |
| **GitSyncConflictRepositoryImpl.resolveConflicts** | **outer isWritable → Error** | **B4e** |
| **GitSyncMemoMirror mirror to/from repo (writeText/delete)** | **requireWritable** | **B4e** |
| **S3ConflictResolver / S3ReviewResolver** | **outer isWritable → Error** | **B4e** |
| **WebDavConflictResolver / WebDavReviewResolver** | **outer isWritable → Error** | **B4e** |
| **SyncInboxRepositoryImpl.sync / resolveReview** | **outer isWritable → Error** | **B4e** (workspace import apply + inbox source cleanup after import; not app-private) |
| **MigrationArchiveRepositoryImpl.importAllNotesArchive** | **requireWritable** | **B4e** |
| Nested inbox delete helpers / conflict bridges | inherit outer entry + storage choke | inventory closed |
| App-private / non-workspace | **named exclusion** | custom fonts, app-update download/install state, share-card temp cache, engine exchange temp, DataStore/Room/protocol index under filesDir — not product workspace roots |

### RED → GREEN (write residual suite)

- RED (pre-fix fact): `GitSyncConflictRepositoryImpl` had no `writeAuthority` parameter and returned
  engine/mirror side effects while frozen; `GitSyncMemoMirror` wrote `target.writeText` /
  `file.delete` without authority; SyncInbox/S3/WebDAV conflict/review/migration import entries
  lacked outer structured fail-closed.
- GREEN command:
  `./kotlin test --include-module=data --include-classes='com.lomo.data.repository.RemoteSyncWriteAuthorityGateTest' --include-classes='com.lomo.data.repository.GitSyncConflictRepositoryImplTest' --include-classes='com.lomo.data.repository.SyncInboxRepositoryImplTest'`
- GREEN result (2026-07-18 B4e): **28/28** (RemoteSyncWriteAuthorityGate **16**, Git conflict repo
  **6**, SyncInbox **6**). New scenarios: outer Git conflict structured Error without engine/mirror;
  MemoMirror freeze leaves no repo memo file; SyncInbox freeze → UnifiedSyncError; S3/WebDAV
  conflict resolvers structured Error without `resolveConfig`.
- Related constructor suites GREEN same day: S3ConflictResolver, WebDavConflictResolver,
  MigrationArchiveRepositoryImpl, SyncInbox structure, GitSyncRepositoryImpl, DataDiBoundary
  (broad constructor-impacted run **88/88**).
- GREEN command: `just check`
- GREEN result: **check complete** (2026-07-18 B4e) data suite **1240/1240**.

### P1-07 smoke migration + tail deletion formal audit

- Residual search (production sources, excluding docs/gates/baselines): no live `FeasibilityProbe`
  feature/source/tests; no UniFFI/JNA production packaging; no `libjnidispatch.so` / old
  `liblomo_native.so` / `rust-bindings` production identity. Remaining hits are architecture
  forbids, xtask packaging validators, STAGE0 feasibility tooling contracts, and docs.
- Architecture tests: **14/14** (`cargo test -p lomo-architecture-tests --locked`).
- Identities: generated module `native-bindings`, package `com.lomo.nativebridge`, only packaged
  Lomo library `liblomo_native_jni.so`.
- native-smoke formal surface remains engine lifecycle/SAF/kill-relaunch (BF-04); re-validated on
  device below after packaging rebuild.

### P1-08 formal exit package

- GREEN command: `just ci`
- GREEN result (2026-07-18 B4e): **`xtask: ci complete`** (exit 0). Includes cargo-deny, Rust LLVM
  coverage, Kotlin JaCoCo, Compose static, fat-LTO four-ABI Release native pack, APK ELF validation.
- Rust coverage TOTAL lines **81.21%** (≥80 floor). Coverage lift: physically separate
  `rust/native/tests/conversion_contract.rs` exercising every platform action/output/error mapping
  branch via `#[doc(hidden)] pub` conversion helpers (architecture forbids `#[cfg(test)]` in
  `native/src`).
- Shipping four-ABI size (Release path inside `just ci`, **not** Dev jniLibs):
  - arm64-v8a **338,464**
  - armeabi-v7a **257,704**
  - x86 **399,156**
  - x86_64 **434,984**
  - four-ABI total **1,430,308** vs UniFFI+JNA baseline **2,476,652** (**0.577×**);
    `four-ABI shipping size gate GREEN (1430308 <= 2476652)`.
- Generated Kotlin after canonicalize: **72,836 bytes / 2,183 lines**.
- Device hard gate re-run after packaging rebuild:
  - GREEN command: `just device-smoke`
  - GREEN result (2026-07-18): SM-S9110 API **36** abi **arm64-v8a**;
    `xtask: device smoke target API 36 abi arm64-v8a` → durable recovery relaunch →
    **`xtask: device smoke passed`**.
- API 26 x86_64 AVD: still **pending_env / non-claim** (not a P1/P2 blocker).

### Status flip

- Global product write-path inventory: **closed** (GREEN + named non-workspace exclusions only).
- `just check`: **GREEN**.
- `just ci`: **GREEN** with recorded shipping size + coverage.
- P1-07 / P1-08 formal exit checklist: **closed** against STAGE1-CONTRACT exit bullets for this line.
- **P1 closed for P2 entry on this contract.** API 26 AVD remains non-claim only.