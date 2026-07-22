# Stage-4 media index and lifecycle behavior contract

> Status: **locked behavior contract; production D4 import residual closed on host (FIXER 2026-07-22);
> formal stage exit remains open until fresh arm64 P4-11 evidence**
>
> This document fixes the behavior and evidence required to close stage 4. It is not evidence that
> every exit bar is green. Actual RED/GREEN commands and results are recorded in
> `STAGE4-EVIDENCE.md` alongside the implementation that produces them.
>
> **Stage-3 store cutover is a hard entry prerequisite** (`STAGE3-EVIDENCE.md` records P3-10
> production store cutover GREEN). Stage-3 formal exit may remain open on arm64 (`pending_env`);
> stage-4 dark construction may proceed on host, but **Wave A/B cutover and stage-4 formal exit
> inherit the API ≥ 26 arm64 hard device gate** and must not claim GREEN while that gate is open.

## Behavior Contract

- **Unit under test:** `lomo-media` content identity (streaming sha256 digest, self-held magic-byte
  MIME table, extension/magic conflict rejection), canonical relative path validation via
  `lomo-workspace` path policy, stage → verify → commit media lifecycle, attachment reference
  refcount across {current, trash, history}, deterministic orphan sweep + media-trash recovery
  window, recording allocate/finalize, and `lomo-store` archive v2
  (export/inspect/import/activate) orchestration.
- **Owning layer:** `lomo-media` for media data rules (identity/digest/mime/path/stage/reference/
  orphan/commit); `lomo-store` for archive v2 orchestration and memo-transaction promote integration;
  `lomo-workspace` for Markdown/path facts used by archive and path policy; `lomo-native` for
  conversion-only FFI; Kotlin `data` for Android execution ports (byte streams, SAF temp copy,
  recorder/player, Coil/Media3 decode) only — not identity, orphan, or archive decision authority
  after cutover.
- **Priority tier:** P0 for ownership/invariant packages; P1 for presentation and performance.
- **Capability:** own media identity and lifecycle in Rust so digest is the sole content identity,
  human filenames remain stable (never hash-named), bytes never cross FFI, staged media never becomes
  a committed file without a same operation-id memo promote, orphans go through media-trash with a
  recovery window, archive v2 is plaintext-only with staging-before-activate, and production uses a
  single media/archive stack after atomic Wave A/B cutover (no feature flags, no dual-write).

## Fundamental invariants

1. **Content identity:** `digest = sha256(bytes)` + size + magic-validated mime identify
   `attachment_ref`. Dedup is by digest. Disk filenames stay human/timestamp schemes — never renamed
   to hash. Path is an independent stable property.
2. **No full-byte FFI (no full media bytes):** stage/verify/commit/recording/archive APIs pass paths
   and commands only. No `Vec<u8>` / `ByteArray` media-byte surfaces across crate or FFI public APIs.
   Large files (≥20GB class) stream with bounded buffers.
3. **Stage before commit:** import = stage+verify into Rust pending (digest known, not yet a
   committed workspace file). Memo save promotes staged media to final path + body refs + SQLite
   `attachment_ref` under one operation-id. Unreferenced staged media never becomes committed.
   Draft discard = drop stage.
4. **No half-success promote:** body must never reference a missing attachment after recovery.
   Crash mid-promote either completes once or leaves explicitly uncommitted stage — never a
   dangling body reference.
5. **Cross-source refcount:** digest refcount spans current memos ∪ trash memos ∪ history versions
   inside the retention window. Deleting a current memo only drops current refs; file moves to
   media-trash only when refcount hits zero; permanent delete only after recovery window.
6. **Deterministic orphan sweep:** reclaim is operation-boundary deterministic (not fuzzy timers).
   Intent is logged before permanent delete.
7. **Archive v2 only:** plaintext ZIP + `ArchiveManifestV2`. Old Kotlin ZIP is rejected as
   `unsupported archive version`. Inspect/import writes an independent staging workspace; zip-slip /
   dup entry / compression bomb / checksum failures fail closed before activate. Activate is atomic
   new workspace generation; failure never mutates the live workspace.
8. **Sync boundary:** only committed+verified media reaches the Kotlin sync-v1 recorder edge
   adapter. Staged/pending media never records. Sync ownership stays frozen until stage 5.
9. **Single production stack after cutover:** production dual-stack media identity or archive ZIP
   is forbidden. Dark-build until Wave A (media) and Wave B (archive) atomic cutovers. No feature
   flags, dual-write, or progressive dual DI.
10. **Entry gate:** stage-3 store cutover (`P3-10` evidence) is required before stage-4 GREEN claims
    for media owner packages. Stage-4 formal exit additionally requires API ≥ 26 arm64 device smoke.

## Resource limits (stage-4 media surface)

