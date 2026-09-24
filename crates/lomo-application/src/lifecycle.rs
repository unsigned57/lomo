//! History listing, trash restore, history body restore, and permanent delete.

use lomo_core::{LomoError, OperationId, RelativeWorkspacePath};
use lomo_store::{
    DocumentPublication, MemoHistoryPage, MemoSnapshot, SafPermanentDeleteTarget,
    SafProjectionCommitResult, SafProjectionMutation, SafProjectionMutationKind,
    ScannedMemoProjection,
};
use lomo_workspace::{
    DocumentPatchCommand, MemoId, MemoIdentityChange, SourceFingerprint, decode_trash_record,
    trash_record_relative_path,
};
use serde::Serialize;

use crate::{
    document_plan::{LoadedDocument, project_memo},
    error::{conflict, resource_limit, validation},
    lock::TransactionLock,
    record_plan::{StateChange, state_files},
    resource::{MAX_TRANSACTION_BYTES, MAX_TRANSACTION_FILES},
    session::WorkspaceSession,
    transaction::{PlannedFile, TransactionInput, payload_digest},
    types::{UpdateMemoRequest, UpdateMemoResult},
    workspace_io::epoch_millis,
};

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct RestoreMemoRequest {
    pub operation_id: OperationId,
    pub memo_id: MemoId,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RestoreMemoResult {
    pub commit_result: SafProjectionCommitResult,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct RestoreRevisionRequest {
    pub operation_id: OperationId,
    pub memo_id: MemoId,
    pub revision: u64,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct PermanentDeleteRequest {
    pub operation_id: OperationId,
    pub memo_id: MemoId,
}

/// One verified trash target in a permanent-delete batch.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct PermanentDeleteManyTarget {
    pub memo_id: MemoId,
    pub source_path: String,
    pub expected_revision: u64,
    pub expected_fingerprint: String,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct PermanentDeleteManyRequest {
    pub operation_id: OperationId,
    pub targets: Vec<PermanentDeleteManyTarget>,
}

/// Aggregate outcome of one permanent-delete operation.
///
/// `batches` carries the durable child receipts in canonical order; `commit_result` is the last
/// child's receipt so callers observe the final projection clock. `idempotent_replay` is true only
/// when every child batch replayed a committed receipt instead of re-executing.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PermanentDeleteManyResult {
    pub commit_result: SafProjectionCommitResult,
    pub deleted: Vec<MemoId>,
    pub batches: Vec<SafProjectionCommitResult>,
    pub idempotent_replay: bool,
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
        self.with_reader(|store| store.list_memo_history(memo_id.as_str(), cursor, limit))
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
                commit_result: receipt,
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
                commit_result: receipt,
            });
        }
        self.recover_pending()?;
        self.drop_trashed(request, digest)
    }

    /// Permanently drops trashed memos as bounded, replay-safe child batches.
    ///
    /// Every child batch is one durable intent: its trash-record deletes plus one atomic
    /// projection publication committed in a single store transaction. Child operation identities
    /// derive from the parent id and the chunk's frozen facts, so a retry of a committed child
    /// replays its receipt while a corrected target set plans only the children that changed.
    ///
    /// # Errors
    /// Unknown or untrashed targets, stale baselines, duplicate ids, oversized requests and
    /// transaction resource overflow all fail closed before any child is planned.
    pub fn permanently_delete_many(
        &self,
        request: &PermanentDeleteManyRequest,
    ) -> Result<PermanentDeleteManyResult, LomoError> {
        let _lock = TransactionLock::acquire(&self.config.runtime_dir)?;
        let mut targets = request.targets.clone();
        targets.sort_by(|left, right| left.memo_id.as_str().cmp(right.memo_id.as_str()));
        if targets.is_empty() {
            return Err(validation(
                "invalid_batch_targets",
                "permanent delete requires at least one target",
            ));
        }
        if targets
            .iter()
            .zip(targets.iter().skip(1))
            .any(|(left, right)| left.memo_id == right.memo_id)
        {
            return Err(validation(
                "duplicate_permanent_delete_target",
                "permanent delete batch contains a duplicate memo identity",
            ));
        }
        // Child ids are "{parent}.{token16}"; keep the derived id inside OperationId's limit.
        if request.operation_id.as_str().len() + 1 + BATCH_TOKEN_LEN > 128 {
            return Err(validation(
                "invalid_operation_id",
                "operation id leaves no room for the derived batch token",
            ));
        }
        self.recover_pending()?;

        // Chunks are a pure function of the sorted request: replay of a committed child must be
        // decidable before any file or projection read, because a committed child already deleted
        // both. Count is the only request-visible bound; the retained-byte budget is verified when
        // each uncommitted chunk is planned and fails closed instead of silently re-chunking.
        let mut batches = Vec::new();
        let mut deleted = Vec::with_capacity(targets.len());
        for chunk in targets.chunks(MAX_TRANSACTION_FILES) {
            let token = batch_token(chunk)?;
            let child_id =
                OperationId::parse(&format!("{}.{token}", request.operation_id.as_str()))?;
            let digest = payload_digest(&(&request.operation_id, chunk))?;
            if let Some(receipt) = self.replay(&child_id, &digest)? {
                deleted.extend(chunk.iter().map(|target| target.memo_id.clone()));
                batches.push(receipt);
                continue;
            }
            let receipt = self.commit_delete_chunk(&child_id, chunk, digest)?;
            deleted.extend(chunk.iter().map(|target| target.memo_id.clone()));
            batches.push(receipt);
        }
        let idempotent_replay = batches.iter().all(|batch| batch.idempotent_replay);
        let commit_result = batches.last().cloned().ok_or_else(|| {
            validation(
                "invalid_batch_targets",
                "permanent delete produced no batch",
            )
        })?;
        Ok(PermanentDeleteManyResult {
            commit_result,
            deleted,
            batches,
            idempotent_replay,
        })
    }

    /// Plans and commits one bounded permanent-delete chunk as a single durable intent.
    fn commit_delete_chunk(
        &self,
        child_id: &OperationId,
        chunk: &[PermanentDeleteManyTarget],
        digest: String,
    ) -> Result<SafProjectionCommitResult, LomoError> {
        let mut files = Vec::with_capacity(chunk.len());
        let mut retained_bytes = 0_u64;
        let mut batch_targets = Vec::with_capacity(chunk.len());
        for target in chunk {
            let summary = self
                .with_reader(|store| store.get_memo_projection(target.memo_id.as_str()))?
                .ok_or_else(|| validation("memo_not_found", "permanent delete target is absent"))?;
            if !summary.is_trashed {
                return Err(validation(
                    "memo_not_trashed",
                    "permanent delete requires a memo currently projected in trash",
                ));
            }
            if summary.content_revision != target.expected_revision
                || summary.file_fingerprint != target.expected_fingerprint
                || summary.source_path != target.source_path
            {
                return Err(conflict(
                    "stale_snapshot",
                    "permanent delete target snapshot is stale",
                ));
            }
            let trash_path = RelativeWorkspacePath::parse(
                trash_record_relative_path(target.memo_id.as_str())?.as_str(),
            )?;
            let trash_file = self.io().require(&trash_path)?;
            retained_bytes =
                retained_bytes
                    .checked_add(u64::try_from(trash_file.bytes.len()).map_err(|error| {
                        resource_limit("transaction_too_large", error.to_string())
                    })?)
                    .ok_or_else(|| {
                        resource_limit("transaction_too_large", "batch byte accounting overflow")
                    })?;
            batch_targets.push(SafPermanentDeleteTarget {
                memo_id: target.memo_id.as_str().to_owned(),
                source_path: target.source_path.clone(),
                expected_revision: target.expected_revision,
                expected_fingerprint: target.expected_fingerprint.clone(),
                // A direct permanent delete does not rewrite the source document; refreshing
                // sibling rows to the same fingerprint keeps the batch contract uniform.
                result_fingerprint: summary.file_fingerprint.clone(),
                reminder_ids: summary
                    .reminders
                    .iter()
                    .map(|reminder| reminder.opaque_id.clone())
                    .collect(),
            });
            files.push(PlannedFile::delete(trash_path, &trash_file));
        }
        let first = chunk
            .first()
            .ok_or_else(|| validation("invalid_batch_targets", "empty permanent delete chunk"))?;
        let publication = DocumentPublication {
            history: None,
            mutation: SafProjectionMutation {
                operation_id: child_id.as_str().to_owned(),
                kind: SafProjectionMutationKind::PermanentDeleteMany,
                memo_id: first.memo_id.as_str().to_owned(),
                expected_revision: first.expected_revision,
                expected_fingerprint: Some(first.expected_fingerprint.clone()),
                projection: None,
                trashed_at_ms: None,
                batch_targets,
            },
        };
        let mutation_bytes = u64::try_from(
            serde_json::to_vec(&publication)
                .map_err(|error| validation("invalid_batch_targets", error.to_string()))?
                .len(),
        )
        .map_err(|error| resource_limit("transaction_too_large", error.to_string()))?;
        if retained_bytes
            .checked_add(mutation_bytes)
            .is_none_or(|total| total > MAX_TRANSACTION_BYTES)
        {
            return Err(resource_limit(
                "transaction_too_large",
                "permanent delete chunk exceeds the transaction byte budget",
            ));
        }
        self.commit_transaction(TransactionInput {
            operation_id: child_id.clone(),
            memo_id: first.memo_id.clone(),
            path: RelativeWorkspacePath::parse(&first.source_path)?,
            payload_digest: digest,
            files,
            mutations: vec![publication],
        })
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
                    batch_targets: Vec::new(),
                },
            }],
        })?;
        Ok(RestoreMemoResult {
            commit_result: receipt,
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
        let projection = remaining_projection(&request.memo_id, &current, loaded.fingerprint())?;
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
                    batch_targets: Vec::new(),
                },
            }],
        })?;
        Ok(RestoreMemoResult {
            commit_result: receipt,
        })
    }

    fn trashed_snapshot(&self, id: &MemoId) -> Result<MemoSnapshot, LomoError> {
        self.with_reader(|store| store.get_projected_memo(id.as_str()))?
            .filter(|memo| memo.summary.is_trashed)
            .ok_or_else(|| validation("memo_not_trashed", "memo is not in the trash projection"))
    }
}

