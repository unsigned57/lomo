# Architecture Overview

This document is the stable architecture entrypoint for the repository. It describes module boundaries and dependency direction only. It intentionally avoids volatile directory inventories so it stays useful as the tree changes.

## Modules

### `domain`

- Pure business layer.
- Owns domain models, repository contracts, and use cases.
- Owns the platform-neutral `EngineReadiness` write-authority contract. Only `Ready` is writable.
- Must stay free of Android, Compose, Lifecycle, Room, Koin (or any DI framework), Ktor, JGit, and any `com.lomo.data.*` type.

### `data`

- Infrastructure and integration layer.
- Implements `domain` contracts and owns persistence, sync engines, file access, network/storage glue, and background work support.
- Sole production consumer of generated `native-bindings` / `com.lomo.nativebridge`.
- Owns the BoltFFI lifecycle lease, bounded callback-invalidation queue, capability registry,
  exchange resolver, DocumentsContract platform-action gateway, platform-batch runner, and the
  process-owned `ManagedEngineSession`. The session implements domain-facing
  `EngineReadinessRepository` and owns the production render/scan/document-command route through
  the same active `RustEngineAdapter` / `BoltFfiNativeEnginePort`; workspace switch and close take
  an exclusive session lease after all in-flight capability calls release. There is no second
  workspace engine/adapter owner and no dual-stack Markdown DI (Kotlin parser + Rust) after cutover.
  After P3-10 there is also no dual-stack local-data DI (Room + Rust): store query/mutation/reminder/
  rebuild go through the same session → native store FFI → `lomo-store`.
  Cold start opens with no workspace; Direct/SAF selection (or cold restore) activates a candidate
  engine and only then closes the previous engine. Callbacks enqueue invalidations only and must
  never re-enter FFI.
- Memo mutation/trash repository implementations fail closed unless the engine is `Ready` and writes
  are not frozen.
- Owns process-local `WorkspaceWriteAuthority` as the shared Ready+!freeze choke for workspace file
  mutations. Markdown/media storage delegates and `DefaultWorkspaceMediaAccess` write/delete paths
  consult it so process-start, migration import, and remote sync apply cannot write outside
  Ready+!freeze.
- After P3-10, Kotlin does not open Room/SQLite for memo projections. Sync/cache tables that remain
  until stage 5 are file-backed (no `androidx.room`).
- **Stage 5 production rule (post P5-13):** `lomo-sync` owns remote sync; Kotlin retains config/Keystore/WorkManager/UI. Historical until-cutover rule: Git/WebDAV/S3 sync business owners, workers, and
  pending stores remain Kotlin. Dark Rust sync stacks must not enter production registry,
  navigation, or scheduler. After P5-13, production dual-stack sync is forbidden and Kotlin sync
  business tails are deleted in the same wave; Kotlin retains DataStore non-secret config, Keystore,
  generic WorkManager runner, SAF action executor, notifications, and Compose (including Sync Center
  UI).
- **P5-09 Kotlin dark surface (unregistered until P5-13):** `data` may compile production-shaped
  dark owners that map only to existing free-function BoltFFI sync exports
  (`syncListConflicts` / `syncResolveConflicts` / `syncReadConflictArtifact` /
  `syncInspectCyclePlan` / secret lease issue·probe·revoke / `syncRetryDispositionFromName`):
  `com.lomo.data.engine.sync.RemoteSyncRepository` +
  `BoltFfiRemoteSyncRepository` + `SyncNativeBridge` / `FreeFunctionSyncNativeBridge`,
  `RemoteSyncCenterRepositoryAdapter` + `ConflictArtifactSource` / `BridgeConflictArtifactSource`,
  `RustSyncSecretSupplier` / `KeystoreRustSyncSecretSupplier` (lease **ids** only; never journals
  plaintext), and unregistered `com.lomo.data.worker.RustSyncWorker` + `RustSyncRetryPolicy` +
  `RustSyncWorkExecutor` / `RustSyncWorkRequest` + dark `RemoteSyncRustWorkExecutor` (CoroutineWorker-
  shaped `doWork`: process-local secret lease issue/revoke around a work unit; maps Rust
  `RetryDisposition` / optional `retryAfter` → WorkManager result types; **no** fixed three-retry
  business logic; fail-closed missing lease / blank workspace; work unit probes opaque lease id +
  Rust-owned `inspectCyclePlan` conversion surface via dark `RemoteSyncRepository` — not a Kotlin
  planner). These types must not appear in
  `SyncDataModule`, navigation, or scheduler enqueue before atomic P5-13. Fake-first host tests under
  `data/test/engine/sync` and `data/test/worker` exercise mapping + lease orchestration + work unit +
  composition (worker + real executor) without JNI. Dual-stack flags (`use_rust_sync`, etc.) remain
  forbidden. **Wave-6 host residual honesty (2026-07-24):** unregistered `CoroutineWorker` body is
  host-tested GREEN. **Wave-7 host residual honesty (2026-07-24):** unregistered
  `RemoteSyncRustWorkExecutor` impl + fake-first tests GREEN. **Wave-8 host residual honesty
  (2026-07-24):** dark cycle free-function `sync_inspect_cycle_plan` + repo method + executor cutover
  to cycle surface + composition FunSpec GREEN; production `workerOf` / shared scheduler enqueue
  remain OPEN until P5-13 (policy+body+executor compile dark only).
- **P5-10 Sync Center dark Compose shell (unregistered until P5-13):** `domain` owns
  `RemoteSyncCenterModels` + `RemoteSyncCenterRepository` (list/resolve + markdown/binary detail
  ports). `data` owns dark unregistered `RemoteSyncCenterRepositoryAdapter` mapping
  `RemoteSyncRepository` BoltFFI facts → domain center port and loading markdown base/local/remote
  bodies from durable conflict artifact refs (binary never invents text preview). `app` feature
  `com.lomo.app.feature.synccenter` owns pure reducer/ViewModel/Compose (config/session/conflict
  list-detail/recovery shells; **ViewModel on select calls domain detail ports**; state carries
  detail facts; Compose prefers state facts over digest-only helpers; binary no text preview;
  markdown digests + optional bodies + merged draft; adaptive phone vs expanded). Not in
  `ViewModelModule`, `LomoNavHost`/`NavRoute`, `SyncDataModule`, or live Settings production path.
  App depends on domain ports only (no `com.lomo.data.*` compile).
- **SAF projection exception (Stage 5, P5-04 host):** unified Direct/SAF local sync ports land in
  `lomo-store`. SAF projection DB is app-private, bound to `WorkspaceGenerationId`, and fully
  rebuildable from the workspace. User-byte and `.lomo` mutations still go through
  `PlatformActionBatch` / expected-revision ports — never a Git- or provider-specific user-file
  mirror. Kotlin SAF action executor / device wiring remains residual (not closed by P5-09 dark
  free-function / Kotlin adapter land; still P5-13+ / `pending_env` for device).
- New repository implementations belong here, typically under `data/repository`.

- **Stage 6 LAN production owner (P6-10 atomic cutover):** `lomo-lan`, reached only through the
  sole managed `LomoEngine` handle, owns device trust, pairing/session transcripts, the versioned
  TCP wire, approval/batch/chunk journals, resume, and per-item workspace commit fences. The
  production `RustLanShareService` is a thin adapter: Kotlin supplies NSD discovery, validated
  Android network snapshots, local-network permission, multicast-lock lifetime, Keystore public
  identity/signatures, source-byte streams, preferences, and Compose projections. Kotlin no longer
  contains an HTTP LAN server/client, pairing secret or E2E toggle, OPEN mode, peer-UUID trust,
  protocol crypto, transfer state machine, or direct incoming workspace writer. The Rust runtime
  remains single-writer through the same engine; no second listener or fallback wire is permitted.
  **Architecture Impact:** owner is `lomo-lan`; the boundary moves all LAN protocol/state decisions
  behind the existing managed engine, while Android platform facts and non-exportable Keystore
  operations remain the explicit platform exception.

