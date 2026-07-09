# Compatibility Matrix

This matrix turns Lomo's README compatibility promises into an auditable contract for external
Markdown workspaces. It is intentionally scoped to externally visible file behavior, not internal
Room projections or UI presentation.

## Contract Levels

| Level | Meaning |
| --- | --- |
| Locked | Covered by an executable contract test in this repository. |
| Supported | Implemented by the current storage/sync path, but not fully represented by a dedicated fixture yet. |
| Best effort | Expected to work for common cases, with documented fallback or manual recovery required for edge cases. |
| Gap | README-compatible behavior that still needs an executable fixture or clearer product decision. |

## Compatibility Matrix

| Tool or scenario | Filename | Timestamp header | Tags and media links | Audio/voice media | Trash | Conflict | Duplicate memo behavior | Line ending and encoding | External delete or move |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Lomo local Markdown workspace | Locked: date filenames with `.md` suffix parse through `StorageFilenameFormats.parseFilenameOrNull`, including `yyyy_MM_dd.md`, `yyyy-MM-dd.md`, `yyyy.MM.dd.md`, `yyyyMMdd.md`, and `MM-dd-yyyy.md`. Writes use the selected date pattern plus `.md`. | Locked: memo bullets parse `- HH:mm:ss body`, `- HH:mm body`, and single-digit hour variants. Writes use the selected timestamp pattern. | Supported: markdown body is preserved after the header split; tag and attachment projection is derived from body content. | Best effort: audio/voice Markdown references stay in memo body text, but file existence, media lookup, playback, and refresh projection still need data/app fixtures. | Supported: app delete moves memo blocks to the trash workflow; file-level transaction gaps are tracked in `audit/01-memo-lifecycle-storage-search-audit.md`. | Supported: built-in sync providers expose conflict state and resolution flows. | Gap: duplicate memo identity, import, and de-dupe behavior is not locked by domain storage fixtures and needs data refresh/sync fixtures. | Supported: UTF-8 text and Kotlin `lines()` normalize common LF/CRLF reads; BOM and zero-width separators near headers are locked by storage tests. | Best effort: refresh/rebuild projects changed markdown files; move semantics depend on the file watcher or refresh path recognizing disappearance and appearance. |
| Thino daily note format | Locked: common Thino daily filenames using `yyyy-MM-dd.md` are accepted. Decorated names such as `Daily 2026-05-23.md` are rejected by the date contract. | Locked: Thino-style memo bullets with seconds are accepted without dropping markdown body text. | Locked at header boundary: `#tags` and wiki/media references remain in the parsed body. Projection details should be covered by data-layer fixtures. | Best effort: Thino audio or voice links embedded in a supported memo bullet remain body text; attachment resolution is not fixture-locked. | Best effort: Lomo trash is a workspace behavior, not a Thino metadata contract. | Best effort: external Thino edits rely on refresh plus sync conflict handling when a remote backend is involved. | Gap: duplicate Thino memo blocks or repeated imports need a data-layer identity fixture before any merge behavior is promised. | Supported: CRLF/LF text reads and UTF-8 content are expected; invisible header separators are handled. | Best effort: external deletion of a daily note or memo block needs data-layer fixture coverage for DB projection cleanup. |
| Obsidian vault direct use | Locked: Obsidian-style daily note names using `yyyy-MM-dd.md` and dotted variants are accepted when they match Lomo's supported date patterns. | Locked: minute-level Obsidian-style bullets such as `- 9:05 [[Project]] follow-up` are accepted. | Supported: markdown links, wiki links, tags, and image references are preserved as memo body text; rendering and attachment lookup are app/UI responsibilities. | Best effort: Obsidian audio embeds and linked voice files remain Markdown body references; lookup/playback compatibility is outside the locked domain fixtures. | Gap: Obsidian trash conventions and Lomo trash files are not declared interoperable. | Best effort: simultaneous vault edits are mediated by the selected sync backend or manual refresh; per-block conflict fixtures are still needed. | Gap: repeated memo blocks, conflict copies, or duplicate daily-note imports are not yet normalized by an executable fixture. | Supported for UTF-8/LF/CRLF reads; non-UTF encodings are not promised. | Best effort: vault moves/renames are treated as file changes, not stable memo identity unless the memo block can be reidentified. |
| Obsidian Remotely Save S3 | Supported: S3 sync can target the vault root or a custom folder; filename compatibility follows local Markdown parsing. | Locked for local parse after sync: supported memo headers remain parseable. | Best effort: attachment links are synced as files when they are inside the synced workspace scope; missing attachments remain markdown references. | Best effort: audio/voice files are expected to round trip only when they are inside the synced workspace scope; missing files remain Markdown references. | Gap: trash semantics across Remotely Save and Lomo need fixture coverage. | Supported: S3 sync exposes conflict detection/resolution, but Remotely Save-specific conflict filename conventions need fixtures. | Gap: duplicate memo behavior after S3 refresh, remote conflict objects, or repeated imports needs data-layer fixture coverage. | Best effort: object bytes are read as Markdown text after sync; UTF-8 is the contract. | Best effort: remote deletes/moves depend on sync planner behavior and should be covered by S3 fixture work outside this slice. |
| Rclone-backed workspace sync | Supported: after Rclone writes files locally, filename compatibility follows the local Markdown contract. | Locked for local parse after sync. | Best effort: Lomo preserves markdown references; Rclone is responsible for copying linked files. | Best effort: audio/voice references remain markdown text; Rclone is responsible for copying referenced media files completely. | Best effort: Lomo trash files are ordinary workspace files from Rclone's perspective. | Best effort: Rclone conflict behavior depends on the chosen remote and command options; Lomo can only resolve conflicts it sees as local files or provider conflict records. | Gap: duplicate files or repeated memo blocks produced by Rclone are not yet covered by a stable import/de-dupe fixture. | Supported for UTF-8 Markdown after local write; no guarantee for partial files while Rclone is still copying. | Best effort: run a refresh after external moves/deletes; partial sync states are not a supported steady state. |
| Syncthing or Nextcloud folder sync | Supported: synced daily `.md` files are parsed through the same local filename contract. | Locked for local parse after sync. | Best effort: markdown body references remain intact; attachment availability depends on folder pairing and sync completion. | Best effort: audio/voice references remain intact as Markdown; availability depends on folder pairing, sync completion, and media lookup fixtures not covered here. | Best effort: trash is synced as normal files if the trash path is inside the paired folder. | Best effort: conflict copies generated by external tools are not yet normalized by a dedicated fixture. | Gap: duplicate/conflict files and repeated memo blocks from multi-device sync need explicit data-layer import behavior. | Supported for UTF-8/LF/CRLF complete files; transient partial files need refresh/sync stabilization. | Best effort: external delete/move requires refresh; concurrent device edits can surface as duplicate/conflict files depending on the sync tool. |
| Manual editor or script | Locked: exact supported date `.md` filenames are accepted; extensionless stems and decorated filenames are rejected at the external filename boundary. | Locked: supported bullet timestamp headers parse; malformed headers become body text or trigger fallback behavior in downstream parsing. | Supported: markdown body text remains source of truth for tags and media references. | Best effort: manually written audio/voice references are preserved as Markdown text; file validation, MIME handling, and playback are not guaranteed by storage fixtures. | Best effort: manual edits to trash files are not a stable public API yet. | Best effort: manual conflicts are represented as whatever files the user creates. | Gap: duplicate memo blocks created by scripts or repeated imports have no locked de-dupe contract yet. | Supported: UTF-8 Markdown with LF or CRLF; BOM near a header is tolerated. | Best effort: refresh handles completed file edits; no guarantee while a script writes files non-atomically. |

