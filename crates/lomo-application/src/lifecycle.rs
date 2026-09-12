//! History listing, trash restore, history body restore, and permanent delete.

use lomo_core::{LomoError, OperationId, RelativeWorkspacePath};
use lomo_store::{
    DocumentPublication, MemoHistoryPage, MemoSnapshot, SafProjectionMutation,
    SafProjectionMutationKind, ScannedMemoProjection,
};
use lomo_workspace::{
    DocumentPatchCommand, MemoId, MemoIdentityChange, decode_trash_record,
    trash_record_relative_path,
};
use serde::{Deserialize, Serialize};

use crate::{
    document_plan::{LoadedDocument, project_memo},
    error::validation,
    lock::TransactionLock,
    record_plan::{StateChange, state_files},
    session::WorkspaceSession,
    transaction::{PlannedFile, TransactionInput, payload_digest},
    types::{UpdateMemoRequest, UpdateMemoResult},
    workspace_io::epoch_millis,
};

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct RestoreMemoRequest {
    pub operation_id: OperationId,
    pub memo_id: MemoId,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RestoreMemoResult {
    pub file_fingerprint: String,
    pub event_sequence: u64,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct RestoreRevisionRequest {
    pub operation_id: OperationId,
    pub memo_id: MemoId,
    pub revision: u64,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct PermanentDeleteRequest {
    pub operation_id: OperationId,
    pub memo_id: MemoId,
}

impl WorkspaceSession {
    /// Lists durable history revisions for one memo.
    ///
    /// # Errors
    /// History validation and storage failures.
    pub fn list_history(
        &self,
        memo_id: &MemoId,
        cursor: Option<&str>,
        limit: usize,
    ) -> Result<MemoHistoryPage, LomoError> {
        self.with_store(|store| store.list_memo_history(memo_id.as_str(), cursor, limit))
    }

    /// Restores a soft-deleted memo with its original durable ID.
    ///
    /// # Errors
    /// Missing trash records, identity conflicts and write failures.
    pub fn restore_memo(
        &self,
        request: &RestoreMemoRequest,
    ) -> Result<RestoreMemoResult, LomoError> {
        let _lock = TransactionLock::acquire(&self.config.runtime_dir)?;
        let digest = payload_digest(&request)?;
        if let Some(receipt) = self.replay(&request.operation_id, &digest)? {
            return Ok(RestoreMemoResult {
                file_fingerprint: receipt.file_fingerprint.clone(),
                event_sequence: receipt.event_sequence,
            });
        }
        self.recover_pending()?;
        self.restore_trashed(request, digest)
    }

    /// Replaces the active body with a historical snapshot through the shared write path.
    ///
    /// # Errors
    /// Missing revisions and stale baselines.
    pub fn restore_revision(
        &self,
        request: RestoreRevisionRequest,
    ) -> Result<UpdateMemoResult, LomoError> {
        let page = self.list_history(&request.memo_id, None, 256)?;
        let revision = page
            .items
            .iter()
            .find(|item| item.revision == request.revision)
            .ok_or_else(|| validation("history_revision_missing", "revision is not in window"))?;
        let current = self.current_memo(&request.memo_id)?;
        self.update_memo(UpdateMemoRequest {
            operation_id: request.operation_id,
            memo_id: request.memo_id,
            content: revision.content.clone(),
            expected_document_fingerprint: current.summary.file_fingerprint,
            pending_promotes: Vec::new(),
        })
    }

    /// Permanently drops a trashed memo after the Markdown block is already gone.
    ///
    /// # Errors
    /// Requires a current trash projection and matching trash record.
    pub fn permanently_delete_memo(
        &self,
        request: &PermanentDeleteRequest,
    ) -> Result<RestoreMemoResult, LomoError> {
        let _lock = TransactionLock::acquire(&self.config.runtime_dir)?;
        let digest = payload_digest(&request)?;
        if let Some(receipt) = self.replay(&request.operation_id, &digest)? {
            return Ok(RestoreMemoResult {
                file_fingerprint: receipt.file_fingerprint.clone(),
                event_sequence: receipt.event_sequence,
            });
        }
        self.recover_pending()?;
        self.drop_trashed(request, digest)
    }
}

impl WorkspaceSession {
    fn restore_trashed(
        &self,
        request: &RestoreMemoRequest,
        digest: String,
    ) -> Result<RestoreMemoResult, LomoError> {
        let current = self.trashed_snapshot(&request.memo_id)?;
        let trash_path = RelativeWorkspacePath::parse(
            trash_record_relative_path(request.memo_id.as_str())?.as_str(),
        )?;
        let trash_file = self.io().require(&trash_path)?;
        let trash = decode_trash_record(&trash_file.bytes)?;
        let path = RelativeWorkspacePath::parse(&trash.source_path)?;
        let loaded = LoadedDocument::load(&self.io(), path.clone())?;
        let command = DocumentPatchCommand::Append {
            path: loaded.logical_path.clone(),
            expected_fingerprint: loaded.document.source().fingerprint().clone(),
            time_part: trash.time_part.clone(),
            content: trash.body,
        };
        let changed = loaded.apply(
            request.operation_id.clone(),
            MemoIdentityChange::Restore(request.memo_id.clone()),
            &command,
        )?;
        let fingerprint = changed.document.source().fingerprint().as_str();
        let projection = project_memo(
            &path,
            &request.memo_id,
            fingerprint,
            loaded.changed_memo(&changed, &request.memo_id)?,
            &self.config.time_zone,
        )?;
        let mut files = loaded.files(&changed)?;
        files.push(PlannedFile::delete(trash_path, &trash_file));
        files.extend(state_files(
            &self.io(),
            &request.memo_id,
            &request.operation_id,
            StateChange::Restore,
            epoch_millis()?,
        )?);
        let receipt = self.commit_transaction(TransactionInput {
            operation_id: request.operation_id.clone(),
            memo_id: request.memo_id.clone(),
            path,
            payload_digest: digest,
            files,
            mutations: vec![DocumentPublication {
                history: None,
                mutation: SafProjectionMutation {
                    operation_id: request.operation_id.as_str().to_owned(),
                    kind: SafProjectionMutationKind::Restore,
                    memo_id: request.memo_id.as_str().to_owned(),
                    expected_revision: current.summary.content_revision,
                    expected_fingerprint: Some(current.summary.file_fingerprint),
                    projection: Some(projection),
                    trashed_at_ms: None,
                },
            }],
        })?;
        Ok(RestoreMemoResult {
            file_fingerprint: receipt.file_fingerprint,
            event_sequence: receipt.event_sequence,
        })
    }

    fn drop_trashed(
        &self,
        request: &PermanentDeleteRequest,
        digest: String,
    ) -> Result<RestoreMemoResult, LomoError> {
        let current = self.trashed_snapshot(&request.memo_id)?;
        let path = RelativeWorkspacePath::parse(&current.summary.source_path)?;
        let loaded = LoadedDocument::load(&self.io(), path.clone())?;
        let trash_path = RelativeWorkspacePath::parse(
            trash_record_relative_path(request.memo_id.as_str())?.as_str(),
        )?;
        let trash_file = self.io().require(&trash_path)?;
        let projection = remaining_projection(&request.memo_id, &current, loaded.fingerprint());
        let receipt = self.commit_transaction(TransactionInput {
            operation_id: request.operation_id.clone(),
            memo_id: request.memo_id.clone(),
            path,
            payload_digest: digest,
            files: vec![PlannedFile::delete(trash_path, &trash_file)],
            mutations: vec![DocumentPublication {
                history: None,
                mutation: SafProjectionMutation {
                    operation_id: request.operation_id.as_str().to_owned(),
                    kind: SafProjectionMutationKind::PermanentDelete,
                    memo_id: request.memo_id.as_str().to_owned(),
                    expected_revision: current.summary.content_revision,
                    expected_fingerprint: Some(current.summary.file_fingerprint),
                    projection: Some(projection),
                    trashed_at_ms: None,
                },
            }],
        })?;
        Ok(RestoreMemoResult {
            file_fingerprint: receipt.file_fingerprint,
            event_sequence: receipt.event_sequence,
        })
    }

    fn trashed_snapshot(&self, id: &MemoId) -> Result<MemoSnapshot, LomoError> {
        self.with_store(|store| store.get_projected_memo(id.as_str()))?
            .filter(|memo| memo.summary.is_trashed)
            .ok_or_else(|| validation("memo_not_trashed", "memo is not in the trash projection"))
    }
}

fn remaining_projection(
    id: &MemoId,
    current: &MemoSnapshot,
    fingerprint: &str,
) -> ScannedMemoProjection {
    ScannedMemoProjection {
        memo_id: id.as_str().to_owned(),
        source_path: current.summary.source_path.clone(),
        file_fingerprint: fingerprint.to_owned(),
        chronology_epoch_ms: current.summary.created_at_ms,
        body: current.body.clone(),
        tags: current.summary.tags.clone(),
        attachment_paths: current.summary.image_urls.clone(),
        has_todo: current.summary.has_todo,
        has_url: current.summary.has_url,
        reminders: current.summary.reminders.clone(),
    }
}
