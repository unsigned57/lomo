//! Semantic record validation applies on every deserialization, including direct JSON callers.

use std::collections::{BTreeMap, BTreeSet};

use lomo_core::LomoError;
use serde::Deserialize;

use super::{
    IDENTITY_SCHEMA, IdentityTransition, MemoBinding, MemoIdentityChange, MemoIdentityConflict,
    MemoIdentityMap, MemoIdentityOperation, reused_id,
};
use crate::limits::validation;
use crate::{MemoId, MemoLocator, SourceFingerprint, WorkspaceRelativePath, WorkspaceRootId};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct IdentityMapWire {
    schema: u32,
    root_id: WorkspaceRootId,
    path: String,
    fingerprint: String,
    bindings: Vec<MemoBinding>,
    unbound: Vec<MemoLocator>,
    conflicts: Vec<MemoIdentityConflict>,
    allocated_ids: BTreeSet<MemoId>,
    retired_ids: BTreeSet<MemoId>,
    operations: Vec<MemoIdentityOperation>,
}

impl TryFrom<IdentityMapWire> for MemoIdentityMap {
    type Error = LomoError;

    fn try_from(wire: IdentityMapWire) -> Result<Self, Self::Error> {
        let map = Self {
            schema: wire.schema,
            root_id: wire.root_id,
            path: WorkspaceRelativePath::parse(&wire.path)?,
            fingerprint: SourceFingerprint::parse(&wire.fingerprint)?,
            bindings: wire.bindings,
            unbound: wire.unbound,
            conflicts: wire.conflicts,
            allocated_ids: wire.allocated_ids,
            retired_ids: wire.retired_ids,
            operations: wire.operations,
        };
        map.validate()?;
        Ok(map)
    }
}

impl MemoIdentityMap {
    pub(super) fn validate(&self) -> Result<(), LomoError> {
        if self.schema != IDENTITY_SCHEMA {
            return Err(validation(
                "unsupported_identity_schema",
                "refuse to process an unknown identity schema",
            ));
        }
        SourceFingerprint::parse(self.fingerprint.as_str())?;
        let mut active_ids = BTreeSet::new();
        let mut locations = BTreeMap::new();
        let ordered = self
            .bindings
            .iter()
            .map(|binding| binding.locator.block_index())
            .is_sorted_by(|a, b| a < b);
        if !ordered {
            return Err(invalid_mapping());
        }
        for binding in &self.bindings {
            if !active_ids.insert(binding.memo_id.clone()) {
                return Err(reused_id());
            }
            SourceFingerprint::parse(binding.block_fingerprint.as_str())?;
            self.validate_current_locator(&binding.locator, &mut locations)?;
        }
        for locator in &self.unbound {
            self.validate_current_locator(locator, &mut locations)?;
        }
        if locations
            .keys()
            .enumerate()
            .any(|(index, actual)| index != *actual as usize)
        {
            return Err(invalid_mapping());
        }
        for conflict in &self.conflicts {
            if !active_ids.insert(conflict.previous.memo_id.clone()) {
                return Err(reused_id());
            }
            self.validate_conflict(conflict, &locations)?;
        }
        let accounted: BTreeSet<_> = active_ids.union(&self.retired_ids).cloned().collect();
        if !active_ids.is_disjoint(&self.retired_ids) || accounted != self.allocated_ids {
            return Err(reused_id());
        }
        self.validate_operations()
    }

    fn validate_conflict(
        &self,
        conflict: &MemoIdentityConflict,
        locations: &BTreeMap<u32, &MemoLocator>,
    ) -> Result<(), LomoError> {
        let previous = &conflict.previous;
        SourceFingerprint::parse(previous.block_fingerprint.as_str())?;
        let mut seen = BTreeSet::new();
        if conflict.observed_fingerprint != self.fingerprint
            || previous.locator.root_id() != self.root_id
            || previous.locator.path() != &self.path
        {
            return Err(validation(
                "invalid_identity_conflict",
                "conflict must retain its document and current source evidence",
            ));
        }
        for locator in &conflict.candidates {
            if locations.get(&locator.block_index()).copied() != Some(locator)
                || !seen.insert(locator.block_index())
            {
                return Err(validation(
                    "invalid_identity_conflict",
                    "candidate is not a distinct current physical block",
                ));
            }
        }
        Ok(())
    }

    fn validate_current_locator<'a>(
        &self,
        locator: &'a MemoLocator,
        locations: &mut BTreeMap<u32, &'a MemoLocator>,
    ) -> Result<(), LomoError> {
        if locator.root_id() != self.root_id
            || locator.path() != &self.path
            || locator.fingerprint() != &self.fingerprint
            || locations.insert(locator.block_index(), locator).is_some()
        {
            return Err(invalid_mapping());
        }
        Ok(())
    }

    fn validate_operations(&self) -> Result<(), LomoError> {
        let mut operation_ids = BTreeSet::new();
        let mut fingerprint = None;
        let mut allocation = Allocation::default();
        for (index, operation) in self.operations.iter().enumerate() {
            SourceFingerprint::parse(operation.after.as_str())?;
            if !operation_ids.insert(operation.operation_id.clone())
                || operation.before.as_ref() != fingerprint
            {
                return Err(invalid_journal());
            }
            match &operation.transition {
                IdentityTransition::Initialize(ids) if index == 0 => allocation.reserve(ids)?,
                IdentityTransition::Initialize(_) => return Err(invalid_journal()),
                _ if index == 0 => return Err(invalid_journal()),
                IdentityTransition::Patch(change) => allocation.apply(change)?,
                IdentityTransition::Discover(ids) => allocation.reserve(ids)?,
                IdentityTransition::External => {}
            }
            fingerprint = Some(&operation.after);
        }
        if fingerprint != Some(&self.fingerprint)
            || allocation.allocated != self.allocated_ids
            || allocation.retired != self.retired_ids
        {
            return Err(invalid_journal());
        }
        Ok(())
    }
}

#[derive(Default)]
struct Allocation {
    allocated: BTreeSet<MemoId>,
    retired: BTreeSet<MemoId>,
}

impl Allocation {
    fn reserve(&mut self, ids: &[MemoId]) -> Result<(), LomoError> {
        for id in ids {
            if !self.allocated.insert(id.clone()) {
                return Err(reused_id());
            }
        }
        Ok(())
    }

    fn apply(&mut self, change: &MemoIdentityChange) -> Result<(), LomoError> {
        match change {
            MemoIdentityChange::Append(id) => self.reserve(std::slice::from_ref(id)),
            MemoIdentityChange::Update(id) => self.require_live(id),
            MemoIdentityChange::Remove(id) => {
                self.require_live(id)?;
                self.retired.insert(id.clone());
                Ok(())
            }
            MemoIdentityChange::Restore(id) => {
                if !self.allocated.contains(id) || !self.retired.remove(id) {
                    return Err(invalid_journal());
                }
                Ok(())
            }
        }
    }

    fn require_live(&self, id: &MemoId) -> Result<(), LomoError> {
        if !self.allocated.contains(id) || self.retired.contains(id) {
            return Err(invalid_journal());
        }
        Ok(())
    }
}

fn invalid_mapping() -> LomoError {
    validation(
        "invalid_identity_mapping",
        "each physical block must have exactly one correctly ordered current address",
    )
}

fn invalid_journal() -> LomoError {
    validation(
        "invalid_identity_journal",
        "identity journal must account for all allocated and retired IDs in one source-version chain",
    )
}
