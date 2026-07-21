use std::time::Duration;

use crate::rules::{Severity, Violation};

#[derive(Debug)]
pub enum VerificationResult {
    Verified {
        unit_count: usize,
        rule_count: usize,
        duration: Duration,
    },
    Failed {
        violations: Vec<Violation>,
        duration: Duration,
    },
}

impl VerificationResult {
    pub fn verified(unit_count: usize, rule_count: usize, duration: Duration) -> Self {
        Self::Verified {
            unit_count,
            rule_count,
            duration,
        }
    }

    pub fn failed(violations: Vec<Violation>, duration: Duration) -> Self {
        Self::Failed {
            violations,
            duration,
        }
    }

    pub fn is_verified(&self) -> bool {
        matches!(self, Self::Verified { .. })
    }

    pub fn is_failed(&self) -> bool {
        matches!(self, Self::Failed { .. })
    }

    pub fn has_errors(&self) -> bool {
        match self {
            Self::Failed { violations, .. } => {
                violations.iter().any(|v| v.severity() == Severity::Error)
            }
            Self::Verified { .. } => false,
        }
    }

    pub fn has_warnings(&self) -> bool {
        match self {
            Self::Failed { violations, .. } => {
                violations.iter().any(|v| v.severity() == Severity::Warning)
            }
            Self::Verified { .. } => false,
        }
    }

    pub fn violation_count(&self) -> usize {
        match self {
            Self::Failed { violations, .. } => violations.len(),
            Self::Verified { .. } => 0,
        }
    }

    pub fn error_count(&self) -> usize {
        match self {
            Self::Failed { violations, .. } => violations
                .iter()
                .filter(|v| v.severity() == Severity::Error)
                .count(),
            Self::Verified { .. } => 0,
        }
    }

    pub fn warning_count(&self) -> usize {
        match self {
            Self::Failed { violations, .. } => violations
                .iter()
                .filter(|v| v.severity() == Severity::Warning)
                .count(),
            Self::Verified { .. } => 0,
        }
    }

    pub fn duration(&self) -> Duration {
        match self {
            Self::Verified { duration, .. } | Self::Failed { duration, .. } => *duration,
        }
    }

    pub fn unit_count(&self) -> usize {
        match self {
            Self::Verified { unit_count, .. } => *unit_count,
            Self::Failed { .. } => 0,
        }
    }

    pub fn rule_count(&self) -> usize {
        match self {
            Self::Verified { rule_count, .. } => *rule_count,
            Self::Failed { .. } => 0,
        }
    }
}
