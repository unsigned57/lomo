//! Private identity mappings and operation evidence, encoded with the existing LOMO envelope.
//! This module plans facts only. The application persists them through its platform executor.

use std::collections::{BTreeMap, BTreeSet};

use crate::limits::{conflict, validation};
use lomo_core::{LomoError, OperationId};
use serde::{Deserialize, Serialize};

use crate::{
    ByteSpan, DocumentPatchPlan, LomoPayload, LomoRecordKind, MemoId, MemoLocator,
    SourceFingerprint, WorkspaceDocument, WorkspaceRelativePath, WorkspaceRootId, decode_record,
    encode_record, parse_workspace_document,
};

mod integrity;
use integrity::IdentityMapWire;

const IDENTITY_SCHEMA: u32 = 1;
const IDENTITY_RECORD_ID: &str = "memo-identity-map-v1";

/// Retained durable operation witness per document.
///
/// The tail answers "was this operation id already consumed" for cross-device replays
/// within the retained window and keeps `is_replay`/validation work bounded. Operations
/// older than the window are still witnessed by the session projection's committed-
/// operation index and the intent journal's retired epochs, so the record never needs
/// unbounded growth to stay honest.
pub const MAX_RETAINED_OPERATIONS: usize = 64;

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct MemoBinding {
    memo_id: MemoId,
    locator: MemoLocator,
    block_fingerprint: SourceFingerprint,
}

impl MemoBinding {
    #[must_use]
    pub const fn memo_id(&self) -> &MemoId {
        &self.memo_id
    }

    #[must_use]
    pub const fn locator(&self) -> &MemoLocator {
        &self.locator
    }

    /// Fingerprint of the block bytes this binding last observed — carried across a
    /// patch when the block's bytes provably did not change.
    #[must_use]
    pub const fn block_fingerprint(&self) -> &SourceFingerprint {
        &self.block_fingerprint
    }
}

/// The durable identity authority advanced by one proven patch plus the document
/// projection the transition was proven against.
///
/// `document` is the same parse that verified the transition's block accounting — the
/// caller publishes it instead of re-parsing result bytes. `refingerprinted_blocks`
/// counts the result blocks whose content was actually re-hashed: the patch target
/// and any block the patched span straddles. Sibling blocks hold byte-identical
/// content, so a single-memo patch never pays a whole-document refingerprint.
#[derive(Clone, Debug)]
pub struct IdentityPatchOutcome {
    /// The identity map advanced by the proven transition.
    pub map: MemoIdentityMap,
    /// The parsed post-patch document.
    pub document: WorkspaceDocument,
    /// Result blocks whose `block_fingerprint` was recomputed this transition.
    pub refingerprinted_blocks: usize,
}

/// Ambiguity is a durable state, not an excuse to assign old IDs by ordering or similarity.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct MemoIdentityConflict {
    previous: MemoBinding,
    candidates: Vec<MemoLocator>,
    observed_fingerprint: SourceFingerprint,
}

impl MemoIdentityConflict {
    #[must_use]
    pub const fn previous(&self) -> &MemoBinding {
        &self.previous
    }

    #[must_use]
    pub fn candidates(&self) -> &[MemoLocator] {
        &self.candidates
    }

    #[must_use]
    pub const fn observed_fingerprint(&self) -> &SourceFingerprint {
        &self.observed_fingerprint
    }
}

/// The application supplies an independently allocated ID on create; no ID derives from a block.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum MemoIdentityChange {
    Append(MemoId),
    Update(MemoId),
    Remove(MemoId),
    /// Rebinds a retired ID onto a newly appended physical block. New creates still cannot reuse it.
    Restore(MemoId),
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
enum IdentityTransition {
    Initialize(Vec<MemoId>),
    Patch(MemoIdentityChange),
    External,
    Discover(Vec<MemoId>),
    RetireAbsent(Vec<MemoId>),
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct MemoIdentityOperation {
    operation_id: OperationId,
    before: Option<SourceFingerprint>,
    after: SourceFingerprint,
    transition: IdentityTransition,
}

impl MemoIdentityOperation {
    #[must_use]
    pub const fn operation_id(&self) -> &OperationId {
        &self.operation_id
    }
}

/// Allocation and fingerprint state proven at the start of the retained operation tail.
///
/// When the journal outgrows [`MAX_RETAINED_OPERATIONS`], evicted operations fold into
/// this checkpoint: `after` is the source fingerprint the first retained operation must
/// cite as `before`, and the two ID sets are the allocation accounting immediately
/// before it. Validation replays the tail on top of this state, so truncation cannot
/// silently rewrite the record's identity accounting.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct IdentityJournalCheckpoint {
    after: SourceFingerprint,
    allocated_ids: BTreeSet<MemoId>,
    retired_ids: BTreeSet<MemoId>,
}

