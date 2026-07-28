# Stage-5 divergence manifest (P5-00)

> Old Kotlin sync behavior is **not** an oracle. Safe behaviors freeze into language-agnostic fixtures.
> Intentional divergences are listed here so characterization never re-introduces known bugs.

## Safe behaviors to preserve (oracle-grade)

These must hold in `lomo-sync` and are frozen in `stage5-safe-behavior-fixtures.v1.json`:

| ID | Behavior |
| --- | --- |
| SB-01 | Incomplete remote listing never yields path delete intents |
| SB-02 | First-takeover / read-only preflight never yields user-file deletes |
| SB-03 | Verify failure or cancel → baseline not advanced |
| SB-04 | Conditional write / ref CAS failure → replan, not overwrite |
| SB-05 | Both-modified / digest mismatch → durable conflict, never silent local-or-remote win |
| SB-06 | Workspace generation mismatch on restore → explicit reject |
| SB-07 | Secrets absent from durable session/journal/diagnostics |
| SB-08 | Unrecognized remote paths reported, not moved/deleted |
| SB-09 | Migration/reset/takeover action types have no user-file delete/overwrite branch |
| SB-10 | Sync Inbox pending review survives remote-sync cutover type cleanup |

## Intentional divergences (do not re-freeze old bug)

| ID | Old behavior (reject as oracle) | Stage-5 truth |
| --- | --- | --- |
| DV-01 | Path/URI hash treated as workspace generation | Real random `WorkspaceGenerationId` |
| DV-02 | History ids `memoId-rN` (collision-prone) | Content-addressed `RevisionId` DAG with parents |
| DV-03 | Mutable single-file state without parent revision | Immutable state revision objects + per-memo head |
| DV-04 | Timestamp / provider metadata can drive delete | Only complete snapshot + baseline + tombstone |
| DV-05 | Provider-specific planners and conflict enums | One provider-neutral intent/state machine |
| DV-06 | Git SAF mirror / checkout as user-file write path | Unified `LocalSyncMutationBatch` only |
| DV-07 | Force push / reset escape hatches | Non-force CAS only; structured failures |
| DV-08 | Dual retry/conflict owners (Kotlin workers + planner) | Single Rust retry/conflict owner |
| DV-09 | Silent layout migration / empty-default recovery | Fail closed → `CorruptState` / structured error |
| DV-10 | ETag or remote lastModified as content identity | Content SHA-256 + opaque revision token roles separated |
| DV-11 | sync-v1 ordinal wire as long-term protocol | New durable `.lomo/sync/v1` + public provider ports; no v1 runtime after cutover |
| DV-12 | Clean-slate wipe of pending sync metadata on DB rebuild without recovery | Dangerous state recovered from `.lomo/sync`; SQLite is rebuildable cache only |

## Characterization rule

- Prefer fixtures under `fixtures/remote/` and `stage5-safe-behavior-fixtures.v1.json`.
- Do not add golden tests that assert DV-* old outcomes.
- When porting a Kotlin unit test, map it to SB-* or mark it divergent and rewrite the expected result.