### `native-bindings`

- Ignored, generated Android/Kotlin binding layer for repository-owned Rust infrastructure.
- Owns BoltFFI-generated Kotlin/JNI declarations only; business rules and Android orchestration do not belong here.
- Package is fixed as `com.lomo.nativebridge`; the packaged library identity is `liblomo_native_jni.so`.
- `lomo-xtask` regenerates this module before any Kotlin build; generated source is never a versioned fact.
- May be consumed by `data`; must not be imported by `domain`, `app`, or `ui-components`.

### `rust/lomo-sync-core` (deleted at P5-13)

- Historical frozen sync-v1 planner. **Absorbed/removed at P5-13**; directory and workspace member
  must not reappear in the production graph. Planning authority is solely `lomo-sync`.

### `rust/sync` (package `lomo-sync`) — Stage 5 production owner (post P5-13)

- **Present from P5-03 (dark).** Sole sync business owner target after P5-13: snapshot / plan /
  apply / verify / baseline / tombstone / conflict / recovery / cancel / retry for Git, WebDAV, and
  S3. Host hermetic slice lands first; production DI remains off until atomic P5-13.
- Owns durable `.lomo/sync/v1` session/baseline/tombstone/conflict state and the provider-neutral
  `RemoteSnapshot → ProviderNeutralIntent → PreparedRemoteBatch → PublishReceipt →
  VerifiedRemoteState` pipeline. Provider adapters only implement protocol ports.
- Depends inward on `lomo-core` + `lomo-workspace`. Local expected-revision mutation uses
  `lomo-store` ports (`snapshot_sync_view` / `prepare_sync_apply` / `commit_sync_apply` /
  `LocalSyncMutationBatch`) as a host-side dependency at the FFI/composition edge only — this crate
  itself must not depend on Android, BoltFFI, UniFFI, JNI, or tooling crates, and must not write
  user files except via those store ports.
- **Dark-build until P5-13:** production DI remains Kotlin Git/WebDAV/S3 owners + frozen
  `lomo-sync-core`. No progressive dual DI, dual-write, or `use_rust_sync` flags. **P5-09 (host):**
  dark `lomo-native` free-functions may depend on this crate for **conversion-only** mapping
  (`sync_list_conflicts` / `sync_resolve_conflicts` / `sync_inspect_cycle_plan` empty-port inspect);
  host residual deepen also exposes `inspect_sync_cycle_plan_with_ports` (hermetic fakes only; not
  BoltFFI). Dark Kotlin `RemoteSyncRepository` / `RustSyncWorker` / secret-supplier adapters may call
  conversion free-functions (compile + fake-first tests) but production Kotlin DI / registry /
  navigation / WorkManager must not bind them until P5-13.
- **P5-05 (host):** ships a dark `WebDAV` `RemoteSyncPort` adapter (reqwest/rustls, Multi-Status,
  conditional PUT/DELETE, hermetic fault-server matrix, **Wave-13 `list_remote_pages` multi-page
  residual** pages ≤512 with single-shot still ≤512 Incomplete when truncated). Adapter
  compiles/executes intents only; no WebDAV-specific planner/session/baseline/tombstone/retry state
  machine. Not production-wired.
- **P5-06 (host):** ships a dark path-style S3 `RemoteSyncPort` adapter (reqwest/rustls + hand
  `SigV4`, ListObjectsV2 pagination, conditional PUT/DELETE, multipart publish with hermetic
  in-process resume proven by mid-fail inject + second publish (confirmed parts not re-uploaded),
  durable on-disk multipart process-death resume, rclone crypt fixture vectors). Stage-5 product law:
  PathStyle + Auto share path-style URL construction (virtual-hosted is real-provider smoke only);
  rclone host-proven surface is fixture standard/base32/dir + data seal (other modes typed code-path
  only). Adapter compiles/executes intents only; multipart is publish detail, not a second planner.
  Not production-wired; AWS four-ABI production link and real R2/S3 smoke remain OPEN / `pending_env`.
- **P5-08 (host):** durable conflict session (base/local/remote digests, artifact refs, remote token,
  monotonic conflict revision), plan→`materialize_conflicts_from_plan` on `OpenConflict` (hollow open
  rejected), expected-revision resolve, Markdown `MergedBody` re-parse via `lomo-workspace` parser +
  budgets, binary KeepLocal/KeepRemote/SkipForNow only, `baseline_must_hold_for_path` integrated into
  `run_sync_cycle` baseline commit, KeepLocal/Merged remote apply via expected-revision tokens +
  verify-before-baseline, KeepRemote/Merged **local** pull via
  `collect_resolved_local_pull_mutations` + host store expected-revision apply +
  `advance_baseline_after_local_pull`, tombstone-first user delete under hard gates with
  `recover_pending_delete_intent` on session revive, delete-vs-edit → `OpenConflict`, offline revival
  fence, identity-reset control-tree clear (never user files), secret-free diagnostic export.
  Streaming multi-page `OpenConflict` outside first intent page fails closed permanently
  (`streaming_open_conflict_outside_first_page` — permanent product law, not design residual).
  Not production-wired. Residual OPEN: full multi-process OS-kill crash graph.
- Absorbs and replaces `lomo-sync-core` at cutover; the two planners must not coexist in production.

### `rust/git` (package `lomo-git`) — Stage 5 production Git adapter (post P5-13)

- **Present from P5-07; production-composed from P5-13+.** Sole `git2` / libgit2 adapter. Exposes
  typed tree/ref/object operations and a public `RemoteSyncPort` implementation (`GitAdapter`) that
  compiles path intents into tree/commit + non-force CAS ref push (`WholeBatchRef`). Does not own
  sync direction, conflict, baseline, tombstone, or retry policy (`lomo-sync` remains the sole planner).
- Only this crate (plus tooling `lomo-feasibility`) may depend on `git2` in production deps. Force
  push / reset-to-remote / checkout user worktrees are permanently forbidden in the adapter.
- Direct: open existing `.git` in place (object graph + CAS push only). SAF / production composition:
  app-private bare mirror for Git objects/cache only; user workspace bytes still flow through the
  unified store/workspace expected-revision path (no provider-specific user-file write path).
  “Rebuild local mirror” deletes/rebuilds only the app-private mirror tree.
- Stale `index.lock` reclaim only when owner PID is gone **and** frozen threshold elapsed. Shallow /
  unproven merge-base blocks (no guess). Git URL/token/libgit2 diagnostics redact secrets.
- **Composition (post P5-13):** `lomo-native` may depend on `lomo-git` and construct the adapter at
  the conversion edge (`sync_run_cycle` → `connect_workspace_git` →
  `run_composed_sync_cycle_with_remote_port`). `lomo-sync` must **not** depend on `lomo-git` (avoids
  crate cycle: `lomo-git` already depends on `lomo-sync` for `RemoteSyncPort`). Real GitHub/GitLab
  HTTPS smoke remains OPEN / `pending_env`.

### `rust/lomo-core`

- Pure platform-independent application-kernel owner.
- Owns constrained engine/job/platform protocol types, workspace identity, the OS-backed
  single-instance lock, checksummed control journal, bounded single-writer actor, cancellation,
  deadlines, snapshots, and loss-detectable events.
- Must not depend on Android, BoltFFI, UniFFI, JNI, SQLite, networking backends, sync-v1 wire types, or tooling
  crates. Its journal under application-private storage is distinct from future workspace mutation
  facts under `.lomo/operations`.
- Workspace exclusivity uses a pure-safe atomic `create_dir` lock with pid-based stale reclaim
  via `/proc` (no first-party `unsafe`; not OS flock; std `File::try_lock` is stubbed on Android
  and is not used).

### `rust/workspace` (package `lomo-workspace`)

- Pure platform-independent workspace document and Markdown semantic owner for stage 2.
- Owns constrained source bytes / path / fingerprint / span / resource-limit types, memo identity
  (`${dateKey}_${timePart}_${ordinal}`), same-parse storage analysis, `RenderDocumentV1` /
  `render_markdown`, and pure document patch planning (`plan_document_patch`). Depends inward on
  `lomo-core` only for shared error/protocol types.
