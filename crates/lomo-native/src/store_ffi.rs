//! Stage-3 dark-build store FFI conversion surface (P3-09).
//!
//! Conversion-only mapping between `BoltFFI` DTOs and `lomo-store`. Business rules stay in
//! `lomo-store`.

use std::collections::BTreeMap;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{
    Condvar, Mutex, RwLock,
    atomic::{AtomicU64, Ordering},
};
use std::time::{Duration, Instant};

use boltffi::data;
use lomo_core::{ErrorCategory, ExchangeToken, LomoError, OperationId, PageSize, RetryDisposition};
use lomo_store::{
    self as store, MemoCommand, MemoCommandKind, MemoFilters, MemoQuery, MemoQueryBoundary,
    PageCursor, ReminderCommand, ReminderQuery, ReminderSessionInput, SnoozeStore, Store,
    StoreReader, TimeZoneContext, ZoneTransition,
};

use crate::EngineError;
use crate::media_ffi::MediaPromotePlanDto;

static NEXT_SAF_REBUILD_ID: AtomicU64 = AtomicU64::new(1);

/// Materializes one already bounded LAN attachment into the app-private staging path.
///
/// The media owner deliberately accepts paths, not full byte buffers. This conversion edge is the
/// only place where the verified LAN payload crosses into the filesystem-backed media pipeline.
fn stage_received_attachment(
    workspace_root: &Path,
    attachment: &lomo_lan::AuthorizedReceivedAttachment,
) -> Result<lomo_media::MediaStaged, LomoError> {
    let digest = lomo_media::ContentDigest::parse(attachment.digest())?;
    let expected_size = u64::try_from(attachment.bytes().len()).map_err(|_error| {
        lomo_media::media_validation(
            "media_stage_received_size_invalid",
            "received media size does not fit the durable size width",
        )
    })?;
    let incoming_dir = workspace_root
        .join(lomo_media::STAGE_DIR_NAME)
        .join("incoming");
    fs::create_dir_all(&incoming_dir).map_err(|error| {
        lomo_media::media_storage(
            "media_stage_incoming_dir_create_failed",
            &format!("failed to create received media scratch: {error}"),
        )
    })?;
    let incoming_path = incoming_dir.join(digest.as_str());
    if incoming_path.exists() {
        let (existing_digest, existing_size) =
            lomo_media::ContentDigest::stream_from_path(&incoming_path)?;
        if existing_digest != digest || existing_size != expected_size {
            return Err(lomo_media::media_corruption(
                "media_stage_incoming_collision",
                "received media scratch path contains different bytes",
            ));
        }
    } else {
        let mut file = fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&incoming_path)
            .map_err(|error| {
                lomo_media::media_storage(
                    "media_stage_incoming_create_failed",
                    &format!("failed to create received media scratch: {error}"),
                )
            })?;
        file.write_all(attachment.bytes()).map_err(|error| {
            lomo_media::media_storage(
                "media_stage_incoming_write_failed",
                &format!("failed to write received media scratch: {error}"),
            )
        })?;
        file.sync_all().map_err(|error| {
            lomo_media::media_storage(
                "media_stage_incoming_sync_failed",
                &format!("failed to sync received media scratch: {error}"),
            )
        })?;
    }
    let staged = lomo_media::stage_media(
        workspace_root,
        lomo_media::MediaSource::StagedTemp {
            path: incoming_path,
        },
        attachment.name(),
    )?;
    if staged.digest.as_str() != attachment.digest() || staged.size != expected_size {
        return Err(lomo_media::media_validation(
            "lan_attachment_media_digest_mismatch",
            "media staging digest differs from the authorized LAN digest",
        ));
    }
    Ok(staged)
}

/// Opaque page cursor for Kotlin (pipe-encoded store cursor; not SQL).
#[data]
#[derive(Clone, Debug, Default)]
pub struct StorePageCursor {
    pub encoded: String,
}

#[data]
#[derive(Clone, Debug, Default)]
pub struct StoreMemoFilters {
    pub tag: Option<String>,
    pub tag_subtree: bool,
    pub date_from_inclusive_ms: Option<i64>,
    pub date_until_exclusive_ms: Option<i64>,
    pub has_todo: Option<bool>,
    pub has_attachment: Option<bool>,
    pub has_url: Option<bool>,
    pub pinned_only: bool,
    pub include_trash: bool,
    pub trash_only: bool,
}

#[data]
#[derive(Clone, Debug, Default)]
pub struct StoreMemoQueryBoundary {
    pub is_pinned: bool,
    pub primary_sort_ms: i64,
    pub created_at_ms: i64,
    pub memo_id: String,
}

#[data]
#[derive(Clone, Debug, Default)]
pub struct StoreMemoQuery {
    pub search_text: Option<String>,
    pub filters: StoreMemoFilters,
    pub sort: StoreMemoSort,
    pub boundary: Option<StoreMemoQueryBoundary>,
}

#[data]
#[derive(Clone, Copy, Debug, Default)]
pub enum StoreMemoSortField {
    #[default]
    CreatedAt,
    UpdatedAt,
}

#[data]
#[derive(Clone, Copy, Debug, Default)]
pub enum StoreSortDirection {
    Ascending,
    #[default]
    Descending,
}

