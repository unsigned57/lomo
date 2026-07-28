//! Memo transaction nine-step state machine with operation-id idempotency.

use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use rusqlite::{Connection, OptionalExtension, params};

use lomo_core::{CoreRevision, EventSequence, InvalidationScope, OperationId};

use crate::content_facts::{fingerprint_content, merge_tags, project_content_facts};
use crate::error::{busy, conflict, from_sqlite, storage, validation};
use crate::lomo_format::{
    HistoryBody, LomoLayoutVersion, LomoPaths, LomoPayload, LomoRecordKind, MemoCommandKind,
    OperationIntent, OperationStatus, StateBody, read_record, write_record_atomic,
};
use crate::query::recompute_stats;
use crate::tokenizer::index_tokens;

/// Crash injection points for recovery matrix tests (production uses `None`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CrashPoint {
    AfterIntent,
    AfterHistory,
    /// After staged media promote succeeds, before Markdown body commit (P4-04).
    AfterPromoteBeforeFiles,
    AfterFiles,
    AfterProjection,
    AfterCommittedMark,
}

/// Fails closed when layout head is V2 while this crate still only writes v1-shaped records.
///
/// First principles: layout head is the sole authority for history/state tree shape. Store memo
/// writers still produce flat v1 bodies (`memoId-rN` history, single-file state). Writing those into
/// a V2 tree would corrupt the dual-layout contract. Production activation migration must stay off
/// the hot path until store v2 writers exist.
///
/// # Errors
///
/// - `layout_v2_requires_v2_writers` when layout is V2.
pub fn refuse_v1_writers_on_layout_v2(paths: &LomoPaths) -> Result<(), lomo_core::LomoError> {
    if paths.layout == LomoLayoutVersion::V2 {
        return Err(validation(
            "layout_v2_requires_v2_writers",
            "layout head is V2 but store memo writers still emit v1-shaped history/state; refuse mutate until store v2 writers cut over",
        ));
    }
    Ok(())
}

/// Command accepted by the nine-step machine.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemoCommand {
    pub operation_id: OperationId,
    pub kind: MemoCommandKind,
    pub memo_id: String,
    pub expected_revision: u64,
    pub expected_fingerprint: Option<String>,
    pub content: Option<String>,
    pub tags: Vec<String>,
    pub pin: Option<bool>,
    /// Staged media to promote under this operation-id before body/`attachment_ref` (P4-04).
    pub pending_promotes: Vec<lomo_media::PromotePlan>,
}

/// Successful commit publication (step 8).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemoCommitResult {
    pub operation_id: String,
    pub memo_id: String,
    pub core_revision: CoreRevision,
    pub event_sequence: EventSequence,
    pub content_revision: u64,
    pub file_fingerprint: String,
    pub scopes: Vec<InvalidationScope>,
    pub idempotent_replay: bool,
}

/// Store write mode gate.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WriteGate {
    Ready,
    RebuildingReadOnly,
}

