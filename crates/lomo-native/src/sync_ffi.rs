//! Stage-5 production sync FFI conversion surface (post P5-13).
//!
//! Conversion-only mapping between `BoltFFI` DTOs and `lomo-sync` / `lomo-core` secret lease /
//! `lomo-git` composition. Business rules stay in `lomo-sync` / `lomo-core`. Git adapter
//! construction happens here so `lomo-sync` never depends on `lomo-git` (no crate cycle with
//! git2).

use std::{
    path::Path,
    sync::{Arc, OnceLock},
    time::Duration,
};

use boltffi::{data, export};
use lomo_application::{UpdateMemoRequest, WorkspaceSession};
use lomo_core::{
    self as core, EphemeralSecretVault, ErrorCategory, LomoError, OperationId, RetryDisposition,
    SecretLeaseId, SecretMaterial, SharedSecretVault,
};
use lomo_sync::{
    self as sync, ConflictPage, ConflictPathRecord, ConflictPathStatus, ConflictResolution,
    ConflictSession, ConflictSessionPresence, ConflictSessionState, RemoteSyncPort,
    ResolvedLocalPullMutation, SyncBackendConfig, SyncBackendKind, SyncCyclePlanSummary, SyncPaths,
    advance_baseline_after_local_pull, collect_resolved_local_pull_mutations,
    connect_sync_remote_port, list_sync_conflicts, read_baseline, read_conflict_artifact,
    read_conflict_session_state, read_cycle_state, request_sync_cycle_cancel,
    reset_sync_control_tree, resolve_sync_conflicts, run_composed_sync_cycle,
    run_composed_sync_cycle_with_remote_port,
};
use lomo_workspace::MemoId;

use crate::{EngineError, LomoEngine};

/// Maximum UTF-8 bytes accepted for a free-function resolution batch (fail closed).
const MAX_RESOLUTION_BATCH_BYTES: usize = 1_048_576;

/// Maximum resolutions in one free-function batch (mirrors conflict page scale).
const MAX_RESOLUTION_BATCH_ITEMS: usize = 100;

/// Process-local ephemeral secret vault for dark host / host-test lease round-trips.
///
/// Never durable across process death; journals and `WorkManager` inputs must only hold lease ids.
fn process_secret_vault() -> &'static SharedSecretVault {
    static VAULT: OnceLock<SharedSecretVault> = OnceLock::new();
    VAULT.get_or_init(|| Arc::new(EphemeralSecretVault::new()))
}

fn boundary_err(code: &str, diagnostic: &str) -> LomoError {
    match LomoError::from_platform_boundary(
        ErrorCategory::Validation,
        code,
        RetryDisposition::Never,
        None,
        None,
        diagnostic,
    ) {
        Ok(error) | Err(error) => error,
    }
}

fn resource_limit_err(code: &str, diagnostic: &str) -> LomoError {
    match LomoError::from_platform_boundary(
        ErrorCategory::ResourceLimit,
        code,
        RetryDisposition::Never,
        None,
        None,
        diagnostic,
    ) {
        Ok(error) | Err(error) => error,
    }
}

fn require_workspace_root(workspace_root: &str) -> Result<&Path, EngineError> {
    if workspace_root.is_empty() || workspace_root.len() > 4096 {
        return Err(EngineError::from(boundary_err(
            "sync_ffi_workspace_root_invalid",
            "workspace_root must be 1..=4096 bytes",
        )));
    }
    Ok(Path::new(workspace_root))
}

fn acquire_workspace_cycle_lock(
    workspace_root: &Path,
) -> Result<lomo_platform_fs::ProcessFileLock, EngineError> {
    let paths = SyncPaths::for_workspace(workspace_root);
    paths.ensure_layout().map_err(EngineError::from)?;
    match lomo_platform_fs::ProcessFileLock::try_acquire(&paths.cycle_lock) {
        Ok(lock) => Ok(lock),
        Err(err) if err.code() == "process_lock_held" => Err(EngineError::from(sync::sync_busy(
            "sync_cycle_lock_held",
            "another host already holds the workspace sync cycle lock",
        ))),
        Err(err) => Err(EngineError::from(err)),
    }
}

/// Wire status for one conflict path (no enum ordinals; named variants only).
#[data]
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum SyncConflictPathStatusDto {
    #[default]
    Open,
    ResolvedKeepLocal,
    ResolvedKeepRemote,
    ResolvedMerged,
    SkippedForNow,
}

/// One conflict path fact for Sync Center listing (digests + refs only; no body bytes).
#[data]
#[derive(Clone, Debug, Default)]
pub struct SyncConflictPathDto {
    pub path: String,
    pub kind: String,
    pub local_digest: Option<String>,
    pub remote_digest: Option<String>,
    pub baseline_digest: Option<String>,
    pub remote_token_present: bool,
    pub local_artifact_ref: Option<String>,
    pub remote_artifact_ref: Option<String>,
    pub baseline_artifact_ref: Option<String>,
    pub status: SyncConflictPathStatusDto,
}

/// Proven presence of a durable conflict session on the list wire.
///
/// Missing `conflicts.rec` is `Absent` (not an engine error). A decoded session, including one
/// with zero paths, is `Present`. Truncation / permission stay `EngineError`.
#[data]
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum SyncConflictSessionStateDto {
    #[default]
    Absent,
    Present,
}

/// Page of conflict paths (coarse-grained; not a DAO).
#[data]
#[derive(Clone, Debug, Default)]
pub struct SyncConflictPageDto {
    pub session: SyncConflictSessionStateDto,
    pub session_id: String,
    pub conflict_revision: u64,
    pub items: Vec<SyncConflictPathDto>,
    pub next_cursor: Option<u32>,
}

/// One user resolution submission (typed path + kind; merged body optional).
#[data]
#[derive(Clone, Debug, Default)]
pub struct SyncConflictResolutionDto {
    pub path: String,
    /// `keep_local` | `keep_remote` | `merged_body` | `skip_for_now`
    pub kind: String,
    /// Required only for `merged_body`.
    pub merged_body: Option<String>,
}

/// Outcome of a resolution batch (new revision always returned on success).
#[data]
#[derive(Clone, Debug, Default)]
pub struct SyncConflictResolveResultDto {
    pub session_id: String,
    pub conflict_revision: u64,
    pub applied_paths: Vec<String>,
}

