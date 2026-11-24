//! Shared application lifecycle and commands. Physical writes use one frozen transaction path.

use std::sync::{
    Arc, Mutex, RwLock,
    atomic::{AtomicBool, AtomicU64, Ordering},
};

use lomo_core::{LomoError, OperationId, PageSize, PlatformActionExecutor, RelativeWorkspacePath};
use lomo_store::{
    DocumentPublication, MemoFilters, MemoPage, MemoQuery, MemoQueryBoundary, MemoQueryStart,
    MemoSnapshot, MemoWindowSides, PageCursor, ProjectionClock, ReaderPoolOptions, RebuildResult,
    SafProjectionMutation, SafProjectionMutationKind, SidebarProjection, Store, StoreReader,
    StoreReaderPool,
};
use lomo_workspace::{
    DocumentPatchCommand, HistorySnapshotV1, MemoId, MemoIdentityChange, encode_trash_record,
    trash_record_relative_path,
};

use crate::{
    config::WorkspaceSessionConfig,
    csprng::generate_device_id,
    document_plan::{DocumentRead, LoadedDocument, project_memo},
    draft::{ConflictEvidence, DraftStore},
    error::{storage, validation},
    intent::IntentJournal,
    lock::TransactionLock,
    private_io::{read_optional, write_atomic},
    record_plan::{
        StateChange, history_files, history_files_after_tip, state_files, trash_snapshot,
    },
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
    pub(crate) intent_journal: Arc<IntentJournal>,
    pub(crate) write_behind: crate::write_behind::WriteBehind,
    device_id: String,
    projection_verified: AtomicBool,
    store: Mutex<Option<Store>>,
    readers: StoreReaderPool,
    read_gate: RwLock<()>,
    mount: Mutex<Option<RebuildResult>>,
    pub(crate) draft_store: DraftStore,
    /// Token-keyed decoded draft projections shared by the sweep and attachment
    /// queries — a draft whose file token still matches never pays a decode.
    pub(crate) draft_ref_cache: Mutex<crate::media_index::DraftRefCache>,
    /// Unified-stamp memoized attachment index — the committed revision plus
    /// pending-record, draft-evidence, and stage-ledger file tokens decide
    /// whether a read rebuilds or serves the retained index.
    pub(crate) attachment_index_cache: Mutex<crate::media_index::AttachmentIndexCache>,
    pub(crate) search_epoch: AtomicU64,
    /// Ranked fuzzy hits memoized per (query fingerprint, projection
    /// revision) — a continuation cursor paginates the snapshot instead of
    /// rescanning and rescoring every candidate, and a prefix-extending
    /// keystroke derives its candidate set from the cached ancestor's hits.
    pub(crate) fuzzy_results: Mutex<lru::LruCache<(String, u64), crate::search::FuzzyResult>>,
    /// Hydrated fuzzy candidate corpus plus scan counters — one build per
    /// `(filters, revision)` publication, shared by every keystroke there.
    pub(crate) fuzzy_scan: Mutex<crate::search::FuzzyScan>,
    /// Revision-keyed query projection shared by search, tasks, review and
    /// reminder planning — collected walks and the derived views cut from
    /// them are functions of one committed revision and invalidate by key.
    pub(crate) query_projection: Mutex<crate::paging::QueryProjection>,
}

impl WorkspaceSession {
    /// Host-private state root for adapters sharing this session's durable generation.
    #[must_use]
    pub fn private_state_dir(&self) -> &std::path::Path {
        &self.config.state_dir
    }

    /// Opens a readable retained projection before workspace revalidation.
    ///
    /// # Errors
    /// Propagates private storage, recovery and projection failures.
    pub fn open_for_presentation(
        config: WorkspaceSessionConfig,
        executor: Arc<dyn PlatformActionExecutor>,
    ) -> Result<Self, LomoError> {
        Self::open_internal(config, executor, true)
    }

    /// Opens private state and recovers pending transactions before admitting new commands.
    ///
    /// # Errors
    /// Private storage, corrupt journals and unresolved physical conflicts fail closed.
    pub fn open(
        config: WorkspaceSessionConfig,
        executor: Arc<dyn PlatformActionExecutor>,
    ) -> Result<Self, LomoError> {
        Self::open_internal(config, executor, false)
    }