/// Length of the content-derived token in a child batch operation id ("{parent}.{token}").
const BATCH_TOKEN_LEN: usize = 16;

/// Derives the stable identity token of one child batch from the caller's CAS facts alone. The
/// derivation must not touch the filesystem or projection: replay of a committed child is decided
/// before either is read, because a committed child already deleted both.
fn batch_token(chunk: &[PermanentDeleteManyTarget]) -> Result<String, LomoError> {
    let bytes = serde_json::to_vec(chunk)
        .map_err(|error| validation("invalid_batch_targets", error.to_string()))?;
    let fingerprint = SourceFingerprint::of_bytes(&bytes);
    Ok(fingerprint.as_str().chars().take(BATCH_TOKEN_LEN).collect())
}

fn remaining_projection(
    id: &MemoId,
    current: &MemoSnapshot,
    fingerprint: &str,
) -> Result<ScannedMemoProjection, LomoError> {
    Ok(ScannedMemoProjection {
        memo_id: id.as_str().to_owned(),
        source_path: current.summary.source_path.clone(),
        file_fingerprint: fingerprint.to_owned(),
        chronology_epoch_ms: current.summary.created_at_ms,
        // The summary only carries non-audio `image_urls`; the projection row must name every
        // attachment type, so the path set is re-derived from the body with the same parser that
        // writes `attachment_ref`.
        attachment_paths: lomo_store::project_content_facts(&current.body)?.attachment_paths,
        body: current.body.clone(),
        tags: current.summary.tags.clone(),
        has_todo: current.summary.has_todo,
        has_url: current.summary.has_url,
        reminders: current.summary.reminders.clone(),
    })
}