/// Applies a memo command through the nine durable steps.
///
/// # Errors
///
/// - `stale_snapshot` when expected revision/fingerprint mismatches.
/// - `store_rebuilding` when the write gate is read-only.
/// - storage/corruption errors from durable I/O or `SQLite`.
/// - Injected `crash_point_*` errors when a test crash point is set.
pub fn apply_memo_command(
    workspace_root: &Path,
    connection: &Connection,
    gate: WriteGate,
    command: &MemoCommand,
    high_water_revision: &mut u64,
    event_sequence: &mut u64,
    crash_point: Option<CrashPoint>,
) -> Result<MemoCommitResult, lomo_core::LomoError> {
    if gate == WriteGate::RebuildingReadOnly {
        return Err(busy(
            "store_rebuilding",
            "mutations are rejected while rebuild is active",
        ));
    }

    let paths = LomoPaths::for_workspace(workspace_root);
    // Dual-layout fence: store writers still emit v1-shaped flat history/state records.
    // Layout V2 is authoritative only after migration; until store v2 writers cut over, refuse
    // mutate so v1 bodies never land under history/v2 or state/v2 paths.
    refuse_v1_writers_on_layout_v2(&paths)?;
    paths.ensure_layout()?;

    let op_path = operation_path(&paths, command.operation_id.as_str());

    // Idempotent replay of a fully committed operation.
    if op_path.exists() {
        let existing = read_record(&op_path)?;
        let intent: OperationIntent =
            serde_json::from_str(&existing.payload.body_json).map_err(|err| {
                validation(
                    "operation_intent_decode_failed",
                    &format!("cannot decode operation intent: {err}"),
                )
            })?;
        if intent.status == OperationStatus::Committed {
            return Ok(MemoCommitResult {
                operation_id: intent.operation_id,
                memo_id: intent.memo_id,
                core_revision: CoreRevision::from_raw(*high_water_revision),
                event_sequence: EventSequence::from_raw(*event_sequence),
                content_revision: intent.content_revision_after.unwrap_or(0),
                file_fingerprint: option_string(intent.file_fingerprint_after),
                scopes: Vec::new(),
                idempotent_replay: true,
            });
        }
        // Resume incomplete operation (crash recovery).
        return recover_operation(
            workspace_root,
            connection,
            &paths,
            &op_path,
            intent,
            high_water_revision,
            event_sequence,
            crash_point,
        );
    }

    // Step 1: validate command + expected revision/fingerprint.
    validate_command(connection, command)?;

    // Merge explicit command tags with content-derived tags so list projections stay complete
    // when Kotlin create/update only passes body text (Room-era content analysis parity).
    let tags = match command.kind {
        MemoCommandKind::Create | MemoCommandKind::Update | MemoCommandKind::HistoryRestore => {
            let content = command.content.as_deref().unwrap_or("");
            let facts = project_content_facts(content)?;
            merge_tags(&command.tags, &facts.tags)?
        }
        MemoCommandKind::Delete
        | MemoCommandKind::Restore
        | MemoCommandKind::Pin
        | MemoCommandKind::Unpin => command.tags.clone(),
    };

    // Step 2: append checksummed intent under .lomo/operations/v1.
    let mut intent = OperationIntent {
        operation_id: command.operation_id.as_str().to_owned(),
        command: command.kind,
        memo_id: command.memo_id.clone(),
        expected_revision: command.expected_revision,
        expected_fingerprint: command.expected_fingerprint.clone(),
        content: command.content.clone(),
        tags,
        pin: command.pin,
        status: OperationStatus::IntentAppended,
        content_revision_after: None,
        file_fingerprint_after: None,
        core_revision_after: None,
        event_sequence_after: None,
        pending_promotes: command.pending_promotes.clone(),
    };
    persist_intent(&op_path, &intent)?;
    maybe_crash(
        crash_point,
        CrashPoint::AfterIntent,
        "crash_point_after_intent",
    )?;

    run_remaining_steps(
        workspace_root,
        connection,
        &paths,
        &op_path,
        &mut intent,
        high_water_revision,
        event_sequence,
        crash_point,
    )
}

#[expect(
    clippy::too_many_arguments,
    reason = "recovery shares durable handles with apply"
)]
fn recover_operation(
    workspace_root: &Path,
    connection: &Connection,
    paths: &LomoPaths,
    op_path: &Path,
    mut intent: OperationIntent,
    high_water_revision: &mut u64,
    event_sequence: &mut u64,
    crash_point: Option<CrashPoint>,
) -> Result<MemoCommitResult, lomo_core::LomoError> {
    run_remaining_steps(
        workspace_root,
        connection,
        paths,
        op_path,
        &mut intent,
        high_water_revision,
        event_sequence,
        crash_point,
    )
}

