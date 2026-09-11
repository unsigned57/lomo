//! Shared application lifecycle and commands. Physical writes use one frozen transaction path.

use std::sync::atomic::AtomicU64;
use std::sync::{Arc, Mutex};

use lomo_core::{LomoError, OperationId, PageSize, PlatformActionExecutor, RelativeWorkspacePath};
use lomo_store::{
    DocumentPublication, MemoPage, MemoQuery, MemoQueryBoundary, MemoSnapshot, PageCursor,
    RebuildResult, SafProjectionMutation, SafProjectionMutationKind, SidebarProjection, Store,
};
use lomo_workspace::{
    DocumentPatchCommand, HistorySnapshotV1, MemoId, MemoIdentityChange, TrashRecordCreate,
    TrashRecordV1, encode_trash_record, trash_record_relative_path,
};

use crate::config::WorkspaceSessionConfig;
use crate::csprng::{generate_device_id, mint_memo_id};
use crate::document_plan::{LoadedDocument, project_memo};
use crate::draft::{ConflictEvidence, DraftStore};
use crate::error::{storage, validation};
use crate::intent::IntentJournal;
use crate::lock::TransactionLock;
use crate::private_io::{read_optional, write_atomic};
use crate::record_plan::{StateChange, history_files, state_files};
use crate::transaction::{PlannedFile, TransactionInput, payload_digest};
use crate::types::{
    CreateMemoRequest, CreateMemoResult, DeleteMemoRequest, DeleteMemoResult, PinMemoRequest,
    PinMemoResult, SessionMemoView, UpdateMemoRequest, UpdateMemoResult,
};
use crate::workspace_io::{WorkspaceIo, epoch_millis};

pub struct WorkspaceSession {
    pub(crate) config: WorkspaceSessionConfig,
    pub(crate) executor: Arc<dyn PlatformActionExecutor>,
    pub(crate) intent_journal: IntentJournal,
    device_id: String,
    store: Mutex<Option<Store>>,
    pub(crate) draft_store: DraftStore,
    pub(crate) search_epoch: AtomicU64,
}

impl WorkspaceSession {
    /// Opens private state and recovers pending transactions before admitting new commands.
    ///
    /// # Errors
    /// Private storage, corrupt journals and unresolved physical conflicts fail closed.
    pub fn open(
        config: WorkspaceSessionConfig,
        executor: Arc<dyn PlatformActionExecutor>,
    ) -> Result<Self, LomoError> {
        crate::calendar::local_date(epoch_millis()?, &config.time_zone)?;
        for directory in [
            &config.state_dir,
            &config.cache_dir,
            &config.runtime_dir,
            &config.exchange_dir,
        ] {
            std::fs::create_dir_all(directory)
                .map_err(|error| storage("private_directory_failed", error.to_string()))?;
        }
        let _lock = TransactionLock::acquire(&config.runtime_dir)?;
        let identity_path = config.state_dir.join("device_id");
        let device_id = if let Some(bytes) = read_optional(&identity_path)? {
            let value = String::from_utf8(bytes)
                .map_err(|error| validation("invalid_device_identity", error.to_string()))?;
            lomo_core::CapabilityToken::parse(&value)?;
            value
        } else {
            let value = generate_device_id()?;
            write_atomic(&identity_path, value.as_bytes())?;
            value
        };
        let store = Store::open_projection(&config.cache_dir)?;
        let session = Self {
            intent_journal: IntentJournal::new(&config.state_dir)?,
            draft_store: DraftStore::new(&config.state_dir)?,
            config,
            executor,
            device_id,
            store: Mutex::new(Some(store)),
            search_epoch: AtomicU64::new(0),
        };
        let pending = session.recover_files_for_mount()?;
        let floor = session.intent_journal.clock_floor()?;
        session.with_store_mut(|store| store.restore_clock_floor(floor))?;
        session.rebuild_locked()?;
        session.acknowledge_mounted_operations(&pending)?;
        Ok(session)
    }