    fn open_internal(
        config: WorkspaceSessionConfig,
        executor: Arc<dyn PlatformActionExecutor>,
        retain_projection: bool,
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
        let state_layout = lomo_workspace::SessionStateLayout::for_state_dir(&config.state_dir);
        let identity_path = state_layout.device_id.clone();
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
        let readers = StoreReaderPool::new(config.cache_dir.clone(), ReaderPoolOptions::default());
        // One database generation pays exactly one `quick_check`: the writer's
        // open above. Pooled readers inherit the verdict through the file token
        // and only re-scan when a different file or generation shows up.
        if let Some(witness) = store.integrity_witness() {
            readers.mark_verified(witness)?;
        }
        let intent_journal = Arc::new(IntentJournal::new(&state_layout)?);
        let session = Self {
            write_behind: crate::write_behind::WriteBehind::new(
                config.clone(),
                Arc::clone(&executor),
                Arc::clone(&intent_journal),
            ),
            intent_journal,
            readers,
            read_gate: RwLock::new(()),
            draft_store: DraftStore::new(&state_layout)?,
            draft_ref_cache: Mutex::new(crate::media_index::DraftRefCache::new()),
            attachment_index_cache: Mutex::new(crate::media_index::AttachmentIndexCache::new()),
            config,
            executor,
            device_id,
            projection_verified: AtomicBool::new(true),
            store: Mutex::new(Some(store)),
            mount: Mutex::new(None),
            search_epoch: AtomicU64::new(0),
            fuzzy_results: Mutex::new(lru::LruCache::new(crate::search::FUZZY_CACHE_ENTRIES)),
            fuzzy_scan: Mutex::new(crate::search::FuzzyScan::new()),
            query_projection: Mutex::new(crate::paging::QueryProjection::new()),
        };
        let pending = session.recover_files_for_mount()?;
        let floor = session.intent_journal.clock_floor()?;
        // Decide before restoring the clock: a migration can intentionally invalidate a
        // projection by resetting its revision, and a journal floor cannot certify its rows.
        let retained = retain_projection
            && pending.is_empty()
            && session.with_store(|store| {
                Ok(store.high_water_revision() > 0
                    && store.high_water_revision() >= floor.core_revision
                    && store.workspace_listing_digest()?.is_some())
            })?;
        session.with_store_mut(|store| store.restore_clock_floor(floor))?;
        if retained {
            session.projection_verified.store(false, Ordering::Release);
        } else {
            session.rebuild_locked(!pending.is_empty())?;
            session.acknowledge_mounted_operations(&pending)?;
            session.evict_stale_baselines()?;
        }
        Ok(session)
    }

    /// Blocks until every committed command's bytes reached the workspace.
    ///
    /// Commands return once their intent is durable and published; workspace files converge
    /// behind them. Callers that hand workspace bytes to another owner (export, an external
    /// editor, tests asserting on disk) settle first.
    ///
    /// # Errors
    /// The replay failure of the oldest unconverged intent.
    pub fn settle_workspace(&self) -> Result<(), LomoError> {
        self.write_behind.settle()
    }

    /// Backlog and stall state of write-behind convergence.
    ///
    /// # Errors
    /// Storage when the queue lock is poisoned.
    pub fn write_convergence(&self) -> Result<crate::write_behind::WriteConvergence, LomoError> {
        self.write_behind.convergence()
    }

    /// Whether the mounted projection has been reconciled in this session.
    #[must_use]
    pub fn projection_is_verified(&self) -> bool {
        self.projection_verified.load(Ordering::Acquire)
    }

    pub(crate) fn require_verified_projection(&self) -> Result<(), LomoError> {
        if self.projection_is_verified() {
            Ok(())
        } else {
            Err(storage(
                "projection_revalidating",
                "workspace verification must complete before writes",
            ))
        }
    }

    #[must_use]
    pub fn device_id(&self) -> &str {
        &self.device_id
    }

    /// Cumulative token-hit/decode evidence for retained conflict-evidence drafts.
    ///
    /// Attachment-index builds decode a draft only when its file token (name, size,
    /// modification time) changed; unchanged tokens reuse the retained projection.
    ///
    /// # Errors
    /// Storage when the cache lock is poisoned.
    pub fn draft_evidence_stats(
        &self,
    ) -> Result<crate::media_index::DraftEvidenceStats, LomoError> {
        self.draft_ref_cache
            .lock()
            .map(|cache| cache.stats)
            .map_err(|error| storage("draft_ref_cache_poisoned", error.to_string()))
    }

