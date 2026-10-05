//! Portable identity mappings and operation evidence, encoded with the existing LOMO envelope.
//! This module plans facts only. The application persists them through its platform executor.

use std::collections::BTreeSet;

use crate::limits::{conflict, validation};
use lomo_core::{LomoError, OperationId};
use serde::{Deserialize, Serialize};

use crate::{
    ByteSpan, DocumentPatchPlan, LomoPayload, LomoRecordKind, MemoId, MemoLocator, SourceBytes,
    SourceFingerprint, WorkspaceDocument, WorkspaceRelativePath, WorkspaceRootId, decode_record,
    encode_record, parse_workspace_document,
};

mod integrity;
use integrity::IdentityMapWire;

const IDENTITY_SCHEMA: u32 = 1;
const IDENTITY_RECORD_ID: &str = "memo-identity-map-v1";

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
    operations: Vec<MemoIdentityOperation>,
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

    #[must_use]
    pub fn operations(&self) -> &[MemoIdentityOperation] {
        &self.operations
    }

    /// Carries IDs through one proven local patch, preserving siblings and reserving removed IDs.
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
    ) -> Result<Self, LomoError> {
        let operation = MemoIdentityOperation {
            operation_id,
            before: Some(before.source().fingerprint().clone()),
            after: patch.result_fingerprint().clone(),
            transition: IdentityTransition::Patch(change.clone()),
        };
        if self.is_replay(&operation)? {
            return Ok(self.clone());
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
        let mut next = self.clone();
        next.bindings = bind_all(self.root_id, &self.path, &after, &ids)?;
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
        next.validate()?;
        Ok(next)
    }

    fn parse_result(&self, patch: &DocumentPatchPlan) -> Result<WorkspaceDocument, LomoError> {
        let filename =
            self.path.as_str().rsplit('/').next().ok_or_else(|| {
                validation("invalid_workspace_path", "document filename is missing")
            })?;
        let stem = filename.strip_suffix(".md").unwrap_or(filename);
        parse_workspace_document(
            &SourceBytes::try_from_bytes(patch.result_bytes().to_vec())?,
            stem,
        )
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
        let mut next = self.clone();
        next.bindings.clear();
        next.conflicts.clear();
        next.fingerprint = document.source().fingerprint().clone();
        for old in &previous {
            let matches: Vec<_> = candidates
                .iter()
                .filter(|(_, hash)| hash == &old.block_fingerprint)
                .collect();
            let prior_count = previous
                .iter()
                .filter(|binding| binding.block_fingerprint == old.block_fingerprint)
                .count();
            if let [(locator, hash)] = matches.as_slice()
                && prior_count == 1
            {
                next.bindings.push(MemoBinding {
                    memo_id: old.memo_id.clone(),
                    locator: locator.clone(),
                    block_fingerprint: hash.clone(),
                });
            } else {
                next.conflicts.push(MemoIdentityConflict {
                    previous: (*old).clone(),
                    candidates: matches
                        .into_iter()
                        .map(|(locator, _)| locator.clone())
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
        next.validate()?;
        Ok(next)
    }

    /// Encodes portable identity facts using the authoritative checksummed LOMO record codec.
    ///
    /// # Errors
    /// Rejects invalid state and oversized records.
    pub fn encode(&self) -> Result<Vec<u8>, LomoError> {
        self.validate()?;
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
        let map: Self =
            serde_json::from_str(&record.payload.body_json).map_err(|error| json_error(&error))?;
        map.validate()?;
        Ok(map)
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
    WorkspaceRelativePath::parse(&format!(".lomo/identity/v1/{}.rec", key.as_str()))
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