impl IdentityJournalCheckpoint {
    /// Folds one evicted operation into the checkpoint: the anchor advances to the
    /// operation's result fingerprint and its identity transition is applied to the
    /// retained allocation sets.
    fn apply(&mut self, operation: &MemoIdentityOperation) {
        self.after = operation.after.clone();
        match &operation.transition {
            IdentityTransition::Initialize(ids) | IdentityTransition::Discover(ids) => {
                self.allocated_ids.extend(ids.iter().cloned());
            }
            IdentityTransition::Patch(MemoIdentityChange::Append(id)) => {
                self.allocated_ids.insert(id.clone());
            }
            IdentityTransition::RetireAbsent(ids) => {
                self.retired_ids.extend(ids.iter().cloned());
            }
            IdentityTransition::Patch(MemoIdentityChange::Remove(id)) => {
                self.retired_ids.insert(id.clone());
            }
            IdentityTransition::Patch(MemoIdentityChange::Restore(id)) => {
                self.retired_ids.remove(id);
            }
            IdentityTransition::Patch(MemoIdentityChange::Update(_))
            | IdentityTransition::External => {}
        }
    }
}

/// One document's identity authority. Unbound blocks and conflicts are explicit scan states.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(try_from = "IdentityMapWire")]
pub struct MemoIdentityMap {
    schema: u32,
    root_id: WorkspaceRootId,
    path: WorkspaceRelativePath,
    fingerprint: SourceFingerprint,
    bindings: Vec<MemoBinding>,
    unbound: Vec<MemoLocator>,
    conflicts: Vec<MemoIdentityConflict>,
    allocated_ids: BTreeSet<MemoId>,
    retired_ids: BTreeSet<MemoId>,
    /// Retained replay-witness tail. Bounded by [`MAX_RETAINED_OPERATIONS`]; records
    /// written before the bound existed still deserialize at full depth and fold on
    /// their next proven transition.
    operations: Vec<MemoIdentityOperation>,
    /// Durable accounting for the history evicted from `operations`. Absent until the
    /// first truncation — older readers reject a truncated record through
    /// `deny_unknown_fields` rather than validating it silently.
    #[serde(skip_serializing_if = "Option::is_none")]
    journal_checkpoint: Option<IdentityJournalCheckpoint>,
}

impl MemoIdentityMap {
    /// Establishes independently allocated IDs or confirmed legacy IDs for the physical blocks.
    ///
    /// # Errors
    /// Rejects duplicate IDs and any assignment that does not cover the document exactly.
    pub fn initialize(
        operation_id: OperationId,
        root_id: WorkspaceRootId,
        path: WorkspaceRelativePath,
        document: &WorkspaceDocument,
        ids: Vec<MemoId>,
    ) -> Result<Self, LomoError> {
        let bindings = bind_all(root_id, &path, document, &ids)?;
        let allocated_ids: BTreeSet<_> = ids.iter().cloned().collect();
        if allocated_ids.len() != ids.len() {
            return Err(reused_id());
        }
        let fingerprint = document.source().fingerprint().clone();
        let map = Self {
            schema: IDENTITY_SCHEMA,
            root_id,
            path,
            fingerprint: fingerprint.clone(),
            bindings,
            unbound: Vec::new(),
            conflicts: Vec::new(),
            allocated_ids,
            retired_ids: BTreeSet::new(),
            operations: vec![MemoIdentityOperation {
                operation_id,
                before: None,
                after: fingerprint,
                transition: IdentityTransition::Initialize(ids),
            }],
            journal_checkpoint: None,
        };
        map.validate()?;
        Ok(map)
    }

