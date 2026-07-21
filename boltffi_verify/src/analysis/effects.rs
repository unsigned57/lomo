use crate::ir::VarId;
use crate::source::SourceSpan;

#[derive(Debug, Clone)]
pub enum Effect {
    Allocate {
        pointer: VarId,
        element_type: String,
        capacity: Capacity,
    },

    Free {
        pointer: VarId,
    },

    Retain {
        object: VarId,
        opaque_handle: VarId,
    },

    Release {
        opaque_handle: VarId,
    },

    TakeRetained {
        opaque_handle: VarId,
        result: VarId,
    },

    BufferWrite {
        pointer: VarId,
        size: Capacity,
    },

    BufferRead {
        pointer: VarId,
        size: Capacity,
    },

    FfiCall {
        function_name: String,
        arguments: Vec<VarId>,
        out_params: Vec<VarId>,
    },

    StatusProduced {
        status_var: VarId,
    },

    StatusChecked {
        status_var: VarId,
    },

    DeferRegistered {
        deferred_effects: Vec<Effect>,
    },

    DeferExecuted,
}

impl Effect {
    pub fn is_allocation(&self) -> bool {
        matches!(self, Self::Allocate { .. })
    }

    pub fn is_deallocation(&self) -> bool {
        matches!(self, Self::Free { .. })
    }

    pub fn is_retain(&self) -> bool {
        matches!(self, Self::Retain { .. })
    }

    pub fn is_release(&self) -> bool {
        matches!(self, Self::Release { .. } | Self::TakeRetained { .. })
    }

    pub fn involved_pointers(&self) -> Vec<VarId> {
        match self {
            Self::Allocate { pointer, .. } => vec![*pointer],
            Self::Free { pointer } => vec![*pointer],
            Self::BufferWrite { pointer, .. } | Self::BufferRead { pointer, .. } => vec![*pointer],
            _ => vec![],
        }
    }

    pub fn involved_handles(&self) -> Vec<VarId> {
        match self {
            Self::Retain { opaque_handle, .. } => vec![*opaque_handle],
            Self::Release { opaque_handle } | Self::TakeRetained { opaque_handle, .. } => {
                vec![*opaque_handle]
            }
            _ => vec![],
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Capacity {
    Literal(u64),
    Variable(VarId),
    FfiResult {
        function_name: String,
        arguments: Vec<VarId>,
    },
    Unknown,
}

impl Capacity {
    pub fn is_known(&self) -> bool {
        !matches!(self, Self::Unknown)
    }

    pub fn as_literal(&self) -> Option<u64> {
        match self {
            Self::Literal(value) => Some(*value),
            _ => None,
        }
    }

    pub fn matches(&self, other: &Capacity) -> bool {
        match (self, other) {
            (Self::Literal(a), Self::Literal(b)) => a == b,
            (Self::Variable(a), Self::Variable(b)) => a == b,
            (
                Self::FfiResult {
                    function_name: fn_a,
                    arguments: args_a,
                },
                Self::FfiResult {
                    function_name: fn_b,
                    arguments: args_b,
                },
            ) => fn_a == fn_b && args_a == args_b,
            (Self::Unknown, _) | (_, Self::Unknown) => true,
            _ => false,
        }
    }
}

#[derive(Debug)]
pub struct EffectTrace {
    entries: Vec<EffectEntry>,
}

#[derive(Debug)]
pub struct EffectEntry {
    pub effect: Effect,
    pub span: SourceSpan,
}

impl EffectTrace {
    pub fn new() -> Self {
        Self {
            entries: Vec::new(),
        }
    }

    pub fn push(&mut self, effect: Effect, span: SourceSpan) {
        self.entries.push(EffectEntry { effect, span });
    }

    pub fn entries(&self) -> &[EffectEntry] {
        &self.entries
    }

    pub fn iter(&self) -> impl Iterator<Item = &EffectEntry> {
        self.entries.iter()
    }

    pub fn allocations(&self) -> impl Iterator<Item = &EffectEntry> {
        self.entries.iter().filter(|e| e.effect.is_allocation())
    }

    pub fn deallocations(&self) -> impl Iterator<Item = &EffectEntry> {
        self.entries.iter().filter(|e| e.effect.is_deallocation())
    }

    pub fn retains(&self) -> impl Iterator<Item = &EffectEntry> {
        self.entries.iter().filter(|e| e.effect.is_retain())
    }

    pub fn releases(&self) -> impl Iterator<Item = &EffectEntry> {
        self.entries.iter().filter(|e| e.effect.is_release())
    }

    pub fn allocation_span(&self, pointer: VarId) -> Option<&SourceSpan> {
        self.entries.iter().find_map(|entry| {
            if let Effect::Allocate { pointer: p, .. } = &entry.effect
                && *p == pointer
            {
                return Some(&entry.span);
            }
            None
        })
    }
}

impl Default for EffectTrace {
    fn default() -> Self {
        Self::new()
    }
}

impl IntoIterator for EffectTrace {
    type Item = EffectEntry;
    type IntoIter = std::vec::IntoIter<EffectEntry>;

    fn into_iter(self) -> Self::IntoIter {
        self.entries.into_iter()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_capacity_matches() {
        let lit_10 = Capacity::Literal(10);
        let lit_20 = Capacity::Literal(20);
        let unknown = Capacity::Unknown;

        assert!(lit_10.matches(&lit_10));
        assert!(!lit_10.matches(&lit_20));
        assert!(lit_10.matches(&unknown));
        assert!(unknown.matches(&lit_10));
    }

    #[test]
    fn test_effect_classification() {
        let alloc = Effect::Allocate {
            pointer: VarId::new(0),
            element_type: "Int32".to_string(),
            capacity: Capacity::Literal(10),
        };

        assert!(alloc.is_allocation());
        assert!(!alloc.is_deallocation());
    }
}