/// Owner-computed conflict suggestion for one path (display-only wire).
///
/// `safe_choice` / `suggested_choice` are `keep_local` | `keep_remote` | `merge_text` when
/// present. `merged_body` is the owner merge output whenever it succeeded, independent of the
/// suggested choice — the host may display it and submit it back as `merged_body` on resolve.
/// Binary paths and non-deterministic merges carry `None` choices; the host keeps the conflict
/// open for user resolution.
#[data]
#[derive(Clone, Debug, Default)]
pub struct SyncConflictSuggestionDto {
    pub safe_choice: Option<String>,
    pub suggested_choice: Option<String>,
    pub merged_body: Option<String>,
}

/// Opaque secret lease wire (id only — never plaintext).
#[data]
#[derive(Clone, Debug, Default)]
pub struct SyncSecretLeaseDto {
    pub lease_id: String,
}

/// Non-secret backend configuration wire (explicit per-backend fields; no borrowed meanings).
///
/// `backend_kind` selects which fields are meaningful: `webdav` uses `endpoint_url` + `identity`
/// (username); `s3` uses `endpoint_url` + `identity` (access key id) + `s3_*`; `git` uses
/// `endpoint_url` (remote URL) + `identity` (HTTPS username) + `git_*`; `hermetic_fake` uses only
/// `remote_dataset_id`. Fields outside the selected kind must be empty — mixed shapes are
/// rejected at the boundary rather than silently borrowed. Secrets never appear here; the secret
/// travels via `secret_lease_id` only.
#[data]
#[derive(Clone, Debug, Default)]
pub struct SyncBackendConfigDto {
    /// `hermetic_fake` | `webdav` | `s3` | `git`.
    pub backend_kind: String,
    /// `WebDAV` base URL / S3 endpoint / Git remote URL.
    pub endpoint_url: String,
    /// `WebDAV` username / S3 access key id / Git HTTPS username (non-secret identity).
    pub identity: String,
    /// S3 bucket (empty for other kinds).
    pub s3_bucket: String,
    /// S3 key prefix (empty when unused).
    pub s3_prefix: String,
    /// S3 region (empty for other kinds).
    pub s3_region: String,
    /// Git branch short name (e.g. `main`).
    pub git_branch: String,
    /// Git commit author name.
    pub git_author_name: String,
    /// Git commit author email.
    pub git_author_email: String,
    /// Opaque remote dataset id for the durable identity fence.
    pub remote_dataset_id: String,
}

/// WorkManager-facing retry disposition (maps Rust `RetryDisposition`; no fixed three-retry).
#[data]
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum SyncRetryDispositionDto {
    #[default]
    Never,
    AfterUserAction,
    Transient,
}

/// Structured dark sync boundary error code mapping for host tests (no secret material).
#[data]
#[derive(Clone, Debug, Default)]
pub struct SyncRetryHintDto {
    pub disposition: SyncRetryDispositionDto,
    /// Optional delay millis for transient; always `None` for `Never` / `AfterUserAction` in this slice.
    pub retry_after_millis: Option<u64>,
}

/// Coarse plan/readiness cycle summary (dark free-function wire; no body bytes / no secrets).
#[data]
#[derive(Clone, Debug, Default)]
pub struct SyncCyclePlanSummaryDto {
    pub session_id: String,
    /// `first_takeover` | `incremental`
    pub session_kind: String,
    pub session_revision: u64,
    pub baseline_established: bool,
    pub ensure_present_count: u32,
    pub ensure_absent_count: u32,
    pub pull_present_count: u32,
    pub open_conflict_count: u32,
    /// Mutations held because the provider offered no strong conditional-update validator.
    pub hold_count: u32,
    pub open_conflict_paths: u32,
    pub conflict_revision: Option<u64>,
    /// `never` | `after_user_action` | `transient` (Rust-owned name; no fixed three-retry).
    pub retry_disposition: String,
    /// Intent pages published + verified this cycle (0 for plan-only).
    pub pages_applied: u32,
    /// True when this cycle advanced the durable baseline.
    pub baseline_advanced: bool,
    /// Local projection entries the cycle observed.
    pub local_entry_count: u32,
    /// Remote listing entries the cycle observed across all pages.
    pub remote_listed_count: u32,
    /// Baseline entries after this cycle's advancement.
    pub baseline_entry_count: u32,
}

/// Durable cycle record wire (`cycle_state.rec`) — sole authority for sync status.
///
/// `has_record` is false when no cycle has ever run for the workspace (`phase` = `idle`).
/// `state_stamp` is the monotonic freshness marker: hosts must drop late writes with an older
/// stamp instead of overwriting newer state.
#[data]
#[derive(Clone, Debug, Default)]
pub struct SyncCycleStatusDto {
    pub has_record: bool,
    pub cycle_seq: u64,
    pub cycle_id: String,
    /// Identity fence the cycle ran under (`generation|dataset|remote-identity`).
    pub fence_key: String,
    /// `hermetic_fake` | `webdav` | `s3` | `git` (empty when no record).
    pub backend_kind: String,
    pub session_id: String,
    pub apply_remote: bool,
    /// `idle` | `running` | `completed` | `failed` | `cancelled`.
    pub phase: String,
    /// `planning` | `applying` | `finished` | `failed` | `cancelled` | `interrupted`.
    pub stage: String,
    pub ensure_present_count: u32,
    pub ensure_absent_count: u32,
    pub pull_present_count: u32,
    pub open_conflict_count: u32,
    pub hold_count: u32,
    pub local_entry_count: u32,
    pub remote_listed_count: u32,
    pub baseline_entry_count: u32,
    /// Intent pages published before terminal; on `cancelled` this is the cancellation point.
    pub pages_applied: u32,
    pub baseline_advanced: bool,
    /// `never` | `after_user_action` | `transient` (empty while running).
    pub retry_disposition: String,
    pub failure_code: Option<String>,
    pub failure_message: Option<String>,
    pub cancel_requested: bool,
    pub started_at_ms: i64,
    pub updated_at_ms: i64,
    pub finished_at_ms: Option<i64>,
    /// Sticky: last apply cycle that completed without a transient/failure outcome.
    pub last_successful_at_ms: Option<i64>,
    /// Monotonic freshness marker (epoch for late-result rejection).
    pub state_stamp: u64,
}

/// Real backend probe result (`testConnection`): capabilities + listing facts from an actual
/// adapter construction + listing round-trip — never an enqueue acceptance.
#[data]
#[derive(Clone, Debug, Default)]
pub struct SyncBackendProbeDto {
    /// `hermetic_fake` | `webdav` | `s3` | `git`.
    pub backend_kind: String,
    /// Remote entries observed by the probe listing across all pages.
    pub listed_entry_count: u32,
    /// Whole-batch snapshot revision presence (Git branch tip; `None` for per-path providers).
    pub snapshot_revision_present: bool,
    pub conditional_write: bool,
    pub conditional_delete: bool,
    pub probed_at_ms: i64,
}