    /// Reader-side `PRAGMA quick_check` executions since session open — the
    /// startup-duplication probe: one projection generation verifies once inside
    /// `Store::open_projection`, and every pooled reader inherits that verdict
    /// through the database-file token, so a healthy session reports `0` here
    /// regardless of how many readers it has opened.
    #[must_use]
    pub fn reader_integrity_checks(&self) -> u64 {
        self.readers.integrity_checks()
    }

    /// Cumulative build/hit evidence for the stamp-memoized attachment index.
    ///
    /// `attachment_index`/`observe_attachments` rebuild only when the unified stamp —
    /// committed revision plus pending-record, draft-evidence, and stage-ledger file
    /// tokens — deviates from the last build's.
    ///
    /// # Errors
    /// Storage when the cache lock is poisoned.
    pub fn attachment_index_stats(
        &self,
    ) -> Result<crate::media_index::AttachmentIndexStats, LomoError> {
        self.attachment_index_cache
            .lock()
            .map(|cache| cache.stats)
            .map_err(|error| storage("attachment_index_cache_poisoned", error.to_string()))
    }

    /// Cumulative fuzzy-scan evidence: snapshot hits, prefix-seeded and full
    /// scans, epoch supersessions and scored-candidate counts.
    ///
    /// # Errors
    /// Storage when the scan-state lock is poisoned.
    pub fn fuzzy_scan_stats(&self) -> Result<crate::search::FuzzyScanStats, LomoError> {
        self.fuzzy_scan
            .lock()
            .map(|scan| scan.stats)
            .map_err(|error| storage("fuzzy_scan_poisoned", error.to_string()))
    }

    /// Cumulative query-projection evidence: shared summary walks and the
    /// per-screen derived sets (tasks, reminder sessions) keyed by revision.
    ///
    /// # Errors
    /// Storage when the projection lock is poisoned.
    pub fn projection_query_stats(&self) -> Result<crate::paging::ProjectionQueryStats, LomoError> {
        self.query_projection
            .lock()
            .map(|projection| projection.stats)
            .map_err(|error| storage("query_projection_poisoned", error.to_string()))
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
        let _held = self.write_behind.exclusive()?;
        self.intent_journal.seal()
    }

    /// Appends a new independently identified memo to its dated Markdown document.
    ///
    /// The composition of [`Self::accept_create`] and [`AcceptedCreate::commit`].
    ///
    /// # Errors
    /// Rejects stale baselines, operation reuse, invalid content and physical write failures.
    pub fn create_memo(&self, request: CreateMemoRequest) -> Result<CreateMemoResult, LomoError> {
        self.accept_create(request)?
            .commit()
            .map_err(|withdrawn| withdrawn.into_error())
    }

