//! Crash-safe provider-backed trash command driver.
//!
//! Soft delete commits the durable trash record while leaving the shared Markdown document intact.
//! Restore removes only a validated matching record. Permanent delete first removes the memo from
//! the active document and only then deletes the record, so every crash prefix remains recoverable.

use std::time::{SystemTime, UNIX_EPOCH};

use lomo_core::{
    ActionEvidence, DriverAdvance, DriverStart, ExchangeArtifact, ExchangeToken,
    ExpectedFingerprint, JobDriver, JobDriverContext, LomoError, PlatformAction,
    PlatformActionBatch, PlatformActionOutput, PlatformBatchResult, Sha256Digest, WorkspaceTarget,
    WriteMode,
};
use serde::{Deserialize, Serialize};

use crate::limits::{conflict, validation};
use crate::parse::parse_workspace_document;
use crate::patch::{DocumentPatchCommand, plan_document_patch};
use crate::source::{SourceBytes, SourceFingerprint};
use crate::trash::{
    TRASH_RECORD_DIRECTORY, TrashRecordCreate, TrashRecordV1, decode_trash_record,
    encode_trash_record, trash_record_relative_path,
};
use crate::types::{MemoIdentity, WorkspaceRelativePath};

use super::document::{DocumentMemoFacts, memo_facts};
use super::shared::{
    exchange_token_for, filename_stem, first_applied_output, plan_read, read_exchange_bytes,
    read_to_exchange_output, source_fingerprint_of, to_core_path, write_complete_output,
    write_exchange_bytes,
};

pub const TRASH_COMMAND_DRIVER_KIND: &str = "workspace-trash-command-v1";

#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
pub struct TrashCommandRequest {
    pub path: String,
    pub expected_fingerprint: String,
    pub command: TrashCommandKind,
}

#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum TrashCommandKind {
    Trash {
        identity: String,
        chronology_epoch_ms: i64,
    },
    Restore {
        identity: String,
    },
    PermanentDelete {
        identity: String,
    },
}