- May depend on pinned `pulldown-cmark 0.13.4` (`default-features = false`); non-owner production
  crates must not.
- Must not depend on Android, BoltFFI, UniFFI, JNI, SQLite, networking backends, sync-v1 wire, or
  tooling crates.
- **Production ownership (post P2-09 cutover):** document model + Render IR + pure patch planner
  + multi-phase scan / document-command job drivers are the sole Markdown semantic authority.
  Free-content attachment destination remap (`remap_attachment_destinations`), canonical
  reminder token construction / mark-done / record-fired mutation planning, memo body extract from
  raw, and identity-keyed memo-shard conflict merge (`merge_memo_shard_by_identity`) are also
  owner-only; Kotlin supplies opaque name maps, typed fields, or local/remote shard bytes and
  applies planned bytes/tokens only.
  `lomo-native` depends on this crate for **conversion-only** BoltFFI DTO mapping. Kotlin production
  consumes domain IR / workspace session adapters only — it must not reintroduce Kotlin
  `MarkdownParser`, JetBrains AST, private Markdown remappers under `data/src/share`, reminder
  token grammar builders, header-line memo-block split for conflict write-back, or parallel
  semantic regex authorities. Presentation spacing helpers may collapse plain text; they must not
  re-parse Markdown structure or invent link markup before owner render.
- **Stage-5 P5-01 ownership (host-green dark facts):** also owns the generic `.lomo` durable record
  codec (`LOMO` magic + schema + length + checksum; temp+fsync+rename), layout roots
  (`LomoPaths` / `LomoLayoutVersion` v1|v2 + layout head), real-random durable
  `WorkspaceGenerationId` under `.lomo/local/v1/generation.rec` (never synced/archived; archive
  activation mints new), content-addressed history/state v2 models (`RevisionId`,
  `HistoryRevisionV2`, `StateRevisionV2`, retention=20 + permanent tombstones), and the one-shot
  v1→v2 migration that is read-only over user Markdown/media (atomic layout-head switch only).
  Transaction / projection / SQLite projection semantics remain in `lomo-store`.

### `rust/media` (package `lomo-media`)

- Pure platform-independent media identity and lifecycle owner for stage 4 (**dark-build until
  Wave A cutover P4-10A**).
- Owns streaming `sha256` content digests, self-held magic-byte MIME table (extension is hint only;
  conflict rejects), workspace-relative media path validation via `lomo-workspace` path policy,
  stage→verify→commit (paths only; no full media-byte public APIs), attachment refcount across
  {current, trash, history}, deterministic orphan sweep + media-trash recovery window, and
  recording allocate/finalize targets.
- Depends inward on `lomo-core` + `lomo-workspace` only. Must not depend on `lomo-store`, native,
  BoltFFI, Android, sync-v1, or tooling crates.
- Disk filenames remain human/timestamp schemes (never hash-named). Dedup identity is digest.
- **P4-10A cutover (2026-07-21):** production media edge is `MediaEdgeRepository` + `MediaPort` /
  `MediaSyncEdgeAdapter` (path-only). Kotlin no longer owns identity/orphan/index.
- Production dual-stack with Kotlin `MediaRepositoryImpl` / orphan cleaners is forbidden after
  Wave A; dark-build must not wire feature-flag dual-write.

### `rust/store` (package `lomo-store`)

- Pure platform-independent local data-loop owner for stage 3 (**production sole owner after P3-10
  cutover**).
- Owns SQLite query projections, FTS5 + pure-Rust CJK tokenizer, memo transaction recovery,
  `.lomo/` durable **transaction** bodies / operation intents / v1 state-history body types used by
  the memo machine (generic codec + layout roots re-exported from `lomo-workspace` after P5-01),
  rebuild (P3-01..P3-06), reminder business state (P3-07: recurrence/fired/done/next-trigger,
  floating local + DST policy, catch-up ≤1/session, app-private snooze), and **archive v2
  orchestration** (P4-06..P4-08: `ArchiveManifestV2` export/inspect/import/activate using
  `lomo-workspace` path/markdown facts and `lomo-media` media validation). Depends inward on
  `lomo-core` + `lomo-workspace` + (stage 4) `lomo-media` (+ `rusqlite` bundled/`backup`, `sha2`,
  `serde`/`serde_json`, trimmed `zip` deflate). Must not depend on Android, BoltFFI, UniFFI, JNI,
  networking backends, sync-v1 wire, or tooling crates. SQLite files live under workspace
  `.lomo-sqlite/` (never under `.lomo/`). Snooze is app-private only (never under `.lomo`/sync/archive).
- **Stage-5 archive allowlist residual (P5-01 host):** export includes markdown/media and durable
  `.lomo` history/state (v1+v2) + layout head; excludes `.lomo/local`, `.lomo/sync`,
  `.lomo/operations`, migration-staging, remote-control, SQLite/WAL, media stage/trash, temps.
- **Schema v1 open contract:** `foreign_keys=ON`, WAL, busy timeout, `user_version` check, quick
  integrity; unknown/higher schema fails closed (no destructive downgrade).
- **Production ownership (post P3-10 cutover):** `lomo-store` is the sole local-data authority via
  `lomo-native` conversion-only store FFI and data `StorePort` / `StoreMemo*` adapters + Paging.
  Kotlin never opens SQLite for memo projections; Room database/entity/DAO/migration/KSP/runtime
  are deleted from the production graph. Sync metadata that formerly lived in Room is clean-slate
  file-backed under app-private storage until stage 5 Rust sync ownership. No feature-flag
  dual-write and no progressive dual-stack DI. `AlarmManager` is schedule/cancel only.
- **Cutover (P3-10):** freeze writes → fail-closed drain of legacy Kotlin memo file outbox (never
  discard) → re-scan/rebuild Rust DB → compare counts/digests → switch DI → delete Room family in
  the same wave. Old Room pin/history/sync metadata are not migrated (clean-slate re-scan).

### `rust/lomo-native`

- The only production native facade (`staticlib` + `rlib`).
- Linked with generated BoltFFI JNI glue into the only packaged Android library,
  `liblomo_native_jni.so`, package surface `com.lomo.nativebridge`.
- Depends inward on `lomo-core`, the frozen `lomo-sync-core`, (from P2-06) `lomo-workspace` for
  conversion-only render/scan/document-command DTO mapping, (from P3-09) `lomo-store` for
  conversion-only query/memo/reminder/rebuild DTO mapping, and (from P5-09 dark) `lomo-sync` for
  conversion-only conflict list/resolve free-function DTO mapping + process-local secret lease edge
  for host tests. It must not re-implement Markdown rules (`parse_workspace_document` /
  `plan_document_patch` / pulldown remain workspace-owned), store business rules
  (query/txn/reminder plan remain store-owned), or sync planner rules (`plan_intents` /
  `run_sync_cycle` / session machines remain `lomo-sync`-owned). Must not depend on `lomo-git` until
  a later package explicitly wires Git composition.
- Does not implement business rules; conversion, panic/error isolation, and lifecycle only.
  Production dual-stack DI is forbidden: after cutover only the workspace/store owner is live;
  dark-build store FFI must not be dual-bound with Room in production modules. Dark P5-09 free
  functions must not be bound into production Kotlin DI / registry / navigation / WorkManager until
  P5-13.

### `rust/lomo-xtask`, `rust/lomo-architecture-tests`, and `rust/lomo-feasibility`

- Tooling-only crates that own orchestration, structural enforcement, and stage-0 evidence.
- Must never appear in a production dependency graph.
- `lomo-xtask` is the sole owner of pinned tools, NDK acquisition, generated bindings/native
  libraries, Kotlin Toolchain execution, APK validation, release signing, cache policy, and smoke runs.
  It packages the same formal engine library into production `app/jniLibs` and tooling
  `native-smoke/jniLibs` (no UniFFI/JNA dual stack).
