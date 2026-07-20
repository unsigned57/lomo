#![deny(unsafe_code)]

mod engine;
mod error;
mod job;
mod platform;
mod types;

pub use engine::{
    CancelOutcome, CoreEvent, CoreEventListener, EngineConfig, EngineState, JobStep, LomoEngine,
    ShutdownDeadline, ShutdownOutcome, Subscription,
};
pub use error::{ErrorCategory, LomoError, RetryDisposition};
pub use job::{
    DriverAdvance, DriverStart, JobDriver, JobDriverContext, JobDriverKind, JobDriverRegistry,
    job_driver_context,
};
pub use platform::{
    ActionEvidence, ActionOutcome, ActionResult, DocumentKind, DocumentMetadata, ExchangeArtifact,
    ExpectedFingerprint, MetadataPage, PlatformAction, PlatformActionBatch, PlatformActionOutput,
    PlatformBatchResult, Sha256Digest, VerifiedAbsence, WorkspaceTarget, WriteMode,
};
pub use types::{
    ActionId, BatchId, CapabilityToken, CoreRevision, EventSequence, ExchangeToken, JobId,
    OperationId, PageSize, RelativeWorkspacePath, WorkspaceDescriptor, WorkspaceId,
};
