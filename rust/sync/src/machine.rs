//! Unified provider-neutral sync state machine (P5-03 host hermetic slice).
//!
//! Pipeline: `RemoteSnapshot` → `ProviderNeutralIntent` → `PreparedRemoteBatch` →
//! `PublishReceipt` → `VerifiedRemoteState`. Baseline advances only after verify success.

use std::collections::{BTreeMap, BTreeSet};

use crate::conflict::{
    ConflictBodySource, ConflictSession, materialize_conflicts_from_plan,
    may_advance_baseline_for_path, read_conflict_session,
};
use crate::durable::{
    BaselineHead, SessionKind, SyncIdentityFence, SyncPaths, SyncSession, TombstoneSet,
    read_baseline, read_session, write_baseline, write_session,
};
use crate::error::{resource_limit, validation};
use crate::limits::{
    MAX_ACTION_PAGE_ITEMS, MAX_STREAMING_INTERMEDIATE_INTENTS, MAX_STREAMING_REMOTE_PATH_KEYS,
};
use crate::pipeline::{
    BatchAtomicity, ContentDigest, PreparedRemoteBatch, ProviderNeutralIntent, PublishReceipt,
    RemotePathEntry, RemoteSnapshot, SnapshotCompleteness, SyncPath, VerifiedRemoteState,
    VerifyStatus,
};
use crate::ports::{
    FakeLocalPort, FakeRemotePort, LocalSnapshot, LocalSyncPort, RemoteSyncPort,
    StoreLocalSnapshotPort,
};
use crate::recovery::{RecoverDeleteRequest, recover_pending_delete_intent};
use lomo_core::LomoError;

/// Outcome of one hermetic plan/apply/verify cycle.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SyncCycleResult {
    pub batch: PreparedRemoteBatch,
    pub receipt: Option<PublishReceipt>,
    pub verified: Option<VerifiedRemoteState>,
    pub baseline_advanced: bool,
    pub baseline: BaselineHead,
    /// Durable conflict session when plan emitted `OpenConflict` and materialize succeeded.
    pub conflict_session: Option<ConflictSession>,
}

/// Outcome of one streaming multi-page residual cycle (plan pages + optional multi-page apply).
///
/// Host residual (P5-11 deepen + Wave-12 multi-page apply): cycle entry consumes
/// [`RemoteSyncPort::list_remote_pages`] and [`plan_intents_streaming`] so multi-page listings never
/// materialize into one `RemoteSnapshot`. Apply (when requested) publishes **each** intent page in
/// order under the same verify-before-baseline rules; a mid-stream verify failure stops further
/// pages and leaves baseline advanced only for already-verified paths.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct StreamingSyncCycleResult {
    /// Streaming plan outcome (paged intents; never one giant batch).
    pub plan: StreamingPlanOutcome,
    /// First intent page as a single batch (empty batch when plan has no intents) for materialize /
    /// first-page publish compatibility with existing conflict / baseline helpers.
    pub first_page_batch: PreparedRemoteBatch,
    /// Number of intent pages that completed publish+verify successfully when `apply_remote` is true.
    /// Zero when plan-only or when the first page fails / has no remote mutations requiring apply.
    pub pages_applied: u32,
    /// Concatenated path results from all successfully published pages (empty when no publish).
    pub receipt: Option<PublishReceipt>,
    /// Concatenated verify results from all applied pages (None when plan-only).
    pub verified: Option<VerifiedRemoteState>,
    pub baseline_advanced: bool,
    pub baseline: BaselineHead,
    pub conflict_session: Option<ConflictSession>,
}

/// Coarse plan/readiness summary for one dark host cycle inspect (no publish/apply).
///
/// Counts and disposition are derived from the owner planner + durable conflict head only.
/// Remote transport is not required: inspect uses empty hermetic ports so the host can touch the
/// conversion surface without re-implementing planner rules in Kotlin/`lomo-native`.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SyncCyclePlanSummary {
    pub session_id: String,
    pub session_kind: SessionKind,
    pub session_revision: u64,
    pub baseline_established: bool,
    pub ensure_present_count: u32,
    pub ensure_absent_count: u32,
    pub pull_present_count: u32,
    pub open_conflict_count: u32,
    /// Open paths still needing user attention on the durable conflict session (0 when absent).
    pub open_conflict_paths: u32,
    /// Conflict session revision when a durable conflict head exists.
    pub conflict_revision: Option<u64>,
    /// WorkManager-facing disposition name owned by Rust (`never` / `after_user_action` / `transient`).
    pub retry_disposition: &'static str,
}

/// Outcome of a streaming multi-page plan (intent pages only; never a full-path payload dump).
///
/// Host scale contracts assert:
/// - each intent page is ≤ [`MAX_ACTION_PAGE_ITEMS`]
/// - remote key working set is bounded by [`MAX_STREAMING_REMOTE_PATH_KEYS`]
/// - no single in-memory remote snapshot holds more than one page of entries at once
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct StreamingPlanOutcome {
    /// Durable action pages (each ≤ 512 intents). Empty pages are never produced.
    pub intent_pages: Vec<PreparedRemoteBatch>,
    /// Distinct remote path keys observed across all pages (path strings only).
    pub remote_path_key_count: usize,
    /// Peak remote entries held in the page buffer during the scan.
    pub peak_remote_page_entries: usize,
    /// Overall remote completeness used for delete derivation (Complete only when stream says so).
    pub completeness: SnapshotCompleteness,
}

impl StreamingPlanOutcome {
    /// Total intents across all pages (sum of page lengths).
    #[must_use]
    pub fn total_intent_count(&self) -> usize {
        self.intent_pages
            .iter()
            .map(|page| page.intents.len())
            .sum()
    }

    /// Total `EnsureAbsent` intents across all pages.
    #[must_use]
    pub fn ensure_absent_count(&self) -> usize {
        self.intent_pages
            .iter()
            .map(PreparedRemoteBatch::ensure_absent_count)
            .sum()
    }

    /// Total `EnsurePresent` intents across all pages.
    #[must_use]
    pub fn ensure_present_count(&self) -> usize {
        self.intent_pages
            .iter()
            .map(PreparedRemoteBatch::ensure_present_count)
            .sum()
    }

    /// Total `OpenConflict` intents across all pages.
    #[must_use]
    pub fn open_conflict_count(&self) -> usize {
        self.intent_pages
            .iter()
            .map(PreparedRemoteBatch::open_conflict_count)
            .sum()
    }

    /// Total `PullPresent` intents across all pages.
    #[must_use]
    pub fn pull_present_count(&self) -> usize {
        self.intent_pages
            .iter()
            .map(PreparedRemoteBatch::pull_present_count)
            .sum()
    }

    /// True when every intent page respects the action page ceiling.
    #[must_use]
    pub fn pages_within_limit(&self) -> bool {
        self.intent_pages
            .iter()
            .all(|page| page.intents.len() <= MAX_ACTION_PAGE_ITEMS)
    }
}

