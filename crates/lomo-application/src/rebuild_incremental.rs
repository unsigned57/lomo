//! Path-scoped projection reconcile.
//!
//! The committed `file_listing` snapshot is the change-detection baseline: the current listing's
//! evidence fingerprints are diffed against it, every changed or removed path is assigned a
//! proven projection scope, and only facts inside those scopes are re-read and re-applied in one
//! transaction. A path whose projection ownership cannot be proven from the listing — an unknown
//! `.lomo` subtree, a deleted record whose identity lives only in its body — sends the caller
//! back to the full scan, which remains the truth rebuilder.
//!
//! Invariant: reconcile cost is proportional to the changed set, never the library size.

use std::{
    collections::{BTreeMap, BTreeSet},
    ops::Bound,
    sync::Arc,
};

use lomo_core::{DocumentMetadata, LomoError, PlatformActionExecutor, RelativeWorkspacePath};
use lomo_store::{
    RebuildResult, ScannedHistoryProjection, ScannedIncrementalFacts, ScannedListingRow,
    ScannedMemoHistoryReplace, ScannedPinProjection, ScannedTrashProjection, Store,
    decode_purge_record, purge_record_relative_path,
};
use lomo_workspace::{
    MemoId, MemoIdentityMap, StateHead, WorkspaceRelativePath, decode_record,
    memo_identity_record_path, trash_record_relative_path,
};

use crate::{
    config::WorkspaceSessionConfig,
    error::corruption,
    rebuild::{
        decode_trash_projection, has_extension, listing_token_map, refresh_listing_tokens,
        scan_markdown_files,
    },
    rebuild_records,
    workspace_io::WorkspaceIo,
};

const HISTORY_HEADS: &str = ".lomo/history/v2/heads/";
const HISTORY_OBJECTS: &str = ".lomo/history/v2/objects/";
const HISTORY_TOMBSTONES: &str = ".lomo/history/v2/tombstones/";
const STATE_HEADS: &str = ".lomo/state/v2/heads/";
const STATE_OBJECTS: &str = ".lomo/state/v2/objects/";
const TRASH_RECORDS: &str = ".lomo/trash/v1/";
const PURGE_RECORDS: &str = ".lomo/purged/v1/";
const IDENTITY_RECORDS: &str = ".lomo/identity/v1/";

/// One reconcile attempt over the current listing against the committed `file_listing` snapshot.
///
/// Returns `None` when the diff contains a path whose projection scope cannot be proven — the
/// caller then falls back to the full workspace scan. Otherwise every changed path's facts are
/// re-gathered at their proven scope and committed through
/// [`Store::apply_scanned_incremental`].
///
/// `observed`, when present, restricts upsert work to the watcher-attested paths, expanded to
/// cover directory-level events by prefix — an unattested add stays deferred until the next
/// digest-diffed pass. Deletions behave differently by construction: a committed row whose
/// file is absent from the complete listing is a proven removal with no re-read needed, so
/// removals are never gated by `observed`. That asymmetry is what keeps a destination-only
/// rename event honest — the unreported source still retires — and it also covers the
/// suppression authority: `.lomo/purged/` tombstones are always treated as covered, because a
/// dropped watcher event must never leave a purged identity able to resurrect.
///
/// # Errors
/// Propagates listing, read, decode, and storage failures that a cold scan of the same durable
/// bytes would surface. Scope ambiguity returns `Ok(None)` instead of failing.
pub fn reconcile_scoped(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
    store: &mut Store,
    listing: &[DocumentMetadata],
    observed: Option<&[RelativeWorkspacePath]>,
) -> Result<Option<RebuildResult>, LomoError> {
    let current = listing_token_map(listing);
    let snapshot = store.workspace_listing_snapshot()?;

    // The diff is the symmetric set difference between committed rows and live fingerprints.
    // Upserts are restricted to watcher-attested coverage when the caller supplied one;
    // removals are always processed — a committed path absent from a complete listing is
    // proven gone without needing attestation — and purge tombstones are always covered
    // because their rows are the suppression authority.
    let covered = observed.map(|paths| {
        let mut set = cover_observed(paths, &current);
        set.extend(
            current
                .keys()
                .filter(|path| path.starts_with(PURGE_RECORDS))
                .cloned(),
        );
        set
    });
    let mut upsert_tokens: BTreeMap<String, String> = BTreeMap::new();
    let mut removed_paths: Vec<String> = Vec::new();
    for (path, token) in &current {
        if let Some(set) = &covered
            && !set.contains(path)
        {
            continue;
        }
        match snapshot.get(path) {
            Some(old) if old == token => {}
            _ => {
                upsert_tokens.insert(path.clone(), token.clone());
            }
        }
    }
    for path in snapshot.keys() {
        if !current.contains_key(path) {
            removed_paths.push(path.clone());
        }
    }
    if upsert_tokens.is_empty() && removed_paths.is_empty() {
        // Nothing changed inside the attested set. Re-applying an empty fact set still lets the
        // store re-anchor the listing digest meta when a legacy commit lacked it.
        return Ok(Some(
            store.apply_scanned_incremental(&ScannedIncrementalFacts::default())?,
        ));
    }
    if snapshot.is_empty() {
        // Without a committed baseline every deletion is unprovable; only a full scan tells
        // "added" apart from "was never tracked".
        return Ok(None);
    }

    let mut scope = PathScope::default();
    for path in upsert_tokens.keys() {
        scope.classify_present(path);
    }
    for path in &removed_paths {
        scope.classify_removed(path);
    }
    if scope.unclassifiable {
        return Ok(None);
    }

    let delta = ListingDelta {
        current,
        scope,
        upsert_tokens,
        removed_paths,
    };
    let Some(facts) = gather_facts(config, executor, store, delta)? else {
        return Ok(None);
    };
    Ok(Some(store.apply_scanned_incremental(&facts)?))
}

