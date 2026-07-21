use std::collections::HashMap;

use super::{Rule, Violation, ViolationKind};
use crate::analysis::{Capacity, Effect, EffectTrace};
use crate::ir::VarId;

pub struct BufferBoundsCheck;

impl Rule for BufferBoundsCheck {
    fn id(&self) -> &'static str {
        "BUF001"
    }

    fn description(&self) -> &'static str {
        "Buffer writes must not exceed allocated capacity"
    }

    fn check(&self, trace: &EffectTrace) -> Vec<Violation> {
        let mut allocations: HashMap<VarId, AllocationInfo> = HashMap::new();
        let mut violations = Vec::new();

        trace.iter().for_each(|entry| match &entry.effect {
            Effect::Allocate {
                pointer, capacity, ..
            } => {
                allocations.insert(
                    *pointer,
                    AllocationInfo {
                        capacity: capacity.clone(),
                    },
                );
            }

            Effect::BufferWrite { pointer, size } => {
                if let Some(alloc_info) = allocations.get(pointer)
                    && !capacities_compatible(&alloc_info.capacity, size)
                {
                    violations.push(Violation::new(
                        ViolationKind::BufferOverflow {
                            pointer: *pointer,
                            capacity: alloc_info.capacity.clone(),
                            access_size: size.clone(),
                        },
                        self.id(),
                        entry.span.clone(),
                    ));
                }
            }

            Effect::Free { pointer } => {
                allocations.remove(pointer);
            }

            _ => {}
        });

        violations
    }
}

struct AllocationInfo {
    capacity: Capacity,
}

fn capacities_compatible(allocated: &Capacity, written: &Capacity) -> bool {
    match (allocated, written) {
        (Capacity::Literal(alloc), Capacity::Literal(write)) => write <= alloc,

        (Capacity::Variable(alloc_var), Capacity::Variable(write_var)) => alloc_var == write_var,

        (
            Capacity::FfiResult {
                function_name: fn_a,
                ..
            },
            Capacity::FfiResult {
                function_name: fn_b,
                ..
            },
        ) => fn_a == fn_b,

        (Capacity::FfiResult { function_name, .. }, Capacity::Variable(_)) => {
            function_name.ends_with("_len")
        }

        (Capacity::Variable(_), Capacity::FfiResult { function_name, .. }) => {
            function_name.ends_with("_len")
        }

        (Capacity::Unknown, _) | (_, Capacity::Unknown) => true,

        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::source::{SourceFile, SourceSpan};
    use std::sync::Arc;

    fn test_span() -> SourceSpan {
        let file = Arc::new(SourceFile::new("test.swift", "test content here"));
        SourceSpan::new(file, 0u32, 4u32)
    }

    #[test]
    fn test_matching_capacity_passes() {
        let mut trace = EffectTrace::new();
        let ptr = VarId::new(0);
        let len_var = VarId::new(1);

        trace.push(
            Effect::Allocate {
                pointer: ptr,
                element_type: "Int32".to_string(),
                capacity: Capacity::Variable(len_var),
            },
            test_span(),
        );
        trace.push(
            Effect::BufferWrite {
                pointer: ptr,
                size: Capacity::Variable(len_var),
            },
            test_span(),
        );

        let rule = BufferBoundsCheck;
        let violations = rule.check(&trace);
        assert!(violations.is_empty());
    }

    #[test]
    fn test_literal_overflow_detected() {
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
        trace.push(
            Effect::BufferWrite {
                pointer: ptr,
                size: Capacity::Literal(20),
            },
            test_span(),
        );

        let rule = BufferBoundsCheck;
        let violations = rule.check(&trace);
        assert_eq!(violations.len(), 1);
        assert!(matches!(
            violations[0].kind,
            ViolationKind::BufferOverflow { .. }
        ));
    }

    #[test]
    fn test_ffi_len_pattern_passes() {
        let mut trace = EffectTrace::new();
        let ptr = VarId::new(0);
        let len_var = VarId::new(1);

        trace.push(
            Effect::Allocate {
                pointer: ptr,
                element_type: "Location".to_string(),
                capacity: Capacity::FfiResult {
                    function_name: "boltffi_generate_locations_len".to_string(),
                    arguments: vec![],
                },
            },
            test_span(),
        );
        trace.push(
            Effect::BufferWrite {
                pointer: ptr,
                size: Capacity::Variable(len_var),
            },
            test_span(),
        );

        let rule = BufferBoundsCheck;
        let violations = rule.check(&trace);
        assert!(violations.is_empty());
    }
}