    #[must_use]
    pub fn memo_ids(&self) -> Vec<MemoId> {
        self.bindings
            .iter()
            .map(|binding| binding.memo_id.clone())
            .collect()
    }

    /// Chooses the identity transition attested by a recoverable trash document.
    /// Fresh private maps admit its ID once; existing maps still require a retired allocation.
    ///
    /// # Errors
    /// Rejects malformed trash facts and recovery into a different source document.
    pub fn trash_restore_change(
        &self,
        trash: &crate::TrashRecordV1,
    ) -> Result<MemoIdentityChange, LomoError> {
        trash.validate()?;
        if trash.source_path != self.path.as_str() {
            return Err(validation(
                "trash_restore_source_mismatch",
                "trash recovery must target its original document",
            ));
        }
        let id = MemoId::parse(&trash.memo_id)?;
        Ok(if self.allocated_ids.contains(&id) {
            MemoIdentityChange::Restore(id)
        } else {
            MemoIdentityChange::Append(id)
        })
    }

    /// Looks up the current physical location of a durable ID.
    ///
    /// # Errors
    /// Unresolved, retired and unknown identities cannot address a write.
    pub fn locator(&self, memo_id: &MemoId) -> Result<&MemoLocator, LomoError> {
        self.bindings
            .iter()
            .find(|binding| &binding.memo_id == memo_id)
            .map(|binding| &binding.locator)
            .ok_or_else(|| {
                conflict(
                    "memo_identity_unresolved",
                    "memo has no uniquely proven current locator",
                )
            })
    }

    /// The document path this identity authority covers.
    ///
    /// Path-scoped reconcile uses it to prove which document a changed `.lomo/identity`
    /// record governs; the durable file name is a content hash of `(root_id, path)` and
    /// cannot be inverted without the record body.
    #[must_use]
    pub const fn path(&self) -> &WorkspaceRelativePath {
        &self.path
    }

    /// The logical root this identity authority was written for.
    ///
    /// A record minted under a different root can never satisfy this session's identity
    /// lookup, which hashes its own `root_id` into the canonical file name.
    #[must_use]
    pub const fn root_id(&self) -> WorkspaceRootId {
        self.root_id
    }

    #[must_use]
    pub fn bindings(&self) -> &[MemoBinding] {
        &self.bindings
    }

    #[must_use]
    pub fn unbound(&self) -> &[MemoLocator] {
        &self.unbound
    }

    #[must_use]
    pub fn conflicts(&self) -> &[MemoIdentityConflict] {
        &self.conflicts
    }

    #[must_use]
    pub const fn retired_ids(&self) -> &BTreeSet<MemoId> {
        &self.retired_ids
    }

    /// Proves that every retained transition since a baseline patched another identity.
    /// Unknown/evicted baselines and external changes never authorize rebasing a delete.
    #[must_use]
    pub fn unchanged_since(
        &self,
        id: &MemoId,
        baseline: &SourceFingerprint,
        current: &SourceFingerprint,
    ) -> bool {
        if &self.fingerprint != current {
            return false;
        }
        let Some(start) = self
            .operations
            .iter()
            .rposition(|op| op.before.as_ref() == Some(baseline))
        else {
            return false;
        };
        self.operations.iter().skip(start).all(|op| {
            matches!(&op.transition, IdentityTransition::Patch(
                MemoIdentityChange::Append(other)
                | MemoIdentityChange::Update(other)
                | MemoIdentityChange::Remove(other)
                | MemoIdentityChange::Restore(other)
            ) if other != id)
        })
    }

    #[must_use]
    pub fn operations(&self) -> &[MemoIdentityOperation] {
        &self.operations
    }

