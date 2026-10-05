//! Shared application lifecycle and commands. Physical writes use one frozen transaction path.

use std::sync::{Arc, Mutex, RwLock, atomic::AtomicU64};

use lomo_core::{LomoError, OperationId, PageSize, PlatformActionExecutor, RelativeWorkspacePath};
use lomo_store::{
    DocumentPublication, MemoFilters, MemoPage, MemoQuery, MemoQueryBoundary, MemoQueryStart,
    MemoSnapshot, MemoWindowSides, PageCursor, ProjectionClock, ReaderPoolOptions, RebuildResult,
    SafProjectionMutation, SafProjectionMutationKind, SidebarProjection, Store, StoreReader,
    StoreReaderPool,
};
use lomo_workspace::{
    DocumentPatchCommand, HistorySnapshotV1, MemoId, MemoIdentityChange, TrashRecordCreate,
    TrashRecordV1, encode_trash_record, trash_record_relative_path,
};

use crate::{
    config::WorkspaceSessionConfig,
    csprng::{generate_device_id, mint_memo_id},
    document_plan::{LoadedDocument, project_memo},
    draft::{ConflictEvidence, DraftStore},
    error::{storage, validation},
    intent::IntentJournal,
    lock::TransactionLock,
    private_io::{read_optional, write_atomic},
    record_plan::{StateChange, history_files, state_files},
    transaction::{PlannedFile, TransactionInput, payload_digest},
    types::{
        CreateMemoRequest, CreateMemoResult, DeleteMemoRequest, DeleteMemoResult, PinMemoRequest,
        PinMemoResult, PinPolicy, SessionMemoView, UpdateMemoRequest, UpdateMemoResult,
    },
    workspace_io::{WorkspaceIo, epoch_millis},
};