/// Plans provider-neutral intents from local, remote, baseline, and tombstone facts.
///
/// Rules enforced here:
/// - first-takeover / migration: no `EnsureAbsent`; only safe ensure-present / baseline
///   establishment / conflict
/// - partial listing (`Incomplete`): no `EnsureAbsent`
/// - both-modified (local ≠ remote ≠ baseline): `OpenConflict`
/// - identical remote/local digests: no-op (baseline can establish)
/// - tombstone consultation: same-bytes reappear after tombstone stays deleted (`EnsureAbsent` when
///   delete gates pass; otherwise skipped); different-bytes reappear → `OpenConflict` (never auto-pull)
///
/// # Errors
///
/// Validation when page limits fail inside [`PreparedRemoteBatch::new`].
pub fn plan_intents(
    session_kind: SessionKind,
    local: &LocalSnapshot,
    remote: &RemoteSnapshot,
    baseline: &BaselineHead,
    tombstones: &TombstoneSet,
) -> Result<PreparedRemoteBatch, LomoError> {
    let local_map: BTreeMap<&str, &ContentDigest> = local
        .entries
        .iter()
        .map(|entry| (entry.path.as_str(), &entry.digest))
        .collect();
    let remote_map: BTreeMap<&str, (&ContentDigest, &str)> = remote
        .entries
        .iter()
        .map(|entry| {
            (
                entry.path.as_str(),
                (&entry.digest, entry.revision_token.as_str()),
            )
        })
        .collect();

    let may_delete = session_kind.may_emit_user_file_delete()
        && matches!(remote.completeness, SnapshotCompleteness::Complete)
        && baseline.is_established();
    let mut intents = Vec::new();
    let mut seen: BTreeSet<String> = BTreeSet::new();

    // Remote paths: pull / conflict / baseline-match / tombstone gates.
    for entry in &remote.entries {
        seen.insert(entry.path.as_str().to_owned());
        if let Some(intent) =
            plan_remote_entry(entry, &local_map, baseline, tombstones, may_delete)?
        {
            intents.push(intent);
        }
    }

    // Local-only paths (not in remote listing): upload when not a baseline-tracked remote absence.
    // Baseline-tracked remote absence is classified by delete-vs-edit below (never silent re-upload
    // over a proven remote delete when local still matches baseline).
    for entry in &local.entries {
        let path_s = entry.path.as_str();
        if seen.contains(path_s) {
            continue;
        }
        if remote_map.contains_key(path_s) {
            continue;
        }
        if baseline.get(path_s).is_some() {
            continue;
        }
        intents.push(ProviderNeutralIntent::EnsurePresent {
            path: entry.path.clone(),
            digest: entry.digest.clone(),
            expected_remote_token: None,
        });
    }

    // Baseline paths missing remotely → delete-vs-edit or EnsureAbsent under hard gates.
    // Even when may_delete is false, local-edit + remote-delete must open conflict (never silent).
    for base_entry in &baseline.entries {
        if remote_map.contains_key(base_entry.path.as_str()) {
            continue;
        }
        let path = SyncPath::parse(&base_entry.path)?;
        let baseline_digest = ContentDigest::parse(&base_entry.digest)?;
        let local_digest = local_map.get(base_entry.path.as_str()).copied().cloned();
        if let Some(intent) = crate::recovery::plan_delete_versus_edit_intent(
            &path,
            Some(&baseline_digest),
            local_digest.as_ref(),
            Some(base_entry.remote_token.as_str()),
            may_delete,
        )? {
            intents.push(intent);
        }
    }

    PreparedRemoteBatch::new(BatchAtomicity::PerPath, intents)
}

/// Plans provider-neutral intents from a **streaming** remote snapshot iterator.
///
/// Host scale contract (P5-11):
/// - remote entries are consumed one page at a time (≤ [`MAX_ACTION_PAGE_ITEMS`] per page)
/// - only path **keys** are retained across pages (no multi-page full-entry materialize)
/// - intent output is split into ≤512-item durable pages (never one giant batch)
/// - overall `Complete` listing may participate in delete derivation; incomplete never does
///
/// The caller supplies an iterator of remote pages. Each page must already be page-bounded
/// (`RemoteSnapshot::page` / `RemoteSnapshot::new` with ≤512 entries). The overall completeness
/// is provided separately so partial multi-page listings still fail closed on deletes.
///
/// # Errors
///
/// Resource-limit when remote key working set exceeds [`MAX_STREAMING_REMOTE_PATH_KEYS`], when a
/// page exceeds the action page ceiling, or when a compiled intent page would exceed the ceiling
/// (should not occur if page splits are correct). Validation on path/digest parse failures.
pub fn plan_intents_streaming<I>(
    session_kind: SessionKind,
    local: &LocalSnapshot,
    remote_pages: I,
    overall_completeness: SnapshotCompleteness,
    baseline: &BaselineHead,
    tombstones: &TombstoneSet,
) -> Result<StreamingPlanOutcome, LomoError>
where
    I: IntoIterator<Item = Result<Vec<RemotePathEntry>, LomoError>>,
{
    let local_map: BTreeMap<&str, &ContentDigest> = local
        .entries
        .iter()
        .map(|entry| (entry.path.as_str(), &entry.digest))
        .collect();

    let may_delete = session_kind.may_emit_user_file_delete()
        && matches!(overall_completeness, SnapshotCompleteness::Complete)
        && baseline.is_established();

    let mut remote_path_keys: BTreeSet<String> = BTreeSet::new();
    let mut intents: Vec<ProviderNeutralIntent> = Vec::new();
    let mut peak_remote_page_entries: usize = 0;

    for page_result in remote_pages {
        let page_entries = page_result?;
        if page_entries.len() > MAX_ACTION_PAGE_ITEMS {
            return Err(resource_limit(
                "remote_snapshot_page_too_large",
                "streaming remote page exceeds the 512-item action page limit",
            ));
        }
        peak_remote_page_entries = peak_remote_page_entries.max(page_entries.len());

        for entry in &page_entries {
            let path_s = entry.path.as_str();
            if !remote_path_keys.insert(path_s.to_owned()) {
                // Duplicate path across pages: fail closed (corrupt/unstable listing).
                return Err(validation(
                    "streaming_remote_duplicate_path",
                    "streaming remote listing repeated a path across pages",
                ));
            }
            if remote_path_keys.len() > MAX_STREAMING_REMOTE_PATH_KEYS {
                return Err(resource_limit(
                    "streaming_remote_path_keys_too_large",
                    "streaming remote path-key working set exceeds the 100k limit",
                ));
            }
            if let Some(intent) =
                plan_remote_entry(entry, &local_map, baseline, tombstones, may_delete)?
            {
                if intents.len() >= MAX_STREAMING_INTERMEDIATE_INTENTS {
                    return Err(resource_limit(
                        "streaming_intermediate_intents_too_large",
                        "streaming intermediate intent accumulation exceeds the path-key ceiling",
                    ));
                }
                intents.push(intent);
            }
        }
        // Page entries drop here — only keys remain. Peak buffer = max page size, not full set.
    }

    // Local-only paths (not observed on any remote page).
    for entry in &local.entries {
        let path_s = entry.path.as_str();
        if remote_path_keys.contains(path_s) {
            continue;
        }
        if baseline.get(path_s).is_some() {
            continue;
        }
        if intents.len() >= MAX_STREAMING_INTERMEDIATE_INTENTS {
            return Err(resource_limit(
                "streaming_intermediate_intents_too_large",
                "streaming intermediate intent accumulation exceeds the path-key ceiling",
            ));
        }
        intents.push(ProviderNeutralIntent::EnsurePresent {
            path: entry.path.clone(),
            digest: entry.digest.clone(),
            expected_remote_token: None,
        });
    }

    // Baseline paths missing remotely → delete-vs-edit / EnsureAbsent under hard gates.
    for base_entry in &baseline.entries {
        if remote_path_keys.contains(base_entry.path.as_str()) {
            continue;
        }
        let path = SyncPath::parse(&base_entry.path)?;
        let baseline_digest = ContentDigest::parse(&base_entry.digest)?;
        let local_digest = local_map.get(base_entry.path.as_str()).copied().cloned();
        if let Some(intent) = crate::recovery::plan_delete_versus_edit_intent(
            &path,
            Some(&baseline_digest),
            local_digest.as_ref(),
            Some(base_entry.remote_token.as_str()),
            may_delete,
        )? {
            if intents.len() >= MAX_STREAMING_INTERMEDIATE_INTENTS {
                return Err(resource_limit(
                    "streaming_intermediate_intents_too_large",
                    "streaming intermediate intent accumulation exceeds the path-key ceiling",
                ));
            }
            intents.push(intent);
        }
    }

    let intent_pages = split_intents_into_pages(&intents)?;
    Ok(StreamingPlanOutcome {
        intent_pages,
        remote_path_key_count: remote_path_keys.len(),
        peak_remote_page_entries,
        completeness: overall_completeness,
    })
}