- `lomo-feasibility` owns versioned corpus/report schemas, redaction rules, feasibility exit codes,
  and hermetic probes (SQLite, Markdown, HTTPS, Git). Production crates must not depend on it.
- Repository-root `fixtures/` holds language-neutral golden format assets (`markdown/`, `remote/`,
  `git/`, `characterization/`, `baseline/`). Quality scripts do not own these; generators and
  characterization tests consume them. Large generated corpora stay under gitignored `build/corpora/`.
  Stage-0 reports belong under gitignored `build/reports/feasibility/`.
  Shared stage-0 audit status lives in `fixtures/baseline/STAGE0-STATUS.md`;
  tooling-only `lomo-feasibility-device` packages linked feasibility deps for four-ABI volume/ELF
  evidence and must never enter production `app/jniLibs`. Gitignored local planning notes are not
  provenance.

### `native-smoke`

- Tooling-only Android application used by CI and `just device-smoke`.
- Loads packaged `liblomo_native_jni.so`, exercises the formal BoltFFI engine (open/state/subscribe/
  cancel/shutdown, durable journal seed→kill→relaunch, concurrent close/use), the frozen sync v1
  planner, and a deterministic in-app `DocumentsProvider` SAF surface, then emits an observable
  PASS/FAIL result.
- Must not be packaged into or depended on by the production application.
- Permanent tooling fixture: the smoke DocumentsProvider remains as a SAF harness without product
  policy. `FeasibilityProbe` is deleted.

### `app`

- Android application and feature orchestration layer.
- Owns screens, navigation, ViewModels, settings orchestration, widgets, and app startup behavior.
- Owns the production APK packaging boundary for ignored Rust native libraries under `app/jniLibs`;
  native business behavior remains in Rust.
- Consumes `domain` contracts and shared UI from `ui-components`.
- Must not import `com.lomo.data.*` or `com.lomo.nativebridge`.
- May package `data` as a runtime-only module for application composition, but must not expose `data` on the app compile surface.

### `ui-components`

- Shared UI infrastructure layer.
- Owns reusable Compose components, markdown/text rendering, input surfaces, and theme primitives.
- Must not import `com.lomo.data.*` or `com.lomo.nativebridge`.
- Keep feature orchestration and repository logic out of this module.

## Dependency Direction

- `domain` sits at the center and should not depend on other project modules.
- `data` depends on `domain`.
- `data` may depend on `native-bindings` for generated native calls.
- `native-bindings` is generated from `lomo-native`; `lomo-native` depends inward on `lomo-core` and the
  frozen `lomo-sync-core` facade.
- `app` depends on `domain` and `ui-components`.
- `native-smoke`, `lomo-xtask`, `lomo-architecture-tests`, and `lomo-feasibility` are tooling leaves
  and are never production dependencies.
- `ui-components` may depend on stable presentation-safe types, but must not depend on `data` and should avoid feature/business orchestration concerns.

## Current FFI Transport — BoltFFI/JNI

Stage-0 UniFFI/JNA evidence remains immutable history under `fixtures/baseline/`. Production transport
is BoltFFI/JNI after the migration gates recorded in `fixtures/baseline/STAGE1-EVIDENCE.md`:

- `lomo-native` is a `staticlib` + `rlib` facade linked with generated BoltFFI JNI glue into
  `liblomo_native_jni.so` only (per ABI).
- Generated bindings identity is `native-bindings` / `com.lomo.nativebridge`.
- `data` owns lifecycle leases, invalidation queues, capability/exchange/SAF execution, and domain
  readiness publication.
- UniFFI, JNA, `libjnidispatch.so`, `FeasibilityProbe`, `rust-bindings`, and `com.lomo.rust` are not
  production identities.
- Four-ABI shipping size is under the frozen UniFFI+JNA native sum gate (see STAGE1-EVIDENCE BF-02/05).
- Device smoke on API ≥ 26 arm64 (and emulator when available) exercises formal engine recovery.

Runtime p95 comparison against the frozen UniFFI host samples remains recorded as host-relative when
the same-host UniFFI binary is no longer present; size and device lifecycle gates are the hard
shipping proof after cutover.

## Change Routing

- New business behavior starts in `domain`, then gets implemented in `data`, and finally consumed from `app`.
- New repository implementations belong in `data`.
- ViewModels should depend on `domain` contracts and use cases, never on DAO, RoomDatabase, repository implementations, sync engines, `DocumentFile`, or direct filesystem helpers.

## Enforcement

- `AGENTS.md` carries the AI workflow and architecture gate.
- `quality/detekt-rules` enforces repository-specific architecture rules.
- `quality/README.md` is the verification entrypoint for build, lint, detekt, coverage, and quality scripts.
- Rust architecture tests enforce the unique native facade, dependency matrix, inherited governance,
  generated-output ownership, Amper-native Kotlin layout, Markdown link integrity, physical test
  separation, and absence of first-party unsafe code.

## Architecture Impact

- Owning layer: `lomo-core` owns the formal application kernel; `lomo-native` only converts the
  BoltFFI boundary; Kotlin `data` is the only production consumer of generated `native-bindings`.
  After P3-10, `lomo-store` (+ `lomo-workspace`) jointly own the local data loop; `data` adapts
  store/reminder/rebuild surfaces and Android platform ports only.
- Boundary effect: `app` remains the production native packaging boundary; `native-smoke` remains
  an isolated CI-only packaging boundary. Domain/app readiness contracts must not expose Rust DTOs.
  Room is absent from the production dependency graph; Kotlin never opens SQLite for memo index.
- Write authority: domain `EngineReadiness` is the global hard gate; create/switch use cases and
  data mutation repositories enforce Ready + write-freeze at shared choke points. Workspace switch
  is validate → freeze → persist → activate engine → rebuild → unfreeze; `SwitchRootStorageUseCase`
  is the sole rebuild owner for intentional root switches. Observe-root must not rebuild while
  freeze is active or while readiness is not Ready for the engine identity matching the persisted
  selection. On switch abort after any partial rebuild/clear, previous selection+engine+index are
  restored (mandatory re-scan of the restored selection). There is no old-Kotlin-core write fallback
  and no Room dual-write path.
- Exception: preferences remain Kotlin DataStore; AlarmManager/notification/exact-alarm capability
  remain Kotlin/Android execution. Sync provider business ownership remains stage-5 work; interim
  sync metadata is file-backed clean-slate (not Room).
- Permanent tooling fixture: smoke `DocumentsProvider` remains for SAF harnesses without product
  policy; production modules must not import it.

## Documentation Trust Model

- Treat this file and `AGENTS.md` as the durable architecture source.
- `quality/README.md` owns executable quality-gate documentation; `quality/release.md` owns release
  and signing documentation; language-specific test rules live under `quality/testing`.
- Concrete paths, APIs, and implementation details are code facts. Verify them against the tree
  instead of maintaining module file inventories.

## Architecture Impact (P4-10A/B, 2026-07-21)

- Owner: `lomo-media` (identity/lifecycle) + `lomo-store` archive v2 orchestration; Kotlin
  `MediaEdgeRepository` / `WorkspaceArchiveEdgeRepository` are path-only adapters.
- Boundary effect: production DI wires `MediaPort`/`ArchivePort` through `ManagedEngineSession`;
  sync edge journals only committed media (D8). No dual-write flags.
- Exception: none. Settings/credentials encryption remains independent Kotlin surface.

## Architecture Impact (P5-00 Stage-5 dark scaffolding, 2026-07-22)

- Owner (production until P5-13): Kotlin Git/WebDAV/S3 sync engines + frozen `lomo-sync-core`
  planner. Target dark owner: `lomo-sync` (decisions/session/baseline/tombstone/conflict) +
  `lomo-git` (git2 adapter only); crates are not required at P5-00.
- Boundary effect: STAGE5-CONTRACT/EVIDENCE + `stage_five_*` architecture tests fail closed on
  missing scaffolding, empty inventory/divergence/feasibility/size fixtures, production dual-stack
  sync feature flags, or P5-13 GREEN claims without Stage-3 P3-10 and Stage-4 P4-10A/B cutover
  records. Production DI must not wire Rust sync early.
