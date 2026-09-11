//! Workspace document-command multi-phase job driver.
//!
//! Flow: read path to exchange → fingerprint + parse + pure patch plan → write patched bytes to a
//! private exchange artifact → `WriteFromExchange` with expected target fingerprint → optional verify
//! stat. Fail closed on stale snapshots; replay uses `AlreadySatisfied` postconditions without a second
//! mutating write plan.

use lomo_core::{
    DriverAdvance, DriverStart, ExchangeArtifact, ExchangeToken, ExpectedFingerprint, JobDriver,
    JobDriverContext, LomoError, PlatformAction, PlatformActionBatch, PlatformBatchResult,
    Sha256Digest, WriteMode,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::limits::{conflict, validation};
use crate::lomo_record::{
    HistorySnapshotV1, LomoPayload, LomoRecordKind, encode_record, hex_encode,
    history_record_filename,
};
use crate::parse::parse_workspace_document;
use crate::patch::{DocumentPatchCommand, plan_document_patch};
use crate::reminder::{ReminderRef, ReminderReference};
use crate::source::{SourceBytes, SourceFingerprint};
use crate::types::{MemoIdentity, WorkspaceRelativePath};

use super::shared::{
    exchange_token_for, filename_stem, first_applied_output, plan_read, read_exchange_bytes,
    read_to_exchange_output, source_fingerprint_of, to_core_path, write_complete_output,
    write_exchange_bytes,
};

pub const DOCUMENT_COMMAND_DRIVER_KIND: &str = "workspace-document-command-v1";

/// Document command request accepted by the engine driver (JSON).
#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
pub struct DocumentCommandRequest {
    pub path: String,
    pub expected_state: DocumentExpectedState,
    pub command: DocumentCommandKind,
    #[serde(default)]
    pub history: Option<DocumentHistoryWrite>,
}

#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
pub struct DocumentHistoryWrite {
    pub revision: u64,
    pub created_at_ms: i64,
}

#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum DocumentExpectedState {
    Absent,
    Match { fingerprint: String },
}

#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum DocumentCommandKind {
    Create {
        time_part: String,
        content: String,
    },
    Append {
        time_part: String,
        content: String,
    },
    Replace {
        identity: String,
        content: String,
    },
    Remove {
        identity: String,
    },
    ToggleTask {
        identity: String,
        body_start: u64,
        body_end: u64,
    },
    RewriteReminder {
        reminder: ReminderReference,
        replacement: String,
    },
}

#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
pub struct DocumentCommandResult {
    pub path: String,
    pub result_fingerprint: String,
    pub bytes_written: u64,
    pub affected_memo: Option<DocumentMemoFacts>,
}

#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
pub struct DocumentMemoFacts {
    pub path: String,
    pub identity: String,
    pub time_part: String,
    pub fingerprint: String,
    pub tags: Vec<String>,
    pub attachments: Vec<String>,
    pub reminders: Vec<ReminderReference>,
    pub has_todo: bool,
    pub has_url: bool,
    /// Post-command memo body when the facts were projected from a parsed document; record-decoded
    /// trash facts carry no body bytes and stay `None` instead of inventing empty content.
    #[serde(default)]
    pub content: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
struct DocumentState {
    path: String,
    expected_fingerprint: Option<String>,
    command: DocumentCommandKind,
    #[serde(default)]
    history: Option<DocumentHistoryWrite>,
    phase: DocumentPhase,
    read_token: Option<String>,
    write_token: Option<String>,
    write_length: Option<u64>,
    write_digest: Option<String>,
    result_fingerprint: Option<String>,
    #[serde(default)]
    history_token: Option<String>,
    #[serde(default)]
    history_path: Option<String>,
    #[serde(default)]
    history_length: Option<u64>,
    #[serde(default)]
    history_digest: Option<String>,
    /// Snapshot of source evidence from the successful read (for `expected_target` Match).
    source_evidence_length: Option<u64>,
    source_evidence_digest: Option<String>,
    source_evidence_fingerprint: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
enum DocumentPhase {
    Read,
    Write,
    Done,
}

pub struct DocumentCommandDriver;

impl JobDriver for DocumentCommandDriver {
    fn kind(&self) -> &'static str {
        DOCUMENT_COMMAND_DRIVER_KIND
    }