#[expect(
    clippy::too_many_arguments,
    clippy::too_many_lines,
    reason = "explicit step machine with durable handles; promote is part of steps 4–5"
)]
fn run_remaining_steps(
    workspace_root: &Path,
    connection: &Connection,
    paths: &LomoPaths,
    op_path: &Path,
    intent: &mut OperationIntent,
    high_water_revision: &mut u64,
    event_sequence: &mut u64,
    crash_point: Option<CrashPoint>,
) -> Result<MemoCommitResult, lomo_core::LomoError> {
    // Step 3: append history snapshot when content mutates.
    if matches!(intent.status, OperationStatus::IntentAppended) {
        if needs_history(intent.command) {
            append_history(paths, intent)?;
        }
        intent.status = OperationStatus::HistoryAppended;
        persist_intent(op_path, intent)?;
        maybe_crash(
            crash_point,
            CrashPoint::AfterHistory,
            "crash_point_after_history",
        )?;
    }

    // Steps 4–5: promote staged media under the same operation-id, then atomic commit Markdown.
    // Body/`attachment_ref` must never land before promote Ok (P4-04 no half-success).
    if intent.status == OperationStatus::HistoryAppended {
        promote_pending_media(workspace_root, intent)?;
        maybe_crash(
            crash_point,
            CrashPoint::AfterPromoteBeforeFiles,
            "crash_point_after_promote_before_files",
        )?;
        let (fingerprint, content_revision) = commit_files(workspace_root, intent)?;
        intent.file_fingerprint_after = Some(fingerprint);
        intent.content_revision_after = Some(content_revision);
        intent.status = OperationStatus::FilesCommitted;
        persist_intent(op_path, intent)?;
        maybe_crash(
            crash_point,
            CrashPoint::AfterFiles,
            "crash_point_after_files",
        )?;
    }

    // Step 6: one SQLite transaction updates all projections.
    if intent.status == OperationStatus::FilesCommitted {
        connection
            .execute_batch("BEGIN IMMEDIATE")
            .map_err(|err| from_sqlite(&err))?;
        let result = update_projections(connection, paths, intent);
        match result {
            Ok(()) => {
                connection
                    .execute_batch("COMMIT")
                    .map_err(|err| from_sqlite(&err))?;
            }
            Err(err) => {
                drop(connection.execute_batch("ROLLBACK"));
                return Err(err);
            }
        }
        intent.status = OperationStatus::ProjectionCommitted;
        persist_intent(op_path, intent)?;
        maybe_crash(
            crash_point,
            CrashPoint::AfterProjection,
            "crash_point_after_projection",
        )?;
    }

    // Steps 7–8: durable publish plan → set meta counters → mark Committed.
    // Invariant: Committed is written only after the publish plan and meta counters are durable.
    // Recovery of ProjectionCommitted re-applies the same planned counters (complete-once).
    // Recovery of Committed is pure idempotent replay without another bump.
    if intent.status == OperationStatus::ProjectionCommitted {
        let target_hw = match intent.core_revision_after {
            Some(planned) => planned,
            None => high_water_revision
                .checked_add(1)
                .ok_or_else(|| validation("revision_overflow", "core revision overflow"))?,
        };
        let target_seq = match intent.event_sequence_after {
            Some(planned) => planned,
            None => event_sequence
                .checked_add(1)
                .ok_or_else(|| validation("event_sequence_overflow", "event sequence overflow"))?,
        };
        if intent.core_revision_after.is_none() || intent.event_sequence_after.is_none() {
            intent.core_revision_after = Some(target_hw);
            intent.event_sequence_after = Some(target_seq);
            // Persist publish plan before meta so a crash mid-publish cannot invent a second bump.
            persist_intent(op_path, intent)?;
        }
        write_meta_u64(connection, "high_water_revision", target_hw)?;
        write_meta_u64(connection, "event_sequence", target_seq)?;
        *high_water_revision = target_hw;
        *event_sequence = target_seq;

        intent.status = OperationStatus::Committed;
        persist_intent(op_path, intent)?;
        maybe_crash(
            crash_point,
            CrashPoint::AfterCommittedMark,
            "crash_point_after_committed_mark",
        )?;
    }

    let scopes = scopes_for(intent.command);
    let content_revision = intent
        .content_revision_after
        .unwrap_or(intent.expected_revision);
    let file_fingerprint = option_string(intent.file_fingerprint_after.clone());
    let published_hw = intent.core_revision_after.unwrap_or(*high_water_revision);
    let published_seq = intent.event_sequence_after.unwrap_or(*event_sequence);

    // Step 9: cleanup is deferred via explicit API (`cleanup_expired_operations`).

    Ok(MemoCommitResult {
        operation_id: intent.operation_id.clone(),
        memo_id: intent.memo_id.clone(),
        core_revision: CoreRevision::from_raw(published_hw),
        event_sequence: EventSequence::from_raw(published_seq),
        content_revision,
        file_fingerprint,
        scopes,
        idempotent_replay: false,
    })
}

fn validate_command(
    connection: &Connection,
    command: &MemoCommand,
) -> Result<(), lomo_core::LomoError> {
    if command.memo_id.is_empty() || command.memo_id.len() > 128 {
        return Err(validation(
            "invalid_memo_id",
            "memo_id must be 1..=128 bytes",
        ));
    }
    match command.kind {
        MemoCommandKind::Create => {
            if command.expected_revision != 0 {
                return Err(validation(
                    "invalid_create_revision",
                    "create requires expected_revision 0",
                ));
            }
            if memo_exists(connection, &command.memo_id)? {
                return Err(conflict(
                    "memo_already_exists",
                    "create rejected because memo already exists",
                ));
            }
            if command.content.is_none() {
                return Err(validation("missing_content", "create requires content"));
            }
        }
        MemoCommandKind::Update | MemoCommandKind::HistoryRestore => {
            let row = load_memo_row(connection, &command.memo_id)?
                .ok_or_else(|| validation("memo_not_found", "memo does not exist for update"))?;
            if row.content_revision != command.expected_revision {
                return Err(conflict(
                    "stale_snapshot",
                    "expected content revision does not match store",
                ));
            }
            if let Some(expected_fp) = &command.expected_fingerprint
                && &row.file_fingerprint != expected_fp
            {
                return Err(conflict(
                    "stale_snapshot",
                    "expected file fingerprint does not match store",
                ));
            }
            if command.content.is_none() && command.kind == MemoCommandKind::Update {
                return Err(validation("missing_content", "update requires content"));
            }
        }
        MemoCommandKind::Delete
        | MemoCommandKind::Restore
        | MemoCommandKind::Pin
        | MemoCommandKind::Unpin => {
            let row = load_memo_row(connection, &command.memo_id)?
                .ok_or_else(|| validation("memo_not_found", "memo does not exist"))?;
            if row.content_revision != command.expected_revision {
                return Err(conflict(
                    "stale_snapshot",
                    "expected content revision does not match store",
                ));
            }
        }
    }
    Ok(())
}