- Final ownership target after P5-13: `lomo-sync` is the sole sync business owner; `lomo-git` is the
  sole git2 adapter; `lomo-store`/`lomo-workspace` own local expected-revision mutation and codec
  facts; `lomo-core` owns actor-external native task + ephemeral secret lease; `lomo-native` is
  conversion-only; Kotlin retains config/Keystore/WorkManager runner/SAF executor/notifications/
  Compose (Sync Center).
- SAF exception: no provider-specific user-file mirror; SAF projection (when present) is
  app-private, generation-bound, rebuildable; user bytes mutate only via platform action batches +
  store commit ports.
- Hard gates inherited for cutover/exit: Stage-0 APK × 1.15 (must not raise), API ≥ 26 arm64
  `just device-smoke`, six real provider smokes, fresh `just check` / `just ci`. Arm64 and provider
  smokes may remain `pending_env` during dark construction — never fictional GREEN.
- Exception: none for dual-write. Settings/credentials encryption remains independent Kotlin surface.

## Architecture Impact (P5-01 identity/codec/history-state v2, 2026-07-23)

- Owner: `lomo-workspace` for durable identity (`WorkspaceGenerationId` / remote dataset + config
  digest types), generic `.lomo` codec + layout head, history/state v2 content-addressed models,
  retention/tombstones, and one-shot v1→v2 migration. `lomo-store` keeps transaction bodies,
  operation intents, rebuild projections, and archive orchestration; re-exports codec surface for
  existing store call sites.
- Boundary effect: codec no longer lives as a store-private implementation; archive allowlist
  excludes device-local trees (local/sync/operations/migration-staging/remote-control). Runtime
  memo transaction writers remain v1 until a later package cuts store writers to v2.
- Exception: none for dual production sync DI. No `lomo-sync` / `lomo-git` yet. No P5-13 cutover.

## Architecture Impact (P5-02 actor-external native task + secret lease, 2026-07-23)

- Owner: `lomo-core` for actor-external native task durability, dispatch fence, completion channel
  drain, crash re-dispatch (`QueuedNative` → new non-zero `dispatch_generation`), and ephemeral
  secret leases.
- Boundary effect: journal may record opaque lease ids only; external workers resolve secrets via
  vault; host may attach `NativeWorkerAttach` for dark/host tests. Production DI remains Kotlin
  until P5-13.
- Exception: none for dual production sync DI. No premature production `WorkManager` wiring.

## Architecture Impact (P5-03 `lomo-sync` durable core dark host, 2026-07-23)

- Owner: new workspace member `rust/sync` (`lomo-sync`) for provider-neutral pipeline types,
  durable session/baseline/tombstone models under `.lomo/sync/v1`, and hermetic state machine
  (first-takeover preflight, partial listing no-delete, verify-before-baseline).
- Boundary effect: dark crate only — not in `lomo-native` production graph, not in Kotlin DI.
  Frozen `lomo-sync-core` remains the sole production Rust planner. Fake local/remote ports only;
  WebDAV/S3/Git adapters land later (P5-05…P5-07; now host-closed dark). Local store mutation ports land in P5-04.
- Exception: none for dual production sync DI. No P5-13 cutover. No SQLite sync authority.

## Architecture Impact (P5-04 unified Direct/SAF local sync ports, 2026-07-23)

- Owner: `lomo-store` for coarse `snapshot_sync_view`, expected-revision `LocalSyncMutationBatch`,
  prepare → verify platform results → `commit_sync_apply`, Direct in-process media FS actions, and
  app-private generation-bound SAF projection rebuild/read cache. `lomo-sync` only bridges store
  coarse facts into `StoreLocalSnapshotPort` / planner (no user-file writes).
- Boundary effect: normal user edits and sync apply share the same memo revision fence; generation
  mismatch / fingerprint mismatch / Failed platform results fail closed with no partial commit. Dark
  only — no production DI, no dual-stack flags, no Kotlin SAF executor device wiring in this package.
- SAF exception confirmed: projection DB is cache not authority; user bytes and durable `.lomo`
  facts still mutate only via platform action batches + store commit ports. No Git/provider-specific
  user-file mirror is an allowed Stage-5 write path (architecture test
  `stage_five_local_sync_ports_forbid_bypass_user_file_writes`).
- Residual OPEN: Kotlin SAF action executor integration / device (P5-09+),
  production cutover (P5-13). WebDAV host residual closed by P5-05; S3 host residual closed by P5-06;
  Git host residual closed by P5-07 (all dark). Host residual note retained from P5-04 exit snapshot:
  (both dark only).

## Architecture Impact (P5-05 WebDAV backend adapter dark host, 2026-07-23)

- Owner: `lomo-sync` for the dark `WebDAV` `RemoteSyncPort` adapter (`WebDavAdapter`), strict
  endpoint normalization, Multi-Status fail-closed parser, status→retry mapping, and hermetic
  fault-server contracts. Core planner/session/baseline/tombstone ownership remains provider-neutral.
- Boundary effect: reqwest/rustls transport is allowed inside dark `lomo-sync` only; production
  remains Kotlin WebDAV owners + frozen `lomo-sync-core`. `lomo-native` must not depend on
  `lomo-sync`. Adapter never owns direction/conflict/baseline/tombstone/retry policy.
- Exception: none for dual production sync DI. Real Nutstore/Nextcloud smoke and arm64 device gates
  remain `pending_env` / OPEN.

## Architecture Impact (P5-06 S3 + multipart + rclone crypt dark host, 2026-07-23)

- Owner: `lomo-sync` for the dark S3 `RemoteSyncPort` adapter (`S3Adapter`), path-style endpoint
  normalize, hand-rolled `SigV4`, ListObjectsV2 fail-closed listing, status→retry mapping,
  multipart publish execution detail, and rclone crypt fixture compatibility. Core remains
  provider-neutral for planner/session/baseline/tombstone/retry.
- Boundary effect: reqwest/rustls + crypto primitives allowed inside dark `lomo-sync` only;
  production remains Kotlin S3 owners + frozen `lomo-sync-core`. `lomo-native` must not depend on
  `lomo-sync`. No progressive dual DI / dual-write / `use_rust_sync` flags.
- Exception: none for dual production sync DI. Hermetic in-process multipart resume + durable
  on-disk/process-death multipart recovery are host-proven. **Wave-15 product law:** PathStyle + Auto
  share path-style URL construction (virtual-hosted is real-provider smoke / `pending_env` only — not
  host residual OPEN). rclone host-proven surface is fixture standard/base32/dir + data seal; full CLI
  mode goldens are not residual OPEN. Real R2/S3 smoke, AWS four-ABI production link, and arm64 device
  gates remain OPEN / `pending_env`.


## Architecture Impact (P5-07 `lomo-git` dark host, 2026-07-23)

- Owner: `lomo-git` for the dark sole `git2` / libgit2 adapter (`GitAdapter` implements
  `RemoteSyncPort`). Compiles path intents into tree/commit + non-force `WholeBatchRef` CAS push.
  Stale lock reclaim, app-private mirror rebuild, and diagnostic redaction live here. `lomo-sync`
  remains the sole planner for direction/conflict/baseline/tombstone/retry.
- Boundary effect: `rust/git` is a workspace member and the only production-graph crate allowed to
  depend on `git2` (tooling `lomo-feasibility` exception remains). Production remains Kotlin Git
  owners + frozen `lomo-sync-core`. `lomo-native` must not depend on `lomo-git` until P5-09 dark FFI /
  P5-13 cutover. No progressive dual DI / dual-write / `use_rust_git` flags. Force push,
  reset-to-remote, and user-worktree checkout remain permanently forbidden in the adapter.
- Exception: none for dual production sync DI. Real GitHub/GitLab HTTPS smoke and arm64 device gates
  remain `pending_env` / OPEN. Planner conflict session product matrix is closed by P5-08 host;
  Git dual-parent merge-commit after resolve remains residual apply depth.