    /// Carries IDs through one proven local patch, preserving siblings and reserving removed IDs.
    ///
    /// Returns the post-patch document projection the transition was proven against — the
    /// same parse that verified block accounting — together with how many result blocks
    /// paid a fresh `block_fingerprint`. Sibling blocks hold byte-identical content, so a
    /// single-memo patch bills only the blocks the patched span touches.
    ///
    /// # Errors
    /// Rejects stale maps, unresolved identities, ID reuse, mismatched retries and patches that
    /// change a different block or create an unexpected number of document blocks.
    pub fn apply_patch(
        &self,
        operation_id: OperationId,
        change: MemoIdentityChange,
        before: &WorkspaceDocument,
        patch: &DocumentPatchPlan,
    ) -> Result<IdentityPatchOutcome, LomoError> {
        let operation = MemoIdentityOperation {
            operation_id,
            before: Some(before.source().fingerprint().clone()),
            after: patch.result_fingerprint().clone(),
            transition: IdentityTransition::Patch(change.clone()),
        };
        if self.is_replay(&operation)? {
            // The transition was already consumed: the map is the outcome. The document
            // handed back must still be the post-patch one — `before` already is when the
            // file carries the committed bytes; otherwise re-derive it from the plan's
            // validated result source rather than accepting the stale projection.
            let document = if before.source().fingerprint() == patch.result_fingerprint() {
                before.clone()
            } else {
                self.parse_result(patch)?
            };
            return Ok(IdentityPatchOutcome {
                map: self.clone(),
                document,
                refingerprinted_blocks: 0,
            });
        }
        self.require_complete(before)?;
        if patch.path() != &self.path || patch.expected_fingerprint() != &self.fingerprint {
            return Err(conflict(
                "stale_identity_patch",
                "patch belongs to another path or source version",
            ));
        }
        let mut ids = self.memo_ids();
        self.transition_ids(&change, before, patch, &mut ids)?;
        let after = self.parse_result(patch)?;
        let (bindings, refingerprinted_blocks) = self.rebind_patched(&after, &ids, patch)?;
        let mut next = self.clone();
        next.bindings = bindings;
        match change {
            MemoIdentityChange::Append(id) => {
                next.allocated_ids.insert(id);
            }
            MemoIdentityChange::Remove(id) => {
                next.retired_ids.insert(id);
            }
            MemoIdentityChange::Restore(id) => {
                next.retired_ids.remove(&id);
            }
            MemoIdentityChange::Update(_) => {}
        }
        next.fingerprint = after.source().fingerprint().clone();
        next.operations.push(operation);
        next.retain_witness_window();
        next.validate()?;
        Ok(IdentityPatchOutcome {
            map: next,
            document: after,
            refingerprinted_blocks,
        })
    }

    /// Parses the plan's validated result source. The parse is the structural gate:
    /// block boundaries the planner assumed must still exist in the actual result.
    fn parse_result(&self, patch: &DocumentPatchPlan) -> Result<WorkspaceDocument, LomoError> {
        let filename =
            self.path.as_str().rsplit('/').next().ok_or_else(|| {
                validation("invalid_workspace_path", "document filename is missing")
            })?;
        let stem = filename.strip_suffix(".md").unwrap_or(filename);
        parse_workspace_document(patch.result_source(), stem)
    }

