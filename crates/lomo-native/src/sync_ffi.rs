//! Stage-5 production sync FFI conversion surface (post P5-13).
//!
//! Conversion-only mapping between `BoltFFI` DTOs and `lomo-sync` / `lomo-core` secret lease /
//! `lomo-git` composition. Business rules stay in `lomo-sync` / `lomo-core`. Git adapter
//! construction happens here so `lomo-sync` never depends on `lomo-git` (no crate cycle with
//! git2).

use std::sync::{Arc, OnceLock};
use std::time::Duration;

use boltffi::{data, export};
use lomo_core::{
    EphemeralSecretVault, ErrorCategory, LomoError, RetryDisposition, SecretLeaseId,
    SecretMaterial, SharedSecretVault,
};
use lomo_sync::{
    self as sync, ConflictPage, ConflictPathRecord, ConflictPathStatus, ConflictResolution,
    SyncBackendConfig, SyncBackendKind, SyncCyclePlanSummary, SyncPaths, inspect_sync_cycle_plan,
    list_sync_conflicts, read_conflict_artifact, resolve_sync_conflicts, run_composed_sync_cycle,
    run_composed_sync_cycle_with_remote_port,
};

use crate::EngineError;

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

/// Page of conflict paths (coarse-grained; not a DAO).
#[data]
#[derive(Clone, Debug, Default)]
pub struct SyncConflictPageDto {
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

/// Opaque secret lease wire (id only — never plaintext).
#[data]
#[derive(Clone, Debug, Default)]
pub struct SyncSecretLeaseDto {
    pub lease_id: String,
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
    pub open_conflict_paths: u32,
    pub conflict_revision: Option<u64>,
    /// `never` | `after_user_action` | `transient` (Rust-owned name; no fixed three-retry).
    pub retry_disposition: String,
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
    let paths = SyncPaths::for_workspace(std::path::Path::new(&workspace_root));
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
    let paths = SyncPaths::for_workspace(std::path::Path::new(&workspace_root));
    read_conflict_artifact(&paths, &artifact_ref).map_err(EngineError::from)
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
    let paths = SyncPaths::for_workspace(std::path::Path::new(&workspace_root));
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

/// Maps a core retry disposition name to WorkManager-facing DTO (dark; no fixed three-retry).
///
/// # Errors
///
/// Validation when the name is unknown.
#[export]
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned String wire types"
)]
pub fn sync_retry_disposition_from_name(name: String) -> Result<SyncRetryHintDto, EngineError> {
    let disposition = match name.as_str() {
        "never" => SyncRetryDispositionDto::Never,
        "after_user_action" => SyncRetryDispositionDto::AfterUserAction,
        "transient" => SyncRetryDispositionDto::Transient,
        _ => {
            return Err(EngineError::from(boundary_err(
                "sync_ffi_retry_disposition_invalid",
                "retry disposition must be never|after_user_action|transient",
            )));
        }
    };
    Ok(SyncRetryHintDto {
        disposition,
        // Host scheduler owns concrete delay policy; dark slice only maps disposition.
        retry_after_millis: None,
    })
}