## Architecture Impact (P5-08 conflict / delete / recovery / diagnostics dark host, 2026-07-23)

- Owner: `lomo-sync` for durable conflict sessions, plan→materialize on `OpenConflict`,
  expected-revision resolution, KeepLocal/Merged remote apply + verify-before-baseline,
  KeepRemote/Merged **local** pull body wire (`collect_resolved_local_pull_mutations` + host store
  expected-revision apply + `advance_baseline_after_local_pull`),
  `baseline_must_hold_for_path` in cycle baseline commit, tombstone-first user delete,
  delete-vs-edit planning, offline revival fence, identity-reset control tree, and secret-free
  diagnostics. Markdown merge validation uses `lomo-workspace` parser + `ResourceBudget`.
- Boundary effect: dark host business rules; P5-09 may conversion-link `lomo-native` free functions
  without production DI. Dual-stack ban unchanged. User Markdown/media still mutate only via
  `lomo-store` expected-revision ports. Status alone must not pretend local/baseline apply.
- Exception: none for dual production sync DI. Narrow host crash-at-transition matrix GREEN (Wave-6);
  full process-death graph, Git dual-parent merge-commit apply, provider smoke, and arm64 remain
  residual / `pending_env`.

## Architecture Impact (P5-09 dark BoltFFI sync conversion surface, 2026-07-23)

- Owner: `lomo-native` for conversion-only dark free-function DTOs/exports (`sync_list_conflicts`,
  `sync_resolve_conflicts`, `sync_inspect_cycle_plan`, secret lease issue/probe/revoke, retry
  disposition mapping). Business
  rules remain in `lomo-sync`; secret material remains in `lomo-core` ephemeral vault (process-local
  lease edge for host round-trips only). Kotlin dark owners under `data/engine/sync` + `data/worker`
  map DTOs / disposition / lease orchestration only.
- Boundary effect: `lomo-native` may depend on `lomo-sync` for conversion after P5-09 evidence is
  non-OPEN. Architecture tests fail closed on `lomo-git` native deps, dual-stack flags
  (`use_rust_sync`), and native re-implementation of planner entrypoints. Production Kotlin DI /
  registry / navigation / WorkManager must not bind these free-functions or dark adapters until
  P5-13. Sync Center host dark shell is P5-10; production nav remains P5-13. Remote token values
  never appear on the list wire (presence only).
- Exception: none for dual production sync DI. Arm64 device-smoke and six-provider smokes remain
  `pending_env` / OPEN.

## Architecture Impact (Wave-6 host residual close — worker body + narrow crash matrix, 2026-07-24)

- Owner: `data/worker` for unregistered `RustSyncWorker` `doWork` + `RustSyncWorkExecutor` port +
  `RustSyncWorkRequest` input facts; `lomo-sync` for narrow host crash-at-transition recoverability
  contracts (artifacts-before-session-head, resolve-write revive + stale fence, tombstone-before-
  baseline re-issue, corrupt mid-transition not clean-slate). Business plan/apply remains Rust.
- Boundary effect: host residual closes hollow CoroutineWorker honesty and narrows P5-08 crash
  residual; production `workerOf(::RustSyncWorker)`, shared scheduler enqueue, full process-death
  graph, Git dual-parent merge-commit apply, and P5-11 differential/scale/APK remain OPEN.
  Dual-stack ban unchanged (`SyncDataModule` still wires only Kotlin Git/WebDAV/S3 workers).
- Exception: none for dual production sync DI. Arm64 / six-provider / APK gate remain
  `pending_env` / OPEN.

## Architecture Impact (Wave-7 host residual close — dark work executor impl, 2026-07-24)

- Owner: `data/worker` for unregistered production-shaped `RemoteSyncRustWorkExecutor` implementing
  `RustSyncWorkExecutor` over dark `RemoteSyncRepository` (opaque lease probe + bounded conflict-list
  readiness; disposition map with no fixed three-retry). Wire-ready for `RustSyncWorker` constructor
  injection. Business plan/apply/publish remains Rust (not re-implemented in Kotlin).
- Boundary effect: closes hollow fun-interface-only residual for the work unit; production
  `workerOf(::RustSyncWorker)`, shared scheduler enqueue, Koin binding of dark types, full
  process-death crash graph, Git dual-parent merge-commit apply, and P5-11 differential/scale/APK
  remain OPEN. Dual-stack ban unchanged (`SyncDataModule` still wires only Kotlin Git/WebDAV/S3
  workers; no `use_rust_sync`).
- Exception: none for dual production sync DI. Arm64 / six-provider / APK gate remain
  `pending_env` / OPEN. Full native plan/apply cycle on host executor remains OPEN (conversion
  readiness surface only until P5-13 / later package).

## Architecture Impact (Wave-8 host residual close — cycle free-function + composition, 2026-07-24)

- Owner: `lomo-sync` for sole coarse plan/readiness entry `inspect_sync_cycle_plan` /
  `SyncCyclePlanSummary` (plan-only owner cycle against empty hermetic ports; no publish/apply).
  `lomo-native` conversion free-function `sync_inspect_cycle_plan` maps DTO only (no
  `plan_intents` / `run_sync_cycle` re-implementation in native sources). Dark Kotlin
  `RemoteSyncRepository.inspectCyclePlan` + `RemoteSyncRustWorkExecutor` call that surface;
  composition FunSpec proves `RustSyncWorker` + real executor + fake repo/secrets (lease → probe →
  inspect → revoke → disposition→WM) while unregistered.
- Boundary effect: deepens dark cutover-prep beyond listConflicts readiness without inventing a
  Kotlin planner; production `workerOf(::RustSyncWorker)`, shared scheduler enqueue, Koin bind of
  dark types, full provider plan/apply/publish on host executor, process-death crash graph, Git
  dual-parent merge-commit apply, and P5-11 scale/APK remain OPEN. Dual-stack ban unchanged
  (`SyncDataModule` still wires only Kotlin Git/WebDAV/S3 workers; no `use_rust_sync`).
- Exception: none for dual production sync DI. Arm64 / six-provider / APK gate remain
  `pending_env` / OPEN. Full remote plan/apply/publish on host executor remains OPEN (inspect is
  conversion/readiness only).

## Architecture Impact (Wave-9 host residual close — with-ports cycle + SB fixtures + crash, 2026-07-24)

- Owner: `lomo-sync` for host residual cycle entry `inspect_sync_cycle_plan_with_ports` (plan and
  optional apply against real local/remote ports under hermetic fakes) + disposition derived from
  owner outcomes (`after_user_action` on open conflict; `transient` on precondition/verify failure;
  no fixed three-retry). Empty-port `inspect_sync_cycle_plan` remains the sole BoltFFI conversion
  surface. P5-11 host residual deepen locks language-agnostic safe-behavior fixtures SB-01..SB-10 on
  owner surfaces (`plan_intents` / `apply_with_verify` / fence / diagnostics / control-tree reset).
  Crash recoverability expands host hermetic transitions only (`conflict_recovery_contract` **39**).
- Boundary effect: conversion FFI and dark Kotlin adapters stay empty-port inspect; with-ports is
  host residual owner deepen, not production DI. Dual-stack ban unchanged (`SyncDataModule` still
  wires only Kotlin Git/WebDAV/S3 workers; no `workerOf(::RustSyncWorker)`; no `use_rust_sync`).
  Production full apply, full multi-process crash graph, Git dual-parent merge-commit apply,
  formal APK×1.15, arm64, and six-provider remain OPEN / `pending_env`. (P5-11 scale host streaming
  closed in Wave-10.)
- Exception: none for dual production sync DI. Formal APK hard gate must not be claimed from host
  four-ABI SO sum observation alone.

## Architecture Impact (Wave-10 host residual close — P5-11 scale + P5-12 takeover start, 2026-07-24)

