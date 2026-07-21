mod branch;
mod buffer;
mod memory;
mod refcount;
mod status;
mod violation;

pub use branch::BranchConsistency;
pub use buffer::BufferBoundsCheck;
pub use memory::{AllocFreeBalance, NoDoubleFree, NoUseAfterFree};
pub use refcount::{NoDoubleRelease, RetainReleaseBalance};
pub use status::StatusMustBeChecked;
pub use violation::{Severity, Violation, ViolationKind};

use crate::analysis::EffectTrace;
use crate::contract::FfiContract;

pub trait Rule: Send + Sync {
    fn id(&self) -> &'static str;
    fn description(&self) -> &'static str;
    fn check(&self, trace: &EffectTrace) -> Vec<Violation>;

    fn check_with_contract(&self, trace: &EffectTrace, _contract: &FfiContract) -> Vec<Violation> {
        self.check(trace)
    }
}

pub struct RuleRegistry {
    rules: Vec<Box<dyn Rule>>,
}

impl RuleRegistry {
    pub fn new() -> Self {
        Self { rules: Vec::new() }
    }

    pub fn with_defaults() -> Self {
        let mut registry = Self::new();
        registry.register(Box::new(AllocFreeBalance));
        registry.register(Box::new(NoUseAfterFree));
        registry.register(Box::new(NoDoubleFree));
        registry.register(Box::new(RetainReleaseBalance));
        registry.register(Box::new(NoDoubleRelease));
        registry.register(Box::new(BufferBoundsCheck));
        registry.register(Box::new(StatusMustBeChecked));
        registry
    }

    pub fn register(&mut self, rule: Box<dyn Rule>) {
        self.rules.push(rule);
    }

    pub fn check_all(&self, trace: &EffectTrace) -> Vec<Violation> {
        self.rules
            .iter()
            .flat_map(|rule| rule.check(trace))
            .collect()
    }

    pub fn check_all_with_contract(
        &self,
        trace: &EffectTrace,
        contract: &FfiContract,
    ) -> Vec<Violation> {
        self.rules
            .iter()
            .flat_map(|rule| rule.check_with_contract(trace, contract))
            .collect()
    }

    pub fn rule_count(&self) -> usize {
        self.rules.len()
    }
}

impl Default for RuleRegistry {
    fn default() -> Self {
        Self::with_defaults()
    }
}