    /// Rebinds IDs through the proven patch, refingerprinting only the blocks the
    /// patched span touches.
    ///
    /// Position proof: the patch rewrites exactly `target_span`, so bytes before it are
    /// verbatim and bytes after it are verbatim and shifted by the replacement delta.
    /// A result block landing at precisely a previous binding's patch-adjusted
    /// coordinates holds identical bytes and may carry that `block_fingerprint`;
    /// every other result block — the patch target and anything the span straddles —
    /// is re-hashed from the result source.
    fn rebind_patched(
        &self,
        after: &WorkspaceDocument,
        ids: &[MemoId],
        patch: &DocumentPatchPlan,
    ) -> Result<(Vec<MemoBinding>, usize), LomoError> {
        if ids.len() != after.memos().len() {
            return Err(count_mismatch());
        }
        let span = patch.target_span();
        let shift = i128::from(
            u64::try_from(patch.replacement().len())
                .map_err(|error| validation("invalid_memo_locator", &error.to_string()))?,
        ) - i128::from(
            u64::try_from(span.len())
                .map_err(|error| validation("invalid_memo_locator", &error.to_string()))?,
        );
        let span_start = u64::try_from(span.start())
            .map_err(|error| validation("invalid_memo_locator", &error.to_string()))?;
        let span_end = u64::try_from(span.end())
            .map_err(|error| validation("invalid_memo_locator", &error.to_string()))?;
        let mut carried = std::collections::HashMap::with_capacity(self.bindings.len());
        for binding in &self.bindings {
            let start = binding.locator().byte_start();
            let end = binding.locator().byte_end();
            if end <= span_start {
                carried.insert((start, end), binding.block_fingerprint());
            } else if start >= span_end {
                let shifted_start = u64::try_from(i128::from(start) + shift)
                    .map_err(|error| validation("invalid_memo_locator", &error.to_string()))?;
                let shifted_end = u64::try_from(i128::from(end) + shift)
                    .map_err(|error| validation("invalid_memo_locator", &error.to_string()))?;
                carried.insert((shifted_start, shifted_end), binding.block_fingerprint());
            }
            // A binding the span straddles has no carry position: its block is the one
            // being rewritten and its result bytes are always re-hashed below.
        }
        let mut refingerprinted = 0usize;
        let mut bindings = Vec::with_capacity(ids.len());
        for (index, memo_id) in ids.iter().enumerate() {
            let index = u32::try_from(index)
                .map_err(|error| validation("memo_block_limit", &error.to_string()))?;
            let memo = after
                .memos()
                .get(index as usize)
                .ok_or_else(count_mismatch)?;
            let locator = MemoLocator::new(self.root_id, self.path.clone(), after, index)?;
            let position = (
                u64::try_from(memo.memo_span().start())
                    .map_err(|error| validation("invalid_memo_locator", &error.to_string()))?,
                u64::try_from(memo.memo_span().end())
                    .map_err(|error| validation("invalid_memo_locator", &error.to_string()))?,
            );
            let block_fingerprint = if let Some(fingerprint) = carried.get(&position) {
                (*fingerprint).clone()
            } else {
                refingerprinted += 1;
                SourceFingerprint::of_bytes(after.source().slice(memo.memo_span())?.as_bytes())
            };
            bindings.push(MemoBinding {
                memo_id: memo_id.clone(),
                locator,
                block_fingerprint,
            });
        }
        Ok((bindings, refingerprinted))
    }

    fn transition_ids(
        &self,
        change: &MemoIdentityChange,
        before: &WorkspaceDocument,
        patch: &DocumentPatchPlan,
        ids: &mut Vec<MemoId>,
    ) -> Result<(), LomoError> {
        let span = patch.target_span();
        match change {
            MemoIdentityChange::Append(id) => {
                if self.allocated_ids.contains(id) {
                    return Err(reused_id());
                }
                require_append_span(span, before)?;
                ids.push(id.clone());
            }
            MemoIdentityChange::Restore(id) => {
                if !self.allocated_ids.contains(id) || !self.retired_ids.contains(id) {
                    return Err(reused_id());
                }
                require_append_span(span, before)?;
                ids.push(id.clone());
            }
            MemoIdentityChange::Update(id) | MemoIdentityChange::Remove(id) => {
                let locator = self.locator(id)?;
                let memo = locator.resolve(self.root_id, &self.path, before)?;
                match change {
                    MemoIdentityChange::Update(_) => {
                        if span.start() < memo.body_span().start()
                            || span.end() > memo.body_span().end()
                        {
                            return Err(patch_mismatch());
                        }
                    }
                    MemoIdentityChange::Remove(_) => {
                        let bom_offset = usize::from(
                            memo.memo_span().start() == 0
                                && before.source().as_bytes().starts_with(&[0xef, 0xbb, 0xbf]),
                        ) * 3;
                        if span.start() > memo.memo_span().start() + bom_offset
                            || span.end() < memo.memo_span().end()
                        {
                            return Err(patch_mismatch());
                        }
                        ids.remove(locator.block_index() as usize);
                    }
                    MemoIdentityChange::Append(_) | MemoIdentityChange::Restore(_) => {
                        return Err(patch_mismatch());
                    }
                }
            }
        }
        Ok(())
    }

    fn require_complete(&self, document: &WorkspaceDocument) -> Result<(), LomoError> {
        if document.source().fingerprint() != &self.fingerprint {
            return Err(conflict(
                "stale_identity_map",
                "identity map belongs to an earlier source version",
            ));
        }
        let complete_count = self.bindings.len() == document.memos().len();
        if !self.unbound.is_empty() || !self.conflicts.is_empty() || !complete_count {
            return Err(conflict(
                "memo_identity_unresolved",
                "resolve identity conflicts before editing this document",
            ));
        }
        for binding in &self.bindings {
            binding
                .locator
                .resolve(self.root_id, &self.path, document)?;
        }
        Ok(())
    }