const fn status_to_dto(status: ConflictPathStatus) -> SyncConflictPathStatusDto {
    match status {
        ConflictPathStatus::Open => SyncConflictPathStatusDto::Open,
        ConflictPathStatus::ResolvedKeepLocal => SyncConflictPathStatusDto::ResolvedKeepLocal,
        ConflictPathStatus::ResolvedKeepRemote => SyncConflictPathStatusDto::ResolvedKeepRemote,
        ConflictPathStatus::ResolvedMerged => SyncConflictPathStatusDto::ResolvedMerged,
        ConflictPathStatus::SkippedForNow => SyncConflictPathStatusDto::SkippedForNow,
    }
}

fn path_record_to_dto(record: &ConflictPathRecord) -> SyncConflictPathDto {
    SyncConflictPathDto {
        path: record.path.clone(),
        kind: match record.kind {
            sync::ConflictContentKind::Markdown => "markdown".to_owned(),
            sync::ConflictContentKind::Binary => "binary".to_owned(),
        },
        local_digest: record.local_digest.clone(),
        remote_digest: record.remote_digest.clone(),
        baseline_digest: record.baseline_digest.clone(),
        // Never expose the token value across FFI — presence only.
        remote_token_present: record.remote_token.is_some(),
        local_artifact_ref: record.local_artifact_ref.clone(),
        remote_artifact_ref: record.remote_artifact_ref.clone(),
        baseline_artifact_ref: record.baseline_artifact_ref.clone(),
        status: status_to_dto(record.status),
    }
}

fn page_to_dto(page: ConflictPage) -> Result<SyncConflictPageDto, LomoError> {
    let next_cursor = match page.next_cursor {
        None => None,
        Some(cursor) => Some(u32::try_from(cursor).map_err(|_overflow| {
            resource_limit_err(
                "sync_ffi_conflict_cursor_overflow",
                "conflict page cursor exceeds u32 wire limit",
            )
        })?),
    };
    Ok(SyncConflictPageDto {
        session: match page.session {
            ConflictSessionPresence::Absent => SyncConflictSessionStateDto::Absent,
            ConflictSessionPresence::Present => SyncConflictSessionStateDto::Present,
        },
        session_id: page.session_id,
        conflict_revision: page.conflict_revision,
        items: page.items.iter().map(path_record_to_dto).collect(),
        next_cursor,
    })
}

fn resolution_from_dto(dto: &SyncConflictResolutionDto) -> Result<ConflictResolution, LomoError> {
    if dto.path.is_empty() || dto.path.len() > sync::MAX_SYNC_PATH_BYTES {
        return Err(boundary_err(
            "sync_ffi_path_invalid",
            "conflict resolution path must be 1..=1024 bytes",
        ));
    }
    match dto.kind.as_str() {
        "keep_local" => Ok(ConflictResolution::KeepLocal {
            path: dto.path.clone(),
        }),
        "keep_remote" => Ok(ConflictResolution::KeepRemote {
            path: dto.path.clone(),
        }),
        "skip_for_now" => Ok(ConflictResolution::SkipForNow {
            path: dto.path.clone(),
        }),
        "merged_body" => {
            let body = dto.merged_body.as_deref().ok_or_else(|| {
                boundary_err(
                    "sync_ffi_merged_body_missing",
                    "merged_body resolution requires merged_body text",
                )
            })?;
            if body.len() > sync::MAX_CONFLICT_ARTIFACT_BYTES {
                return Err(resource_limit_err(
                    "sync_ffi_merged_body_too_large",
                    "merged body exceeds the 1 MiB conflict artifact limit",
                ));
            }
            Ok(ConflictResolution::MergedBody {
                path: dto.path.clone(),
                body: body.to_owned(),
            })
        }
        _ => Err(boundary_err(
            "sync_ffi_resolution_kind_invalid",
            "resolution kind must be keep_local|keep_remote|merged_body|skip_for_now",
        )),
    }
}

/// Lists conflict paths from durable `.lomo/sync/v1` for a workspace root (dark free-function).
///
/// # Errors
///
/// Structured engine errors when the workspace path is invalid or the durable session is corrupt.
#[export]
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned String wire types"
)]
pub fn sync_list_conflicts(
    workspace_root: String,
    cursor: u32,
    limit: u32,
) -> Result<SyncConflictPageDto, EngineError> {
    if workspace_root.is_empty() || workspace_root.len() > 4096 {
        return Err(EngineError::from(boundary_err(
            "sync_ffi_workspace_root_invalid",
            "workspace_root must be 1..=4096 bytes",
        )));
    }
    if limit == 0 || limit as usize > sync::MAX_CONFLICT_PAGE_ITEMS {
        return Err(EngineError::from(resource_limit_err(
            "sync_ffi_conflict_page_limit",
            "conflict page limit must be 1..=100",
        )));
    }
    let paths = SyncPaths::for_workspace(Path::new(&workspace_root));
    let page =
        list_sync_conflicts(&paths, cursor as usize, limit as usize).map_err(EngineError::from)?;
    page_to_dto(page).map_err(EngineError::from)
}

/// Reads one durable conflict artifact body by relative ref (dark free-function).
///
/// List/detail wires stay digest/ref-first; this port loads candidate bytes only when the host
/// requests them (markdown triple-view). Binary Sync Center UI must not invent text previews from
/// these bytes.
///
/// # Errors
///
/// Validation for empty/invalid root or traversal refs; storage when missing; `resource_limit` when
/// the artifact exceeds the 1 MiB host limit.
#[export]
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned String wire types"
)]
pub fn sync_read_conflict_artifact(
    workspace_root: String,
    artifact_ref: String,
) -> Result<Vec<u8>, EngineError> {
    if workspace_root.is_empty() || workspace_root.len() > 4096 {
        return Err(EngineError::from(boundary_err(
            "sync_ffi_workspace_root_invalid",
            "workspace_root must be 1..=4096 bytes",
        )));
    }
    if artifact_ref.is_empty() || artifact_ref.len() > sync::MAX_SYNC_PATH_BYTES * 2 {
        return Err(EngineError::from(boundary_err(
            "sync_ffi_artifact_ref_invalid",
            "conflict artifact ref must be non-empty and bounded",
        )));
    }
    let paths = SyncPaths::for_workspace(Path::new(&workspace_root));
    read_conflict_artifact(&paths, &artifact_ref).map_err(EngineError::from)
}