struct MemoRow {
    content_revision: u64,
    file_fingerprint: String,
}

fn memo_exists(connection: &Connection, memo_id: &str) -> Result<bool, lomo_core::LomoError> {
    let count: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM memo WHERE memo_id = ?1",
            params![memo_id],
            |row| row.get(0),
        )
        .map_err(|err| from_sqlite(&err))?;
    Ok(count > 0)
}

fn load_memo_row(
    connection: &Connection,
    memo_id: &str,
) -> Result<Option<MemoRow>, lomo_core::LomoError> {
    connection
        .query_row(
            "SELECT content_revision, file_fingerprint FROM memo WHERE memo_id = ?1",
            params![memo_id],
            |row| {
                let rev: i64 = row.get(0)?;
                let fp: String = row.get(1)?;
                Ok((rev, fp))
            },
        )
        .optional()
        .map_err(|err| from_sqlite(&err))?
        .map(|(rev, fp)| {
            let content_revision = u64::try_from(rev).map_err(|_overflow| {
                validation("invalid_content_revision", "content_revision out of u64")
            })?;
            Ok(MemoRow {
                content_revision,
                file_fingerprint: fp,
            })
        })
        .transpose()
}

const fn needs_history(kind: MemoCommandKind) -> bool {
    matches!(
        kind,
        MemoCommandKind::Create | MemoCommandKind::Update | MemoCommandKind::HistoryRestore
    )
}

fn append_history(paths: &LomoPaths, intent: &OperationIntent) -> Result<(), lomo_core::LomoError> {
    let content = option_string(intent.content.clone());
    let revision = intent.expected_revision.saturating_add(1);
    let fingerprint = fingerprint_content(&content);
    let body = HistoryBody {
        memo_id: intent.memo_id.clone(),
        revision,
        content,
        file_fingerprint: fingerprint,
    };
    let body_json = serde_json::to_string(&body).map_err(|err| {
        validation(
            "history_encode_failed",
            &format!("cannot encode history: {err}"),
        )
    })?;
    let record_id = format!("{}-r{revision}", intent.memo_id);
    let path = paths.history.join(format!("{record_id}.rec"));
    write_record_atomic(
        &path,
        &LomoPayload {
            kind: LomoRecordKind::History,
            record_id,
            body_json,
        },
    )
}

/// Promotes every pending staged media plan for this operation before body write.
///
/// Recovery re-enters this path while status is still `HistoryAppended`. Promote is idempotent
/// when the final path already holds the same digest (see `lomo_media::promote_staged`).
fn promote_pending_media(
    workspace_root: &Path,
    intent: &OperationIntent,
) -> Result<(), lomo_core::LomoError> {
    for plan in &intent.pending_promotes {
        if plan.operation_id != intent.operation_id {
            return Err(validation(
                "promote_operation_id_mismatch",
                "pending promote operation_id must match the memo operation-id",
            ));
        }
        // Injected AfterMoveBeforeRecord is not used from store; store uses CrashPoint matrix.
        let _result =
            lomo_media::promote_staged(workspace_root, plan, lomo_media::PromoteCrashPoint::None)?;
    }
    // Fail closed: body attachment paths must exist as committed files after promote.
    if matches!(
        intent.command,
        MemoCommandKind::Create | MemoCommandKind::Update | MemoCommandKind::HistoryRestore
    ) {
        let content = option_string(intent.content.clone());
        let facts = project_content_facts(&content)?;
        for relative in &facts.attachment_paths {
            let absolute = workspace_root.join(relative);
            if !absolute.is_file() {
                return Err(validation(
                    "attachment_file_missing_after_promote",
                    "memo body references an attachment path that is not a committed file; refuse body/`attachment_ref`",
                ));
            }
        }
    }
    Ok(())
}