    /// Retires all identities after the platform has proved the source file absent.
    ///
    /// Unlike an edited document, absence leaves no blocks whose ownership could be ambiguous.
    /// Allocation and operation witnesses survive so recreating the path cannot reuse old IDs.
    ///
    /// # Errors
    /// Rejects a reused operation ID or an invalid journal transition.
    pub fn retire_missing_document(&self, operation_id: OperationId) -> Result<Self, LomoError> {
        let empty = crate::SourceBytes::try_from_bytes(Vec::new())?;
        if let Some(previous) = self
            .operations
            .iter()
            .find(|op| op.operation_id == operation_id)
        {
            if matches!(previous.transition, IdentityTransition::RetireAbsent(_))
                && previous.after == *empty.fingerprint()
                && self.fingerprint == *empty.fingerprint()
            {
                return Ok(self.clone());
            }
            return Err(conflict(
                "identity_operation_mismatch",
                "operation ID was already used with other identity facts",
            ));
        }
        let ids: Vec<_> = self
            .allocated_ids
            .difference(&self.retired_ids)
            .cloned()
            .collect();
        let operation = MemoIdentityOperation {
            operation_id,
            before: Some(self.fingerprint.clone()),
            after: empty.fingerprint().clone(),
            transition: IdentityTransition::RetireAbsent(ids.clone()),
        };
        let mut next = self.clone();
        next.bindings.clear();
        next.conflicts.clear();
        next.unbound.clear();
        next.retired_ids.extend(ids);
        next.fingerprint = empty.fingerprint().clone();
        next.operations.push(operation);
        next.retain_witness_window();
        next.validate()?;
        Ok(next)
    }

    /// Reconciles external edits only with unique, equal raw block bytes. Ambiguity is recorded.
    ///
    /// # Errors
    /// Rejects operation-ID reuse, malformed records or source coordinates.
    pub fn reconcile_external(
        &self,
        operation_id: OperationId,
        document: &WorkspaceDocument,
    ) -> Result<Self, LomoError> {
        if let Some(previous) = self
            .operations
            .iter()
            .find(|operation| operation.operation_id == operation_id)
        {
            if previous.after == *document.source().fingerprint()
                && previous.transition == IdentityTransition::External
            {
                return Ok(self.clone());
            }
            return Err(conflict(
                "identity_operation_mismatch",
                "operation ID was already used with other identity facts",
            ));
        }
        if document.source().fingerprint() == &self.fingerprint {
            return Ok(self.clone());
        }
        let operation = MemoIdentityOperation {
            operation_id,
            before: Some(self.fingerprint.clone()),
            after: document.source().fingerprint().clone(),
            transition: IdentityTransition::External,
        };
        let candidates = physical_blocks(self.root_id, &self.path, document)?;
        let previous: Vec<_> = self
            .bindings
            .iter()
            .chain(self.conflicts.iter().map(|conflict| &conflict.previous))
            .collect();
        // Identity survives exactly when this raw-block fingerprint is unique on both sides.
        // Index the equivalence classes once instead of re-scanning both documents per ID.
        let mut candidates_by_hash: BTreeMap<&str, Vec<_>> = BTreeMap::new();
        for (locator, hash) in &candidates {
            candidates_by_hash
                .entry(hash.as_str())
                .or_default()
                .push((locator, hash));
        }
        let mut prior_counts = BTreeMap::new();
        for binding in &previous {
            *prior_counts
                .entry(binding.block_fingerprint.as_str())
                .or_insert(0usize) += 1;
        }
        let mut next = self.clone();
        next.bindings.clear();
        next.conflicts.clear();
        next.fingerprint = document.source().fingerprint().clone();
        for old in &previous {
            let matches = candidates_by_hash
                .get(old.block_fingerprint.as_str())
                .map_or(&[][..], Vec::as_slice);
            if let [(locator, hash)] = matches
                && prior_counts.get(old.block_fingerprint.as_str()) == Some(&1)
            {
                next.bindings.push(MemoBinding {
                    memo_id: old.memo_id.clone(),
                    locator: (*locator).clone(),
                    block_fingerprint: (*hash).clone(),
                });
            } else {
                next.conflicts.push(MemoIdentityConflict {
                    previous: (*old).clone(),
                    candidates: matches
                        .iter()
                        .map(|(locator, _)| (*locator).clone())
                        .collect(),
                    observed_fingerprint: next.fingerprint.clone(),
                });
            }
        }
        next.bindings
            .sort_by_key(|binding| binding.locator.block_index());
        let bound: BTreeSet<_> = next
            .bindings
            .iter()
            .map(|binding| binding.locator.block_index())
            .collect();
        next.unbound = candidates
            .into_iter()
            .map(|(locator, _)| locator)
            .filter(|locator| !bound.contains(&locator.block_index()))
            .collect();
        next.operations.push(operation);
        next.retain_witness_window();
        next.validate()?;
        Ok(next)
    }