fn split_intents_into_pages(
    intents: &[ProviderNeutralIntent],
) -> Result<Vec<PreparedRemoteBatch>, LomoError> {
    if intents.is_empty() {
        return Ok(Vec::new());
    }
    let mut pages = Vec::new();
    for chunk in intents.chunks(MAX_ACTION_PAGE_ITEMS) {
        pages.push(PreparedRemoteBatch::new(
            BatchAtomicity::PerPath,
            chunk.to_vec(),
        )?);
    }
    Ok(pages)
}

fn plan_remote_entry(
    entry: &RemotePathEntry,
    local_map: &BTreeMap<&str, &ContentDigest>,
    baseline: &BaselineHead,
    tombstones: &TombstoneSet,
    may_delete: bool,
) -> Result<Option<ProviderNeutralIntent>, LomoError> {
    let path = entry.path.as_str();
    // Unrecognized remote paths are report-only: never pull, move, or delete (SB-08).
    if !crate::pipeline::is_owned_sync_user_path(path) {
        return Ok(Some(ProviderNeutralIntent::ReportUnrecognized {
            path: entry.path.clone(),
        }));
    }
    if let Some(tombstone) = tombstones.get(path) {
        if tombstone.content_digest == entry.digest.as_str() {
            return Ok(may_delete.then_some(ProviderNeutralIntent::EnsureAbsent {
                path: entry.path.clone(),
                expected_remote_token: entry.revision_token.clone(),
            }));
        }
        let local_digest = match local_map.get(path) {
            Some(digest) => (*digest).clone(),
            None => ContentDigest::parse(&tombstone.content_digest)?,
        };
        let baseline_digest = baseline
            .get(path)
            .map(|base| ContentDigest::parse(&base.digest))
            .transpose()?;
        return Ok(Some(ProviderNeutralIntent::OpenConflict {
            path: entry.path.clone(),
            local_digest,
            remote_digest: entry.digest.clone(),
            baseline_digest,
        }));
    }

    let Some(local_digest) = local_map.get(path) else {
        return Ok(Some(ProviderNeutralIntent::PullPresent {
            path: entry.path.clone(),
            digest: entry.digest.clone(),
            remote_token: entry.revision_token.clone(),
        }));
    };
    if local_digest.as_str() == entry.digest.as_str() {
        return Ok(None);
    }
    let baseline_digest = baseline
        .get(path)
        .map(|base| ContentDigest::parse(&base.digest))
        .transpose()?;
    let both_modified = baseline_digest.as_ref().is_none_or(|base| {
        base.as_str() != local_digest.as_str() && base.as_str() != entry.digest.as_str()
    });
    if both_modified {
        return Ok(Some(ProviderNeutralIntent::OpenConflict {
            path: entry.path.clone(),
            local_digest: (*local_digest).clone(),
            remote_digest: entry.digest.clone(),
            baseline_digest,
        }));
    }
    if baseline_digest
        .as_ref()
        .is_some_and(|base| base.as_str() == local_digest.as_str())
    {
        return Ok(Some(ProviderNeutralIntent::PullPresent {
            path: entry.path.clone(),
            digest: entry.digest.clone(),
            remote_token: entry.revision_token.clone(),
        }));
    }
    Ok(Some(ProviderNeutralIntent::EnsurePresent {
        path: entry.path.clone(),
        digest: (*local_digest).clone(),
        expected_remote_token: Some(entry.revision_token.clone()),
    }))
}

/// Runs one plan → optional materialize → optional publish → verify → conditional baseline advance.
///
/// When `paths` is set and the plan emits `OpenConflict`, durable conflict session + candidate
/// artifacts are materialized **before** any baseline advance. Open / `SkipForNow` paths never
/// advance baseline (`baseline_must_hold_for_path`). Tombstone-backed pending deletes are
/// re-issued via `recover_pending_delete_intent` on session revive when durable paths exist.
///
/// `conflict_bodies` supplies candidate bytes for materialization. When open conflicts exist and
/// `paths` is set, bodies are **required** (hollow open is rejected). Plan-only (`apply_remote =
/// false`) still materializes when paths + bodies are provided so conflict is durable before UI.
///
/// # Errors
///
/// Port / planning / durable write / hollow-open errors. Verify failure leaves baseline unchanged.
pub fn run_sync_cycle(
    session: &SyncSession,
    local: &dyn LocalSyncPort,
    remote: &dyn RemoteSyncPort,
    mut baseline: BaselineHead,
    paths: Option<&SyncPaths>,
    apply_remote: bool,
    conflict_bodies: Option<&ConflictBodySource>,
) -> Result<SyncCycleResult, LomoError> {
    let local_snap = local.snapshot()?;
    let remote_snap = remote.list_remote()?;
    let tombstones = paths
        .map(crate::durable::read_tombstones)
        .transpose()?
        .unwrap_or_else(TombstoneSet::empty);

    let recovery_intents = collect_pending_delete_recovery(
        session,
        &local_snap,
        &remote_snap,
        &baseline,
        &tombstones,
    )?;
    let mut batch = plan_intents(
        session.kind,
        &local_snap,
        &remote_snap,
        &baseline,
        &tombstones,
    )?;
    merge_recovery_ensure_absent(&mut batch, recovery_intents);

    let conflict_session = materialize_or_load_conflict_session(
        session,
        paths,
        &batch,
        &remote_snap,
        conflict_bodies,
    )?;

    if !apply_remote {
        return Ok(SyncCycleResult {
            batch,
            receipt: None,
            verified: None,
            baseline_advanced: false,
            baseline,
            conflict_session,
        });
    }

    let (receipt, verified) = publish_and_verify(remote, &batch, &local_snap, &remote_snap)?;
    let baseline_advanced = advance_baseline_after_verify(
        session,
        paths,
        conflict_session.as_ref(),
        &verified,
        &mut baseline,
    )?;

    Ok(SyncCycleResult {
        batch,
        receipt,
        verified: Some(verified),
        baseline_advanced,
        baseline,
        conflict_session,
    })
}