#[data]
#[derive(Clone, Copy, Debug, Default)]
pub struct StoreMemoSort {
    pub field: StoreMemoSortField,
    pub direction: StoreSortDirection,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreMemoSummary {
    pub memo_id: String,
    pub source_path: String,
    pub file_fingerprint: String,
    pub updated_at_ms: i64,
    pub created_at_ms: i64,
    pub has_todo: bool,
    pub has_url: bool,
    pub has_attachment: bool,
    pub is_pinned: bool,
    pub is_trashed: bool,
    pub body_preview: String,
    pub content_revision: u64,
    pub rank: Option<f64>,
    pub tags: Vec<String>,
    pub image_urls: Vec<String>,
    pub reminders: Vec<crate::WorkspaceReminderReference>,
    /// Row was published by a begun create whose durable commit has not landed yet.
    pub is_pending: bool,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreMemoPage {
    pub items: Vec<StoreMemoSummary>,
    pub next_cursor: Option<StorePageCursor>,
    pub high_water_revision: u64,
    pub query_fingerprint: String,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreSidebarDateCount {
    pub date: String,
    pub count: i64,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreSidebarTagCount {
    pub name: String,
    pub count: i64,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreSidebarProjection {
    pub schema_version: u32,
    pub memo_count: i64,
    pub date_counts: Vec<StoreSidebarDateCount>,
    pub tag_counts: Vec<StoreSidebarTagCount>,
}

/// One compact materialized statistics row (word/character counts live in the projection).
#[data]
#[derive(Clone, Copy, Debug)]
pub struct StoreMemoStatisticsRow {
    pub created_at_ms: i64,
    pub word_count: i64,
    pub char_count: i64,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreMemoSnapshot {
    pub summary: StoreMemoSummary,
    pub body: String,
}

/// Attachment path still referenced by a durable history revision (D6 orphan keep-set).
#[data]
#[derive(Clone, Debug)]
pub struct StoreHistoryAttachmentRef {
    pub memo_id: String,
    pub revision: u64,
    pub relative_path: String,
    pub owner_key: String,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreMemoHistoryRevision {
    pub revision: u64,
    pub created_at_ms: i64,
    pub content: String,
    pub file_fingerprint: String,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreMemoHistoryPage {
    pub items: Vec<StoreMemoHistoryRevision>,
    pub next_cursor: Option<String>,
}

#[data]
#[derive(Clone, Copy, Debug)]
pub enum StoreMemoCommandKind {
    Create,
    Update,
    Delete,
    PermanentDelete,
    Restore,
    Pin,
    Unpin,
    HistoryRestore,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreMemoCommand {
    pub operation_id: String,
    pub kind: StoreMemoCommandKind,
    pub memo_id: String,
    pub expected_revision: u64,
    pub expected_fingerprint: Option<String>,
    pub content: Option<String>,
    pub tags: Vec<String>,
    pub pin: Option<bool>,
    /// Path-only promote plans under the same operation-id (P4-09 dark wire).
    pub pending_promotes: Vec<MediaPromotePlanDto>,
    /// Source chronology required by SAF create; non-create commands may omit it.
    pub chronology_epoch_ms: Option<i64>,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreMemoCommit {
    pub operation_id: String,
    pub memo_id: String,
    pub core_revision: u64,
    pub event_sequence: u64,
    pub content_revision: u64,
    pub file_fingerprint: String,
    pub scopes: Vec<String>,
    pub idempotent_replay: bool,
}

/// One CAS target for an atomic permanent-delete batch.
///
/// `source_path` and the expected fingerprint come from the Rust-owned projection. SAF uses the
/// same facts to fence each platform document action before the final projection commit.
#[data]
#[derive(Clone, Debug)]
pub struct StoreMemoDeleteTarget {
    pub memo_id: String,
    pub source_path: String,
    pub expected_revision: u64,
    pub expected_fingerprint: String,
    /// SAF fills this after its verified platform action.  Direct batches leave it `None`.
    pub result_fingerprint: Option<String>,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreMemoBatchDelete {
    pub operation_id: String,
    pub targets: Vec<StoreMemoDeleteTarget>,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreMemoDeletedMemo {
    pub memo_id: String,
    pub reminder_ids: Vec<String>,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreMemoBatchCommit {
    pub operation_id: String,
    pub deleted: Vec<StoreMemoDeletedMemo>,
    pub core_revision: u64,
    pub event_sequence: u64,
    pub scopes: Vec<String>,
    pub idempotent_replay: bool,
}

/// Begin facts for a SAF memo create published before durable platform I/O.
#[data]
#[derive(Clone, Debug)]
pub struct StoreSafMemoCreateBegin {
    pub operation_id: String,
    pub date_key: String,
    pub time_part: String,
    pub chronology_epoch_ms: i64,
    pub source_path: String,
    pub body: String,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreSafMemoCreateBeginResult {
    pub memo_id: String,
    pub core_revision: u64,
    pub event_sequence: u64,
    pub scopes: Vec<String>,
    pub idempotent_replay: bool,
}

/// Publication for a begun create's rollback. `removed=false` means nothing was pending
/// (already swept or already rolled back) and no publication happened.
#[data]
#[derive(Clone, Debug)]
pub struct StoreSafMemoRollbackResult {
    pub removed: bool,
    pub core_revision: u64,
    pub event_sequence: u64,
    pub scopes: Vec<String>,
}

#[data]
#[derive(Clone, Copy, Debug)]
pub struct StoreZoneTransition {
    pub transition_utc_ms: i64,
    pub offset_before_secs: i32,
    pub offset_after_secs: i32,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreTimeZoneContext {
    pub zone_id: String,
    pub base_offset_secs: i32,
    pub transitions: Vec<StoreZoneTransition>,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreReminderSession {
    pub opaque_id: String,
    pub memo_identity: String,
    pub memo_revision: String,
    pub token: String,
    pub due_at_local: String,
    pub repeat_count: u32,
    pub fired_count: u32,
    pub done: bool,
    pub interval_minutes: u32,
    pub recurrence_code: String,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreReminderQuery {
    pub now_utc_ms: i64,
    pub zone: StoreTimeZoneContext,
    pub sessions: Vec<StoreReminderSession>,
    pub rolling_window: u32,
    pub workspace_generation: u64,
}

#[data]
#[derive(Clone, Debug)]
pub struct StorePlannedAlarm {
    pub opaque_id: String,
    pub memo_identity: String,
    pub trigger_at_utc_ms: i64,
    pub is_catch_up: bool,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreReminderPlan {
    pub alarms: Vec<StorePlannedAlarm>,
    pub workspace_generation: u64,
}

#[data]
#[derive(Clone, Copy, Debug)]
pub enum StoreReminderCommandKind {
    MarkDone,
    RecordFired,
    Snooze,
    ClearSnooze,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreReminderCommand {
    pub kind: StoreReminderCommandKind,
    pub session: Option<StoreReminderSession>,
    pub expected_revision: Option<String>,
    pub opaque_id: Option<String>,
    pub memo_identity: Option<String>,
    pub memo_revision: Option<String>,
    pub workspace_generation: Option<u64>,
    pub snooze_until_utc_ms: Option<i64>,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreReminderCommandResult {
    pub replacement_token: Option<String>,
    pub scopes: Vec<String>,
    pub snooze_only: bool,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreRebuildResult {
    pub memos_indexed: u64,
    pub file_count: u64,
    pub attachment_count: u64,
    pub workspace_digest: String,
    pub store_digest: String,
    pub corrupt_lomo_isolated: u64,
    pub high_water_revision: u64,
}

/// One memo already parsed by the Rust workspace scan for an Android SAF tree.
#[data]
#[derive(Clone, Debug)]
pub struct StoreSafMemoProjection {
    pub memo_id: String,
    pub source_path: String,
    pub file_fingerprint: String,
    pub chronology_epoch_ms: i64,
    pub body: String,
    pub tags: Vec<String>,
    pub attachment_paths: Vec<String>,
    pub has_todo: bool,
    pub has_url: bool,
    pub reminders: Vec<crate::WorkspaceReminderReference>,
    pub trashed_at_ms: Option<i64>,
}

/// SAF scan facts for the streaming rebuild. Body bytes stay in Rust-owned exchange storage.
#[data]
#[derive(Clone, Debug)]
pub struct StoreSafMemoProjectionReference {
    pub memo_id: String,
    pub source_path: String,
    pub file_fingerprint: String,
    pub chronology_epoch_ms: i64,
    pub content: crate::WorkspaceMemoContentReference,
    pub tags: Vec<String>,
    pub attachment_paths: Vec<String>,
    pub has_todo: bool,
    pub has_url: bool,
    pub reminders: Vec<crate::WorkspaceReminderReference>,
}

/// Durable SAF trash-record facts for the streaming rebuild. Body bytes stay in Rust exchange
/// storage and the workspace scan owns record decoding and checksum verification.
#[data]
#[derive(Clone, Debug)]
pub struct StoreSafTrashProjectionReference {
    pub memo_id: String,
    pub source_path: String,
    pub file_fingerprint: String,
    pub chronology_epoch_ms: i64,
    pub trashed_at_ms: i64,
    pub content: crate::WorkspaceMemoContentReference,
    pub tags: Vec<String>,
    pub attachment_paths: Vec<String>,
    pub has_todo: bool,
    pub has_url: bool,
    pub reminders: Vec<crate::WorkspaceReminderReference>,
}

/// Durable SAF history facts for the streaming rebuild.
#[data]
#[derive(Clone, Debug)]
pub struct StoreSafHistoryProjectionReference {
    pub memo_id: String,
    pub revision: u64,
    pub created_at_ms: i64,
    pub file_fingerprint: String,
    pub content: crate::WorkspaceMemoContentReference,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum StoreWorkspaceMode {
    Direct,
    Saf,
}

const STORE_READER_POOL_SIZE: usize = 4;
const STORE_READER_WAIT_TIMEOUT: Duration = Duration::from_secs(5);

/// Bounded pool of read-only projection connections.
///
/// The pool mutex protects only checkout/return bookkeeping. It is never held while SQLite is
/// queried, so independent readers can use WAL concurrently while the single writer remains
/// serialized by `StoreHandle::store`.
struct StoreReaderPool {
    workspace_root: PathBuf,
    state: Mutex<StoreReaderPoolState>,
    available: Condvar,
}

struct StoreReaderPoolState {
    idle: Vec<StoreReader>,
    total: usize,
}

struct StoreReaderLease<'pool> {
    pool: &'pool StoreReaderPool,
    reader: Option<StoreReader>,
}

impl StoreReaderPool {
    const fn new(workspace_root: PathBuf) -> Self {
        Self {
            workspace_root,
            state: Mutex::new(StoreReaderPoolState {
                idle: Vec::new(),
                total: 0,
            }),
            available: Condvar::new(),
        }
    }

    fn checkout(&self) -> Result<StoreReaderLease<'_>, EngineError> {
        let deadline = Instant::now() + STORE_READER_WAIT_TIMEOUT;
        let mut state = self.state.lock().map_err(|_error| {
            EngineError::from(boundary_err(
                "store_reader_pool_poisoned",
                "store reader pool mutex poisoned",
            ))
        })?;
        loop {
            if let Some(reader) = state.idle.pop() {
                return Ok(StoreReaderLease {
                    pool: self,
                    reader: Some(reader),
                });
            }
            if state.total < STORE_READER_POOL_SIZE {
                state.total += 1;
                drop(state);
                let reader = match StoreReader::open(&self.workspace_root) {
                    Ok(reader) => reader,
                    Err(error) => {
                        let mut rollback = self.state.lock().map_err(|_poisoned| {
                            EngineError::from(boundary_err(
                                "store_reader_pool_poisoned",
                                "store reader pool mutex poisoned while opening a reader",
                            ))
                        })?;
                        if rollback.total == 0 {
                            return Err(EngineError::from(boundary_err(
                                "store_reader_pool_inconsistent",
                                "reader open rollback found no outstanding reader slot",
                            )));
                        }
                        rollback.total -= 1;
                        self.available.notify_one();
                        drop(rollback);
                        return Err(EngineError::from(error));
                    }
                };
                return Ok(StoreReaderLease {
                    pool: self,
                    reader: Some(reader),
                });
            }

            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return Err(EngineError::from(boundary_err(
                    "store_reader_pool_exhausted",
                    "all bounded store readers remained busy past the checkout deadline",
                )));
            }
            let (next_state, timeout) =
                self.available
                    .wait_timeout(state, remaining)
                    .map_err(|_poisoned| {
                        EngineError::from(boundary_err(
                            "store_reader_pool_poisoned",
                            "store reader pool wait mutex poisoned",
                        ))
                    })?;
            state = next_state;
            if timeout.timed_out() && state.idle.is_empty() {
                return Err(EngineError::from(boundary_err(
                    "store_reader_pool_exhausted",
                    "all bounded store readers remained busy past the checkout deadline",
                )));
            }
        }
    }

    fn clear_idle(&self) -> Result<(), EngineError> {
        let mut state = self.state.lock().map_err(|_error| {
            EngineError::from(boundary_err(
                "store_reader_pool_poisoned",
                "store reader pool mutex poisoned while clearing readers",
            ))
        })?;
        let removed = state.idle.len();
        if removed > state.total {
            return Err(EngineError::from(boundary_err(
                "store_reader_pool_inconsistent",
                "idle reader count exceeds total reader count",
            )));
        }
        state.idle.clear();
        state.total -= removed;
        self.available.notify_all();
        drop(state);
        Ok(())
    }
}

impl StoreReaderLease<'_> {
    fn reader(&self) -> Result<&StoreReader, EngineError> {
        self.reader.as_ref().ok_or_else(|| {
            EngineError::from(boundary_err(
                "store_reader_lease_empty",
                "store reader lease was consumed before query execution",
            ))
        })
    }
}

impl Drop for StoreReaderLease<'_> {
    fn drop(&mut self) {
        let Some(reader) = self.reader.take() else {
            return;
        };
        if let Ok(mut state) = self.pool.state.lock() {
            state.idle.push(reader);
            self.pool.available.notify_one();
        }
    }
}

/// Process-local store handle for one engine.
pub struct StoreHandle {
    workspace_root: PathBuf,
    mode: StoreWorkspaceMode,
    store: Mutex<Option<Store>>,
    readers: StoreReaderPool,
    snooze: Mutex<SnoozeStore>,
    projection_gate: RwLock<()>,
    saf_rebuild: Mutex<Option<SafProjectionRebuildState>>,
}

struct SafProjectionRebuildState {
    id: String,
    rebuild: store::SafProjectionRebuild,
}

impl std::fmt::Debug for StoreHandle {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let store_open = match self.store.lock() {
            Ok(guard) => guard.is_some(),
            Err(_poison) => false,
        };
        f.debug_struct("StoreHandle")
            .field("workspace_root", &self.workspace_root)
            .field("mode", &self.mode)
            .field("store_open", &store_open)
            .finish_non_exhaustive()
    }
}

impl StoreHandle {
    /// Opens app-private snooze under `app_private_root` (never workspace `.lomo`).
    ///
    /// # Errors
    ///
    /// Snooze open failures.
    pub fn new(workspace_root: PathBuf, app_private_root: &Path) -> Result<Self, EngineError> {
        let snooze_dir = app_private_root.join("reminder_snooze");
        let snooze = SnoozeStore::open_app_private(&snooze_dir).map_err(EngineError::from)?;
        Ok(Self {
            readers: StoreReaderPool::new(workspace_root.clone()),
            workspace_root,
            mode: StoreWorkspaceMode::Direct,
            store: Mutex::new(None),
            snooze: Mutex::new(snooze),
            projection_gate: RwLock::new(()),
            saf_rebuild: Mutex::new(None),
        })
    }

    /// Opens a generation-stable app-private projection for one SAF workspace identity.
    ///
    /// # Errors
    ///
    /// Snooze open failures.
    pub fn new_saf(projection_root: PathBuf, app_private_root: &Path) -> Result<Self, EngineError> {
        let snooze_dir = app_private_root.join("reminder_snooze");
        let snooze = SnoozeStore::open_app_private(&snooze_dir).map_err(EngineError::from)?;
        Ok(Self {
            readers: StoreReaderPool::new(projection_root.clone()),
            workspace_root: projection_root,
            mode: StoreWorkspaceMode::Saf,
            store: Mutex::new(None),
            snooze: Mutex::new(snooze),
            projection_gate: RwLock::new(()),
            saf_rebuild: Mutex::new(None),
        })
    }

    fn open_store(&self) -> Result<Store, EngineError> {
        match self.mode {
            StoreWorkspaceMode::Direct => Store::open(&self.workspace_root),
            StoreWorkspaceMode::Saf => Store::open_projection(&self.workspace_root),
        }
        .map_err(EngineError::from)
    }

    fn lock_store(&self) -> Result<std::sync::MutexGuard<'_, Option<Store>>, EngineError> {
        self.store.lock().map_err(|_e| {
            EngineError::from(boundary_err("store_mutex_poisoned", "store mutex poisoned"))
        })
    }

    fn ensure_store_open(&self) -> Result<(), EngineError> {
        let mut guard = self.lock_store()?;
        if guard.is_none() {
            *guard = Some(self.open_store()?);
        }
        drop(guard);
        Ok(())
    }

    fn with_reader<R>(
        &self,
        f: impl FnOnce(&StoreReader) -> Result<R, LomoError>,
    ) -> Result<R, EngineError> {
        let _projection = self.projection_gate.read().map_err(|_error| {
            EngineError::from(boundary_err(
                "store_projection_lock_poisoned",
                "store projection read lock poisoned",
            ))
        })?;
        self.ensure_store_open()?;
        let lease = self.readers.checkout()?;
        f(lease.reader()?).map_err(EngineError::from)
    }

    fn with_store_mut<R>(
        &self,
        f: impl FnOnce(&mut Store) -> Result<R, LomoError>,
    ) -> Result<R, EngineError> {
        if self.mode == StoreWorkspaceMode::Saf {
            return Err(EngineError::from(boundary_err(
                "saf_store_mutation_requires_platform_job",
                "SAF memo mutations must execute through the workspace platform-action job",
            )));
        }
        let _projection = self.projection_gate.read().map_err(|_error| {
            EngineError::from(boundary_err(
                "store_projection_lock_poisoned",
                "store projection read lock poisoned",
            ))
        })?;
        let mut guard = self.lock_store()?;
        if guard.is_none() {
            *guard = Some(Store::open(&self.workspace_root).map_err(EngineError::from)?);
        }
        let store = guard.as_mut().ok_or_else(|| {
            EngineError::from(boundary_err(
                "store_missing",
                "store handle missing after open",
            ))
        })?;
        let result = f(store).map_err(EngineError::from)?;
        drop(guard);
        Ok(result)
    }

    fn with_projection_store_mut<R>(
        &self,
        f: impl FnOnce(&mut Store) -> Result<R, LomoError>,
    ) -> Result<R, EngineError> {
        if self.mode != StoreWorkspaceMode::Saf {
            return Err(EngineError::from(boundary_err(
                "saf_projection_commit_requires_saf",
                "projection-only commit is available only for SAF stores",
            )));
        }
        let _projection = self.projection_gate.read().map_err(|_error| {
            EngineError::from(boundary_err(
                "store_projection_lock_poisoned",
                "store projection read lock poisoned",
            ))
        })?;
        let mut guard = self.lock_store()?;
        if guard.is_none() {
            *guard = Some(self.open_store()?);
        }
        let store = guard.as_mut().ok_or_else(|| {
            EngineError::from(boundary_err(
                "store_missing",
                "store handle missing after open",
            ))
        })?;
        let result = f(store).map_err(EngineError::from)?;
        drop(guard);
        Ok(result)
    }

    /// Runs a projection-only mutation against either a Direct or SAF projection store. The
    /// workspace document command has already committed user bytes before this boundary, so no
    /// storage-mode-specific platform action is needed here.
    fn with_any_projection_store_mut<R>(
        &self,
        f: impl FnOnce(&mut Store) -> Result<R, LomoError>,
    ) -> Result<R, EngineError> {
        let _projection = self.projection_gate.read().map_err(|_error| {
            EngineError::from(boundary_err(
                "store_projection_lock_poisoned",
                "store projection read lock poisoned",
            ))
        })?;
        let mut guard = self.lock_store()?;
        if guard.is_none() {
            *guard = Some(self.open_store()?);
        }
        let store = guard.as_mut().ok_or_else(|| {
            EngineError::from(boundary_err(
                "store_missing",
                "store handle missing after open",
            ))
        })?;
        let result = f(store).map_err(EngineError::from)?;
        drop(guard);
        Ok(result)
    }

    /// Stages received LAN attachments and remaps Markdown references. Document writes belong to
    /// [`WorkspaceSession`].
    ///
    /// # Errors
    ///
    /// Invalid operation identity, digest mismatch, or staging failures.
    pub(crate) fn prepare_received_lan_create(
        &self,
        command: &lomo_lan::AuthorizedReceivedCreate,
    ) -> Result<(String, Vec<lomo_media::PromotePlan>), EngineError> {
        let operation_id =
            OperationId::parse(command.item_id().as_str()).map_err(EngineError::from)?;
        self.with_store_mut(|store| {
            command.approved_generation().assert_matches(
                lomo_workspace::load_workspace_generation(store.workspace_root())?.as_str(),
            )?;
            let mut promotes_by_digest: BTreeMap<
                String,
                (lomo_media::PromotePlan, lomo_media::MediaRelativePath),
            > = BTreeMap::new();
            let mut remaps = BTreeMap::new();
            for attachment in command.attachments() {
                let final_relative_path = if let Some((_plan, final_relative_path)) =
                    promotes_by_digest.get(attachment.digest())
                {
                    final_relative_path.clone()
                } else {
                    let staged = stage_received_attachment(store.workspace_root(), attachment)?;
                    if staged.digest.as_str() != attachment.digest() {
                        return Err(lomo_media::media_validation(
                            "lan_attachment_media_digest_mismatch",
                            "media staging digest differs from the authorized LAN digest",
                        ));
                    }
                    let final_relative_path = lomo_media::resolve_received_final_relative_path(
                        store.workspace_root(),
                        &staged,
                    )?;
                    let plan = lomo_media::PromotePlan {
                        operation_id: operation_id.as_str().to_owned(),
                        staged,
                        final_relative_path: final_relative_path.clone(),
                    };
                    promotes_by_digest.insert(
                        attachment.digest().to_owned(),
                        (plan, final_relative_path.clone()),
                    );
                    final_relative_path
                };
                let stored = final_relative_path.as_str().to_owned();
                if let Some(previous) = remaps.insert(attachment.source_reference().to_owned(), stored)
                    && previous != final_relative_path.as_str()
                {
                    return Err(lomo_media::media_validation(
                        "lan_attachment_source_reference_conflict",
                        "one Markdown attachment reference cannot resolve to multiple received files",
                    ));
                }
            }
            let content = lomo_workspace::remap_attachment_destinations(command.content(), &remaps)?;
            let pending_promotes = promotes_by_digest
                .into_values()
                .map(|(plan, _final_relative_path)| plan)
                .collect();
            Ok((content, pending_promotes))
        })
    }

    /// See plan `query_memos`.
    ///
    /// # Errors
    ///
    /// Store query / cursor validation errors.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned query wire types"
    )]
    pub fn query_memos(
        &self,
        query: StoreMemoQuery,
        cursor: Option<StorePageCursor>,
        page_size: u32,
    ) -> Result<StoreMemoPage, EngineError> {
        let page_size = PageSize::new(page_size).map_err(EngineError::from)?;
        let decoded_cursor = cursor
            .as_ref()
            .map(|c| decode_cursor(&c.encoded))
            .transpose()?;
        let (mq, boundary) = memo_query_from_ffi(query);
        let page = self.with_reader(|reader| {
            reader.query_memos_with_boundary(
                &mq,
                boundary.as_ref(),
                decoded_cursor.as_ref(),
                page_size,
            )
        })?;
        Ok(StoreMemoPage {
            items: page.items.into_iter().map(summary_to_ffi).collect(),
            next_cursor: page.next_cursor.map(|c| StorePageCursor {
                encoded: encode_cursor(&c),
            }),
            high_water_revision: page.high_water_revision,
            query_fingerprint: page.query_fingerprint,
        })
    }

    /// Counts the rows `query_memos` would return without transferring them (F1 count outlet).
    ///
    /// # Errors
    ///
    /// Store query / validation errors.
    pub fn query_count(&self, query: StoreMemoQuery) -> Result<u64, EngineError> {
        let (mq, _boundary) = memo_query_from_ffi(query);
        self.with_reader(|reader| reader.query_count(&mq))
    }

    /// Reads materialized word/character statistics rows for every active memo (F2 outlet).
    ///
    /// # Errors
    ///
    /// Store projection errors.
    pub fn memo_statistics_rows(&self) -> Result<Vec<StoreMemoStatisticsRow>, EngineError> {
        let rows = self.with_reader(StoreReader::memo_statistics_rows)?;
        Ok(rows
            .into_iter()
            .map(|row| StoreMemoStatisticsRow {
                created_at_ms: row.created_at_ms,
                word_count: row.word_count,
                char_count: row.char_count,
            })
            .collect())
    }

    /// Reads the complete active sidebar aggregate without memo pagination.
    ///
    /// # Errors
    ///
    /// Store projection errors.
    pub fn sidebar_projection(&self) -> Result<StoreSidebarProjection, EngineError> {
        let projection = self.with_reader(StoreReader::sidebar_projection)?;
        Ok(StoreSidebarProjection {
            schema_version: projection.schema_version,
            memo_count: projection.memo_count,
            date_counts: projection
                .date_counts
                .into_iter()
                .map(|bucket| StoreSidebarDateCount {
                    date: bucket.date,
                    count: bucket.count,
                })
                .collect(),
            tag_counts: projection
                .tag_counts
                .into_iter()
                .map(|tag| StoreSidebarTagCount {
                    name: tag.name,
                    count: tag.count,
                })
                .collect(),
        })
    }

    /// See plan `get_memo`.
    ///
    /// # Errors
    ///
    /// Store errors.
    pub fn get_memo(&self, memo_id: &str) -> Result<Option<StoreMemoSnapshot>, EngineError> {
        if self.mode == StoreWorkspaceMode::Saf {
            let snapshot = self.with_reader(|reader| reader.get_projected_memo(memo_id))?;
            return Ok(snapshot.map(|value| StoreMemoSnapshot {
                summary: summary_to_ffi(value.summary),
                body: value.body,
            }));
        }
        let snap = self.with_reader(|reader| reader.get_memo(memo_id))?;
        Ok(snap.map(|s| StoreMemoSnapshot {
            summary: summary_to_ffi(s.summary),
            body: s.body,
        }))
    }

    /// Canonical source-document fingerprint without a platform directory enumeration.
    ///
    /// # Errors
    ///
    /// Store validation/corruption/storage errors.
    pub fn source_document_fingerprint(
        &self,
        source_path: &str,
    ) -> Result<Option<String>, EngineError> {
        self.with_reader(|reader| reader.source_document_fingerprint(source_path))
    }

    /// D6 history-window attachment paths for media orphan refcount.
    ///
    /// # Errors
    ///
    /// Store history list errors.
    pub fn list_history_attachment_refs(
        &self,
    ) -> Result<Vec<StoreHistoryAttachmentRef>, EngineError> {
        let refs = self.with_reader(StoreReader::list_history_attachment_refs)?;
        Ok(refs
            .into_iter()
            .map(|r| StoreHistoryAttachmentRef {
                memo_id: r.memo_id,
                revision: r.revision,
                relative_path: r.relative_path,
                owner_key: r.owner_key,
            })
            .collect())
    }

    ///
    /// # Errors
    ///
    /// Store history list errors.
    pub fn list_memo_history(
        &self,
        memo_id: &str,
        cursor: Option<&str>,
        limit: u32,
    ) -> Result<StoreMemoHistoryPage, EngineError> {
        let page =
            self.with_reader(|reader| reader.list_memo_history(memo_id, cursor, limit as usize))?;
        Ok(StoreMemoHistoryPage {
            items: page
                .items
                .into_iter()
                .map(|item| StoreMemoHistoryRevision {
                    revision: item.revision,
                    created_at_ms: item.created_at_ms,
                    content: item.content,
                    file_fingerprint: item.file_fingerprint,
                })
                .collect(),
            next_cursor: page.next_cursor,
        })
    }

    /// See plan `apply_memo_command` (dark-build returns commit facts synchronously).
    ///
    /// # Errors
    ///
    /// Store transaction errors.
    pub fn apply_memo_command(
        &self,
        command: StoreMemoCommand,
    ) -> Result<StoreMemoCommit, EngineError> {
        let kind = match command.kind {
            StoreMemoCommandKind::Create => MemoCommandKind::Create,
            StoreMemoCommandKind::Update => MemoCommandKind::Update,
            StoreMemoCommandKind::Delete => MemoCommandKind::Delete,
            StoreMemoCommandKind::PermanentDelete => MemoCommandKind::PermanentDelete,
            StoreMemoCommandKind::Restore => MemoCommandKind::Restore,
            StoreMemoCommandKind::Pin => MemoCommandKind::Pin,
            StoreMemoCommandKind::Unpin => MemoCommandKind::Unpin,
            StoreMemoCommandKind::HistoryRestore => MemoCommandKind::HistoryRestore,
        };
        let operation_id = OperationId::parse(&command.operation_id).map_err(EngineError::from)?;
        let chronology_epoch_ms = command.chronology_epoch_ms;
        let pending_promotes =
            crate::media_ffi::pending_promotes_from_ffi(&command.pending_promotes)?;
        let inner = MemoCommand {
            operation_id,
            kind,
            memo_id: command.memo_id,
            expected_revision: command.expected_revision,
            expected_fingerprint: command.expected_fingerprint,
            content: command.content,
            tags: command.tags,
            pin: command.pin,
            pending_promotes,
        };
        let result = self.with_store_mut(|store| {
            store.apply_memo_command_with_created_at(&inner, chronology_epoch_ms, None)
        })?;
        Ok(StoreMemoCommit {
            operation_id: result.operation_id,
            memo_id: result.memo_id,
            core_revision: result.core_revision.get(),
            event_sequence: result.event_sequence.get(),
            content_revision: result.content_revision,
            file_fingerprint: result.file_fingerprint,
            scopes: result.scopes.into_iter().map(scope_name).collect(),
            idempotent_replay: result.idempotent_replay,
        })
    }

    /// Resolves the staged media candidates referenced by one Markdown body.
    ///
    /// This is a pure Rust planning edge shared by Direct and SAF writes.  The platform shell may
    /// execute the returned path-only plans, but it cannot choose a different subset with a second
    /// Markdown parser.
    ///
    /// # Errors
    ///
    /// Returns a typed validation or Markdown-projection error when a candidate is malformed or
    /// the body contains an ambiguous staged destination.
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI boundary requires owned body and candidate DTOs"
    )]
    pub fn select_memo_promote_plans(
        &self,
        content: String,
        candidates: Vec<MediaPromotePlanDto>,
    ) -> Result<Vec<MediaPromotePlanDto>, EngineError> {
        let plans = crate::media_ffi::pending_promotes_from_ffi(&candidates)?;
        let selected =
            store::select_pending_promotes(&content, &plans).map_err(EngineError::from)?;
        Ok(candidates
            .into_iter()
            .filter(|candidate| crate::media_ffi::pending_promote_is_selected(candidate, &selected))
            .collect())
    }

    /// Permanently deletes a bounded Direct-workspace trash batch in one store publication.
    ///
    /// SAF callers must first complete their provider actions and use the projection batch
    /// boundary below; allowing this method to silently bypass SAF would violate the workspace
    /// capability fence.
    ///
    /// # Errors
    ///
    /// Returns a mode, identity, validation, conflict, or storage error when the batch cannot be
    /// committed atomically.
    pub fn permanent_delete_many(
        &self,
        request: StoreMemoBatchDelete,
    ) -> Result<StoreMemoBatchCommit, EngineError> {
        if self.mode != StoreWorkspaceMode::Direct {
            return Err(EngineError::from(boundary_err(
                "saf_batch_requires_platform_actions",
                "SAF permanent-delete batches must commit through the verified platform-action boundary",
            )));
        }
        let operation_id = OperationId::parse(&request.operation_id).map_err(EngineError::from)?;
        let targets = request
            .targets
            .into_iter()
            .map(|target| {
                if target.result_fingerprint.is_some() {
                    return Err(EngineError::from(boundary_err(
                        "direct_batch_result_fingerprint_unexpected",
                        "Direct permanent-delete targets must not carry a platform result fingerprint",
                    )));
                }
                Ok(store::PermanentDeleteTarget {
                    memo_id: target.memo_id,
                    source_path: target.source_path,
                    expected_revision: target.expected_revision,
                    expected_fingerprint: target.expected_fingerprint,
                })
            })
            .collect::<Result<Vec<_>, EngineError>>()?;
        let result =
            self.with_store_mut(|store| store.permanent_delete_many(&operation_id, &targets))?;
        Ok(batch_commit_to_ffi(result))
    }

    /// Commits verified SAF permanent-delete platform results in one projection transaction.
    ///
    /// # Errors
    ///
    /// Returns a mode, validation, conflict, or storage error when any provider result is missing
    /// or stale.
    pub fn commit_saf_permanent_delete_many(
        &self,
        request: StoreMemoBatchDelete,
    ) -> Result<StoreMemoBatchCommit, EngineError> {
        if self.mode != StoreWorkspaceMode::Saf {
            return Err(EngineError::from(boundary_err(
                "saf_batch_projection_requires_saf",
                "SAF batch projection commit is available only for SAF stores",
            )));
        }
        let targets = request
            .targets
            .into_iter()
            .map(|target| {
                let result_fingerprint = target.result_fingerprint.ok_or_else(|| {
                    EngineError::from(boundary_err(
                        "saf_batch_result_fingerprint_missing",
                        "SAF batch projection requires every verified result fingerprint",
                    ))
                })?;
                Ok(store::SafPermanentDeleteTarget {
                    memo_id: target.memo_id,
                    source_path: target.source_path,
                    expected_revision: target.expected_revision,
                    expected_fingerprint: target.expected_fingerprint,
                    result_fingerprint,
                })
            })
            .collect::<Result<Vec<_>, EngineError>>()?;
        let result = self.with_projection_store_mut(|store| {
            store.commit_saf_permanent_delete_many(&request.operation_id, &targets)
        })?;
        Ok(saf_batch_commit_to_ffi(result))
    }

    /// Commits facts from a completed SAF platform job into the app-private projection only.
    ///
    /// # Errors
    ///
    /// Rejects Direct callers, malformed facts, stale revisions, or projection transaction errors.
    pub fn commit_saf_projection_mutation(
        &self,
        command: StoreMemoCommand,
        projection: Option<StoreSafMemoProjection>,
    ) -> Result<StoreMemoCommit, EngineError> {
        let trashed_at_ms = projection.as_ref().and_then(|value| value.trashed_at_ms);
        let kind = match command.kind {
            StoreMemoCommandKind::Create => store::SafProjectionMutationKind::Create,
            StoreMemoCommandKind::Update => store::SafProjectionMutationKind::Update,
            StoreMemoCommandKind::Delete => store::SafProjectionMutationKind::Delete,
            StoreMemoCommandKind::PermanentDelete => {
                store::SafProjectionMutationKind::PermanentDelete
            }
            StoreMemoCommandKind::Restore => store::SafProjectionMutationKind::Restore,
            StoreMemoCommandKind::Pin => store::SafProjectionMutationKind::Pin,
            StoreMemoCommandKind::Unpin => store::SafProjectionMutationKind::Unpin,
            StoreMemoCommandKind::HistoryRestore => {
                store::SafProjectionMutationKind::HistoryRestore
            }
        };
        let facts = projection.map(|value| store::ScannedMemoProjection {
            memo_id: value.memo_id,
            source_path: value.source_path,
            file_fingerprint: value.file_fingerprint,
            chronology_epoch_ms: value.chronology_epoch_ms,
            body: value.body,
            tags: value.tags,
            attachment_paths: value.attachment_paths,
            has_todo: value.has_todo,
            has_url: value.has_url,
            reminders: value
                .reminders
                .into_iter()
                .map(crate::workspace_reminder_from_ffi)
                .collect(),
        });
        let mutation = store::SafProjectionMutation {
            operation_id: command.operation_id,
            kind,
            memo_id: command.memo_id,
            expected_revision: command.expected_revision,
            expected_fingerprint: command.expected_fingerprint,
            projection: facts,
            trashed_at_ms,
        };
        let result = self
            .with_projection_store_mut(|store| store.commit_saf_projection_mutation(&mutation))?;
        Ok(StoreMemoCommit {
            operation_id: result.operation_id,
            memo_id: result.memo_id,
            core_revision: result.core_revision,
            event_sequence: result.event_sequence,
            content_revision: result.content_revision,
            file_fingerprint: result.file_fingerprint,
            scopes: result.scopes.into_iter().map(scope_name).collect(),
            idempotent_replay: result.idempotent_replay,
        })
    }

    /// Commits Rust-parsed facts from a completed workspace document command into the projection.
    ///
    /// Unlike the SAF platform pipeline this boundary is valid for both Direct and SAF stores:
    /// the workspace owner has already performed and verified the document write. No Markdown is
    /// written here, preventing the former double-write/full-rebuild path.
    ///
    /// # Errors
    ///
    /// Returns validation, conflict, corruption, or storage errors when the facts do not match the
    /// current projection.
    pub fn commit_workspace_document_facts(
        &self,
        command: StoreMemoCommand,
        projection: StoreSafMemoProjection,
    ) -> Result<StoreMemoCommit, EngineError> {
        let kind = match command.kind {
            StoreMemoCommandKind::Update => store::SafProjectionMutationKind::Update,
            StoreMemoCommandKind::Create
            | StoreMemoCommandKind::Delete
            | StoreMemoCommandKind::PermanentDelete
            | StoreMemoCommandKind::Restore
            | StoreMemoCommandKind::Pin
            | StoreMemoCommandKind::Unpin
            | StoreMemoCommandKind::HistoryRestore => {
                return Err(EngineError::from(boundary_err(
                    "invalid_document_projection_kind",
                    &format!(
                        "workspace document facts require Update kind, got {:?}",
                        command.kind
                    ),
                )));
            }
        };
        let facts = store::ScannedMemoProjection {
            memo_id: projection.memo_id,
            source_path: projection.source_path,
            file_fingerprint: projection.file_fingerprint,
            chronology_epoch_ms: projection.chronology_epoch_ms,
            body: projection.body,
            tags: projection.tags,
            attachment_paths: projection.attachment_paths,
            has_todo: projection.has_todo,
            has_url: projection.has_url,
            reminders: projection
                .reminders
                .into_iter()
                .map(crate::workspace_reminder_from_ffi)
                .collect(),
        };
        let mutation = store::SafProjectionMutation {
            operation_id: command.operation_id,
            kind,
            memo_id: command.memo_id,
            expected_revision: command.expected_revision,
            expected_fingerprint: command.expected_fingerprint,
            projection: Some(facts),
            trashed_at_ms: None,
        };
        let result = self.with_any_projection_store_mut(|store| {
            store.commit_workspace_document_facts(&mutation)
        })?;
        Ok(StoreMemoCommit {
            operation_id: result.operation_id,
            memo_id: result.memo_id,
            core_revision: result.core_revision,
            event_sequence: result.event_sequence,
            content_revision: result.content_revision,
            file_fingerprint: result.file_fingerprint,
            scopes: result.scopes.into_iter().map(scope_name).collect(),
            idempotent_replay: result.idempotent_replay,
        })
    }

    /// Publishes a pending SAF memo create projection before durable platform I/O.
    ///
    /// # Errors
    ///
    /// Rejects Direct callers, malformed begin facts, or projection transaction errors.
    pub fn begin_saf_memo_create(
        &self,
        begin: StoreSafMemoCreateBegin,
    ) -> Result<StoreSafMemoCreateBeginResult, EngineError> {
        let inner = store::SafMemoCreateBegin {
            operation_id: begin.operation_id,
            date_key: begin.date_key,
            time_part: begin.time_part,
            chronology_epoch_ms: begin.chronology_epoch_ms,
            source_path: begin.source_path,
            body: begin.body,
        };
        let result = self.with_projection_store_mut(|store| store.begin_saf_memo_create(&inner))?;
        Ok(StoreSafMemoCreateBeginResult {
            memo_id: result.memo_id,
            core_revision: result.core_revision,
            event_sequence: result.event_sequence,
            scopes: result.scopes.into_iter().map(scope_name).collect(),
            idempotent_replay: result.idempotent_replay,
        })
    }

    /// Removes a begun SAF memo create's pending projection when the pipeline fails.
    ///
    /// # Errors
    ///
    /// Rejects Direct callers, malformed identifiers, or projection transaction errors.
    pub fn rollback_saf_memo_create(
        &self,
        operation_id: &str,
        memo_id: &str,
    ) -> Result<StoreSafMemoRollbackResult, EngineError> {
        let result = self.with_projection_store_mut(|store| {
            store.rollback_saf_memo_create(operation_id, memo_id)
        })?;
        Ok(match result {
            Some(publication) => StoreSafMemoRollbackResult {
                removed: true,
                core_revision: publication.core_revision,
                event_sequence: publication.event_sequence,
                scopes: publication.scopes.into_iter().map(scope_name).collect(),
            },
            None => StoreSafMemoRollbackResult {
                removed: false,
                core_revision: 0,
                event_sequence: 0,
                scopes: Vec::new(),
            },
        })
    }

    /// See plan `query_reminder_plan`.
    ///
    /// # Errors
    ///
    /// Reminder plan errors.
    pub fn query_reminder_plan(
        &self,
        query: StoreReminderQuery,
    ) -> Result<StoreReminderPlan, EngineError> {
        let zone = TimeZoneContext {
            zone_id: query.zone.zone_id,
            base_offset_secs: query.zone.base_offset_secs,
            transitions: query
                .zone
                .transitions
                .into_iter()
                .map(|t| ZoneTransition {
                    transition_utc_ms: t.transition_utc_ms,
                    offset_before_secs: t.offset_before_secs,
                    offset_after_secs: t.offset_after_secs,
                })
                .collect(),
        };
        let sessions = query
            .sessions
            .into_iter()
            .map(|s| ReminderSessionInput {
                opaque_id: s.opaque_id,
                memo_identity: s.memo_identity,
                memo_revision: s.memo_revision,
                token: s.token,
                due_at_local: s.due_at_local,
                repeat_count: s.repeat_count,
                fired_count: s.fired_count,
                done: s.done,
                interval_minutes: s.interval_minutes,
                recurrence_code: s.recurrence_code,
            })
            .collect();
        let rolling_window = usize::try_from(query.rolling_window).unwrap_or(usize::MAX);
        let inner = ReminderQuery {
            now_utc_ms: query.now_utc_ms,
            zone,
            sessions,
            rolling_window,
            workspace_generation: query.workspace_generation,
        };
        let plan = {
            let snooze = self.snooze.lock().map_err(|_e| {
                EngineError::from(boundary_err(
                    "snooze_mutex_poisoned",
                    "snooze mutex poisoned",
                ))
            })?;
            store::query_reminder_plan(&inner, &snooze).map_err(EngineError::from)?
        };
        Ok(StoreReminderPlan {
            alarms: plan
                .alarms
                .into_iter()
                .map(|a| StorePlannedAlarm {
                    opaque_id: a.opaque_id,
                    memo_identity: a.memo_identity,
                    trigger_at_utc_ms: a.trigger_at_utc_ms,
                    is_catch_up: a.is_catch_up,
                })
                .collect(),
            workspace_generation: plan.workspace_generation,
        })
    }

    /// See plan `apply_reminder_command`.
    ///
    /// # Errors
    ///
    /// Reminder command errors.
    pub fn apply_reminder_command(
        &self,
        command: StoreReminderCommand,
    ) -> Result<StoreReminderCommandResult, EngineError> {
        let inner = reminder_command_from_ffi(command)?;
        let result = {
            let mut snooze = self.snooze.lock().map_err(|_e| {
                EngineError::from(boundary_err(
                    "snooze_mutex_poisoned",
                    "snooze mutex poisoned",
                ))
            })?;
            store::apply_reminder_command(&inner, &mut snooze).map_err(EngineError::from)?
        };
        Ok(StoreReminderCommandResult {
            replacement_token: result.replacement_token,
            scopes: result.scopes.into_iter().map(scope_name).collect(),
            snooze_only: result.snooze_only,
        })
    }

    /// See plan `start_rebuild` (dark-build synchronous rebuild).
    ///
    /// # Errors
    ///
    /// Rebuild errors.
    pub fn start_rebuild(&self, batch_size: u32) -> Result<StoreRebuildResult, EngineError> {
        if self.mode == StoreWorkspaceMode::Saf {
            return Err(EngineError::from(boundary_err(
                "saf_store_rebuild_requires_workspace_scan",
                "SAF projection rebuild requires a completed workspace scan",
            )));
        }
        let _projection = self.projection_gate.write().map_err(|_error| {
            EngineError::from(boundary_err(
                "store_projection_lock_poisoned",
                "store projection write lock poisoned",
            ))
        })?;
        self.readers.clear_idle()?;
        let batch = if batch_size == 0 {
            64
        } else {
            usize::try_from(batch_size).unwrap_or(64)
        };
        {
            let mut guard = self.lock_store()?;
            // Drop live connection so rebuild can replace the file.
            drop(guard.take());
        }
        let opened = Store::open(&self.workspace_root).map_err(EngineError::from)?;
        let (store, result) = opened.rebuild(batch).map_err(EngineError::from)?;
        {
            let mut guard = self.lock_store()?;
            *guard = Some(store);
        }
        Ok(rebuild_result_to_ffi(result))
    }

    /// Begins a Rust-owned, page-at-a-time SAF projection rebuild.
    ///
    /// # Errors
    ///
    /// Returns validation for a non-SAF/busy handle or storage errors while creating the temporary
    /// projection. The live projection remains open until finish.
    pub fn begin_saf_projection_rebuild(&self) -> Result<String, EngineError> {
        if self.mode != StoreWorkspaceMode::Saf {
            return Err(EngineError::from(boundary_err(
                "saf_store_projection_requires_saf_workspace",
                "SAF projection rebuild requires a SAF workspace",
            )));
        }
        let mut state = self.saf_rebuild.lock().map_err(|_error| {
            EngineError::from(boundary_err(
                "store_projection_mutex_poisoned",
                "store projection mutex poisoned",
            ))
        })?;
        if state.is_some() {
            return Err(EngineError::from(boundary_err(
                "saf_projection_rebuild_busy",
                "a SAF projection rebuild is already active",
            )));
        }
        let rebuild =
            store::SafProjectionRebuild::begin(&self.workspace_root).map_err(EngineError::from)?;
        let id = format!(
            "saf-rebuild-{}",
            NEXT_SAF_REBUILD_ID.fetch_add(1, Ordering::Relaxed)
        );
        *state = Some(SafProjectionRebuildState {
            id: id.clone(),
            rebuild,
        });
        drop(state);
        Ok(id)
    }

    /// Appends one bounded SAF scan page. Exchange artifacts are read and verified in Rust.
    ///
    /// # Errors
    ///
    /// Returns validation for a missing/mismatched rebuild, invalid exchange reference, duplicate
    /// memo, oversized page, or projection write failure.
    pub fn append_saf_projection_rebuild_page(
        &self,
        rebuild_id: &str,
        memos: Vec<StoreSafMemoProjectionReference>,
        exchange_root: &Path,
    ) -> Result<(), EngineError> {
        let mut state_guard = self.saf_rebuild.lock().map_err(|_error| {
            EngineError::from(boundary_err(
                "store_projection_mutex_poisoned",
                "store projection mutex poisoned",
            ))
        })?;
        let state = state_guard.as_mut().ok_or_else(|| {
            EngineError::from(boundary_err(
                "saf_projection_rebuild_missing",
                "no SAF projection rebuild is active",
            ))
        })?;
        if state.id != rebuild_id {
            return Err(EngineError::from(boundary_err(
                "saf_projection_rebuild_id_mismatch",
                "SAF projection rebuild id does not match the active rebuild",
            )));
        }
        let mut projections = Vec::with_capacity(memos.len());
        let mut consumed_tokens = Vec::with_capacity(memos.len());
        for memo in memos {
            consumed_tokens.push(memo.content.exchange_token.clone());
            let body = read_projection_exchange_body(exchange_root, &memo.content)?;
            projections.push(store::ScannedMemoProjection {
                memo_id: memo.memo_id,
                source_path: memo.source_path,
                file_fingerprint: memo.file_fingerprint,
                chronology_epoch_ms: memo.chronology_epoch_ms,
                body,
                tags: memo.tags,
                attachment_paths: memo.attachment_paths,
                has_todo: memo.has_todo,
                has_url: memo.has_url,
                reminders: memo
                    .reminders
                    .into_iter()
                    .map(crate::workspace_reminder_from_ffi)
                    .collect(),
            });
        }
        state
            .rebuild
            .append_page(&projections)
            .map_err(EngineError::from)?;
        for token in consumed_tokens {
            remove_projection_exchange_body(exchange_root, &token)?;
        }
        drop(state_guard);
        Ok(())
    }

    /// Appends one bounded durable trash scan page. Exchange artifacts are read and verified in
    /// Rust, then the recoverable body replaces the active body in the pending snapshot.
    ///
    /// # Errors
    ///
    /// Returns validation for a missing/mismatched rebuild, malformed trash facts, invalid
    /// exchange references, oversized/duplicate pages, or projection write failure.
    pub fn append_saf_trash_projection_rebuild_page(
        &self,
        rebuild_id: &str,
        memos: Vec<StoreSafTrashProjectionReference>,
        exchange_root: &Path,
    ) -> Result<(), EngineError> {
        let mut state_guard = self.saf_rebuild.lock().map_err(|_error| {
            EngineError::from(boundary_err(
                "store_projection_mutex_poisoned",
                "store projection mutex poisoned",
            ))
        })?;
        let state = state_guard.as_mut().ok_or_else(|| {
            EngineError::from(boundary_err(
                "saf_projection_rebuild_missing",
                "no SAF projection rebuild is active",
            ))
        })?;
        if state.id != rebuild_id {
            return Err(EngineError::from(boundary_err(
                "saf_projection_rebuild_id_mismatch",
                "SAF projection rebuild id does not match the active rebuild",
            )));
        }
        let mut projections = Vec::with_capacity(memos.len());
        let mut consumed_tokens = Vec::with_capacity(memos.len());
        for memo in memos {
            consumed_tokens.push(memo.content.exchange_token.clone());
            let body = read_projection_exchange_body(exchange_root, &memo.content)?;
            projections.push(store::ScannedTrashProjection {
                memo: store::ScannedMemoProjection {
                    memo_id: memo.memo_id,
                    source_path: memo.source_path,
                    file_fingerprint: memo.file_fingerprint,
                    chronology_epoch_ms: memo.chronology_epoch_ms,
                    body,
                    tags: memo.tags,
                    attachment_paths: memo.attachment_paths,
                    has_todo: memo.has_todo,
                    has_url: memo.has_url,
                    reminders: memo
                        .reminders
                        .into_iter()
                        .map(crate::workspace_reminder_from_ffi)
                        .collect(),
                },
                trashed_at_ms: memo.trashed_at_ms,
            });
        }
        state
            .rebuild
            .append_trash_page(&projections)
            .map_err(EngineError::from)?;
        for token in consumed_tokens {
            remove_projection_exchange_body(exchange_root, &token)?;
        }
        drop(state_guard);
        Ok(())
    }

    /// Appends one bounded durable history scan page to the active rebuild.
    ///
    /// # Errors
    ///
    /// Returns validation for malformed facts/exchange references or projection write failure.
    pub fn append_saf_history_projection_rebuild_page(
        &self,
        rebuild_id: &str,
        revisions: Vec<StoreSafHistoryProjectionReference>,
        exchange_root: &Path,
    ) -> Result<(), EngineError> {
        let mut state_guard = self.saf_rebuild.lock().map_err(|_error| {
            EngineError::from(boundary_err(
                "store_projection_mutex_poisoned",
                "store projection mutex poisoned",
            ))
        })?;
        let state = state_guard.as_mut().ok_or_else(|| {
            EngineError::from(boundary_err(
                "saf_projection_rebuild_missing",
                "no SAF projection rebuild is active",
            ))
        })?;
        if state.id != rebuild_id {
            return Err(EngineError::from(boundary_err(
                "saf_projection_rebuild_id_mismatch",
                "SAF projection rebuild id does not match the active rebuild",
            )));
        }
        let mut projections = Vec::with_capacity(revisions.len());
        let mut consumed_tokens = Vec::with_capacity(revisions.len());
        for revision in revisions {
            consumed_tokens.push(revision.content.exchange_token.clone());
            let content = read_projection_exchange_body(exchange_root, &revision.content)?;
            projections.push(store::ScannedHistoryProjection {
                record_id: format!("{}-r{}", revision.memo_id, revision.revision),
                memo_id: revision.memo_id,
                revision: revision.revision,
                created_at_ms: revision.created_at_ms,
                content,
                file_fingerprint: revision.file_fingerprint,
            });
        }
        state
            .rebuild
            .append_history_page(&projections)
            .map_err(EngineError::from)?;
        for token in consumed_tokens {
            remove_projection_exchange_body(exchange_root, &token)?;
        }
        drop(state_guard);
        Ok(())
    }

    /// Finishes and atomically publishes the active SAF projection rebuild.
    ///
    /// # Errors
    ///
    /// Returns validation for a missing/mismatched rebuild or storage/corruption errors while
    /// verifying and publishing it. On publish failure the previous live projection is reopened.
    pub fn finish_saf_projection_rebuild(
        &self,
        rebuild_id: &str,
    ) -> Result<StoreRebuildResult, EngineError> {
        // The projection gate is the single publication boundary shared with SAF mutations and
        // queries. It must span final revision validation, live-state copy, atomic replacement,
        // and reopening so no writer can enter the revision-check-to-rename window.
        let _projection = self.projection_gate.write().map_err(|_error| {
            EngineError::from(boundary_err(
                "store_projection_lock_poisoned",
                "store projection write lock poisoned",
            ))
        })?;
        self.readers.clear_idle()?;
        let state = {
            let mut guard = self.saf_rebuild.lock().map_err(|_error| {
                EngineError::from(boundary_err(
                    "store_projection_mutex_poisoned",
                    "store projection mutex poisoned",
                ))
            })?;
            let state = guard.take().ok_or_else(|| {
                EngineError::from(boundary_err(
                    "saf_projection_rebuild_missing",
                    "no SAF projection rebuild is active",
                ))
            })?;
            if state.id != rebuild_id {
                *guard = Some(state);
                drop(guard);
                return Err(EngineError::from(boundary_err(
                    "saf_projection_rebuild_id_mismatch",
                    "SAF projection rebuild id does not match the active rebuild",
                )));
            }
            drop(guard);
            state
        };
        {
            let mut store_guard = self.lock_store()?;
            drop(store_guard.take());
            drop(store_guard);
        }
        let result = match state.rebuild.finish() {
            Ok(result) => result,
            Err(error) => {
                let reopened =
                    Store::open_projection(&self.workspace_root).map_err(EngineError::from)?;
                let mut store_guard = self.lock_store()?;
                *store_guard = Some(reopened);
                drop(store_guard);
                return Err(EngineError::from(error));
            }
        };
        let reopened = Store::open_projection(&self.workspace_root).map_err(EngineError::from)?;
        let mut store_guard = self.lock_store()?;
        *store_guard = Some(reopened);
        drop(store_guard);
        Ok(rebuild_result_to_ffi(result))
    }

    /// Aborts the active SAF projection rebuild without changing the live projection.
    ///
    /// # Errors
    ///
    /// Returns validation for a missing/mismatched rebuild or storage errors while removing its
    /// temporary artifacts.
    pub fn abort_saf_projection_rebuild(&self, rebuild_id: &str) -> Result<(), EngineError> {
        let state = {
            let mut guard = self.saf_rebuild.lock().map_err(|_error| {
                EngineError::from(boundary_err(
                    "store_projection_mutex_poisoned",
                    "store projection mutex poisoned",
                ))
            })?;
            let state = guard.take().ok_or_else(|| {
                EngineError::from(boundary_err(
                    "saf_projection_rebuild_missing",
                    "no SAF projection rebuild is active",
                ))
            })?;
            if state.id != rebuild_id {
                *guard = Some(state);
                drop(guard);
                return Err(EngineError::from(boundary_err(
                    "saf_projection_rebuild_id_mismatch",
                    "SAF projection rebuild id does not match the active rebuild",
                )));
            }
            drop(guard);
            state
        };
        state.rebuild.abort().map_err(EngineError::from)
    }

    #[must_use]
    pub fn workspace_root(&self) -> &Path {
        &self.workspace_root
    }
}

fn read_projection_exchange_body(
    exchange_root: &Path,
    reference: &crate::WorkspaceMemoContentReference,
) -> Result<String, EngineError> {
    let token = ExchangeToken::parse(&reference.exchange_token).map_err(EngineError::from)?;
    let path = exchange_root.join(token.as_str());
    let bytes = fs::read(&path).map_err(|error| {
        let diagnostic = format!("cannot read SAF projection exchange artifact: {error}");
        EngineError::from(boundary_err(
            "saf_projection_exchange_read_failed",
            &diagnostic,
        ))
    })?;
    if u64::try_from(bytes.len()).map_err(|_error| {
        EngineError::from(boundary_err(
            "saf_projection_exchange_length_invalid",
            "exchange artifact length exceeds u64",
        ))
    })? != reference.length
    {
        return Err(EngineError::from(boundary_err(
            "saf_projection_exchange_length_mismatch",
            "exchange artifact length differs from the scan reference",
        )));
    }
    let body = String::from_utf8(bytes).map_err(|_error| {
        EngineError::from(boundary_err(
            "saf_projection_exchange_not_utf8",
            "exchange artifact is not valid UTF-8",
        ))
    })?;
    if store::fingerprint_content(&body) != reference.digest {
        return Err(EngineError::from(boundary_err(
            "saf_projection_exchange_digest_mismatch",
            "exchange artifact digest differs from the scan reference",
        )));
    }
    Ok(body)
}

fn remove_projection_exchange_body(exchange_root: &Path, token: &str) -> Result<(), EngineError> {
    let token = ExchangeToken::parse(token).map_err(EngineError::from)?;
    match fs::remove_file(exchange_root.join(token.as_str())) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(EngineError::from(boundary_err(
            "saf_projection_exchange_cleanup_failed",
            &format!("cannot remove consumed exchange artifact: {error}"),
        ))),
    }
}

fn rebuild_result_to_ffi(result: store::RebuildResult) -> StoreRebuildResult {
    StoreRebuildResult {
        memos_indexed: result.memos_indexed,
        file_count: result.file_count,
        attachment_count: result.attachment_count,
        workspace_digest: result.workspace_digest,
        store_digest: result.store_digest,
        corrupt_lomo_isolated: result.corrupt_lomo_isolated,
        high_water_revision: result.high_water_revision,
    }
}

#[expect(
    clippy::too_many_lines,
    reason = "FFI enum arm mapping is one conversion table for all reminder command kinds"
)]
fn reminder_command_from_ffi(
    command: StoreReminderCommand,
) -> Result<ReminderCommand, EngineError> {
    match command.kind {
        StoreReminderCommandKind::MarkDone => {
            let session = command.session.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "MarkDone requires session",
                ))
            })?;
            let expected = command.expected_revision.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "MarkDone requires expected_revision",
                ))
            })?;
            Ok(ReminderCommand::MarkDone {
                session: session_from_ffi(session),
                expected_revision: expected,
            })
        }
        StoreReminderCommandKind::RecordFired => {
            let session = command.session.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "RecordFired requires session",
                ))
            })?;
            let expected = command.expected_revision.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "RecordFired requires expected_revision",
                ))
            })?;
            Ok(ReminderCommand::RecordFired {
                session: session_from_ffi(session),
                expected_revision: expected,
            })
        }
        StoreReminderCommandKind::Snooze => {
            let opaque_id = command.opaque_id.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "Snooze requires opaque_id",
                ))
            })?;
            let memo_identity = command.memo_identity.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "Snooze requires memo_identity",
                ))
            })?;
            let memo_revision = command.memo_revision.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "Snooze requires memo_revision",
                ))
            })?;
            let workspace_generation = command.workspace_generation.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "Snooze requires workspace_generation",
                ))
            })?;
            let snooze_until_utc_ms = command.snooze_until_utc_ms.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "Snooze requires snooze_until_utc_ms",
                ))
            })?;
            Ok(ReminderCommand::Snooze {
                opaque_id,
                memo_identity,
                memo_revision,
                workspace_generation,
                snooze_until_utc_ms,
            })
        }
        StoreReminderCommandKind::ClearSnooze => {
            let opaque_id = command.opaque_id.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "ClearSnooze requires opaque_id",
                ))
            })?;
            let memo_identity = command.memo_identity.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "ClearSnooze requires memo_identity",
                ))
            })?;
            let memo_revision = command.memo_revision.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "ClearSnooze requires memo_revision",
                ))
            })?;
            let workspace_generation = command.workspace_generation.ok_or_else(|| {
                EngineError::from(boundary_err(
                    "invalid_reminder_command",
                    "ClearSnooze requires workspace_generation",
                ))
            })?;
            Ok(ReminderCommand::ClearSnooze {
                opaque_id,
                memo_identity,
                memo_revision,
                workspace_generation,
            })
        }
    }
}