- Owner: `lomo-sync` for streaming multi-page planner `plan_intents_streaming` /
  `StreamingPlanOutcome` (page ≤512, path-key working set ≤100k, intent page splits, fail-closed
  oversize/duplicate; never full multi-page remote payload materialize) and product-shaped
  FirstTakeover / Migration session class (`SessionKind::Migration` +
  `may_emit_user_file_delete` / `is_migration_or_takeover_class`). Host contracts
  `scale_streaming_contract` and `takeover_matrix_contract` lock budgets and takeover rules against
  hermetic fakes + `lomo-store` local snapshot ports. `lomo-native` conversion only maps the new
  `SessionKind::Migration` string; empty-port inspect remains the sole BoltFFI cycle surface.
- Boundary effect: scale and takeover host residuals close without production DI, provider smoke,
  or APK measurement claims. Dual-stack ban unchanged (`SyncDataModule` still wires only Kotlin
  Git/WebDAV/S3 workers; no `workerOf(::RustSyncWorker)`; no `use_rust_sync`). Real provider
  takeover, formal APK×1.15 / ceiling, arm64, six-provider, and P5-13 cutover remain OPEN /
  `pending_env`.
- Exception: none for dual production sync DI. Do not invent formal APK GREEN from host SO sum or
  claim real-provider takeover GREEN from hermetic store/fake ports alone.

## Architecture Impact (P5-10 dark Sync Center Compose shell, 2026-07-23)

- Owner: `domain` for Sync Center presentation models + `RemoteSyncCenterRepository` (list/resolve +
  markdown/binary detail ports); `data` for dark unregistered
  `RemoteSyncCenterRepositoryAdapter` + artifact body load; `app` feature `synccenter` for pure
  reducer/ViewModel/Compose shell (not DI/nav registered). Wave-5 live path: ViewModel on select
  calls domain detail ports; state carries facts; Compose prefers state facts over digest-only
  helpers; binary never invents text preview.
- Boundary effect: independent Sync Center UI surface is host-testable and dark-only. Production
  Settings remains Kotlin provider coordinators until P5-13; no dual-wire to Rust engines. App
  compile surface stays free of `com.lomo.data.*`. Dual-stack ban unchanged.
- Exception: none for dual production sync DI. Device a11y/screenshot, Settings entry summary
  dual-wire, and arm64/provider remain OPEN / `pending_env`. Production nav/DI remains P5-13.

## Architecture Impact (Wave-11 host residual deepen — P5-12 takeover + P5-11 streaming cycle, 2026-07-24)

- Owner: `lomo-sync` for migration-class session entry symmetry (`migration_preflight` /
  `first_takeover_preflight` + hard `ensure_absent==0` post-condition +
  `reject_if_migration_class_emitted_delete` inject surface), durable fence revive re-open under
  hermetic files, and residual streaming cycle entry `run_sync_cycle_streaming` consuming
  `RemoteSyncPort::list_remote_pages` + `plan_intents_streaming` (page ≤512 retained; intermediate
  intent ceiling; first-page apply optional; never multi-page single-shot materialize). Host contracts
  `takeover_matrix_contract` **16** and `scale_streaming_contract` **12**. Provider adapters keep
  default one-page `list_remote_pages` fallback from `list_remote` (WebDAV/S3/Git unchanged).
- Boundary effect: Package A/B host deepen without production DI, provider smoke, or APK measurement
  claims. Dual-stack ban unchanged (`SyncDataModule` still wires only Kotlin Git/WebDAV/S3 workers;
  no `workerOf(::RustSyncWorker)`; no `use_rust_sync`). BoltFFI conversion remains empty-port inspect;
  residual streaming cycle is host hermetic only. Real provider takeover, formal APK×1.15 / ceiling,
  arm64, six-provider, and P5-13 cutover remain OPEN / `pending_env`.
- Exception: none for dual production sync DI. Do not invent formal APK GREEN or real-provider
  takeover GREEN from hermetic store/fake ports alone. Do not raise single-shot `RemoteSnapshot`
  page ceiling above 512.

## Architecture Impact (Wave-12 host residual — P5-13 cutover prep + S3 multi-page + streaming apply, 2026-07-24)

- Owner: fixtures baseline for **PREP_ONLY** P5-13 cutover inventory
  (`fixtures/baseline/stage5-p5-13-cutover-prep-inventory.v1.md`) naming production
  `SyncDataModule` / `workerOf` / scheduler / Settings-nav dual-wire / Kotlin engine tails to replace
  at cutover, dark Rust readiness, dual-stack ban checklist, and inheritance env gates still OPEN.
  `lomo-sync` S3 adapter owns `list_remote_pages` multi-page residual (pages ≤512; single-shot
  `list_remote` still ≤512 Incomplete when truncated; never raise `RemoteSnapshot` ceiling).
  Streaming cycle `run_sync_cycle_streaming` applies **all** intent pages in order with
  verify-before-baseline (`pages_applied`; mid-stream verify failure stops further publish).
  Architecture tests fail closed on missing prep inventory and premature production wire
  (`stage_five_cutover_prep_inventory_must_not_authorize_premature_production_wire`).
- Boundary effect: host-closeable cutover **prep only** — no production DI flip, no
  `workerOf(::RustSyncWorker)`, no Settings dual-wire, no Sync Center nav registration. Dual-stack
  ban unchanged. S3 multi-page + multi-page apply are dark host hermetic residuals only (not
  production scheduler / BoltFFI production path). WebDAV multi-page override deferred. P5-13 /
  P5-14 / arm64 / six-provider / APK remain OPEN / `pending_env`.
- Exception: none for dual production sync DI. Prep inventory must not be cited as P5-13 GREEN.
  Do not invent formal APK / provider / arm64 GREEN.

## Architecture Impact (Wave-13 host residual — WebDAV multi-page + streaming conflict first-page, 2026-07-24)

- Owner: `lomo-sync` WebDAV adapter owns `list_remote_pages` multi-page residual (mirror S3: pages
  ≤512; single-shot `list_remote` still ≤512 Incomplete when truncated; never raise
  `RemoteSnapshot` ceiling). Streaming cycle `run_sync_cycle_streaming` fail-closes when
  `OpenConflict` appears outside the first intent page
  (`streaming_open_conflict_outside_first_page`) rather than full-materializing multi-page conflict
  views or silently dropping later conflicts. Hermetic `FakeRemotePort` publish/verify are
  page-scoped (canned multi-path fixtures filter to the current batch / requested paths only).
- Boundary effect: host hermetic residual deepen only — dark adapters remain unregistered; dual-stack
  ban unchanged (`SyncDataModule` still wires only Kotlin Git/WebDAV/S3 workers; no
  `workerOf(::RustSyncWorker)`; no `use_rust_sync`). **Wave-15:** multi-page conflict fail-closed is
  permanent product law (not residual design OPEN). P5-13 / P5-14 / arm64 / six-provider / formal APK
  remain OPEN / `pending_env`.
- Exception: none for dual production sync DI. Do not invent formal APK / provider / arm64 GREEN.
  Do not raise single-shot `RemoteSnapshot` past 512.

## Architecture Impact (Wave-14 host residual — durable multipart + dual-parent + crash deepen, 2026-07-24)

- Owner: `lomo-sync` S3 adapter owns durable on-disk multipart sessions under
  `.lomo/sync/v1/multipart/` (LSYN framed; process-death resume of confirmed parts; corrupt record
  fail-closed). `lomo-git` owns dual-parent merge-commit parent selection after conflict resolve
  (`select_publish_parents`: remote tip first for CAS; local HEAD second when merge-base proven;
  unproven merge-base still blocks). `lomo-sync` conflict/durable crash matrix deepens host
  recoverability for publish-before-baseline, conflict-session temp-before-rename, and
  local-pull-before-baseline-advance transitions (`conflict_recovery_contract` **42**).
- Boundary effect: host hermetic residual close only — dark adapters remain unregistered; dual-stack
  ban unchanged (`SyncDataModule` still wires only Kotlin Git/WebDAV/S3 workers; no
  `workerOf(::RustSyncWorker)`; no `use_rust_sync`). Real R2/S3 / GitHub-GitLab HTTPS smokes and
  full multi-process OS-kill crash graph remain OPEN / `pending_env`. P5-13 / P5-14 / arm64 /
  six-provider / formal APK remain OPEN / `pending_env`.