    fn recover_canonical_request_json(
        &self,
        state_json: &str,
    ) -> Result<Option<String>, LomoError> {
        let state: DocumentState = serde_json::from_str(state_json).map_err(|_error| {
            validation(
                "document_command_state_invalid",
                "document command durable state is invalid",
            )
        })?;
        let expected_state = state
            .expected_fingerprint
            .map_or(DocumentExpectedState::Absent, |fingerprint| {
                DocumentExpectedState::Match { fingerprint }
            });
        let request = DocumentCommandRequest {
            path: state.path,
            expected_state,
            command: state.command,
            history: state.history,
        };
        serde_json::to_string(&request).map(Some).map_err(|_error| {
            validation(
                "document_command_state_invalid",
                "document command request identity cannot be reconstructed",
            )
        })
    }

    fn terminal_cleanup_exchange_artifacts(
        &self,
        state_json: &str,
    ) -> Result<Vec<ExchangeToken>, LomoError> {
        let state: DocumentState = serde_json::from_str(state_json).map_err(|_error| {
            validation(
                "document_command_state_invalid",
                "document command durable state is invalid",
            )
        })?;
        state
            .read_token
            .into_iter()
            .chain(state.write_token)
            .chain(state.history_token)
            .map(|token| ExchangeToken::parse(&token))
            .collect()
    }