fn session_from_ffi(s: StoreReminderSession) -> ReminderSessionInput {
    ReminderSessionInput {
        opaque_id: s.opaque_id,
        memo_identity: s.memo_identity,
        memo_revision: s.memo_revision,
        token: s.token,
        due_at_local: s.due_at_local,
        repeat_count: s.repeat_count,
        fired_count: s.fired_count,
        done: s.done,
        interval_minutes: s.interval_minutes,
        recurrence_code: s.recurrence_code,
    }
}

pub fn summary_to_ffi(s: store::MemoSummary) -> StoreMemoSummary {
    StoreMemoSummary {
        memo_id: s.memo_id,
        source_path: s.source_path,
        file_fingerprint: s.file_fingerprint,
        updated_at_ms: s.updated_at_ms,
        created_at_ms: s.created_at_ms,
        has_todo: s.has_todo,
        has_url: s.has_url,
        has_attachment: s.has_attachment,
        is_pinned: s.is_pinned,
        is_trashed: s.is_trashed,
        body_preview: s.body_preview,
        content_revision: s.content_revision,
        rank: s.rank,
        tags: s.tags,
        image_urls: s.image_urls,
        reminders: s
            .reminders
            .into_iter()
            .map(crate::workspace_reminder_to_ffi)
            .collect(),
        is_pending: s.is_pending,
    }
}

