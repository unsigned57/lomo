use std::collections::{BTreeMap, BTreeSet};
use std::sync::Arc;

use lomo_core::{
    ActionId, DocumentKind, DocumentMetadata, LomoError, PageSize, PlatformAction,
    PlatformActionBatch, PlatformActionExecutor, PlatformActionOutput, RelativeWorkspacePath,
    WorkspaceTarget,
};
use lomo_store::{
    RebuildResult, SafProjectionRebuild, ScannedHistoryProjection, ScannedListingRow,
    ScannedMemoProjection, ScannedPinProjection, ScannedRowImage, ScannedTrashProjection, Store,
    aggregate_memo_digest, decode_purge_record,
};
use lomo_workspace::{
    MemoIdentityMap, ReminderReference, SourceBytes, WorkspaceRelativePath,
    canonical_attachment_keys, decode_trash_record, memo_identity_record_path,
    parse_workspace_document, trash_record_relative_path,
};

use lomo_workspace::layout::relative as lomo_rel;

use crate::{
    calendar::memo_chronology,
    config::WorkspaceSessionConfig,
    csprng::{generate_hex_token, mint_memo_id},
    error::{corruption, storage, validation},
    workspace_io::{FileSnapshot, MAX_WORKSPACE_READ_BATCH, WorkspaceIo, deadline},
};

/// Workspace facts already scanned for a projection rebuild or fingerprint reconcile.
pub(crate) struct ProjectionInventory {
    active_memos: Vec<ScannedMemoProjection>,
    trash_memos: Vec<ScannedTrashProjection>,
    history_revisions: Vec<ScannedHistoryProjection>,
    pins: Vec<ScannedPinProjection>,
    /// Identities a durable state head attested a pin verdict for — the materialize
    /// path reports them to the rebuild so a stale projection-cache pin for an
    /// attested identity can never outrank the durable verdict.
    pin_attested_ids: BTreeSet<String>,
    /// Every file row the scan committed: the provoking listing's fingerprints refreshed with
    /// the post-write tokens of files the scan itself wrote — the committed snapshot must
    /// describe the filesystem at scan completion, not at list time.
    listing_rows: Vec<ScannedListingRow>,
    /// Durable purge tombstone identities discovered with the trash scan.
    purged_ids: BTreeSet<String>,
}

/// The predicted committed `memo` row for one inventory identity.
struct PredictedMemoRow {
    /// The surviving lane's `source_path`; a dual-lane record must claim it unchanged.
    source_path: String,
    /// Canonical `file_fingerprint` the merge arm commits for this identity.
    fingerprint: String,
    /// The committed `is_trashed` bit — lifecycle membership is part of the row image.
    trashed: bool,
    /// Canonical attachment keys the committed `attachment_ref` rows carry.
    attachment_keys: Vec<String>,
}

impl ProjectionInventory {
    /// Builds the committed row image this inventory implies.
    ///
    /// A document block and a durable trash record naming one identity is a supported
    /// state: `merge_trash_projection` commits it as one trashed `memo` row — whose
    /// canonical fingerprint stays the surviving document's (or an already-committed
    /// sibling's) attestation — plus one `memo_trash` row attesting the record itself.
    /// This image applies exactly those rules, so the reconcile gate compares what the
    /// materialize path commits rather than a flatter abstraction that must reject or
    /// silently drop one lane.
    ///
    /// `Ok(None)` means the inventory names no certifiable image — a record claims a
    /// `source_path` that disagrees with its identity's surviving document row — and the
    /// caller must materialize so the merge surfaces `saf_trash_source_path_mismatch`.
    ///
    /// # Errors
    /// Fails on a duplicate active identity or a record whose attestation cannot digest.
    fn certification_image(&self) -> Result<Option<ScannedRowImage>, LomoError> {
        let mut rows: BTreeMap<String, PredictedMemoRow> = BTreeMap::new();
        let mut document_fingerprints: BTreeMap<String, String> = BTreeMap::new();
        for memo in &self.active_memos {
            document_fingerprints
                .entry(memo.source_path.clone())
                .or_insert_with(|| memo.file_fingerprint.clone());
            let row = PredictedMemoRow {
                source_path: memo.source_path.clone(),
                fingerprint: memo.file_fingerprint.clone(),
                trashed: memo.source_path.starts_with("trash/"),
                attachment_keys: canonical_attachment_keys(&memo.attachment_paths),
            };
            if rows.insert(memo.memo_id.clone(), row).is_some() {
                return Err(corruption(
                    "rebuild_compare_failed",
                    "a memo identity appears twice in the workspace scan",
                ));
            }
        }
        // A document-absent path's canonical fingerprint is its first record's claim:
        // `sibling_document_fingerprint` hands a later record the already-committed
        // sibling row's fingerprint, which is exactly that first claim.
        let mut claimed_fingerprints: BTreeMap<String, String> = BTreeMap::new();
        let mut trash_attestations = Vec::with_capacity(self.trash_memos.len());
        for trash in &self.trash_memos {
            trash_attestations.push((
                trash.memo.memo_id.clone(),
                trash.trashed_at_ms,
                trash.attestation_digest()?,
            ));
            if let Some(row) = rows.get_mut(&trash.memo.memo_id) {
                // Dual lane: the file attests content; the record owns the lane. The
                // merge arm validates the claim against the surviving row's path, so a
                // disagreeing record has no certifiable image — materialize reports it.
                if row.source_path != trash.memo.source_path {
                    return Ok(None);
                }
                row.trashed = true;
                row.attachment_keys = canonical_attachment_keys(&trash.memo.attachment_paths);
                continue;
            }
            let fingerprint = document_fingerprints
                .get(&trash.memo.source_path)
                .or_else(|| claimed_fingerprints.get(&trash.memo.source_path))
                .cloned()
                .unwrap_or_else(|| {
                    claimed_fingerprints
                        .entry(trash.memo.source_path.clone())
                        .or_insert_with(|| trash.memo.file_fingerprint.clone())
                        .clone()
                });
            rows.insert(
                trash.memo.memo_id.clone(),
                PredictedMemoRow {
                    source_path: trash.memo.source_path.clone(),
                    fingerprint,
                    trashed: true,
                    attachment_keys: canonical_attachment_keys(&trash.memo.attachment_paths),
                },
            );
        }
        let attachment_count = rows.values().try_fold(0_u64, |total, row| {
            let count = u64::try_from(row.attachment_keys.len()).map_err(|_error| {
                validation("attachment_count_overflow", "attachment count exceeds u64")
            })?;
            total.checked_add(count).ok_or_else(|| {
                validation("attachment_count_overflow", "attachment count exceeds u64")
            })
        })?;
        let memo_rows = rows
            .into_iter()
            .map(|(memo_id, row)| (memo_id, row.fingerprint, row.trashed))
            .collect();
        Ok(Some(ScannedRowImage {
            memo_rows,
            trash_attestations,
            attachment_count,
        }))
    }