fn commit_files(
    workspace_root: &Path,
    intent: &OperationIntent,
) -> Result<(String, u64), lomo_core::LomoError> {
    let memo_path = memo_file_path(workspace_root, &intent.memo_id);
    match intent.command {
        MemoCommandKind::Create | MemoCommandKind::Update | MemoCommandKind::HistoryRestore => {
            let content = option_string(intent.content.clone());
            atomic_write_text(&memo_path, &content)?;
            let fingerprint = fingerprint_content(&content);
            let revision = intent.expected_revision.saturating_add(1);
            Ok((fingerprint, revision))
        }
        MemoCommandKind::Delete => {
            // Move to trash folder under workspace (physical recoverability).
            let trash_dir = workspace_root.join("trash");
            std::fs::create_dir_all(&trash_dir).map_err(|err| {
                storage(
                    "trash_dir_create_failed",
                    &format!("cannot create trash dir: {err}"),
                )
            })?;
            if memo_path.exists() {
                let dest = trash_dir.join(format!("{}.md", intent.memo_id));
                std::fs::rename(&memo_path, &dest).map_err(|err| {
                    storage(
                        "trash_move_failed",
                        &format!("cannot move memo to trash: {err}"),
                    )
                })?;
                let content = std::fs::read_to_string(&dest).unwrap_or_else(|_err| {
                    // Missing trash body after move is treated as empty content for fingerprint only.
                    String::new()
                });
                Ok((fingerprint_content(&content), intent.expected_revision))
            } else {
                Ok((String::new(), intent.expected_revision))
            }
        }
        MemoCommandKind::Restore => {
            let trash_path = workspace_root
                .join("trash")
                .join(format!("{}.md", intent.memo_id));
            if trash_path.exists() {
                if let Some(parent) = memo_path.parent() {
                    std::fs::create_dir_all(parent).map_err(|err| {
                        storage(
                            "memo_dir_create_failed",
                            &format!("cannot create memo dir: {err}"),
                        )
                    })?;
                }
                std::fs::rename(&trash_path, &memo_path).map_err(|err| {
                    storage(
                        "restore_move_failed",
                        &format!("cannot restore memo from trash: {err}"),
                    )
                })?;
                let content = std::fs::read_to_string(&memo_path).map_err(|err| {
                    storage(
                        "restore_read_failed",
                        &format!("cannot read restored memo: {err}"),
                    )
                })?;
                Ok((fingerprint_content(&content), intent.expected_revision))
            } else {
                Err(validation(
                    "trash_memo_missing",
                    "cannot restore: trash memo file missing",
                ))
            }
        }
        MemoCommandKind::Pin | MemoCommandKind::Unpin => {
            // No markdown mutation; fingerprint stays as on disk if present.
            if memo_path.exists() {
                let content = std::fs::read_to_string(&memo_path).map_err(|err| {
                    storage("memo_read_failed", &format!("cannot read memo: {err}"))
                })?;
                Ok((fingerprint_content(&content), intent.expected_revision))
            } else {
                Ok((String::new(), intent.expected_revision))
            }
        }
    }
}

