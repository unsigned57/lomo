use std::collections::HashMap;

use super::effects::Capacity;
use crate::ir::VarId;

#[derive(Debug, Clone, Default)]
pub struct MemoryState {
    pointers: HashMap<VarId, PointerState>,
    ref_counts: HashMap<VarId, RefCountState>,
    statuses: HashMap<VarId, StatusState>,
    pending_defers: Vec<DeferBlock>,
}

#[derive(Debug, Clone)]
struct DeferBlock {
    effects: Vec<super::Effect>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub enum PointerState {
    #[default]
    Unallocated,
    Allocated {
        capacity: Capacity,
        element_type: String,
    },
    Freed,
    Unknown,
}

impl PointerState {
    pub fn is_allocated(&self) -> bool {
        matches!(self, Self::Allocated { .. })
    }

    pub fn is_freed(&self) -> bool {
        matches!(self, Self::Freed)
    }

    pub fn capacity(&self) -> Option<&Capacity> {
        match self {
            Self::Allocated { capacity, .. } => Some(capacity),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub enum RefCountState {
    #[default]
    NotRetained,
    Retained {
        count: u32,
    },
    Released,
}

impl RefCountState {
    pub fn is_retained(&self) -> bool {
        matches!(self, Self::Retained { .. })
    }

    pub fn is_released(&self) -> bool {
        matches!(self, Self::Released)
    }

    pub fn retain_count(&self) -> u32 {
        match self {
            Self::Retained { count } => *count,
            _ => 0,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum StatusState {
    #[default]
    Unchecked,
    Checked,
}

impl StatusState {
    pub fn is_checked(&self) -> bool {
        matches!(self, Self::Checked)
    }
}

impl MemoryState {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn pointer_state(&self, pointer: VarId) -> &PointerState {
        self.pointers
            .get(&pointer)
            .unwrap_or(&PointerState::Unallocated)
    }

    pub fn ref_count_state(&self, handle: VarId) -> &RefCountState {
        self.ref_counts
            .get(&handle)
            .unwrap_or(&RefCountState::NotRetained)
    }

    pub fn status_state(&self, status: VarId) -> &StatusState {
        self.statuses
            .get(&status)
            .unwrap_or(&StatusState::Unchecked)
    }

    pub fn allocate(&mut self, pointer: VarId, element_type: String, capacity: Capacity) {
        self.pointers.insert(
            pointer,
            PointerState::Allocated {
                capacity,
                element_type,
            },
        );
    }

    pub fn free(&mut self, pointer: VarId) {
        self.pointers.insert(pointer, PointerState::Freed);
    }

    pub fn retain(&mut self, handle: VarId) {
        let current = self.ref_counts.get(&handle).cloned().unwrap_or_default();
        let new_count = match current {
            RefCountState::NotRetained => 1,
            RefCountState::Retained { count } => count + 1,
            RefCountState::Released => 1,
        };
        self.ref_counts
            .insert(handle, RefCountState::Retained { count: new_count });
    }

    pub fn release(&mut self, handle: VarId) {
        let current = self.ref_counts.get(&handle).cloned().unwrap_or_default();
        let new_state = match current {
            RefCountState::Retained { count } if count > 1 => {
                RefCountState::Retained { count: count - 1 }
            }
            RefCountState::Retained { count: 1 } | RefCountState::NotRetained => {
                RefCountState::Released
            }
            _ => RefCountState::Released,
        };
        self.ref_counts.insert(handle, new_state);
    }

    pub fn produce_status(&mut self, status: VarId) {
        self.statuses.insert(status, StatusState::Unchecked);
    }

    pub fn check_status(&mut self, status: VarId) {
        self.statuses.insert(status, StatusState::Checked);
    }

    pub fn register_defer(&mut self, effects: Vec<super::Effect>) {
        self.pending_defers.push(DeferBlock { effects });
    }

    pub fn execute_defers(&mut self) -> Vec<super::Effect> {
        self.pending_defers
            .drain(..)
            .rev()
            .flat_map(|block| block.effects)
            .collect()
    }

    pub fn allocated_pointers(&self) -> impl Iterator<Item = VarId> + '_ {
        self.pointers
            .iter()
            .filter(|(_, state)| state.is_allocated())
            .map(|(id, _)| *id)
    }

    pub fn retained_handles(&self) -> impl Iterator<Item = VarId> + '_ {
        self.ref_counts
            .iter()
            .filter(|(_, state)| state.is_retained())
            .map(|(id, _)| *id)
    }

    pub fn unchecked_statuses(&self) -> impl Iterator<Item = VarId> + '_ {
        self.statuses
            .iter()
            .filter(|(_, state)| !state.is_checked())
            .map(|(id, _)| *id)
    }

    pub fn merge(&self, other: &MemoryState) -> MemoryState {
        let mut merged = self.clone();

        other.pointers.iter().for_each(|(id, state)| {
            let current = merged.pointers.get(id);
            let merged_state = match (current, state) {
                (Some(a), b) if a == b => a.clone(),
                (None, b) => b.clone(),
                (Some(PointerState::Unknown), _) | (_, PointerState::Unknown) => {
                    PointerState::Unknown
                }
                _ => PointerState::Unknown,
            };
            merged.pointers.insert(*id, merged_state);
        });

        merged
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_allocate_and_free() {
        let mut state = MemoryState::new();
        let ptr = VarId::new(0);

        state.allocate(ptr, "Int32".to_string(), Capacity::Literal(10));
        assert!(state.pointer_state(ptr).is_allocated());

        state.free(ptr);
        assert!(state.pointer_state(ptr).is_freed());
    }

    #[test]
    fn test_retain_release_balance() {
        let mut state = MemoryState::new();
        let handle = VarId::new(0);

        state.retain(handle);
        assert_eq!(state.ref_count_state(handle).retain_count(), 1);

        state.retain(handle);
        assert_eq!(state.ref_count_state(handle).retain_count(), 2);

        state.release(handle);
        assert_eq!(state.ref_count_state(handle).retain_count(), 1);

        state.release(handle);
        assert!(state.ref_count_state(handle).is_released());
    }
}