/// Realigns the committed `file_listing` rows after a non-rewriting reconcile.
///
/// `try_reconcile` proves memo contents still match, but listing tokens may have drifted (mtime
/// bumps, same-byte rewrites) and the scan itself may have written files the provoking listing
/// predates. `rows` is the inventory's committed-intent listing — already refreshed for
/// scan-written files — so realignment can never re-lose them.
///
/// # Errors
/// Propagates storage failures.
pub fn align_listing_snapshot(
    store: &mut Store,
    rows: &[ScannedListingRow],
) -> Result<(), LomoError> {
    let current: BTreeMap<String, String> = rows
        .iter()
        .map(|row| (row.path.clone(), row.digest.clone()))
        .collect();
    let snapshot = store.workspace_listing_snapshot()?;
    let mut facts = ScannedIncrementalFacts::default();
    for (path, token) in &current {
        match snapshot.get(path) {
            Some(old) if old == token => {}
            _ => facts.listing_upserts.push(ScannedListingRow {
                path: path.clone(),
                digest: token.clone(),
            }),
        }
    }
    for path in snapshot.keys() {
        if !current.contains_key(path) {
            facts.listing_removes.push(path.clone());
        }
    }
    // The commit recomputes the digest meta from the updated rows, so the stored baseline is
    // exactly the live listing again.
    store.apply_scanned_incremental(&facts)?;
    Ok(())
}

/// Expands watcher-reported paths to cover directory-level events: an observed directory path
/// attests every currently listed path beneath it. Only live paths can carry an upsert, so
/// attestation resolves against the current listing — removals are never gated by this set.
fn cover_observed(
    observed: &[RelativeWorkspacePath],
    current: &BTreeMap<String, String>,
) -> BTreeSet<String> {
    let mut covered = BTreeSet::new();
    for path in observed {
        let path = path.as_str();
        covered.insert(path.to_owned());
        let prefix = format!("{path}/");
        for (key, _) in
            current.range::<str, _>((Bound::Included(prefix.as_str()), Bound::Unbounded))
        {
            if !key.starts_with(&prefix) {
                break;
            }
            covered.insert(key.clone());
        }
    }
    covered
}

/// Strips a canonical record directory and `.rec` suffix. Anything nested deeper or lacking the
/// extension cannot claim path authority and is left to the caller's unclassifiable handling.
fn record_stem<'a>(path: &'a str, dir: &str) -> Option<&'a str> {
    let name = path.strip_prefix(dir)?;
    let stem = name.strip_suffix(".rec")?;
    if stem.is_empty() || stem.contains('/') {
        return None;
    }
    Some(stem)
}

#[derive(Default)]
struct PathScope {
    docs_changed: BTreeSet<String>,
    docs_removed: BTreeSet<String>,
    /// Changed history-head paths; their stems must equal the decoded body's memo id.
    history_heads: Vec<(String, String)>,
    history_head_removed: Vec<String>,
    /// Changed history-object paths; their stems must equal the decoded body's
    /// claimed revision id — the same naming authority `HistoryGraph::insert`
    /// proves before keying the map.
    history_objects: Vec<(String, String)>,
    history_object_removed: Vec<String>,
    /// Changed history-tombstone paths; their stems must equal the decoded
    /// body's claimed revision id, so a prune effect can never land on a claim
    /// no closure walk could name.
    history_tombstones: Vec<(String, String)>,
    history_tombstone_removed: Vec<String>,
    /// Changed state-head paths; their stems must equal the decoded body's memo id.
    state_heads: Vec<(String, String)>,
    state_head_removed: Vec<String>,
    /// Changed state-object paths; their stems must equal the decoded body's
    /// claimed revision id, the claimed memo's durable head must point at
    /// that stem, and the body's fields must recompute the stored id — the
    /// slot answers only to the head claiming the one body the stem can name,
    /// the same route the cold `pins()` walk reads it through.
    state_objects: Vec<(String, String)>,
    trash_reads: BTreeSet<String>,
    trash_removed: Vec<String>,
    purge_reads: Vec<String>,
    purge_removed: Vec<String>,
    identity_reads: Vec<String>,
    unclassifiable: bool,
}

impl PathScope {
    /// Assigns a scope to a path that is present now and changed or added versus the snapshot.
    fn classify_present(&mut self, path: &str) {
        if !path.starts_with(".lomo/") {
            if has_extension(path, "md") {
                self.docs_changed.insert(path.to_owned());
            }
            // Everything else outside .lomo is never projected.
            return;
        }
        if let Some(stem) = record_stem(path, HISTORY_HEADS) {
            self.history_heads.push((path.to_owned(), stem.to_owned()));
        } else if let Some(stem) = record_stem(path, HISTORY_OBJECTS) {
            self.history_objects
                .push((path.to_owned(), stem.to_owned()));
        } else if let Some(stem) = record_stem(path, HISTORY_TOMBSTONES) {
            self.history_tombstones
                .push((path.to_owned(), stem.to_owned()));
        } else if let Some(stem) = record_stem(path, STATE_HEADS) {
            self.state_heads.push((path.to_owned(), stem.to_owned()));
        } else if let Some(stem) = record_stem(path, STATE_OBJECTS) {
            self.state_objects.push((path.to_owned(), stem.to_owned()));
        } else if record_stem(path, TRASH_RECORDS).is_some() {
            self.trash_reads.insert(path.to_owned());
        } else if record_stem(path, PURGE_RECORDS).is_some() {
            self.purge_reads.push(path.to_owned());
        } else if record_stem(path, IDENTITY_RECORDS).is_some() {
            self.identity_reads.push(path.to_owned());
        } else {
            self.unclassifiable = true;
        }
    }

