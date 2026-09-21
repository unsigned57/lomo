//! Stage-3 dark-build store FFI conversion surface (P3-09).
//!
//! Conversion-only mapping between `BoltFFI` DTOs and `lomo-store`. Business rules stay in
//! `lomo-store`.

use std::{collections::BTreeMap, fs, io::Write, path::Path};

use boltffi::data;
use lomo_core::{ErrorCategory, LomoError, OperationId, RetryDisposition};
use lomo_store::{
    self as store, MemoFilters, MemoQuery, MemoQueryBoundary, MemoQueryStart, PageCursor,
};

use crate::{EngineError, media_ffi::MediaPromotePlanDto};

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

/// Stages received LAN attachments and remaps Markdown references. Document writes belong to the
/// workspace session; this helper must not open a second SQLite.
///
/// # Errors
///
/// Invalid operation identity, digest mismatch, generation mismatch, or staging failures.
pub fn prepare_received_lan_create(
    workspace_root: &Path,
    command: &lomo_lan::AuthorizedReceivedCreate,
) -> Result<(String, Vec<lomo_media::PromotePlan>), EngineError> {
    let operation_id = OperationId::parse(command.item_id().as_str()).map_err(EngineError::from)?;
    command
        .approved_generation()
        .assert_matches(lomo_workspace::load_workspace_generation(workspace_root)?.as_str())
        .map_err(EngineError::from)?;
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
            let staged = stage_received_attachment(workspace_root, attachment)?;
            if staged.digest.as_str() != attachment.digest() {
                return Err(EngineError::from(lomo_media::media_validation(
                    "lan_attachment_media_digest_mismatch",
                    "media staging digest differs from the authorized LAN digest",
                )));
            }
            let final_relative_path =
                lomo_media::resolve_received_final_relative_path(workspace_root, &staged)
                    .map_err(EngineError::from)?;
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
            return Err(EngineError::from(lomo_media::media_validation(
                "lan_attachment_source_reference_conflict",
                "one Markdown attachment reference cannot resolve to multiple received files",
            )));
        }
    }
    let content = lomo_workspace::remap_attachment_destinations(command.content(), &remaps)
        .map_err(EngineError::from)?;
    let pending_promotes = promotes_by_digest
        .into_values()
        .map(|(plan, _final_relative_path)| plan)
        .collect();
    Ok((content, pending_promotes))
}