| Surface | Limit / rule | Failure |
| --- | --- | --- |
| Digest stream buffer | bounded chunk (not whole-file `Vec`) | architecture / contract fail |
| Cross-FFI media | path + command only; no full media bytes | architecture fail |
| Magic/extension | conflict → validation reject | `validation` fail closed |
| Archive entry | path policy + per-entry checksum + bomb ratio | fail closed at inspect/import |
| Unknown archive schema | reject; no Kotlin ZIP compat | `unsupported_archive_version` |
| Media-trash window | recovery window then permanent delete | intentional sweep only |

Over-limit handling never clamps, truncates, or returns partial success.

## Scenarios

GWT form below uses explicit Given / When / Then tokens so architecture locks can verify the
scenario contract without relying on prose alone.

### Stage entry and scaffolding (P4-00)

- Given `fixtures/baseline/STAGE4-CONTRACT.md` or `STAGE4-EVIDENCE.md` is missing, When architecture
  tests run, Then they fail with a named missing invariant.
- Given the real `lomo-media` owner crate is missing, empty, or not a workspace member, When
  architecture tests run, Then they fail.
- Given dark-build `lomo-media` sources exist, When production Kotlin DI is inspected before P4-10A,
  Then Kotlin `MediaRepositoryImpl` / `MigrationArchive*` remain the sole live production media and
  archive authorities and production dual-stack media DI / dual-write feature flags are absent.
- Given stage-3 store cutover is unrecorded, When implementers claim stage-4 media GREEN, Then
  architecture tests fail closed.

### Content identity and path (P4-01)

- Given file bytes, When digest is computed, Then result is lowercase hex sha256 of the full stream.
- Given magic bytes that disagree with extension hint, When mime is resolved, Then validation rejects
  the conflict at the boundary.
- Given a workspace-relative path, When media path is validated, Then `lomo-workspace`
  `WorkspaceRelativePath` policy applies (no absolute, `..`, backslash, control, or oversized path).

### Stage lifecycle (P4-02 / P4-05)

- Given `MediaSource::DirectPath` or `StagedTemp`, When `stage_media` runs, Then streaming digest is
  memory-bounded and returns `MediaStaged{digest,size,mime,staging_path}`.
- Given a discarded draft or crashed recording, When recovery runs, Then unpromoted stage is dropped
  and never becomes a committed file.
- Given `allocate_recording_target`, When the recorder writes and `finalize_recording` runs, Then
  digest/verify produces PendingMedia ready for memo promote.

### Reference, orphan, commit (P4-03 / P4-04)

- Given history still references a digest after current memo delete, When orphan sweep runs, Then
  the file is not trashed.
- Given refcount zero and recovery window elapsed, When sweep runs, Then media-trash entry is
  permanently deleted after intent log.
- Given staged media and a memo save, When commit runs under one operation-id, Then promote + body
  refs + SQLite attachment_ref are atomic; crash-point recovery never leaves body refs without files.

### Archive v2 (P4-06..P4-08)

- Given a workspace, When archive export runs, Then `ArchiveManifestV2` records entry type/length/
  digest and streams entries without loading full media into memory.
- Given zip-slip, duplicate entry, bomb ratio, checksum mismatch, or old Kotlin ZIP, When
  inspect/import runs, Then fail closed without mutating the live workspace.
- Given a fully green staging workspace, When activate runs, Then a new workspace generation is
  activated atomically; mid-activate death leaves the previous live workspace intact.

### Production switch (P4-10A / P4-10B)

- Given dark stack GREEN and arm64 gate available, When Wave A lands, Then production media identity
  owner is unique in Rust and Kotlin media index tails are deleted in the same wave.
- Given Wave A complete, When Wave B lands, Then production archive owner is unique in Rust and
  Kotlin `MigrationArchive*` ZIP tails are deleted in the same wave.
- Given Kotlin media identity or ZIP archive residual after claimed cutover, When architecture tests
  run, Then the build fails.

## Observable outcomes

- Architecture-test failures that name missing STAGE4 files, missing `lomo-media` owner, premature
  production dual-stack media/archive wiring, full-byte media FFI surfaces, or stage-3 cutover
  absence.
- Constrained digest/mime/path/stage/reference/orphan/commit/archive results with structured
  `LomoError` category/code.
- Device-smoke and performance numbers only when the corresponding package claims them; no fictional
  GREEN. API ≥ 26 arm64 remains the hard gate for cutover/exit.

## Excludes

- Sync-v1 ownership move (stage 5).
- Settings/credentials encrypted archive formats (remain Kotlin-owned independent interfaces).
- Coil/Media3/AudioRecorder/AudioPlayer decode and playback implementation (Kotlin execution ports).
- Hash-based on-disk filenames.
- Old Kotlin ZIP read/write compatibility paths.
- Feature-flag dual-write or progressive dual DI.

## RED/GREEN evidence format

Every implementation package must record in `STAGE4-EVIDENCE.md`:

1. **RED command** — narrowest command that should fail before the capability exists.
2. **Observed RED** — real failure output summary.
3. **GREEN command** — command that passes after the fix.
4. **Observed GREEN** — real pass summary.
5. **First principles** — invariant / violation / rebuild / edge / tail for non-trivial packages.
6. Honest `pending_env` when arm64 or other required env is unavailable — never mark those GREEN.
