//! Workspace document-command multi-phase job driver.
//!
//! Flow: read path to exchange → fingerprint + parse + pure patch plan → write patched bytes to a
//! private exchange artifact → `WriteFromExchange` with expected target fingerprint → optional verify
//! stat. Fail closed on stale snapshots; replay uses `AlreadySatisfied` postconditions without a second
//! mutating write plan.

use lomo_core::{
    DriverAdvance, DriverStart, ExchangeArtifact, ExpectedFingerprint, JobDriver, JobDriverContext,
    LomoError, PlatformAction, PlatformActionBatch, PlatformBatchResult, Sha256Digest, WriteMode,
};
use serde::{Deserialize, Serialize};

use crate::limits::validation;
use crate::parse::parse_workspace_document;
use crate::patch::{DocumentPatchCommand, TaskSourceIdentity, plan_document_patch};
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
    pub expected_fingerprint: String,
    pub command: DocumentCommandKind,
}

#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum DocumentCommandKind {
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
        source_start: u64,
        source_end: u64,
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
}

#[derive(Clone, Debug, Serialize, Deserialize)]
struct DocumentState {
    path: String,
    expected_fingerprint: String,
    command: DocumentCommandKind,
    phase: DocumentPhase,
    read_token: Option<String>,
    write_token: Option<String>,
    write_length: Option<u64>,
    write_digest: Option<String>,
    result_fingerprint: Option<String>,
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
        let fingerprint = SourceFingerprint::parse(&request.expected_fingerprint)?;
        validate_command_shape(&request.command, &fingerprint)?;

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
            expected_fingerprint: request.expected_fingerprint,
            command: request.command,
            phase: DocumentPhase::Read,
            read_token: Some(token),
            write_token: None,
            write_length: None,
            write_digest: None,
            result_fingerprint: None,
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
    if source_fp.as_str() != state.expected_fingerprint {
        return Err(validation(
            "stale_snapshot",
            "document fingerprint does not match expected snapshot",
        ));
    }
    let source = SourceBytes::try_from_bytes(bytes)?;
    let stem = filename_stem(&state.path)?;
    let document = parse_workspace_document(&source, &stem)?;
    let path = WorkspaceRelativePath::parse(&state.path)?;
    let expected = SourceFingerprint::parse(&state.expected_fingerprint)?;
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
    Ok(DriverAdvance::NeedsBatch {
        state_json: encode_state(state)?,
        actions: vec![write],
        result_json: None,
    })
}

fn advance_after_write(
    _ctx: &mut JobDriverContext<'_>,
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
    let payload = DocumentCommandResult {
        path: state.path.clone(),
        result_fingerprint: expected_fp,
        bytes_written,
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

fn validate_command_shape(
    command: &DocumentCommandKind,
    expected_fingerprint: &SourceFingerprint,
) -> Result<(), LomoError> {
    match command {
        DocumentCommandKind::Append {
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
            source_start,
            source_end,
        } => {
            if *source_end < *source_start {
                return Err(validation(
                    "invalid_task_source_identity",
                    "task source identity span must satisfy start <= end",
                ));
            }
            Ok(())
        }
        DocumentCommandKind::RewriteReminder {
            reminder,
            replacement: _,
        } => {
            let parsed = ReminderRef::try_from_reference(reminder.clone())?;
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

fn to_patch_command(
    path: &WorkspaceRelativePath,
    expected: &SourceFingerprint,
    command: &DocumentCommandKind,
) -> Result<DocumentPatchCommand, LomoError> {
    Ok(match command {
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
            source_start,
            source_end,
        } => DocumentPatchCommand::ToggleTask {
            path: path.clone(),
            expected_fingerprint: expected.clone(),
            source_identity: TaskSourceIdentity::try_new(
                usize::try_from(*source_start).map_err(|_e| {
                    validation(
                        "invalid_task_source_identity",
                        "task source span exceeds platform usize",
                    )
                })?,
                usize::try_from(*source_end).map_err(|_e| {
                    validation(
                        "invalid_task_source_identity",
                        "task source span exceeds platform usize",
                    )
                })?,
            )?,
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
