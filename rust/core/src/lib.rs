#![deny(unsafe_code)]

mod engine;
mod error;
mod job;
mod native_task;
mod platform;
mod secret;
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
pub use native_task::{
    MAX_NATIVE_QUEUE, MAX_NATIVE_WORKERS, NativeTaskCompletion, NativeTaskDispatch,
    NativeTaskExecutor, NativeTaskOutcome, NativeTaskWorkerPool, NativeWorkerAttach, PendingEffect,
    RecordingNativeExecutor,
};
pub use platform::{
    ActionEvidence, ActionOutcome, ActionResult, DocumentKind, DocumentMetadata, ExchangeArtifact,
    ExpectedFingerprint, MetadataPage, PlatformAction, PlatformActionBatch, PlatformActionOutput,
    PlatformBatchResult, Sha256Digest, VerifiedAbsence, WorkspaceTarget, WriteMode,
};
pub use secret::{EphemeralSecretVault, SecretLeaseId, SecretMaterial, SharedSecretVault};
pub use types::{
    ActionId, BatchId, CapabilityToken, CoreRevision, EventSequence, ExchangeToken,
    InvalidationScope, JobId, OperationId, PageSize, RelativeWorkspacePath, WorkspaceDescriptor,
    WorkspaceId, event_sequence_requires_full_invalidate,
};