/// Host residual cycle entry that plans from **paged** remote listings.
///
/// Consumes [`RemoteSyncPort::list_remote_pages`] + [`plan_intents_streaming`] so multi-page
/// listings never thrash into a single `RemoteSnapshot`. Optional multi-page apply uses the same
/// verify-before-baseline rules as [`run_sync_cycle`] for **each** intent page in order.
///
/// Conflict materialize for open intents uses a **view** `RemoteSnapshot` built from the first
/// remote page only (page-bounded; never full multi-page materialize). Hollow open still fails closed
/// when bodies are required and missing.
///
/// **Permanent product law (multi-page conflict):** when the plan emits `OpenConflict` intents on
/// any intent page beyond the first, streaming cycle rejects with
/// `streaming_open_conflict_outside_first_page` rather than silently materializing only the first
/// page's conflicts or thrashing multi-page remote entries into one materialize view. First-page
/// `OpenConflict` still materializes page-bounded. Full multi-page conflict materialize is
/// permanently forbidden — this is Stage-5 product law, not a deferred design residual.
///
/// # Errors
///
/// Port / planning / durable write / hollow-open / resource-limit /
/// `streaming_open_conflict_outside_first_page` errors.
pub fn run_sync_cycle_streaming(
    session: &SyncSession,
    local: &dyn LocalSyncPort,
    remote: &dyn RemoteSyncPort,
    mut baseline: BaselineHead,
    paths: Option<&SyncPaths>,
    apply_remote: bool,
    conflict_bodies: Option<&ConflictBodySource>,
) -> Result<StreamingSyncCycleResult, LomoError> {
    let local_snap = local.snapshot()?;
    let listing = remote.list_remote_pages()?;
    let overall_completeness = listing.overall_completeness;
    // First page view only for conflict materialize / same-byte verify helpers (page-bounded).
    // Empty listing → empty entries (domain-empty, not a silent default of missing data).
    let first_page_entries = listing.pages.first().cloned().unwrap_or_else(Vec::new);
    let remote_view = RemoteSnapshot {
        completeness: overall_completeness,
        entries: first_page_entries,
    };
    let tombstones = paths
        .map(crate::durable::read_tombstones)
        .transpose()?
        .unwrap_or_else(TombstoneSet::empty);

    let plan = plan_intents_streaming(
        session.kind,
        &local_snap,
        listing.into_page_iter(),
        overall_completeness,
        &baseline,
        &tombstones,
    )?;

    // Recovery merge is single-shot path only for now (tombstone revive under Incremental).
    // Streaming residual keeps plan pages pure; recovery EnsureAbsent is folded into first page when
    // the single-shot remote view can observe the path (host hermetic fakes).
    let recovery_intents = collect_pending_delete_recovery(
        session,
        &local_snap,
        &remote_view,
        &baseline,
        &tombstones,
    )?;

    let first_page_batch = match plan.intent_pages.first() {
        None => {
            let mut empty = PreparedRemoteBatch::new(BatchAtomicity::PerPath, Vec::new())?;
            merge_recovery_ensure_absent(&mut empty, recovery_intents);
            empty
        }
        Some(first) => {
            let mut first = first.clone();
            merge_recovery_ensure_absent(&mut first, recovery_intents);
            first
        }
    };

    // Permanent product law: multi-page OpenConflict is fail-closed (never full multi-page
    // materialize). Later intent pages that emit OpenConflict would be invisible to first-page
    // materialize and must not silently drop.
    reject_open_conflict_outside_first_page(&plan)?;

    let conflict_session = materialize_or_load_conflict_session(
        session,
        paths,
        &first_page_batch,
        &remote_view,
        conflict_bodies,
    )?;

    if !apply_remote {
        return Ok(StreamingSyncCycleResult {
            plan,
            first_page_batch,
            pages_applied: 0,
            receipt: None,
            verified: None,
            baseline_advanced: false,
            baseline,
            conflict_session,
        });
    }

    let mut apply_request = StreamingApplyRequest {
        session,
        remote,
        local_snap: &local_snap,
        remote_view: &remote_view,
        paths,
        conflict_session: conflict_session.as_ref(),
        plan: &plan,
        first_page_batch: &first_page_batch,
        baseline: &mut baseline,
    };
    let applied = apply_streaming_intent_pages(&mut apply_request)?;

    Ok(StreamingSyncCycleResult {
        plan,
        first_page_batch,
        pages_applied: applied.pages_applied,
        receipt: applied.receipt,
        verified: Some(applied.verified),
        baseline_advanced: applied.baseline_advanced,
        baseline,
        conflict_session,
    })
}

struct StreamingApplyRequest<'a> {
    session: &'a SyncSession,
    remote: &'a dyn RemoteSyncPort,
    local_snap: &'a LocalSnapshot,
    remote_view: &'a RemoteSnapshot,
    paths: Option<&'a SyncPaths>,
    conflict_session: Option<&'a ConflictSession>,
    plan: &'a StreamingPlanOutcome,
    first_page_batch: &'a PreparedRemoteBatch,
    baseline: &'a mut BaselineHead,
}

struct StreamingApplyOutcome {
    pages_applied: u32,
    receipt: Option<PublishReceipt>,
    verified: VerifiedRemoteState,
    baseline_advanced: bool,
}

/// Publish + verify each streaming intent page in order; stop after mid-stream verify failure.
fn apply_streaming_intent_pages(
    request: &mut StreamingApplyRequest<'_>,
) -> Result<StreamingApplyOutcome, LomoError> {
    // Multi-page apply residual: each page uses the same verify-before-baseline rules.
    // Empty plan still runs the (possibly recovery-merged) first batch once.
    let batches_to_apply: Vec<PreparedRemoteBatch> = if request.plan.intent_pages.is_empty() {
        vec![request.first_page_batch.clone()]
    } else {
        let mut batches = Vec::with_capacity(request.plan.intent_pages.len());
        batches.push(request.first_page_batch.clone());
        batches.extend(request.plan.intent_pages.iter().skip(1).cloned());
        batches
    };

    let mut combined_path_results = Vec::new();
    let mut combined_verify = Vec::new();
    let mut pages_applied = 0u32;
    let mut baseline_advanced = false;
    let mut any_receipt = false;

    for batch in &batches_to_apply {
        let (receipt, verified) = publish_and_verify(
            request.remote,
            batch,
            request.local_snap,
            request.remote_view,
        )?;
        if let Some(published) = receipt {
            any_receipt = true;
            combined_path_results.extend(published.path_results);
        }
        let page_verified_ok = verified.all_verified();
        combined_verify.extend(verified.results.iter().cloned());
        let page_advanced = advance_baseline_after_verify(
            request.session,
            request.paths,
            request.conflict_session,
            &verified,
            request.baseline,
        )?;
        baseline_advanced = baseline_advanced || page_advanced;
        pages_applied = pages_applied.saturating_add(1);
        if !page_verified_ok {
            // Fail closed: do not publish subsequent pages after verify failure.
            break;
        }
    }

    let receipt = if any_receipt {
        Some(PublishReceipt {
            path_results: combined_path_results,
        })
    } else {
        None
    };

    Ok(StreamingApplyOutcome {
        pages_applied,
        receipt,
        verified: VerifiedRemoteState {
            results: combined_verify,
        },
        baseline_advanced,
    })
}

