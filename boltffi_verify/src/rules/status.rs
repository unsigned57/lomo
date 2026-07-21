use std::collections::HashSet;

use super::{Rule, Violation, ViolationKind};
use crate::analysis::{Effect, EffectTrace};
use crate::ir::VarId;

pub struct StatusMustBeChecked;

impl Rule for StatusMustBeChecked {
    fn id(&self) -> &'static str {
        "STATUS001"
    }

    fn description(&self) -> &'static str {
        "FFI status codes must be checked before using results"
    }

    fn check(&self, trace: &EffectTrace) -> Vec<Violation> {
        let mut produced_statuses: Vec<(VarId, crate::source::SourceSpan)> = Vec::new();
        let mut checked_statuses: HashSet<VarId> = HashSet::new();

        trace.iter().for_each(|entry| match &entry.effect {
            Effect::StatusProduced { status_var } => {
                produced_statuses.push((*status_var, entry.span.clone()));
            }

            Effect::StatusChecked { status_var } => {
                checked_statuses.insert(*status_var);
            }

            _ => {}
        });

        produced_statuses
            .into_iter()
            .filter(|(status_var, _)| !checked_statuses.contains(status_var))
            .map(|(status_var, span)| {
                Violation::new(
                    ViolationKind::UncheckedStatus { status: status_var },
                    self.id(),
                    span,
                )
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::analysis::EffectTrace;
    use crate::source::{SourceFile, SourceSpan};
    use std::sync::Arc;

    fn test_span() -> SourceSpan {
        let file = Arc::new(SourceFile::new("test.swift", "test content here"));
        SourceSpan::new(file, 0u32, 4u32)
    }

    #[test]
    fn test_checked_status_passes() {
        let mut trace = EffectTrace::new();
        let status = VarId::new(0);

        trace.push(
            Effect::FfiCall {
                function_name: "boltffi_get_data".to_string(),
                arguments: vec![],
                out_params: vec![],
            },
            test_span(),
        );
        trace.push(Effect::StatusProduced { status_var: status }, test_span());
        trace.push(Effect::StatusChecked { status_var: status }, test_span());

        let rule = StatusMustBeChecked;
        let violations = rule.check(&trace);
        assert!(violations.is_empty());
    }

    #[test]
    fn test_unchecked_status_detected() {
        let mut trace = EffectTrace::new();
        let status = VarId::new(0);

        trace.push(
            Effect::FfiCall {
                function_name: "boltffi_get_data".to_string(),
                arguments: vec![],
                out_params: vec![],
            },
            test_span(),
        );
        trace.push(Effect::StatusProduced { status_var: status }, test_span());

        let rule = StatusMustBeChecked;
        let violations = rule.check(&trace);
        assert_eq!(violations.len(), 1);
        assert!(matches!(
            violations[0].kind,
            ViolationKind::UncheckedStatus { .. }
        ));
    }

    #[test]
    fn test_multiple_ffi_calls() {
        let mut trace = EffectTrace::new();
        let status1 = VarId::new(0);
        let status2 = VarId::new(1);

        trace.push(
            Effect::FfiCall {
                function_name: "boltffi_call_1".to_string(),
                arguments: vec![],
                out_params: vec![],
            },
            test_span(),
        );
        trace.push(
            Effect::StatusProduced {
                status_var: status1,
            },
            test_span(),
        );
        trace.push(
            Effect::StatusChecked {
                status_var: status1,
            },
            test_span(),
        );

        trace.push(
            Effect::FfiCall {
                function_name: "boltffi_call_2".to_string(),
                arguments: vec![],
                out_params: vec![],
            },
            test_span(),
        );
        trace.push(
            Effect::StatusProduced {
                status_var: status2,
            },
            test_span(),
        );

        let rule = StatusMustBeChecked;
        let violations = rule.check(&trace);
        assert_eq!(violations.len(), 1);
    }
}
