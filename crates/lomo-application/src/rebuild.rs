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

use crate::{
    calendar::memo_chronology,
    config::WorkspaceSessionConfig,
    csprng::{generate_hex_token, mint_memo_id},
    error::{corruption, storage, validation},
    workspace_io::{WorkspaceIo, deadline},
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
    /// Digest of `listing_rows`, in the same formula the store's committed-row digest uses.
    listing_digest: String,
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

    /// Digest of `listing_rows`, identical to the `workspace_listing_digest` a commit of
    /// these rows recomputes.
    pub(crate) fn listing_digest(&self) -> &str {
        &self.listing_digest
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
}

#[derive(Clone, Debug)]
enum ScanEnumeration {
    Complete,
    Incomplete(LomoError),
}

impl ScanEvidence {
    const fn complete(listing: Vec<DocumentMetadata>) -> Self {
        Self {
            enumeration: ScanEnumeration::Complete,
            listing,
        }
    }

    const fn incomplete(error: LomoError, listing: Vec<DocumentMetadata>) -> Self {
        Self {
            enumeration: ScanEnumeration::Incomplete(error),
            listing,
        }
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
    list_recursive(config, executor)
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

/// Re-lists the parent directory of each `written` path and stores its current listing
/// fingerprint into `tokens`.
///
/// Files written mid-scan or mid-commit post-date the enumeration that provoked the work;
/// the parent directory re-list is the same listing evidence a fresh scan of the finished
/// filesystem reports. Paths under directories the workspace listing never admits
/// (`.git`, `.lomo/local`) are skipped, as is a path the re-list finds absent.
///
/// # Errors
/// Propagates listing failures for a directory that must exist — a write just landed there.
pub(crate) fn refresh_listing_tokens(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
    written: &[RelativeWorkspacePath],
    tokens: &mut BTreeMap<String, String>,
) -> Result<(), LomoError> {
    let mut parents: BTreeMap<String, Vec<String>> = BTreeMap::new();
    for path in written {
        let rel = path.as_str().to_owned();
        if rel == ".git"
            || rel.starts_with(".git/")
            || rel == ".lomo/local"
            || rel.starts_with(".lomo/local/")
        {
            continue;
        }
        let parent = std::path::Path::new(&rel)
            .parent()
            .and_then(|p| p.to_str())
            .map_or_else(String::new, str::to_owned);
        parents.entry(parent).or_default().push(rel);
    }
    for (parent, children) in parents {
        let listed = if parent.is_empty() {
            list_target_tokens(config, executor, &WorkspaceTarget::Root)?
        } else {
            list_target_tokens(
                config,
                executor,
                &WorkspaceTarget::Relative(RelativeWorkspacePath::parse(&parent)?),
            )?
        };
        for child in children {
            if let Some(token) = listed.get(&child) {
                tokens.insert(child, token.clone());
            }
        }
    }
    Ok(())
}

/// Scans workspace Markdown and `.lomo` facts without replacing SQLite.
pub(crate) fn scan_projection_inventory(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
) -> Result<ProjectionInventory, LomoError> {
    let evidence = list_recursive(config, executor)?;
    let listing = evidence.admitted_listing()?;
    scan_projection_inventory_from_listing(config, executor, listing)
}

pub(crate) fn scan_projection_inventory_from_listing(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
    listing: &[DocumentMetadata],
) -> Result<ProjectionInventory, LomoError> {
    let all_files = listing_file_paths(listing);

    let mut markdown_files = Vec::new();
    let mut history_files = Vec::new();
    let mut state_files = Vec::new();
    let mut trash_files = Vec::new();
    let mut purge_files = Vec::new();

    let mut written_paths = Vec::new();
    for path in all_files {
        let path_str = path.as_str();
        if has_extension(path_str, "md") && !path_str.starts_with(".lomo/") {
            markdown_files.push(path);
        } else if path_str.starts_with(".lomo/history/") && has_extension(path_str, "rec") {
            history_files.push(path);
        } else if path_str.starts_with(".lomo/state/") && has_extension(path_str, "rec") {
            state_files.push(path);
        } else if path_str.starts_with(".lomo/trash/") && has_extension(path_str, "rec") {
            trash_files.push(path);
        } else if path_str.starts_with(".lomo/purged/") && has_extension(path_str, "rec") {
            purge_files.push(path);
        }
    }

    let active_memos = scan_markdown_files(
        config,
        executor,
        &markdown_files,
        &mut history_files,
        &mut Vec::new(),
        &mut written_paths,
    )?;
    history_files.sort_by(|left, right| left.as_str().cmp(right.as_str()));
    history_files.dedup();
    let purged_ids = scan_purged_ids(config, executor, &purge_files)?;
    let trash_memos = scan_trash_files(config, executor, &trash_files, &purged_ids)?;
    let history_revisions =
        crate::rebuild_records::history(&WorkspaceIo { config, executor }, &history_files)?;
    // Pin facts attach only to identities this scan projects a row for — document
    // emissions plus trash claims that survived purge suppression. Anything else is a
    // dead tip the projection can never carry.
    let live_ids: BTreeSet<String> = active_memos
        .iter()
        .map(|memo| memo.memo_id.clone())
        .chain(trash_memos.iter().map(|trash| trash.memo.memo_id.clone()))
        .collect();
    let pin_scan =
        crate::rebuild_records::pins(&WorkspaceIo { config, executor }, &state_files, &live_ids)?;
    // The scan may have written identity records or initial history chains while it ran; the
    // committed listing rows must describe the filesystem at scan completion, so their tokens
    // come from a parent-directory re-list, not from the pre-scan listing.
    let mut listing_tokens = listing_token_map(listing);
    refresh_listing_tokens(config, executor, &written_paths, &mut listing_tokens)?;
    // Absorbed duplicate heads stay tolerated evidence — never baseline rows. A committed
    // copy would drop out of the path diff and let a claim gone stale stay invisible to
    // every later reconcile, so they are re-verified on every pass instead.
    for path in &pin_scan.absorbed_head_paths {
        listing_tokens.remove(path.as_str());
    }
    let listing_digest = listing_token_digest(&listing_tokens);
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
        listing_digest,
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
    history_files: &mut Vec<RelativeWorkspacePath>,
    initial_history: &mut Vec<ScannedHistoryProjection>,
    written_paths: &mut Vec<RelativeWorkspacePath>,
) -> Result<Vec<ScannedMemoProjection>, LomoError> {
    let io = WorkspaceIo { config, executor };
    let mut active_memos = Vec::new();
    for md_path in markdown_files {
        let snapshot = io.require(md_path)?;
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
        let identity_map = reconcile_identity(&io, &path, &document, written_paths)?;
        for binding in identity_map.bindings() {
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
            let history_before = history_files.len();
            if let Some(projection) =
                ensure_initial_history(&io, binding.memo_id(), memo, chronology, history_files)?
            {
                initial_history.push(projection);
                written_paths.extend(history_files.iter().skip(history_before).cloned());
            }
            active_memos.push(ScannedMemoProjection {
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
        }
    }
    Ok(active_memos)
}

/// Writes initial durable history for one memo when no head exists yet.
///
/// Returns the projection row for the revision just written — the memo's complete durable chain,
/// since a missing head means no earlier history exists — so a path-scoped caller can use it
/// directly as the memo's `history_replaces` fact without re-walking what it just wrote.
/// `None` means an existing head already anchors the chain.
fn ensure_initial_history(
    io: &WorkspaceIo<'_>,
    id: &lomo_workspace::MemoId,
    memo: &lomo_workspace::WorkspaceMemo,
    chronology: i64,
    inventory: &mut Vec<RelativeWorkspacePath>,
) -> Result<Option<ScannedHistoryProjection>, LomoError> {
    if crate::record_plan::history_tip(io, id)?.is_some() {
        return Ok(None);
    }
    let prepared = crate::record_plan::history_files(
        io,
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
    )?;
    let projection = prepared.projection.clone();
    for file in prepared.files {
        let current = io.read(file.path())?;
        file.apply(io, &crate::transaction::CurrentState::Bytes(current))?;
        inventory.push(file.path().clone());
    }
    Ok(Some(projection))
}

fn reconcile_identity(
    io: &WorkspaceIo<'_>,
    path: &WorkspaceRelativePath,
    document: &lomo_workspace::WorkspaceDocument,
    written_paths: &mut Vec<RelativeWorkspacePath>,
) -> Result<MemoIdentityMap, LomoError> {
    let record_path = memo_identity_record_path(io.config.root_id, path)?;
    let record_path = RelativeWorkspacePath::parse(record_path.as_str())?;
    let before = io.read(&record_path)?;
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
/// A tombstone must sit at its canonical `sha256(memo_id)` path — the same name authority the
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
                "durable purge tombstone is not stored at its canonical hashed path",
            ));
        }
        purged_ids.insert(tombstone.memo_id);
    }
    Ok(purged_ids)
}

/// Decodes one trash record and proves the file sits at its canonical `sha256(memo_id)` path.
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
    let expected = trash_record_relative_path(&trash.memo_id)?;
    if trash_path != expected.as_str() {
        return Err(corruption(
            "trash_record_path_mismatch",
            "durable trash record is not stored at its canonical hashed path",
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

/// One fully-paged listing for a single target.
pub(crate) enum ListingPage {
    /// Every item under the target.
    Items(Vec<DocumentMetadata>),
    /// The platform rejected the listing mid-enumeration; partial rows are not evidence.
    Rejected(LomoError),
}

/// Pages one listing target to completion.
///
/// A `Failed` action outcome becomes [`ListingPage::Rejected`] so the workspace-wide walk can
/// degrade to incomplete evidence while callers that need a certified single directory treat it
/// as an error.
fn list_target_pages(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
    target: &WorkspaceTarget,
) -> Result<ListingPage, LomoError> {
    let mut items = Vec::new();
    let mut cursor = None;
    loop {
        let action_id = ActionId::parse(&format!("list-{}", generate_hex_token(8)?))?;
        let page_size = PageSize::new(256)?;
        let action = match target {
            WorkspaceTarget::Root => {
                PlatformAction::list_root(action_id, config.capability.clone(), cursor, page_size)
            }
            WorkspaceTarget::Relative(rel) => PlatformAction::list_children(
                action_id,
                config.capability.clone(),
                rel.clone(),
                cursor,
                page_size,
            ),
        };

        let job_id = lomo_core::JobId::parse(&format!("job-list-{}", generate_hex_token(8)?))?;
        let batch_id =
            lomo_core::BatchId::parse(&format!("batch-list-{}", generate_hex_token(8)?))?;
        let batch = PlatformActionBatch::new(job_id, batch_id, 1, deadline()?, vec![action])?;

        let result = executor.execute(&batch)?;
        result.validate_against(&batch)?;

        let first_res = result
            .action_results()
            .first()
            .ok_or_else(|| storage("list_empty_result", "missing action result for list"))?;

        let output = match first_res.outcome() {
            lomo_core::ActionOutcome::Applied(out)
            | lomo_core::ActionOutcome::AlreadySatisfied(out) => out,
            lomo_core::ActionOutcome::Failed(err) => {
                return Ok(ListingPage::Rejected(err.clone()));
            }
        };

        let page = match output {
            PlatformActionOutput::Listed { page } => page,
            PlatformActionOutput::Stat { .. }
            | PlatformActionOutput::DirectoryReady { .. }
            | PlatformActionOutput::ReadToExchange { .. }
            | PlatformActionOutput::WriteComplete { .. }
            | PlatformActionOutput::MoveComplete { .. }
            | PlatformActionOutput::DeleteComplete { .. } => {
                return Err(corruption("invalid_list_output", "expected Listed output"));
            }
        };

        items.extend(page.items().iter().cloned());
        cursor = page.next_cursor().map(|c| c.as_str().to_owned());
        if cursor.is_none() {
            break;
        }
    }
    Ok(ListingPage::Items(items))
}

/// Re-lists one listing target (`Root` or a relative directory) and returns each file entry's
/// current listing fingerprint.
///
/// Path-scoped reconcile uses this to learn the post-write change tokens of files it wrote
/// while the workspace-wide listing was already taken — the fingerprint a fresh listing would
/// report for the same file state.
///
/// # Errors
/// Propagates listing failures: a directory the scan just wrote cannot fail to enumerate.
pub(crate) fn list_target_tokens(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
    target: &WorkspaceTarget,
) -> Result<BTreeMap<String, String>, LomoError> {
    match list_target_pages(config, executor, target)? {
        ListingPage::Items(items) => Ok(items
            .into_iter()
            .filter(|item| item.kind() == DocumentKind::File)
            .filter_map(|item| match item.target() {
                WorkspaceTarget::Relative(path) => Some((
                    path.as_str().to_owned(),
                    item.evidence().fingerprint().to_owned(),
                )),
                WorkspaceTarget::Root => None,
            })
            .collect()),
        ListingPage::Rejected(error) => Err(error),
    }
}

fn list_recursive(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
) -> Result<ScanEvidence, LomoError> {
    let mut files = Vec::new();
    let mut dirs_to_visit = vec![WorkspaceTarget::Root];

    while let Some(target) = dirs_to_visit.pop() {
        let items = match list_target_pages(config, executor, &target)? {
            ListingPage::Items(items) => items,
            ListingPage::Rejected(error) => {
                return Ok(ScanEvidence::incomplete(error, files));
            }
        };
        for item in &items {
            if matches!(item.target(), WorkspaceTarget::Relative(path) if matches!(path.as_str(), ".git" | ".lomo/local"))
            {
                continue;
            }
            match item.kind() {
                DocumentKind::File => {
                    files.push(item.clone());
                }
                DocumentKind::Directory => {
                    dirs_to_visit.push(item.target().clone());
                }
            }
        }
    }

    Ok(ScanEvidence::complete(files))
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
    Ok(WorkspaceIo { config, executor }.require(path)?.bytes)
}

pub(crate) fn has_extension(path: &str, ext: &str) -> bool {
    std::path::Path::new(path)
        .extension()
        .is_some_and(|e| e.eq_ignore_ascii_case(ext))
}
