use std::collections::HashSet;

use crate::ir::VarId;
use crate::source::SourceSpan;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct PathId(u32);

impl PathId {
    pub fn root() -> Self {
        Self(0)
    }

    pub fn then_branch(parent: PathId) -> Self {
        Self(parent.0 * 2 + 1)
    }

    pub fn else_branch(parent: PathId) -> Self {
        Self(parent.0 * 2 + 2)
    }

    pub fn is_root(&self) -> bool {
        self.0 == 0
    }
}

impl std::fmt::Display for PathId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        if self.is_root() {
            write!(f, "main")
        } else {
            write!(f, "path_{}", self.0)
        }
    }
}

#[derive(Debug, Clone)]
pub struct BranchState {
    pub allocated: HashSet<VarId>,
    pub freed: HashSet<VarId>,
    pub retained: HashSet<VarId>,
    pub released: HashSet<VarId>,
}

impl BranchState {
    pub fn new() -> Self {
        Self {
            allocated: HashSet::new(),
            freed: HashSet::new(),
            retained: HashSet::new(),
            released: HashSet::new(),
        }
    }

    pub fn from_parent(parent: &BranchState) -> Self {
        Self {
            allocated: parent.allocated.clone(),
            freed: parent.freed.clone(),
            retained: parent.retained.clone(),
            released: parent.released.clone(),
        }
    }

    pub fn allocate(&mut self, ptr: VarId) {
        self.allocated.insert(ptr);
        self.freed.remove(&ptr);
    }

    pub fn free(&mut self, ptr: VarId) {
        self.freed.insert(ptr);
    }

    pub fn retain(&mut self, handle: VarId) {
        self.retained.insert(handle);
        self.released.remove(&handle);
    }

    pub fn release(&mut self, handle: VarId) {
        self.released.insert(handle);
    }

    pub fn live_allocations(&self) -> impl Iterator<Item = &VarId> {
        self.allocated.iter().filter(|p| !self.freed.contains(p))
    }

    pub fn live_retains(&self) -> impl Iterator<Item = &VarId> {
        self.retained.iter().filter(|h| !self.released.contains(h))
    }
}

impl Default for BranchState {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug)]
pub struct BranchDivergence {
    pub variable: VarId,
    pub kind: DivergenceKind,
    pub span: SourceSpan,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DivergenceKind {
    FreedInOneBranch,
    ReleasedInOneBranch,
    AllocatedInOneBranch,
    RetainedInOneBranch,
}

impl DivergenceKind {
    pub fn message(&self) -> &'static str {
        match self {
            Self::FreedInOneBranch => "pointer freed in one branch but not the other",
            Self::ReleasedInOneBranch => "handle released in one branch but not the other",
            Self::AllocatedInOneBranch => "pointer allocated in one branch but not freed",
            Self::RetainedInOneBranch => "handle retained in one branch but not released",
        }
    }
}

pub fn check_branch_consistency(
    then_state: &BranchState,
    else_state: &BranchState,
    pre_branch_state: &BranchState,
    span: &SourceSpan,
) -> Vec<BranchDivergence> {
    let mut divergences = Vec::new();

    let then_freed: HashSet<_> = then_state
        .freed
        .difference(&pre_branch_state.freed)
        .collect();
    let else_freed: HashSet<_> = else_state
        .freed
        .difference(&pre_branch_state.freed)
        .collect();

    then_freed
        .symmetric_difference(&else_freed)
        .for_each(|&ptr| {
            if pre_branch_state.allocated.contains(ptr) {
                divergences.push(BranchDivergence {
                    variable: *ptr,
                    kind: DivergenceKind::FreedInOneBranch,
                    span: span.clone(),
                });
            }
        });

    let then_released: HashSet<_> = then_state
        .released
        .difference(&pre_branch_state.released)
        .collect();
    let else_released: HashSet<_> = else_state
        .released
        .difference(&pre_branch_state.released)
        .collect();

    then_released
        .symmetric_difference(&else_released)
        .for_each(|&handle| {
            if pre_branch_state.retained.contains(handle) {
                divergences.push(BranchDivergence {
                    variable: *handle,
                    kind: DivergenceKind::ReleasedInOneBranch,
                    span: span.clone(),
                });
            }
        });

    divergences
}

pub fn merge_branch_states(then_state: &BranchState, else_state: &BranchState) -> BranchState {
    BranchState {
        allocated: then_state
            .allocated
            .union(&else_state.allocated)
            .copied()
            .collect(),
        freed: then_state
            .freed
            .intersection(&else_state.freed)
            .copied()
            .collect(),
        retained: then_state
            .retained
            .union(&else_state.retained)
            .copied()
            .collect(),
        released: then_state
            .released
            .intersection(&else_state.released)
            .copied()
            .collect(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::source::SourceFile;
    use std::sync::Arc;

    fn test_span() -> SourceSpan {
        let file = Arc::new(SourceFile::new("test.swift", "test content"));
        SourceSpan::new(file, 0u32, 4u32)
    }

    #[test]
    fn test_consistent_branches_no_divergence() {
        let ptr = VarId::new(0);

        let mut pre = BranchState::new();
        pre.allocate(ptr);

        let mut then_state = BranchState::from_parent(&pre);
        then_state.free(ptr);

        let mut else_state = BranchState::from_parent(&pre);
        else_state.free(ptr);

        let divergences = check_branch_consistency(&then_state, &else_state, &pre, &test_span());
        assert!(divergences.is_empty());
    }

    #[test]
    fn test_freed_in_one_branch_detected() {
        let ptr = VarId::new(0);

        let mut pre = BranchState::new();
        pre.allocate(ptr);

        let mut then_state = BranchState::from_parent(&pre);
        then_state.free(ptr);

        let else_state = BranchState::from_parent(&pre);

        let divergences = check_branch_consistency(&then_state, &else_state, &pre, &test_span());
        assert_eq!(divergences.len(), 1);
        assert_eq!(divergences[0].kind, DivergenceKind::FreedInOneBranch);
    }

    #[test]
    fn test_merge_takes_intersection_of_freed() {
        let ptr = VarId::new(0);

        let mut then_state = BranchState::new();
        then_state.allocate(ptr);
        then_state.free(ptr);

        let mut else_state = BranchState::new();
        else_state.allocate(ptr);

        let merged = merge_branch_states(&then_state, &else_state);

        assert!(merged.allocated.contains(&ptr));
        assert!(!merged.freed.contains(&ptr));
    }
}