    /// The `file_listing` rows this inventory commits — provoking-listing fingerprints
    /// already refreshed with the post-write tokens of files the scan itself wrote.
    pub(crate) fn listing_rows(&self) -> &[ScannedListingRow] {
        &self.listing_rows
    }

    /// Returns a non-rewriting rebuild result when the live projection already matches this scan.
    ///
    /// The gate compares the predicted committed row image — lane-aware `memo` rows and
    /// `memo_trash` attestations — so a record can neither certify a live row's state nor
    /// hide a rewrite behind a stable claimed fingerprint.
    pub(crate) fn try_reconcile(&self, store: &Store) -> Result<Option<RebuildResult>, LomoError> {
        let Some(mut image) = self.certification_image()? else {
            return Ok(None);
        };
        store.reconcile_scanned_projection(
            &mut image,
            &self.pins,
            &self.history_revisions,
            &self.purged_ids,
        )
    }
}

/// One workspace enumeration used to admit a projection reconcile or skip.
///
/// Incomplete pages never become `Complete` with an empty listing. `ContentDigest::Unknown`
/// means the platform did not hash bytes. A verified SHA-256 of an empty file is a real digest.
#[derive(Clone, Debug)]
pub(crate) struct ScanEvidence {
    enumeration: ScanEnumeration,
    listing: Vec<DocumentMetadata>,
    skipped_sync_subtrees: u64,
}

#[derive(Clone, Debug)]
enum ScanEnumeration {
    Complete,
    Incomplete(LomoError),
}

impl ScanEvidence {
    const fn complete(listing: Vec<DocumentMetadata>, skipped_sync_subtrees: u64) -> Self {
        Self {
            enumeration: ScanEnumeration::Complete,
            listing,
            skipped_sync_subtrees,
        }
    }

    const fn incomplete(
        error: LomoError,
        listing: Vec<DocumentMetadata>,
        skipped_sync_subtrees: u64,
    ) -> Self {
        Self {
            enumeration: ScanEnumeration::Incomplete(error),
            listing,
            skipped_sync_subtrees,
        }
    }

    /// `.lomo/sync` subtrees the enumeration excluded — the sync carrier is never
    /// reconciled as workspace content.
    pub(crate) const fn skipped_sync_subtrees(&self) -> u64 {
        self.skipped_sync_subtrees
    }

    /// Listing admitted for reconcile, materialize, or digest persist.
    ///
    /// # Errors
    /// Incomplete enumeration cannot certify an empty or partial directory.
    pub(crate) fn admitted_listing(&self) -> Result<&[DocumentMetadata], LomoError> {
        match &self.enumeration {
            ScanEnumeration::Complete => Ok(&self.listing),
            ScanEnumeration::Incomplete(error) => Err(error.clone()),
        }
    }

    /// Listing-evidence digest of a complete enumeration, or `None` when it is incomplete.
    ///
    /// The digest covers each file's listing fingerprint — a verified content digest on
    /// hashing platforms or a metadata change token on stat-only listings — so it is cheap
    /// on every platform while still flipping on any content-bearing change.
    #[must_use]
    pub(crate) fn listing_digest(&self) -> Option<String> {
        match &self.enumeration {
            ScanEnumeration::Incomplete(_) => None,
            ScanEnumeration::Complete => Some(listing_evidence_digest(&self.listing)),
        }
    }
}

/// Rebuilds the entire SQLite query projection from Markdown and `.lomo` physical facts.
///
/// # Errors
/// Returns `Storage` or `Corruption` error if scanning or SQLite indexing fails.
pub fn rebuild_projection(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
) -> Result<RebuildResult, LomoError> {
    materialize_scanned_projection(config, &scan_projection_inventory(config, executor)?)
}

/// Lists workspace files with platform evidence. Content digests are present on Direct listings.
///
/// # Errors
/// Propagates listing I/O and protocol failures that are not an incomplete page.
pub(crate) fn list_workspace_listing(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
) -> Result<ScanEvidence, LomoError> {
    list_recursive(config, executor, ListingScope::WorkspaceFacts)
}

/// The (path, listing fingerprint) map one enumeration produces — the same view
/// `file_listing` rows, the workspace-listing digest, and the incremental differ all compare.
pub(crate) fn listing_token_map(listing: &[DocumentMetadata]) -> BTreeMap<String, String> {
    let mut map = BTreeMap::new();
    for item in listing {
        if item.kind() != DocumentKind::File {
            continue;
        }
        let WorkspaceTarget::Relative(path) = item.target() else {
            continue;
        };
        map.insert(
            path.as_str().to_owned(),
            item.evidence().fingerprint().to_owned(),
        );
    }
    map
}

/// Aggregate digest over a (path, listing fingerprint) token map. `BTreeMap` iteration order
/// is the sorted order the store's committed-row digest recomputes, so this value is exactly
/// the `workspace_listing_digest` a commit of these rows produces.
pub(crate) fn listing_token_digest(tokens: &BTreeMap<String, String>) -> String {
    let pairs: Vec<(String, String)> = tokens
        .iter()
        .map(|(path, digest)| (path.clone(), digest.clone()))
        .collect();
    aggregate_memo_digest(&pairs)
}

/// Aggregate digest over every listed file's evidence fingerprint.
///
/// `file_listing` rows and the `workspace_listing_digest` meta value share this function, so
/// a snapshot written by a full materialize, an incremental apply, or a reconcile persist can
/// never disagree about what "the same listing" means.
#[must_use]
pub(crate) fn listing_evidence_digest(listing: &[DocumentMetadata]) -> String {
    listing_token_digest(&listing_token_map(listing))
}

fn listing_parent(path: &str) -> Result<WorkspaceTarget, LomoError> {
    path.rsplit_once('/')
        .map_or(Ok(WorkspaceTarget::Root), |(parent, _)| {
            RelativeWorkspacePath::parse(parent).map(WorkspaceTarget::Relative)
        })
}

fn update_written_token(
    path: &RelativeWorkspacePath,
    metadata: &DocumentMetadata,
    tokens: &mut BTreeMap<String, String>,
) -> Result<(), LomoError> {
    if metadata.kind() != DocumentKind::File {
        return Err(corruption(
            "written_target_not_file",
            "written target is no longer a file",
        ));
    }
    tokens.insert(
        path.as_str().to_owned(),
        metadata.evidence().fingerprint().to_owned(),
    );
    Ok(())
}

