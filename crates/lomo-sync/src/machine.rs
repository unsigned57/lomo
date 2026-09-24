//! Unified provider-neutral sync state machine (P5-03 host hermetic slice).
//!
//! Pipeline: `RemoteSnapshot` → `ProviderNeutralIntent` → `PreparedRemoteBatch` →
//! `PublishReceipt` → `VerifiedRemoteState`. Baseline advances only after verify success.

use std::collections::{BTreeMap, BTreeSet};

use crate::conflict::{
    ConflictBodySource, ConflictPathStatus, ConflictSession, ConflictSessionState,
    apply_resolved_conflicts_remote, load_conflict_bodies_for_open_intents,
    load_conflict_bodies_for_open_pages, materialize_conflicts_from_intent_pages,
    materialize_conflicts_from_plan, may_advance_baseline_for_path, read_conflict_session_state,
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
    BatchAtomicity, ContentDigest, HoldReason, PreparedRemoteBatch, ProviderNeutralIntent,
    PublishReceipt, RemoteDigestFact, RemotePathEntry, RemotePublishContract, RemoteSnapshot,
    SnapshotCompleteness, SyncPath, VerifiedRemoteState, VerifyExpectation, VerifyStatus,
};
use crate::ports::{
    FakeLocalPort, FakeRemotePort, LocalSnapshot, LocalSyncPort, RemoteResolvedObject,
    RemoteSyncPort, StoreLocalSnapshotPort,
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
    /// Local projection entries the cycle observed (durable status fact).
    pub local_entry_count: u32,
    /// Remote listing entries the cycle observed across all pages.
    pub remote_listed_count: u32,
    /// Baseline entries after this cycle's advancement (synced-path count).
    pub baseline_entry_count: u32,
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
    /// Mutations held because the provider gave no strong conditional-update validator.
    pub hold_count: u32,
    /// Open paths still needing user attention on the durable conflict session (0 when absent).
    pub open_conflict_paths: u32,
    /// Conflict session revision when a durable conflict head exists.
    pub conflict_revision: Option<u64>,
    /// WorkManager-facing disposition name owned by Rust (`never` / `after_user_action` / `transient`).
    pub retry_disposition: &'static str,
    /// Intent pages published + verified this cycle (0 for plan-only).
    pub pages_applied: u32,
    /// True when this cycle advanced the durable baseline.
    pub baseline_advanced: bool,
    /// Local projection entries the cycle observed.
    pub local_entry_count: u32,
    /// Remote listing entries the cycle observed across all pages.
    pub remote_listed_count: u32,
    /// Baseline entries after this cycle's advancement.
    pub baseline_entry_count: u32,
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
    /// Remote listing facts retained only for paths that opened a conflict (page-bounded, not a
    /// full listing snapshot).
    pub conflict_remote_entries: Vec<RemotePathEntry>,
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

    /// True when every intent page respects its atomicity ceiling.
    #[must_use]
    pub fn pages_within_limit(&self) -> bool {
        self.intent_pages.iter().all(|page| {
            let ceiling = match page.atomicity {
                BatchAtomicity::PerPath => MAX_ACTION_PAGE_ITEMS,
                BatchAtomicity::WholeBatchRef => crate::limits::MAX_WHOLE_BATCH_INTENTS,
            };
            page.intents.len() <= ceiling
        })
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
    plan_intents_with_atomicity(
        session_kind,
        local,
        remote,
        baseline,
        tombstones,
        RemotePublishContract::per_path(),
    )
}

/// Plans intents using the adapter's [`RemotePublishContract`] (atomicity + snapshot CAS token).
///
/// Whole-batch Git CAS compares branch tip to branch tip. Path blob OIDs stay on each intent and
/// never become the snapshot validator.
///
/// # Errors
///
/// Validation when page limits fail inside [`PreparedRemoteBatch::with_snapshot_token`].
pub fn plan_intents_with_atomicity(
    session_kind: SessionKind,
    local: &LocalSnapshot,
    remote: &RemoteSnapshot,
    baseline: &BaselineHead,
    tombstones: &TombstoneSet,
    contract: RemotePublishContract,
) -> Result<PreparedRemoteBatch, LomoError> {
    let local_map: BTreeMap<&str, &ContentDigest> = local
        .entries
        .iter()
        .map(|entry| (entry.path.as_str(), &entry.digest))
        .collect();
    let remote_paths: BTreeSet<&str> = remote
        .entries
        .iter()
        .map(|entry| entry.path.as_str())
        .collect();

    let may_delete = session_kind.may_emit_user_file_delete()
        && matches!(remote.completeness, SnapshotCompleteness::Complete)
        && baseline.is_established();
    let per_path_cas = matches!(contract.atomicity, BatchAtomicity::PerPath);
    let mut intents = Vec::new();
    let mut seen: BTreeSet<String> = BTreeSet::new();

    // Remote paths: pull / conflict / baseline-match / tombstone gates.
    for entry in &remote.entries {
        seen.insert(entry.path.as_str().to_owned());
        if let Some(intent) = plan_remote_entry(
            entry,
            &local_map,
            baseline,
            tombstones,
            may_delete,
            per_path_cas,
        )? {
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
        if remote_paths.contains(path_s) {
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
        if remote_paths.contains(base_entry.path.as_str()) {
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

    PreparedRemoteBatch::from_contract(contract, intents)
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
    plan_intents_streaming_with_atomicity(
        session_kind,
        local,
        remote_pages,
        overall_completeness,
        baseline,
        tombstones,
        RemotePublishContract::per_path(),
    )
}

/// Streaming plan using the adapter's publish atomicity.
///
/// [`BatchAtomicity::WholeBatchRef`] emits one batch (Git tree CAS) instead of 512-intent pages.
///
/// # Errors
///
/// Same as [`plan_intents_streaming`].
pub fn plan_intents_streaming_with_atomicity<I>(
    session_kind: SessionKind,
    local: &LocalSnapshot,
    remote_pages: I,
    overall_completeness: SnapshotCompleteness,
    baseline: &BaselineHead,
    tombstones: &TombstoneSet,
    contract: RemotePublishContract,
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
    let per_path_cas = matches!(contract.atomicity, BatchAtomicity::PerPath);

    let mut remote_path_keys: BTreeSet<String> = BTreeSet::new();
    let mut intents: Vec<ProviderNeutralIntent> = Vec::new();
    let mut conflict_remote_entries: Vec<RemotePathEntry> = Vec::new();
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
            if let Some(intent) = plan_remote_entry(
                entry,
                &local_map,
                baseline,
                tombstones,
                may_delete,
                per_path_cas,
            )? {
                push_streaming_intent(
                    &mut intents,
                    intent,
                    Some(entry),
                    &mut conflict_remote_entries,
                )?;
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
        push_streaming_intent(
            &mut intents,
            ProviderNeutralIntent::EnsurePresent {
                path: entry.path.clone(),
                digest: entry.digest.clone(),
                expected_remote_token: None,
            },
            None,
            &mut conflict_remote_entries,
        )?;
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
            push_streaming_intent(&mut intents, intent, None, &mut conflict_remote_entries)?;
        }
    }

    let intent_pages = split_intents_into_pages(&intents, contract)?;
    Ok(StreamingPlanOutcome {
        intent_pages,
        remote_path_key_count: remote_path_keys.len(),
        peak_remote_page_entries,
        completeness: overall_completeness,
        conflict_remote_entries,
    })
}

fn push_streaming_intent(
    intents: &mut Vec<ProviderNeutralIntent>,
    intent: ProviderNeutralIntent,
    listing_entry: Option<&RemotePathEntry>,
    conflict_remote_entries: &mut Vec<RemotePathEntry>,
) -> Result<(), LomoError> {
    if intents.len() >= MAX_STREAMING_INTERMEDIATE_INTENTS {
        return Err(resource_limit(
            "streaming_intermediate_intents_too_large",
            "streaming intermediate intent accumulation exceeds the path-key ceiling",
        ));
    }
    if matches!(intent, ProviderNeutralIntent::OpenConflict { .. })
        && let Some(entry) = listing_entry
    {
        conflict_remote_entries.push(entry.clone());
    }
    intents.push(intent);
    Ok(())
}

fn split_intents_into_pages(
    intents: &[ProviderNeutralIntent],
    contract: RemotePublishContract,
) -> Result<Vec<PreparedRemoteBatch>, LomoError> {
    if intents.is_empty() {
        return Ok(Vec::new());
    }
    match contract.atomicity {
        BatchAtomicity::WholeBatchRef => Ok(vec![PreparedRemoteBatch::from_contract(
            contract,
            intents.to_vec(),
        )?]),
        BatchAtomicity::PerPath => {
            let mut pages = Vec::new();
            for chunk in intents.chunks(MAX_ACTION_PAGE_ITEMS) {
                pages.push(PreparedRemoteBatch::from_contract(
                    RemotePublishContract::per_path(),
                    chunk.to_vec(),
                )?);
            }
            Ok(pages)
        }
    }
}

/// True when the listing proves the remote still holds baseline bytes: either the strong validator
/// still equals the durable baseline token, or a listing-proven digest equals the baseline digest.
fn remote_entry_proven_unchanged(entry: &RemotePathEntry, baseline: &BaselineHead) -> bool {
    let Some(base) = baseline.get(entry.path.as_str()) else {
        return false;
    };
    if entry
        .validator
        .strong_token()
        .is_some_and(|token| token == base.remote_token.as_str())
    {
        return true;
    }
    entry
        .digest
        .known()
        .is_some_and(|digest| digest.as_str() == base.digest.as_str())
}

/// True when nothing needs doing: remote proven at baseline **and** local still at baseline.
fn remote_path_in_sync(
    entry: &RemotePathEntry,
    local_map: &BTreeMap<&str, &ContentDigest>,
    baseline: &BaselineHead,
) -> bool {
    let Some(base) = baseline.get(entry.path.as_str()) else {
        return false;
    };
    entry
        .validator
        .strong_token()
        .is_some_and(|token| token == base.remote_token.as_str())
        && local_map
            .get(entry.path.as_str())
            .is_some_and(|digest| digest.as_str() == base.digest.as_str())
}

fn unresolved_remote_digest() -> LomoError {
    validation(
        "remote_digest_unresolved",
        "remote listing left a digest unresolved where a byte-level decision requires it",
    )
}

/// Conditional update for an existing remote path. Per-path CAS requires a strong validator;
/// without one the mutation is held instead of risking an unconditional overwrite. Whole-batch
/// providers (Git) cover the write with the snapshot-level CAS, so the path token is informational.
fn ensure_present_intent(
    entry: &RemotePathEntry,
    local_digest: &ContentDigest,
    per_path_cas: bool,
) -> ProviderNeutralIntent {
    if per_path_cas && entry.validator.strong_token().is_none() {
        return ProviderNeutralIntent::Hold {
            path: entry.path.clone(),
            reason: HoldReason::ConditionalUpdateUnsupported,
        };
    }
    ProviderNeutralIntent::EnsurePresent {
        path: entry.path.clone(),
        digest: local_digest.clone(),
        expected_remote_token: entry.validator.strong_token().map(str::to_owned),
    }
}

/// Conditional delete for an existing remote path: same strong-validator rule as
/// [`ensure_present_intent`].
fn ensure_absent_intent(entry: &RemotePathEntry, per_path_cas: bool) -> ProviderNeutralIntent {
    let token = entry.validator.strong_token();
    if per_path_cas && token.is_none() {
        return ProviderNeutralIntent::Hold {
            path: entry.path.clone(),
            reason: HoldReason::ConditionalUpdateUnsupported,
        };
    }
    ProviderNeutralIntent::EnsureAbsent {
        path: entry.path.clone(),
        expected_remote_token: token.unwrap_or("").to_owned(),
    }
}

fn plan_remote_entry(
    entry: &RemotePathEntry,
    local_map: &BTreeMap<&str, &ContentDigest>,
    baseline: &BaselineHead,
    tombstones: &TombstoneSet,
    may_delete: bool,
    per_path_cas: bool,
) -> Result<Option<ProviderNeutralIntent>, LomoError> {
    let path = entry.path.as_str();
    // Unrecognized remote paths are report-only: never pull, move, or delete (SB-08).
    if !crate::pipeline::is_owned_sync_user_path(path) {
        return Ok(Some(ProviderNeutralIntent::ReportUnrecognized {
            path: entry.path.clone(),
        }));
    }
    let observed_token = entry.validator.token().map(str::to_owned);
    if let Some(tombstone) = tombstones.get(path) {
        let remote_digest = entry.digest.known().ok_or_else(unresolved_remote_digest)?;
        if tombstone.content_digest == remote_digest.as_str() {
            return Ok(may_delete.then(|| ensure_absent_intent(entry, per_path_cas)));
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
            remote_digest: remote_digest.clone(),
            baseline_digest,
        }));
    }

    let Some(local_digest) = local_map.get(path) else {
        return Ok(Some(ProviderNeutralIntent::PullPresent {
            path: entry.path.clone(),
            digest: entry
                .digest
                .known()
                .ok_or_else(unresolved_remote_digest)?
                .clone(),
            remote_token: entry.validator.strong_token().map(str::to_owned),
        }));
    };

    let baseline_digest = baseline
        .get(path)
        .map(|base| ContentDigest::parse(&base.digest))
        .transpose()?;
    if remote_entry_proven_unchanged(entry, baseline) {
        // Remote still holds baseline bytes: local-at-baseline is in sync, anything else is a
        // local-side update under a conditional write.
        return Ok(match baseline_digest {
            Some(base) if base.as_str() == local_digest.as_str() => None,
            _ => Some(ensure_present_intent(entry, local_digest, per_path_cas)),
        });
    }

    // Remote is not proven at baseline: byte-level facts decide pull vs conflict vs update.
    let remote_digest = entry.digest.known().ok_or_else(unresolved_remote_digest)?;
    if local_digest.as_str() == remote_digest.as_str() {
        return Ok(None);
    }
    if baseline_digest
        .as_ref()
        .is_some_and(|base| base.as_str() == local_digest.as_str())
    {
        return Ok(Some(ProviderNeutralIntent::PullPresent {
            path: entry.path.clone(),
            digest: remote_digest.clone(),
            remote_token: observed_token.filter(|_| entry.validator.strong_token().is_some()),
        }));
    }
    Ok(Some(ProviderNeutralIntent::OpenConflict {
        path: entry.path.clone(),
        local_digest: (*local_digest).clone(),
        remote_digest: remote_digest.clone(),
        baseline_digest,
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
    if apply_remote && let Some(sync_paths) = paths {
        execute_pending_resolved_remote_apply(sync_paths, remote)?;
    }
    let local_snap = local.snapshot()?;
    let mut remote_snap = remote.list_remote()?;
    let mut resolved = ResolvedObjectCache::new(remote);
    resolve_listing_digests(
        &mut resolved,
        remote_snap.entries.iter_mut(),
        &local_snap,
        &baseline,
    )?;
    let remote = &resolved;
    let mut tombstones = paths
        .map(crate::durable::read_tombstones)
        .transpose()?
        .unwrap_or_else(TombstoneSet::empty);
    if let Some(sync_paths) = paths {
        record_observed_user_deletes(
            session,
            sync_paths,
            &local_snap,
            &remote_snap.entries,
            remote_snap.completeness,
            &baseline,
            &mut tombstones,
        )?;
    }

    let recovery_intents = collect_pending_delete_recovery(
        session,
        &local_snap,
        &remote_snap,
        &baseline,
        &tombstones,
    )?;
    let mut batch = plan_intents_with_atomicity(
        session.kind,
        &local_snap,
        &remote_snap,
        &baseline,
        &tombstones,
        RemotePublishContract::from_atomicity(
            remote.batch_atomicity(),
            remote_snap.snapshot_revision.clone(),
        ),
    )?;
    merge_recovery_ensure_absent(&mut batch, recovery_intents);

    let loaded_bodies = owned_conflict_bodies_for_batch(conflict_bodies, paths, &batch, remote)?;
    let bodies = conflict_bodies.or(loaded_bodies.as_ref());
    let conflict_session =
        materialize_or_load_conflict_session(session, paths, &batch, &remote_snap, bodies)?;

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
/// Conflict materialize walks **each intent page** and writes `OpenConflict` paths into one durable
/// `ConflictSession`. Remote tokens come from conflict-path listing facts retained during the
/// stream (O(conflicts), never a multi-page listing `RemoteSnapshot`). Hollow open still fails
/// closed when bodies are required and missing (no workspace file and no remote object). When
/// `conflict_bodies` is `None`, `OpenConflict` loads from the Direct workspace and
/// [`RemoteSyncPort::load_object`]. When `apply_remote` is set, pending `KeepLocal` / Merged
/// resolutions publish through [`apply_resolved_conflicts_remote`] before the new plan.
///
/// # Errors
///
/// Port / planning / durable write / hollow-open / resource-limit errors.
pub fn run_sync_cycle_streaming(
    session: &SyncSession,
    local: &dyn LocalSyncPort,
    remote: &dyn RemoteSyncPort,
    mut baseline: BaselineHead,
    paths: Option<&SyncPaths>,
    apply_remote: bool,
    conflict_bodies: Option<&ConflictBodySource>,
) -> Result<StreamingSyncCycleResult, LomoError> {
    if apply_remote && let Some(sync_paths) = paths {
        execute_pending_resolved_remote_apply(sync_paths, remote)?;
    }
    let local_snap = local.snapshot()?;
    let local_entry_count = count_u32(local_snap.entries.len())?;
    let facts = observe_streaming_listing(remote, &local_snap, &baseline)?;
    let remote_listed_count = facts.listed_count_u32()?;
    let listing = facts.listing;
    let overall_completeness = listing.overall_completeness;
    let publish_contract = facts.publish_contract;
    let remote_view = facts.remote_view;
    let resolved = facts.resolved;
    let remote = &resolved;
    let mut tombstones = paths
        .map(crate::durable::read_tombstones)
        .transpose()?
        .unwrap_or_else(TombstoneSet::empty);
    if let Some(sync_paths) = paths {
        record_observed_user_deletes(
            session,
            sync_paths,
            &local_snap,
            listing.pages.iter().flatten(),
            overall_completeness,
            &baseline,
            &mut tombstones,
        )?;
    }

    let plan = plan_intents_streaming_with_atomicity(
        session.kind,
        &local_snap,
        listing.into_page_iter(),
        overall_completeness,
        &baseline,
        &tombstones,
        publish_contract.clone(),
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

    let first_page_batch = first_streaming_batch(&plan, publish_contract, recovery_intents)?;

    let loaded_bodies =
        owned_conflict_bodies_for_streaming_plan(conflict_bodies, paths, &plan, remote)?;
    let bodies = conflict_bodies.or(loaded_bodies.as_ref());
    let conflict_session =
        materialize_or_load_streaming_conflict_session(session, paths, &plan, bodies)?;

    if !apply_remote {
        return Ok(StreamingSyncCycleResult {
            plan,
            first_page_batch,
            pages_applied: 0,
            receipt: None,
            verified: None,
            baseline_advanced: false,
            baseline_entry_count: count_u32(baseline.entries.len())?,
            baseline,
            conflict_session,
            local_entry_count,
            remote_listed_count,
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
        baseline_entry_count: count_u32(baseline.entries.len())?,
        baseline,
        conflict_session,
        local_entry_count,
        remote_listed_count,
    })
}

/// First apply batch: the plan's first intent page (or an empty contract batch) with pending
/// delete-recovery `EnsureAbsent` intents merged in.
fn first_streaming_batch(
    plan: &StreamingPlanOutcome,
    publish_contract: RemotePublishContract,
    recovery_intents: Vec<ProviderNeutralIntent>,
) -> Result<PreparedRemoteBatch, LomoError> {
    match plan.intent_pages.first() {
        None => {
            let mut empty = PreparedRemoteBatch::from_contract(publish_contract, Vec::new())?;
            merge_recovery_ensure_absent(&mut empty, recovery_intents);
            Ok(empty)
        }
        Some(first) => {
            let mut first = first.clone();
            merge_recovery_ensure_absent(&mut first, recovery_intents);
            Ok(first)
        }
    }
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

    if let Some(paths) = request.paths {
        crate::cycle_state::note_sync_cycle_applying(paths)?;
    }
    for batch in &batches_to_apply {
        if let Some(paths) = request.paths
            && crate::cycle_state::sync_cycle_cancel_requested(paths)?
        {
            crate::cycle_state::mark_sync_cycle_cancelled(paths, pages_applied)?;
            return Err(crate::error::cancelled(
                crate::cycle_state::CYCLE_CANCELLED_CODE,
                "durable cancel request observed between publication pages",
            ));
        }
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

/// Listing facts collected before the streaming plan: the (digest-resolved) pages, the first-page
/// remote view, the publish contract, and the resolved-body cache that backs `load_object`.
struct StreamingListingFacts<'r> {
    listing: crate::ports::RemoteListingStream,
    remote_view: RemoteSnapshot,
    publish_contract: RemotePublishContract,
    resolved: ResolvedObjectCache<'r>,
}

impl StreamingListingFacts<'_> {
    /// Total remote entries across all listing pages (durable status fact).
    fn listed_count_u32(&self) -> Result<u32, LomoError> {
        count_u32(self.listing.pages.iter().flatten().count())
    }
}

/// Lists remote pages and resolves metadata-only digests for the paths the planner must decide
/// on bytes (see [`resolve_listing_digests`] for the skip rule).
fn observe_streaming_listing<'r>(
    remote: &'r dyn RemoteSyncPort,
    local_snap: &LocalSnapshot,
    baseline: &BaselineHead,
) -> Result<StreamingListingFacts<'r>, LomoError> {
    let mut listing = remote.list_remote_pages()?;
    let publish_contract = RemotePublishContract::from_atomicity(
        remote.batch_atomicity(),
        listing.snapshot_revision.clone(),
    );
    // Resolve metadata-only listing digests for the paths the planner must decide on bytes.
    let mut resolved = ResolvedObjectCache::new(remote);
    resolve_listing_digests(
        &mut resolved,
        listing.pages.iter_mut().flatten(),
        local_snap,
        baseline,
    )?;
    // First page view only for conflict materialize / same-byte verify helpers (page-bounded).
    // Empty listing → empty entries (domain-empty, not a silent default of missing data).
    let first_page_entries = listing.pages.first().cloned().unwrap_or_else(Vec::new);
    let remote_view = RemoteSnapshot {
        completeness: listing.overall_completeness,
        entries: first_page_entries,
        snapshot_revision: publish_contract.snapshot_revision.clone(),
    };
    Ok(StreamingListingFacts {
        listing,
        remote_view,
        publish_contract,
        resolved,
    })
}

/// Tombstone-first recording for observed user deletes: a baseline-tracked remote path that is
/// locally absent is a user delete candidate. The durable tombstone is written **before** the
/// `EnsureAbsent` enters the plan, so a crash between tombstone and remote delete is recovered by
/// [`collect_pending_delete_recovery`].
///
/// Gate rejections ([`lomo_core::ErrorCategory::Validation`]) are a defined domain outcome — no
/// delete authority without baseline proof, complete listing, and matching remote token — so the
/// planner's `PullPresent` fallback stands and no tombstone is written. Non-validation failures
/// (e.g. tombstone persistence) propagate: a tombstone that cannot persist must not silently
/// degrade into a pull.
fn record_observed_user_deletes<'a, I>(
    session: &SyncSession,
    paths: &SyncPaths,
    local_snap: &LocalSnapshot,
    remote_entries: I,
    remote_completeness: SnapshotCompleteness,
    baseline: &BaselineHead,
    tombstones: &mut TombstoneSet,
) -> Result<(), LomoError>
where
    I: IntoIterator<Item = &'a RemotePathEntry>,
{
    for entry in remote_entries {
        let path = entry.path.as_str();
        if !crate::pipeline::is_owned_sync_user_path(path)
            || tombstones.contains_path(path)
            || local_snap
                .entries
                .iter()
                .any(|local| local.path.as_str() == path)
        {
            continue;
        }
        let Some(base) = baseline.get(path) else {
            continue;
        };
        let baseline_digest = ContentDigest::parse(&base.digest)?;
        match crate::recovery::record_user_delete_tombstone_first(
            &crate::recovery::UserDeleteRequest {
                paths,
                fence: &session.fence,
                baseline,
                session_kind: session.kind,
                remote_completeness,
                path: &entry.path,
                local_has_path: false,
                observed_remote_token: entry.validator.strong_token(),
                content_digest: &baseline_digest,
            },
        ) {
            Ok(_) => tombstones.upsert(
                path,
                &session.fence.remote_dataset_id,
                baseline_digest.as_str(),
            ),
            Err(error) if error.category() == lomo_core::ErrorCategory::Validation => {}
            Err(error) => return Err(error),
        }
    }
    Ok(())
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
        let remote_digest = remote_entry.and_then(|remote| remote.digest.known());
        let remote_token = remote_entry.and_then(|remote| remote.validator.strong_token());
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
            | ProviderNeutralIntent::ReportUnrecognized { .. }
            | ProviderNeutralIntent::Hold { .. } => continue,
        };
        let already = batch.intents.iter().any(|existing| match existing {
            ProviderNeutralIntent::EnsureAbsent { path, .. } => path.as_str() == path_s,
            ProviderNeutralIntent::EnsurePresent { .. }
            | ProviderNeutralIntent::PullPresent { .. }
            | ProviderNeutralIntent::OpenConflict { .. }
            | ProviderNeutralIntent::ReportUnrecognized { .. }
            | ProviderNeutralIntent::Hold { .. } => false,
        });
        if !already {
            batch.intents.push(intent);
        }
    }
}

fn owned_conflict_bodies_for_streaming_plan(
    injected: Option<&ConflictBodySource>,
    paths: Option<&SyncPaths>,
    plan: &StreamingPlanOutcome,
    remote: &dyn RemoteSyncPort,
) -> Result<Option<ConflictBodySource>, LomoError> {
    if injected.is_some() {
        return Ok(None);
    }
    let Some(sync_paths) = paths else {
        return Ok(None);
    };
    if plan.open_conflict_count() == 0 {
        return Ok(None);
    }
    let planned = planned_open_conflict_paths(
        plan.intent_pages
            .iter()
            .flat_map(|page| page.intents.iter()),
    );
    if existing_session_covers_open_paths(sync_paths, &planned)? {
        return Ok(None);
    }
    Ok(Some(load_conflict_bodies_for_open_pages(
        &sync_paths.workspace_root,
        &plan.intent_pages,
        remote,
    )?))
}

fn owned_conflict_bodies_for_batch(
    injected: Option<&ConflictBodySource>,
    paths: Option<&SyncPaths>,
    batch: &PreparedRemoteBatch,
    remote: &dyn RemoteSyncPort,
) -> Result<Option<ConflictBodySource>, LomoError> {
    if injected.is_some() {
        return Ok(None);
    }
    let Some(sync_paths) = paths else {
        return Ok(None);
    };
    if batch.open_conflict_count() == 0 {
        return Ok(None);
    }
    let planned = planned_open_conflict_paths(batch.intents.iter());
    if existing_session_covers_open_paths(sync_paths, &planned)? {
        return Ok(None);
    }
    Ok(Some(load_conflict_bodies_for_open_intents(
        &sync_paths.workspace_root,
        &batch.intents,
        remote,
    )?))
}

fn execute_pending_resolved_remote_apply(
    paths: &SyncPaths,
    remote: &dyn RemoteSyncPort,
) -> Result<(), LomoError> {
    let ConflictSessionState::Present(session) = read_conflict_session_state(paths)? else {
        return Ok(());
    };
    let needs_remote = session.paths.iter().any(|record| {
        matches!(
            record.status,
            ConflictPathStatus::ResolvedKeepLocal | ConflictPathStatus::ResolvedMerged
        )
    });
    if !needs_remote {
        return Ok(());
    }
    let baseline = read_baseline(paths)?;
    apply_resolved_conflicts_remote(paths, session.conflict_revision, remote, baseline)?;
    Ok(())
}

fn planned_open_conflict_paths<'a>(
    intents: impl Iterator<Item = &'a ProviderNeutralIntent>,
) -> BTreeSet<&'a str> {
    intents
        .filter_map(|intent| match intent {
            ProviderNeutralIntent::OpenConflict { path, .. } => Some(path.as_str()),
            ProviderNeutralIntent::EnsurePresent { .. }
            | ProviderNeutralIntent::EnsureAbsent { .. }
            | ProviderNeutralIntent::PullPresent { .. }
            | ProviderNeutralIntent::ReportUnrecognized { .. }
            | ProviderNeutralIntent::Hold { .. } => None,
        })
        .collect()
}

fn session_covers_open_paths(session: &ConflictSession, planned: &BTreeSet<&str>) -> bool {
    if planned.is_empty() {
        return false;
    }
    let existing: BTreeSet<&str> = session
        .paths
        .iter()
        .map(|record| record.path.as_str())
        .collect();
    planned.iter().all(|path| existing.contains(path))
}

fn existing_session_covers_open_paths(
    paths: &SyncPaths,
    planned: &BTreeSet<&str>,
) -> Result<bool, LomoError> {
    match read_conflict_session_state(paths)? {
        ConflictSessionState::Present(existing) => {
            Ok(session_covers_open_paths(&existing, planned))
        }
        ConflictSessionState::Absent => Ok(false),
    }
}

fn materialize_or_load_streaming_conflict_session(
    session: &SyncSession,
    paths: Option<&SyncPaths>,
    plan: &StreamingPlanOutcome,
    conflict_bodies: Option<&ConflictBodySource>,
) -> Result<Option<ConflictSession>, LomoError> {
    let Some(sync_paths) = paths else {
        return Ok(None);
    };
    if plan.open_conflict_count() == 0 {
        return match read_conflict_session_state(sync_paths)? {
            ConflictSessionState::Absent => Ok(None),
            ConflictSessionState::Present(existing) => Ok(Some(existing)),
        };
    }
    if let ConflictSessionState::Present(existing) = read_conflict_session_state(sync_paths)?
        && session_covers_open_paths(
            &existing,
            &planned_open_conflict_paths(
                plan.intent_pages
                    .iter()
                    .flat_map(|page| page.intents.iter()),
            ),
        )
    {
        return Ok(Some(existing));
    }
    let bodies = conflict_bodies.ok_or_else(|| {
        validation(
            "conflict_candidate_body_missing",
            "OpenConflict materialize requires candidate body source",
        )
    })?;
    let conflict_session_id = crate::conflict::conflict_session_id(&session.session_id);
    materialize_conflicts_from_intent_pages(
        sync_paths,
        &session.fence,
        &conflict_session_id,
        &plan.intent_pages,
        &plan.conflict_remote_entries,
        bodies,
    )
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
        if let ConflictSessionState::Present(existing) = read_conflict_session_state(sync_paths)?
            && session_covers_open_paths(
                &existing,
                &planned_open_conflict_paths(batch.intents.iter()),
            )
        {
            return Ok(Some(existing));
        }
        let bodies = conflict_bodies.ok_or_else(|| {
            validation(
                "conflict_candidate_body_missing",
                "OpenConflict materialize requires candidate body source",
            )
        })?;
        let conflict_session_id = crate::conflict::conflict_session_id(&session.session_id);
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
    match read_conflict_session_state(sync_paths)? {
        ConflictSessionState::Absent => Ok(None),
        ConflictSessionState::Present(existing) => Ok(Some(existing)),
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
        // Capability participation: the probed/declared remote capability facts gate
        // conditional mutations before any publish — server leniency is never assumed.
        crate::pipeline::require_remote_capabilities(
            &batch.intents,
            remote.remote_capabilities()?,
        )?;
        Some(remote.publish(batch)?)
    } else {
        None
    };

    let mut verify_expectations: Vec<VerifyExpectation> = Vec::new();
    if let Some(published) = receipt.as_ref() {
        for (path, status) in &published.path_results {
            let crate::pipeline::PathPublishStatus::Applied { new_token } = status else {
                continue;
            };
            // Published paths verify against the intent digest (present) or expected absence
            // (delete), with the publish receipt's fresh token as the observed validator.
            let expected_digest = batch.intents.iter().find_map(|intent| {
                if let ProviderNeutralIntent::EnsurePresent {
                    path: intent_path,
                    digest,
                    ..
                } = intent
                    && intent_path.as_str() == path.as_str()
                {
                    Some(digest.clone())
                } else {
                    None
                }
            });
            verify_expectations.push(VerifyExpectation {
                path: path.clone(),
                expected_digest,
                expected_token: Some(new_token.clone()),
            });
        }
    }

    // PullPresent is local-store apply; remote verify still confirms token/digest.
    // Same-byte paths may establish baseline after verify of remote presence (no publish needed).
    for intent in &batch.intents {
        if let ProviderNeutralIntent::PullPresent {
            path,
            digest,
            remote_token,
        } = intent
            && !verify_expectations
                .iter()
                .any(|expectation| expectation.path.as_str() == path.as_str())
        {
            verify_expectations.push(VerifyExpectation {
                path: path.clone(),
                expected_digest: Some(digest.clone()),
                expected_token: remote_token.clone(),
            });
        }
    }
    for entry in &remote_snap.entries {
        if let Some(local_entry) = local_snap
            .entries
            .iter()
            .find(|local| local.path.as_str() == entry.path.as_str())
            && entry
                .digest
                .known()
                .is_some_and(|digest| digest.as_str() == local_entry.digest.as_str())
            && !verify_expectations
                .iter()
                .any(|expectation| expectation.path.as_str() == entry.path.as_str())
        {
            verify_expectations.push(VerifyExpectation {
                path: entry.path.clone(),
                expected_digest: Some(local_entry.digest.clone()),
                expected_token: entry.validator.strong_token().map(str::to_owned),
            });
        }
    }

    let verified = if verify_expectations.is_empty() {
        VerifiedRemoteState {
            results: Vec::new(),
        }
    } else {
        remote.verify(&verify_expectations)?
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
/// When the plan emits `OpenConflict` and `conflict_bodies` is `None`, candidate bytes are loaded
/// from the Direct workspace and [`RemoteSyncPort::load_object`] (hollow still fails closed).
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

    let result = run_sync_cycle_streaming(
        &session,
        local,
        remote,
        baseline,
        Some(paths),
        apply_remote,
        conflict_bodies,
    )?;

    let (open_conflict_paths, conflict_revision) = match read_conflict_session_state(paths)? {
        ConflictSessionState::Absent => (0, None),
        ConflictSessionState::Present(conflict) => (
            u32::try_from(conflict.open_count()).map_err(|_overflow| {
                validation(
                    "sync_open_conflict_paths_overflow",
                    "open conflict path count exceeds u32",
                )
            })?,
            Some(conflict.conflict_revision),
        ),
    };

    let ensure_present_count = count_u32(streaming_intent_count(
        &result,
        PreparedRemoteBatch::ensure_present_count,
    ))?;
    let ensure_absent_count = count_u32(streaming_intent_count(
        &result,
        PreparedRemoteBatch::ensure_absent_count,
    ))?;
    let pull_present_count = count_u32(streaming_intent_count(
        &result,
        PreparedRemoteBatch::pull_present_count,
    ))?;
    let open_conflict_count = count_u32(streaming_intent_count(
        &result,
        PreparedRemoteBatch::open_conflict_count,
    ))?;
    let hold_count = count_u32(streaming_intent_count(
        &result,
        PreparedRemoteBatch::hold_count,
    ))?;

    let retry_disposition =
        disposition_for_streaming_result(&result, open_conflict_paths, open_conflict_count);

    Ok(SyncCyclePlanSummary {
        session_id: session.session_id,
        session_kind: session.kind,
        session_revision: session.session_revision,
        baseline_established: result.baseline.is_established(),
        ensure_present_count,
        ensure_absent_count,
        pull_present_count,
        open_conflict_count,
        hold_count,
        open_conflict_paths,
        conflict_revision,
        retry_disposition,
        pages_applied: result.pages_applied,
        baseline_advanced: result.baseline_advanced,
        local_entry_count: result.local_entry_count,
        remote_listed_count: result.remote_listed_count,
        baseline_entry_count: result.baseline_entry_count,
    })
}

/// Wraps one composed cycle body with the durable [`crate::SyncCycleRecord`] lifecycle.
///
/// `begin_sync_cycle` repairs a stale `Running` record (writer death → `Failed(interrupted)`)
/// and persists `Running` before any port construction work, so connect failures also land in
/// the durable record. The terminal write lands on success, owner error, or the apply loop's
/// own `sync_cycle_cancelled` write. Conversion-only inspect ([`inspect_sync_cycle_plan`])
/// stays side-effect-free — it is not a cycle.
///
/// # Errors
///
/// Session/cycle-state persistence errors; the wrapped body's own errors after the terminal
/// record write.
fn run_cycle_with_record(
    paths: &SyncPaths,
    backend_kind: SyncBackendKind,
    apply_remote: bool,
    run: impl FnOnce() -> Result<SyncCyclePlanSummary, LomoError>,
) -> Result<SyncCyclePlanSummary, LomoError> {
    let session = read_session(paths)?;
    let mut record =
        crate::cycle_state::begin_sync_cycle(paths, &session, backend_kind, apply_remote)?;
    match run() {
        Ok(summary) => {
            crate::cycle_state::complete_sync_cycle(paths, &mut record, &summary)?;
            Ok(summary)
        }
        Err(err) => {
            // The apply loop persists the terminal Cancelled write itself.
            if err.code() != crate::cycle_state::CYCLE_CANCELLED_CODE {
                crate::cycle_state::fail_sync_cycle(paths, &mut record, &err)?;
            }
            Err(err)
        }
    }
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

impl SyncBackendKind {
    /// Stable wire name (`hermetic_fake` | `webdav` | `s3` | `git`).
    #[must_use]
    pub const fn wire_name(self) -> &'static str {
        match self {
            Self::HermeticFake => "hermetic_fake",
            Self::WebDav => "webdav",
            Self::S3 => "s3",
            Self::Git => "git",
        }
    }
}

/// Non-secret backend configuration for one production cycle composition.
///
/// Each backend is a dedicated variant — fields cannot be borrowed across providers.
/// Secrets are never stored here — callers resolve a process-local secret lease and pass
/// material separately. Git adapter construction lives at the native composition edge
/// (`lomo-git`); this config still carries Git non-secret identity for the durable session fence.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum SyncBackendConfig {
    /// Hermetic fake backend (no network; host composition proof).
    HermeticFake {
        /// Opaque remote dataset id for the durable identity fence.
        remote_dataset_id: String,
    },
    /// `WebDAV` endpoint + non-secret username.
    WebDav {
        /// Endpoint base URL.
        endpoint_url: String,
        /// `WebDAV` username (non-secret identity).
        username: String,
        /// Opaque remote dataset id for the durable identity fence.
        remote_dataset_id: String,
    },
    /// Path-style S3 endpoint + bucket/prefix/region + non-secret access key id.
    S3 {
        /// Endpoint base URL.
        endpoint_url: String,
        /// Access key id (non-secret identity; the secret key travels via lease).
        access_key_id: String,
        /// Bucket name (required).
        bucket: String,
        /// Key prefix (optional; empty when unused).
        prefix: String,
        /// Region (required).
        region: String,
        /// Opaque remote dataset id for the durable identity fence.
        remote_dataset_id: String,
    },
    /// Git remote + explicit branch and commit author identity (all non-secret).
    Git {
        /// Remote URL (validated by `lomo-git` at the composition edge; userinfo is rejected).
        remote_url: String,
        /// HTTPS username (non-secret identity; may be empty for anonymous local remotes).
        username: String,
        /// Branch short name (e.g. `main`).
        branch: String,
        /// Commit author name.
        author_name: String,
        /// Commit author email.
        author_email: String,
        /// Opaque remote dataset id for the durable identity fence.
        remote_dataset_id: String,
    },
}

impl SyncBackendConfig {
    /// Builds a hermetic fake backend config (no network; host composition proof).
    #[must_use]
    pub fn hermetic_fake(remote_dataset_id: impl Into<String>) -> Self {
        Self::HermeticFake {
            remote_dataset_id: remote_dataset_id.into(),
        }
    }

    /// Backend discriminant for diagnostics and canonical identity.
    #[must_use]
    pub const fn kind(&self) -> SyncBackendKind {
        match self {
            Self::HermeticFake { .. } => SyncBackendKind::HermeticFake,
            Self::WebDav { .. } => SyncBackendKind::WebDav,
            Self::S3 { .. } => SyncBackendKind::S3,
            Self::Git { .. } => SyncBackendKind::Git,
        }
    }

    /// Opaque remote dataset id carried by every variant.
    #[must_use]
    pub fn remote_dataset_id(&self) -> &str {
        match self {
            Self::HermeticFake { remote_dataset_id }
            | Self::WebDav {
                remote_dataset_id, ..
            }
            | Self::S3 {
                remote_dataset_id, ..
            }
            | Self::Git {
                remote_dataset_id, ..
            } => remote_dataset_id,
        }
    }

    /// Canonical non-secret identity bytes for the durable session fence.
    ///
    /// Per-variant field names are part of the canonical form — the same values under a
    /// different backend spell a different fence. Secret material never participates.
    #[must_use]
    pub fn canonical_identity(&self) -> String {
        match self {
            Self::HermeticFake { remote_dataset_id } => {
                format!("kind=HermeticFake\ndataset={remote_dataset_id}\n")
            }
            Self::WebDav {
                endpoint_url,
                username,
                remote_dataset_id,
            } => format!(
                "kind=WebDav\nendpoint={endpoint_url}\nuser={username}\ndataset={remote_dataset_id}\n"
            ),
            Self::S3 {
                endpoint_url,
                access_key_id,
                bucket,
                prefix,
                region,
                remote_dataset_id,
            } => format!(
                "kind=S3\nendpoint={endpoint_url}\naccess_key={access_key_id}\nbucket={bucket}\nprefix={prefix}\nregion={region}\ndataset={remote_dataset_id}\n"
            ),
            Self::Git {
                remote_url,
                username,
                branch,
                author_name,
                author_email,
                remote_dataset_id,
            } => format!(
                "kind=Git\nremote={remote_url}\nuser={username}\nbranch={branch}\nauthor={author_name}\nemail={author_email}\ndataset={remote_dataset_id}\n"
            ),
        }
    }
}

/// Runs one **production-shaped** owner cycle with real local (store snapshot) + remote ports.
///
/// Composition only: opens `lomo-store` for a generation-fenced local snapshot, ensures a durable
/// session (first-takeover when missing), builds the remote port from [`SyncBackendConfig`] + optional secret
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
    if config.remote_dataset_id().is_empty() || config.remote_dataset_id().len() > 128 {
        return Err(validation(
            "sync_remote_dataset_id_invalid",
            "remote_dataset_id must be 1..=128 bytes",
        ));
    }

    if matches!(config, SyncBackendConfig::Git { .. }) {
        return Err(validation(
            "sync_git_compose_via_remote_port",
            "git composition builds lomo-git at the native edge; use run_composed_sync_cycle_with_remote_port",
        ));
    }

    // Real local port: store coarse snapshot (path/digest/generation only). Read-only: the
    // projection handle is used because a session-migrated V2 workspace refuses v1 writers.
    let store = lomo_store::Store::open_projection(workspace_root)?;
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
    run_cycle_with_record(&paths, config.kind(), apply_remote, || {
        run_composed_with_remote(
            workspace_root,
            &paths,
            &local,
            config,
            secret_material,
            apply_remote,
        )
    })
}