/// Computes the owner conflict suggestion for one text path (dark free-function).
///
/// Kotlin displays `merged_body`/`suggested_choice` and submits the user's choice; the merge
/// algorithm, identity-keyed memo merge, and newer-side adjudication are owned by `lomo-sync`.
/// Per-side bodies/mtimes are optional exactly as the conflict/review models carry them.
///
/// # Errors
///
/// Validation when a supplied body exceeds the durable conflict artifact byte budget.
#[export]
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned String wire types"
)]
pub fn sync_suggest_conflict_resolution(
    local_body: Option<String>,
    remote_body: Option<String>,
    local_last_modified_ms: Option<i64>,
    remote_last_modified_ms: Option<i64>,
    is_binary: bool,
) -> Result<SyncConflictSuggestionDto, EngineError> {
    for body in [&local_body, &remote_body].into_iter().flatten() {
        if body.len() > sync::MAX_CONFLICT_ARTIFACT_BYTES {
            return Err(EngineError::from(resource_limit_err(
                "sync_ffi_suggestion_body_too_large",
                "conflict suggestion body exceeds the durable artifact byte budget",
            )));
        }
    }
    let suggestion = sync::suggest_conflict_resolution(
        local_body.as_deref(),
        remote_body.as_deref(),
        local_last_modified_ms,
        remote_last_modified_ms,
        is_binary,
    );
    Ok(SyncConflictSuggestionDto {
        safe_choice: suggestion.safe_choice.map(choice_name),
        suggested_choice: suggestion.suggested_choice.map(choice_name),
        merged_body: suggestion.merged_body,
    })
}

fn choice_name(choice: sync::ConflictSuggestionChoice) -> String {
    match choice {
        sync::ConflictSuggestionChoice::KeepLocal => "keep_local".to_owned(),
        sync::ConflictSuggestionChoice::KeepRemote => "keep_remote".to_owned(),
        sync::ConflictSuggestionChoice::MergeText => "merge_text".to_owned(),
    }
}

/// Resolves conflict paths with the expected conflict revision fence (dark free-function).
///
/// # Errors
///
/// Stale revision, invalid kind/path, oversize batch/body, or durable session errors.
#[export]
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned String / Vec wire types"
)]
pub fn sync_resolve_conflicts(
    workspace_root: String,
    expected_revision: u64,
    resolutions: Vec<SyncConflictResolutionDto>,
) -> Result<SyncConflictResolveResultDto, EngineError> {
    if workspace_root.is_empty() || workspace_root.len() > 4096 {
        return Err(EngineError::from(boundary_err(
            "sync_ffi_workspace_root_invalid",
            "workspace_root must be 1..=4096 bytes",
        )));
    }
    if resolutions.is_empty() {
        return Err(EngineError::from(boundary_err(
            "sync_ffi_resolution_batch_empty",
            "resolution batch must contain at least one path",
        )));
    }
    if resolutions.len() > MAX_RESOLUTION_BATCH_ITEMS {
        return Err(EngineError::from(resource_limit_err(
            "sync_ffi_resolution_batch_too_large",
            "resolution batch exceeds 100 items",
        )));
    }
    let batch_bytes: usize = resolutions
        .iter()
        .map(|item| {
            item.path.len() + item.kind.len() + item.merged_body.as_ref().map_or(0, String::len)
        })
        .sum();
    if batch_bytes > MAX_RESOLUTION_BATCH_BYTES {
        return Err(EngineError::from(resource_limit_err(
            "sync_ffi_resolution_batch_bytes",
            "resolution batch payload exceeds 1 MiB",
        )));
    }
    let mapped = resolutions
        .iter()
        .map(resolution_from_dto)
        .collect::<Result<Vec<_>, _>>()
        .map_err(EngineError::from)?;
    let paths = SyncPaths::for_workspace(Path::new(&workspace_root));
    let result =
        resolve_sync_conflicts(&paths, expected_revision, &mapped).map_err(EngineError::from)?;
    Ok(SyncConflictResolveResultDto {
        session_id: result.session.session_id,
        conflict_revision: result.session.conflict_revision,
        applied_paths: result.applied_paths,
    })
}

/// Issues an ephemeral secret lease (process-local; never journals plaintext).
///
/// # Errors
///
/// Resource limit when the vault is full; validation when secret bytes are empty/oversized.
#[export]
pub fn sync_issue_secret_lease(
    secret_bytes: Vec<u8>,
    ttl_millis: u64,
) -> Result<SyncSecretLeaseDto, EngineError> {
    if secret_bytes.is_empty() {
        return Err(EngineError::from(boundary_err(
            "sync_ffi_secret_empty",
            "secret material must be non-empty",
        )));
    }
    // Fail closed on multi-megabyte secrets at the FFI edge (never clamp).
    if secret_bytes.len() > 64 * 1024 {
        return Err(EngineError::from(resource_limit_err(
            "sync_ffi_secret_too_large",
            "secret material exceeds the 64 KiB lease limit",
        )));
    }
    if ttl_millis == 0 {
        return Err(EngineError::from(boundary_err(
            "sync_ffi_secret_ttl_invalid",
            "secret lease TTL must be positive",
        )));
    }
    let material = SecretMaterial::from_bytes(secret_bytes);
    let lease = process_secret_vault()
        .put(material, Duration::from_millis(ttl_millis), None)
        .map_err(EngineError::from)?;
    Ok(SyncSecretLeaseDto {
        lease_id: lease.as_str().to_owned(),
    })
}

/// Resolves a lease id to confirm presence (returns length only — never secret bytes on the wire).
///
/// # Errors
///
/// `secret_lease_missing` / `secret_lease_expired` / invalid lease id.
#[export]
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned String wire types"
)]
pub fn sync_probe_secret_lease(lease_id: String) -> Result<u32, EngineError> {
    let id = SecretLeaseId::parse(&lease_id).map_err(EngineError::from)?;
    let material = process_secret_vault()
        .resolve(&id)
        .map_err(EngineError::from)?;
    let len = u32::try_from(material.len()).map_err(|_overflow| {
        EngineError::from(resource_limit_err(
            "sync_ffi_secret_len_overflow",
            "secret material length exceeds u32",
        ))
    })?;
    Ok(len)
}

/// Revokes a lease (best-effort wipe via vault drop).
///
/// # Errors
///
/// Validation when the lease id is not a valid protocol identifier.
#[export]
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned String wire types"
)]
pub fn sync_revoke_secret_lease(lease_id: String) -> Result<(), EngineError> {
    let id = SecretLeaseId::parse(&lease_id).map_err(EngineError::from)?;
    process_secret_vault().revoke(&id);
    Ok(())
}