    /// Assigns a scope to a path the snapshot certified but the current listing lost.
    ///
    /// Deleted files cannot be decoded, so removal scopes rely on committed index authority:
    /// history object/tombstone stems resolve through `revision_index`, head stems are already
    /// memo ids, and trash/purge stems hash back to their canonical names. Anything without a
    /// proof is unclassifiable.
    fn classify_removed(&mut self, path: &str) {
        if !path.starts_with(".lomo/") {
            if has_extension(path, "md") {
                self.docs_removed.insert(path.to_owned());
            }
            return;
        }
        if let Some(stem) = record_stem(path, HISTORY_HEADS) {
            self.history_head_removed.push(stem.to_owned());
        } else if let Some(stem) = record_stem(path, HISTORY_OBJECTS) {
            self.history_object_removed.push(stem.to_owned());
        } else if let Some(stem) = record_stem(path, HISTORY_TOMBSTONES) {
            self.history_tombstone_removed.push(stem.to_owned());
        } else if let Some(stem) = record_stem(path, STATE_HEADS) {
            self.state_head_removed.push(stem.to_owned());
        } else if record_stem(path, STATE_OBJECTS).is_some() {
            // A deleted state object leaves no body to decode and no index to resolve; only a
            // head re-walk can tell whether it was the tip.
            self.unclassifiable = true;
        } else if record_stem(path, TRASH_RECORDS).is_some() {
            self.trash_removed.push(path.to_owned());
        } else if record_stem(path, PURGE_RECORDS).is_some() {
            self.purge_removed.push(path.to_owned());
        } else if record_stem(path, IDENTITY_RECORDS).is_some() {
            // Which memo owned the deleted binding is not derivable from its hash.
            self.unclassifiable = true;
        } else {
            self.unclassifiable = true;
        }
    }
}

/// Finds the committed identity whose canonical record name is exactly `path`.
fn canonical_owner(
    committed_ids: &BTreeSet<String>,
    path: &str,
    canonical_path: impl Fn(&str) -> Result<WorkspaceRelativePath, LomoError>,
) -> Result<Option<String>, LomoError> {
    for id in committed_ids {
        if canonical_path(id)?.as_str() == path {
            return Ok(Some(id.clone()));
        }
    }
    Ok(None)
}

/// One fact-gathering pass over a proven [`PathScope`].
///
/// Owns every mutable collection so each phase stays a small method: `facts` is what
/// [`Store::apply_scanned_incremental`] commits, the `*_memos`/`memo_removes` sets are the
/// scope expansion state, `purged_ids` is the effective suppression set, and
/// `initial_history`/`written_paths` carry what the document rescan itself wrote.
struct Gather<'a> {
    io: &'a WorkspaceIo<'a>,
    store: &'a Store,
    current: &'a BTreeMap<String, String>,
    scope: &'a mut PathScope,
    facts: ScannedIncrementalFacts,
    memo_removes: BTreeSet<String>,
    history_memos: BTreeSet<String>,
    state_memos: BTreeSet<String>,
    purged_ids: BTreeSet<String>,
    initial_history: BTreeMap<String, ScannedHistoryProjection>,
    written_paths: Vec<RelativeWorkspacePath>,
    /// Memo ids a document rescan emitted this pass — the only identities whose
    /// committed row may act as live-document attestation for a trash claim.
    emitted_ids: BTreeSet<String>,
    /// Trash records decoded during claim disambiguation, reused by the trash
    /// phase so each changed record is read and decoded once.
    decoded_trash: BTreeMap<String, ScannedTrashProjection>,
    /// Diffed `state/v2/heads/` paths whose decoded body names a different
    /// identity than the filename stem — tolerated duplicate evidence the pass
    /// absorbs but never commits: a committed duplicate would leave the diff
    /// baseline and turn stale-invisible the moment the tip it shadows moves.
    absorbed_state_heads: BTreeSet<String>,
}

