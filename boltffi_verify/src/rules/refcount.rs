use std::collections::{HashMap, HashSet};

use super::{Rule, Violation, ViolationKind};
use crate::analysis::{Effect, EffectTrace};
use crate::contract::FfiContract;
use crate::ir::VarId;

pub struct RetainReleaseBalance;

impl Rule for RetainReleaseBalance {
    fn id(&self) -> &'static str {
        "REF001"
    }

    fn description(&self) -> &'static str {
        "Every retained object must be released exactly once"
    }

    fn check(&self, trace: &EffectTrace) -> Vec<Violation> {
        self.check_impl(trace, None)
    }

    fn check_with_contract(&self, trace: &EffectTrace, contract: &FfiContract) -> Vec<Violation> {
        self.check_impl(trace, Some(contract))
    }
}

impl RetainReleaseBalance {
    fn check_impl(&self, trace: &EffectTrace, contract: Option<&FfiContract>) -> Vec<Violation> {
        let mut retained: HashMap<VarId, RetainInfo> = HashMap::new();
        let mut released: HashSet<VarId> = HashSet::new();
        let mut violations = Vec::new();

        trace.iter().for_each(|entry| match &entry.effect {
            Effect::Retain { opaque_handle, .. } => {
                let source_text = entry.span.text().unwrap_or_default().to_string();
                let is_callback_bridge = contract
                    .map(|c| c.is_callback_bridge_retain(&source_text))
                    .unwrap_or(false);

                retained.insert(
                    *opaque_handle,
                    RetainInfo {
                        span: entry.span.clone(),
                        is_callback_bridge,
                    },
                );
            }
            Effect::Release { opaque_handle } | Effect::TakeRetained { opaque_handle, .. } => {
                if !retained.contains_key(opaque_handle) {
                    violations.push(Violation::new(
                        ViolationKind::ReleaseUnretained {
                            handle: *opaque_handle,
                        },
                        self.id(),
                        entry.span.clone(),
                    ));
                } else {
                    released.insert(*opaque_handle);
                }
            }
            _ => {}
        });

        retained
            .iter()
            .filter(|(handle, info)| !released.contains(handle) && !info.is_callback_bridge)
            .for_each(|(handle, info)| {
                violations.push(Violation::new(
                    ViolationKind::RetainLeak { handle: *handle },
                    self.id(),
                    info.span.clone(),
                ));
            });

        violations
    }
}

struct RetainInfo {
    span: crate::source::SourceSpan,
    is_callback_bridge: bool,
}

pub struct NoDoubleRelease;

impl Rule for NoDoubleRelease {
    fn id(&self) -> &'static str {
        "REF002"
    }

    fn description(&self) -> &'static str {
        "Objects must not be released multiple times"
    }

    fn check(&self, trace: &EffectTrace) -> Vec<Violation> {
        let mut released: HashSet<VarId> = HashSet::new();

        trace
            .iter()
            .filter_map(|entry| {
                let handle = match &entry.effect {
                    Effect::Release { opaque_handle } => Some(*opaque_handle),
                    Effect::TakeRetained { opaque_handle, .. } => Some(*opaque_handle),
                    _ => None,
                };

                handle.and_then(|h| {
                    if released.contains(&h) {
                        Some(Violation::new(
                            ViolationKind::DoubleRelease { handle: h },
                            self.id(),
                            entry.span.clone(),
                        ))
                    } else {
                        released.insert(h);
                        None
                    }
                })
            })
            .collect()
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
    fn test_balanced_retain_release() {
        let mut trace = EffectTrace::new();
        let obj = VarId::new(0);
        let handle = VarId::new(1);

        trace.push(
            Effect::Retain {
                object: obj,
                opaque_handle: handle,
            },
            test_span(),
        );
        trace.push(
            Effect::Release {
                opaque_handle: handle,
            },
            test_span(),
        );

        let rule = RetainReleaseBalance;
        let violations = rule.check(&trace);
        assert!(violations.is_empty());
    }

    #[test]
    fn test_retain_leak_detected() {
        let mut trace = EffectTrace::new();
        let obj = VarId::new(0);
        let handle = VarId::new(1);

        trace.push(
            Effect::Retain {
                object: obj,
                opaque_handle: handle,
            },
            test_span(),
        );

        let rule = RetainReleaseBalance;
        let violations = rule.check(&trace);
        assert_eq!(violations.len(), 1);
        assert!(matches!(
            violations[0].kind,
            ViolationKind::RetainLeak { .. }
        ));
    }

    #[test]
    fn test_double_release_detected() {
        let mut trace = EffectTrace::new();
        let obj = VarId::new(0);
        let handle = VarId::new(1);

        trace.push(
            Effect::Retain {
                object: obj,
                opaque_handle: handle,
            },
            test_span(),
        );
        trace.push(
            Effect::Release {
                opaque_handle: handle,
            },
            test_span(),
        );
        trace.push(
            Effect::Release {
                opaque_handle: handle,
            },
            test_span(),
        );

        let rule = NoDoubleRelease;
        let violations = rule.check(&trace);
        assert_eq!(violations.len(), 1);
        assert!(matches!(
            violations[0].kind,
            ViolationKind::DoubleRelease { .. }
        ));
    }

    #[test]
    fn test_take_retained_counts_as_release() {
        let mut trace = EffectTrace::new();
        let obj = VarId::new(0);
        let handle = VarId::new(1);
        let result = VarId::new(2);

        trace.push(
            Effect::Retain {
                object: obj,
                opaque_handle: handle,
            },
            test_span(),
        );
        trace.push(
            Effect::TakeRetained {
                opaque_handle: handle,
                result,
            },
            test_span(),
        );

        let rule = RetainReleaseBalance;
        let violations = rule.check(&trace);
        assert!(violations.is_empty());
    }
}