/// Resolves staged media candidates referenced by one Markdown body without a store handle.
///
/// # Errors
///
/// Returns a typed validation or Markdown-projection error when a candidate is malformed or the
/// body contains an ambiguous staged destination.
pub fn select_memo_promote_plans_for_ffi(
    content: &str,
    candidates: Vec<MediaPromotePlanDto>,
) -> Result<Vec<MediaPromotePlanDto>, EngineError> {
    let plans = crate::media_ffi::pending_promotes_from_ffi(&candidates)?;
    let selected = store::select_pending_promotes(content, &plans).map_err(EngineError::from)?;
    Ok(candidates
        .into_iter()
        .filter(|candidate| crate::media_ffi::pending_promote_is_selected(candidate, &selected))
        .collect())
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
    pub char_count: i64,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreMemoPage {
    pub items: Vec<StoreMemoSummary>,
    pub next_cursor: Option<StorePageCursor>,
    pub prev_cursor: Option<StorePageCursor>,
    pub items_before: u64,
    pub items_after: u64,
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

/// Wire form of [`lomo_core::InvalidationScope`]. Integer discriminants, not UTF-8 names.
#[data]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum StoreInvalidationScope {
    MemoList,
    Search,
    Trash,
    Pin,
    Tags,
    Stats,
    Reminder,
    Full,
}

impl From<lomo_core::InvalidationScope> for StoreInvalidationScope {
    fn from(scope: lomo_core::InvalidationScope) -> Self {
        match scope {
            lomo_core::InvalidationScope::MemoList => Self::MemoList,
            lomo_core::InvalidationScope::Search => Self::Search,
            lomo_core::InvalidationScope::Trash => Self::Trash,
            lomo_core::InvalidationScope::Pin => Self::Pin,
            lomo_core::InvalidationScope::Tags => Self::Tags,
            lomo_core::InvalidationScope::Stats => Self::Stats,
            lomo_core::InvalidationScope::Reminder => Self::Reminder,
            lomo_core::InvalidationScope::Full => Self::Full,
        }
    }
}

#[must_use]
pub fn scopes_to_ffi(scopes: Vec<lomo_core::InvalidationScope>) -> Vec<StoreInvalidationScope> {
    scopes
        .into_iter()
        .map(StoreInvalidationScope::from)
        .collect()
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
    pub scopes: Vec<StoreInvalidationScope>,
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
    pub scopes: Vec<StoreInvalidationScope>,
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
    pub scopes: Vec<StoreInvalidationScope>,
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
    pub scopes: Vec<StoreInvalidationScope>,
}

#[data]
#[derive(Clone, Debug)]
pub struct StorePlannedAlarm {
    /// Deterministic occurrence identity (`{generation}\u{1f}{opaque_id}\u{1f}{trigger_ms}`).
    /// Platform scheduling/cancellation must key on this, not on a bare request-code hash.
    pub occurrence_id: String,
    pub opaque_id: String,
    pub memo_identity: String,
    pub trigger_at_utc_ms: i64,
    pub is_catch_up: bool,
}

#[data]
#[derive(Clone, Debug)]
pub struct StoreReminderPlan {
    pub alarms: Vec<StorePlannedAlarm>,
    /// Future alarms omitted because the rolling window is full; re-plan after a terminal
    /// occurrence event to refill the window.
    pub dropped_count: u32,
    /// Durable workspace generation (`WorkspaceGenerationId` hex) the plan was issued under.
    pub workspace_generation: String,
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
    pub rewritten: bool,
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

pub fn workspace_document_facts_mutation(
    command: StoreMemoCommand,
    projection: StoreSafMemoProjection,
) -> Result<store::SafProjectionMutation, EngineError> {
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
    Ok(store::SafProjectionMutation {
        operation_id: command.operation_id,
        kind,
        memo_id: command.memo_id,
        expected_revision: command.expected_revision,
        expected_fingerprint: command.expected_fingerprint,
        projection: Some(facts),
        trashed_at_ms: None,
    })
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
        char_count: s.char_count,
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

pub fn ffi_query_start<'a>(
    cursor: Option<&'a PageCursor>,
    start_memo_id: Option<&'a str>,
    backward: bool,
) -> Result<MemoQueryStart<'a>, LomoError> {
    match (cursor, start_memo_id, backward) {
        (None, None, false) => Ok(MemoQueryStart::Head),
        (Some(cursor), None, false) => Ok(MemoQueryStart::After(cursor)),
        (Some(cursor), None, true) => Ok(MemoQueryStart::Before(cursor)),
        (None, Some(id), false) => Ok(MemoQueryStart::AtMemo(id)),
        _ => Err(boundary_err(
            "invalid_page_start",
            "page start must be exactly one of head, exclusive cursor, exclusive backward cursor, or memo identity",
        )),
    }
}

pub fn memo_page_to_ffi(page: store::MemoPage) -> StoreMemoPage {
    StoreMemoPage {
        items: page.items.into_iter().map(summary_to_ffi).collect(),
        next_cursor: page.next_cursor.map(|cursor| StorePageCursor {
            encoded: encode_cursor(&cursor),
        }),
        prev_cursor: page.prev_cursor.map(|cursor| StorePageCursor {
            encoded: encode_cursor(&cursor),
        }),
        items_before: page.items_before,
        items_after: page.items_after,
        high_water_revision: page.high_water_revision,
        query_fingerprint: page.query_fingerprint,
    }
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
            filters: memo_filters_from_ffi(query.filters),
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

pub fn memo_filters_from_ffi(filters: StoreMemoFilters) -> MemoFilters {
    MemoFilters {
        tag: filters.tag,
        tag_selection: if filters.tag_subtree {
            store::TagSelectionMode::Subtree
        } else {
            store::TagSelectionMode::Exact
        },
        date_from_inclusive_ms: filters.date_from_inclusive_ms,
        date_until_exclusive_ms: filters.date_until_exclusive_ms,
        has_todo: filters.has_todo,
        has_attachment: filters.has_attachment,
        has_url: filters.has_url,
        pinned_only: filters.pinned_only,
        include_trash: filters.include_trash,
        trash_only: filters.trash_only,
    }
}