impl Gather<'_> {
    /// Reads the file at `path` — every scoped read goes through here so the return contract
    /// stays in one place.
    fn read(&self, path: &str) -> Result<Vec<u8>, LomoError> {
        Ok(self.io.require(&RelativeWorkspacePath::parse(path)?)?.bytes)
    }

    /// Retires one memo identity from the projection: drop its memo-owned rows, re-derive its
    /// pin fact, and let any live trash record claim it again.
    fn retire_memo(&mut self, memo_id: &str) -> Result<(), LomoError> {
        if !self.memo_removes.insert(memo_id.to_owned()) {
            return Ok(());
        }
        self.state_memos.insert(memo_id.to_owned());
        let trash_path = trash_record_relative_path(memo_id)?.as_str().to_owned();
        if self.current.contains_key(&trash_path) {
            self.scope.trash_reads.insert(trash_path);
        }
        Ok(())
    }

    /// Purge records first: their effective set suppresses trash reads in the same pass.
    /// `false` means a removal could not name its claimant — the caller falls back.
    /// The tombstones this queues in `purged_upserts` get their live-document lane
    /// arbitration later, inside `disambiguate_trash_claims`, once the suppression set
    /// is final and before the document rescan runs.
    fn purge(&mut self) -> Result<bool, LomoError> {
        let committed = self.store.purged_memo_ids()?;
        self.purged_ids.clone_from(&committed);
        for path in self.scope.purge_reads.clone() {
            let tombstone = decode_purge_record(&self.read(&path)?)?;
            let expected = purge_record_relative_path(&tombstone.memo_id)?;
            if path != expected.as_str() {
                return Err(corruption(
                    "purge_record_path_mismatch",
                    "purge record is not at its canonical path",
                ));
            }
            self.purged_ids.insert(tombstone.memo_id.clone());
            self.facts.purged_upserts.push(tombstone.memo_id);
        }
        for path in self.scope.purge_removed.clone() {
            let Some(owner) = canonical_owner(&committed, &path, purge_record_relative_path)?
            else {
                // A purge-record deletion whose claimant cannot be named is not provably inert.
                return Ok(false);
            };
            self.facts.purged_removes.push(owner.clone());
            self.purged_ids.remove(&owner);
            // The lifted suppression lets a live trash record claim the memo again.
            let trash_path = trash_record_relative_path(&owner)?.as_str().to_owned();
            if self.current.contains_key(&trash_path) {
                self.scope.trash_reads.insert(trash_path);
            }
        }
        Ok(true)
    }

    /// Identity records only prove which source document to rescan; a binding that cannot be
    /// decoded cannot name its document, so that one defeats scoping.
    fn identities(&mut self, config: &WorkspaceSessionConfig) -> Result<bool, LomoError> {
        for path in self.scope.identity_reads.clone() {
            let Ok(map) = MemoIdentityMap::decode(&self.read(&path)?) else {
                return Ok(false);
            };
            if map.root_id() != config.root_id {
                continue;
            }
            let canonical = memo_identity_record_path(config.root_id, map.path())?;
            if canonical.as_str() != path {
                continue;
            }
            let doc = map.path().as_str().to_owned();
            if self.current.contains_key(&doc) {
                self.scope.docs_changed.insert(doc);
            }
        }
        Ok(true)
    }

    /// Decodes every changed trash record early enough that a committed trashed
    /// row sitting under a live document can be disambiguated before the merge
    /// meets it. `merge_trash_projection` treats a surviving `memo` row as
    /// live-document attestation; a committed row is that only when a live
    /// document still emits the identity — a row a durable record previously
    /// claimed (an echo of the record's own earlier claim) can never attest.
    /// Record-claimed rows are ambiguous at a live path — doc-derived dual lane
    /// or record echo — so the claimed document joins the rescan scope: the
    /// document's own bytes decide the lane, re-emitting dual-lane identities
    /// and retiring echoes before the trash phase runs.
    ///
    /// The same lane question sits on the removal-side arms, where the
    /// committed row's shape must not decide the lane either:
    ///
    /// - A *removed* record retires its owner through `memo_removes` — but an
    ///   owner whose committed row sits under a live document is the restore
    ///   arm: the document re-emits the identity and `memo_upserts`
    ///   re-projects the row untrashed after the removal lands, exactly as a
    ///   cold scan restores it.
    /// - A *landing tombstone* suppresses the trash lane only — the cold scan
    ///   keeps a document-emitted row — yet `apply_purged_id` deletes every
    ///   row carrying membership. A committed trashed identity under a live
    ///   document gets the same rescan so the delete+reinsert order the apply
    ///   already guarantees re-projects its document lane.
    fn disambiguate_trash_claims(&mut self) -> Result<(), LomoError> {
        let committed_trashed = self.store.trashed_memo_ids()?;
        for path in self.scope.trash_reads.clone() {
            let projection = decode_trash_projection(&self.read(&path)?, &path)?;
            let memo_id = projection.memo.memo_id.clone();
            self.decoded_trash.insert(path, projection);
            if self.purged_ids.contains(&memo_id) || !committed_trashed.contains(&memo_id) {
                continue;
            }
            self.widen_document_lane(&memo_id)?;
        }
        for path in self.scope.trash_removed.clone() {
            if let Some(owner) =
                canonical_owner(&committed_trashed, &path, trash_record_relative_path)?
            {
                self.widen_document_lane(&owner)?;
            }
        }
        for memo_id in self.facts.purged_upserts.clone() {
            if committed_trashed.contains(&memo_id) {
                self.widen_document_lane(&memo_id)?;
            }
        }
        Ok(())
    }

    /// Widens the document rescan so the committed row's live document can
    /// arbitrate the lane for `memo_id`: a manifest `.md` outside `.lomo/`
    /// joins `docs_changed`, and the document's own bytes decide whether the
    /// identity re-emits (document lane) or stays absent (record echo). Any
    /// other committed row shape leaves the removal/suppression arm's verdict
    /// untouched.
    fn widen_document_lane(&mut self, memo_id: &str) -> Result<(), LomoError> {
        let Some(source_path) = self.store.memo_source_path(memo_id)? else {
            return Ok(());
        };
        if self.current.contains_key(&source_path)
            && !source_path.starts_with(".lomo/")
            && has_extension(&source_path, "md")
        {
            self.scope.docs_changed.insert(source_path);
        }
        Ok(())
    }

    /// Rescans changed documents and retires every projected memo the scan no longer emits.
    /// A removed document retires every projection kind attesting it — active and trashed
    /// alike — because no live durable record can re-attest the row; `retire_memo` then lets a
    /// live trash record claim the identity again in the trash phase. Memos that received a
    /// fresh initial chain contribute their one-revision history fact directly — re-walking
    /// the records this scan just wrote would only re-read its own writes.
    fn documents(
        &mut self,
        config: &WorkspaceSessionConfig,
        executor: &Arc<dyn PlatformActionExecutor>,
    ) -> Result<(), LomoError> {
        let mut scan_history_files: Vec<RelativeWorkspacePath> = Vec::new();
        for path in self.scope.docs_changed.clone() {
            let rel = RelativeWorkspacePath::parse(&path)?;
            let mut written: Vec<ScannedHistoryProjection> = Vec::new();
            let memos = scan_markdown_files(
                config,
                executor,
                std::slice::from_ref(&rel),
                &mut scan_history_files,
                &mut written,
                &mut self.written_paths,
            )?;
            for projection in written {
                self.initial_history
                    .insert(projection.memo_id.clone(), projection);
            }
            let new_ids: BTreeSet<String> = memos.iter().map(|memo| memo.memo_id.clone()).collect();
            self.facts.memo_upserts.extend(memos);
            self.emitted_ids.extend(new_ids.iter().cloned());
            for old_id in self.store.memo_ids_for_source_path(path.as_str())? {
                if !new_ids.contains(&old_id) {
                    self.retire_memo(&old_id)?;
                }
            }
        }
        for path in self.scope.docs_removed.clone() {
            for old_id in self.store.memo_ids_for_source_path(path.as_str())? {
                self.retire_memo(&old_id)?;
            }
        }
        Ok(())
    }

    /// State records: heads and objects both decode to an owning memo; head removals take their
    /// scope from the canonical stem name. `false` defeats scoping.
    ///
    /// A changed object at `state/v2/objects/<stem>.rec` is provable only when its body sits
    /// inside the naming authorities the cold `pins()` walk applies: the stem must equal the
    /// body's claimed revision id, and the claimed memo's durable head must claim that same
    /// stem — the slot answers only to the head pointing at it, never to the body's `memo_id`
    /// alone. The id must additionally self-prove: `RevisionId::compute` mints `memo_id` into
    /// the hash, so `recomputed_id() == revision_id` makes the stem's occupant the only body
    /// that can legitimately claim it — without it, a head forged onto the body's claimant
    /// satisfies the head check while the slot's real owner is never re-walked. A body whose
    /// claim escapes any of the three — a revision its stem cannot name, an owner whose head
    /// points at another tip, or an id its fields never minted — is naming-authority-foreign
    /// evidence the full scan judges on the identical bytes.
    ///
    /// A present head whose body names a memo different from its filename stem is outside its
    /// naming authority: it carries no projection scope of its own and can only ever be
    /// duplicate evidence for the canonical head of the identity it claims. The cold `pins()`
    /// walk tolerates such a duplicate exactly while its claimed tip still answers — and the
    /// scoped pass must produce the same outcome without ever committing the duplicate into
    /// the `file_listing` baseline, where it would stop diffing and turn stale-invisible.
    /// Anything a cold tip-check would reject — a divergent claim or a vanished canonical
    /// head — still defeats scoping so the full scan reports the identical corruption.
    ///
    /// The decode runs ahead of the absorb judgments so every stray meets this
    /// pass's true anchor set: a canonical slot counts as re-anchored only when
    /// the file on it decoded to the identity its stem names. A foreign body
    /// occupying the slot is absorbed evidence — never an anchor — so a stray
    /// shadowing it falls through to the live tip-check `pins()` performs.
    fn state(&mut self) -> Result<bool, LomoError> {
        let mut anchored_stems: BTreeSet<String> = BTreeSet::new();
        let mut strays: Vec<(String, MemoId, StateHead)> = Vec::new();
        for (path, stem) in self.scope.state_heads.clone() {
            let (id, head) =
                rebuild_records::decode_state_head(&decode_record(&self.read(&path)?)?)?;
            if id.as_str() == stem {
                anchored_stems.insert(stem.clone());
                self.state_memos.insert(stem);
            } else {
                strays.push((path, id, head));
            }
        }
        for (path, id, head) in strays {
            if !self.absorb_duplicate_state_head(&path, &id, &head, &anchored_stems)? {
                // Name authority is broken beyond tolerated duplication; the cold
                // outcome is produced by the full scan.
                return Ok(false);
            }
        }
        for stem in self.scope.state_head_removed.clone() {
            if MemoId::parse(&stem).is_err() {
                // A committed head file always names a memo id; anything else is unprovable.
                return Ok(false);
            }
            self.state_memos.insert(stem);
        }
        for (path, stem) in self.scope.state_objects.clone() {
            match rebuild_records::decode_state_revision_owner(&self.read(&path)?) {
                Ok((memo_id, revision)) => {
                    // Stem is naming authority: the slot names a revision, so
                    // the body must claim that revision, and the claimed
                    // memo's head must claim the stem — the re-walk then
                    // re-reads this file through `state_scoped`'s canonical
                    // tip resolution, the same route `pins()` takes. The id
                    // must also self-prove: `RevisionId::compute` binds
                    // `memo_id` into the minted hash, so a body reproducing
                    // its stored id is provably the one revision the slot can
                    // name — a claim any other identity was minted for is
                    // forged evidence no head's claim can anchor. The recompute
                    // runs last so a corrupt claimant head's decode failure
                    // still propagates as the head-layer code. Anything else
                    // leaves the slot's true owner unproven, so the cold
                    // outcome is produced by the full scan.
                    if revision.revision_id.as_str() != stem
                        || rebuild_records::state_head_tip_claim(self.io, &memo_id)?
                            .is_none_or(|tip| tip.as_str() != stem)
                        || revision.recomputed_id() != revision.revision_id
                    {
                        return Ok(false);
                    }
                    self.state_memos.insert(revision.memo_id);
                }
                Err(_) => {
                    // Cold scans never decode state objects standalone; a corrupt one is inert
                    // unless a head reaches it, which only the full scan can decide.
                    return Ok(false);
                }
            }
        }
        Ok(true)
    }

    /// Judges one stem-mismatched state head the way the cold `pins()` walk does.
    ///
    /// `true` absorbs the file as tolerated duplicate evidence: it is excluded from
    /// the committed listing so it re-diffs — and is re-judged — on every later
    /// pass. A duplicate is absorbed when the canonical head it shadows is itself
    /// re-anchored by this pass — `anchored_stems` carries the claimed identity
    /// only when its canonical file both diffed and decoded to that identity;
    /// a foreign body occupying the slot is absorbed evidence, never an anchor,
    /// so this premise cannot be inherited from a path's presence in the diff.
    /// The fresh durable verdict the pass installs for an anchored identity is
    /// the authority; the duplicate's divergence becomes visible to the very
    /// next pass where the anchor is stable. Otherwise the duplicate's claimed
    /// tip must still match the identity's live durable tip — the same
    /// tip-agreement the cold walk checks before absorbing, through the same
    /// `state_tip` that surfaces the identical corruption when the slot itself
    /// is occupied by a foreign body. `false` means the claim diverged from
    /// the live tip or the canonical head is gone: the full scan produces the
    /// identical `state_head_changed`/`state_head_missing` verdict.
    fn absorb_duplicate_state_head(
        &mut self,
        path: &str,
        memo_id: &MemoId,
        head: &StateHead,
        anchored_stems: &BTreeSet<String>,
    ) -> Result<bool, LomoError> {
        if anchored_stems.contains(memo_id.as_str()) {
            self.absorbed_state_heads.insert(path.to_owned());
            return Ok(true);
        }
        let tip_matches = crate::record_plan::state_tip(self.io, memo_id)?
            .is_some_and(|tip| tip.revision.revision_id == head.head_revision_id);
        if !tip_matches {
            return Ok(false);
        }
        self.absorbed_state_heads.insert(path.to_owned());
        Ok(true)
    }

    /// History records: object and tombstone bodies name their memo, but only a file whose
    /// body claim equals its filename stem sits inside path naming authority — a mismatch is
    /// unclassifiable and the full scan's `insert` produces the cold verdict on the same
    /// bytes. Removals resolve through the committed `revision_index`, and any stem the
    /// index cannot name defeats scoping.
    fn history(&mut self) -> Result<bool, LomoError> {
        for (path, stem) in self.scope.history_heads.clone() {
            let head = rebuild_records::decode_history_head(&self.read(&path)?)?;
            if head.memo_id != stem {
                // Name authority is broken; the cold outcome is produced by the full scan.
                return Ok(false);
            }
            self.history_memos.insert(stem);
        }
        for stem in self.scope.history_head_removed.clone() {
            if MemoId::parse(&stem).is_err() {
                return Ok(false);
            }
            self.history_memos.insert(stem);
        }
        for (path, stem) in self.scope.history_objects.clone() {
            let revision = rebuild_records::decode_history_revision_record(&self.read(&path)?)?;
            if revision.revision_id.as_str() != stem {
                // Name authority is broken — the body claims a revision its
                // stem cannot name; the cold outcome is produced by the full
                // scan's `insert`, which proves the same stem↔claim binding.
                return Ok(false);
            }
            self.history_memos.insert(revision.memo_id);
        }
        for (path, stem) in self.scope.history_tombstones.clone() {
            let tombstone = rebuild_records::decode_history_tombstone(&self.read(&path)?)?;
            if tombstone.revision_id.as_str() != stem {
                // Same naming authority: a prune claim outside its stem is
                // naming-authority-foreign evidence, not a scope to re-walk.
                return Ok(false);
            }
            // A tombstone for a revision the projection never indexed prunes nothing.
            if let Some(memo) = self
                .store
                .history_record_owner(tombstone.revision_id.as_str())?
            {
                self.history_memos.insert(memo);
            }
        }
        for stem in self
            .scope
            .history_object_removed
            .iter()
            .chain(&self.scope.history_tombstone_removed)
        {
            match self.store.history_record_owner(stem)? {
                Some(memo) => {
                    self.history_memos.insert(memo);
                }
                None => return Ok(false),
            }
        }
        Ok(true)
    }

    /// Trash records, decoded after every claimant expansion (purge lifts, memo retirements)
    /// so the suppression set and the read set are both final. `false` defeats scoping.
    ///
    /// Before a changed record re-claims its identity, a committed trashed row no
    /// live document re-emitted is retired first: that row is the record's own
    /// earlier claim — never live-document attestation — so the merge must meet
    /// `current=None` for it, exactly as a cold scan does. Without the retire,
    /// the merge would anchor the stale committed row (or trip the document
    /// attestation guard on a claim the row never attested). `doc_attested_ids`
    /// records the opposite lane — identities the apply may keep as document
    /// attestation — for the store's post-apply self-certification.
    ///
    /// The removal arm retires the removed record's owner unconditionally for
    /// the same reason the claim arm retires echoes: the cascade delete is
    /// what clears `memo_trash` membership a live record's absence can no
    /// longer re-attest. Whether the row comes back is the lane question the
    /// earlier disambiguation already delegated — a re-emitted identity is
    /// re-inserted by `memo_upserts` after the removal lands; an identity no
    /// document re-emits stays retired, both byte-identical to the cold scan.
    fn trash(&mut self) -> Result<bool, LomoError> {
        let committed_trashed = self.store.trashed_memo_ids()?;
        for path in self.scope.trash_reads.clone() {
            let projection = match self.decoded_trash.remove(&path) {
                Some(projection) => projection,
                None => decode_trash_projection(&self.read(&path)?, &path)?,
            };
            if self.purged_ids.contains(&projection.memo.memo_id) {
                continue;
            }
            let memo_id = projection.memo.memo_id.clone();
            if committed_trashed.contains(&memo_id) && !self.emitted_ids.contains(&memo_id) {
                self.retire_memo(&memo_id)?;
            }
            if self.emitted_ids.contains(&memo_id)
                || (!committed_trashed.contains(&memo_id)
                    && !self.memo_removes.contains(&memo_id)
                    && self.store.memo_source_path(&memo_id)?.is_some())
            {
                self.facts.doc_attested_ids.insert(memo_id);
            }
            self.facts.trash_upserts.push(projection);
        }
        for path in self.scope.trash_removed.clone() {
            if let Some(owner) =
                canonical_owner(&committed_trashed, &path, trash_record_relative_path)?
            {
                self.retire_memo(&owner)?;
            } else if canonical_owner(&self.purged_ids, &path, trash_record_relative_path)?
                .is_none()
            {
                // Neither a live trash row nor a suppressed purge can claim this path.
                return Ok(false);
            }
        }
        // A tombstone-deleted row a rescanned document re-projects loses its
        // pin row to the same cascade delete `apply_purged_id` performs, so
        // the state re-walk re-derives that fact for the reinserted row —
        // exactly what `retire_memo` does for every removal it schedules.
        // Identities no document re-emitted stay deleted and owe no fact.
        for memo_id in self.facts.purged_upserts.clone() {
            if committed_trashed.contains(&memo_id) && self.emitted_ids.contains(&memo_id) {
                self.state_memos.insert(memo_id);
            }
        }
        Ok(true)
    }

    /// Whether `memo_id` owns a `memo` row after this apply — the only condition under
    /// which a pin fact may commit, identical to the cold scan's live-identity filter.
    /// A document re-emit or a surviving trash claim re-creates the row inside this same
    /// apply even when the identity was also retired; a committed row survives unless this
    /// pass removed it or a landing tombstone strips its trash membership — the exact row
    /// set `apply_purged_id` deletes.
    fn memo_row_survives(
        &self,
        memo_id: &str,
        committed_trashed: &BTreeSet<String>,
    ) -> Result<bool, LomoError> {
        if self.emitted_ids.contains(memo_id)
            || self
                .facts
                .trash_upserts
                .iter()
                .any(|trash| trash.memo.memo_id == memo_id)
        {
            return Ok(true);
        }
        if self.memo_removes.contains(memo_id)
            || (self.facts.purged_upserts.iter().any(|id| id == memo_id)
                && committed_trashed.contains(memo_id))
        {
            return Ok(false);
        }
        self.store
            .memo_source_path(memo_id)
            .map(|path| path.is_some())
    }

    /// Per-memo history and state re-walks, identical to what the cold scan projects. Fresh
    /// initial chains arrive with their row already known; any memo that also surfaces through
    /// a record delta is re-walked instead, which covers the just-written files identically.
    ///
    /// The state re-walk still reads every scheduled memo's durable tip — corruption parity
    /// with the cold `pins()` scan — and answers with the same three verdicts the cold side
    /// distinguishes. A pinned tip over a surviving row is a `pin_upserts` fact; a pinned tip
    /// over a dead identity is a dead fact and an unpinned tip is durable's explicit "no pin" —
    /// both degrade to `pin_removes`, exactly what `attested_ids` contributes on the cold
    /// path. A missing head means durable never answered — and a head file whose decoded
    /// body names another identity is that identity's evidence, not this one's head, so it
    /// answers `Unattested` identically: the committed `memo_pin` row keeps its verdict
    /// wherever a memo row survives, the same carry `copy_saf_private_state` performs for
    /// an identity outside `attested_ids`.
    ///
    /// The re-walk's memo set is the diff-scoped state records' owners plus every identity a
    /// committed `memo_pin` row asserts a verdict for. Cache pins are evidence the projection
    /// carries — never an answer of their own — so durable attestation decides each pass
    /// whether a committed row is stale evidence, the same gate `pin_attested_ids` applies
    /// on the materialize path where an attested identity's cache row is never copied.
    fn rewalk(&mut self) -> Result<(), LomoError> {
        for memo in self.history_memos.clone() {
            let memo_id = MemoId::parse(&memo)?;
            let revisions = rebuild_records::history_scoped(self.io, &memo_id)?;
            self.facts.history_replaces.push(ScannedMemoHistoryReplace {
                memo_id: memo,
                revisions,
            });
        }
        for (memo, projection) in std::mem::take(&mut self.initial_history) {
            if self.history_memos.contains(&memo) {
                continue;
            }
            self.facts.history_replaces.push(ScannedMemoHistoryReplace {
                memo_id: memo,
                revisions: vec![projection],
            });
        }
        let committed_trashed = self.store.trashed_memo_ids()?;
        let committed_pins = self.store.pinned_memo_timestamps()?;
        // Every committed `memo_pin` row owes the durable re-walk a verdict — durable
        // attestation, not diff scope, decides whether a cache pin is stale evidence.
        // A row naming a string no memo id parses as can never own a projection row:
        // the same absence the cold `WHERE EXISTS(memo)` gate leaves behind.
        for memo_id in committed_pins.keys() {
            if MemoId::parse(memo_id).is_ok() {
                self.state_memos.insert(memo_id.clone());
            } else {
                self.facts.pin_removes.push(memo_id.clone());
            }
        }
        // A `memo` row this apply re-creates arrives without a `memo_pin` row: the cascade
        // delete destroyed it together with the old row, and its identity sits outside every
        // other supply set — no state record diffed, nothing retired it, and the
        // committed-pin snapshot no longer names it. The cold scan re-derives the verdict
        // for every identity it projects a row for (the `live_ids` filter), so an identity
        // whose committed row is gone right now joins the durable re-walk too. A row that
        // was never removed keeps its committed pin and already owes a verdict through the
        // merge above; only an absent row could have lost the pin to the cascade.
        let recreated_ids = self
            .emitted_ids
            .iter()
            .chain(
                self.facts
                    .trash_upserts
                    .iter()
                    .map(|trash| &trash.memo.memo_id),
            )
            .filter(|memo_id| !self.state_memos.contains(*memo_id))
            .filter(|memo_id| MemoId::parse(memo_id).is_ok())
            .cloned()
            .collect::<Vec<_>>();
        for memo_id in recreated_ids {
            if self.store.memo_source_path(&memo_id)?.is_none() {
                self.state_memos.insert(memo_id);
            }
        }
        for memo in self.state_memos.clone() {
            let memo_id = MemoId::parse(&memo)?;
            match rebuild_records::state_scoped(self.io, &memo_id)? {
                rebuild_records::ScopedPinVerdict::Pinned(pin)
                    if self.memo_row_survives(&memo, &committed_trashed)? =>
                {
                    self.facts.pin_upserts.push(pin);
                }
                rebuild_records::ScopedPinVerdict::Pinned(_)
                | rebuild_records::ScopedPinVerdict::Unpinned => {
                    self.facts.pin_removes.push(memo);
                }
                rebuild_records::ScopedPinVerdict::Unattested => {
                    if !self.memo_row_survives(&memo, &committed_trashed)? {
                        // No memo row survives: the cold `WHERE EXISTS(memo)` gate never
                        // carries this pin, so the committed row leaves — an idempotent
                        // delete where the FK cascade already took it with the row.
                        self.facts.pin_removes.push(memo);
                    } else if let Some(pinned_at_ms) = committed_pins.get(&memo) {
                        // Durable has no pin answer for this identity — the committed
                        // cache pin keeps its verdict wherever a memo row survives, the
                        // cold-side carry verbatim. Re-emitting it as an upsert is an
                        // idempotent rewrite for a row this apply never touched and
                        // restores the pin the FK cascade deleted for a row it removed
                        // and re-projected. A row already holding a non-positive
                        // timestamp cannot be re-emitted (fact validation rejects it);
                        // leaving it untouched is the verbatim carry `INSERT OR IGNORE`
                        // performs on the materialize path.
                        if *pinned_at_ms > 0 {
                            self.facts.pin_upserts.push(ScannedPinProjection {
                                memo_id: memo,
                                pinned_at_ms: *pinned_at_ms,
                            });
                        }
                    }
                }
            }
        }
        self.facts.memo_removes = self.memo_removes.iter().cloned().collect();
        Ok(())
    }

    /// Listing rows. Files written during this scan (identity records, initial history)
    /// post-date the listing that provoked it, so their tokens refresh from a parent-directory
    /// re-list — the same mechanism the full inventory uses.
    fn finish_listing(
        mut self,
        config: &WorkspaceSessionConfig,
        executor: &Arc<dyn PlatformActionExecutor>,
        upsert_tokens: BTreeMap<String, String>,
        removed_paths: &[String],
    ) -> Result<ScannedIncrementalFacts, LomoError> {
        self.facts.listing_removes = removed_paths.to_vec();
        // Absorbed duplicate heads are tolerated evidence, never baseline rows: a committed
        // row would pull them out of the diff set and let a stale claim go invisible the
        // moment the canonical tip moves. The remove here is also the eviction for any
        // row an older commit already wrote.
        self.facts
            .listing_removes
            .extend(self.absorbed_state_heads.iter().cloned());
        let mut listing_rows = upsert_tokens;
        refresh_listing_tokens(config, executor, &self.written_paths, &mut listing_rows)?;
        for path in &self.absorbed_state_heads {
            listing_rows.remove(path);
        }
        self.facts.listing_upserts = listing_rows
            .into_iter()
            .map(|(path, digest)| ScannedListingRow { path, digest })
            .collect();
        Ok(self.facts)
    }
}