fn known_siblings_up_to(
    target: &WorkspaceTarget,
    tokens: &BTreeMap<String, String>,
    limit: usize,
) -> usize {
    match target {
        WorkspaceTarget::Root => tokens
            .keys()
            .filter(|path| !path.contains('/'))
            .take(limit)
            .count(),
        WorkspaceTarget::Relative(parent) => {
            let prefix = format!("{}/", parent.as_str());
            tokens
                .range(prefix.clone()..)
                .take_while(|(path, _)| path.starts_with(&prefix))
                .filter(|(path, _)| {
                    path.rsplit_once('/')
                        .is_some_and(|(directory, _)| directory == parent.as_str())
                })
                .take(limit)
                .count()
        }
    }
}

/// Refreshes written-file tokens without amplifying a small change into sibling enumeration.
/// One read-lane batch uses exact metadata observations. A larger write set uses bulk listing
/// only when it is at least as large as the parent's known membership, bounding normal bulk
/// metadata to at most old members plus writes rather than the size of an unchanged library.
/// Content CAS evidence remains independent and is never weakened to a listing change token.
///
/// # Errors
/// A written target that vanished or ceased to be a file cannot certify this projection.
pub(crate) fn refresh_listing_tokens(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
    written: &[RelativeWorkspacePath],
    inventory: &BTreeMap<String, String>,
) -> Result<BTreeMap<String, String>, LomoError> {
    let mut parents: BTreeMap<WorkspaceTarget, BTreeSet<RelativeWorkspacePath>> = BTreeMap::new();
    for path in written {
        let rel = path.as_str();
        if rel == lomo_workspace::GIT_ROOT_NAME
            || rel.starts_with(".git/")
            || rel == lomo_rel::LOCAL
            || rel.starts_with(lomo_rel::LOCAL_PREFIX)
            || rel == lomo_rel::SYNC
            || rel.starts_with(lomo_rel::SYNC_PREFIX)
        {
            continue;
        }
        parents
            .entry(listing_parent(rel)?)
            .or_default()
            .insert(path.clone());
    }
    let mut exact = Vec::new();
    let mut bulk = Vec::new();
    for (target, paths) in parents {
        if paths.len() <= MAX_WORKSPACE_READ_BATCH
            || known_siblings_up_to(&target, inventory, paths.len() + 1) > paths.len()
        {
            exact.extend(paths);
        } else {
            bulk.push((target, paths));
        }
    }
    let io = WorkspaceIo::physical(config, executor);
    let mut refreshed = BTreeMap::new();
    for chunk in exact.chunks(MAX_WORKSPACE_READ_BATCH) {
        for (path, metadata) in chunk.iter().zip(io.inspect_batch(chunk)?) {
            update_written_token(path, &metadata, &mut refreshed)?;
        }
    }
    let targets: Vec<_> = bulk.iter().map(|(target, _)| target.clone()).collect();
    for ((_, paths), page) in bulk
        .into_iter()
        .zip(list_targets_pages(config, executor, &targets)?)
    {
        let items = match page {
            ListingPage::Items(items) => items,
            ListingPage::Rejected(error) => return Err(error),
        };
        let mut listed = BTreeMap::new();
        for item in &items {
            let WorkspaceTarget::Relative(path) = item.target() else {
                return Err(corruption(
                    "invalid_child_listing",
                    "a directory listing returned its root",
                ));
            };
            listed.insert(path.as_str(), item);
        }
        for path in paths {
            let metadata = listed.get(path.as_str()).ok_or_else(|| {
                storage(
                    "written_target_missing",
                    format!("written file disappeared: {}", path.as_str()),
                )
            })?;
            update_written_token(&path, metadata, &mut refreshed)?;
        }
    }
    Ok(refreshed)
}

/// Scans workspace Markdown and `.lomo` facts without replacing SQLite.
pub(crate) fn scan_projection_inventory(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
) -> Result<ProjectionInventory, LomoError> {
    let evidence = list_recursive(config, executor, ListingScope::WorkspaceFacts)?;
    let listing = evidence.admitted_listing()?;
    scan_projection_inventory_from_listing(config, executor, listing)
}

pub(crate) fn scan_projection_inventory_from_listing(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
    listing: &[DocumentMetadata],
) -> Result<ProjectionInventory, LomoError> {
    let all_files = listing_file_paths(listing);
    let mut listing_tokens = listing_token_map(listing);

    let mut markdown_files = Vec::new();
    let mut history_files = Vec::new();
    let mut state_files = Vec::new();
    let mut trash_files = Vec::new();
    let mut purge_files = Vec::new();

    let mut written_paths = Vec::new();
    for path in all_files {
        let path_str = path.as_str();
        if path_str.starts_with(lomo_rel::TRASH_PREFIX) && has_extension(path_str, "md") {
            trash_files.push(path);
        } else if has_extension(path_str, "md") && !path_str.starts_with(lomo_rel::ROOT_PREFIX) {
            markdown_files.push(path);
        } else if path_str.starts_with(lomo_rel::HISTORY_PREFIX) && has_extension(path_str, "rec") {
            history_files.push(path);
        } else if path_str.starts_with(lomo_rel::STATE_PREFIX) && has_extension(path_str, "rec") {
            state_files.push(path);
        } else if path_str.starts_with(lomo_rel::PURGED_PREFIX) && has_extension(path_str, "rec") {
            purge_files.push(path);
        }
    }

    let documents = scan_markdown_files(
        config,
        executor,
        &markdown_files,
        &listing_tokens,
        &mut history_files,
        &mut Vec::new(),
        &mut written_paths,
    )?;
    history_files.sort_by(|left, right| left.as_str().cmp(right.as_str()));
    history_files.dedup();
    let purged_ids = scan_purged_ids(config, executor, &purge_files)?;
    let trash_memos = scan_trash_files(config, executor, &trash_files, &purged_ids)?;
    let history_revisions = crate::rebuild_records::history(
        &WorkspaceIo::physical(config, executor),
        &history_files,
        documents.history_tips,
    )?;
    let active_memos = documents.memos;
    // Pin facts attach only to identities this scan projects a row for — document
    // emissions plus trash claims that survived purge suppression. Anything else is a
    // dead tip the projection can never carry.
    let live_ids: BTreeSet<String> = active_memos
        .iter()
        .map(|memo| memo.memo_id.clone())
        .chain(trash_memos.iter().map(|trash| trash.memo.memo_id.clone()))
        .collect();
    let pin_scan = crate::rebuild_records::pins(
        &WorkspaceIo::physical(config, executor),
        &state_files,
        &live_ids,
    )?;
    // The scan may have written identity records or initial history chains while it ran; the
    // committed listing rows must describe the filesystem at scan completion, so their tokens
    // come from a parent-directory re-list, not from the pre-scan listing.
    let refreshed = refresh_listing_tokens(config, executor, &written_paths, &listing_tokens)?;
    listing_tokens.extend(refreshed);
    // Absorbed duplicate heads stay tolerated evidence — never baseline rows. A committed
    // copy would drop out of the path diff and let a claim gone stale stay invisible to
    // every later reconcile, so they are re-verified on every pass instead.
    for path in &pin_scan.absorbed_head_paths {
        listing_tokens.remove(path.as_str());
    }
    let listing_rows = listing_tokens
        .into_iter()
        .map(|(path, digest)| ScannedListingRow { path, digest })
        .collect();
    Ok(ProjectionInventory {
        active_memos,
        trash_memos,
        history_revisions,
        pins: pin_scan.pins,
        pin_attested_ids: pin_scan.attested_ids,
        listing_rows,
        purged_ids,
    })
}