#[expect(
    clippy::too_many_lines,
    reason = "single SQLite projection transaction for all memo command kinds"
)]
fn update_projections(
    connection: &Connection,
    paths: &LomoPaths,
    intent: &OperationIntent,
) -> Result<(), lomo_core::LomoError> {
    let now = now_ms();
    match intent.command {
        MemoCommandKind::Create | MemoCommandKind::Update | MemoCommandKind::HistoryRestore => {
            let content = option_string(intent.content.clone());
            let search_content = index_tokens(&content);
            let preview: String = content.chars().take(200).collect();
            let fingerprint = intent
                .file_fingerprint_after
                .clone()
                .unwrap_or_else(|| fingerprint_content(&content));
            let revision =
                i64::try_from(intent.content_revision_after.unwrap_or(1)).map_err(|_overflow| {
                    validation("revision_overflow", "content revision overflow")
                })?;
            let facts = project_content_facts(&content)?;
            let has_todo = i64::from(facts.has_todo);
            let has_url = i64::from(facts.has_url);
            let has_attachment = i64::from(!facts.attachment_paths.is_empty());
            let source_path = format!("memos/{}.md", intent.memo_id);

            let existing: Option<i64> = connection
                .query_row(
                    "SELECT rowid FROM memo WHERE memo_id = ?1",
                    params![intent.memo_id],
                    |row| row.get(0),
                )
                .optional()
                .map_err(|err| from_sqlite(&err))?;

            if let Some(rowid) = existing {
                // FTS5 external-content requires the pre-update column values on 'delete'.
                // Delete the old FTS row first, then update content, then insert the new FTS row.
                let old_search: String = connection
                    .query_row(
                        "SELECT search_content FROM memo WHERE rowid = ?1",
                        params![rowid],
                        |row| row.get(0),
                    )
                    .map_err(|err| from_sqlite(&err))?;
                connection
                    .execute(
                        "INSERT INTO memo_fts(memo_fts, rowid, search_content) VALUES('delete', ?1, ?2)",
                        params![rowid, old_search],
                    )
                    .map_err(|err| from_sqlite(&err))?;
                connection
                    .execute(
                        "UPDATE memo SET source_path=?1, file_fingerprint=?2, has_todo=?3, has_url=?4, \
                         has_attachment=?5, updated_at_ms=?6, body_preview=?7, search_content=?8, content_revision=?9 \
                         WHERE memo_id=?10",
                        params![
                            source_path,
                            fingerprint,
                            has_todo,
                            has_url,
                            has_attachment,
                            now,
                            preview,
                            search_content,
                            revision,
                            intent.memo_id
                        ],
                    )
                    .map_err(|err| from_sqlite(&err))?;
                connection
                    .execute(
                        "INSERT INTO memo_fts(rowid, search_content) VALUES(?1, ?2)",
                        params![rowid, search_content],
                    )
                    .map_err(|err| from_sqlite(&err))?;
            } else {
                connection
                    .execute(
                        "INSERT INTO memo(memo_id, source_path, file_fingerprint, has_todo, has_url, \
                         has_attachment, created_at_ms, updated_at_ms, body_preview, search_content, content_revision) \
                         VALUES(?1,?2,?3,?4,?5,?6,?7,?7,?8,?9,?10)",
                        params![
                            intent.memo_id,
                            source_path,
                            fingerprint,
                            has_todo,
                            has_url,
                            has_attachment,
                            now,
                            preview,
                            search_content,
                            revision
                        ],
                    )
                    .map_err(|err| from_sqlite(&err))?;
                let rowid: i64 = connection
                    .query_row(
                        "SELECT rowid FROM memo WHERE memo_id = ?1",
                        params![intent.memo_id],
                        |row| row.get(0),
                    )
                    .map_err(|err| from_sqlite(&err))?;
                connection
                    .execute(
                        "INSERT INTO memo_fts(rowid, search_content) VALUES(?1, ?2)",
                        params![rowid, search_content],
                    )
                    .map_err(|err| from_sqlite(&err))?;
            }

            // Tags (SQLite projection + durable .lomo state).
            connection
                .execute(
                    "DELETE FROM memo_tag WHERE memo_id = ?1",
                    params![intent.memo_id],
                )
                .map_err(|err| from_sqlite(&err))?;
            for tag in &intent.tags {
                upsert_tag(connection, &intent.memo_id, tag)?;
            }
            replace_attachments(connection, &intent.memo_id, &facts.attachment_paths)?;
            merge_write_state(
                paths,
                &intent.memo_id,
                StatePatch {
                    pinned: None,
                    trashed: None,
                    pinned_at_ms: TimestampPatch::Keep,
                    trashed_at_ms: TimestampPatch::Keep,
                    tags: Some(intent.tags.clone()),
                },
            )?;

            // History projection
            if let Some(rev) = intent.content_revision_after {
                let rev_i = i64::try_from(rev).map_err(|_overflow| {
                    validation("revision_overflow", "history revision overflow")
                })?;
                connection
                    .execute(
                        "INSERT OR REPLACE INTO revision_index(memo_id, revision, history_record_id, created_at_ms) \
                         VALUES(?1,?2,?3,?4)",
                        params![
                            intent.memo_id,
                            rev_i,
                            format!("{}-r{rev}", intent.memo_id),
                            now
                        ],
                    )
                    .map_err(|err| from_sqlite(&err))?;
            }
        }
        MemoCommandKind::Delete => {
            connection
                .execute(
                    "INSERT OR REPLACE INTO memo_trash(memo_id, trashed_at_ms) VALUES(?1, ?2)",
                    params![intent.memo_id, now],
                )
                .map_err(|err| from_sqlite(&err))?;
            // Read-merge: trash must not clear pin (or tags).
            merge_write_state(
                paths,
                &intent.memo_id,
                StatePatch {
                    pinned: None,
                    trashed: Some(true),
                    pinned_at_ms: TimestampPatch::Keep,
                    trashed_at_ms: TimestampPatch::Set(now),
                    tags: None,
                },
            )?;
        }
        MemoCommandKind::Restore => {
            connection
                .execute(
                    "DELETE FROM memo_trash WHERE memo_id = ?1",
                    params![intent.memo_id],
                )
                .map_err(|err| from_sqlite(&err))?;
            merge_write_state(
                paths,
                &intent.memo_id,
                StatePatch {
                    pinned: None,
                    trashed: Some(false),
                    pinned_at_ms: TimestampPatch::Keep,
                    trashed_at_ms: TimestampPatch::Clear,
                    tags: None,
                },
            )?;
        }
        MemoCommandKind::Pin => {
            connection
                .execute(
                    "INSERT OR REPLACE INTO memo_pin(memo_id, pinned_at_ms) VALUES(?1, ?2)",
                    params![intent.memo_id, now],
                )
                .map_err(|err| from_sqlite(&err))?;
            // Read-merge: pin must not clear trash (or tags).
            merge_write_state(
                paths,
                &intent.memo_id,
                StatePatch {
                    pinned: Some(true),
                    trashed: None,
                    pinned_at_ms: TimestampPatch::Set(now),
                    trashed_at_ms: TimestampPatch::Keep,
                    tags: None,
                },
            )?;
        }
        MemoCommandKind::Unpin => {
            connection
                .execute(
                    "DELETE FROM memo_pin WHERE memo_id = ?1",
                    params![intent.memo_id],
                )
                .map_err(|err| from_sqlite(&err))?;
            merge_write_state(
                paths,
                &intent.memo_id,
                StatePatch {
                    pinned: Some(false),
                    trashed: None,
                    pinned_at_ms: TimestampPatch::Clear,
                    trashed_at_ms: TimestampPatch::Keep,
                    tags: None,
                },
            )?;
        }
    }
    recompute_stats(connection)?;
    Ok(())
}