/// The diffed delta: current listing tokens, the classified path scope, the upsert token rows
/// and the paths the listing lost. Bundled so the gather driver stays a three-parameter call.
struct ListingDelta {
    current: BTreeMap<String, String>,
    scope: PathScope,
    upsert_tokens: BTreeMap<String, String>,
    removed_paths: Vec<String>,
}

/// Gathers the facts for every proven scope. Returns `None` when a scope could not be proven
/// during the read phase — the caller then falls back to the full scan.
fn gather_facts(
    config: &WorkspaceSessionConfig,
    executor: &Arc<dyn PlatformActionExecutor>,
    store: &Store,
    mut delta: ListingDelta,
) -> Result<Option<ScannedIncrementalFacts>, LomoError> {
    let io = WorkspaceIo { config, executor };
    let mut gather = Gather {
        io: &io,
        store,
        current: &delta.current,
        scope: &mut delta.scope,
        facts: ScannedIncrementalFacts::default(),
        memo_removes: BTreeSet::new(),
        history_memos: BTreeSet::new(),
        state_memos: BTreeSet::new(),
        purged_ids: BTreeSet::new(),
        initial_history: BTreeMap::new(),
        written_paths: Vec::new(),
        emitted_ids: BTreeSet::new(),
        decoded_trash: BTreeMap::new(),
        absorbed_state_heads: BTreeSet::new(),
    };
    // Phase order matters: purge fixes the suppression set, identities expand the document
    // scope, trash-claim disambiguation widens it once more for every live-document lane a
    // trash-authority change can reach (re-claims, record removals, landing tombstones),
    // documents retire memos and write initial history, state/history decode their
    // records, and trash decodes last so every claimant is already visible.
    if !gather.purge()? || !gather.identities(config)? {
        return Ok(None);
    }
    gather.disambiguate_trash_claims()?;
    gather.documents(config, executor)?;
    if !gather.state()? || !gather.history()? || !gather.trash()? {
        return Ok(None);
    }
    gather.rewalk()?;
    Ok(Some(gather.finish_listing(
        config,
        executor,
        delta.upsert_tokens,
        &delta.removed_paths,
    )?))
}