## Executable Fixtures

- `domain/test/model/StorageFormatCompatibilityFixtureTest.kt`
  locks filename and header compatibility for Lomo, Thino, Obsidian, compact export, month-first import,
  and common external editor spacing.
- `domain/test/model/StorageTimestampFormatsInvisibleSeparatorTest.kt`
  locks BOM and zero-width separator tolerance around memo headers.
- `domain/test/util/StorageFilenameFormatsTest.kt`
  locks strict supported date patterns and invalid-date rejection.
- `domain/test/util/StorageTimestampFormatsTest.kt`
  locks malformed timestamp rejection and supported header parsing.

## Fixture Backlog

| Fixture | Owning layer | Expected proof |
| --- | --- | --- |
| Data-layer workspace fixture with mixed Thino and Obsidian daily files | data | Refresh creates stable memo projections, tags, media refs, and local dates from a fixture directory. |
| Audio/voice attachment fixture | data/app | Markdown audio/voice references remain visible, files resolve when present, missing media is explicit, and playback-facing metadata is not lost during refresh. |
| Duplicate memo import fixture | data | Refresh behavior for repeated memo blocks, duplicate daily notes, and sync conflict copies is explicit: imported separately, merged, ignored, or surfaced as a conflict. |
| External delete of a daily note | data | Refresh removes or marks stale memo projections according to the chosen lifecycle contract. |
| External move or rename of a daily note | data | Refresh either preserves memo identity through block matching or documents the new identity behavior. |
| Remotely Save conflict object naming | data | S3 sync classifies conflict files without corrupting the local Markdown source. |
| Syncthing conflict copy | data | Conflict-copy files are either ignored, imported as separate Markdown, or surfaced explicitly. |
| Trash interop | data | Lomo trash files remain recoverable after sync round trips and do not get imported as active memos. |
| Attachment folder move | data/app | Markdown references remain visible, missing attachments are explicit, and no memo content is dropped. |

## Current Boundaries

- This matrix does not promise compatibility with arbitrary decorated daily note names, non-UTF encodings,
  partial files written by external sync tools, or another app's trash database.
- The locked domain fixtures prove parsing and formatting contracts only. Repository refresh, trash,
  conflict, media, and sync behavior need data-layer fixtures before they should be treated as fully locked.