fn collect_pending_delete_recovery(
    session: &SyncSession,
    local_snap: &LocalSnapshot,
    remote_snap: &RemoteSnapshot,
    baseline: &BaselineHead,
    tombstones: &TombstoneSet,
) -> Result<Vec<ProviderNeutralIntent>, LomoError> {
    let mut recovery_intents = Vec::new();
    if !session.kind.may_emit_user_file_delete() {
        return Ok(recovery_intents);
    }
    for entry in &tombstones.entries {
        let Ok(path) = SyncPath::parse(&entry.path) else {
            continue;
        };
        let remote_entry = remote_snap
            .entries
            .iter()
            .find(|remote| remote.path.as_str() == entry.path.as_str());
        let remote_digest = remote_entry.map(|remote| &remote.digest);
        let remote_token = remote_entry.map(|remote| remote.revision_token.as_str());
        let local_has = local_snap
            .entries
            .iter()
            .any(|local| local.path.as_str() == entry.path.as_str());
        if let Some(intent) = recover_pending_delete_intent(&RecoverDeleteRequest {
            fence: &session.fence,
            baseline,
            tombstones,
            session_kind: session.kind,
            remote_completeness: remote_snap.completeness,
            path: &path,
            local_has_path: local_has,
            remote_token,
            remote_digest,
        })? {
            recovery_intents.push(intent);
        }
    }
    Ok(recovery_intents)
}

fn merge_recovery_ensure_absent(
    batch: &mut PreparedRemoteBatch,
    recovery_intents: Vec<ProviderNeutralIntent>,
) {
    for intent in recovery_intents {
        let path_s = match &intent {
            ProviderNeutralIntent::EnsureAbsent { path, .. } => path.as_str(),
            ProviderNeutralIntent::EnsurePresent { .. }
            | ProviderNeutralIntent::PullPresent { .. }
            | ProviderNeutralIntent::OpenConflict { .. }
            | ProviderNeutralIntent::ReportUnrecognized { .. } => continue,
        };
        let already = batch.intents.iter().any(|existing| match existing {
            ProviderNeutralIntent::EnsureAbsent { path, .. } => path.as_str() == path_s,
            ProviderNeutralIntent::EnsurePresent { .. }
            | ProviderNeutralIntent::PullPresent { .. }
            | ProviderNeutralIntent::OpenConflict { .. }
            | ProviderNeutralIntent::ReportUnrecognized { .. } => false,
        });
        if !already {
            batch.intents.push(intent);
        }
    }
}

/// Permanent product law: reject streaming cycles whose later intent pages carry `OpenConflict`
/// outside the first-page materialize view (never full multi-page conflict materialize).
fn reject_open_conflict_outside_first_page(plan: &StreamingPlanOutcome) -> Result<(), LomoError> {
    let outside = plan
        .intent_pages
        .iter()
        .skip(1)
        .map(PreparedRemoteBatch::open_conflict_count)
        .sum::<usize>();
    if outside > 0 {
        return Err(validation(
            "streaming_open_conflict_outside_first_page",
            "streaming cycle permanently rejects OpenConflict outside the first intent page (product law: no multi-page conflict materialize)",
        ));
    }
    Ok(())
}

fn materialize_or_load_conflict_session(
    session: &SyncSession,
    paths: Option<&SyncPaths>,
    batch: &PreparedRemoteBatch,
    remote_snap: &RemoteSnapshot,
    conflict_bodies: Option<&ConflictBodySource>,
) -> Result<Option<ConflictSession>, LomoError> {
    let Some(sync_paths) = paths else {
        return Ok(None);
    };
    if batch.open_conflict_count() > 0 {
        let bodies = conflict_bodies.ok_or_else(|| {
            validation(
                "conflict_candidate_body_missing",
                "OpenConflict materialize requires candidate body source",
            )
        })?;
        let conflict_session_id = format!("{}-conflict", session.session_id);
        return materialize_conflicts_from_plan(
            sync_paths,
            &session.fence,
            &conflict_session_id,
            batch,
            remote_snap,
            bodies,
        );
    }
    // Load existing session if present so baseline hold still applies across cycles.
    match read_conflict_session(sync_paths) {
        Ok(existing) => Ok(Some(existing)),
        Err(err) if err.code() == "conflict_session_missing" => Ok(None),
        Err(err) => Err(err),
    }
}

fn batch_has_remote_mutations(batch: &PreparedRemoteBatch) -> bool {
    batch.intents.iter().any(|intent| {
        matches!(
            intent,
            ProviderNeutralIntent::EnsurePresent { .. }
                | ProviderNeutralIntent::EnsureAbsent { .. }
        )
    })
}

fn publish_and_verify(
    remote: &dyn RemoteSyncPort,
    batch: &PreparedRemoteBatch,
    local_snap: &LocalSnapshot,
    remote_snap: &RemoteSnapshot,
) -> Result<(Option<PublishReceipt>, VerifiedRemoteState), LomoError> {
    // Remote mutations only: EnsurePresent / EnsureAbsent. OpenConflict / PullPresent /
    // ReportUnrecognized never publish (adapters would Skip; hollow conflict must not pretend apply).
    let receipt = if batch_has_remote_mutations(batch) {
        Some(remote.publish(batch)?)
    } else {
        None
    };

    let mut verify_paths: Vec<SyncPath> = receipt.as_ref().map_or_else(Vec::new, |published| {
        published
            .path_results
            .iter()
            .filter_map(|(path, status)| match status {
                crate::pipeline::PathPublishStatus::Applied { .. } => Some(path.clone()),
                crate::pipeline::PathPublishStatus::PreconditionFailed
                | crate::pipeline::PathPublishStatus::Failed { .. }
                | crate::pipeline::PathPublishStatus::Skipped => None,
            })
            .collect()
    });

    // PullPresent is local-store apply; remote verify still confirms token/digest.
    // Same-byte paths may establish baseline after verify of remote presence (no publish needed).
    for intent in &batch.intents {
        if let ProviderNeutralIntent::PullPresent { path, .. } = intent
            && !verify_paths.iter().any(|p| p.as_str() == path.as_str())
        {
            verify_paths.push(path.clone());
        }
    }
    for entry in &remote_snap.entries {
        if let Some(local_entry) = local_snap
            .entries
            .iter()
            .find(|local| local.path.as_str() == entry.path.as_str())
            && local_entry.digest.as_str() == entry.digest.as_str()
            && !verify_paths
                .iter()
                .any(|p| p.as_str() == entry.path.as_str())
        {
            verify_paths.push(entry.path.clone());
        }
    }

    let verified = if verify_paths.is_empty() {
        VerifiedRemoteState {
            results: Vec::new(),
        }
    } else {
        remote.verify(&verify_paths)?
    };
    Ok((receipt, verified))
}

fn advance_baseline_after_verify(
    session: &SyncSession,
    paths: Option<&SyncPaths>,
    conflict_session: Option<&ConflictSession>,
    verified: &VerifiedRemoteState,
    baseline: &mut BaselineHead,
) -> Result<bool, LomoError> {
    if !verified.all_verified() {
        return Ok(false);
    }
    let mut baseline_advanced = false;
    for result in &verified.results {
        match result {
            VerifyStatus::Verified {
                path,
                digest,
                remote_token,
            } => {
                if may_advance_baseline_for_path(conflict_session, path.as_str()) {
                    baseline.upsert(path, digest, remote_token.clone());
                    baseline_advanced = true;
                }
            }
            VerifyStatus::AbsentVerified { path } => {
                if may_advance_baseline_for_path(conflict_session, path.as_str()) {
                    baseline.remove(path.as_str());
                    baseline_advanced = true;
                }
            }
            VerifyStatus::Failed { .. } => {
                // all_verified() false path; unreachable here.
            }
        }
    }
    // Fence + durable write only when at least one path actually advanced. Empty verify
    // results make `all_verified()` true vacuously — do not invent "established" baseline
    // (PreconditionFailed / no-op apply must leave is_established false).
    if baseline_advanced {
        if baseline.fence.is_none() {
            baseline.fence = Some(session.fence.clone());
        }
        if let Some(sync_paths) = paths {
            write_baseline(sync_paths, baseline)?;
        }
    }
    // Held-only open/skip paths leave baseline bytes on disk unchanged (no write).
    Ok(baseline_advanced)
}