/// Inspects one dark host plan/readiness cycle from durable `.lomo/sync/v1` (conversion only).
///
/// Maps `lomo-sync::inspect_sync_cycle_plan` into a coarse DTO. Does **not** re-implement the
/// planner; does not publish/apply remote work; does not journal secrets.
///
/// # Errors
///
/// Validation when the workspace root is empty/oversize or durable session is missing; storage /
/// corruption for unreadable durable state; owner planner boundary errors.
#[export]
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI free-function boundary requires owned String wire types"
)]
pub fn sync_inspect_cycle_plan(
    workspace_root: String,
) -> Result<SyncCyclePlanSummaryDto, EngineError> {
    if workspace_root.is_empty() || workspace_root.len() > 4096 {
        return Err(EngineError::from(boundary_err(
            "sync_ffi_workspace_root_invalid",
            "workspace_root must be 1..=4096 bytes",
        )));
    }
    let paths = SyncPaths::for_workspace(std::path::Path::new(&workspace_root));
    let summary = inspect_sync_cycle_plan(&paths).map_err(EngineError::from)?;
    Ok(cycle_summary_to_dto(summary))
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
        open_conflict_paths: summary.open_conflict_paths,
        conflict_revision: summary.conflict_revision,
        retry_disposition: summary.retry_disposition.to_owned(),
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

/// Runs one **production-shaped** owner cycle via `lomo-sync` composition.
///
/// Conversion only: resolves an optional process-local secret lease (material never journals),
/// builds non-secret [`SyncBackendConfig`], opens real store local snapshot + protocol remote port
/// (or hermetic fake remote for host proof), then returns the owner disposition summary.
///
/// Git: constructs `lomo-git` at this edge (app-private bare mirror under `.lomo/sync/v1/git-mirror`)
/// and calls [`run_composed_sync_cycle_with_remote_port`] so `lomo-sync` stays free of `git2`.
///
/// Does **not** re-implement planner rules. Empty-port inspect remains available as
/// [`sync_inspect_cycle_plan`] for readiness; production work units must call this free-function.
///
/// # Errors
///
/// Validation for blank/oversize workspace, invalid backend kind, incomplete config, missing/expired
/// lease when required; store open / adapter / planner boundary errors from the owner.
#[export]
#[expect(
    clippy::needless_pass_by_value,
    clippy::too_many_arguments,
    reason = "BoltFFI free-function boundary requires owned String wire types"
)]
pub fn sync_run_cycle(
    workspace_root: String,
    backend_kind: String,
    endpoint_url: String,
    username_or_access_key: String,
    bucket: String,
    prefix: String,
    region: String,
    remote_dataset_id: String,
    secret_lease_id: String,
    apply_remote: bool,
) -> Result<SyncCyclePlanSummaryDto, EngineError> {
    if workspace_root.is_empty() || workspace_root.len() > 4096 {
        return Err(EngineError::from(boundary_err(
            "sync_ffi_workspace_root_invalid",
            "workspace_root must be 1..=4096 bytes",
        )));
    }
    let kind = parse_backend_kind(&backend_kind)?;
    let config = SyncBackendConfig {
        kind,
        endpoint_url,
        username_or_access_key,
        bucket,
        prefix,
        region,
        remote_dataset_id,
    };

    // Resolve secret material inside native only — never return plaintext on the wire.
    let secret_owned: Option<Vec<u8>> = {
        let trimmed = secret_lease_id.trim();
        if trimmed.is_empty() {
            None
        } else {
            let id = SecretLeaseId::parse(trimmed).map_err(EngineError::from)?;
            let material = process_secret_vault()
                .resolve(&id)
                .map_err(EngineError::from)?;
            Some(material.as_bytes().to_vec())
        }
    };
    let secret_ref = secret_owned.as_deref();

    let summary = if matches!(kind, SyncBackendKind::Git) {
        run_composed_git_cycle(
            std::path::Path::new(&workspace_root),
            &config,
            secret_ref,
            apply_remote,
        )
        .map_err(EngineError::from)?
    } else {
        run_composed_sync_cycle(
            std::path::Path::new(&workspace_root),
            &config,
            secret_ref,
            apply_remote,
        )
        .map_err(EngineError::from)?
    };
    Ok(cycle_summary_to_dto(summary))
}

/// Composes store local + `lomo-git` remote and runs the owner cycle.
///
/// Wire field reuse (Git):
/// - `endpoint_url` = remote URL
/// - `username_or_access_key` = HTTPS username (default `git` when token present)
/// - `bucket` = branch short name (default `main`)
/// - `prefix` = author name (default `Lomo`)
/// - `region` = author email (default `git@lomo.local`)
/// - secret lease = token (may be empty for local bare remotes)
fn run_composed_git_cycle(
    workspace_root: &std::path::Path,
    config: &SyncBackendConfig,
    secret_material: Option<&[u8]>,
    apply_remote: bool,
) -> Result<SyncCyclePlanSummary, LomoError> {
    if config.endpoint_url.trim().is_empty() {
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
    let username = if config.username_or_access_key.trim().is_empty() {
        if token.is_empty() {
            String::new()
        } else {
            // GitHub/GitLab PAT convention when UI only stores a token.
            "git".to_owned()
        }
    } else {
        config.username_or_access_key.trim().to_owned()
    };
    let branch = if config.bucket.trim().is_empty() {
        "main"
    } else {
        config.bucket.trim()
    };
    let author_name = if config.prefix.trim().is_empty() {
        "Lomo"
    } else {
        config.prefix.trim()
    };
    let author_email = if config.region.trim().is_empty() {
        "git@lomo.local"
    } else {
        config.region.trim()
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
    let remote = lomo_git::connect_workspace_git(
        config.endpoint_url.trim(),
        branch,
        mirror_dir,
        &username,
        &token,
        objects,
        author_name,
        author_email,
        Duration::from_secs(30),
    )?;
    run_composed_sync_cycle_with_remote_port(workspace_root, config, &remote, apply_remote)
}

/// Crate-visible helper for host contract tests: whether a string looks like a lease id (not secret).
#[must_use]
pub fn looks_like_lease_id(value: &str) -> bool {
    value.starts_with("lease-") && value.len() <= 128
}