/// Runs one production-shaped owner cycle with a **caller-provided** remote port.
///
/// Used by native Git composition (`lomo-git` constructed outside `lomo-sync`) and hermetic host
/// contracts that already hold a `RemoteSyncPort`. Opens the store local snapshot, ensures the
/// durable session fence from [`SyncBackendConfig`] identity, then runs the owner cycle.
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
    if config.remote_dataset_id().is_empty() || config.remote_dataset_id().len() > 128 {
        return Err(validation(
            "sync_remote_dataset_id_invalid",
            "remote_dataset_id must be 1..=128 bytes",
        ));
    }

    // Read-only local snapshot: same projection-open rule as above (V2 refuses v1 writers).
    let store = lomo_store::Store::open_projection(workspace_root)?;
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
    run_cycle_with_record(&paths, config.kind(), apply_remote, || {
        inspect_sync_cycle_plan_with_ports(&paths, &local, remote, apply_remote, None)
    })
}

fn run_composed_with_remote(
    workspace_root: &std::path::Path,
    paths: &SyncPaths,
    local: &dyn LocalSyncPort,
    config: &SyncBackendConfig,
    secret_material: Option<&[u8]>,
    apply_remote: bool,
) -> Result<SyncCyclePlanSummary, LomoError> {
    let remote = connect_sync_remote_port(workspace_root, paths, config, secret_material)?;
    inspect_sync_cycle_plan_with_ports(paths, local, remote.as_ref(), apply_remote, None)
}