/// Replaces the live projection from an already-scanned inventory.
pub(crate) fn materialize_scanned_projection(
    config: &WorkspaceSessionConfig,
    inventory: &ProjectionInventory,
) -> Result<RebuildResult, LomoError> {
    let mut rebuild = SafProjectionRebuild::begin(&config.cache_dir)?;
    for chunk in inventory.active_memos.chunks(256) {
        rebuild.append_page(chunk)?;
    }
    for chunk in inventory.trash_memos.chunks(256) {
        rebuild.append_trash_page(chunk)?;
    }
    for chunk in inventory.history_revisions.chunks(256) {
        rebuild.append_history_page(chunk)?;
    }
    for chunk in inventory.pins.chunks(256) {
        rebuild.append_pin_page(chunk)?;
    }
    rebuild.append_pin_attestations(&inventory.pin_attested_ids)?;
    let purged_ids: Vec<String> = inventory.purged_ids.iter().cloned().collect();
    for chunk in purged_ids.chunks(256) {
        rebuild.append_purged_page(chunk)?;
    }
    for chunk in inventory.listing_rows.chunks(256) {
        rebuild.append_listing_page(chunk)?;
    }

    rebuild.finish()
}

/// Parsed documents and the immutable history facts their scan already admitted.
pub(crate) struct ScannedDocuments {
    pub memos: Vec<ScannedMemoProjection>,
    /// Canonical tip objects already admitted while ensuring each memo has history.
    pub history_tips: Vec<lomo_workspace::HistoryRevisionV2>,
}

/// Parses one batch of documents and reconciles their identity records.
///
/// `history_files` accumulates history record paths the scan must still index (initial-history
/// writes made here are pushed on by value). `initial_history` collects the complete projection
/// row for every memo that received a fresh initial history chain — a head-absent memo's chain
/// is exactly that one revision — so a path-scoped caller can project it without re-reading the
/// files it just wrote. `written_paths` collects every file the scan itself wrote — identity
/// maps and initial history — so the caller can refresh their listing tokens after the listing
/// snapshot that provoked this scan was taken.
pub(crate) fn scan_markdown_files(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
    markdown_files: &[RelativeWorkspacePath],
    listed_files: &BTreeMap<String, String>,
    history_files: &mut Vec<RelativeWorkspacePath>,
    initial_history: &mut Vec<ScannedHistoryProjection>,
    written_paths: &mut Vec<RelativeWorkspacePath>,
) -> Result<ScannedDocuments, LomoError> {
    let io = WorkspaceIo::physical(config, executor);
    let mut documents = ScannedDocuments {
        memos: Vec::new(),
        history_tips: Vec::new(),
    };
    if !markdown_files.is_empty() {
        prepare_scan_directories(&io)?;
    }
    for chunk in markdown_files.chunks(MAX_WORKSPACE_READ_BATCH) {
        let snapshots = io.require_batch(chunk)?;
        // Share one eight-lane budget between documents and memo chains. A large daily
        // document uses the spare lanes instead of serialising every initial-history write.
        let memo_lanes = MAX_WORKSPACE_READ_BATCH / chunk.len();
        let scanned = scan_batch(chunk.iter().zip(snapshots).collect(), |(path, snapshot)| {
            scan_document(&io, path, snapshot, listed_files, memo_lanes)
        })?;
        for scanned in scanned {
            documents.memos.extend(scanned.memos);
            documents.history_tips.extend(scanned.history_tips);
            history_files.extend(scanned.history_files);
            initial_history.extend(scanned.initial_history);
            written_paths.extend(scanned.written_paths);
        }
    }
    Ok(documents)
}

/// Join every bounded worker, including after failure, before admitting any batch facts.
fn scan_batch<T: Send, R: Send>(
    items: Vec<T>,
    scan: impl Fn(T) -> Result<R, LomoError> + Sync,
) -> Result<Vec<R>, LomoError> {
    if items.len() <= 1 {
        return items.into_iter().map(scan).collect();
    }
    std::thread::scope(|scope| {
        let scan = &scan;
        let workers: Vec<_> = items
            .into_iter()
            .map(|item| std::thread::Builder::new().spawn_scoped(scope, move || scan(item)))
            .collect();
        workers
            .into_iter()
            .map(|worker| match worker {
                Ok(worker) => worker.join().map_err(|panic| {
                    storage("document_scan_panicked", format!("scan worker: {panic:?}"))
                })?,
                Err(error) => Err(storage("document_scan_spawn_failed", error.to_string())),
            })
            .collect::<Vec<_>>()
    })
    .into_iter()
    .collect()
}

/// Shared parents must exist before independent writers can create children: SAF directory
/// creation is not an atomic create-if-absent operation and may otherwise rename a duplicate.
fn prepare_scan_directories(io: &WorkspaceIo<'_>) -> Result<(), LomoError> {
    for prefix in [
        lomo_rel::IDENTITY_V1_PREFIX,
        lomo_rel::HISTORY_V2_OBJECTS_PREFIX,
        lomo_rel::HISTORY_V2_HEADS_PREFIX,
    ] {
        let action = PlatformAction::ensure_directory(
            ActionId::parse(&format!("scan-mkdir-{}", generate_hex_token(16)?))?,
            io.config.capability.clone(),
            RelativeWorkspacePath::parse(prefix.trim_end_matches('/'))?,
        );
        let PlatformActionOutput::DirectoryReady { .. } = io.execute(action)? else {
            return Err(corruption(
                "invalid_ensure_directory_output",
                "expected a directory receipt",
            ));
        };
    }
    Ok(())
}

#[derive(Default)]
struct ScannedDocument {
    memos: Vec<ScannedMemoProjection>,
    history_tips: Vec<lomo_workspace::HistoryRevisionV2>,
    history_files: Vec<RelativeWorkspacePath>,
    initial_history: Vec<ScannedHistoryProjection>,
    written_paths: Vec<RelativeWorkspacePath>,
}