impl TrashCommandKind {
    fn identity(&self) -> &str {
        match self {
            Self::Trash { identity, .. }
            | Self::Restore { identity }
            | Self::PermanentDelete { identity } => identity,
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
pub struct TrashCommandResult {
    pub path: String,
    pub result_fingerprint: String,
    pub affected_memo: DocumentMemoFacts,
    pub trashed_at_ms: Option<i64>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
struct TrashCommandState {
    path: String,
    expected_fingerprint: String,
    command: TrashCommandKind,
    phase: TrashCommandPhase,
    source_read_token: String,
    marker_read_token: Option<String>,
    marker_write_token: Option<String>,
    source_write_token: Option<String>,
    marker_write_length: Option<u64>,
    marker_write_digest: Option<String>,
    source_write_length: Option<u64>,
    source_write_digest: Option<String>,
    result_fingerprint: Option<String>,
    marker_evidence: Option<ActionEvidence>,
    affected_memo: Option<DocumentMemoFacts>,
    trashed_at_ms: Option<i64>,
}

#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
enum TrashCommandPhase {
    Read,
    WriteMarker,
    WriteSource,
    DeleteMarker,
}

pub struct TrashCommandDriver;

struct VerifiedSourceRead {
    evidence: ActionEvidence,
    fingerprint: SourceFingerprint,
    document: crate::WorkspaceDocument,
}

struct VerifiedTrashMarker {
    evidence: ActionEvidence,
    record: TrashRecordV1,
}

impl JobDriver for TrashCommandDriver {
    fn kind(&self) -> &'static str {
        TRASH_COMMAND_DRIVER_KIND
    }

    fn recover_canonical_request_json(
        &self,
        state_json: &str,
    ) -> Result<Option<String>, LomoError> {
        let state = decode_state(state_json)?;
        serde_json::to_string(&TrashCommandRequest {
            path: state.path,
            expected_fingerprint: state.expected_fingerprint,
            command: state.command,
        })
        .map(Some)
        .map_err(|_error| {
            validation(
                "trash_command_state_invalid",
                "trash command request identity cannot be reconstructed",
            )
        })
    }

    fn terminal_cleanup_exchange_artifacts(
        &self,
        state_json: &str,
    ) -> Result<Vec<ExchangeToken>, LomoError> {
        let state = decode_state(state_json)?;
        std::iter::once(state.source_read_token)
            .chain(state.marker_read_token)
            .chain(state.marker_write_token)
            .chain(state.source_write_token)
            .map(|token| ExchangeToken::parse(&token))
            .collect()
    }

    fn start(
        &self,
        ctx: &mut JobDriverContext<'_>,
        request_json: &str,
    ) -> Result<DriverStart, LomoError> {
        let request: TrashCommandRequest =
            serde_json::from_str(request_json).map_err(|_error| {
                validation(
                    "invalid_trash_command_request",
                    "workspace trash command request JSON is invalid",
                )
            })?;
        let path = WorkspaceRelativePath::parse(&request.path)?;
        let _expected = SourceFingerprint::parse(&request.expected_fingerprint)?;
        let _identity = MemoIdentity::parse(request.command.identity())?;
        let _marker_path = trash_record_relative_path(request.command.identity())?;
        if let TrashCommandKind::Trash {
            chronology_epoch_ms,
            ..
        } = request.command
            && chronology_epoch_ms <= 0
        {
            return Err(validation(
                "invalid_trash_chronology",
                "trash memo chronology must be a positive epoch millisecond",
            ));
        }

        let source_read_token = exchange_token_for(
            ctx.workspace.identity().as_str(),
            ctx.job_id.as_str(),
            "trash-source-read",
        );
        let source_read = plan_read(
            ctx.next_action_id("trash-source-read")?,
            ctx.capability(),
            to_core_path(&path)?,
            &source_read_token,
            ExpectedFingerprint::absent(),
        )?;
        let marker_read_token = if matches!(
            request.command,
            TrashCommandKind::Restore { .. } | TrashCommandKind::PermanentDelete { .. }
        ) {
            Some(exchange_token_for(
                ctx.workspace.identity().as_str(),
                ctx.job_id.as_str(),
                "trash-marker-read",
            ))
        } else {
            None
        };
        let mut actions = vec![source_read];
        if let Some(token) = marker_read_token.as_deref() {
            let marker_path = trash_record_relative_path(request.command.identity())?;
            actions.push(plan_read(
                ctx.next_action_id("trash-marker-read")?,
                ctx.capability(),
                to_core_path(&marker_path)?,
                token,
                ExpectedFingerprint::absent(),
            )?);
        }
        let state = TrashCommandState {
            path: request.path,
            expected_fingerprint: request.expected_fingerprint,
            command: request.command,
            phase: TrashCommandPhase::Read,
            source_read_token,
            marker_read_token,
            marker_write_token: None,
            source_write_token: None,
            marker_write_length: None,
            marker_write_digest: None,
            source_write_length: None,
            source_write_digest: None,
            result_fingerprint: None,
            marker_evidence: None,
            affected_memo: None,
            trashed_at_ms: None,
        };
        Ok(DriverStart {
            state_json: encode_state(&state)?,
            actions,
            result_json: None,
        })
    }

    fn advance(
        &self,
        ctx: &mut JobDriverContext<'_>,
        state_json: &str,
        batch: &PlatformActionBatch,
        result: &PlatformBatchResult,
    ) -> Result<DriverAdvance, LomoError> {
        let mut state = decode_state(state_json)?;
        match state.phase {
            TrashCommandPhase::Read => advance_after_read(ctx, &mut state, batch, result),
            TrashCommandPhase::WriteMarker => advance_after_marker_write(&state, batch, result),
            TrashCommandPhase::WriteSource => {
                advance_after_source_write(ctx, &mut state, batch, result)
            }
            TrashCommandPhase::DeleteMarker => advance_after_marker_delete(&state, batch, result),
        }
    }
}

fn advance_after_read(
    ctx: &mut JobDriverContext<'_>,
    state: &mut TrashCommandState,
    batch: &PlatformActionBatch,
    result: &PlatformBatchResult,
) -> Result<DriverAdvance, LomoError> {
    let source = verified_source_read(ctx, state, batch, result)?;
    match state.command.clone() {
        TrashCommandKind::Trash {
            identity,
            chronology_epoch_ms,
        } => plan_soft_delete(ctx, state, &source, &identity, chronology_epoch_ms),
        TrashCommandKind::Restore { identity } => {
            let marker = verified_trash_marker(ctx, state, batch, result, &identity)?;
            state.marker_evidence = Some(marker.evidence);
            state.trashed_at_ms = Some(marker.record.trashed_at_ms);
            plan_restore(ctx, state, &source, &marker.record, &identity)
        }
        TrashCommandKind::PermanentDelete { identity } => {
            let marker = verified_trash_marker(ctx, state, batch, result, &identity)?;
            state.marker_evidence = Some(marker.evidence);
            state.trashed_at_ms = Some(marker.record.trashed_at_ms);
            plan_permanent_delete(ctx, state, source, &marker.record, &identity)
        }
    }
}

fn verified_source_read(
    ctx: &JobDriverContext<'_>,
    state: &TrashCommandState,
    batch: &PlatformActionBatch,
    result: &PlatformBatchResult,
) -> Result<VerifiedSourceRead, LomoError> {
    let source_output = first_applied_output(batch, result, 0)?;
    let (source_metadata, source_artifact) = read_to_exchange_output(source_output)?;
    if source_artifact.token().as_str() != state.source_read_token {
        return Err(validation(
            "trash_source_exchange_token_mismatch",
            "trash source read token does not match the planned token",
        ));
    }
    let source_bytes = read_exchange_bytes(ctx.exchange_root, &state.source_read_token)?;
    let source_fingerprint = source_fingerprint_of(&source_bytes);
    if source_fingerprint.as_str() != state.expected_fingerprint {
        return Err(conflict(
            "stale_snapshot",
            "trash command source fingerprint does not match the expected snapshot",
        ));
    }
    let source = SourceBytes::try_from_bytes(source_bytes)?;
    let stem = filename_stem(&state.path)?;
    let document = parse_workspace_document(&source, &stem)?;
    Ok(VerifiedSourceRead {
        evidence: source_metadata.evidence().clone(),
        fingerprint: source_fingerprint,
        document,
    })
}

fn verified_trash_marker(
    ctx: &JobDriverContext<'_>,
    state: &TrashCommandState,
    batch: &PlatformActionBatch,
    result: &PlatformBatchResult,
    identity: &str,
) -> Result<VerifiedTrashMarker, LomoError> {
    let marker_output = first_applied_output(batch, result, 1)?;
    let (marker_metadata, marker_artifact) = read_to_exchange_output(marker_output)?;
    let expected_token = state.marker_read_token.as_deref().ok_or_else(|| {
        validation(
            "trash_marker_read_token_missing",
            "restore/permanent delete requires a marker read token",
        )
    })?;
    if marker_artifact.token().as_str() != expected_token {
        return Err(validation(
            "trash_marker_exchange_token_mismatch",
            "trash marker read token does not match the planned token",
        ));
    }
    let record = decode_trash_record(&read_exchange_bytes(ctx.exchange_root, expected_token)?)?;
    validate_record_target(&record, &state.path, identity)?;
    Ok(VerifiedTrashMarker {
        evidence: marker_metadata.evidence().clone(),
        record,
    })
}

fn plan_soft_delete(
    ctx: &mut JobDriverContext<'_>,
    state: &mut TrashCommandState,
    source: &VerifiedSourceRead,
    identity: &str,
    chronology_epoch_ms: i64,
) -> Result<DriverAdvance, LomoError> {
    let memo = require_target_memo(&source.document, identity)?;
    let facts = memo_facts(&state.path, source.fingerprint.as_str(), memo);
    let trashed_at_ms = current_time_ms()?;
    let record = TrashRecordV1::try_new(TrashRecordCreate {
        memo_id: facts.identity.clone(),
        source_path: facts.path.clone(),
        time_part: facts.time_part.clone(),
        source_fingerprint: facts.fingerprint.clone(),
        chronology_epoch_ms,
        trashed_at_ms,
        body: memo.content().to_owned(),
        tags: facts.tags.clone(),
        attachments: facts.attachments.clone(),
        reminders: facts.reminders.clone(),
        has_todo: facts.has_todo,
        has_url: facts.has_url,
    })?;
    let marker_bytes = encode_trash_record(&record)?;
    let marker_write_token = exchange_token_for(
        ctx.workspace.identity().as_str(),
        ctx.job_id.as_str(),
        "trash-marker-write",
    );
    let (length, digest) =
        write_exchange_bytes(ctx.exchange_root, &marker_write_token, &marker_bytes)?;
    let artifact =
        ExchangeArtifact::new(&marker_write_token, length, Sha256Digest::parse(&digest)?)?;
    let marker_path = trash_record_relative_path(identity)?;
    let directory = WorkspaceRelativePath::parse(TRASH_RECORD_DIRECTORY)?;
    state.phase = TrashCommandPhase::WriteMarker;
    state.marker_write_token = Some(marker_write_token);
    state.marker_write_length = Some(length);
    state.marker_write_digest = Some(digest);
    state.result_fingerprint = Some(source.fingerprint.as_str().to_owned());
    state.affected_memo = Some(facts);
    state.trashed_at_ms = Some(trashed_at_ms);
    Ok(DriverAdvance::NeedsBatch {
        state_json: encode_state(state)?,
        actions: vec![
            PlatformAction::ensure_directory(
                ctx.next_action_id("trash-directory")?,
                ctx.capability(),
                to_core_path(&directory)?,
            ),
            PlatformAction::write_from_exchange(
                ctx.next_action_id("trash-marker-write")?,
                ctx.capability(),
                artifact,
                to_core_path(&marker_path)?,
                WriteMode::Replace,
                ExpectedFingerprint::absent(),
            ),
        ],
        result_json: None,
    })
}

fn plan_restore(
    ctx: &mut JobDriverContext<'_>,
    state: &mut TrashCommandState,
    source: &VerifiedSourceRead,
    record: &TrashRecordV1,
    identity: &str,
) -> Result<DriverAdvance, LomoError> {
    let memo = require_target_memo(&source.document, identity).map_err(|_error| {
        validation(
            "trash_restore_source_missing",
            "restore requires the deleted memo snapshot to remain in its source document",
        )
    })?;
    validate_record_matches_memo(record, memo)?;
    state.affected_memo = Some(memo_facts(&state.path, source.fingerprint.as_str(), memo));
    state.result_fingerprint = Some(source.fingerprint.as_str().to_owned());
    plan_marker_delete(ctx, state, identity)
}

fn plan_permanent_delete(
    ctx: &mut JobDriverContext<'_>,
    state: &mut TrashCommandState,
    source: VerifiedSourceRead,
    record: &TrashRecordV1,
    identity: &str,
) -> Result<DriverAdvance, LomoError> {
    state.affected_memo = Some(record_to_facts(record, source.fingerprint.as_str()));
    let Some(memo) = source
        .document
        .memos()
        .iter()
        .find(|memo| memo.identity().as_str() == identity)
    else {
        state.result_fingerprint = Some(source.fingerprint.as_str().to_owned());
        return plan_marker_delete(ctx, state, identity);
    };
    validate_record_matches_memo(record, memo)?;
    let path = WorkspaceRelativePath::parse(&state.path)?;
    let plan = plan_document_patch(
        &source.document,
        &DocumentPatchCommand::Remove {
            path: path.clone(),
            expected_fingerprint: source.fingerprint,
            identity: MemoIdentity::parse(identity)?,
        },
    )?;
    let source_write_token = exchange_token_for(
        ctx.workspace.identity().as_str(),
        ctx.job_id.as_str(),
        "trash-source-write",
    );
    let (length, digest) =
        write_exchange_bytes(ctx.exchange_root, &source_write_token, plan.result_bytes())?;
    let artifact =
        ExchangeArtifact::new(&source_write_token, length, Sha256Digest::parse(&digest)?)?;
    state.phase = TrashCommandPhase::WriteSource;
    state.source_write_token = Some(source_write_token);
    state.source_write_length = Some(length);
    state.source_write_digest = Some(digest);
    state.result_fingerprint = Some(plan.result_fingerprint().as_str().to_owned());
    Ok(DriverAdvance::NeedsBatch {
        state_json: encode_state(state)?,
        actions: vec![PlatformAction::write_from_exchange(
            ctx.next_action_id("trash-source-write")?,
            ctx.capability(),
            artifact,
            to_core_path(&path)?,
            WriteMode::Replace,
            ExpectedFingerprint::matching(source.evidence),
        )],
        result_json: None,
    })
}

fn advance_after_marker_write(
    state: &TrashCommandState,
    batch: &PlatformActionBatch,
    result: &PlatformBatchResult,
) -> Result<DriverAdvance, LomoError> {
    let directory_output = first_applied_output(batch, result, 0)?;
    if !matches!(
        directory_output,
        PlatformActionOutput::DirectoryReady { .. }
    ) {
        return Err(validation(
            "trash_directory_postcondition_unproven",
            "trash marker parent directory was not verified",
        ));
    }
    let metadata = write_complete_output(first_applied_output(batch, result, 1)?)?;
    require_write_postcondition(
        metadata.evidence(),
        state.marker_write_length,
        state.marker_write_digest.as_deref(),
        "trash marker",
    )?;
    completed_result(state, true)
}

fn advance_after_source_write(
    ctx: &mut JobDriverContext<'_>,
    state: &mut TrashCommandState,
    batch: &PlatformActionBatch,
    result: &PlatformBatchResult,
) -> Result<DriverAdvance, LomoError> {
    let metadata = write_complete_output(first_applied_output(batch, result, 0)?)?;
    require_write_postcondition(
        metadata.evidence(),
        state.source_write_length,
        state.source_write_digest.as_deref(),
        "permanent delete source",
    )?;
    let identity = state.command.identity().to_owned();
    plan_marker_delete(ctx, state, &identity)
}

fn plan_marker_delete(
    ctx: &mut JobDriverContext<'_>,
    state: &mut TrashCommandState,
    identity: &str,
) -> Result<DriverAdvance, LomoError> {
    let evidence = state.marker_evidence.clone().ok_or_else(|| {
        validation(
            "trash_marker_evidence_missing",
            "trash marker delete requires verified read evidence",
        )
    })?;
    let marker_path = trash_record_relative_path(identity)?;
    state.phase = TrashCommandPhase::DeleteMarker;
    Ok(DriverAdvance::NeedsBatch {
        state_json: encode_state(state)?,
        actions: vec![PlatformAction::delete(
            ctx.next_action_id("trash-marker-delete")?,
            ctx.capability(),
            to_core_path(&marker_path)?,
            ExpectedFingerprint::matching(evidence),
        )],
        result_json: None,
    })
}

fn advance_after_marker_delete(
    state: &TrashCommandState,
    batch: &PlatformActionBatch,
    result: &PlatformBatchResult,
) -> Result<DriverAdvance, LomoError> {
    let output = first_applied_output(batch, result, 0)?;
    let PlatformActionOutput::DeleteComplete { absence } = output else {
        return Err(validation(
            "trash_marker_delete_postcondition_unproven",
            "trash marker deletion did not publish verified absence",
        ));
    };
    let expected_path = trash_record_relative_path(state.command.identity())?;
    if absence.target() != &WorkspaceTarget::Relative(to_core_path(&expected_path)?) {
        return Err(validation(
            "trash_marker_delete_target_mismatch",
            "trash marker deletion verified a different path",
        ));
    }
    completed_result(state, false)
}

fn completed_result(
    state: &TrashCommandState,
    keep_trashed_timestamp: bool,
) -> Result<DriverAdvance, LomoError> {
    let payload = TrashCommandResult {
        path: state.path.clone(),
        result_fingerprint: state.result_fingerprint.clone().ok_or_else(|| {
            validation(
                "trash_result_fingerprint_missing",
                "trash command completed without a result fingerprint",
            )
        })?,
        affected_memo: state.affected_memo.clone().ok_or_else(|| {
            validation(
                "trash_affected_memo_missing",
                "trash command completed without affected memo facts",
            )
        })?,
        trashed_at_ms: keep_trashed_timestamp
            .then_some(state.trashed_at_ms)
            .flatten(),
    };
    Ok(DriverAdvance::Done {
        result_json: serde_json::to_string(&payload).map_err(|_error| {
            validation(
                "trash_result_encode_failed",
                "trash command result cannot be serialized",
            )
        })?,
    })
}

fn require_target_memo<'a>(
    document: &'a crate::WorkspaceDocument,
    identity: &str,
) -> Result<&'a crate::WorkspaceMemo, LomoError> {
    document
        .memos()
        .iter()
        .find(|memo| memo.identity().as_str() == identity)
        .ok_or_else(|| {
            validation(
                "memo_identity_not_found",
                "trash command target identity is absent from the source document",
            )
        })
}

fn validate_record_target(
    record: &TrashRecordV1,
    path: &str,
    identity: &str,
) -> Result<(), LomoError> {
    if record.memo_id != identity || record.source_path != path {
        return Err(validation(
            "trash_record_target_mismatch",
            "trash record does not belong to the requested memo source",
        ));
    }
    Ok(())
}

fn validate_record_matches_memo(
    record: &TrashRecordV1,
    memo: &crate::WorkspaceMemo,
) -> Result<(), LomoError> {
    if record.memo_id != memo.identity().as_str()
        || record.time_part != memo.time_part()
        || record.body != memo.content()
    {
        return Err(validation(
            "trash_snapshot_conflict",
            "active memo bytes no longer match the recoverable trash snapshot",
        ));
    }
    Ok(())
}

fn record_to_facts(record: &TrashRecordV1, fingerprint: &str) -> DocumentMemoFacts {
    DocumentMemoFacts {
        path: record.source_path.clone(),
        identity: record.memo_id.clone(),
        time_part: record.time_part.clone(),
        fingerprint: fingerprint.to_owned(),
        tags: record.tags.clone(),
        attachments: record.attachments.clone(),
        reminders: record.reminders.clone(),
        has_todo: record.has_todo,
        has_url: record.has_url,
        content: None,
    }
}

fn require_write_postcondition(
    evidence: &ActionEvidence,
    expected_length: Option<u64>,
    expected_digest: Option<&str>,
    label: &str,
) -> Result<(), LomoError> {
    if Some(evidence.length()) != expected_length
        || Some(evidence.digest().as_str()) != expected_digest
    {
        return Err(validation(
            "trash_write_postcondition_unproven",
            &format!("{label} bytes do not match the planned artifact"),
        ));
    }
    Ok(())
}

fn current_time_ms() -> Result<i64, LomoError> {
    let duration = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_error| {
            validation(
                "system_clock_before_epoch",
                "system wall clock is before the Unix epoch",
            )
        })?;
    i64::try_from(duration.as_millis()).map_err(|_error| {
        validation(
            "trash_timestamp_overflow",
            "system wall clock exceeds the trash timestamp representation",
        )
    })
}

fn encode_state(state: &TrashCommandState) -> Result<String, LomoError> {
    serde_json::to_string(state).map_err(|_error| {
        validation(
            "trash_command_state_invalid",
            "trash command durable state cannot be serialized",
        )
    })
}

fn decode_state(state_json: &str) -> Result<TrashCommandState, LomoError> {
    serde_json::from_str(state_json).map_err(|_error| {
        validation(
            "trash_command_state_invalid",
            "trash command durable state is invalid",
        )
    })
}
