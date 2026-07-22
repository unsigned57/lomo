# Stage-4 implementation evidence

> Status: **host media/archive cutover PASS with residual honesty notes (FIXER A1/A4, 2026-07-22).**
> Prior Audit #10 formal exit claim is **superseded for D4 residual**: production import had been promoting
> immediately with a random UUID (dual-authority promote path). That residual is **FIXED** on host
> (stage-only import + `pendingPromotes` on memo save). **API ≥ 26 arm64 device-smoke remains
> OPEN/`pending_env` in this session** — do not re-claim full P4-11 formal exit without a fresh
> arm64 run. Stage-3 P3-10 production store cutover remains GREEN (entry prerequisite).
>
> **Honesty (this fixer):** host Kotlin tests for D4/D8/A4 re-run GREEN. Full `just ci` and
> arm64 `just device-smoke` **not** re-run here → keep formal exit OPEN until re-audit + env.
>

## First principles (P4 scaffolding)

1. **Invariant:** when production is switched, one media authority owns digest/mime/path/stage/
   reference/orphan/commit and one archive authority owns archive v2 export/inspect/import/activate.
   Markdown/media/`.lomo` remain durable facts. Dark-build must not create a second production media
   or archive authority beside Kotlin until atomic Wave A/B cutover.
2. **Axiom violation:** without versioned STAGE4 contract/evidence and fail-closed architecture
   gates, implementers can mis-claim GREEN, ship an empty marker as `lomo-media`, dual-wire Kotlin +
   Rust media via feature flags, pass full media bytes over FFI, or cut over without arm64.
3. **Rebuild from truth:** versioned contract + evidence + architecture scaffolding that fails when
   STAGE4 files, the real `lomo-media` owner, production dual-stack media/archive wiring, or full-byte
   media FFI surfaces are wrong; production cutover remains two atomic waves (P4-10A/B) with Kotlin
   tail deletion only then.
4. **Edge enforcement:** missing STAGE4 files / missing owner crate / production dual-stack media DI /
   full media-byte FFI / stage-4 GREEN without stage-3 store cutover → architecture fail.
5. **Tail deletion:** no empty marker crate, no production dual-stack wiring, no dual-write feature
   flags, no fictional GREEN. Kotlin media/archive tail deletion is **not** performed in P4-00.

## Package status overview

