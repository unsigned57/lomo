use super::{Rule, Violation, ViolationKind};
use crate::analysis::{BranchDivergence, DivergenceKind, EffectTrace};

pub struct BranchConsistency;

impl Rule for BranchConsistency {
    fn id(&self) -> &'static str {
        "FLOW001"
    }

    fn description(&self) -> &'static str {
        "Resources must be handled consistently across all branches"
    }

    fn check(&self, _trace: &EffectTrace) -> Vec<Violation> {
        Vec::new()
    }
}

impl BranchConsistency {
    pub fn check_divergences(&self, divergences: &[BranchDivergence]) -> Vec<Violation> {
        divergences
            .iter()
            .map(|d| {
                let kind = match d.kind {
                    DivergenceKind::FreedInOneBranch => ViolationKind::InconsistentFree {
                        pointer: d.variable,
                    },
                    DivergenceKind::ReleasedInOneBranch => {
                        ViolationKind::InconsistentRelease { handle: d.variable }
                    }
                    DivergenceKind::AllocatedInOneBranch => ViolationKind::InconsistentAllocation {
                        pointer: d.variable,
                    },
                    DivergenceKind::RetainedInOneBranch => {
                        ViolationKind::InconsistentRetain { handle: d.variable }
                    }
                };
                Violation::new(kind, self.id(), d.span.clone())
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ir::VarId;
    use crate::source::{SourceFile, SourceSpan};
    use std::sync::Arc;

    fn test_span() -> SourceSpan {
        let file = Arc::new(SourceFile::new("test.swift", "test content"));
        SourceSpan::new(file, 0u32, 4u32)
    }

    #[test]
    fn test_divergence_to_violation() {
        let divergences = vec![BranchDivergence {
            variable: VarId::new(0),
            kind: DivergenceKind::FreedInOneBranch,
            span: test_span(),
        }];

        let rule = BranchConsistency;
        let violations = rule.check_divergences(&divergences);

        assert_eq!(violations.len(), 1);
        assert!(matches!(
            violations[0].kind,
            ViolationKind::InconsistentFree { .. }
        ));
    }
}