    /// Assigns fresh IDs to genuinely new blocks after a conflict-free external discovery.
    ///
    /// # Errors
    /// Rejects ambiguity, stale bytes, duplicate IDs and assignment count mismatches.
    pub fn assign_discovered(
        &self,
        operation_id: OperationId,
        document: &WorkspaceDocument,
        ids: Vec<MemoId>,
    ) -> Result<Self, LomoError> {
        let operation = MemoIdentityOperation {
            operation_id,
            before: Some(self.fingerprint.clone()),
            after: self.fingerprint.clone(),
            transition: IdentityTransition::Discover(ids.clone()),
        };
        if self.is_replay(&operation)? {
            return Ok(self.clone());
        }
        if !self.conflicts.is_empty() || document.source().fingerprint() != &self.fingerprint {
            return Err(conflict(
                "memo_identity_unresolved",
                "discovery requires unambiguous current source evidence",
            ));
        }
        if ids.len() != self.unbound.len() {
            return Err(count_mismatch());
        }
        let mut next = self.clone();
        for (locator, id) in self.unbound.iter().zip(ids) {
            if !next.allocated_ids.insert(id.clone()) {
                return Err(reused_id());
            }
            let memo = locator.resolve(self.root_id, &self.path, document)?;
            next.bindings.push(MemoBinding {
                memo_id: id,
                locator: locator.clone(),
                block_fingerprint: SourceFingerprint::of_bytes(
                    document.source().slice(memo.memo_span())?.as_bytes(),
                ),
            });
        }
        next.unbound.clear();
        next.bindings
            .sort_by_key(|binding| binding.locator.block_index());
        next.operations.push(operation);
        next.retain_witness_window();
        next.validate()?;
        Ok(next)
    }

    /// Encodes portable identity facts using the authoritative checksummed LOMO record codec.
    ///
    /// # Errors
    /// Rejects serialization failures and oversized records. Private fields can only be
    /// constructed by validated transitions or the checked deserializer.
    pub fn encode(&self) -> Result<Vec<u8>, LomoError> {
        encode_record(&LomoPayload {
            kind: LomoRecordKind::Manifest,
            record_id: IDENTITY_RECORD_ID.to_owned(),
            body_json: serde_json::to_string(self).map_err(|error| json_error(&error))?,
        })
    }

    /// Decodes and validates identity authority without any database or device state.
    ///
    /// # Errors
    /// Rejects checksum errors, unknown versions, wrong record kinds and invalid identity graphs.
    pub fn decode(bytes: &[u8]) -> Result<Self, LomoError> {
        let record = decode_record(bytes)?;
        if record.payload.kind != LomoRecordKind::Manifest {
            return Err(validation(
                "invalid_identity_record",
                "expected a memo identity manifest record",
            ));
        }
        if record.payload.record_id != IDENTITY_RECORD_ID {
            return Err(validation(
                "unsupported_identity_schema",
                "unsupported memo identity record version",
            ));
        }
        // IdentityMapWire::try_from is the single semantic validation boundary, including
        // direct JSON callers. A decoded map already satisfies the same invariant as a patch.
        serde_json::from_str(&record.payload.body_json).map_err(|error| json_error(&error))
    }