    /// Admits a create and publishes its pending projection row before any workspace I/O.
    ///
    /// The acceptance holds the transaction lock and the minted identity; the row is queryable
    /// with `is_pending` until [`AcceptedCreate::commit`] upgrades it in place. A commit failure
    /// or an acceptance dropped without commit withdraws the row. A replayed operation is
    /// accepted with its committed receipt and no pending row.
    ///
    /// # Errors
    /// Invalid requests, an unverified projection, operation reuse and projection failures.
    pub fn accept_create(
        &self,
        request: CreateMemoRequest,
    ) -> Result<AcceptedCreate<'_>, LomoError> {
        request.validate()?;
        self.require_verified_projection()?;
        require_new_memo_content(&request.content)?;
        let lock = self.write_behind.exclusive()?;
        let digest = payload_digest(&request)?;
        let replay = self.replay(&lock, &request.operation_id, &digest)?;
        if let Some(receipt) = replay {
            drop(lock);
            return Ok(AcceptedCreate {
                session: self,
                state: AcceptedState::Replayed(CreateMemoResult {
                    memo_id: MemoId::parse(&receipt.memo_id)?,
                    commit_result: receipt,
                }),
            });
        }
        self.recover_pending(&lock)?;
        let now = epoch_millis()?;
        let chronology = request.chronology_epoch_ms.unwrap_or(now);
        let date_format = request.date_format.unwrap_or(self.config.date_format);
        let timestamp_format = request
            .timestamp_format
            .unwrap_or(crate::calendar::TimestampFormat::HhMmSs);
        let stamp = crate::calendar::journal_stamp_with_formats(
            chronology,
            &self.config.time_zone,
            date_format,
            timestamp_format,
        )?;
        let path = if let Some(path) = request.relative_path.clone() {
            path
        } else {
            RelativeWorkspacePath::parse(&stamp.filename)?
        };
        let time_part = request.time_token.clone().unwrap_or(stamp.time_token);
        let date_key = crate::document_plan::filename_stem(&path)?.to_owned();
        // Pending and durable rows must share the chronology a cold rebuild derives from the
        // document, including its second precision. The event timestamp belongs to history only.
        let projected_chronology =
            crate::calendar::memo_chronology(&date_key, &time_part, &self.config.time_zone)?;
        let begun = self.with_store_mut(|store| {
            store.begin_saf_memo_create(&lomo_store::SafMemoCreateBegin {
                operation_id: request.operation_id.as_str().to_owned(),
                date_key,
                time_part: time_part.clone(),
                chronology_epoch_ms: projected_chronology,
                source_path: path.as_str().to_owned(),
                body: request.content.clone(),
            })
        })?;
        let memo_id = MemoId::parse(&begun.memo_id)?;
        Ok(AcceptedCreate {
            session: self,
            state: AcceptedState::Pending(Box::new(PendingCreate {
                _lock: lock,
                publication: lomo_store::SafProjectionCommitResult {
                    operation_id: request.operation_id.as_str().to_owned(),
                    memo_id: begun.memo_id,
                    core_revision: begun.core_revision,
                    event_sequence: begun.event_sequence,
                    content_revision: 0,
                    file_fingerprint: String::new(),
                    scopes: begun.scopes,
                    idempotent_replay: begun.idempotent_replay,
                    deleted_memos: Vec::new(),
                },
                plan: CreatePlan {
                    request,
                    digest,
                    path,
                    time_part,
                    chronology,
                    now,
                    memo_id,
                },
            })),
        })
    }

    #[expect(
        clippy::too_many_lines,
        reason = "create freezes markdown, attachments, history, and optional pin in one transaction"
    )]
    fn commit_create(
        &self,
        held: &crate::write_behind::Exclusive<'_>,
        plan: CreatePlan,
    ) -> Result<CreateMemoResult, LomoError> {
        let CreatePlan {
            request,
            digest,
            path,
            time_part,
            chronology,
            now,
            memo_id,
        } = plan;
        let io = self.io();
        // Creation follows the physical source, including verified absence. A retained query
        // projection cannot turn an externally removed document into a required existing file.
        let read = if self
            .with_reader(|store| store.source_document_fingerprint(path.as_str()))?
            .is_some()
        {
            DocumentRead::CataloguedDestination
        } else {
            DocumentRead::UncataloguedDestination
        };
        let loaded = LoadedDocument::load(&io, path.clone(), read)?;
        if loaded.original.is_none() {
            let memo_removes = self.active_memo_ids_for_source_path(path.as_str())?;
            if !memo_removes.is_empty() {
                // These rows just lost their source attestation. Preserve the admitted pending
                // create and use the normal incremental fact publisher to retire stale siblings.
                self.with_store_mut(|store| {
                    store.apply_scanned_incremental(&lomo_store::ScannedIncrementalFacts {
                        listing_removes: vec![path.as_str().to_owned()],
                        memo_removes,
                        ..lomo_store::ScannedIncrementalFacts::default()
                    })
                })?;
            }
        }
        if let Some(expected) = &request.expected_document_fingerprint {
            self.check_baseline(&loaded, &request.operation_id, expected, &request.content)?;
        }
        let command = DocumentPatchCommand::Append {
            path: loaded.logical_path.clone(),
            expected_fingerprint: loaded.document.source().fingerprint().clone(),
            time_part,
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
            crate::media_plan::AttachmentBaseline::NewMemo,
            &request.pending_promotes,
        )?);
        let history = history_files_after_tip(
            &HistorySnapshotV1 {
                memo_id: memo_id.as_str().to_owned(),
                revision: 1,
                content: projection.body.clone(),
                file_fingerprint: fingerprint.to_owned(),
                created_at_ms: chronology,
            },
            None,
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
        let commit_result = self.commit_transaction(
            held,
            TransactionInput {
                operation_id: request.operation_id,
                memo_id: memo_id.clone(),
                path,
                payload_digest: digest,
                files,
                mutations,
                staging: request.pending_promotes,
            },
        )?;
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
        self.require_verified_projection()?;
        let target =
            self.with_reader(|store| store.get_memo_projection(request.memo_id.as_str()))?;
        let held = match target {
            Some(target) if !target.is_trashed && request.pending_promotes.is_empty() => self
                .write_behind
                .document_edits(&RelativeWorkspacePath::parse(&target.source_path)?)?,
            _ => self.write_behind.exclusive()?,
        };
        let digest = payload_digest(&request)?;
        if let Some(receipt) = self.replay(&held, &request.operation_id, &digest)? {
            return Ok(update_result(receipt));
        }
        self.recover_pending(&held)?;
        let current = self.current_memo(&request.memo_id)?;
        let path = RelativeWorkspacePath::parse(&current.summary.source_path)?;
        let loaded = LoadedDocument::load(&self.io(), path.clone(), DocumentRead::ActiveMemo)?;
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
            crate::media_plan::AttachmentBaseline::ExistingMemo(
                loaded.memo(&request.memo_id)?.attachments(),
            ),
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
        let receipt = self.commit_transaction(
            &held,
            TransactionInput {
                operation_id: request.operation_id,
                memo_id: request.memo_id,
                path,
                payload_digest: digest,
                files,
                mutations: vec![DocumentPublication {
                    mutation,
                    history: Some(history.projection),
                }],
                staging: request.pending_promotes,
            },
        )?;
        drop(held);
        Ok(update_result(receipt))
    }

    /// Removes a memo block and retains a durable trash snapshot through the same transaction.
    ///
    /// # Errors
    /// Missing activity source (`memo_source_missing`), stale identity, invalid trash data,
    /// and platform/projection errors.
    pub fn delete_memo(&self, request: DeleteMemoRequest) -> Result<DeleteMemoResult, LomoError> {
        request.validate()?;
        self.require_verified_projection()?;
        let held = self.write_behind.exclusive()?;
        let digest = payload_digest(&request)?;
        if let Some(receipt) = self.replay(&held, &request.operation_id, &digest)? {
            return Ok(delete_result(receipt));
        }
        self.recover_pending(&held)?;
        let current = self.current_memo(&request.memo_id)?;
        let path = RelativeWorkspacePath::parse(&current.summary.source_path)?;
        let loaded = LoadedDocument::load(&self.io(), path.clone(), DocumentRead::ActiveMemo)?;
        let baseline =
            lomo_workspace::SourceFingerprint::parse(&request.expected_document_fingerprint)?;
        if !loaded.identities.unchanged_since(
            &request.memo_id,
            &baseline,
            loaded.document.source().fingerprint(),
        ) {
            self.check_baseline(
                &loaded,
                &request.operation_id,
                &request.expected_document_fingerprint,
                &current.body,
            )?;
        }
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
        let trash = trash_snapshot(&projection, target.time_part(), trashed_at)?;
        let mut files = loaded.files(&changed)?;
        files.extend(state_files(
            &self.io(),
            &request.memo_id,
            &request.operation_id,
            StateChange::Trash(trashed_at),
            trashed_at,
        )?);
        let trash_path = RelativeWorkspacePath::parse(
            trash_record_relative_path(request.memo_id.as_str(), &trash.source_path)?.as_str(),
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
            expected_fingerprint: Some(current.summary.file_fingerprint),
            projection: Some(projection),
            trashed_at_ms: Some(trashed_at),
            batch_targets: Vec::new(),
        };
        let receipt = self.commit_transaction(
            &held,
            TransactionInput {
                operation_id: request.operation_id,
                memo_id: request.memo_id,
                path,
                payload_digest: digest,
                files,
                mutations: vec![DocumentPublication {
                    mutation,
                    history: None,
                }],
                staging: Vec::new(),
            },
        )?;
        drop(held);
        Ok(delete_result(receipt))
    }

    /// Writes durable pin state, then publishes its query projection.
    ///
    /// # Errors
    /// Rejects operation reuse and invalid source identities or pin timestamps.
    pub fn pin_memo(&self, request: PinMemoRequest) -> Result<PinMemoResult, LomoError> {
        request.validate()?;
        self.require_verified_projection()?;
        let held = self.write_behind.exclusive()?;
        let digest = payload_digest(&request)?;
        let (operation_id, memo_id, pin) = request.into_parts();
        if let Some(receipt) = self.replay(&held, &operation_id, &digest)? {
            return Ok(PinMemoResult {
                commit_result: receipt,
            });
        }
        self.recover_pending(&held)?;
        let current = self.current_memo(&memo_id)?;
        let path = RelativeWorkspacePath::parse(&current.summary.source_path)?;
        let loaded = LoadedDocument::load(&self.io(), path.clone(), DocumentRead::ActiveMemo)?;
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
        let commit_result = self.commit_transaction(
            &held,
            TransactionInput {
                operation_id,
                memo_id,
                path,
                payload_digest: digest,
                files,
                mutations: vec![DocumentPublication {
                    mutation,
                    history: None,
                }],
                staging: Vec::new(),
            },
        )?;
        drop(held);
        Ok(PinMemoResult { commit_result })
    }

    /// Captures unique source documents from this session's private projection for sync.
    ///
    /// The workspace root is not a projection directory. Every digest is checked against
    /// the live source before it reaches a remote planner; multiple memos share one file fact.
    ///
    /// # Errors
    /// Projection, source-read and stale-source failures are surfaced before remote publication.
    pub fn snapshot_sync_documents(&self) -> Result<crate::SessionSyncSnapshot, LomoError> {
        let _held = self.write_behind.exclusive()?;
        let query = MemoQuery {
            filters: MemoFilters {
                include_trash: true,
                ..MemoFilters::default()
            },
            search_text: None,
            sort: lomo_store::MemoSort::default(),
        };
        let walk = crate::paging::collect_summaries(self, &query, crate::paging::NEVER_CANCELLED)?;
        let mut documents = std::collections::BTreeMap::new();
        for memo in walk.items.iter() {
            if let Some(previous) =
                documents.insert(memo.source_path.clone(), memo.file_fingerprint.clone())
                && previous != memo.file_fingerprint
            {
                return Err(crate::error::corruption(
                    "inconsistent_source_document_fingerprint",
                    "sync source rows disagree",
                ));
            }
        }
        let paths = documents
            .keys()
            .map(|path| RelativeWorkspacePath::parse(path))
            .collect::<Result<Vec<_>, _>>()?;
        for chunk in paths.chunks(crate::workspace_io::MAX_WORKSPACE_READ_BATCH) {
            for (path, source) in chunk.iter().zip(self.io().require_batch(chunk)?) {
                let digest = lomo_workspace::SourceFingerprint::of_bytes(&source.bytes);
                if documents.get(path.as_str()).map(String::as_str) != Some(digest.as_str()) {
                    return Err(crate::error::conflict(
                        "sync_snapshot_fingerprint_mismatch",
                        "sync source changed since projection publication",
                    ));
                }
            }
        }
        Ok(crate::SessionSyncSnapshot {
            generation: self.config.workspace_generation.clone(),
            documents: documents
                .into_iter()
                .map(|(path, fingerprint)| {
                    Ok(crate::SyncDocumentFact {
                        path: RelativeWorkspacePath::parse(&path)?,
                        fingerprint: lomo_workspace::SourceFingerprint::parse(&fingerprint)?,
                    })
                })
                .collect::<Result<Vec<_>, LomoError>>()?,
        })
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

    /// Renders bounded memo body previews from persisted projection rows in request order.
    ///
    /// # Errors
    /// Propagates schema validation, batch-limit, memo-id validation, projection storage, or
    /// Markdown presentation budget errors.
    pub fn render_memo_previews(
        &self,
        items: &[MemoPreviewRenderRequest],
        schema_version: u32,
    ) -> Result<Vec<MemoPreviewRenderOutcome>, LomoError> {
        lomo_workspace::RenderDocumentV1::reject_unknown_schema(schema_version)?;
        if items.len() > MAX_PREVIEW_RENDER_BATCH_SIZE {
            return Err(crate::error::resource_limit(
                "preview_render_batch_too_large",
                format!("preview render batch exceeds {MAX_PREVIEW_RENDER_BATCH_SIZE} item limit"),
            ));
        }
        if items.is_empty() {
            return Ok(Vec::new());
        }
        let mut unique_ids = Vec::with_capacity(items.len());
        for item in items {
            if item.memo_id.trim().is_empty() {
                return Err(validation("invalid_memo_id", "memo_id must not be blank"));
            }
            if !unique_ids.iter().any(|seen| seen == &item.memo_id) {
                unique_ids.push(item.memo_id.clone());
            }
        }
        let sources = self.with_reader(|store| store.get_memo_preview_sources(&unique_ids))?;
        let mut outcomes = Vec::with_capacity(items.len());
        for item in items {
            let Some(source) = sources.get(&item.memo_id) else {
                outcomes.push(MemoPreviewRenderOutcome::NotFound {
                    memo_id: item.memo_id.clone(),
                });
                continue;
            };
            if source.content_revision != item.expected_content_revision {
                outcomes.push(MemoPreviewRenderOutcome::RevisionMismatch {
                    memo_id: item.memo_id.clone(),
                    actual_content_revision: source.content_revision,
                });
                continue;
            }
            let bytes = lomo_workspace::SourceBytes::try_from_str(&source.body_preview)?;
            let document = lomo_workspace::render_markdown(&bytes)?;
            let source_byte_length = u64::try_from(source.body_preview.len()).map_err(|error| {
                crate::error::resource_limit("preview_render_size_overflow", error.to_string())
            })?;
            outcomes.push(MemoPreviewRenderOutcome::Rendered {
                memo_id: source.memo_id.clone(),
                content_revision: source.content_revision,
                source_byte_length,
                document,
            });
        }
        Ok(outcomes)
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
        let held = self.write_behind.exclusive()?;
        self.recover_pending(&held)?;
        let result = self.rebuild_locked(false)?;
        self.evict_stale_baselines()?;
        self.projection_verified.store(true, Ordering::Release);
        Ok(result)
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
        let held = self.write_behind.exclusive()?;
        self.recover_pending(&held)?;
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
        // Projection facts are only derivable under a supported layout. Presentation of a
        // retained projection consumes no workspace facts and therefore skips this check.
        crate::record_plan::validate_workspace_layout(&self.io())?;
        let evidence = crate::rebuild::list_workspace_listing(&self.config, &self.executor)?;
        let skipped_sync = evidence.skipped_sync_subtrees();
        let listing = evidence.admitted_listing()?;
        if !force_scan {
            if let Some(digest) = evidence.listing_digest() {
                let matched = self.with_store(|store| {
                    Ok(store.workspace_listing_digest()?.as_deref() == Some(digest.as_str()))
                })?;
                if matched {
                    // Digest-matched reconciles stay write-free: telemetry counters
                    // persist only on passes that committed a projection write.
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
                self.with_store_mut(|store| store.record_reconcile_telemetry(false, skipped_sync))?;
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
                crate::rebuild_incremental::align_listing_snapshot(
                    store,
                    inventory.listing_rows(),
                )?;
                store.record_reconcile_telemetry(false, skipped_sync)
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
        let witness = reopened.integrity_witness();
        let mut guard = self
            .store
            .lock()
            .map_err(|error| storage("store_lock_poisoned", error.to_string()))?;
        *guard = Some(reopened);
        drop(guard);
        // `clear_idle` above retired the previous generation's verdict; re-seed
        // the token this fresh `open_projection` just verified.
        if let Some(witness) = witness {
            self.readers.mark_verified(witness)?;
        }
        let result = result?;
        self.with_store_mut(|store| store.record_reconcile_telemetry(true, skipped_sync))?;
        // The build commit already carried `workspace_listing_digest` derived from the exact
        // `file_listing` rows it wrote — no post-promote write exists to lose to a crash.
        self.record_mount(result)
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
            self.draft_store.save_conflict_evidence(
                &ConflictEvidence {
                    operation_id: operation_id.clone(),
                    path: document.path.clone(),
                    baseline_fingerprint: expected.to_owned(),
                    disk_fingerprint: document.fingerprint().to_owned(),
                    draft_content: draft.to_owned(),
                    recorded_at_ms: epoch_millis()?,
                },
                document
                    .original
                    .as_ref()
                    .map(|snapshot| snapshot.bytes.as_slice()),
            )?;
            return Err(error);
        }
        Ok(())
    }

    /// Retires CAS baseline blobs no live fingerprint can cite: the keep-set is retained
    /// conflict evidence ∪ committed `memo` fingerprints (trashed identities carry theirs on
    /// the same `memo` row). Runs once per open after the projection mounts so the criterion
    /// is never a stale snapshot.
    fn evict_stale_baselines(&self) -> Result<(), LomoError> {
        let mut keep = self.draft_store.referenced_fingerprints()?;
        keep.extend(self.with_store(Store::live_document_fingerprints)?);
        crate::private_io::evict_unreferenced_baselines(
            &lomo_workspace::SessionStateLayout::for_state_dir(&self.config.state_dir).baselines,
            &keep,
        )?;
        Ok(())
    }

    pub(crate) fn io(&self) -> WorkspaceIo<'_> {
        WorkspaceIo::committed(&self.config, &self.executor, &self.write_behind)
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

/// Maximum preview render items processed per batched session call.
pub const MAX_PREVIEW_RENDER_BATCH_SIZE: usize = 8;

/// Request to render one memo's stored `body_preview` only if `expected_content_revision` still matches.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MemoPreviewRenderRequest {
    pub memo_id: String,
    pub expected_content_revision: u64,
}

/// Outcome of rendering one requested preview item, preserving caller request order.
#[derive(Clone, Debug, PartialEq)]
pub enum MemoPreviewRenderOutcome {
    Rendered {
        memo_id: String,
        content_revision: u64,
        source_byte_length: u64,
        document: lomo_workspace::RenderDocumentV1,
    },
    RevisionMismatch {
        memo_id: String,
        actual_content_revision: u64,
    },
    NotFound {
        memo_id: String,
    },
}

/// A create the engine admitted: identity minted and pending row published, no workspace I/O
/// performed yet. Exactly one of [`Self::commit`] or drop settles it.
pub struct AcceptedCreate<'session> {
    session: &'session WorkspaceSession,
    state: AcceptedState<'session>,
}

enum AcceptedState<'session> {
    Replayed(CreateMemoResult),
    Pending(Box<PendingCreate<'session>>),
    Settled,
}

struct PendingCreate<'session> {
    _lock: crate::write_behind::Exclusive<'session>,
    publication: lomo_store::SafProjectionCommitResult,
    plan: CreatePlan,
}