/// Starts a first-takeover session (read-only preflight planning by default).
///
/// # Errors
///
/// Session / planning errors. Returns `first_takeover_emitted_delete` when `EnsureAbsent` leaked.
pub fn first_takeover_preflight(
    fence: SyncIdentityFence,
    session_id: &str,
    local: &dyn LocalSyncPort,
    remote: &dyn RemoteSyncPort,
) -> Result<(SyncSession, SyncCycleResult), LomoError> {
    let session = SyncSession::new(fence, SessionKind::FirstTakeover, session_id)?;
    migration_class_preflight(&session, local, remote)
}

/// Starts a migration-class session (read-only preflight; no user-file deletes).
///
/// Symmetric to [`first_takeover_preflight`]: plan-only cycle with empty baseline and a hard
/// post-condition that `ensure_absent_count == 0` (code `migration_emitted_delete` on leak).
///
/// # Errors
///
/// Session / planning errors. Returns `migration_emitted_delete` when `EnsureAbsent` leaked.
pub fn migration_preflight(
    fence: SyncIdentityFence,
    session_id: &str,
    local: &dyn LocalSyncPort,
    remote: &dyn RemoteSyncPort,
) -> Result<(SyncSession, SyncCycleResult), LomoError> {
    let session = SyncSession::new(fence, SessionKind::Migration, session_id)?;
    migration_class_preflight(&session, local, remote)
}

/// Shared migration/takeover-class preflight: plan-only + hard `ensure_absent` == 0 post-condition.
fn migration_class_preflight(
    session: &SyncSession,
    local: &dyn LocalSyncPort,
    remote: &dyn RemoteSyncPort,
) -> Result<(SyncSession, SyncCycleResult), LomoError> {
    debug_assert!(
        session.kind.is_migration_or_takeover_class(),
        "migration_class_preflight requires FirstTakeover or Migration"
    );
    let baseline = BaselineHead::empty();
    let result = run_sync_cycle(session, local, remote, baseline, None, false, None)?;
    if result.batch.ensure_absent_count() != 0 {
        let (code, message) = match session.kind {
            SessionKind::FirstTakeover => (
                "first_takeover_emitted_delete",
                "first-takeover preflight must not emit EnsureAbsent",
            ),
            SessionKind::Migration => (
                "migration_emitted_delete",
                "migration preflight must not emit EnsureAbsent",
            ),
            SessionKind::Incremental => (
                "migration_class_emitted_delete",
                "migration-class preflight must not emit EnsureAbsent",
            ),
        };
        return Err(validation(code, message));
    }
    Ok((session.clone(), result))
}

/// Rejects a batch that already carries `EnsureAbsent` under migration/takeover class.
///
/// Host residual injection forces the `*_emitted_delete` RED path without a planner bug.
/// Production planners must never reach this with non-zero `EnsureAbsent`.
///
/// # Errors
///
/// Validation `first_takeover_emitted_delete` / `migration_emitted_delete` when count ≠ 0.
pub fn reject_if_migration_class_emitted_delete(
    kind: SessionKind,
    batch: &PreparedRemoteBatch,
) -> Result<(), LomoError> {
    if !kind.is_migration_or_takeover_class() {
        return Ok(());
    }
    if batch.ensure_absent_count() == 0 {
        return Ok(());
    }
    let (code, message) = match kind {
        SessionKind::FirstTakeover => (
            "first_takeover_emitted_delete",
            "first-takeover preflight must not emit EnsureAbsent",
        ),
        SessionKind::Migration => (
            "migration_emitted_delete",
            "migration preflight must not emit EnsureAbsent",
        ),
        SessionKind::Incremental => (
            "migration_class_emitted_delete",
            "migration-class preflight must not emit EnsureAbsent",
        ),
    };
    Err(validation(code, message))
}

/// Persists session + runs an apply cycle with verify-before-baseline.
///
/// When the plan emits `OpenConflict`, `conflict_bodies` must supply candidate bytes so the
/// durable session is materialised before baseline can advance.
///
/// # Errors
///
/// Durable / port / hollow-open errors. Verify failure does not advance baseline.
pub fn apply_with_verify(
    paths: &SyncPaths,
    session: &SyncSession,
    local: &dyn LocalSyncPort,
    remote: &dyn RemoteSyncPort,
    baseline: BaselineHead,
    conflict_bodies: Option<&ConflictBodySource>,
) -> Result<SyncCycleResult, LomoError> {
    write_session(paths, session)?;
    run_sync_cycle(
        session,
        local,
        remote,
        baseline,
        Some(paths),
        true,
        conflict_bodies,
    )
}

/// Inspects one dark host plan/readiness cycle from durable `.lomo/sync/v1` only.
///
/// Loads session + baseline, runs a **plan-only** owner cycle against empty hermetic local/remote
/// ports (no publish, no baseline advance, no user-file mutation), then reports intent counts and a
/// disposition. Open conflict paths come from the durable conflict session when present.
///
/// This is the sole coarse cycle entry intended for `BoltFFI` conversion — Kotlin must not re-plan.
/// Host residual deepen (real local/remote snapshots under fakes) uses
/// [`inspect_sync_cycle_plan_with_ports`].
///
/// # Errors
///
/// Validation when the durable session is missing; storage/corruption for unreadable durable state;
/// planner / page-limit errors from the owner cycle.
pub fn inspect_sync_cycle_plan(paths: &SyncPaths) -> Result<SyncCyclePlanSummary, LomoError> {
    // Empty hermetic ports: conversion/readiness only — not provider apply.
    let local = FakeLocalPort {
        entries: Vec::new(),
    };
    let remote = FakeRemotePort::new(
        RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new())?,
        PublishReceipt {
            path_results: Vec::new(),
        },
        VerifiedRemoteState {
            results: Vec::new(),
        },
    );
    inspect_sync_cycle_plan_with_ports(paths, &local, &remote, false, None)
}

