//! Multi-phase user job drivers for the single-writer engine.
//!
//! Core owns job identity, journal transitions, cancel/deadline arbitration, and platform-batch
//! sequencing. Domain-specific document/scan semantics live in injectable [`JobDriver`]
//! implementations (stage-2: `lomo-workspace`). Bootstrap jobs have no driver.

use std::path::Path;
use std::sync::Arc;

use serde::{Deserialize, Serialize};

use crate::{
    ActionId, BatchId, CapabilityToken, JobId, LomoError, PlatformAction, PlatformActionBatch,
    PlatformBatchResult, WorkspaceDescriptor,
};

/// Opaque driver identity registered with the engine.
#[derive(Clone, Debug, Eq, PartialEq, Hash, Serialize, Deserialize)]
pub struct JobDriverKind(String);

impl JobDriverKind {
    /// Parses a stable driver kind identifier.
    ///
    /// # Errors
    ///
    /// Returns a validation error when the value is empty, oversized, or outside the protocol
    /// identifier alphabet.
    pub fn parse(raw: &str) -> Result<Self, LomoError> {
        JobId::parse(raw)
            .map(|id| Self(id.as_str().to_owned()))
            .map_err(|_error| {
                LomoError::validation(
                    "invalid_job_driver_kind",
                    "job driver kind must be a 1..=128 protocol identifier",
                )
            })
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// Context supplied to a driver while planning the next platform batch.
pub struct JobDriverContext<'a> {
    pub job_id: &'a JobId,
    pub exchange_root: &'a Path,
    pub workspace: &'a WorkspaceDescriptor,
    pub deadline_epoch_millis: u64,
    pub attempt: u32,
    pub next_counter: &'a mut u64,
}

impl JobDriverContext<'_> {
    /// Allocates the next unique action id for this engine generation.
    ///
    /// # Errors
    ///
    /// Returns corruption when the identifier counter overflows.
    pub fn next_action_id(&mut self, suffix: &str) -> Result<ActionId, LomoError> {
        let counter = *self.next_counter;
        *self.next_counter = counter.checked_add(1).ok_or_else(|| {
            LomoError::corruption(
                "identifier_counter_overflow",
                "journal identifier counter cannot advance",
            )
        })?;
        ActionId::parse(&format!("action-{counter}-{suffix}"))
    }

    /// Allocates the next batch id.
    ///
    /// # Errors
    ///
    /// Returns corruption when the identifier counter overflows.
    pub fn next_batch_id(&mut self) -> Result<BatchId, LomoError> {
        let counter = *self.next_counter;
        *self.next_counter = counter.checked_add(1).ok_or_else(|| {
            LomoError::corruption(
                "identifier_counter_overflow",
                "journal identifier counter cannot advance",
            )
        })?;
        BatchId::parse(&format!("batch-{counter}"))
    }

    /// Capability token used for platform actions (SAF token or the direct-root sentinel).
    #[must_use]
    pub fn capability(&self) -> CapabilityToken {
        match self.workspace {
            WorkspaceDescriptor::Saf { capability, .. } => capability.clone(),
            WorkspaceDescriptor::Direct { .. } => CapabilityToken::direct_root(),
        }
    }
}

/// Initial platform work produced by a driver for a newly started job.
#[derive(Clone, Debug)]
pub struct DriverStart {
    pub state_json: String,
    pub actions: Vec<PlatformAction>,
    /// Optional durable result already available without platform I/O (rare).
    pub result_json: Option<String>,
}

/// Continuation after a platform batch prefix is durably accepted.
#[derive(Clone, Debug)]
pub enum DriverAdvance {
    /// More platform work is required.
    NeedsBatch {
        state_json: String,
        actions: Vec<PlatformAction>,
        /// Optional intermediate durable result (for example a scan page) published before the job
        /// finishes. Callers may read it while the job continues or after completion.
        result_json: Option<String>,
    },
    /// Job finished successfully with a durable result payload (JSON text).
    Done { result_json: String },
}