fn scan_document(
    io: &WorkspaceIo<'_>,
    md_path: &RelativeWorkspacePath,
    snapshot: FileSnapshot,
    listed_files: &BTreeMap<String, String>,
    memo_lanes: usize,
) -> Result<ScannedDocument, LomoError> {
    let config = io.config;
    let mut scanned = ScannedDocument::default();
    let source = SourceBytes::try_from_bytes(snapshot.bytes)?;
    let filename = md_path
        .as_str()
        .rsplit('/')
        .next()
        .ok_or_else(|| validation("invalid_workspace_path", "document needs a filename"))?;
    let stem = filename.strip_suffix(".md").unwrap_or(filename);
    let document = parse_workspace_document(&source, stem)?;
    let chronologies = document
        .memos()
        .iter()
        .map(|memo| memo_chronology(stem, memo.time_part(), &config.time_zone))
        .collect::<Result<Vec<_>, _>>()?;
    let path = WorkspaceRelativePath::parse(md_path.as_str())?;
    let identity_map = reconcile_identity(
        io,
        &path,
        &document,
        &mut scanned.written_paths,
        listed_files,
    )?;
    let existing_ids = identity_map
        .bindings()
        .iter()
        .map(|binding| {
            let id = binding.memo_id();
            let head_path = crate::record_plan::history_head_relative_path(id)?;
            Ok(listed_files.contains_key(head_path.as_str()).then_some(id))
        })
        .collect::<Result<Vec<_>, LomoError>>()?
        .into_iter()
        .flatten()
        .collect::<Vec<_>>();
    scanned.history_tips.extend(
        crate::record_plan::history_tips(io, &existing_ids)?
            .into_iter()
            .map(|tip| tip.revision),
    );
    let existing_ids: BTreeSet<_> = existing_ids.into_iter().collect();
    for chunk in identity_map.bindings().chunks(memo_lanes) {
        let memo_scans = scan_batch(chunk.iter().collect(), |binding| {
            let mut memo_scan = ScannedDocument::default();
            let memo = binding
                .locator()
                .resolve(config.root_id, &path, &document)?;
            let chronology = *chronologies
                .get(binding.locator().block_index() as usize)
                .ok_or_else(|| {
                    corruption(
                        "memo_chronology_missing",
                        "validated block chronology is absent",
                    )
                })?;
            if !existing_ids.contains(&binding.memo_id()) {
                let history_before = memo_scan.history_files.len();
                let projection = create_initial_history(
                    io,
                    binding.memo_id(),
                    memo,
                    chronology,
                    &mut memo_scan.history_files,
                    &mut memo_scan.history_tips,
                )?;
                memo_scan.initial_history.push(projection);
                memo_scan
                    .written_paths
                    .extend(memo_scan.history_files.iter().skip(history_before).cloned());
            }
            memo_scan.memos.push(ScannedMemoProjection {
                memo_id: binding.memo_id().as_str().to_owned(),
                source_path: md_path.as_str().to_owned(),
                file_fingerprint: source.fingerprint().as_str().to_owned(),
                chronology_epoch_ms: chronology,
                body: memo.content().to_owned(),
                tags: memo.tags().to_vec(),
                attachment_paths: memo.attachments().to_vec(),
                has_todo: memo.has_todo(),
                has_url: memo.has_url(),
                reminders: memo
                    .reminders()
                    .iter()
                    .map(ReminderReference::from)
                    .collect(),
            });
            Ok(memo_scan)
        })?;
        for memo_scan in memo_scans {
            scanned.memos.extend(memo_scan.memos);
            scanned.history_tips.extend(memo_scan.history_tips);
            scanned.history_files.extend(memo_scan.history_files);
            scanned.initial_history.extend(memo_scan.initial_history);
            scanned.written_paths.extend(memo_scan.written_paths);
        }
    }
    Ok(scanned)
}

/// Writes initial durable history for one memo when no head exists yet.
///
/// Returns the projection row for the revision just written — the memo's complete durable chain,
/// since a missing head means no earlier history exists — so a path-scoped caller can use it
/// directly as the memo's `history_replaces` fact without re-walking what it just wrote.
fn create_initial_history(
    io: &WorkspaceIo<'_>,
    id: &lomo_workspace::MemoId,
    memo: &lomo_workspace::WorkspaceMemo,
    chronology: i64,
    inventory: &mut Vec<RelativeWorkspacePath>,
    tips: &mut Vec<lomo_workspace::HistoryRevisionV2>,
) -> Result<ScannedHistoryProjection, LomoError> {
    let prepared = crate::record_plan::history_files_after_tip(
        &lomo_workspace::HistorySnapshotV1 {
            memo_id: id.as_str().to_owned(),
            revision: 1,
            content: memo.content().to_owned(),
            file_fingerprint: lomo_workspace::SourceFingerprint::of_bytes(
                memo.content().as_bytes(),
            )
            .as_str()
            .to_owned(),
            created_at_ms: chronology,
        },
        None,
    )?;
    let projection = prepared.projection;
    for file in prepared.files {
        let current = file.observe(io)?;
        file.apply(io, &current)?;
        inventory.push(file.path().clone());
    }
    tips.push(prepared.revision);
    Ok(projection)
}

fn reconcile_identity(
    io: &WorkspaceIo<'_>,
    path: &WorkspaceRelativePath,
    document: &lomo_workspace::WorkspaceDocument,
    written_paths: &mut Vec<RelativeWorkspacePath>,
    listed_files: &BTreeMap<String, String>,
) -> Result<MemoIdentityMap, LomoError> {
    let record_path = memo_identity_record_path(io.config.root_id, path)?;
    let record_path = RelativeWorkspacePath::parse(record_path.as_str())?;
    // Enumeration decides which records must be read. An unlisted slot is create-only:
    // a concurrent claimant must survive the platform's absence/name arbitration untouched.
    let before = if listed_files.contains_key(record_path.as_str()) {
        Some(io.require(&record_path)?)
    } else {
        None
    };
    let operation = lomo_core::OperationId::parse(&format!("scan-{}", generate_hex_token(16)?))?;
    let mut map = if let Some(snapshot) = &before {
        MemoIdentityMap::decode(&snapshot.bytes)?.reconcile_external(operation, document)?
    } else {
        let ids = document
            .memos()
            .iter()
            .map(|_| mint_memo_id())
            .collect::<Result<Vec<_>, _>>()?;
        MemoIdentityMap::initialize(operation, io.config.root_id, path.clone(), document, ids)?
    };
    if map.conflicts().is_empty() && !map.unbound().is_empty() {
        let ids = map
            .unbound()
            .iter()
            .map(|_| mint_memo_id())
            .collect::<Result<Vec<_>, _>>()?;
        map = map.assign_discovered(
            lomo_core::OperationId::parse(&format!("discover-{}", generate_hex_token(16)?))?,
            document,
            ids,
        )?;
    }
    let after = map.encode()?;
    if before
        .as_ref()
        .is_none_or(|snapshot| snapshot.bytes != after)
    {
        io.write(&record_path, before.as_ref(), &after)?;
        written_paths.push(record_path);
    }
    if !map.conflicts().is_empty() {
        return Err(crate::error::conflict(
            "memo_identity_unresolved",
            "external document changes have ambiguous identities; evidence is preserved in .lomo",
        ));
    }
    Ok(map)
}