/// Host residual cycle entry: plan (and optionally apply) against real local/remote ports under
/// hermetic fakes. Conversion-only `BoltFFI` stays on [`inspect_sync_cycle_plan`] (empty ports).
///
/// Disposition is derived from owner outcomes:
/// - open conflict (plan or durable) → `after_user_action`
/// - apply path with precondition failure or verify failure → `transient` (replan; never overwrite)
/// - idle / plan-only work observed → `after_user_action` (no fixed three-retry)
///
/// # Errors
///
/// Validation when the durable session is missing; storage/corruption; planner / port / hollow-open
/// errors. Verify / precondition failure still returns a summary (disposition `transient`) rather
/// than inventing baseline advance.
pub fn inspect_sync_cycle_plan_with_ports(
    paths: &SyncPaths,
    local: &dyn LocalSyncPort,
    remote: &dyn RemoteSyncPort,
    apply_remote: bool,
    conflict_bodies: Option<&ConflictBodySource>,
) -> Result<SyncCyclePlanSummary, LomoError> {
    if !paths.session.exists() {
        return Err(validation(
            "sync_session_missing",
            "durable sync session is required before cycle plan inspect",
        ));
    }
    let session = read_session(paths)?;
    let baseline = read_baseline(paths)?;

    let result = run_sync_cycle(
        &session,
        local,
        remote,
        baseline,
        Some(paths),
        apply_remote,
        conflict_bodies,
    )?;

    let (open_conflict_paths, conflict_revision) = match read_conflict_session(paths) {
        Ok(conflict) => (
            u32::try_from(conflict.open_count()).map_err(|_overflow| {
                validation(
                    "sync_open_conflict_paths_overflow",
                    "open conflict path count exceeds u32",
                )
            })?,
            Some(conflict.conflict_revision),
        ),
        Err(err) if err.code() == "conflict_session_missing" => (0, None),
        Err(err) => return Err(err),
    };

    let ensure_present_count = count_u32(result.batch.ensure_present_count())?;
    let ensure_absent_count = count_u32(result.batch.ensure_absent_count())?;
    let pull_present_count = count_u32(result.batch.pull_present_count())?;
    let open_conflict_count = count_u32(result.batch.open_conflict_count())?;

    let retry_disposition =
        disposition_for_cycle_result(&result, open_conflict_paths, open_conflict_count);

    Ok(SyncCyclePlanSummary {
        session_id: session.session_id,
        session_kind: session.kind,
        session_revision: session.session_revision,
        baseline_established: result.baseline.is_established(),
        ensure_present_count,
        ensure_absent_count,
        pull_present_count,
        open_conflict_count,
        open_conflict_paths,
        conflict_revision,
        retry_disposition,
    })
}

/// Backend kind for production composition (conversion-friendly string wire).
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SyncBackendKind {
    /// Hermetic in-memory remote (host tests / composition proof without network).
    HermeticFake,
    /// `WebDAV` protocol adapter (`RemoteSyncPort`).
    WebDav,
    /// Path-style S3 protocol adapter (`RemoteSyncPort`).
    S3,
    /// Git remote adapter (`lomo-git` via `RemoteSyncPort`).
    ///
    /// `run_composed_sync_cycle` does **not** construct this adapter (avoids `lomo-sync` → `lomo-git`
    /// cycles). Production/native composition builds the port and calls
    /// [`run_composed_sync_cycle_with_remote_port`].
    Git,
}

/// Non-secret backend configuration for one production cycle composition.
///
/// Secrets are never stored here — callers resolve a process-local secret lease and pass material
/// separately. Git adapter construction lives at the native composition edge (`lomo-git`); this
/// config still carries Git non-secret identity for the durable session fence.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SyncBackendConfig {
    pub kind: SyncBackendKind,
    /// Endpoint / base URL (`WebDAV` / S3) or Git remote URL.
    pub endpoint_url: String,
    /// `WebDAV` username, S3 access key id, or Git HTTPS username (non-secret identity).
    pub username_or_access_key: String,
    /// S3 bucket (required for S3; empty for `WebDAV` / Git / hermetic).
    /// For Git: branch short name when non-empty (default `main` at the composition edge).
    pub bucket: String,
    /// S3 key prefix (optional; empty when unused).
    /// For Git: author name when non-empty (default `Lomo` at the composition edge).
    pub prefix: String,
    /// S3 region (required for S3; empty otherwise).
    /// For Git: author email when non-empty (default `git@lomo.local` at the composition edge).
    pub region: String,
    /// Opaque remote dataset id for the durable identity fence.
    pub remote_dataset_id: String,
}

impl SyncBackendConfig {
    /// Builds a hermetic fake backend config (no network; host composition proof).
    #[must_use]
    pub fn hermetic_fake(remote_dataset_id: impl Into<String>) -> Self {
        Self {
            kind: SyncBackendKind::HermeticFake,
            endpoint_url: String::new(),
            username_or_access_key: String::new(),
            bucket: String::new(),
            prefix: String::new(),
            region: String::new(),
            remote_dataset_id: remote_dataset_id.into(),
        }
    }
}

/// Runs one **production-shaped** owner cycle with real local (store snapshot) + remote ports.
///
/// Composition only: opens `lomo-store` for a generation-fenced local snapshot, ensures a durable
/// session (first-takeover when missing), builds the remote port from [config] + optional secret
/// material, then calls [`inspect_sync_cycle_plan_with_ports`] so disposition remains owner-owned.
///
/// `apply_remote` is product-true for `WebDAV`/S3/Git; hermetic fake defaults to plan-only unless the
/// caller sets `apply_remote` (host proof can set false while still using non-empty local ports).
///
/// Git: this function does **not** construct `lomo-git` (avoids crate cycles). Callers that own the
/// Git adapter (native composition / host contracts) must build the port and call
/// [`run_composed_sync_cycle_with_remote_port`].
///
/// # Errors
///
/// Validation when workspace/config/secret are incomplete; store open / planner / adapter errors.
pub fn run_composed_sync_cycle(
    workspace_root: &std::path::Path,
    config: &SyncBackendConfig,
    secret_material: Option<&[u8]>,
    apply_remote: bool,
) -> Result<SyncCyclePlanSummary, LomoError> {
    if workspace_root.as_os_str().is_empty() {
        return Err(validation(
            "sync_workspace_root_invalid",
            "workspace root must be non-empty for composed cycle",
        ));
    }
    if config.remote_dataset_id.is_empty() || config.remote_dataset_id.len() > 128 {
        return Err(validation(
            "sync_remote_dataset_id_invalid",
            "remote_dataset_id must be 1..=128 bytes",
        ));
    }

    if matches!(config.kind, SyncBackendKind::Git) {
        return Err(validation(
            "sync_git_compose_via_remote_port",
            "git composition builds lomo-git at the native edge; use run_composed_sync_cycle_with_remote_port",
        ));
    }

    // Real local port: store coarse snapshot (path/digest/generation only).
    let store = lomo_store::Store::open(workspace_root)?;
    let snap = store.snapshot_sync_view()?;
    if snap.workspace_generation.is_empty() {
        return Err(validation(
            "local_snapshot_generation_empty",
            "store local snapshot requires a workspace generation fence",
        ));
    }
    let local = StoreLocalSnapshotPort::from_store_snapshot(
        &snap.workspace_generation,
        snap.entries
            .iter()
            .map(|entry| (entry.path.clone(), entry.digest.clone())),
    )?;

    let paths = SyncPaths::for_workspace(workspace_root);
    ensure_session_for_composition(&paths, &snap.workspace_generation, config)?;
    run_composed_with_remote(
        workspace_root,
        &paths,
        &local,
        config,
        secret_material,
        apply_remote,
    )
}