fn cycle_summary_to_dto(summary: SyncCyclePlanSummary) -> SyncCyclePlanSummaryDto {
    let session_kind = match summary.session_kind {
        sync::SessionKind::FirstTakeover => "first_takeover",
        sync::SessionKind::Migration => "migration",
        sync::SessionKind::Incremental => "incremental",
    };
    SyncCyclePlanSummaryDto {
        session_id: summary.session_id,
        session_kind: session_kind.to_owned(),
        session_revision: summary.session_revision,
        baseline_established: summary.baseline_established,
        ensure_present_count: summary.ensure_present_count,
        ensure_absent_count: summary.ensure_absent_count,
        pull_present_count: summary.pull_present_count,
        open_conflict_count: summary.open_conflict_count,
        hold_count: summary.hold_count,
        open_conflict_paths: summary.open_conflict_paths,
        conflict_revision: summary.conflict_revision,
        retry_disposition: summary.retry_disposition.to_owned(),
        pages_applied: summary.pages_applied,
        baseline_advanced: summary.baseline_advanced,
        local_entry_count: summary.local_entry_count,
        remote_listed_count: summary.remote_listed_count,
        baseline_entry_count: summary.baseline_entry_count,
    }
}

fn cycle_record_to_dto(record: Option<sync::SyncCycleRecord>) -> SyncCycleStatusDto {
    let Some(record) = record else {
        return SyncCycleStatusDto {
            phase: "idle".to_owned(),
            ..SyncCycleStatusDto::default()
        };
    };
    let phase = match record.phase {
        sync::SyncCyclePhase::Running => "running",
        sync::SyncCyclePhase::Completed => "completed",
        sync::SyncCyclePhase::Failed => "failed",
        sync::SyncCyclePhase::Cancelled => "cancelled",
    };
    SyncCycleStatusDto {
        has_record: true,
        cycle_seq: record.cycle_seq,
        cycle_id: record.cycle_id,
        fence_key: record.fence_key,
        backend_kind: record.backend_kind,
        session_id: record.session_id,
        apply_remote: record.apply_remote,
        phase: phase.to_owned(),
        stage: record.stage,
        ensure_present_count: record.ensure_present_count,
        ensure_absent_count: record.ensure_absent_count,
        pull_present_count: record.pull_present_count,
        open_conflict_count: record.open_conflict_count,
        hold_count: record.hold_count,
        local_entry_count: record.local_entry_count,
        remote_listed_count: record.remote_listed_count,
        baseline_entry_count: record.baseline_entry_count,
        pages_applied: record.pages_applied,
        baseline_advanced: record.baseline_advanced,
        retry_disposition: record.retry_disposition,
        failure_code: record.failure_code,
        failure_message: record.failure_message,
        cancel_requested: record.cancel_requested,
        started_at_ms: record.started_at_ms,
        updated_at_ms: record.updated_at_ms,
        finished_at_ms: record.finished_at_ms,
        last_successful_at_ms: record.last_successful_at_ms,
        state_stamp: record.state_stamp,
    }
}

fn parse_backend_kind(kind: &str) -> Result<SyncBackendKind, EngineError> {
    match kind.trim().to_ascii_lowercase().as_str() {
        "hermetic_fake" | "hermetic" | "fake" => Ok(SyncBackendKind::HermeticFake),
        "webdav" => Ok(SyncBackendKind::WebDav),
        "s3" => Ok(SyncBackendKind::S3),
        "git" => Ok(SyncBackendKind::Git),
        _ => Err(EngineError::from(boundary_err(
            "sync_ffi_backend_kind_invalid",
            "backend_kind must be hermetic_fake|webdav|s3|git",
        ))),
    }
}

/// Rejects a wire field that carries a value while belonging to another backend kind.
fn reject_stray_backend_field(field: &str, value: &str) -> Result<(), EngineError> {
    if value.trim().is_empty() {
        Ok(())
    } else {
        Err(EngineError::from(boundary_err(
            "sync_ffi_config_field_mismatch",
            &format!("field {field} does not belong to the selected backend_kind"),
        )))
    }
}

/// Converts the wire DTO into the typed [`SyncBackendConfig`] variant.
///
/// Per-kind required fields must be non-empty and fields belonging to other kinds must be
/// empty — a mixed shape is a wire contract violation, not a silent default. `identity` is the
/// non-secret username / access key id; the secret always travels via `secret_lease_id`.
fn backend_config_from_dto(dto: &SyncBackendConfigDto) -> Result<SyncBackendConfig, EngineError> {
    let kind = parse_backend_kind(&dto.backend_kind)?;
    let dataset_id = dto.remote_dataset_id.trim().to_owned();

    match kind {
        SyncBackendKind::HermeticFake => {
            reject_stray_backend_field("endpoint_url", &dto.endpoint_url)?;
            reject_stray_backend_field("identity", &dto.identity)?;
            reject_stray_backend_field("s3_bucket", &dto.s3_bucket)?;
            reject_stray_backend_field("s3_prefix", &dto.s3_prefix)?;
            reject_stray_backend_field("s3_region", &dto.s3_region)?;
            reject_stray_backend_field("git_branch", &dto.git_branch)?;
            reject_stray_backend_field("git_author_name", &dto.git_author_name)?;
            reject_stray_backend_field("git_author_email", &dto.git_author_email)?;
            Ok(SyncBackendConfig::HermeticFake {
                remote_dataset_id: dataset_id,
            })
        }
        SyncBackendKind::WebDav => {
            reject_stray_backend_field("s3_bucket", &dto.s3_bucket)?;
            reject_stray_backend_field("s3_prefix", &dto.s3_prefix)?;
            reject_stray_backend_field("s3_region", &dto.s3_region)?;
            reject_stray_backend_field("git_branch", &dto.git_branch)?;
            reject_stray_backend_field("git_author_name", &dto.git_author_name)?;
            reject_stray_backend_field("git_author_email", &dto.git_author_email)?;
            Ok(SyncBackendConfig::WebDav {
                endpoint_url: dto.endpoint_url.trim().to_owned(),
                username: dto.identity.trim().to_owned(),
                remote_dataset_id: dataset_id,
            })
        }
        SyncBackendKind::S3 => {
            reject_stray_backend_field("git_branch", &dto.git_branch)?;
            reject_stray_backend_field("git_author_name", &dto.git_author_name)?;
            reject_stray_backend_field("git_author_email", &dto.git_author_email)?;
            Ok(SyncBackendConfig::S3 {
                endpoint_url: dto.endpoint_url.trim().to_owned(),
                access_key_id: dto.identity.trim().to_owned(),
                bucket: dto.s3_bucket.trim().to_owned(),
                prefix: dto.s3_prefix.trim().to_owned(),
                region: dto.s3_region.trim().to_owned(),
                remote_dataset_id: dataset_id,
            })
        }
        SyncBackendKind::Git => {
            reject_stray_backend_field("s3_bucket", &dto.s3_bucket)?;
            reject_stray_backend_field("s3_prefix", &dto.s3_prefix)?;
            reject_stray_backend_field("s3_region", &dto.s3_region)?;
            if dto.git_branch.trim().is_empty() {
                return Err(EngineError::from(boundary_err(
                    "git_config_incomplete",
                    "git_branch is required for the git backend",
                )));
            }
            lomo_git::validate_git_remote_url(dto.endpoint_url.trim())
                .map_err(EngineError::from)?;
            Ok(SyncBackendConfig::Git {
                remote_url: dto.endpoint_url.trim().to_owned(),
                username: dto.identity.trim().to_owned(),
                branch: dto.git_branch.trim().to_owned(),
                author_name: dto.git_author_name.trim().to_owned(),
                author_email: dto.git_author_email.trim().to_owned(),
                remote_dataset_id: dataset_id,
            })
        }
    }
}