fn batch_commit_to_ffi(result: store::PermanentDeleteManyResult) -> StoreMemoBatchCommit {
    StoreMemoBatchCommit {
        operation_id: result.operation_id,
        deleted: result
            .deleted
            .into_iter()
            .map(|memo| StoreMemoDeletedMemo {
                memo_id: memo.memo_id,
                reminder_ids: memo.reminder_ids,
            })
            .collect(),
        core_revision: result.core_revision.get(),
        event_sequence: result.event_sequence.get(),
        scopes: result.scopes.into_iter().map(scope_name).collect(),
        idempotent_replay: result.idempotent_replay,
    }
}

fn saf_batch_commit_to_ffi(result: store::SafPermanentDeleteManyResult) -> StoreMemoBatchCommit {
    StoreMemoBatchCommit {
        operation_id: result.operation_id,
        deleted: result
            .deleted
            .into_iter()
            .map(|memo| StoreMemoDeletedMemo {
                memo_id: memo.memo_id,
                reminder_ids: memo.reminder_ids,
            })
            .collect(),
        core_revision: result.core_revision,
        event_sequence: result.event_sequence,
        scopes: result.scopes.into_iter().map(scope_name).collect(),
        idempotent_replay: result.idempotent_replay,
    }
}

pub fn scope_name(scope: lomo_core::InvalidationScope) -> String {
    match scope {
        lomo_core::InvalidationScope::MemoList => "memo_list".to_owned(),
        lomo_core::InvalidationScope::Search => "search".to_owned(),
        lomo_core::InvalidationScope::Trash => "trash".to_owned(),
        lomo_core::InvalidationScope::Pin => "pin".to_owned(),
        lomo_core::InvalidationScope::Tags => "tags".to_owned(),
        lomo_core::InvalidationScope::Stats => "stats".to_owned(),
        lomo_core::InvalidationScope::Reminder => "reminder".to_owned(),
        lomo_core::InvalidationScope::Full => "full".to_owned(),
    }
}

