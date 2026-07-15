#![deny(unsafe_code)]

use std::fmt;

use lomo_sync_core::plan_envelope;

#[cfg(feature = "feasibility-probe")]
pub mod feasibility_probe;

#[derive(Debug, uniffi::Error)]
pub enum SyncPlannerError {
    Rejected { reason: String },
}

impl fmt::Display for SyncPlannerError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Rejected { reason } => {
                write!(
                    formatter,
                    "Rust sync planner rejected the request: {reason}"
                )
            }
        }
    }
}

impl std::error::Error for SyncPlannerError {}

#[uniffi::export]
/// Plans one sync v1 request through the native facade.
///
/// # Errors
///
/// Returns [`SyncPlannerError::Rejected`] when the core rejects the request envelope.
pub fn plan_sync_envelope(input: Vec<u8>) -> Result<Vec<u8>, SyncPlannerError> {
    let input = input.into_boxed_slice();
    plan_envelope(&input).map_err(|error| SyncPlannerError::Rejected {
        reason: error.to_string(),
    })
}

uniffi::setup_scaffolding!();