/// Decodes every durable purge tombstone and returns the suppressed memo identities.
///
/// A tombstone must sit at its canonical source-labelled identity path — the same name authority the
/// path-scoped reconcile relies on when a tombstone row is added or removed — and a corrupt
/// tombstone fails the scan rather than silently dropping suppression.
fn scan_purged_ids(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
    purge_files: &[RelativeWorkspacePath],
) -> Result<BTreeSet<String>, LomoError> {
    let mut purged_ids = BTreeSet::new();
    for purge_path in purge_files {
        let bytes = read_workspace_file(config, executor, purge_path)?;
        let tombstone = decode_purge_record(&bytes)?;
        let expected = lomo_store::purge_record_relative_path(&tombstone.memo_id)?;
        if purge_path.as_str() != expected.as_str() {
            return Err(corruption(
                "purge_record_path_mismatch",
                "durable purge tombstone is not stored at its canonical source-labelled path",
            ));
        }
        purged_ids.insert(tombstone.memo_id);
    }
    Ok(purged_ids)
}

/// Decodes one trash record and proves the file sits at its canonical source-labelled identity path.
///
/// Shared by the full scan and the path-scoped reconcile so both admit exactly the same
/// durable facts. `ScannedTrashProjection::from_record` is the single record→projection
/// construction: attachment evidence derives from the recoverable body — the declared
/// `attachments` payload is never proof — and a body that cannot render fails closed.
pub(crate) fn decode_trash_projection(
    bytes: &[u8],
    trash_path: &str,
) -> Result<ScannedTrashProjection, LomoError> {
    let trash = decode_trash_record(bytes)?;
    let expected = trash_record_relative_path(&trash.memo_id, &trash.source_path)?;
    if trash_path != expected.as_str() {
        return Err(corruption(
            "trash_record_path_mismatch",
            "durable trash record is not stored at its canonical source-labelled path",
        ));
    }
    ScannedTrashProjection::from_record(&trash)
}

fn scan_trash_files(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
    trash_files: &[RelativeWorkspacePath],
    purged_ids: &BTreeSet<String>,
) -> Result<Vec<ScannedTrashProjection>, LomoError> {
    // Durable purge tombstones suppress resurrection: a permanently deleted memo id must never
    // re-project from a stray, rewritten, or peer-redelivered trash record.
    let mut trash_memos = Vec::new();
    for trash_path in trash_files {
        let bytes = read_workspace_file(config, executor, trash_path)?;
        let trash = decode_trash_projection(&bytes, trash_path.as_str())?;
        if purged_ids.contains(&trash.memo.memo_id) {
            continue;
        }
        trash_memos.push(trash);
    }
    Ok(trash_memos)
}

fn listing_file_paths(listing: &[DocumentMetadata]) -> Vec<RelativeWorkspacePath> {
    listing
        .iter()
        .filter_map(|item| {
            if item.kind() != DocumentKind::File {
                return None;
            }
            match item.target() {
                WorkspaceTarget::Relative(path) => Some(path.clone()),
                WorkspaceTarget::Root => None,
            }
        })
        .collect()
}

/// Page size for workspace scans — the largest page the platform contract admits, so a directory
/// re-queried per page (SAF) enumerates in one round trip where smaller pages would chain.
const WORKSPACE_SCAN_PAGE_SIZE: u32 = 512;

/// Independent listing chains the engine joins into one platform batch — the same bound the
/// engine's parallel read lane enforces, so a wider batch would only queue serially while a
/// narrower one wastes provider round trips.
const LISTING_BATCH_WIDTH: usize = 8;

/// One fully-paged listing for a single target.
pub(crate) enum ListingPage {
    /// Every item under the target.
    Items(Vec<DocumentMetadata>),
    /// The platform rejected the listing mid-enumeration; partial rows are not evidence.
    Rejected(LomoError),
}

/// One open cursor chain over a listing target. A chain only ever continues through the cursor
/// the provider issued for its previous page — never a guessed offset — so chains over
/// independent directories can share a batch without breaking provider cursor semantics.
struct ListingChain {
    /// Caller-chosen correlation returned with the settled page (`list_targets_pages` uses the
    /// target index; the recursive walk does not correlate).
    tag: usize,
    target: WorkspaceTarget,
    cursor: Option<String>,
    items: Vec<DocumentMetadata>,
}

impl ListingChain {
    const fn new(tag: usize, target: WorkspaceTarget) -> Self {
        Self {
            tag,
            target,
            cursor: None,
            items: Vec::new(),
        }
    }

    fn next_action(&self, config: &WorkspaceSessionConfig) -> Result<PlatformAction, LomoError> {
        let action_id = ActionId::parse(&format!("list-{}", generate_hex_token(8)?))?;
        let page_size = PageSize::new(WORKSPACE_SCAN_PAGE_SIZE)?;
        Ok(match &self.target {
            WorkspaceTarget::Root => PlatformAction::list_root(
                action_id,
                config.capability.clone(),
                self.cursor.clone(),
                page_size,
            ),
            WorkspaceTarget::Relative(rel) => PlatformAction::list_children(
                action_id,
                config.capability.clone(),
                rel.clone(),
                self.cursor.clone(),
                page_size,
            ),
        })
    }
}

