use std::collections::{HashMap, HashSet};

use super::{Rule, Violation, ViolationKind};
use crate::analysis::{Effect, EffectTrace};
use crate::ir::VarId;

pub struct AllocFreeBalance;

impl Rule for AllocFreeBalance {
    fn id(&self) -> &'static str {
        "MEM001"
    }

    fn description(&self) -> &'static str {
        "Every allocated pointer must be freed exactly once"
    }

    fn check(&self, trace: &EffectTrace) -> Vec<Violation> {
        let mut allocated: HashMap<VarId, &crate::source::SourceSpan> = HashMap::new();
        let mut freed: HashSet<VarId> = HashSet::new();
        let mut violations = Vec::new();

        trace.iter().for_each(|entry| match &entry.effect {
            Effect::Allocate { pointer, .. } => {
                if allocated.contains_key(pointer) {
                    violations.push(Violation::new(
                        ViolationKind::DoubleAllocation { pointer: *pointer },
                        self.id(),
                        entry.span.clone(),
                    ));
                } else {
                    allocated.insert(*pointer, &entry.span);
                }
            }
            Effect::Free { pointer } => {
                if !allocated.contains_key(pointer) {
                    violations.push(Violation::new(
                        ViolationKind::FreeUnallocated { pointer: *pointer },
                        self.id(),
                        entry.span.clone(),
                    ));
                } else if freed.contains(pointer) {
                    violations.push(Violation::new(
                        ViolationKind::DoubleFree { pointer: *pointer },
                        self.id(),
                        entry.span.clone(),
                    ));
                } else {
                    freed.insert(*pointer);
                }
            }
            _ => {}
        });

        allocated
            .iter()
            .filter(|(ptr, _)| !freed.contains(ptr))
            .for_each(|(ptr, span)| {
                violations.push(Violation::new(
                    ViolationKind::MemoryLeak { pointer: *ptr },
                    self.id(),
                    (*span).clone(),
                ));
            });

        violations
    }
}

pub struct NoUseAfterFree;

impl Rule for NoUseAfterFree {
    fn id(&self) -> &'static str {
        "MEM002"
    }

    fn description(&self) -> &'static str {
        "Memory must not be accessed after being freed"
    }

    fn check(&self, trace: &EffectTrace) -> Vec<Violation> {
        let mut freed: HashSet<VarId> = HashSet::new();

        trace
            .iter()
            .filter_map(|entry| match &entry.effect {
                Effect::Free { pointer } => {
                    freed.insert(*pointer);
                    None
                }
                Effect::BufferRead { pointer, .. } | Effect::BufferWrite { pointer, .. } => {
                    freed.contains(pointer).then(|| {
                        Violation::new(
                            ViolationKind::UseAfterFree { pointer: *pointer },
                            self.id(),
                            entry.span.clone(),
                        )
                    })
                }
                _ => None,
            })
            .collect()
    }
}

pub struct NoDoubleFree;

impl Rule for NoDoubleFree {
    fn id(&self) -> &'static str {
        "MEM003"
    }

    fn description(&self) -> &'static str {
        "Memory must not be freed multiple times"
    }

    fn check(&self, trace: &EffectTrace) -> Vec<Violation> {
        let mut freed: HashSet<VarId> = HashSet::new();

        trace
            .iter()
            .filter_map(|entry| {
                if let Effect::Free { pointer } = &entry.effect {
                    if freed.contains(pointer) {
                        Some(Violation::new(
                            ViolationKind::DoubleFree { pointer: *pointer },
                            self.id(),
                            entry.span.clone(),
                        ))
                    } else {
                        freed.insert(*pointer);
                        None
                    }
                } else {
                    None
                }
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::analysis::Capacity;
    use crate::source::{SourceFile, SourceSpan};
    use std::sync::Arc;

    fn test_span() -> SourceSpan {
        let file = Arc::new(SourceFile::new("test.swift", "test content here"));
        SourceSpan::new(file, 0u32, 4u32)
    }

    #[test]
    fn test_balanced_alloc_free() {
        let mut trace = EffectTrace::new();
        let ptr = VarId::new(0);

        trace.push(
            Effect::Allocate {
                pointer: ptr,
                element_type: "Int32".to_string(),
                capacity: Capacity::Literal(10),
            },
            test_span(),
        );
        trace.push(Effect::Free { pointer: ptr }, test_span());

        let rule = AllocFreeBalance;
        let violations = rule.check(&trace);
        assert!(violations.is_empty());
    }

    #[test]
    fn test_memory_leak_detected() {
        let mut trace = EffectTrace::new();
        let ptr = VarId::new(0);

        trace.push(
            Effect::Allocate {
                pointer: ptr,
                element_type: "Int32".to_string(),
                capacity: Capacity::Literal(10),
            },
            test_span(),
        );

        let rule = AllocFreeBalance;
        let violations = rule.check(&trace);
        assert_eq!(violations.len(), 1);
        assert!(matches!(
            violations[0].kind,
            ViolationKind::MemoryLeak { .. }
        ));
    }

    #[test]
    fn test_double_free_detected() {
        let mut trace = EffectTrace::new();
        let ptr = VarId::new(0);

        trace.push(
            Effect::Allocate {
                pointer: ptr,
                element_type: "Int32".to_string(),
                capacity: Capacity::Literal(10),
            },
            test_span(),
        );
        trace.push(Effect::Free { pointer: ptr }, test_span());
        trace.push(Effect::Free { pointer: ptr }, test_span());

        let rule = NoDoubleFree;
        let violations = rule.check(&trace);
        assert_eq!(violations.len(), 1);
        assert!(matches!(
            violations[0].kind,
            ViolationKind::DoubleFree { .. }
        ));
    }

    #[test]
    fn test_use_after_free_detected() {
        let mut trace = EffectTrace::new();
        let ptr = VarId::new(0);

        trace.push(
            Effect::Allocate {
                pointer: ptr,
                element_type: "Int32".to_string(),
                capacity: Capacity::Literal(10),
            },
            test_span(),
        );
        trace.push(Effect::Free { pointer: ptr }, test_span());
        trace.push(
            Effect::BufferRead {
                pointer: ptr,
                size: Capacity::Literal(4),
            },
            test_span(),
        );

        let rule = NoUseAfterFree;
        let violations = rule.check(&trace);
        assert_eq!(violations.len(), 1);
        assert!(matches!(
            violations[0].kind,
            ViolationKind::UseAfterFree { .. }
        ));
    }
}