/// Runs one **production-shaped** owner cycle via `lomo-sync` composition.
///
/// Conversion only: resolves an optional process-local secret lease (material never journals),
/// builds non-secret [`SyncBackendConfig`], opens real store local snapshot + protocol remote port
/// (or hermetic fake remote for host proof), then returns the owner disposition summary.
///
/// Git: constructs `lomo-git` at this edge (app-private bare mirror under `.lomo/sync/v1/git-mirror`)
/// and calls [`run_composed_sync_cycle_with_remote_port`] so `lomo-sync` stays free of `git2`.
///
/// Does **not** re-implement planner rules.
///
/// Session-less apply refuses pending `KeepRemote` / Merged local pulls
/// (`sync_local_pull_requires_workspace_session`). Production apply cycles that may pull local
/// documents must use [`LomoEngine::sync_run_cycle`] so writes go through `WorkspaceSession`.
///
/// # Errors
///
/// Validation for blank/oversize workspace, invalid backend kind, incomplete config, missing/expired
/// lease when required; pending local pulls without a session; store open / adapter / planner
/// boundary errors from the owner.
#[export]
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned wire types"
)]
pub fn sync_run_cycle(
    workspace_root: String,
    config: SyncBackendConfigDto,
    secret_lease_id: String,
    apply_remote: bool,
) -> Result<SyncCyclePlanSummaryDto, EngineError> {
    execute_composed_sync_cycle(
        &workspace_root,
        &config,
        &secret_lease_id,
        apply_remote,
        None,
    )
}

#[export]
impl LomoEngine {
    /// Runs one composed sync cycle, then applies pending `KeepRemote`/Merged local pulls through
    /// the open [`WorkspaceSession`].
    ///
    /// # Errors
    ///
    /// Session unavailable, Direct workspace mismatch, pending-pull apply / CAS failures, and the
    /// same composed-cycle failures as [`sync_run_cycle`].
    #[expect(
        clippy::needless_pass_by_value,
        reason = "BoltFFI instance boundary requires owned wire types"
    )]
    pub fn sync_run_cycle(
        &self,
        workspace_root: String,
        config: SyncBackendConfigDto,
        secret_lease_id: String,
        apply_remote: bool,
    ) -> Result<SyncCyclePlanSummaryDto, EngineError> {
        require_engine_direct_workspace(self, Path::new(&workspace_root))?;
        let session = crate::session_ffi::session_arc(self)?;
        execute_composed_sync_cycle(
            &workspace_root,
            &config,
            &secret_lease_id,
            apply_remote,
            Some(session.as_ref()),
        )
    }
}

/// Loads the durable `WorkspaceGenerationId` for `workspace_root` (read-only; never mints).
///
/// # Errors
///
/// Validation when the workspace root is empty/oversize or `generation.rec` is missing/malformed.
#[export]
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned String wire types"
)]
pub fn sync_workspace_generation(workspace_root: String) -> Result<String, EngineError> {
    let workspace = require_workspace_root(&workspace_root)?;
    lomo_workspace::load_workspace_generation(workspace)
        .map(|id| id.as_str().to_owned())
        .map_err(EngineError::from)
}

/// Clears durable `.lomo/sync/v1` control records for identity reset (not user Markdown/media).
///
/// Serializes against [`sync_run_cycle`] via the workspace cycle lock.
///
/// # Errors
///
/// Validation when the workspace root is empty/oversize; busy when the cycle lock is held;
/// storage when control files cannot be removed.
#[export]
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned String wire types"
)]
pub fn sync_reset_control_tree(workspace_root: String) -> Result<(), EngineError> {
    let workspace = require_workspace_root(&workspace_root)?;
    let _cycle_lock = acquire_workspace_cycle_lock(workspace)?;
    let paths = SyncPaths::for_workspace(workspace);
    reset_sync_control_tree(&paths).map_err(EngineError::from)
}

/// Reads the durable cycle record — the sole authority for host sync status.
///
/// Read-only: no cycle lock, no state writes. `has_record=false`/`phase=idle` when the workspace
/// has never run a cycle. A stale `Running` record is repaired by the next `begin_sync_cycle`,
/// not by this read (process death repair is a writer-side concern).
///
/// # Errors
///
/// Validation errors for a blank/unresolvable workspace root and structured corruption errors
/// when `cycle_state.rec` fails its checksum/schema gate (never a silent clean-slate).
#[export]
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned String wire types"
)]
pub fn sync_cycle_status(workspace_root: String) -> Result<SyncCycleStatusDto, EngineError> {
    let workspace = require_workspace_root(&workspace_root)?;
    let paths = SyncPaths::for_workspace(workspace);
    let record = read_cycle_state(&paths).map_err(EngineError::from)?;
    Ok(cycle_record_to_dto(record))
}

/// Persists a cancellation request bound to the running cycle's identity fence.
///
/// Rejects when no matching cycle is running or when the requester holds a stale cycle
/// identity — the returned record (on success) is the post-write authoritative state.
///
/// # Errors
///
/// `sync_cycle_not_running` / `sync_cycle_fence_mismatch` style validation errors plus storage
/// errors for the durable write; validation errors for a blank workspace root.
#[export]
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned String wire types"
)]
pub fn sync_request_cancel(workspace_root: String) -> Result<SyncCycleStatusDto, EngineError> {
    let workspace = require_workspace_root(&workspace_root)?;
    let paths = SyncPaths::for_workspace(workspace);
    let record = request_sync_cycle_cancel(&paths).map_err(EngineError::from)?;
    Ok(cycle_record_to_dto(Some(record)))
}