fn upsert_tag(
    connection: &Connection,
    memo_id: &str,
    tag: &str,
) -> Result<(), lomo_core::LomoError> {
    connection
        .execute("INSERT OR IGNORE INTO tag(name) VALUES(?1)", params![tag])
        .map_err(|err| from_sqlite(&err))?;
    let tag_id: i64 = connection
        .query_row("SELECT id FROM tag WHERE name = ?1", params![tag], |row| {
            row.get(0)
        })
        .map_err(|err| from_sqlite(&err))?;
    connection
        .execute(
            "INSERT OR IGNORE INTO memo_tag(memo_id, tag_id) VALUES(?1, ?2)",
            params![memo_id, tag_id],
        )
        .map_err(|err| from_sqlite(&err))?;
    Ok(())
}

fn replace_attachments(
    connection: &Connection,
    memo_id: &str,
    paths: &[String],
) -> Result<(), lomo_core::LomoError> {
    connection
        .execute(
            "DELETE FROM attachment_ref WHERE memo_id = ?1",
            params![memo_id],
        )
        .map_err(|err| from_sqlite(&err))?;
    for path in paths {
        if path.is_empty() || path.len() > 1024 {
            return Err(validation(
                "invalid_attachment_path",
                "attachment relative path is empty or too long",
            ));
        }
        connection
            .execute(
                "INSERT OR IGNORE INTO attachment_ref(memo_id, relative_path) VALUES(?1, ?2)",
                params![memo_id, path],
            )
            .map_err(|err| from_sqlite(&err))?;
    }
    Ok(())
}

/// How a timestamp field should be updated during a durable state merge.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum TimestampPatch {
    Keep,
    Clear,
    Set(i64),
}

impl TimestampPatch {
    const fn apply(self, prior: Option<i64>) -> Option<i64> {
        match self {
            Self::Keep => prior,
            Self::Clear => None,
            Self::Set(value) => Some(value),
        }
    }
}

/// Partial update to durable pin/trash/tag state. `None` fields preserve prior values.
struct StatePatch {
    pinned: Option<bool>,
    trashed: Option<bool>,
    pinned_at_ms: TimestampPatch,
    trashed_at_ms: TimestampPatch,
    tags: Option<Vec<String>>,
}

/// Reads prior durable state (if any) and merges `patch` so pin/trash/tags do not clobber each other.
fn merge_write_state(
    paths: &LomoPaths,
    memo_id: &str,
    patch: StatePatch,
) -> Result<(), lomo_core::LomoError> {
    let state_path = paths.state.join(format!("{memo_id}.rec"));
    let prior = if state_path.exists() {
        match read_record(&state_path) {
            Ok(record) if record.payload.kind == LomoRecordKind::State => {
                serde_json::from_str::<StateBody>(&record.payload.body_json).map_err(|err| {
                    crate::error::corruption(
                        "lomo_state_payload_invalid",
                        &format!("cannot decode prior state for merge: {err}"),
                    )
                })?
            }
            Ok(_other) => {
                return Err(crate::error::corruption(
                    "lomo_state_kind_mismatch",
                    "state path does not contain a State record",
                ));
            }
            Err(err) => return Err(err),
        }
    } else {
        StateBody {
            memo_id: memo_id.to_owned(),
            pinned: false,
            trashed: false,
            pinned_at_ms: None,
            trashed_at_ms: None,
            tags: Vec::new(),
        }
    };

    let body = StateBody {
        memo_id: memo_id.to_owned(),
        pinned: patch.pinned.unwrap_or(prior.pinned),
        trashed: patch.trashed.unwrap_or(prior.trashed),
        pinned_at_ms: patch.pinned_at_ms.apply(prior.pinned_at_ms),
        trashed_at_ms: patch.trashed_at_ms.apply(prior.trashed_at_ms),
        tags: match patch.tags {
            Some(tags) => tags,
            None => prior.tags,
        },
    };
    let body_json = serde_json::to_string(&body).map_err(|err| {
        validation(
            "state_encode_failed",
            &format!("cannot encode state: {err}"),
        )
    })?;
    write_record_atomic(
        &state_path,
        &LomoPayload {
            kind: LomoRecordKind::State,
            record_id: memo_id.to_owned(),
            body_json,
        },
    )
}

fn scopes_for(kind: MemoCommandKind) -> Vec<InvalidationScope> {
    match kind {
        MemoCommandKind::Create | MemoCommandKind::Update | MemoCommandKind::HistoryRestore => {
            vec![
                InvalidationScope::MemoList,
                InvalidationScope::Search,
                InvalidationScope::Tags,
                InvalidationScope::Stats,
            ]
        }
        MemoCommandKind::Delete | MemoCommandKind::Restore => {
            vec![
                InvalidationScope::MemoList,
                InvalidationScope::Trash,
                InvalidationScope::Search,
                InvalidationScope::Stats,
            ]
        }
        MemoCommandKind::Pin | MemoCommandKind::Unpin => {
            vec![
                InvalidationScope::MemoList,
                InvalidationScope::Pin,
                InvalidationScope::Stats,
            ]
        }
    }
}