/// Executes one platform batch that advances every open `chains` entry by one listing page.
///
/// Settled chains — cursor proven complete, action rejected, or page slot unwitnessed by a
/// truncated result prefix — are returned as `(tag, ListingPage)` pairs in `chains` order; the
/// rest stay open in `chains` for the next round. Per-page results stay aligned with the chain
/// that requested them, so parallel chains can never cross-attribute rows.
fn page_round(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
    chains: &mut Vec<ListingChain>,
) -> Result<Vec<(usize, ListingPage)>, LomoError> {
    debug_assert!(!chains.is_empty());
    let actions = chains
        .iter()
        .map(|chain| chain.next_action(config))
        .collect::<Result<Vec<_>, _>>()?;
    let job_id = lomo_core::JobId::parse(&format!("job-list-{}", generate_hex_token(8)?))?;
    let batch_id = lomo_core::BatchId::parse(&format!("batch-list-{}", generate_hex_token(8)?))?;
    let batch = PlatformActionBatch::new(job_id, batch_id, 1, deadline()?, actions)?;
    let result = executor.execute(&batch)?;
    result.validate_against(&batch)?;
    let results = result.action_results();

    let mut settled = Vec::new();
    let mut kept = Vec::with_capacity(chains.len());
    for (index, mut chain) in chains.drain(..).enumerate() {
        match results.get(index).map(lomo_core::ActionResult::outcome) {
            Some(
                lomo_core::ActionOutcome::Applied(PlatformActionOutput::Listed { page })
                | lomo_core::ActionOutcome::AlreadySatisfied(PlatformActionOutput::Listed { page }),
            ) => {
                chain.items.extend(page.items().iter().cloned());
                chain.cursor = page.next_cursor().map(|c| c.as_str().to_owned());
                if chain.cursor.is_some() {
                    kept.push(chain);
                } else {
                    settled.push((chain.tag, ListingPage::Items(chain.items)));
                }
            }
            Some(lomo_core::ActionOutcome::Failed(error)) => {
                settled.push((chain.tag, ListingPage::Rejected(error.clone())));
            }
            Some(
                lomo_core::ActionOutcome::Applied(_)
                | lomo_core::ActionOutcome::AlreadySatisfied(_),
            ) => {
                return Err(corruption("invalid_list_output", "expected Listed output"));
            }
            None => {
                settled.push((
                    chain.tag,
                    ListingPage::Rejected(storage(
                        "list_batch_truncated",
                        "provider ended the listing batch without a result for every requested page",
                    )),
                ));
            }
        }
    }
    *chains = kept;
    Ok(settled)
}

/// Pages every `targets` chain to completion, batching independent cursor chains up to
/// [`LISTING_BATCH_WIDTH`] wide. The returned vector preserves `targets` order; each entry is the
/// terminal page for that target only — a rejected chain never contaminates its neighbors.
fn list_targets_pages(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
    targets: &[WorkspaceTarget],
) -> Result<Vec<ListingPage>, LomoError> {
    let mut settled: Vec<Option<ListingPage>> = targets.iter().map(|_| None).collect();
    let mut seeds: Vec<usize> = (0..targets.len()).collect();
    let mut chains: Vec<ListingChain> = Vec::new();
    loop {
        while chains.len() < LISTING_BATCH_WIDTH {
            let Some(index) = seeds.pop() else {
                break;
            };
            let Some(target) = targets.get(index) else {
                break;
            };
            chains.push(ListingChain::new(index, target.clone()));
        }
        if chains.is_empty() {
            break;
        }
        for (tag, page) in page_round(config, executor, &mut chains)? {
            if let Some(slot) = settled.get_mut(tag) {
                *slot = Some(page);
            }
        }
    }
    Ok(settled
        .into_iter()
        .map(|page| {
            page.unwrap_or_else(|| {
                ListingPage::Rejected(storage(
                    "list_chain_unsettled",
                    "listing chain ended without a settled page",
                ))
            })
        })
        .collect())
}

/// Pages one listing target to completion.
///
/// A `Failed` action outcome becomes [`ListingPage::Rejected`] so the workspace-wide walk can
/// degrade to incomplete evidence while callers that need a certified single directory treat it
/// as an error.
pub(crate) fn list_target_pages(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
    target: &WorkspaceTarget,
) -> Result<ListingPage, LomoError> {
    Ok(
        list_targets_pages(config, executor, std::slice::from_ref(target))?
            .into_iter()
            .next()
            .unwrap_or_else(|| {
                ListingPage::Rejected(storage(
                    "list_empty_result",
                    "missing action result for list",
                ))
            }),
    )
}

/// Admits one entry of a completed directory listing: files join the workspace listing,
/// directories queue for their own listing chains, and carrier subtrees (`.git`,
/// `.lomo/local`, `.lomo/sync`) never enter the walk.
fn admit_listing_item(
    item: &DocumentMetadata,
    files: &mut Vec<DocumentMetadata>,
    pending: &mut Vec<WorkspaceTarget>,
    skipped_sync_subtrees: &mut u64,
) {
    if let WorkspaceTarget::Relative(path) = item.target() {
        match path.as_str() {
            lomo_workspace::GIT_ROOT_NAME
            | lomo_rel::ROOT
            | lomo_rel::LOCAL
            | lomo_media::STAGE_DIR_NAME
            | lomo_media::MEDIA_TRASH_DIR_NAME
            | lomo_media::MEDIA_DELETE_INTENT_DIR_NAME => return,
            // The sync control tree is a carrier, not workspace content: it never enters the
            // admitted listing and its churn can never wake a reconcile.
            lomo_rel::SYNC => {
                *skipped_sync_subtrees += 1;
                return;
            }
            _ => {}
        }
    }
    match item.kind() {
        DocumentKind::File => files.push(item.clone()),
        DocumentKind::Directory => pending.push(item.target().clone()),
    }
}

#[derive(Clone, Copy)]
enum ListingScope {
    Documents,
    WorkspaceFacts,
}

pub(crate) fn list_document_listing(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
) -> Result<ScanEvidence, LomoError> {
    list_recursive(config, executor, ListingScope::Documents)
}

fn list_recursive(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
    scope: ListingScope,
) -> Result<ScanEvidence, LomoError> {
    if matches!(scope, ListingScope::WorkspaceFacts) {
        normalize_plain_trash(config, executor)?;
    }
    let mut files = Vec::new();
    let control = RelativeWorkspacePath::parse(lomo_rel::ROOT)?;
    let mut pending = vec![WorkspaceTarget::Root];
    if matches!(scope, ListingScope::WorkspaceFacts)
        && WorkspaceIo::physical(config, executor)
            .stat(&control)?
            .is_some()
    {
        pending.push(WorkspaceTarget::Relative(control));
    }
    let mut skipped_sync_subtrees = 0u64;
    // The walk keeps up to LISTING_BATCH_WIDTH independent cursor chains open: directories the
    // listing discovers are fanned into shared platform batches while each chain still advances
    // strictly through the cursor its own provider page issued. Only independent directories
    // interleave — page order inside one chain is untouched.
    let mut chains: Vec<ListingChain> = Vec::new();
    loop {
        while chains.len() < LISTING_BATCH_WIDTH {
            match pending.pop() {
                Some(target) => chains.push(ListingChain::new(0, target)),
                None => break,
            }
        }
        if chains.is_empty() {
            break;
        }
        for (_tag, page) in page_round(config, executor, &mut chains)? {
            match page {
                ListingPage::Items(items) => {
                    for item in &items {
                        admit_listing_item(
                            item,
                            &mut files,
                            &mut pending,
                            &mut skipped_sync_subtrees,
                        );
                    }
                }
                ListingPage::Rejected(error) => {
                    return Ok(ScanEvidence::incomplete(
                        error,
                        files,
                        skipped_sync_subtrees,
                    ));
                }
            }
        }
    }

    Ok(ScanEvidence::complete(files, skipped_sync_subtrees))
}