pub fn encode_cursor(cursor: &PageCursor) -> String {
    format!(
        "{}|{}|{}|{}|{}|{}|{}|{}",
        cursor.query_fingerprint,
        cursor
            .sort_rank_bits
            .map_or_else(|| "none".to_owned(), |rank| rank.to_string()),
        u8::from(cursor.sort_pinned),
        cursor.sort_primary_ms,
        cursor.sort_created_at_ms,
        cursor.sort_memo_id,
        cursor.high_water_revision,
        cursor.tokenizer_version
    )
}

pub fn decode_cursor(encoded: &str) -> Result<PageCursor, EngineError> {
    let parts: Vec<&str> = encoded.split('|').collect();
    let (
        Some(query_fingerprint),
        Some(sort_rank),
        Some(sort_pinned),
        Some(sort_primary),
        Some(sort_created),
        Some(sort_memo_id),
        Some(high_water),
        Some(tokenizer_version),
    ) = (
        parts.first().copied(),
        parts.get(1).copied(),
        parts.get(2).copied(),
        parts.get(3).copied(),
        parts.get(4).copied(),
        parts.get(5).copied(),
        parts.get(6).copied(),
        parts.get(7).copied(),
    )
    else {
        return Err(EngineError::from(boundary_err(
            "invalid_page_cursor",
            "store page cursor encoding mismatch",
        )));
    };
    if parts.len() != 8 {
        return Err(EngineError::from(boundary_err(
            "invalid_page_cursor",
            "store page cursor encoding mismatch",
        )));
    }
    let sort_rank_bits = if sort_rank == "none" {
        None
    } else {
        Some(sort_rank.parse::<u64>().map_err(|_e| {
            EngineError::from(boundary_err(
                "invalid_page_cursor",
                "store page cursor rank is not u64 bits",
            ))
        })?)
    };
    let sort_pinned = match sort_pinned {
        "0" => false,
        "1" => true,
        _ => {
            return Err(EngineError::from(boundary_err(
                "invalid_page_cursor",
                "store page cursor pinned key is not 0 or 1",
            )));
        }
    };
    let sort_primary_ms = sort_primary.parse::<i64>().map_err(|_e| {
        EngineError::from(boundary_err(
            "invalid_page_cursor",
            "store page cursor primary sort key is not i64",
        ))
    })?;
    let sort_created_at_ms = sort_created.parse::<i64>().map_err(|_e| {
        EngineError::from(boundary_err(
            "invalid_page_cursor",
            "store page cursor created-at key is not i64",
        ))
    })?;
    let high_water = high_water.parse::<u64>().map_err(|_e| {
        EngineError::from(boundary_err(
            "invalid_page_cursor",
            "store page cursor high_water is not u64",
        ))
    })?;
    let tokenizer_version = tokenizer_version.parse::<u32>().map_err(|_e| {
        EngineError::from(boundary_err(
            "invalid_page_cursor",
            "store page cursor tokenizer_version is not u32",
        ))
    })?;
    Ok(PageCursor {
        query_fingerprint: query_fingerprint.to_owned(),
        sort_rank_bits,
        sort_pinned,
        sort_primary_ms,
        sort_created_at_ms,
        sort_memo_id: sort_memo_id.to_owned(),
        high_water_revision: high_water,
        tokenizer_version,
    })
}