fn persist_intent(path: &Path, intent: &OperationIntent) -> Result<(), lomo_core::LomoError> {
    let body_json = serde_json::to_string(intent).map_err(|err| {
        validation(
            "operation_intent_encode_failed",
            &format!("cannot encode intent: {err}"),
        )
    })?;
    write_record_atomic(
        path,
        &LomoPayload {
            kind: LomoRecordKind::Operation,
            record_id: intent.operation_id.clone(),
            body_json,
        },
    )
}

fn operation_path(paths: &LomoPaths, operation_id: &str) -> PathBuf {
    paths.operations.join(format!("{operation_id}.rec"))
}

fn memo_file_path(workspace_root: &Path, memo_id: &str) -> PathBuf {
    workspace_root.join("memos").join(format!("{memo_id}.md"))
}

fn atomic_write_text(path: &Path, content: &str) -> Result<(), lomo_core::LomoError> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|err| {
            storage(
                "memo_dir_create_failed",
                &format!("cannot create memo dir: {err}"),
            )
        })?;
    }
    let tmp = path.with_extension("md.tmp");
    std::fs::write(&tmp, content).map_err(|err| {
        storage(
            "memo_temp_write_failed",
            &format!("cannot write temp memo: {err}"),
        )
    })?;
    let file = std::fs::File::open(&tmp).map_err(|err| {
        storage(
            "memo_temp_open_failed",
            &format!("cannot reopen temp memo: {err}"),
        )
    })?;
    file.sync_all().map_err(|err| {
        storage(
            "memo_temp_fsync_failed",
            &format!("cannot fsync temp memo: {err}"),
        )
    })?;
    std::fs::rename(&tmp, path).map_err(|err| {
        storage(
            "memo_rename_failed",
            &format!("cannot rename temp memo into place: {err}"),
        )
    })?;
    Ok(())
}

#[expect(
    clippy::manual_unwrap_or_default,
    clippy::option_if_let_else,
    reason = "unwrap_or_default is disallowed by clippy.toml; explicit empty string is intentional"
)]
fn option_string(value: Option<String>) -> String {
    match value {
        Some(inner) => inner,
        None => String::new(),
    }
}

// fingerprint_content lives in content_facts (shared with rebuild compare digests).

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}

fn maybe_crash(
    injected: Option<CrashPoint>,
    point: CrashPoint,
    code: &str,
) -> Result<(), lomo_core::LomoError> {
    if injected == Some(point) {
        return Err(storage(code, "injected crash point for recovery matrix"));
    }
    Ok(())
}

fn write_meta_u64(
    connection: &Connection,
    key: &str,
    value: u64,
) -> Result<(), lomo_core::LomoError> {
    connection
        .execute(
            "INSERT INTO store_meta(key, value) VALUES(?1, ?2) \
             ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            params![key, value.to_string()],
        )
        .map_err(|err| from_sqlite(&err))?;
    Ok(())
}

/// Step 9: remove committed operation logs older than `retain_ms` (explicit deferred cleanup).
///
/// # Errors
///
/// Storage errors when directory listing or deletion fails. Never deletes non-committed intents.
pub fn cleanup_expired_operations(
    workspace_root: &Path,
    retain_ms: u64,
) -> Result<usize, lomo_core::LomoError> {
    let paths = LomoPaths::for_workspace(workspace_root);
    if !paths.operations.exists() {
        return Ok(0);
    }
    let cutoff = now_ms().saturating_sub(i64::try_from(retain_ms).unwrap_or(i64::MAX));
    let mut removed = 0usize;
    for entry in std::fs::read_dir(&paths.operations).map_err(|err| {
        storage(
            "operations_list_failed",
            &format!("cannot list operations: {err}"),
        )
    })? {
        let entry = entry.map_err(|err| {
            storage(
                "operations_list_failed",
                &format!("cannot read operations entry: {err}"),
            )
        })?;
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("rec") {
            continue;
        }
        let meta = entry.metadata().map_err(|err| {
            storage(
                "operations_meta_failed",
                &format!("cannot stat operation: {err}"),
            )
        })?;
        let modified = meta.modified().map_or(0, |time| {
            time.duration_since(UNIX_EPOCH).map_or(0, |duration| {
                i64::try_from(duration.as_millis()).unwrap_or(0)
            })
        });
        if modified > cutoff {
            continue;
        }
        if let Ok(record) = read_record(&path)
            && let Ok(intent) = serde_json::from_str::<OperationIntent>(&record.payload.body_json)
            && intent.status == OperationStatus::Committed
        {
            std::fs::remove_file(&path).map_err(|err| {
                storage(
                    "operations_cleanup_failed",
                    &format!("cannot remove committed op: {err}"),
                )
            })?;
            removed += 1;
        }
        // Corrupt records are not auto-deleted; isolation is a separate path.
    }
    Ok(removed)
}