/// Runs one production-shaped owner cycle with a **caller-provided** remote port.
///
/// Used by native Git composition (`lomo-git` constructed outside `lomo-sync`) and hermetic host
/// contracts that already hold a `RemoteSyncPort`. Opens the store local snapshot, ensures the
/// durable session fence from [config] identity, then runs the owner cycle.
///
/// # Errors
///
/// Validation when workspace/config are incomplete; store open / planner / port errors.
pub fn run_composed_sync_cycle_with_remote_port(
    workspace_root: &std::path::Path,
    config: &SyncBackendConfig,
    remote: &dyn RemoteSyncPort,
    apply_remote: bool,
) -> Result<SyncCyclePlanSummary, LomoError> {
    if workspace_root.as_os_str().is_empty() {
        return Err(validation(
            "sync_workspace_root_invalid",
            "workspace root must be non-empty for composed cycle",
        ));
    }
    if config.remote_dataset_id.is_empty() || config.remote_dataset_id.len() > 128 {
        return Err(validation(
            "sync_remote_dataset_id_invalid",
            "remote_dataset_id must be 1..=128 bytes",
        ));
    }

    let store = lomo_store::Store::open(workspace_root)?;
    let snap = store.snapshot_sync_view()?;
    if snap.workspace_generation.is_empty() {
        return Err(validation(
            "local_snapshot_generation_empty",
            "store local snapshot requires a workspace generation fence",
        ));
    }
    let local = StoreLocalSnapshotPort::from_store_snapshot(
        &snap.workspace_generation,
        snap.entries
            .iter()
            .map(|entry| (entry.path.clone(), entry.digest.clone())),
    )?;
    let paths = SyncPaths::for_workspace(workspace_root);
    ensure_session_for_composition(&paths, &snap.workspace_generation, config)?;
    inspect_sync_cycle_plan_with_ports(&paths, &local, remote, apply_remote, None)
}

fn run_composed_with_remote(
    workspace_root: &std::path::Path,
    paths: &SyncPaths,
    local: &dyn LocalSyncPort,
    config: &SyncBackendConfig,
    secret_material: Option<&[u8]>,
    apply_remote: bool,
) -> Result<SyncCyclePlanSummary, LomoError> {
    match config.kind {
        SyncBackendKind::HermeticFake => {
            // Non-empty ports: local is real store; remote is hermetic empty complete listing.
            // This is the host proof that production composition is not empty-port inspect.
            let remote = FakeRemotePort::new(
                RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new())?,
                PublishReceipt {
                    path_results: Vec::new(),
                },
                VerifiedRemoteState {
                    results: Vec::new(),
                },
            );
            inspect_sync_cycle_plan_with_ports(paths, local, &remote, apply_remote, None)
        }
        SyncBackendKind::WebDav => {
            let password = secret_utf8(secret_material, "webdav_secret_required")?;
            if config.endpoint_url.is_empty() || config.username_or_access_key.is_empty() {
                return Err(validation(
                    "webdav_config_incomplete",
                    "webdav endpoint_url and username are required",
                ));
            }
            let temp_dir = paths.root.join("tmp");
            std::fs::create_dir_all(&temp_dir).map_err(|err| {
                crate::error::storage(
                    "webdav_temp_dir",
                    &format!("failed to create webdav temp dir: {err}"),
                )
            })?;
            let objects =
                crate::webdav::WorkspaceFileObjectSource::new(workspace_root.to_path_buf());
            let remote = crate::webdav::connect_workspace_webdav(
                &config.endpoint_url,
                &config.username_or_access_key,
                password,
                &temp_dir,
                objects,
                std::time::Duration::from_secs(30),
            )?;
            inspect_sync_cycle_plan_with_ports(paths, local, &remote, apply_remote, None)
        }
        SyncBackendKind::S3 => {
            let secret = secret_utf8(secret_material, "s3_secret_required")?;
            if config.endpoint_url.is_empty()
                || config.username_or_access_key.is_empty()
                || config.bucket.is_empty()
                || config.region.is_empty()
            {
                return Err(validation(
                    "s3_config_incomplete",
                    "s3 endpoint_url, access_key_id, bucket, and region are required",
                ));
            }
            let temp_dir = paths.root.join("tmp");
            std::fs::create_dir_all(&temp_dir).map_err(|err| {
                crate::error::storage(
                    "s3_temp_dir",
                    &format!("failed to create s3 temp dir: {err}"),
                )
            })?;
            let objects = crate::s3::WorkspaceFileObjectSource::new(workspace_root.to_path_buf());
            let remote = crate::s3::connect_workspace_s3(
                &config.endpoint_url,
                &config.bucket,
                &config.prefix,
                &config.region,
                &config.username_or_access_key,
                secret,
                &temp_dir,
                objects,
                std::time::Duration::from_secs(30),
            )?;
            inspect_sync_cycle_plan_with_ports(paths, local, &remote, apply_remote, None)
        }
        SyncBackendKind::Git => Err(validation(
            "sync_git_compose_via_remote_port",
            "git composition builds lomo-git at the native edge; use run_composed_sync_cycle_with_remote_port",
        )),
    }
}

fn secret_utf8<'a>(
    secret_material: Option<&'a [u8]>,
    missing_code: &'static str,
) -> Result<&'a str, LomoError> {
    let bytes = secret_material.ok_or_else(|| {
        validation(
            missing_code,
            "secret material lease is required for this backend",
        )
    })?;
    if bytes.is_empty() {
        return Err(validation(
            missing_code,
            "secret material must be non-empty",
        ));
    }
    std::str::from_utf8(bytes).map_err(|_err| {
        validation(
            "sync_secret_not_utf8",
            "secret material must be valid UTF-8 for protocol credentials",
        )
    })
}

fn ensure_session_for_composition(
    paths: &SyncPaths,
    workspace_generation: &str,
    config: &SyncBackendConfig,
) -> Result<(), LomoError> {
    if paths.session.exists() {
        return Ok(());
    }
    let generation = lomo_workspace::WorkspaceGenerationId::parse(workspace_generation)?;
    let dataset = lomo_workspace::RemoteDatasetId::parse(&config.remote_dataset_id)?;
    // Canonical identity: backend kind + endpoint + non-secret identity fields (never secret bytes).
    let canonical = format!(
        "kind={:?}\nendpoint={}\nuser={}\nbucket={}\nprefix={}\nregion={}\ndataset={}\n",
        config.kind,
        config.endpoint_url,
        config.username_or_access_key,
        config.bucket,
        config.prefix,
        config.region,
        config.remote_dataset_id,
    );
    let identity =
        lomo_workspace::RemoteIdentityDigest::from_canonical_config_bytes(canonical.as_bytes());
    let fence = SyncIdentityFence::from_parts(&generation, &dataset, &identity);
    let session_id = format!("cycle-{}", config.remote_dataset_id);
    let session = SyncSession::new(fence, SessionKind::FirstTakeover, session_id)?;
    write_session(paths, &session)
}

fn disposition_for_cycle_result(
    result: &SyncCycleResult,
    open_conflict_paths: u32,
    open_conflict_count: u32,
) -> &'static str {
    if open_conflict_paths > 0 || open_conflict_count > 0 {
        return "after_user_action";
    }
    if let Some(receipt) = result.receipt.as_ref()
        && PreparedRemoteBatch::receipt_requires_replan(receipt)
    {
        return "transient";
    }
    if let Some(verified) = result.verified.as_ref()
        && !verified.all_verified()
        && !verified.results.is_empty()
    {
        return "transient";
    }
    // Plan-only work, idle, or successful apply: no fixed three-retry thrash.
    "after_user_action"
}

fn count_u32(value: usize) -> Result<u32, LomoError> {
    u32::try_from(value).map_err(|_overflow| {
        validation(
            "sync_cycle_count_overflow",
            "cycle plan count exceeds u32 wire limit",
        )
    })
}