    /// Bounds the retained witness tail: every operation evicted from the front folds
    /// its transition into the durable checkpoint, so `validate` can still replay the
    /// record's complete allocation accounting on top of the checkpoint anchor.
    ///
    /// A single `drain` fold keeps the one-time truncation of a pre-bound record
    /// linear in its history instead of paying a front-shift per evicted operation.
    fn retain_witness_window(&mut self) {
        let evicted: Vec<MemoIdentityOperation> = self
            .operations
            .drain(
                ..self
                    .operations
                    .len()
                    .saturating_sub(MAX_RETAINED_OPERATIONS),
            )
            .collect();
        for operation in evicted {
            let checkpoint =
                self.journal_checkpoint
                    .get_or_insert_with(|| IdentityJournalCheckpoint {
                        after: operation.after.clone(),
                        allocated_ids: BTreeSet::new(),
                        retired_ids: BTreeSet::new(),
                    });
            checkpoint.apply(&operation);
        }
    }

    fn is_replay(&self, candidate: &MemoIdentityOperation) -> Result<bool, LomoError> {
        match self
            .operations
            .iter()
            .find(|operation| operation.operation_id == candidate.operation_id)
        {
            Some(previous) if previous == candidate => Ok(true),
            Some(_) => Err(conflict(
                "identity_operation_mismatch",
                "operation ID was already used with other identity facts",
            )),
            None => Ok(false),
        }
    }
}

/// The mapping extends the existing `.lomo` record layout; native paths stay outside this API.
///
/// # Errors
/// Returns validation if the logical path cannot be encoded.
pub fn memo_identity_record_path(
    root: WorkspaceRootId,
    path: &WorkspaceRelativePath,
) -> Result<WorkspaceRelativePath, LomoError> {
    let bytes = serde_json::to_vec(&(root, path)).map_err(|error| json_error(&error))?;
    let key = SourceFingerprint::of_bytes(&bytes);
    WorkspaceRelativePath::parse(&format!(
        "{}{}.rec",
        crate::layout::relative::IDENTITY_V1_PREFIX,
        key.as_str()
    ))
}

fn physical_blocks(
    root: WorkspaceRootId,
    path: &WorkspaceRelativePath,
    document: &WorkspaceDocument,
) -> Result<Vec<(MemoLocator, SourceFingerprint)>, LomoError> {
    document
        .memos()
        .iter()
        .enumerate()
        .map(|(index, memo)| {
            let index = u32::try_from(index)
                .map_err(|error| validation("memo_block_limit", &error.to_string()))?;
            Ok((
                MemoLocator::new(root, path.clone(), document, index)?,
                SourceFingerprint::of_bytes(document.source().slice(memo.memo_span())?.as_bytes()),
            ))
        })
        .collect()
}

fn bind_all(
    root: WorkspaceRootId,
    path: &WorkspaceRelativePath,
    document: &WorkspaceDocument,
    ids: &[MemoId],
) -> Result<Vec<MemoBinding>, LomoError> {
    if ids.len() != document.memos().len() {
        return Err(count_mismatch());
    }
    Ok(physical_blocks(root, path, document)?
        .into_iter()
        .zip(ids)
        .map(|((locator, block_fingerprint), memo_id)| MemoBinding {
            memo_id: memo_id.clone(),
            locator,
            block_fingerprint,
        })
        .collect())
}

fn reused_id() -> LomoError {
    conflict(
        "memo_id_reused",
        "a memo ID cannot be assigned twice or reused after deletion",
    )
}
fn count_mismatch() -> LomoError {
    validation(
        "memo_identity_count_mismatch",
        "identity transition must account for every physical block exactly once",
    )
}
fn require_append_span(span: ByteSpan, before: &WorkspaceDocument) -> Result<(), LomoError> {
    if !span.is_empty() || span.start() != before.source().len() {
        return Err(patch_mismatch());
    }
    Ok(())
}

fn patch_mismatch() -> LomoError {
    validation(
        "identity_patch_target_mismatch",
        "patch does not implement the named identity transition",
    )
}
fn json_error(error: &serde_json::Error) -> LomoError {
    validation("invalid_identity_record", &error.to_string())
}