/// Older `.trash` daily Markdown is still user content. Admit it as individual recoverable
/// documents before scanning, without reading or importing any external `.lomo` records.
fn normalize_plain_trash(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
) -> Result<(), LomoError> {
    let io = WorkspaceIo::physical(config, executor);
    let directory = RelativeWorkspacePath::parse(lomo_rel::TRASH_V1)?;
    if io.stat(&directory)?.is_none() {
        return Ok(());
    }
    let listing = match list_target_pages(config, executor, &WorkspaceTarget::Relative(directory))?
    {
        ListingPage::Items(items) => items,
        ListingPage::Rejected(error) => return Err(error),
    };
    for item in listing {
        if item.kind() != DocumentKind::File {
            continue;
        }
        let WorkspaceTarget::Relative(path) = item.target() else {
            return Err(corruption(
                "invalid_trash_path",
                "trash listing contains a root entry",
            ));
        };
        if has_extension(path.as_str(), "md") {
            if lomo_workspace::is_trash_record_path(path.as_str()) {
                // Only the previous bare digest form needs admission. Dated records incur no
                // extra reads here; their facts/path are checked by the ordinary scan.
                if path
                    .as_str()
                    .strip_prefix(lomo_rel::TRASH_PREFIX)
                    .is_some_and(|name| name.len() == 67)
                {
                    normalize_trash_record_name(&io, path)?;
                }
            } else {
                normalize_plain_trash_document(&io, path)?;
            }
        }
    }
    Ok(())
}

fn normalize_trash_record_name(
    io: &WorkspaceIo<'_>,
    path: &RelativeWorkspacePath,
) -> Result<(), LomoError> {
    let source = io.require(path)?;
    let record = decode_trash_record(&source.bytes)?;
    let canonical = trash_record_relative_path(&record.memo_id, &record.source_path)?;
    let digest_name = canonical
        .as_str()
        .rsplit_once('-')
        .ok_or_else(|| {
            corruption(
                "invalid_trash_path",
                "canonical trash name requires an identity suffix",
            )
        })?
        .1;
    if path.as_str() != format!("{}{digest_name}", lomo_rel::TRASH_PREFIX) {
        return Err(corruption(
            "trash_record_path_mismatch",
            "trash filename does not match its recovery identity",
        ));
    }
    let destination = RelativeWorkspacePath::parse(canonical.as_str())?;
    if let Some(existing) = io.read(&destination)? {
        if existing.bytes != source.bytes {
            return Err(crate::error::conflict(
                "trash_admission_conflict",
                "dated trash already contains different recovery facts",
            ));
        }
    } else {
        io.write(&destination, None, &source.bytes)?;
    }
    // Write the complete verified snapshot first; source CAS makes interrupted admission resumable.
    io.delete(path, &source)
}

fn normalize_plain_trash_document(
    io: &WorkspaceIo<'_>,
    path: &RelativeWorkspacePath,
) -> Result<(), LomoError> {
    let source = io.require(path)?;
    let logical = WorkspaceRelativePath::parse(path.as_str())?;
    let document = parse_workspace_document(
        &SourceBytes::try_from_bytes(source.bytes.clone())?,
        crate::document_plan::filename_stem(path)?,
    )?;
    let mapping = memo_identity_record_path(io.config.root_id, &logical)?;
    let mapping_path = RelativeWorkspacePath::parse(mapping.as_str())?;
    let mut listed = BTreeMap::new();
    if let Some(metadata) = io.stat(&mapping_path)? {
        listed.insert(
            mapping.as_str().to_owned(),
            metadata.evidence().fingerprint().to_owned(),
        );
    }
    let identities = reconcile_identity(io, &logical, &document, &mut Vec::new(), &listed)?;
    let destination = RelativeWorkspacePath::parse(
        path.as_str()
            .strip_prefix(lomo_rel::TRASH_PREFIX)
            .ok_or_else(|| {
                corruption("invalid_trash_path", "daily trash must live under .trash")
            })?,
    )?;
    for binding in identities.bindings() {
        let memo = document
            .memos()
            .get(binding.locator().block_index() as usize)
            .ok_or_else(|| {
                corruption("invalid_trash_binding", "trash identity has no memo block")
            })?;
        let projection = crate::document_plan::project_memo(
            &destination,
            binding.memo_id(),
            document.source().fingerprint().as_str(),
            memo,
            &io.config.time_zone,
        )?;
        // Daily files carry no deletion timestamp. Their first admission is the recorded event;
        // a resumed admission keeps the timestamp of the already-written snapshot.
        let trash = crate::record_plan::trash_snapshot(
            &projection,
            memo.time_part(),
            crate::workspace_io::epoch_millis()?,
        )?;
        admit_plain_trash_snapshot(io, trash)?;
    }
    // Each complete recoverable body is durable first. Source CAS prevents a concurrent edit
    // from being unlinked; a crash resumes with the same private identity map and exact snapshots.
    io.delete(path, &source)
}

fn admit_plain_trash_snapshot(
    io: &WorkspaceIo<'_>,
    mut trash: lomo_workspace::TrashRecordV1,
) -> Result<(), LomoError> {
    let path = RelativeWorkspacePath::parse(
        trash_record_relative_path(&trash.memo_id, &trash.source_path)?.as_str(),
    )?;
    if let Some(previous) = io.read(&path)? {
        let admitted = decode_trash_record(&previous.bytes)?;
        trash.trashed_at_ms = admitted.trashed_at_ms;
        if trash != admitted {
            return Err(crate::error::conflict(
                "trash_admission_conflict",
                "daily trash changed while its recoverable documents were being admitted",
            ));
        }
        return Ok(());
    }
    io.write(&path, None, &lomo_workspace::encode_trash_record(&trash)?)?;
    Ok(())
}

/// Reads a workspace file via platform action and returns its byte contents.
///
/// # Errors
/// Returns `Storage` or `Corruption` error if read fails.
pub fn read_workspace_file(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
    path: &RelativeWorkspacePath,
) -> Result<Vec<u8>, LomoError> {
    Ok(WorkspaceIo::physical(config, executor).require(path)?.bytes)
}

pub(crate) fn has_extension(path: &str, ext: &str) -> bool {
    std::path::Path::new(path)
        .extension()
        .is_some_and(|e| e.eq_ignore_ascii_case(ext))
}