    #[must_use]
    pub fn device_id(&self) -> &str {
        &self.device_id
    }

    /// Appends a new independently identified memo to its dated Markdown document.
    ///
    /// # Errors
    /// Rejects stale baselines, operation reuse, invalid content and physical write failures.
    #[expect(
        clippy::too_many_lines,
        reason = "create freezes markdown, attachments, history, and optional pin in one transaction"
    )]
    pub fn create_memo(&self, request: CreateMemoRequest) -> Result<CreateMemoResult, LomoError> {
        require_new_memo_content(&request.content)?;
        let _lock = TransactionLock::acquire(&self.config.runtime_dir)?;
        let digest = payload_digest(&request)?;
        if let Some(receipt) = self.replay(&request.operation_id, &digest)? {
            return Ok(CreateMemoResult {
                memo_id: MemoId::parse(&receipt.memo_id)?,
                commit_result: receipt,
            });
        }
        self.recover_pending()?;
        let now = epoch_millis()?;
        let chronology = request.chronology_epoch_ms.unwrap_or(now);
        let stamp = crate::calendar::journal_stamp(
            chronology,
            &self.config.time_zone,
            self.config.date_format,
        )?;
        let path = if let Some(path) = request.relative_path {
            path
        } else {
            RelativeWorkspacePath::parse(&stamp.filename)?
        };
        let io = self.io();
        let loaded = LoadedDocument::load(&io, path.clone())?;
        if let Some(expected) = &request.expected_document_fingerprint {
            self.check_baseline(&loaded, &request.operation_id, expected, &request.content)?;
        }
        let memo_id = mint_memo_id()?;
        let command = DocumentPatchCommand::Append {
            path: loaded.logical_path.clone(),
            expected_fingerprint: loaded.document.source().fingerprint().clone(),
            time_part: request.time_token.unwrap_or(stamp.time_token),
            content: request.content.clone(),
        };
        let changed = loaded.apply(
            request.operation_id.clone(),
            MemoIdentityChange::Append(memo_id.clone()),
            &command,
        )?;
        let fingerprint = changed.document.source().fingerprint().as_str();
        let projection = project_memo(
            &path,
            &memo_id,
            fingerprint,
            loaded.changed_memo(&changed, &memo_id)?,
            &self.config.time_zone,
        )?;
        let mut files = loaded.files(&changed)?;
        files.extend(crate::media_plan::plan_attachment_files(
            &io,
            &request.operation_id,
            &request.content,
            &request.pending_promotes,
        )?);
        let history = history_files(
            &io,
            &HistorySnapshotV1 {
                memo_id: memo_id.as_str().to_owned(),
                revision: 1,
                content: projection.body.clone(),
                file_fingerprint: fingerprint.to_owned(),
                created_at_ms: chronology,
            },
        )?;
        files.extend(history.files);
        let mut mutations = vec![DocumentPublication {
            history: Some(history.projection),
            mutation: SafProjectionMutation {
                operation_id: request.operation_id.as_str().to_owned(),
                kind: SafProjectionMutationKind::Create,
                memo_id: memo_id.as_str().to_owned(),
                expected_revision: 0,
                expected_fingerprint: None,
                projection: Some(projection),
                trashed_at_ms: None,
            },
        }];
        if request.pinned {
            files.extend(state_files(
                &io,
                &memo_id,
                &request.operation_id,
                StateChange::Pin(Some(now)),
                now,
            )?);
            mutations.push(DocumentPublication {
                history: None,
                mutation: pin_mutation(
                    format!("{}-pin", request.operation_id.as_str()),
                    &memo_id,
                    true,
                    1,
                    fingerprint,
                ),
            });
        }
        let staging = request.pending_promotes.clone();
        let commit_result = self.commit_transaction(TransactionInput {
            operation_id: request.operation_id,
            memo_id: memo_id.clone(),
            path,
            payload_digest: digest,
            files,
            mutations,
        })?;
        crate::media_plan::discard_private_staging(&staging);
        Ok(CreateMemoResult {
            memo_id,
            commit_result,
        })
    }

    /// Replaces only the addressed memo body while preserving neighboring bytes and identities.
    ///
    /// # Errors
    /// Rejects stale source versions and preserves the frozen transaction on I/O failure.
    pub fn update_memo(&self, request: UpdateMemoRequest) -> Result<UpdateMemoResult, LomoError> {
        let _lock = TransactionLock::acquire(&self.config.runtime_dir)?;
        let digest = payload_digest(&request)?;
        if let Some(receipt) = self.replay(&request.operation_id, &digest)? {
            return Ok(update_result(receipt));
        }
        self.recover_pending()?;
        let current = self.current_memo(&request.memo_id)?;
        let path = RelativeWorkspacePath::parse(&current.summary.source_path)?;
        let loaded = LoadedDocument::load(&self.io(), path.clone())?;
        self.check_baseline(
            &loaded,
            &request.operation_id,
            &request.expected_document_fingerprint,
            &request.content,
        )?;
        let command = DocumentPatchCommand::Replace {
            path: loaded.logical_path.clone(),
            expected_fingerprint: loaded.document.source().fingerprint().clone(),
            identity: loaded.memo(&request.memo_id)?.identity().clone(),
            content: request.content.clone(),
        };
        let changed = loaded.apply(
            request.operation_id.clone(),
            MemoIdentityChange::Update(request.memo_id.clone()),
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
        let revision = current
            .summary
            .content_revision
            .checked_add(1)
            .ok_or_else(|| validation("revision_overflow", "memo revision overflow"))?;
        let mut files = loaded.files(&changed)?;
        files.extend(crate::media_plan::plan_attachment_files(
            &self.io(),
            &request.operation_id,
            &request.content,
            &request.pending_promotes,
        )?);
        let history = history_files(
            &self.io(),
            &HistorySnapshotV1 {
                memo_id: request.memo_id.as_str().to_owned(),
                revision,
                content: projection.body.clone(),
                file_fingerprint: fingerprint.to_owned(),
                created_at_ms: epoch_millis()?,
            },
        )?;
        files.extend(history.files);
        let mutation = SafProjectionMutation {
            operation_id: request.operation_id.as_str().to_owned(),
            kind: SafProjectionMutationKind::Update,
            memo_id: request.memo_id.as_str().to_owned(),
            expected_revision: current.summary.content_revision,
            expected_fingerprint: Some(request.expected_document_fingerprint),
            projection: Some(projection),
            trashed_at_ms: None,
        };
        let staging = request.pending_promotes.clone();
        let receipt = self.commit_transaction(TransactionInput {
            operation_id: request.operation_id,
            memo_id: request.memo_id,
            path,
            payload_digest: digest,
            files,
            mutations: vec![DocumentPublication {
                mutation,
                history: Some(history.projection),
            }],
        })?;
        crate::media_plan::discard_private_staging(&staging);
        Ok(update_result(receipt))
    }

    /// Removes a memo block and retains a durable trash snapshot through the same transaction.
    ///
    /// # Errors
    /// Propagates stale identity, invalid trash data and platform/projection errors.
    pub fn delete_memo(&self, request: DeleteMemoRequest) -> Result<DeleteMemoResult, LomoError> {
        let _lock = TransactionLock::acquire(&self.config.runtime_dir)?;
        let digest = payload_digest(&request)?;
        if let Some(receipt) = self.replay(&request.operation_id, &digest)? {
            return Ok(delete_result(receipt));
        }
        self.recover_pending()?;
        let current = self.current_memo(&request.memo_id)?;
        let path = RelativeWorkspacePath::parse(&current.summary.source_path)?;
        let loaded = LoadedDocument::load(&self.io(), path.clone())?;
        self.check_baseline(
            &loaded,
            &request.operation_id,
            &request.expected_document_fingerprint,
            &current.body,
        )?;
        let target = loaded.memo(&request.memo_id)?;
        let command = DocumentPatchCommand::Remove {
            path: loaded.logical_path.clone(),
            expected_fingerprint: loaded.document.source().fingerprint().clone(),
            identity: target.identity().clone(),
        };
        let changed = loaded.apply(
            request.operation_id.clone(),
            MemoIdentityChange::Remove(request.memo_id.clone()),
            &command,
        )?;
        let fingerprint = changed.document.source().fingerprint().as_str();
        let projection = project_memo(
            &path,
            &request.memo_id,
            fingerprint,
            target,
            &self.config.time_zone,
        )?;
        let trashed_at = request.trashed_at_ms.map_or_else(epoch_millis, Ok)?;
        let trash = TrashRecordV1::try_new(TrashRecordCreate {
            memo_id: request.memo_id.as_str().to_owned(),
            source_path: path.as_str().to_owned(),
            time_part: target.time_part().to_owned(),
            source_fingerprint: fingerprint.to_owned(),
            chronology_epoch_ms: projection.chronology_epoch_ms,
            trashed_at_ms: trashed_at,
            body: target.content().to_owned(),
            tags: target.tags().to_vec(),
            attachments: target.attachments().to_vec(),
            reminders: projection.reminders.clone(),
            has_todo: target.has_todo(),
            has_url: target.has_url(),
        })?;
        let mut files = loaded.files(&changed)?;
        files.extend(state_files(
            &self.io(),
            &request.memo_id,
            &request.operation_id,
            StateChange::Trash(trashed_at),
            trashed_at,
        )?);
        let trash_path = RelativeWorkspacePath::parse(
            trash_record_relative_path(request.memo_id.as_str())?.as_str(),
        )?;
        let previous = self.io().read(&trash_path)?;
        files.push(PlannedFile::new(
            trash_path,
            previous.as_ref(),
            encode_trash_record(&trash)?,
        ));
        let mutation = SafProjectionMutation {
            operation_id: request.operation_id.as_str().to_owned(),
            kind: SafProjectionMutationKind::Delete,
            memo_id: request.memo_id.as_str().to_owned(),
            expected_revision: current.summary.content_revision,
            expected_fingerprint: Some(request.expected_document_fingerprint),
            projection: Some(projection),
            trashed_at_ms: Some(trashed_at),
        };
        let receipt = self.commit_transaction(TransactionInput {
            operation_id: request.operation_id,
            memo_id: request.memo_id,
            path,
            payload_digest: digest,
            files,
            mutations: vec![DocumentPublication {
                mutation,
                history: None,
            }],
        })?;
        Ok(delete_result(receipt))
    }

    /// Writes durable pin state, then publishes its query projection.
    ///
    /// # Errors
    /// Rejects operation reuse and invalid source identities or pin timestamps.
    pub fn pin_memo(&self, request: PinMemoRequest) -> Result<PinMemoResult, LomoError> {
        let _lock = TransactionLock::acquire(&self.config.runtime_dir)?;
        let digest = payload_digest(&request)?;
        if let Some(receipt) = self.replay(&request.operation_id, &digest)? {
            return Ok(PinMemoResult {
                commit_result: receipt,
            });
        }
        self.recover_pending()?;
        let current = self.current_memo(&request.memo_id)?;
        let path = RelativeWorkspacePath::parse(&current.summary.source_path)?;
        let loaded = LoadedDocument::load(&self.io(), path.clone())?;
        loaded.memo(&request.memo_id)?;
        let now = epoch_millis()?;
        let pinned_at = if request.pinned {
            Some(request.pinned_at_ms.unwrap_or(now))
        } else {
            None
        };
        if pinned_at.is_some_and(|timestamp| timestamp <= 0) {
            return Err(validation(
                "invalid_pin_timestamp",
                "pin timestamp must be positive",
            ));
        }
        let files = state_files(
            &self.io(),
            &request.memo_id,
            &request.operation_id,
            StateChange::Pin(pinned_at),
            now,
        )?;
        let mutation = pin_mutation(
            request.operation_id.as_str().to_owned(),
            &request.memo_id,
            request.pinned,
            current.summary.content_revision,
            loaded.fingerprint(),
        );
        let commit_result = self.commit_transaction(TransactionInput {
            operation_id: request.operation_id,
            memo_id: request.memo_id,
            path,
            payload_digest: digest,
            files,
            mutations: vec![DocumentPublication {
                mutation,
                history: None,
            }],
        })?;
        Ok(PinMemoResult { commit_result })
    }

    /// Reads an active memo from the disposable query projection.
    ///
    /// # Errors
    /// Propagates projection access failures.
    pub fn get_memo(&self, id: &MemoId) -> Result<Option<SessionMemoView>, LomoError> {
        let snapshot = self.with_store(|store| store.get_projected_memo(id.as_str()))?;
        Ok(snapshot
            .filter(|snapshot| !snapshot.summary.is_trashed)
            .map(|snapshot| SessionMemoView {
                memo_id: snapshot.summary.memo_id,
                source_path: snapshot.summary.source_path,
                file_fingerprint: snapshot.summary.file_fingerprint,
                body: snapshot.body,
                is_pinned: snapshot.summary.is_pinned,
                is_trashed: snapshot.summary.is_trashed,
                created_at_ms: snapshot.summary.created_at_ms,
                updated_at_ms: snapshot.summary.updated_at_ms,
            }))
    }

    /// Queries a bounded projection page.
    ///
    /// # Errors
    /// Propagates query validation and storage failures.
    pub fn list_memos(&self, query: &MemoQuery) -> Result<MemoPage, LomoError> {
        self.with_store(|store| store.query_memos(query, None, PageSize::new(256)?))
    }

    /// Queries one page from the session projection, including an optional sort boundary.
    ///
    /// # Errors
    /// Propagates query validation and storage failures.
    pub fn query_memos_page(
        &self,
        query: &MemoQuery,
        boundary: Option<&MemoQueryBoundary>,
        cursor: Option<&PageCursor>,
        page_size: PageSize,
    ) -> Result<MemoPage, LomoError> {
        self.with_store(|store| store.query_memos_with_boundary(query, boundary, cursor, page_size))
    }

    /// Counts rows accepted by the same predicate as [`Self::query_memos_page`].
    ///
    /// # Errors
    /// Propagates query validation and storage failures.
    pub fn query_count(&self, query: &MemoQuery) -> Result<u64, LomoError> {
        self.with_store(|store| store.query_count(query))
    }

    /// Reads the sidebar aggregate from the session projection.
    ///
    /// # Errors
    /// Propagates projection storage failures.
    pub fn sidebar_projection(&self) -> Result<SidebarProjection, LomoError> {
        self.with_store(Store::sidebar_projection)
    }

    /// Reads one projected snapshot, including trashed rows.
    ///
    /// # Errors
    /// Propagates projection storage failures.
    pub fn projected_memo(&self, memo_id: &str) -> Result<Option<MemoSnapshot>, LomoError> {
        self.with_store(|store| store.get_projected_memo(memo_id))
    }

    /// Canonical source-document fingerprint from the session projection.
    ///
    /// # Errors
    /// Propagates projection storage failures.
    pub fn source_document_fingerprint(
        &self,
        source_path: &str,
    ) -> Result<Option<String>, LomoError> {
        self.with_store(|store| store.source_document_fingerprint(source_path))
    }

    /// Reconstructs the cache from portable physical facts under transaction exclusion.
    ///
    /// # Errors
    /// Unresolved identities and corrupt facts leave the previous projection intact.
    pub fn rebuild_projection(&self) -> Result<RebuildResult, LomoError> {
        let _lock = TransactionLock::acquire(&self.config.runtime_dir)?;
        self.recover_pending()?;
        self.rebuild_locked()
    }

    pub(crate) fn rebuild_locked(&self) -> Result<RebuildResult, LomoError> {
        {
            let mut guard = self
                .store
                .lock()
                .map_err(|error| storage("store_lock_poisoned", error.to_string()))?;
            *guard = None;
        }
        let result = crate::rebuild::rebuild_projection(&self.config, &self.executor);
        let reopened = Store::open_projection(&self.config.cache_dir).map_err(|error| {
            storage(
                "projection_reopen_failed",
                format!("{error}; rebuild result: {result:?}"),
            )
        })?;
        let mut guard = self
            .store
            .lock()
            .map_err(|error| storage("store_lock_poisoned", error.to_string()))?;
        *guard = Some(reopened);
        drop(guard);
        result
    }

    pub(crate) fn current_memo(&self, id: &MemoId) -> Result<MemoSnapshot, LomoError> {
        self.with_store(|store| store.get_projected_memo(id.as_str()))?
            .filter(|memo| !memo.summary.is_trashed)
            .ok_or_else(|| validation("memo_not_found", "memo has no active projection"))
    }

    pub(crate) fn check_baseline(
        &self,
        document: &LoadedDocument,
        operation_id: &OperationId,
        expected: &str,
        draft: &str,
    ) -> Result<(), LomoError> {
        if let Err(error) = document.check_baseline(expected) {
            self.draft_store.save_conflict_evidence(&ConflictEvidence {
                operation_id: operation_id.clone(),
                path: document.path.clone(),
                baseline_fingerprint: expected.to_owned(),
                disk_fingerprint: document.fingerprint().to_owned(),
                draft_content: draft.to_owned(),
                recorded_at_ms: epoch_millis()?,
            })?;
            return Err(error);
        }
        Ok(())
    }

    pub(crate) fn io(&self) -> WorkspaceIo<'_> {
        WorkspaceIo {
            config: &self.config,
            executor: &self.executor,
        }
    }

    pub(crate) fn with_store<R>(
        &self,
        read: impl FnOnce(&Store) -> Result<R, LomoError>,
    ) -> Result<R, LomoError> {
        let guard = self
            .store
            .lock()
            .map_err(|error| storage("store_lock_poisoned", error.to_string()))?;
        let store = guard
            .as_ref()
            .ok_or_else(|| storage("store_rebuilding", "projection is being rebuilt"))?;
        let result = read(store);
        drop(guard);
        result
    }

    pub(crate) fn with_store_mut<R>(
        &self,
        write: impl FnOnce(&mut Store) -> Result<R, LomoError>,
    ) -> Result<R, LomoError> {
        let mut guard = self
            .store
            .lock()
            .map_err(|error| storage("store_lock_poisoned", error.to_string()))?;
        let store = guard
            .as_mut()
            .ok_or_else(|| storage("store_rebuilding", "projection is being rebuilt"))?;
        let result = write(store);
        drop(guard);
        result
    }
}