/// Domain-owned multi-phase job logic. Must not perform platform I/O itself.
pub trait JobDriver: Send + Sync + 'static {
    /// Stable driver kind string.
    fn kind(&self) -> &'static str;

    /// Canonicalizes the semantic request used to identify one active durable operation.
    ///
    /// The default accepts any JSON value and serializes its normalized representation. Drivers
    /// may override when multiple wire requests represent the same domain operation.
    ///
    /// # Errors
    ///
    /// Returns validation when the request is not JSON, or internal failure when its canonical
    /// representation cannot be encoded.
    fn canonical_request_json(&self, request_json: &str) -> Result<String, LomoError> {
        let value: serde_json::Value = serde_json::from_str(request_json).map_err(|_error| {
            LomoError::validation(
                "invalid_job_request_json",
                "job driver request must be valid JSON",
            )
        })?;
        serde_json::to_string(&value).map_err(|_error| {
            LomoError::internal(
                "job_request_identity_unavailable",
                "job driver request identity cannot be encoded",
            )
        })
    }

    /// Recovers a canonical request from legacy active driver state when it is provable.
    ///
    /// `None` means the old state lacks enough facts; it remains active but cannot be coalesced.
    ///
    /// # Errors
    ///
    /// Drivers return corruption when the legacy continuation cannot be decoded safely.
    fn recover_canonical_request_json(
        &self,
        _state_json: &str,
    ) -> Result<Option<String>, LomoError> {
        Ok(None)
    }

    /// Plans the first platform batch from an opaque request payload (JSON text).
    ///
    /// # Errors
    ///
    /// Returns structured validation / resource-limit errors for illegal requests.
    fn start(
        &self,
        ctx: &mut JobDriverContext<'_>,
        request_json: &str,
    ) -> Result<DriverStart, LomoError>;

    /// Advances after a fully applied platform batch (no failed actions).
    ///
    /// Drivers must treat replay as idempotent: when postconditions are already satisfied the next
    /// plan must not request a second mutating write for the same logical effect.
    ///
    /// # Errors
    ///
    /// Returns structured errors when postconditions are unproven, fingerprints are stale, or the
    /// driver state is corrupt.
    fn advance(
        &self,
        ctx: &mut JobDriverContext<'_>,
        state_json: &str,
        batch: &PlatformActionBatch,
        result: &PlatformBatchResult,
    ) -> Result<DriverAdvance, LomoError>;
}

/// Registry of injectable job drivers.
#[derive(Clone, Default)]
pub struct JobDriverRegistry {
    drivers: Vec<Arc<dyn JobDriver>>,
}

impl JobDriverRegistry {
    #[must_use]
    pub fn new(drivers: Vec<Arc<dyn JobDriver>>) -> Self {
        Self { drivers }
    }

    #[must_use]
    pub fn get(&self, kind: &str) -> Option<Arc<dyn JobDriver>> {
        self.drivers
            .iter()
            .find(|driver| driver.kind() == kind)
            .cloned()
    }

    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.drivers.is_empty()
    }
}

impl std::fmt::Debug for JobDriverRegistry {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let kinds: Vec<&str> = self.drivers.iter().map(|driver| driver.kind()).collect();
        formatter
            .debug_struct("JobDriverRegistry")
            .field("kinds", &kinds)
            .finish()
    }
}

/// Builds a [`JobDriverContext`] for tests and engine wiring.
pub const fn job_driver_context<'a>(
    job_id: &'a JobId,
    exchange_root: &'a Path,
    workspace: &'a WorkspaceDescriptor,
    deadline_epoch_millis: u64,
    attempt: u32,
    next_counter: &'a mut u64,
) -> JobDriverContext<'a> {
    JobDriverContext {
        job_id,
        exchange_root,
        workspace,
        deadline_epoch_millis,
        attempt,
        next_counter,
    }
}