/// Converts the wire query into the store query (single conversion point for count + page reads).
pub fn memo_query_from_ffi(query: StoreMemoQuery) -> (MemoQuery, Option<MemoQueryBoundary>) {
    let boundary = query.boundary.map(|boundary| MemoQueryBoundary {
        sort_pinned: boundary.is_pinned,
        sort_primary_ms: boundary.primary_sort_ms,
        sort_created_at_ms: boundary.created_at_ms,
        sort_memo_id: boundary.memo_id,
    });
    (
        MemoQuery {
            search_text: query.search_text,
            filters: MemoFilters {
                tag: query.filters.tag,
                tag_selection: if query.filters.tag_subtree {
                    store::TagSelectionMode::Subtree
                } else {
                    store::TagSelectionMode::Exact
                },
                date_from_inclusive_ms: query.filters.date_from_inclusive_ms,
                date_until_exclusive_ms: query.filters.date_until_exclusive_ms,
                has_todo: query.filters.has_todo,
                has_attachment: query.filters.has_attachment,
                has_url: query.filters.has_url,
                pinned_only: query.filters.pinned_only,
                include_trash: query.filters.include_trash,
                trash_only: query.filters.trash_only,
            },
            sort: store::MemoSort {
                field: match query.sort.field {
                    StoreMemoSortField::CreatedAt => store::MemoSortField::CreatedAt,
                    StoreMemoSortField::UpdatedAt => store::MemoSortField::UpdatedAt,
                },
                direction: match query.sort.direction {
                    StoreSortDirection::Ascending => store::SortDirection::Ascending,
                    StoreSortDirection::Descending => store::SortDirection::Descending,
                },
            },
        },
        boundary,
    )
}

fn boundary_err(code: &str, diagnostic: &str) -> LomoError {
    match LomoError::from_platform_boundary(
        ErrorCategory::Validation,
        code,
        RetryDisposition::Never,
        None,
        None,
        diagnostic,
    ) {
        Ok(error) | Err(error) => error,
    }
}