pub struct WorkspaceSession {
    pub(crate) config: WorkspaceSessionConfig,
    pub(crate) executor: Arc<dyn PlatformActionExecutor>,
    pub(crate) intent_journal: IntentJournal,
    device_id: String,
    store: Mutex<Option<Store>>,
    readers: StoreReaderPool,
    read_gate: RwLock<()>,
    mount: Mutex<Option<RebuildResult>>,
    pub(crate) draft_store: DraftStore,
    pub(crate) search_epoch: AtomicU64,
    /// Ranked fuzzy hits memoized per query fingerprint and projection
    /// revision — a continuation cursor paginates the snapshot instead of
    /// rescanning and rescoring every candidate.
    pub(crate) fuzzy_results: Mutex<std::collections::VecDeque<crate::search::FuzzyResult>>,
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
            readers: StoreReaderPool::new(config.cache_dir.clone(), ReaderPoolOptions::default()),
            read_gate: RwLock::new(()),
            draft_store: DraftStore::new(&config.state_dir)?,
            config,
            executor,
            device_id,
            store: Mutex::new(Some(store)),
            mount: Mutex::new(None),
            search_epoch: AtomicU64::new(0),
            fuzzy_results: Mutex::new(std::collections::VecDeque::new()),
        };
        let pending = session.recover_files_for_mount()?;
        let floor = session.intent_journal.clock_floor()?;
        session.with_store_mut(|store| store.restore_clock_floor(floor))?;
        session.rebuild_locked(!pending.is_empty())?;
        session.acknowledge_mounted_operations(&pending)?;
        Ok(session)
    }

    #[must_use]
    pub fn device_id(&self) -> &str {
        &self.device_id
    }

    /// Result of the most recent mount or explicit projection rebuild.
    ///
    /// # Errors
    /// Storage when the session has not completed a mount.
    pub fn last_mount_result(&self) -> Result<RebuildResult, LomoError> {
        self.mount
            .lock()
            .map_err(|error| storage("store_lock_poisoned", error.to_string()))?
            .clone()
            .ok_or_else(|| storage("mount_result_missing", "session has not completed a mount"))
    }

    /// Closes the current operation epoch and retires its committed receipts.
    ///
    /// The retirement witness is durable before any receipt disappears, so a retry that names a
    /// retired operation fails with `operation_expired` instead of re-executing it. Returns
    /// `false` while pending operations still block retirement; the caller may retry later.
    ///
    /// # Errors
    /// Storage or corruption failures while publishing the witness or deleting receipts.
    pub fn seal(&self) -> Result<bool, LomoError> {
        let _lock = TransactionLock::acquire(&self.config.runtime_dir)?;
        self.intent_journal.seal()
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
        request.validate()?;
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
                batch_targets: Vec::new(),
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
        request.validate()?;
        let _lock = TransactionLock::acquire(&self.config.runtime_dir)?;
        let digest = payload_digest(&request)?;
        if let Some(receipt) = self.replay(&request.operation_id, &digest)? {
            return Ok(update_result(receipt));
        }
        self.recover_pending()?;
        let current = self.current_memo(&request.memo_id)?;
        let path = RelativeWorkspacePath::parse(&current.summary.source_path)?;
        let loaded = LoadedDocument::load_existing(&self.io(), path.clone())?;
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
            batch_targets: Vec::new(),
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
    /// Missing activity source (`memo_source_missing`), stale identity, invalid trash data,
    /// and platform/projection errors.
    pub fn delete_memo(&self, request: DeleteMemoRequest) -> Result<DeleteMemoResult, LomoError> {
        request.validate()?;
        let _lock = TransactionLock::acquire(&self.config.runtime_dir)?;
        let digest = payload_digest(&request)?;
        if let Some(receipt) = self.replay(&request.operation_id, &digest)? {
            return Ok(delete_result(receipt));
        }
        self.recover_pending()?;
        let current = self.current_memo(&request.memo_id)?;
        let path = RelativeWorkspacePath::parse(&current.summary.source_path)?;
        let loaded = LoadedDocument::load_existing(&self.io(), path.clone())?;
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
            batch_targets: Vec::new(),
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
        request.validate()?;
        let _lock = TransactionLock::acquire(&self.config.runtime_dir)?;
        let digest = payload_digest(&request)?;
        let (operation_id, memo_id, pin) = request.into_parts();
        if let Some(receipt) = self.replay(&operation_id, &digest)? {
            return Ok(PinMemoResult {
                commit_result: receipt,
            });
        }
        self.recover_pending()?;
        let current = self.current_memo(&memo_id)?;
        let path = RelativeWorkspacePath::parse(&current.summary.source_path)?;
        let loaded = LoadedDocument::load_existing(&self.io(), path.clone())?;
        loaded.memo(&memo_id)?;
        let now = epoch_millis()?;
        let pinned_at = match pin {
            PinPolicy::Unpinned => None,
            PinPolicy::Pinned { at_ms } => Some(at_ms.unwrap_or(now)),
        };
        let files = state_files(
            &self.io(),
            &memo_id,
            &operation_id,
            StateChange::Pin(pinned_at),
            now,
        )?;
        let mutation = pin_mutation(
            operation_id.as_str().to_owned(),
            &memo_id,
            pin.is_pinned(),
            current.summary.content_revision,
            loaded.fingerprint(),
        );
        let commit_result = self.commit_transaction(TransactionInput {
            operation_id,
            memo_id,
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
        let snapshot = self.with_reader(|store| store.get_projected_memo(id.as_str()))?;
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

    /// Live projection publication clock for the mounted session store.
    ///
    /// # Errors
    /// Propagates projection access failures.
    pub fn projection_clock(&self) -> Result<ProjectionClock, LomoError> {
        self.with_store(|store| Ok(store.projection_clock()))
    }

    /// Queries a bounded projection page.
    ///
    /// # Errors
    /// Propagates query validation and storage failures.
    pub fn list_memos(&self, query: &MemoQuery) -> Result<MemoPage, LomoError> {
        self.with_reader(|store| store.query_memos(query, None, PageSize::new(256)?))
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
        crate::search::validate_filters(&query.filters)?;
        self.with_reader(|store| {
            store.query_memos_with_boundary(query, boundary, cursor, page_size)
        })
    }

    /// Queries one page from an explicit start in the current query order.
    ///
    /// # Errors
    /// Propagates query validation and storage failures.
    pub fn query_memos_starting_at(
        &self,
        query: &MemoQuery,
        boundary: Option<&MemoQueryBoundary>,
        start: MemoQueryStart<'_>,
        page_size: PageSize,
    ) -> Result<MemoPage, LomoError> {
        crate::search::validate_filters(&query.filters)?;
        self.with_reader(|store| store.query_memos_starting_at(query, boundary, start, page_size))
    }

    /// Counts rows accepted by the same predicate as [`Self::query_memos_page`].
    ///
    /// # Errors
    /// Propagates query validation and storage failures.
    pub fn query_count(&self, query: &MemoQuery) -> Result<u64, LomoError> {
        crate::search::validate_filters(&query.filters)?;
        self.with_reader(|store| store.query_count(query))
    }

    /// Which of `memo_ids` still satisfy `query`, split by where they sort
    /// relative to a refresh reply's window — strictly above its head bound /
    /// strictly below its tail bound, each side in live query order.
    ///
    /// The merge replays this order over the loaded set: a loaded card absent
    /// from both sides and the reply has left the result set, and the sides
    /// give every survivor its live rank instead of a stale slot.
    ///
    /// # Errors
    /// Propagates query validation and storage failures.
    pub fn matching_memo_window(
        &self,
        query: &MemoQuery,
        memo_ids: &[String],
        head: Option<&PageCursor>,
        tail: Option<&PageCursor>,
    ) -> Result<MemoWindowSides, LomoError> {
        crate::search::validate_filters(&query.filters)?;
        self.with_reader(|store| store.matching_memo_window(query, memo_ids, head, tail))
    }

    /// The fuzzy counterpart of [`Self::matching_memo_window`]: the same
    /// above/below split measured in scored hit positions instead of keyset
    /// bounds, read from the same snapshot pagination uses.
    ///
    /// # Errors
    /// Propagates filter validation, projection storage and
    /// `stale_search_result` failures.
    pub fn fuzzy_window_sides(
        &self,
        text: &str,
        filters: &MemoFilters,
        memo_ids: &[String],
        window_start: u64,
        window_end: u64,
    ) -> Result<MemoWindowSides, LomoError> {
        crate::search::validate_filters(filters)?;
        crate::search::fuzzy_window_sides(self, text, filters, memo_ids, window_start, window_end)
    }

    /// Reads the sidebar aggregate from the session projection.
    ///
    /// # Errors
    /// Propagates projection storage failures.
    pub fn sidebar_projection(&self) -> Result<SidebarProjection, LomoError> {
        self.with_reader(StoreReader::sidebar_projection)
    }

    /// Reads one projected snapshot, including trashed rows.
    ///
    /// # Errors
    /// Propagates projection storage failures.
    pub fn projected_memo(&self, memo_id: &str) -> Result<Option<MemoSnapshot>, LomoError> {
        self.with_reader(|store| store.get_projected_memo(memo_id))
    }

    /// Reads projected snapshots for one id set in a single batched query —
    /// the store chunks the `IN` list internally. Ids without a projected row
    /// simply produce no entry; callers needing an exact set check each id.
    ///
    /// # Errors
    /// Propagates projection storage failures.
    pub fn projected_memos(&self, memo_ids: &[String]) -> Result<Vec<MemoSnapshot>, LomoError> {
        self.with_reader(|store| store.get_projected_memos(memo_ids))
    }

    /// Live projection word/character rows for every active memo.
    ///
    /// # Errors
    /// Propagates projection storage failures.
    pub fn memo_statistics_rows(&self) -> Result<Vec<lomo_store::MemoStatisticsRow>, LomoError> {
        self.with_reader(StoreReader::memo_statistics_rows)
    }

    /// Commits facts from a completed workspace document write into the session projection.
    ///
    /// # Errors
    /// Propagates projection CAS and storage failures.
    pub fn commit_workspace_document_facts(
        &self,
        mutation: &SafProjectionMutation,
    ) -> Result<lomo_store::SafProjectionCommitResult, LomoError> {
        self.with_store_mut(|store| store.commit_workspace_document_facts(mutation))
    }

    /// Canonical source-document fingerprint from the session projection.
    ///
    /// # Errors
    /// Propagates projection storage failures.
    pub fn source_document_fingerprint(
        &self,
        source_path: &str,
    ) -> Result<Option<String>, LomoError> {
        self.with_reader(|store| store.source_document_fingerprint(source_path))
    }

    /// Active memo ids projected from one source document path.
    ///
    /// # Errors
    ///
    /// Propagates path validation and projection storage failures.
    pub fn active_memo_ids_for_source_path(
        &self,
        source_path: &str,
    ) -> Result<Vec<String>, LomoError> {
        self.with_reader(|store| store.active_memo_ids_for_source_path(source_path))
    }

    /// Reconstructs the cache from portable physical facts under transaction exclusion.
    ///
    /// # Errors
    /// Unresolved identities and corrupt facts leave the previous projection intact.
    pub fn rebuild_projection(&self) -> Result<RebuildResult, LomoError> {
        let _lock = TransactionLock::acquire(&self.config.runtime_dir)?;
        self.recover_pending()?;
        self.rebuild_locked(false)
    }

    /// Reconciles the projection using watcher-supplied changed paths.
    ///
    /// The observed set scopes the listing diff: only those paths are re-verified against the
    /// committed snapshot, and any path whose projection scope cannot be proven falls back to
    /// the full scan. Anything the watcher failed to report stays certified until the next
    /// digest-diff reconcile.
    ///
    /// # Errors
    /// Propagates scan and projection storage failures.
    pub fn reconcile_observed_paths(
        &self,
        changed_paths: &[RelativeWorkspacePath],
    ) -> Result<RebuildResult, LomoError> {
        let _lock = TransactionLock::acquire(&self.config.runtime_dir)?;
        self.recover_pending()?;
        self.reconcile_locked(false, Some(changed_paths))
    }

    pub(crate) fn rebuild_locked(&self, force_scan: bool) -> Result<RebuildResult, LomoError> {
        self.reconcile_locked(force_scan, None)
    }

    /// One reconcile pass: cheap listing, digest short-circuit, path-scoped incremental apply,
    /// and full materialize as the truth rebuilder when scope cannot be proven.
    fn reconcile_locked(
        &self,
        force_scan: bool,
        observed: Option<&[RelativeWorkspacePath]>,
    ) -> Result<RebuildResult, LomoError> {
        let evidence = crate::rebuild::list_workspace_listing(&self.config, &self.executor)?;
        let listing = evidence.admitted_listing()?;
        if !force_scan {
            if let Some(digest) = evidence.listing_digest() {
                let matched = self.with_store(|store| {
                    Ok(store.workspace_listing_digest()?.as_deref() == Some(digest.as_str()))
                })?;
                if matched {
                    let result = self.with_store(Store::reconciled_live_result)?;
                    return self.record_mount(result);
                }
            }
            let scoped = {
                let mut guard = self
                    .store
                    .lock()
                    .map_err(|error| storage("store_lock_poisoned", error.to_string()))?;
                match guard.as_mut() {
                    Some(store) => crate::rebuild_incremental::reconcile_scoped(
                        &self.config,
                        &self.executor,
                        store,
                        listing,
                        observed,
                    )?,
                    None => None,
                }
            };
            if let Some(result) = scoped {
                return self.record_mount(result);
            }
        }
        let inventory = crate::rebuild::scan_projection_inventory_from_listing(
            &self.config,
            &self.executor,
            listing,
        )?;
        let reconciled = {
            let guard = self
                .store
                .lock()
                .map_err(|error| storage("store_lock_poisoned", error.to_string()))?;
            match guard.as_ref() {
                Some(store) => inventory.try_reconcile(store)?,
                None => None,
            }
        };
        if let Some(result) = reconciled {
            self.with_store_mut(|store| {
                crate::rebuild_incremental::align_listing_snapshot(store, inventory.listing_rows())
            })?;
            return self.record_mount(result);
        }
        let _read_exclusion = self
            .read_gate
            .write()
            .map_err(|error| storage("projection_gate_poisoned", error.to_string()))?;
        self.readers.clear_idle()?;
        {
            let mut guard = self
                .store
                .lock()
                .map_err(|error| storage("store_lock_poisoned", error.to_string()))?;
            *guard = None;
        }
        let result = crate::rebuild::materialize_scanned_projection(&self.config, &inventory);
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
        let result = result?;
        self.persist_scanned_listing_digest(inventory.listing_digest())?;
        self.record_mount(result)
    }

    /// Persists the digest of the exact `file_listing` rows the materialize commit wrote.
    ///
    /// Re-listing here would certify the projection against a later directory state it never read,
    /// so a concurrent external change could be skipped permanently. The digest therefore comes
    /// from the inventory — the provoking listing's fingerprints already refreshed with the
    /// post-write tokens of files the scan itself wrote — and never from a re-list.
    fn persist_scanned_listing_digest(&self, digest: &str) -> Result<(), LomoError> {
        self.with_store_mut(|store| store.set_workspace_listing_digest(digest))
    }

    fn record_mount(&self, result: RebuildResult) -> Result<RebuildResult, LomoError> {
        *self
            .mount
            .lock()
            .map_err(|error| storage("store_lock_poisoned", error.to_string()))? =
            Some(result.clone());
        Ok(result)
    }

    pub(crate) fn current_memo(&self, id: &MemoId) -> Result<MemoSnapshot, LomoError> {
        self.with_reader(|store| store.get_projected_memo(id.as_str()))?
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

    pub(crate) fn with_reader<R>(
        &self,
        read: impl FnOnce(&StoreReader) -> Result<R, LomoError>,
    ) -> Result<R, LomoError> {
        let _admission = self
            .read_gate
            .try_read()
            .map_err(|error| storage("projection_read_unavailable", error.to_string()))?;
        let lease = self.readers.checkout()?;
        read(lease.reader()?)
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
        batch_targets: Vec::new(),
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
