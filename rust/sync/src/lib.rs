//! Unified remote sync durable core for stage 5 (`lomo-sync`) — production owner (post P5-13).
//!
//! Owns the provider-neutral pipeline
//! `RemoteSnapshot → ProviderNeutralIntent → PreparedRemoteBatch → PublishReceipt →
//! VerifiedRemoteState`, durable session/baseline/tombstone/conflict models, delete-vs-edit and
//! tombstone-first recovery, secret-free diagnostics, and the hermetic state machine for
//! first-takeover / partial-listing / verify-before-baseline.
//!
//! Sole production remote-sync business owner. `WebDAV` / S3 adapters live here; Git adapter
//! (`lomo-git`) is constructed at the native composition edge and plugged in via
//! [`run_composed_sync_cycle_with_remote_port`] (avoids crate cycles with `git2`).

#![deny(unsafe_code)]

mod conflict;
mod durable;
mod error;
mod limits;
mod machine;
mod pipeline;
mod ports;
mod recovery;
mod s3;
mod webdav;

pub use conflict::{
    ConflictApplyRemoteResult, ConflictBodySource, ConflictCandidateBodies, ConflictContentKind,
    ConflictPage, ConflictPathRecord, ConflictPathStatus, ConflictResolution,
    ConflictResolveResult, ConflictSession, ResolvedLocalPullMutation,
    advance_baseline_after_local_pull, apply_resolved_conflicts_remote,
    baseline_must_hold_for_path, clear_conflict_session, collect_resolved_local_pull_mutations,
    collect_resolved_present_bodies, conflict_artifacts_dir, conflict_path_from_open,
    decode_conflict_session_bytes, encode_conflict_session, is_markdown_sync_path,
    list_sync_conflicts, materialize_conflicts_from_plan, may_advance_baseline_for_path,
    read_conflict_artifact, read_conflict_session, resolve_sync_conflicts,
    validate_merged_markdown_body, write_conflict_artifact, write_conflict_session,
};
pub use durable::{
    BaselineEntry, BaselineHead, SYNC_RECORD_MAGIC, SessionKind, SyncIdentityFence, SyncPaths,
    SyncSession, TombstoneEntry, TombstoneSet, decode_sync_record, encode_sync_record,
    read_baseline, read_session, read_sync_record, read_tombstones, write_baseline, write_session,
    write_sync_record_atomic, write_tombstones,
};
pub use error::{
    authentication as sync_authentication, busy as sync_busy, conflict as sync_conflict,
    corrupt_state as sync_corrupt_state, network as sync_network, permission as sync_permission,
    resource_limit as sync_resource_limit, storage as sync_storage, validation as sync_validation,
};
pub use limits::{
    BASELINE_SHARD_COUNT, MAX_ACTION_PAGE_ITEMS, MAX_CONFLICT_ARTIFACT_BYTES,
    MAX_CONFLICT_PAGE_ITEMS, MAX_DURABLE_RECORD_BYTES, MAX_S3_LIST_BODY_BYTES, MAX_S3_LIST_PAGES,
    MAX_S3_MULTIPART_PARTS, MAX_S3_OBJECT_BYTES, MAX_S3_SNAPSHOT_ENTRIES,
    MAX_STREAMING_INTERMEDIATE_INTENTS, MAX_STREAMING_REMOTE_PATH_KEYS, MAX_SYNC_PATH_BYTES,
    MAX_WEBDAV_MULTISTATUS_BYTES, MAX_WEBDAV_OBJECT_BYTES, MAX_WEBDAV_SNAPSHOT_ENTRIES,
    MAX_WEBDAV_TRAVERSAL_DEPTH, S3_MULTIPART_PART_BYTES, SCALE_HOST_PATH_COUNT,
    SYNC_DURABLE_SCHEMA,
};
pub use machine::{
    StreamingPlanOutcome, StreamingSyncCycleResult, SyncBackendConfig, SyncBackendKind,
    SyncCyclePlanSummary, SyncCycleResult, apply_with_verify, first_takeover_preflight,
    inspect_sync_cycle_plan, inspect_sync_cycle_plan_with_ports, migration_preflight, plan_intents,
    plan_intents_streaming, reject_if_migration_class_emitted_delete, run_composed_sync_cycle,
    run_composed_sync_cycle_with_remote_port, run_sync_cycle, run_sync_cycle_streaming,
};
pub use pipeline::{
    BatchAtomicity, ContentDigest, PathPublishStatus, PipelineStage, PreparedRemoteBatch,
    ProviderNeutralIntent, PublishReceipt, RemotePathEntry, RemoteSnapshot, SnapshotCompleteness,
    SyncPath, VerifiedRemoteState, VerifyStatus, is_owned_sync_user_path,
};
pub use ports::{
    FakeLocalPort, FakePublishedBody, FakeRemotePort, LocalPathEntry, LocalSnapshot, LocalSyncPort,
    MapRemoteObjectSource, RemoteListingStream, RemoteSyncPort, StoreLocalSnapshotPort,
};
pub use recovery::{
    DeleteVersusEdit, RecoverDeleteRequest, SyncDiagnosticEntry, SyncDiagnosticError,
    SyncDiagnosticExport, SyncRequestTelemetry, UserDeleteContext, UserDeleteGate,
    UserDeleteRequest, assert_fence_for_revival, build_default_diagnostic_export,
    classify_delete_versus_edit, path_is_sync_control, plan_delete_versus_edit_intent,
    record_user_delete_tombstone_first, recover_pending_delete_intent, reset_sync_control_tree,
    tombstone_authoritative_for_fence, user_delete_gate_for_path, write_diagnostic_export,
};
pub use s3::{
    EMPTY_PAYLOAD_SHA256, MapS3ConnectParams, MapS3ObjectSource, MultipartConfirmedPart,
    MultipartSession, RcloneCryptConfig, RcloneFilenameEncoding, RcloneFilenameEncryption,
    RcloneKeyMaterial, S3Adapter, S3AddressingStyle, S3Credentials, S3Endpoint, S3ObjectSource,
    S3Transport, WorkspaceFileObjectSource as S3WorkspaceFileObjectSource,
    aws_published_sigv4_example_matches, connect_map_s3_source, connect_workspace_s3,
    decrypt_filename_path, decrypt_payload, encrypt_filename_path, encrypt_payload,
    map_s3_http_status,
};
pub use webdav::{
    MapObjectSource, RemoteCapabilities, WebDavAdapter, WebDavCredentials, WebDavEndpoint,
    WebDavObjectSource, WorkspaceFileObjectSource as WebDavWorkspaceFileObjectSource,
    connect_map_source, connect_workspace_webdav, is_same_origin, map_http_status,
};

use lomo_core::{ErrorCategory, LomoError};

/// Crate package identity for architecture ownership locks.
pub const SYNC_CRATE_NAME: &str = "lomo-sync";

/// Owner identity document for stage-5 ownership locks.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SyncOwnerIdentity {
    /// Package name of the sync owner crate.
    pub crate_name: &'static str,
}

impl SyncOwnerIdentity {
    /// Returns the shipped dark-owner identity.
    #[must_use]
    pub const fn current() -> Self {
        Self {
            crate_name: SYNC_CRATE_NAME,
        }
    }

    /// Validates the owner name matches the shipped package.
    ///
    /// # Errors
    ///
    /// Validation when the name is forged.
    pub fn validate(self) -> Result<(), LomoError> {
        if self.crate_name != SYNC_CRATE_NAME {
            return Err(sync_validation(
                "invalid_sync_owner",
                "sync owner crate name must be lomo-sync",
            ));
        }
        Ok(())
    }
}

/// Maps a sync boundary error category for contracts that assert `CorruptState` semantics.
#[must_use]
pub const fn error_category(error: &LomoError) -> ErrorCategory {
    error.category()
}