/// Inputs frozen at acceptance; the commit derives every durable byte from them.
struct CreatePlan {
    request: CreateMemoRequest,
    digest: String,
    path: RelativeWorkspacePath,
    time_part: String,
    chronology: i64,
    now: i64,
    memo_id: MemoId,
}

/// A create whose durable commit failed after acceptance.
///
/// `withdrawal` is the outcome of removing its pending row: the removal publication, `None` when
/// the row was already gone, or the projection failure that left it until the next open sweeps it.
#[derive(Debug)]
pub struct WithdrawnCreate {
    pub error: LomoError,
    pub withdrawal: Result<Option<lomo_store::SafMemoPublication>, LomoError>,
}

impl WithdrawnCreate {
    /// The failure a caller must act on: a projection fault that left the pending row visible
    /// outranks the commit failure it was cleaning up after.
    #[must_use]
    pub fn into_error(self) -> LomoError {
        match self.withdrawal {
            Ok(_) => self.error,
            Err(withdrawal_error) => withdrawal_error,
        }
    }
}

impl AcceptedCreate<'_> {
    /// The projection publication that made the accepted memo visible: the pending row's clock,
    /// or the committed receipt for a replayed operation.
    #[must_use]
    pub fn publication(&self) -> &lomo_store::SafProjectionCommitResult {
        match &self.state {
            AcceptedState::Replayed(result) => &result.commit_result,
            AcceptedState::Pending(pending) => &pending.publication,
            AcceptedState::Settled => unreachable!("a settled acceptance is consumed"),
        }
    }

    /// Runs the durable transaction and upgrades the pending row in place.
    ///
    /// # Errors
    /// The commit failure together with the publication that withdrew the pending row.
    pub fn commit(mut self) -> Result<CreateMemoResult, Box<WithdrawnCreate>> {
        match std::mem::replace(&mut self.state, AcceptedState::Settled) {
            AcceptedState::Replayed(result) => Ok(result),
            AcceptedState::Pending(pending) => {
                let PendingCreate {
                    _lock: lock,
                    publication,
                    plan,
                } = *pending;
                let outcome = self.session.commit_create(&lock, plan).map_err(|error| {
                    Box::new(WithdrawnCreate {
                        error,
                        withdrawal: self.session.withdraw_pending_create(&publication),
                    })
                });
                drop(lock);
                outcome
            }
            AcceptedState::Settled => unreachable!("a settled acceptance is consumed"),
        }
    }
}

impl Drop for AcceptedCreate<'_> {
    fn drop(&mut self) {
        if let AcceptedState::Pending(pending) =
            std::mem::replace(&mut self.state, AcceptedState::Settled)
        {
            // An abandoned acceptance has no caller to report to; a row this withdrawal cannot
            // remove is swept by the next projection open, the same path a crash takes.
            drop(self.session.withdraw_pending_create(&pending.publication));
        }
    }
}

impl WorkspaceSession {
    fn withdraw_pending_create(
        &self,
        publication: &lomo_store::SafProjectionCommitResult,
    ) -> Result<Option<lomo_store::SafMemoPublication>, LomoError> {
        self.with_store_mut(|store| {
            store.rollback_saf_memo_create(&publication.operation_id, &publication.memo_id)
        })
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