| Package | Status | Notes |
| --- | --- | --- |
| P4-00 | **PASS** (host architecture + contract) | Contract, evidence, `stage_four_*` arch tests |
| P4-01 | **PASS** (host) | `lomo-media` identity + path |
| P4-02 | **PASS** (host, audit #2 closed) | stage DirectPath/StagedTemp + fixed 16 KiB stream buffer property; no silent stage drops without contract |
| P4-03 | **PASS** (host, audit #2 closed) | refcount + orphan/media-trash; durable delete-intent journal before permanent delete; missing attachment paths fail closed at memo promote |
| P4-04 | **PASS residual closed (host FIXER B1)** | store nine-step + import/voice stage-only; memo save fills `pendingPromotes` same operation-id; blank promote opId rejected; apply-fail re-stages |
| P4-05 | **PASS residual closed (host FIXER B1)** | recording allocate/finalize → registry; markdown dest = suggestedFinalRelativePath; mid-record death = unpromoted stage discard |
| P4-06 | **PASS** (host) | archive export + ArchiveManifestV2 |
| P4-07 | **PASS** (host, audit #3 hardened) | inspect/import fail-closed: zip-slip, dup (`archive_duplicate_entry` via CD preflight), bomb (`archive_compression_bomb` / size only), checksum mismatch, unlisted entry; live-root immutability on each failure |
| P4-08 | **PASS** (host, audit #4) | activate atomicity; mid-swap restore; **exact** `archive_activate_restore_failed` via `archive_activate_with_rename` injection; **import→activate→rebuild** projects memo + digests on activated live (`archive_import_activate_rebuild`) |
| P4-09 | **PASS** (host dark, audit #4) | BoltFFI path-only media stage/finalize/promote/manifest/orphan + archive export/inspect/import/activate + import_activate_rebuild; `StoreMemoCommand.pending_promotes` wired; **not** in production Kotlin DI |
| P4-10A | **GREEN residual closed (host A1/A4/A5)** | MediaEdge stage-only; StagedTemp content:// bounds; MIME presentation table consolidated |
| P4-10B | **GREEN** (host production cutover) | ArchivePort DI + WorkspaceArchiveEdgeRepository; MigrationArchive* ZIP tails deleted; settings independent |
| P4-11 | **OPEN / pending_env** (this session) | arm64 device-smoke not re-run after D4 fix; formal exit not re-claimed |

## P4-00 stage entry, contract, architecture scaffolding

### First principles

1. **Invariant:** stage 4 has a versioned contract/evidence pair and architecture gates that require a
   real `lomo-media` owner (non-empty sources + external behavior tests), not a hollow marker.
2. **Axiom violation:** architecture suite had no `stage_four_*` gates; no STAGE4 fixtures; no
   `rust/media` workspace member.
3. **Rebuild from truth:** STAGE4-CONTRACT/EVIDENCE + architecture tests that fail closed on missing
   artifacts/owner + dark-build dual-stack prohibition + no full-byte media FFI claim.
4. **Edge enforcement:** missing STAGE4 files, missing/empty owner, wrong package identity, tooling
   deps on owner, production dual-stack media wiring, or stage-4 GREEN without stage-3 cutover fail
   architecture tests.
5. **Tail deletion:** no empty marker, no production DI wiring of media, no Kotlin media deletion at
   entry.

### RED / GREEN

- RED command:
  `cd rust && cargo test -p lomo-architecture-tests --locked -- stage_four`
- Observed RED (pre-STAGE4 evidence lock / before `RED command` literal): architecture gate
  `stage_four_contract_and_evidence_files_exist` fails when evidence lacks required lock text
  (`RED command`, `GREEN command`, package anchors).
- GREEN command:
  `cd rust && cargo test -p lomo-architecture-tests --locked -- stage_four`
- GREEN result (2026-07-21, audit #4): **6 passed / 0 failed** (`stage_four_*` suite), including
  expanded `stage_four_forbids_full_media_byte_public_api` requiring `media_ffi` path-only surface
  symbols when the module exists.

## P4-01 content identity and path

### First principles

1. **Invariant:** content identity is streaming sha256 + size + magic mime; extension is hint only;
   conflict rejects; paths use workspace relative-path law; human filenames stay.
2. **Axiom violation:** Kotlin used filename-as-identity without digest/dedup.
3. **Rebuild from truth:** `ContentDigest` / `MediaMime` / `MediaRelativePath` in `lomo-media`.
4. **Edge enforcement:** invalid digest wire form, magic/ext conflict, invalid path → validation.
5. **Tail deletion:** no hash filenames, no Kotlin identity authority after cutover (later wave).

### RED / GREEN

- RED command (illustrative host absence before identity owner): missing `lomo-media` fails
  `stage_four_requires_lomo_media_owner`.
- GREEN command: `cd rust && cargo test -p lomo-media --locked` (identity_path_contract among suite).

## P4-02 stage lifecycle (memory-bound stream)

### First principles

1. **Invariant:** stage is pending only; digest/copy uses fixed `DIGEST_STREAM_CHUNK_BYTES` (16 KiB),
   never whole-file `Vec` for the stream buffer contract.
2. **Axiom violation:** silent `drop(remove_file)` on stage cleanup hid storage failures.
3. **Rebuild from truth:** StagedTemp consume and promote stage remove return storage errors; corrupt
   stage cleanup on digest mismatch is documented `// behavior-contract: silent-result-ok`.
4. **Edge enforcement:** unknown magic / digest mismatch fail closed; unpromoted stage never committed.
5. **Tail deletion:** no unbounded buffer path.

### RED / GREEN

- Tests: `stage_contract` — `stream_buffer_is_bounded_chunk` (exactly 16 KiB),
  `large_stream_uses_fixed_chunk_not_whole_file_vec` (3× chunk payload).
- GREEN command: `cargo test -p lomo-media --locked`.

## P4-03 reference / orphan / delete-intent

### First principles

1. **Invariant:** refcount spans current ∪ trash ∪ history; permanent delete only after recovery
   window with durable delete-intent journaled first.
2. **Axiom violation:** intent only in-memory in sweep result (not durable); body could reference
   missing files before P4-04 fail-closed.
3. **Rebuild from truth:** `.lomo-media-delete-intents/` journal write before `remove_file`; store
   promote refuses body attachment paths without committed files.
4. **Edge enforcement:** missing expected paths at promote → `attachment_file_missing_after_promote`.
5. **Tail deletion:** no silent permanent delete without journal.

### RED / GREEN

- `reference_orphan_contract` asserts delete-intent dir + journal file after expiry sweep.
- Store: body with missing `media/...` fails closed (transaction_contract).
- **Boundary note:** orphan sweep still takes model-fed `committed`/`refs` maps (host API); store-fed
  wire of every workspace attachment is not claimed as a separate production sweep job in this pass
  (promote fail-closed is the store wire for refs-without-files).

## P4-04 commit promote in nine-step memo transaction

### First principles

1. **Invariant:** promote + body + SQLite `attachment_ref` share one operation-id; body never
   references a missing attachment after recovery; crash mid-promote completes once or leaves
   uncommitted stage — never dangling body refs.
2. **Axiom violation:** `promote_staged` lived only in `lomo-media` with caller discipline; store
   nine-step wrote Markdown/`attachment_ref` without promote.
3. **Rebuild from truth:** `MemoCommand.pending_promotes` + durable `OperationIntent.pending_promotes`;
   `promote_pending_media` runs while status is `HistoryAppended` **before** `commit_files`; recovery
   re-enters promote (idempotent when final digest already present).
4. **Edge enforcement:** `attachment_file_missing_after_promote`; operation-id mismatch reject;
   `CrashPoint::AfterPromoteBeforeFiles`.
5. **Tail deletion:** no dual path that records refs without promote Ok.

### Crash-point matrix (media-aware)

| Point | Observable |
| --- | --- |
| BeforeMove (media unit) | stage remains; final absent |
| AfterMoveBeforeRecord (media unit) | final may exist; caller must not write body/ref on Err |
| AfterPromoteBeforeFiles (store) | final may exist; Markdown body absent until recovery |
| Recovery same operation-id | complete-once; idempotent replay after Committed |

### RED / GREEN

- RED command before integrate: store create with attachment path and no file would previously succeed projection.
- GREEN command (2026-07-21):
  - `cargo test -p lomo-store --test transaction_contract --locked` — includes
    `promote_integrated_under_same_operation_id`,
    `promote_crash_after_promote_before_files_recovers_complete_once`,
    `body_attachment_without_file_fails_closed` (**11 passed**).
  - `cargo test -p lomo-media --test commit_recording_contract --locked` — AfterMoveBeforeRecord (**5 passed** suite file).

## P4-05 recording

### First principles

1. **Invariant:** allocate under stage dir; finalize → `MediaStaged`; mid-record death leaves only
   unpromoted stage (never committed media/).
2. **Rebuild from truth:** `allocate_recording_target` / `finalize_recording` + discard contract tests.
3. **Edge enforcement:** invalid extension validation; unpromoted paths not under `media/`.

### RED / GREEN

- `commit_recording_contract`: `recording_allocate_and_finalize`,
  `mid_record_death_leaves_unpromoted_stage_discardable`.

## P4-06..P4-08 archive v2

### First principles

1. **Invariant:** only `ArchiveManifestV2` plaintext ZIP; staging workspace; fail closed on malicious
   zip; atomic activate; never mutate live workspace on failure; mid-activate after live→backup must
   restore previous generation to live or report restore failure (never silent empty live success).
2. **Axiom violation:** tests covered only zip-slip + missing manifest + happy path (false GREEN);
   dup detection only after `zip` crate last-wins collapse; silent `drop(rename)` on restore;
   import stopped at staging without rebuild projection.
3. **Rebuild from truth:** expanded `archive_contract` matrix; CD preflight for
   `archive_duplicate_entry`; bomb codes limited to ratio/size; `archive_activate` /
   `archive_activate_with_rename` surfaces restore errors as `archive_activate_restore_failed`;
   `archive_import_activate_rebuild` for host generation switch; `.lomo/operations` archiveable as
   `LomoState` so export of real store workspaces is not false-`archive_unknown_entry_kind`.
4. **Edge enforcement:** unsupported version / zip-slip / dup / bomb / checksum / unlisted → reject;
   mid-swap EXDEV restore path; injected restore-fail path.
5. **Tail deletion:** Kotlin MigrationArchive* at Wave B only.

### RED / GREEN

- RED command (pre restore-fail / import-rebuild):
  `cd rust && cargo test -p lomo-store --test archive_contract --locked -- activate_restore_failure`
  / `import_activate_rebuild` absent or failing.
- GREEN command:
  `cd rust && cargo test -p lomo-store --test archive_contract --locked`
- GREEN result (2026-07-21, audit #4): **12 passed / 0 failed** including
  `activate_mid_swap_failure_restores_previous_generation_to_live`,
  `activate_restore_failure_returns_archive_activate_restore_failed` (exact code),
  `import_activate_rebuild_projects_store_generation` (memo id projected after activate+rebuild).

## P4-09 BoltFFI dark media/archive facade

### First principles

1. **Invariant:** unique BoltFFI facade exposes path-only media/archive commands; no full media bytes
   over FFI; production Kotlin DI remains Kotlin media/archive until Wave cutover.
2. **Axiom violation:** store FFI forced `pending_promotes: Vec::new()`; no media/archive native
   methods.
3. **Rebuild from truth:** `rust/native/src/media_ffi.rs` conversion + `LomoEngine` path-only methods;
   `StoreMemoCommand.pending_promotes`; architecture gate requires path-only symbols and forbids
   byte surfaces in `media_ffi` when present.
4. **Edge enforcement:** architecture dual-stack + no-byte gates; host contract tests.
5. **Tail deletion:** no production DI registration this package.

### RED / GREEN

- RED (pre facade): missing `media_ffi` / tests fail.
- GREEN command:
  - `cargo test -p lomo-native --test media_archive_ffi_contract --locked` — **6 passed**
  - `cargo test -p lomo-native --test store_ffi_contract --locked` — **8 passed** (pending_promotes field)
  - `cargo clippy -p lomo-native --all-targets --locked -- -D warnings` — exit 0
- Kotlin production adapters: **not** added / **not** registered in production DI (dark).

## P4-10..P4-11

### P4-10A Wave A (fixer #6)

1. **Invariant:** sole media authority is `lomo-media` via path-only `MediaPort`; sync edge journals only committed paths (D8).
2. **Axiom violation:** production still owned identity/orphan/sync-record in Kotlin `MediaRepositoryImpl`.
3. **Rebuild from truth:** `MediaEdgeRepository` stages/promotes through Rust; `MediaSyncEdgeAdapter` only after promote.
4. **Edge enforcement:** arch test `stage_four_production_media_owner_is_unique_after_cutover` fails if forbidden tails return.
5. **Tail deletion:** `MediaRepositoryImpl`, `AttachmentOrphanCleaner`, ImageLocationCache*, `DiscardMemoDraftAttachmentsUseCase`.

### P4-10B Wave B (fixer #6)

1. **Invariant:** sole workspace archive authority is store archive v2 via `ArchivePort`.
2. **Axiom violation:** Kotlin ZIP MigrationArchive* dual owner.
3. **Rebuild from truth:** `WorkspaceArchiveEdgeRepository` path-only export/inspect/import-activate-rebuild.
4. **Edge enforcement:** old ZIP rejected at Rust (`unsupported archive version`); settings encryption independent.
5. **Tail deletion:** `MigrationArchiveRepositoryImpl`, StagingWorkspace, DryRunPlanner, Support ZIP helpers.

- P4-11 **OPEN / pending_env** (authoritative overview table, 2026-07-22): prior Audit #10 /
  fixer #12 arm64+ci claim is **superseded** after D4 residual rework (stage-only import + voice
  finalize registry + blank-opId reject). Do **not** treat historical GREEN prose below as current
  formal exit. Product interactive media/archive UI beyond native-smoke remains an explicit
  non-claim residual (strict D11 matrix only).

## Verification log (audit #5 fixer #5, 2026-07-21)

Commands from `rust/` (mandatory host gates after native fix):

- `cargo test -p lomo-media --locked` → GREEN.
- `cargo clippy -p lomo-media --all-targets --locked -- -D warnings` → exit 0.
- `cargo test -p lomo-store --locked` → GREEN (archive **12**, transaction **11**, …).
- `cargo clippy -p lomo-store --all-targets --locked -- -D warnings` → exit 0 (after
  `reject_duplicate_central_directory_names` too-many-lines split).
- `cargo test -p lomo-architecture-tests --locked -- stage_four` → **6 passed / 0 failed**.
- `cargo test -p lomo-native --test media_archive_ffi_contract --locked` → **6 passed**.
- `cargo test -p lomo-native --test store_ffi_contract --locked` → **8 passed**.
- `cargo clippy -p lomo-native --all-targets --locked -- -D warnings` → exit 0.

Host iterative gate:

- `just check` → **complete** (`xtask: check complete`; data android tests 729 passed).

Device:

- `ANDROID_SERIAL=RFCX911Z9PL just device-smoke` → **`device smoke passed`**
  - Target: API **36** abi **arm64-v8a** (SM-S9110 / `RFCX911Z9PL`)
  - Log: `/tmp/p4-fixer5-device-smoke-arm64.log`
  - Also present: `emulator-5554` x86_64 API 36 (not used for this arm64 claim)

## Honesty notes

- **Single authoritative P4-11 status (2026-07-22 re-audit residuals):** **OPEN / pending_env**.
  Host D4/D8/A4/voice-finalize/re-stage fixes may be GREEN on Kotlin unit surface; full formal exit
  still requires fresh `just check` + `just ci` + durable perf×2 + API ≥ 26 arm64 `just device-smoke`
  after those residuals. Historical Audit #10 / fixer #5–#12 arm64 “device smoke passed” lines are
  **archival only** and must not be re-read as current formal exit.
- Do **not** claim full product interactive media/archive UI matrix GREEN from native-smoke alone;
  that residual stays an **explicit non-claim** if D11 is read strictly.
- Arm64 hardware gate is **`pending_env` again** until re-run after D4 residual packages (B1–B5).
- Wave A residual dual magic/basename ownership is **deleted** after fixer #7; Wave B archive DI
  cutover remains as recorded in fixer #6.
- P4-09 PASS is **host facade** (now production-wired via MediaPort/ArchivePort after cutover).
- Silent cleanup contracts remain marked `// behavior-contract: silent-result-ok` only where
  documented (test /dev/shm cleanup; corrupt stage cleanup paths already in media owner).
- SAF without Direct filesystem root fails closed at MediaEdge — intentional path-only FFI boundary.


### Fixer #6 host verification (2026-07-21)

- `cargo test -p lomo-architecture-tests --locked` → **38 passed** (includes
  `stage_four_production_media_owner_is_unique_after_cutover` GREEN after tail deletion).
- `./kotlin test` media port + store + ManagedEngineSession + domain discard + app MemoEditor → GREEN.
- `just check` → **xtask: check complete** (data module 691 tests successful among suite).
- `ANDROID_SERIAL=RFCX911Z9PL just device-smoke` → **device smoke passed** (API 36 arm64-v8a SM-S9110);
  log `/tmp/p4-fixer6-device-smoke-arm64.log`. native-smoke only — **not** product media/archive UI scenarios.
- `just ci` / `just perf` durable double-pass: **not run this pass** → P4-11 formal exit remains OPEN.

### Fixer #7 Wave A residual depth (2026-07-21)

First principles for residuals:

1. **Invariant:** sole media identity authority is Rust (`magic` + digest + suggested final path);
   Kotlin is URI/temp/path edge only.
2. **Axiom violation (audit #6 PARTIAL):** `MediaEdgeRepository` pre-filtered with
   `ImageMagicByteValidator` and invented `media_<digest12>.ext` basenames; share path still used
   `MediaStorageDataSource.saveImage` (Kotlin magic + timestamp basename).
3. **Rebuild from truth:** stage returns `suggested_final_relative_path` from
   `suggest_human_relative_path`; MediaEdge promotes that path; share routes images through
   `MediaRepository.importImage`; `FileMediaStorageDataSourceDelegate.saveImage` fails closed;
   `ImageMagicByteValidator` deleted; D10 digest-set compare golden in
   `d10_manifest_compare_contract`.
4. **Edge enforcement:** arch unique-owner forbids `ImageMagicByteValidator` / `basenameForStaged` /
   `digest.take(` in MediaEdge; requires `suggestedFinalRelativePath`.
5. **Tail deletion:** Kotlin magic validator + digest basename helper + saveImage identity path.

Verification:

- `cargo test -p lomo-media --locked` → GREEN (includes D10 **4 passed**).
- `cargo test -p lomo-store --locked` → GREEN.
- `cargo test -p lomo-native --test media_archive_ffi_contract --locked` → **6 passed**.
- `cargo test -p lomo-architecture-tests --locked -- stage_four` → **6 passed** (unique-owner depth).
- `cargo clippy -p lomo-media -p lomo-store -p lomo-native --all-targets --locked -- -D warnings` → exit 0.
- `just native` → bindings regenerated; four-ABI shipping gate GREEN (**9640204 ≤ 10500000** stage-4 ceiling).
- Kotlin targeted: `BoltFfiMediaPortTest` (2), `FileMediaStorageDataSourceDelegateTest` (4),
  `ShareAttachmentStorageTest` (5), `ShareServiceManagerTest` (1) → GREEN.
- `just check` after residual edits → **xtask: check complete** (data module **688** tests successful;
  ImageMagic validator suite removed).
- `just ci` / durable `just perf` double-pass / product media-archive device scenarios: **not claimed**.
- SAF content import remains Direct-workspace-root path FFI only (fail closed without Direct root) —
  intentional boundary, not product dual-stack.

### Fixer #8 Wave A orphan/delete lifecycle (2026-07-21)

First principles:

1. **Invariant (D6):** permanent committed-media reclaim is refcount → media-trash → recovery window →
   permanent delete with delete-intent journal; host `File.delete` is not permanent authority.
2. **Axiom violation (audit #7 FAIL):** `MediaEdgeRepository.removeImage`/`removeVoiceCapture` still
   permanently deleted `media/$basename` via `File.delete`; `mediaOrphanSweep` was FFI-only plumbing;
   share audio still wrote through `MediaStorageDataSource.createVoiceFile`.
3. **Rebuild from truth:** delete paths journal sync + drop path-cache then call
   `runOrphanSweepAtOperationBoundary` (manifest committed + store imageUrls as refs); Rust
   `list_trash_entries` auto-loads on-disk trash when host list is empty; share audio uses
   `MediaRepository.importImage` stage/promote; refresh uses `queryMediaManifest` path-cache.
4. **Edge enforcement:** architecture unique-owner requires orphan-sweep symbols and rejects
   permanent `File.delete` of committed `media/` paths on MediaEdge.
5. **Tail deletion:** permanent committed `File.delete` branches on MediaEdge; share audio dual path.

Verification (fixer #8 host, 2026-07-21):

- `cargo test -p lomo-media --locked` → GREEN (includes
  `empty_existing_trash_auto_lists_disk_and_expires` + prior orphan contracts).
- `cargo test -p lomo-store --locked` → GREEN.
- `cargo test -p lomo-native --test media_archive_ffi_contract --locked` → **6 passed**.
- `cargo test -p lomo-architecture-tests --locked -- stage_four` → **6 passed** (orphan-sweep +
  no permanent committed File.delete depth).
- `cargo clippy -p lomo-media -p lomo-store -p lomo-native --all-targets --locked -- -D warnings` →
  exit 0.
- Kotlin: `MediaEdgeRepositoryOrphanSweepTest` (4) + `ShareAttachmentStorageTest` (3) → **7 passed**.
- Formal exit / P4-11: still **OPEN** (`just ci`, perf×2, product arm64 scenarios not claimed).

### Fixer #9 Wave A residual D6 history + delete tails (2026-07-21)

First principles:

1. **Invariant (D6):** orphan keep-set is current ∪ trash ∪ **history** attachment digests; permanent
   reclaim is only media-trash after recovery window (delete-intent journal).
2. **Axiom violation (audit #8 PARTIAL):** MediaEdge collected only current/trash `imageUrls`;
   permanent `File.delete` tails remained on Direct/SAF media backends, `LocalMediaSyncStore`, and
   WorkspaceMedia access; arch scan only covered MediaEdge; host tests only asserted port invocation.
3. **Rebuild from truth:** store-owned `list_history_attachment_refs` (+ BoltFFI /
   `StorePort.listHistoryAttachmentRefs`); MediaEdge maps history paths as `source=history`;
   production host delete tails fail-closed with `UnsupportedOperationException` / no-op journal
   (sync local hard-delete retired).
4. **Edge enforcement:** `stage_four_production_media_owner_is_unique_after_cutover` requires
   history-ref symbols and fail-closed delete owners outside MediaEdge.
5. **Tail deletion:** hard-delete helpers on Direct/SAF media backends + WorkspaceMedia delete paths.

Verification (fixer #9 host, 2026-07-21):

- `cargo test -p lomo-store --test history_refs_contract --locked` → **3 passed**.
- `cargo test -p lomo-store --locked` → GREEN.
- `cargo test -p lomo-media --locked` → GREEN (FS trash move / expire contracts).
- `cargo test -p lomo-native --test media_archive_ffi_contract --locked` → **6 passed**.
- `cargo test -p lomo-architecture-tests --locked -- stage_four` → **6 passed** (history + tails).
- `cargo clippy -p lomo-media -p lomo-store -p lomo-native --all-targets --locked -- -D warnings` →
  exit 0.
- `just native` → bindings regenerated with `listHistoryAttachmentRefs`.
- Kotlin: `MediaEdgeRepositoryOrphanSweepTest` → **6 passed** (history + trash-move result).
- P4-10A residual depth: **PARTIAL residual closed for history+tails** on host; formal exit still
  **NO** until P4-11 (`just check`/`just ci`/perf×2/device) claimed.
### Fixer #12 coverage floor + residual re-verify (2026-07-22)

**User decision (binding, 2026-07-22):** Rust llvm-cov fail-under for formal `just ci` / P4-11 is
**≥70%**, not 80%. Prior fixer was killed for grinding coverage toward 80 after this decision.

Code/docs:

- `rust/xtask/src/quality.rs`: `RUST_COVERAGE_MINIMUM` **80 → 70**.
- `quality/README.md`: documents fixed threshold as **70%** (product decision 2026-07-22); raise only
  after measured green, no coverage grind solely to climb an arbitrary higher bar.

Wave A residual re-verify (host, no new grind):

- History retention window: `DEFAULT_HISTORY_MEDIA_RETENTION_REVISIONS = 20` +
  `list_history_attachment_refs_with_retention`; contract tests cover in-window keep /
  out-of-window does not keep / zero retention / per-memo windows
  (`rust/store/tests/history_refs_contract.rs`).
- Sync delete: `LocalMediaSyncStore` journals remote delete; `localChanged=false` when bytes
  unchanged; reclaim via MediaEdge orphan sweep (`LocalMediaSyncStoreTest`,
  `WebDavSyncActionApplierTest` name matches behavior).
- Arch breadth: `assert_wave_a_media_delete_law` scans named delete owners + all `data/src` for
  hard-delete of committed `media/` outside D6 media-trash law.
- Kotlin orphan FQCN: `com.lomo.data.repository.MediaEdgeRepositoryOrphanSweepTest`.

P4-11 gates: **authoritative live status remains OPEN / pending_env** (overview table + Honesty notes,
2026-07-22 re-audit residuals). Fixer #12 / Audit #10 host+device lines below are **archival only** —
they predate D4 residual packages (stage-only import, voice finalize registry, blank-opId reject,
C1–C5 residual scrub) and must **not** be re-read as current formal exit.

### Fixer #12 gate results (2026-07-22) + Audit #10 honesty lock — ARCHIVAL ONLY

> **Not current formal exit.** Superseded by OPEN / pending_env after D4 residual rework. Do not
> promote these historical GREEN lines into a re-claim without fresh `just check` + `just ci` +
> durable perf×2 + API ≥ 26 arm64 `just device-smoke` on the residual-closed tree.

Coverage floors (user decision 2026-07-22; still policy, not a re-claim):

- Rust `RUST_COVERAGE_MINIMUM=70`; measured llvm-cov **lines 79.50%** at the time of Audit #10
  (regions 69.71% — fail-under is **lines**).
- Kotlin JaCoCo filtered line **70.34%** (min 70) at that pass. **No Rust coverage grind toward 80.**

Host / formal (archival snapshot, Audit #10 era):

- `just check` / `just ci` were recorded GREEN under 70% floors at that pass.
- Targeted media/store/native stage_four surfaces were GREEN then.

Device / perf (archival snapshot, Audit #10 era):

- Historical `just perf` ×2 and arm64 `just device-smoke` lines exist in older logs; they are **not**
  a live re-run after D4 residual packages.
- Product-level media/archive interactive scenarios on device: **explicit non-claim residual** under
  strict D11 reading only.

**P4-11 formal exit (live): OPEN / pending_env** — single narrative with overview table and Honesty
notes. Historical “formal exit GREEN” wording from Audit #10 / fixer #12 is **scrubbed to archival**
and is not authoritative.

## FIXER A1 / A4 residual close (2026-07-22)

### First principles (D4)

1. **Invariant:** import = stage+verify only; staged media never becomes committed without memo
   promote under the same operation-id; sync journals only post-promote.
2. **Axiom violation:** `MediaEdgeRepository.importImage` called `promoteMedia` with
   `UUID.randomUUID()` and journaled sync immediately; `StoreMemoMutationRepository` left
   `pendingPromotes` empty.
3. **Rebuild from truth:** `PendingMediaStageRegistry` holds staged facts; memo save/update takes
   plans for body destinations into `StoreMemoCommand.pendingPromotes`; sync upsert after commit.
4. **Edge enforcement:** content:// never passed as Rust path; private temp + StagedTemp +
   maxStageBytes; draft removeImage drops stage only.
5. **Tail deletion:** production import path no longer calls `MediaPort.promoteMedia`.

### RED / GREEN (host)

- GREEN command:
  `./kotlin test --include-module=data --include-classes='com.lomo.data.repository.MediaEdgeImportStageOnlyTest'`
- Observed GREEN: **4 tests successful** (stage-only import; content:// StagedTemp; draft discard;
  saveMemo pendingPromotes same operationId + sync upsert).
- Also GREEN: `MediaEdgeRepositoryOrphanSweepTest` (6), `StoreMemoRepositoriesTest` (4).

### Residual

- arm64 `just device-smoke` not run this session → P4-11 OPEN.
- Full `just check` / `just ci` not run this session.
- Domain type rename `MigrationArchive*` deferred (wide surface).
- **C4 markdown destination residual (documented fail-closed, 2026-07-22 FIXER #3):**
  attachment destinations are collected from pulldown IR `destination` strings
  (`RenderInline::Image` / audio `Link`). Production writers emit unquoted relative paths
  (`media/...`). Angle-bracket destinations (`![x](<media/a.png>)`) are normalized by
  CommonMark/pulldown into the destination field without brackets. Link **titles** are not
  part of the destination and are never used as promote keys. Residual risk: non-production
  hand-authored markdown with space-containing destinations that lack angle brackets may fail
  promote body-path checks (fail-closed: memo commit refuses missing attachment path). No
  host-side dest re-parser is added; owner remains Rust IR.