/// Connects the production remote port for a non-Git backend.
///
/// Shared by composed cycles and host probes (`testConnection`): the probe exercises the same
/// adapter construction, capabilities, and listing path instead of reporting acceptance without
/// touching the remote. Git stays rejected — `lomo-git` is built at the native composition edge.
///
/// # Errors
///
/// Validation for incomplete config / Git kind; secret-lease and adapter construction errors.
pub fn connect_sync_remote_port(
    workspace_root: &std::path::Path,
    paths: &SyncPaths,
    config: &SyncBackendConfig,
    secret_material: Option<&[u8]>,
) -> Result<Box<dyn RemoteSyncPort>, LomoError> {
    match config {
        SyncBackendConfig::HermeticFake { .. } => {
            // Non-empty ports: local is real store; remote is hermetic empty complete listing.
            // This is the host proof that production composition is not empty-port inspect.
            Ok(Box::new(FakeRemotePort::new(
                RemoteSnapshot::new(SnapshotCompleteness::Complete, Vec::new())?,
                PublishReceipt {
                    path_results: Vec::new(),
                },
                VerifiedRemoteState {
                    results: Vec::new(),
                },
            )))
        }
        SyncBackendConfig::WebDav {
            endpoint_url,
            username,
            ..
        } => {
            let password = secret_utf8(secret_material, "webdav_secret_required")?;
            if endpoint_url.is_empty() || username.is_empty() {
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
                endpoint_url,
                username,
                password,
                &temp_dir,
                objects,
                std::time::Duration::from_secs(30),
            )?;
            Ok(Box::new(remote))
        }
        SyncBackendConfig::S3 {
            endpoint_url,
            access_key_id,
            bucket,
            prefix,
            region,
            ..
        } => {
            let secret = secret_utf8(secret_material, "s3_secret_required")?;
            if endpoint_url.is_empty()
                || access_key_id.is_empty()
                || bucket.is_empty()
                || region.is_empty()
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
            // Durable multipart sessions bind to the canonical sync identity fence ensured
            // by composition; a stale-generation record aborts + clears instead of resuming.
            let session = read_session(paths)?;
            let remote = crate::s3::connect_workspace_s3(
                endpoint_url,
                bucket,
                prefix,
                region,
                access_key_id,
                secret,
                &temp_dir,
                objects,
                &session.fence.stable_key(),
                std::time::Duration::from_secs(30),
            )?;
            Ok(Box::new(remote))
        }
        SyncBackendConfig::Git { .. } => Err(validation(
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
    let dataset = lomo_workspace::RemoteDatasetId::parse(config.remote_dataset_id())?;
    // Canonical identity: backend kind + endpoint + non-secret identity fields (never secret bytes).
    let canonical = config.canonical_identity();
    let identity =
        lomo_workspace::RemoteIdentityDigest::from_canonical_config_bytes(canonical.as_bytes());
    let fence = SyncIdentityFence::from_parts(&generation, &dataset, &identity);
    let session = SyncSession::for_first_takeover(fence, &dataset)?;
    write_session(paths, &session)
}

fn streaming_intent_count(
    result: &StreamingSyncCycleResult,
    count: fn(&PreparedRemoteBatch) -> usize,
) -> usize {
    let mut total = count(&result.first_page_batch);
    for page in result.plan.intent_pages.iter().skip(1) {
        total += count(page);
    }
    total
}

fn disposition_for_streaming_result(
    result: &StreamingSyncCycleResult,
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

/// Remote port wrapper that serves bodies resolved during the listing pre-pass.
///
/// [`crate::ports::RemoteSyncPort::resolve_remote_object`] results are cached so the conflict
/// materialize / local-pull `load_object` calls reuse the bytes already fetched instead of
/// issuing a second GET per path.
struct ResolvedObjectCache<'a> {
    inner: &'a dyn RemoteSyncPort,
    objects: BTreeMap<String, RemoteResolvedObject>,
}

impl<'a> ResolvedObjectCache<'a> {
    fn new(inner: &'a dyn RemoteSyncPort) -> Self {
        Self {
            inner,
            objects: BTreeMap::new(),
        }
    }
}

impl RemoteSyncPort for ResolvedObjectCache<'_> {
    fn list_remote(&self) -> Result<RemoteSnapshot, LomoError> {
        self.inner.list_remote()
    }

    fn list_remote_pages(&self) -> Result<crate::ports::RemoteListingStream, LomoError> {
        self.inner.list_remote_pages()
    }

    fn batch_atomicity(&self) -> BatchAtomicity {
        self.inner.batch_atomicity()
    }

    fn remote_capabilities(&self) -> Result<crate::ports::RemoteCapabilities, LomoError> {
        self.inner.remote_capabilities()
    }

    fn publish(&self, batch: &PreparedRemoteBatch) -> Result<PublishReceipt, LomoError> {
        self.inner.publish(batch)
    }

    fn verify(&self, expectations: &[VerifyExpectation]) -> Result<VerifiedRemoteState, LomoError> {
        self.inner.verify(expectations)
    }

    fn resolve_remote_object(
        &self,
        path: &SyncPath,
    ) -> Result<Option<RemoteResolvedObject>, LomoError> {
        self.inner.resolve_remote_object(path)
    }

    fn load_object(
        &self,
        path: &SyncPath,
        expected_digest: &ContentDigest,
    ) -> Result<Option<Vec<u8>>, LomoError> {
        if let Some(object) = self.objects.get(path.as_str()) {
            if object.digest.as_str() != expected_digest.as_str() {
                return Err(validation(
                    "resolved_remote_object_digest_mismatch",
                    "resolved remote object digest does not match the expected digest",
                ));
            }
            return Ok(Some(object.body.clone()));
        }
        self.inner.load_object(path, expected_digest)
    }
}

/// Resolves [`RemoteDigestFact::Unresolved`] listing digests for exactly the paths the planner
/// needs byte-level facts for.
///
/// A path is skipped (digest stays unresolved, no fetch) only when the listing already proves it
/// fully in-sync: the strong validator still equals the durable baseline token and the local
/// digest still equals the baseline digest. Every other unresolved path — pulls, deletes under
/// tombstone gates, conflicts, local updates — is resolved through
/// [`crate::ports::RemoteSyncPort::resolve_remote_object`] once, and the body is retained in
/// `cache` for downstream `load_object` reuse. An object that vanishes between listing and
/// resolution fails the cycle closed; the next listing converges.
fn resolve_listing_digests<'a, I>(
    cache: &mut ResolvedObjectCache<'_>,
    entries: I,
    local_snap: &LocalSnapshot,
    baseline: &BaselineHead,
) -> Result<(), LomoError>
where
    I: IntoIterator<Item = &'a mut RemotePathEntry>,
{
    let local_map: BTreeMap<&str, &ContentDigest> = local_snap
        .entries
        .iter()
        .map(|entry| (entry.path.as_str(), &entry.digest))
        .collect();
    for entry in entries {
        if entry.digest.known().is_some() || remote_path_in_sync(entry, &local_map, baseline) {
            continue;
        }
        let Some(object) = cache.inner.resolve_remote_object(&entry.path)? else {
            return Err(validation(
                "remote_object_vanished",
                "remote object listed but vanished before digest resolution",
            ));
        };
        entry.digest = RemoteDigestFact::Known(object.digest.clone());
        cache.objects.insert(entry.path.as_str().to_owned(), object);
    }
    Ok(())
}