/// Probes the configured backend with the same adapter construction a real cycle uses.
///
/// Constructs the remote port (Git via `lomo-git`, others via `lomo-sync` composition), probes
/// capabilities and streams the remote listing — a real round-trip, never an enqueue
/// acceptance. Takes the workspace cycle lock so a probe cannot interleave with a running
/// cycle's remote mutations (Git mirror writes are not concurrent-safe).
///
/// # Errors
///
/// Validation errors for blank roots / malformed config / stale secret leases, `Busy` when the
/// cycle lock is held, and the adapter's probe/transport errors (structured, no fabrication).
#[export]
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned String wire types"
)]
pub fn sync_probe_backend(
    workspace_root: String,
    config: SyncBackendConfigDto,
    secret_lease_id: String,
) -> Result<SyncBackendProbeDto, EngineError> {
    let workspace = require_workspace_root(&workspace_root)?;
    let config = backend_config_from_dto(&config)?;
    let secret_owned = resolve_secret_material(&secret_lease_id)?;
    let secret_ref = secret_owned.as_deref();
    let _cycle_lock = acquire_workspace_cycle_lock(workspace)?;
    let paths = SyncPaths::for_workspace(workspace);

    let remote: Box<dyn RemoteSyncPort> = if matches!(config, SyncBackendConfig::Git { .. }) {
        Box::new(connect_git_port(workspace, &config, secret_ref)?)
    } else {
        connect_sync_remote_port(workspace, &paths, &config, secret_ref)
            .map_err(EngineError::from)?
    };
    let capabilities = remote.remote_capabilities().map_err(EngineError::from)?;
    let listing = remote.list_remote_pages().map_err(EngineError::from)?;
    let listed_entry_count = listing.pages.iter().map(Vec::len).sum::<usize>();
    let snapshot_revision_present = listing.snapshot_revision.is_some();

    let probed_at_ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |duration| {
            i64::try_from(duration.as_millis()).unwrap_or(i64::MAX)
        });
    Ok(SyncBackendProbeDto {
        backend_kind: config.kind().wire_name().to_owned(),
        listed_entry_count: u32::try_from(listed_entry_count).unwrap_or(u32::MAX),
        snapshot_revision_present,
        conditional_write: capabilities.conditional_write,
        conditional_delete: capabilities.conditional_delete,
        probed_at_ms,
    })
}

/// Composes store local + `lomo-git` remote and runs the owner cycle.
///
/// Git wire fields are explicit: `endpoint_url` = remote URL (userinfo rejected by `lomo-git`),
/// `identity` = HTTPS username (default `git` when a token lease is present), `git_branch`,
/// `git_author_name`, `git_author_email`; the secret lease carries the token (may be empty for
/// local bare remotes).
fn run_composed_git_cycle(
    workspace_root: &Path,
    config: &SyncBackendConfig,
    secret_material: Option<&[u8]>,
    apply_remote: bool,
) -> Result<SyncCyclePlanSummary, LomoError> {
    let remote = connect_git_port(workspace_root, config, secret_material)?;
    run_composed_sync_cycle_with_remote_port(workspace_root, config, &remote, apply_remote)
}

/// Builds the `lomo-git` remote port for a Git backend (composition + probe share one
/// construction path so `testConnection` exercises the same adapter as a real cycle).
fn connect_git_port(
    workspace_root: &Path,
    config: &SyncBackendConfig,
    secret_material: Option<&[u8]>,
) -> Result<lomo_git::GitAdapter<lomo_git::WorkspaceFileGitObjectSource>, LomoError> {
    let SyncBackendConfig::Git {
        remote_url,
        username,
        branch,
        author_name,
        author_email,
        ..
    } = config
    else {
        return Err(boundary_err(
            "git_config_incomplete",
            "git composition requires the Git backend config variant",
        ));
    };
    if remote_url.trim().is_empty() {
        return Err(boundary_err(
            "git_config_incomplete",
            "git endpoint_url (remote) is required",
        ));
    }
    let token = match secret_material {
        None | Some([]) => String::new(),
        Some(bytes) => std::str::from_utf8(bytes)
            .map_err(|_err| {
                boundary_err(
                    "sync_secret_not_utf8",
                    "secret material must be valid UTF-8 for protocol credentials",
                )
            })?
            .to_owned(),
    };
    let username = if username.trim().is_empty() {
        if token.is_empty() {
            String::new()
        } else {
            // GitHub/GitLab PAT convention when UI only stores a token.
            "git".to_owned()
        }
    } else {
        username.trim().to_owned()
    };

    let paths = SyncPaths::for_workspace(workspace_root);
    let mirror_dir = paths.root.join("git-mirror");
    std::fs::create_dir_all(&paths.root).map_err(|err| {
        boundary_err(
            "git_mirror_parent_create_failed",
            &format!("failed to create git mirror parent: {err}"),
        )
    })?;

    let objects = lomo_git::WorkspaceFileGitObjectSource::new(workspace_root.to_path_buf());
    lomo_git::connect_workspace_git(
        remote_url.trim(),
        branch.trim(),
        mirror_dir,
        &username,
        &token,
        objects,
        author_name.trim(),
        author_email.trim(),
        Duration::from_secs(30),
    )
}

/// Resolves a process-local secret lease into owned material (empty lease id → `None`).
fn resolve_secret_material(secret_lease_id: &str) -> Result<Option<Vec<u8>>, EngineError> {
    let trimmed = secret_lease_id.trim();
    if trimmed.is_empty() {
        return Ok(None);
    }
    let id = SecretLeaseId::parse(trimmed).map_err(EngineError::from)?;
    let material = process_secret_vault()
        .resolve(&id)
        .map_err(EngineError::from)?;
    Ok(Some(material.as_bytes().to_vec()))
}