fn pin_mutation(
    operation_id: String,
    id: &MemoId,
    pinned: bool,
    revision: u64,
    fingerprint: &str,
) -> SafProjectionMutation {
    SafProjectionMutation {
        operation_id,
        kind: if pinned {
            SafProjectionMutationKind::Pin
        } else {
            SafProjectionMutationKind::Unpin
        },
        memo_id: id.as_str().to_owned(),
        expected_revision: revision,
        expected_fingerprint: Some(fingerprint.to_owned()),
        projection: None,
        trashed_at_ms: None,
    }
}

fn update_result(receipt: lomo_store::SafProjectionCommitResult) -> UpdateMemoResult {
    UpdateMemoResult {
        file_fingerprint: receipt.file_fingerprint.clone(),
        event_sequence: receipt.event_sequence,
        commit_result: receipt,
    }
}

fn delete_result(receipt: lomo_store::SafProjectionCommitResult) -> DeleteMemoResult {
    DeleteMemoResult {
        file_fingerprint: receipt.file_fingerprint.clone(),
        event_sequence: receipt.event_sequence,
        commit_result: receipt,
    }
}

fn require_new_memo_content(content: &str) -> Result<(), LomoError> {
    if content.trim().is_empty() {
        return Err(crate::error::cancelled(
            "memo_creation_cancelled",
            "empty draft does not create a memo",
        ));
    }
    Ok(())
}