    fn start(
        &self,
        ctx: &mut JobDriverContext<'_>,
        request_json: &str,
    ) -> Result<DriverStart, LomoError> {
        let request: DocumentCommandRequest =
            serde_json::from_str(request_json).map_err(|_error| {
                validation(
                    "invalid_document_command_request",
                    "workspace document command request JSON is invalid",
                )
            })?;
        let path = WorkspaceRelativePath::parse(&request.path)?;
        let fingerprint = match &request.expected_state {
            DocumentExpectedState::Absent => None,
            DocumentExpectedState::Match { fingerprint } => {
                Some(SourceFingerprint::parse(fingerprint)?)
            }
        };
        validate_command_shape(&request.command, fingerprint.as_ref())?;
        validate_history_write(request.history.as_ref(), &request.command)?;

        if matches!(request.command, DocumentCommandKind::Create { .. }) {
            if !matches!(request.expected_state, DocumentExpectedState::Absent) {
                return Err(validation(
                    "invalid_document_expected_state",
                    "create requires an absent target",
                ));
            }
            return start_create(ctx, request, &path);
        }
        let expected_fingerprint = fingerprint.ok_or_else(|| {
            validation(
                "invalid_document_expected_state",
                "editing an existing document requires a matching fingerprint",
            )
        })?;

        let token = exchange_token_for(
            ctx.workspace.identity().as_str(),
            ctx.job_id.as_str(),
            "doc-read",
        );
        let action = plan_read(
            ctx.next_action_id("doc-read")?,
            ctx.capability(),
            to_core_path(&path)?,
            &token,
            ExpectedFingerprint::absent(),
        )?;
        let state = DocumentState {
            path: request.path,
            expected_fingerprint: Some(expected_fingerprint.as_str().to_owned()),
            command: request.command,
            history: request.history,
            phase: DocumentPhase::Read,
            read_token: Some(token),
            write_token: None,
            write_length: None,
            write_digest: None,
            result_fingerprint: None,
            history_token: None,
            history_path: None,
            history_length: None,
            history_digest: None,
            source_evidence_length: None,
            source_evidence_digest: None,
            source_evidence_fingerprint: None,
        };
        Ok(DriverStart {
            state_json: encode_state(&state)?,
            actions: vec![action],
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
        let mut state: DocumentState = serde_json::from_str(state_json).map_err(|_error| {
            validation(
                "invalid_document_driver_state",
                "workspace document driver state is corrupt",
            )
        })?;
        match state.phase {
            DocumentPhase::Read => advance_after_read(ctx, &mut state, batch, result),
            DocumentPhase::Write => advance_after_write(ctx, &mut state, batch, result),
            DocumentPhase::Done => Err(validation(
                "document_command_already_done",
                "document command driver cannot advance a completed job",
            )),
        }
    }
}

fn start_create(
    ctx: &mut JobDriverContext<'_>,
    request: DocumentCommandRequest,
    path: &WorkspaceRelativePath,
) -> Result<DriverStart, LomoError> {
    let DocumentCommandKind::Create { time_part, content } = &request.command else {
        return Err(validation(
            "invalid_document_command",
            "create start requires a create command",
        ));
    };
    let source = SourceBytes::try_from_str(&format!("- {time_part}\n{content}\n"))?;
    let result_fingerprint = source.fingerprint().as_str().to_owned();
    let write_token = exchange_token_for(
        ctx.workspace.identity().as_str(),
        ctx.job_id.as_str(),
        "doc-create",
    );
    let (write_length, write_digest) =
        write_exchange_bytes(ctx.exchange_root, &write_token, source.as_bytes())?;
    let artifact = ExchangeArtifact::new(
        &write_token,
        write_length,
        Sha256Digest::parse(&write_digest)?,
    )?;
    let mut state = DocumentState {
        path: request.path,
        expected_fingerprint: None,
        command: request.command,
        history: request.history,
        phase: DocumentPhase::Write,
        read_token: None,
        write_token: Some(write_token),
        write_length: Some(write_length),
        write_digest: Some(write_digest),
        result_fingerprint: Some(result_fingerprint),
        history_token: None,
        history_path: None,
        history_length: None,
        history_digest: None,
        source_evidence_length: None,
        source_evidence_digest: None,
        source_evidence_fingerprint: None,
    };
    let write = PlatformAction::write_from_exchange(
        ctx.next_action_id("doc-create")?,
        ctx.capability(),
        artifact,
        to_core_path(path)?,
        WriteMode::Create,
        ExpectedFingerprint::absent(),
    );
    let mut actions = vec![write];
    actions.extend(prepare_history_actions(ctx, &mut state)?);
    Ok(DriverStart {
        state_json: encode_state(&state)?,
        actions,
        result_json: None,
    })
}

fn advance_after_read(
    ctx: &mut JobDriverContext<'_>,
    state: &mut DocumentState,
    batch: &PlatformActionBatch,
    result: &PlatformBatchResult,
) -> Result<DriverAdvance, LomoError> {
    let output = first_applied_output(batch, result, 0)?;
    let (metadata, artifact) = read_to_exchange_output(output)?;
    let token = state.read_token.clone().ok_or_else(|| {
        validation(
            "document_missing_read_token",
            "document read phase is missing the exchange token",
        )
    })?;
    if artifact.token().as_str() != token {
        return Err(validation(
            "document_exchange_token_mismatch",
            "read-to-exchange token does not match the planned token",
        ));
    }
    let bytes = read_exchange_bytes(ctx.exchange_root, &token)?;
    let source_fp = source_fingerprint_of(&bytes);
    if Some(source_fp.as_str()) != state.expected_fingerprint.as_deref() {
        return Err(conflict(
            "stale_snapshot",
            "document fingerprint does not match expected snapshot",
        ));
    }
    let source = SourceBytes::try_from_bytes(bytes)?;
    let stem = filename_stem(&state.path)?;
    let document = parse_workspace_document(&source, &stem)?;
    let path = WorkspaceRelativePath::parse(&state.path)?;
    let expected =
        SourceFingerprint::parse(state.expected_fingerprint.as_deref().ok_or_else(|| {
            validation(
                "document_missing_expected_fingerprint",
                "read phase requires a matching source fingerprint",
            )
        })?)?;
    let command = to_patch_command(&path, &expected, &state.command)?;
    let plan = plan_document_patch(&document, &command)?;

    let write_token = exchange_token_for(
        ctx.workspace.identity().as_str(),
        ctx.job_id.as_str(),
        "doc-write",
    );
    let (length, digest) =
        write_exchange_bytes(ctx.exchange_root, &write_token, plan.result_bytes())?;
    let write_artifact =
        ExchangeArtifact::new(&write_token, length, Sha256Digest::parse(&digest)?)?;

    state.source_evidence_length = Some(metadata.evidence().length());
    state.source_evidence_digest = Some(metadata.evidence().digest().as_str().to_owned());
    state.source_evidence_fingerprint = Some(metadata.evidence().fingerprint().to_owned());
    state.write_token = Some(write_token);
    state.write_length = Some(length);
    state.write_digest = Some(digest);
    state.result_fingerprint = Some(plan.result_fingerprint().as_str().to_owned());
    state.phase = DocumentPhase::Write;

    let expected_target = ExpectedFingerprint::matching(metadata.evidence().clone());
    let write = PlatformAction::write_from_exchange(
        ctx.next_action_id("doc-write")?,
        ctx.capability(),
        write_artifact,
        to_core_path(&path)?,
        WriteMode::Replace,
        expected_target,
    );
    let mut actions = vec![write];
    actions.extend(prepare_history_actions(ctx, state)?);
    Ok(DriverAdvance::NeedsBatch {
        state_json: encode_state(state)?,
        actions,
        result_json: None,
    })
}

fn advance_after_write(
    ctx: &JobDriverContext<'_>,
    state: &mut DocumentState,
    batch: &PlatformActionBatch,
    result: &PlatformBatchResult,
) -> Result<DriverAdvance, LomoError> {
    let output = first_applied_output(batch, result, 0)?;
    let metadata = write_complete_output(output)?;
    let expected_fp = state.result_fingerprint.clone().ok_or_else(|| {
        validation(
            "document_missing_result_fingerprint",
            "document write phase is missing the planned result fingerprint",
        )
    })?;
    // Content authority is SHA-256 of bytes. Platform evidence.digest is that digest when the
    // gateway digests written content; require match fail-closed.
    let written_digest = metadata.evidence().digest().as_str();
    let planned_digest = state.write_digest.as_deref().ok_or_else(|| {
        validation(
            "document_missing_write_digest",
            "document write phase is missing the planned write digest",
        )
    })?;
    if written_digest != planned_digest {
        return Err(validation(
            "document_write_postcondition_unproven",
            "written content digest does not match the planned patch result",
        ));
    }
    let bytes_written = state.write_length.ok_or_else(|| {
        validation(
            "document_missing_write_length",
            "document write phase is missing the planned write length",
        )
    })?;
    if bytes_written != metadata.evidence().length() {
        return Err(validation(
            "document_write_postcondition_unproven",
            "written content length does not match the planned patch result",
        ));
    }
    verify_history_write(state, batch, result)?;
    let affected_memo = project_affected_memo(ctx, state, &expected_fp)?;
    let payload = DocumentCommandResult {
        path: state.path.clone(),
        result_fingerprint: expected_fp,
        bytes_written,
        affected_memo: Some(affected_memo),
    };
    state.phase = DocumentPhase::Done;
    Ok(DriverAdvance::Done {
        result_json: serde_json::to_string(&payload).map_err(|_error| {
            validation(
                "document_result_encode_failed",
                "document command result cannot be serialized",
            )
        })?,
    })
}

fn project_affected_memo(
    ctx: &JobDriverContext<'_>,
    state: &DocumentState,
    result_fingerprint: &str,
) -> Result<DocumentMemoFacts, LomoError> {
    let stem = filename_stem(&state.path)?;
    let memo = match &state.command {
        DocumentCommandKind::Remove { identity } => {
            let token = state.read_token.as_deref().ok_or_else(|| {
                validation(
                    "document_missing_read_token",
                    "remove result requires the verified source exchange artifact",
                )
            })?;
            let source =
                SourceBytes::try_from_bytes(read_exchange_bytes(ctx.exchange_root, token)?)?;
            let document = parse_workspace_document(&source, &stem)?;
            document
                .memos()
                .iter()
                .find(|memo| memo.identity().as_str() == identity)
                .map(|memo| memo_facts(&state.path, result_fingerprint, memo))
        }
        command @ (DocumentCommandKind::Create { .. }
        | DocumentCommandKind::Append { .. }
        | DocumentCommandKind::Replace { .. }
        | DocumentCommandKind::ToggleTask { .. }
        | DocumentCommandKind::RewriteReminder { .. }) => {
            let token = state.write_token.as_deref().ok_or_else(|| {
                validation(
                    "document_missing_write_token",
                    "document result requires the verified write exchange artifact",
                )
            })?;
            let source =
                SourceBytes::try_from_bytes(read_exchange_bytes(ctx.exchange_root, token)?)?;
            let document = parse_workspace_document(&source, &stem)?;
            let selected = match command {
                DocumentCommandKind::Create { time_part, .. }
                | DocumentCommandKind::Append { time_part, .. } => document
                    .memos()
                    .iter()
                    .rev()
                    .find(|memo| memo.time_part() == time_part),
                DocumentCommandKind::Replace { identity, .. } => document
                    .memos()
                    .iter()
                    .find(|memo| memo.identity().as_str() == identity),
                DocumentCommandKind::ToggleTask { identity, .. } => document
                    .memos()
                    .iter()
                    .find(|memo| memo.identity().as_str() == identity),
                DocumentCommandKind::RewriteReminder { reminder, .. } => document
                    .memos()
                    .iter()
                    .find(|memo| memo.identity().as_str() == reminder.memo_identity),
                DocumentCommandKind::Remove { .. } => None,
            };
            selected.map(|memo| memo_facts(&state.path, result_fingerprint, memo))
        }
    };
    memo.ok_or_else(|| {
        validation(
            "document_affected_memo_missing",
            "completed document command did not produce exactly one affected memo",
        )
    })
}

pub(super) fn memo_facts(
    path: &str,
    fingerprint: &str,
    memo: &crate::document::WorkspaceMemo,
) -> DocumentMemoFacts {
    DocumentMemoFacts {
        path: path.to_owned(),
        identity: memo.identity().as_str().to_owned(),
        time_part: memo.time_part().to_owned(),
        fingerprint: fingerprint.to_owned(),
        tags: memo.tags().to_vec(),
        attachments: memo.attachments().to_vec(),
        reminders: memo
            .reminders()
            .iter()
            .map(ReminderReference::from)
            .collect(),
        has_todo: memo.has_todo(),
        has_url: memo.has_url(),
        content: Some(memo.content().to_owned()),
    }
}

fn validate_command_shape(
    command: &DocumentCommandKind,
    expected_fingerprint: Option<&SourceFingerprint>,
) -> Result<(), LomoError> {
    match command {
        DocumentCommandKind::Create {
            time_part,
            content: _,
        }
        | DocumentCommandKind::Append {
            time_part,
            content: _,
        } => {
            if time_part.is_empty() {
                return Err(validation(
                    "invalid_document_command",
                    "append time_part must be non-empty",
                ));
            }
            Ok(())
        }
        DocumentCommandKind::Replace {
            identity,
            content: _,
        }
        | DocumentCommandKind::Remove { identity } => {
            let _identity = MemoIdentity::parse(identity)?;
            Ok(())
        }
        DocumentCommandKind::ToggleTask {
            identity,
            body_start,
            body_end,
        } => {
            let parsed = MemoIdentity::parse(identity)?;
            if *body_end < *body_start || *body_end == 0 {
                return Err(validation(
                    "invalid_task_source_identity",
                    "task source identity span must satisfy 0 < start <= end",
                ));
            }
            if parsed.as_str() != identity {
                return Err(validation(
                    "invalid_task_source_identity",
                    "task command identity is not canonical",
                ));
            }
            Ok(())
        }
        DocumentCommandKind::RewriteReminder {
            reminder,
            replacement: _,
        } => {
            let parsed = ReminderRef::try_from_reference(reminder.clone())?;
            let expected_fingerprint = expected_fingerprint.ok_or_else(|| {
                validation(
                    "invalid_document_expected_state",
                    "reminder rewrite requires a matching source fingerprint",
                )
            })?;
            if parsed.revision() != expected_fingerprint {
                return Err(validation(
                    "invalid_reminder_reference",
                    "reminder revision must match the document command snapshot",
                ));
            }
            Ok(())
        }
    }
}

fn validate_history_write(
    history: Option<&DocumentHistoryWrite>,
    command: &DocumentCommandKind,
) -> Result<(), LomoError> {
    let Some(history) = history else {
        return Ok(());
    };
    if history.revision == 0 || history.created_at_ms <= 0 {
        return Err(validation(
            "invalid_document_history_write",
            "history revision and creation time must be positive",
        ));
    }
    if !matches!(
        command,
        DocumentCommandKind::Create { .. }
            | DocumentCommandKind::Append { .. }
            | DocumentCommandKind::Replace { .. }
    ) {
        return Err(validation(
            "invalid_document_history_write",
            "history write requires a command with an explicit memo body",
        ));
    }
    Ok(())
}

fn prepare_history_actions(
    ctx: &mut JobDriverContext<'_>,
    state: &mut DocumentState,
) -> Result<Vec<PlatformAction>, LomoError> {
    let Some(history) = state.history.clone() else {
        return Ok(Vec::new());
    };
    let result_fingerprint = state.result_fingerprint.as_deref().ok_or_else(|| {
        validation(
            "document_missing_result_fingerprint",
            "history preparation requires the planned document fingerprint",
        )
    })?;
    let affected = project_affected_memo(ctx, state, result_fingerprint)?;
    let content = match &state.command {
        DocumentCommandKind::Create { content, .. }
        | DocumentCommandKind::Append { content, .. }
        | DocumentCommandKind::Replace { content, .. } => content.clone(),
        DocumentCommandKind::Remove { .. }
        | DocumentCommandKind::ToggleTask { .. }
        | DocumentCommandKind::RewriteReminder { .. } => {
            return Err(validation(
                "invalid_document_history_write",
                "history write requires a command with an explicit memo body",
            ));
        }
    };
    let content_digest = hex_encode(&Sha256::digest(content.as_bytes()));
    let body = HistorySnapshotV1 {
        memo_id: affected.identity.clone(),
        revision: history.revision,
        content,
        file_fingerprint: content_digest,
        created_at_ms: history.created_at_ms,
    };
    let body_json = serde_json::to_string(&body).map_err(|_error| {
        validation(
            "document_history_encode_failed",
            "document history snapshot cannot be serialized",
        )
    })?;
    let record_id = format!("{}-r{}", affected.identity, history.revision);
    let bytes = encode_record(&LomoPayload {
        kind: LomoRecordKind::History,
        record_id: record_id.clone(),
        body_json,
    })?;
    let token = exchange_token_for(
        ctx.workspace.identity().as_str(),
        ctx.job_id.as_str(),
        "doc-history",
    );
    let (length, digest) = write_exchange_bytes(ctx.exchange_root, &token, &bytes)?;
    let artifact = ExchangeArtifact::new(&token, length, Sha256Digest::parse(&digest)?)?;
    let path = format!(".lomo/history/v1/{}", history_record_filename(&record_id));
    let target = to_core_path(&WorkspaceRelativePath::parse(&path)?)?;
    state.history_token = Some(token);
    state.history_path = Some(path);
    state.history_length = Some(length);
    state.history_digest = Some(digest);
    let history_directory = to_core_path(&WorkspaceRelativePath::parse(".lomo/history/v1")?)?;
    Ok(vec![
        PlatformAction::ensure_directory(
            ctx.next_action_id("doc-history-dir")?,
            ctx.capability(),
            history_directory,
        ),
        PlatformAction::write_from_exchange(
            ctx.next_action_id("doc-history")?,
            ctx.capability(),
            artifact,
            target,
            WriteMode::Create,
            ExpectedFingerprint::absent(),
        ),
    ])
}

fn verify_history_write(
    state: &DocumentState,
    batch: &PlatformActionBatch,
    result: &PlatformBatchResult,
) -> Result<(), LomoError> {
    if state.history.is_none() {
        return Ok(());
    }
    let output = first_applied_output(batch, result, 2)?;
    let metadata = write_complete_output(output)?;
    let expected_length = state.history_length.ok_or_else(|| {
        validation(
            "document_history_state_incomplete",
            "history write length is missing from durable state",
        )
    })?;
    let expected_digest = state.history_digest.as_deref().ok_or_else(|| {
        validation(
            "document_history_state_incomplete",
            "history write digest is missing from durable state",
        )
    })?;
    if metadata.evidence().length() != expected_length
        || metadata.evidence().digest().as_str() != expected_digest
    {
        return Err(validation(
            "document_history_postcondition_unproven",
            "history record does not match the planned artifact",
        ));
    }
    Ok(())
}

fn to_patch_command(
    path: &WorkspaceRelativePath,
    expected: &SourceFingerprint,
    command: &DocumentCommandKind,
) -> Result<DocumentPatchCommand, LomoError> {
    Ok(match command {
        DocumentCommandKind::Create { .. } => {
            return Err(validation(
                "invalid_document_command",
                "create does not use the existing-document patch planner",
            ));
        }
        DocumentCommandKind::Append { time_part, content } => DocumentPatchCommand::Append {
            path: path.clone(),
            expected_fingerprint: expected.clone(),
            time_part: time_part.clone(),
            content: content.clone(),
        },
        DocumentCommandKind::Replace { identity, content } => DocumentPatchCommand::Replace {
            path: path.clone(),
            expected_fingerprint: expected.clone(),
            identity: MemoIdentity::parse(identity)?,
            content: content.clone(),
        },
        DocumentCommandKind::Remove { identity } => DocumentPatchCommand::Remove {
            path: path.clone(),
            expected_fingerprint: expected.clone(),
            identity: MemoIdentity::parse(identity)?,
        },
        DocumentCommandKind::ToggleTask {
            identity,
            body_start,
            body_end,
        } => DocumentPatchCommand::ToggleTask {
            path: path.clone(),
            expected_fingerprint: expected.clone(),
            identity: MemoIdentity::parse(identity)?,
            body_start: *body_start,
            body_end: *body_end,
        },
        DocumentCommandKind::RewriteReminder {
            reminder,
            replacement,
        } => DocumentPatchCommand::RewriteReminder {
            path: path.clone(),
            reminder: ReminderRef::try_from_reference(reminder.clone())?,
            replacement: replacement.clone(),
        },
    })
}

fn encode_state(state: &DocumentState) -> Result<String, LomoError> {
    serde_json::to_string(state).map_err(|_error| {
        validation(
            "document_state_encode_failed",
            "workspace document driver state cannot be serialized",
        )
    })
}