fn execute_composed_sync_cycle(
    workspace_root: &str,
    config_dto: &SyncBackendConfigDto,
    secret_lease_id: &str,
    apply_remote: bool,
    session: Option<&WorkspaceSession>,
) -> Result<SyncCyclePlanSummaryDto, EngineError> {
    let workspace = require_workspace_root(workspace_root)?;
    let config = backend_config_from_dto(config_dto)?;

    let secret_owned = resolve_secret_material(secret_lease_id)?;
    let secret_ref = secret_owned.as_deref();
    let _cycle_lock = acquire_workspace_cycle_lock(workspace)?;

    if apply_remote && session.is_none() {
        refuse_sessionless_local_pulls(workspace)?;
    }

    let summary = if matches!(config, SyncBackendConfig::Git { .. }) {
        run_composed_git_cycle(workspace, &config, secret_ref, apply_remote)
            .map_err(EngineError::from)?
    } else {
        run_composed_sync_cycle(workspace, &config, secret_ref, apply_remote)
            .map_err(EngineError::from)?
    };

    if apply_remote && let Some(session) = session {
        apply_resolved_local_pulls_via_session(workspace, session)?;
    }
    Ok(cycle_summary_to_dto(summary))
}

fn refuse_sessionless_local_pulls(workspace: &Path) -> Result<(), EngineError> {
    let pulls = pending_resolved_local_pulls(workspace)?;
    if pulls.is_empty() {
        Ok(())
    } else {
        Err(EngineError::from(boundary_err(
            "sync_local_pull_requires_workspace_session",
            "KeepRemote/Merged local apply requires an open WorkspaceSession",
        )))
    }
}

fn pending_resolved_local_pulls(
    workspace: &Path,
) -> Result<Vec<ResolvedLocalPullMutation>, EngineError> {
    let paths = SyncPaths::for_workspace(workspace);
    match read_conflict_session_state(&paths).map_err(EngineError::from)? {
        ConflictSessionState::Absent => Ok(Vec::new()),
        ConflictSessionState::Present(session) => {
            collect_resolved_local_pull_mutations(&paths, &session).map_err(EngineError::from)
        }
    }
}

fn require_engine_direct_workspace(
    engine: &LomoEngine,
    requested: &Path,
) -> Result<(), EngineError> {
    match &engine.workspace {
        Some(core::WorkspaceDescriptor::Direct { canonical_root, .. }) => {
            if workspace_paths_match(canonical_root, requested)? {
                Ok(())
            } else {
                Err(EngineError::from(boundary_err(
                    "sync_cycle_workspace_mismatch",
                    "engine Direct workspace does not match the cycle workspace_root",
                )))
            }
        }
        Some(core::WorkspaceDescriptor::Saf { .. }) => Err(EngineError::from(boundary_err(
            "sync_local_pull_requires_direct_workspace",
            "KeepRemote/Merged session apply is composed for Direct workspaces",
        ))),
        None => Err(EngineError::from(boundary_err(
            "workspace_session_unavailable",
            "composed sync cycle requires an active Direct workspace",
        ))),
    }
}

fn workspace_paths_match(left: &Path, right: &Path) -> Result<bool, EngineError> {
    if left == right {
        return Ok(true);
    }
    let left_canon = left.canonicalize().map_err(|err| {
        EngineError::from(boundary_err(
            "sync_cycle_workspace_unresolvable",
            &format!("cannot canonicalize engine workspace: {err}"),
        ))
    })?;
    let right_canon = right.canonicalize().map_err(|err| {
        EngineError::from(boundary_err(
            "sync_cycle_workspace_unresolvable",
            &format!("cannot canonicalize cycle workspace_root: {err}"),
        ))
    })?;
    Ok(left_canon == right_canon)
}

fn apply_resolved_local_pulls_via_session(
    workspace: &Path,
    session: &WorkspaceSession,
) -> Result<(), EngineError> {
    let paths = SyncPaths::for_workspace(workspace);
    let conflict = match read_conflict_session_state(&paths).map_err(EngineError::from)? {
        ConflictSessionState::Absent => return Ok(()),
        ConflictSessionState::Present(session_state) => session_state,
    };
    let mutations =
        collect_resolved_local_pull_mutations(&paths, &conflict).map_err(EngineError::from)?;
    if mutations.is_empty() {
        return Ok(());
    }
    for mutation in &mutations {
        apply_one_local_pull(session, &conflict, mutation)?;
    }
    let baseline = read_baseline(&paths).map_err(EngineError::from)?;
    advance_baseline_after_local_pull(&paths, conflict.conflict_revision, baseline, &mutations)
        .map_err(EngineError::from)?;
    Ok(())
}

fn apply_one_local_pull(
    session: &WorkspaceSession,
    conflict: &ConflictSession,
    mutation: &ResolvedLocalPullMutation,
) -> Result<(), EngineError> {
    let record = conflict
        .paths
        .iter()
        .find(|path| path.path == mutation.path)
        .ok_or_else(|| {
            EngineError::from(boundary_err(
                "conflict_local_pull_path_unknown",
                "local pull path is not part of the durable conflict session",
            ))
        })?;
    let planning_fingerprint = record.local_digest.as_deref().ok_or_else(|| {
        EngineError::from(boundary_err(
            "conflict_local_pull_local_digest_missing",
            "KeepRemote/Merged session apply requires the planning-time local document digest",
        ))
    })?;
    lomo_workspace::SourceFingerprint::parse(planning_fingerprint).map_err(EngineError::from)?;
    let source = String::from_utf8(mutation.body.clone()).map_err(|_err| {
        EngineError::from(boundary_err(
            "sync_local_pull_body_not_utf8",
            "KeepRemote/Merged local body must be UTF-8 Markdown",
        ))
    })?;
    let content = lomo_workspace::extract_memo_body_from_raw(&source).map_err(EngineError::from)?;
    let memo_ids = session
        .active_memo_ids_for_source_path(&mutation.path)
        .map_err(EngineError::from)?;
    let memo_id = match memo_ids.as_slice() {
        [] => {
            return Err(EngineError::from(boundary_err(
                "sync_local_pull_memo_missing",
                "KeepRemote/Merged local apply requires exactly one active memo at the source path",
            )));
        }
        [one] => MemoId::parse(one).map_err(EngineError::from)?,
        _ => {
            return Err(EngineError::from(boundary_err(
                "sync_local_pull_source_not_unique",
                "KeepRemote/Merged local apply refuses a source document with multiple memos",
            )));
        }
    };
    let operation_id = OperationId::parse(&format!(
        "spull-{}-{}",
        conflict.conflict_revision, mutation.content_digest
    ))
    .map_err(EngineError::from)?;
    session
        .update_memo(UpdateMemoRequest {
            operation_id,
            memo_id,
            content,
            expected_document_fingerprint: planning_fingerprint.to_owned(),
            pending_promotes: Vec::new(),
        })
        .map_err(EngineError::from)?;
    Ok(())
}

/// Crate-visible helper for host contract tests: whether a string looks like a lease id (not secret).
#[must_use]
pub fn looks_like_lease_id(value: &str) -> bool {
    value.starts_with("lease-") && value.len() <= 128
}