- Exception: none for dual production sync DI. Do not invent formal APK / provider / arm64 GREEN.
  Do not claim full OS multi-process death graph from host suite alone.

## Architecture Impact (Wave-15 absolute host residual dry — product-law freezes, 2026-07-24)

- Owner: `lomo-sync` freezes three Stage-5 host product laws that adversarial audit still listed as
  host-closeable residual OPEN: (1) S3 addressing is path-style only for custom endpoints
  (`S3AddressingStyle::PathStyle` and `Auto` share path-style object/list URL construction;
  virtual-hosted is real AWS smoke / `pending_env` only); (2) rclone host-proven surface is fixture
  standard/base32/dir + data seal (`fixtures/remote/rclone-crypt-vectors.json`); non-fixture modes
  remain typed code paths for cutover parity and are not residual OPEN for full CLI goldens;
  (3) streaming multi-page `OpenConflict` outside the first intent page permanently fails closed
  (`streaming_open_conflict_outside_first_page`) — full multi-page conflict materialize is forbidden
  product law, not deferred design debt.
- Boundary effect: host residual absolute dry only — no production DI flip, no dual-stack, no inventing
  virtual-hosted transport or full rclone alphabet goldens as Stage-5 host work. Evidence residual
  matrices no longer list the three freezes as OPEN. Dual-stack ban unchanged.
- Exception: none for dual production sync DI. Formal exit remains blocked on P5-13 cutover, arm64
  device-smoke, six-provider smoke, formal APK×1.15 / ceiling measurement, and `just check` /
  `just ci` formal-exit re-claim — all env/formal walls, not host residual OPEN.



## Architecture Impact (P5-13 production cutover, 2026-07-24)

- **Owner:** `lomo-sync` is the sole production remote-sync business owner (snapshot/plan/apply/verify/baseline/tombstone/conflict/recovery). `lomo-git` remains the sole `git2` adapter, composed at the conversion edge (`lomo-native` → `lomo-git`; `lomo-sync` does not depend on `lomo-git`).
- **Boundary effect:** `lomo-native` conversion free-functions only (`sync_*` conflict/lease/cycle inspect). Production Kotlin DI binds `BoltFfiRemoteSyncRepository` / `RemoteSyncCenterRepositoryAdapter` / `RustSyncWorker` / `RustSyncScheduler` once. Settings retain DataStore config + Keystore credentials via thin facades that enqueue Rust work. **Conflict presentation:** original conflict dialog (`SyncConflictViewModel` / `SyncConflictStateViewModel`) is the primary remote-conflict UX; it resolves only through `RemoteSyncConflictDialogUseCase` → `RemoteSyncCenterRepository` (expected-revision). Sync Center remains config/session + secondary conflict surface (list/detail). Dual Kotlin engine resolve paths are forbidden.
- **Tail deletion:** Kotlin Git/WebDAV/S3 engines, provider workers/schedulers, sync-v1 wire planner consumers, JGit/AWS Kotlin SDK/BouncyCastle sync-only deps, force/reset business paths, dual-stack flags / dual engine conflict authority, and crate `lomo-sync-core` removed from the production graph. Original dialog ViewModels are **not** deleted — they are Rust-backed presentation, not a second business owner.
- **Exception:** Independent Sync Inbox product capability retained (file-backed pending review + `InboxUnifiedSyncProvider`). Inheritance env gates (arm64, six-provider, APK×1.15, formal Stage-5 exit) remain OPEN / `pending_env` for P5-14.
- **Production rule:** After P5-13, dual-stack sync DI / `use_rust_sync` flags / residual Kotlin planner or engine authority are forbidden. A single conflict dialog over the Rust kernel is allowed and required for the restored UX.

## Architecture Impact (Git-in-native composition, 2026-07-25)

- **Owner:** `lomo-git` sole `git2` adapter; `lomo-sync` sole planner; `lomo-native` conversion-only
  composition edge for Git.
- **Boundary effect:** `lomo-native` depends on `lomo-git` and constructs
  `connect_workspace_git` (app-private bare mirror under `.lomo/sync/v1/git-mirror`) inside
  `sync_run_cycle` when `backend_kind=git`, then calls
  `lomo_sync::run_composed_sync_cycle_with_remote_port`. `lomo-sync` must **not** depend on
  `lomo-git` (crate cycle ban: `lomo-git` already depends on `lomo-sync` for `RemoteSyncPort`).
  Architecture tests require post-cutover native→git dep and forbid production `git2` deps outside
  `lomo-git` / tooling feasibility. Wire field reuse for Git: endpoint=remote, username=HTTPS user,
  bucket=branch, prefix=author name, region=author email; secret lease=token.
- **Tail deletion:** `sync_ffi_git_backend_not_composed` fail-closed theater removed; Git enqueue is
  a real composed cycle path (hermetic bare-repo host contracts GREEN). Dual-stack still forbidden.
- **Exception:** Real GitHub/GitLab HTTPS smoke remains OPEN / `pending_env`. Formal APK×1.15 /
  six-provider remain P5-14 env walls. Host formal gates (`just check` / `just ci` / arm64 device-smoke)
  closed under the P5-14 wall (2026-07-25).

## Architecture Impact (P5-14 formal-exit wall + evidence hygiene, 2026-07-25)

- **Owner:** Stage-5 formal-exit wall evidence (`fixtures/baseline/STAGE5-EVIDENCE.md`,
  `fixtures/baseline/stage5-native-size-ceiling.v1.json`) and shipping four-ABI gate in
  `rust/xtask/src/native.rs` (`MAX_FOUR_ABI_BYTES = 46_530_532`).
- **Boundary effect:** Host formal wall is audit-stable: `just check` / `just ci` / API≥26 arm64
  device-smoke GREEN; release-android four-ABI SO sum **42300484** ≤ ceiling **46530532**; CI debug
  universal APK under Stage-0×1.15 hard gate observed only. Dual-stack ban and unique Rust sync owner
  hold. Evidence hygiene: ceiling typo scrub (`46_530_532` only — never stale `56_901_781`); signed
  shipping residual wording is signing secrets/password + formal release measure under hard gate
  (keystore file may exist at `release.keystore` / `app/keystore.properties` — not “path missing”
  alone). Absolute Stage-5 full GREEN remains blocked only by env residuals: six real provider smokes
  (no credentials; no invent) + signed shipping APK formal measure (do not invent from keystore
  presence).
- **Exception:** six-provider smoke and signed shipping APK×1.15 formal measure stay OPEN /
  `pending_env`. Host SO sum and CI debug APK are not signed shipping claims.

## Architecture Impact (Stage 7 Kotlin shell convergence, 2026-08-01)

- **Owner:** Rust is the sole production authority for Markdown/reminder/store/media/archive/sync/LAN
  rules. Domain owns only language-neutral application contracts and the typed recovery policy;
  `ManagedEngineSession` is the single lifecycle and recovery executor over the Rust engine.
- **Boundary effect:** Kotlin `data` is restricted to engine conversion plus Android SAF, Keystore,
  WorkManager, notification, NSD/network, media codec, preferences and update adapters. `app` and
  `ui-components` remain presentation-only and cannot import generated native bindings. Recovery
  opens a restricted same-workspace candidate, rebuilds only the derived SQLite index, closes it,
  and promotes only a newly opened Ready candidate.
- **Tail deletion:** no Room/JGit/AWS Kotlin/Kotlin Markdown parser/remote-sync planner production
  owner, differential runtime, migration flag, compatibility overload, raw diagnostic export or
  stale generated-profile rule may survive final convergence.
- **Exception:** Android platform operations and the explicitly non-migrated preferences/update
  loop remain Kotlin-owned. API >= 26 arm64, real-provider, signed APK, capacity, performance and
  soak claims remain `pending_env` until backed by recorded runs.
