//! User-delete gates, tombstone-first recovery, and secret-free diagnostics (P5-08).
//!
//! Normal user deletes require: not first-takeover, baseline established, delete fact under the
//! current generation/dataset fence, matching remote/local tokens, durable tombstone **before**
//! conditional remote delete + verify. Partial listing never authorizes `EnsureAbsent`. Crash
//! between tombstone and delete leaves a durable tombstone so recovery can re-issue delete
//! without silent overwrite.

use std::fs;
use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::durable::{
    BaselineHead, SessionKind, SyncIdentityFence, SyncPaths, TombstoneSet, read_tombstones,
    write_tombstones,
};
use crate::error::{storage, validation};
use crate::pipeline::{
    ContentDigest, PreparedRemoteBatch, ProviderNeutralIntent, SnapshotCompleteness, SyncPath,
};
use lomo_core::LomoError;

/// Inputs for user-delete gate evaluation (avoids a multi-bool bag struct).
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct UserDeleteContext<'a> {
    pub session_kind: SessionKind,
    pub remote_completeness: SnapshotCompleteness,
    pub fence: &'a SyncIdentityFence,
    pub baseline: &'a BaselineHead,
    pub path: &'a SyncPath,
    pub local_has_path: bool,
    pub observed_remote_token: Option<&'a str>,
}

/// Result of evaluating hard gates for a normal user delete.
///
/// Gate bits are independent product preconditions (not a latent state machine); the structured
/// reject codes in [`UserDeleteGate::reject_code`] keep each bit observable for contracts.
#[derive(Clone, Debug, Eq, PartialEq)]
#[expect(
    clippy::struct_excessive_bools,
    reason = "independent delete preconditions; reject_code maps each bit to a stable product code"
)]
pub struct UserDeleteGate {
    pub session_kind: SessionKind,
    pub remote_completeness: SnapshotCompleteness,
    pub baseline_established: bool,
    pub path_in_baseline: bool,
    pub remote_token_matches: bool,
    pub local_absent: bool,
}

impl UserDeleteGate {
    /// Returns true when every hard gate for a normal user delete holds.
    #[must_use]
    pub const fn allows_delete(&self) -> bool {
        self.session_kind.may_emit_user_file_delete()
            && matches!(self.remote_completeness, SnapshotCompleteness::Complete)
            && self.baseline_established
            && self.path_in_baseline
            && self.remote_token_matches
            && self.local_absent
    }

    /// Structured reject code for contracts (`None` when allowed).
    #[must_use]
    pub const fn reject_code(&self) -> Option<&'static str> {
        if self.session_kind.is_migration_or_takeover_class() {
            return Some("user_delete_first_takeover");
        }
        if !matches!(self.remote_completeness, SnapshotCompleteness::Complete) {
            return Some("user_delete_partial_listing");
        }
        if !self.baseline_established {
            return Some("user_delete_baseline_incomplete");
        }
        if !self.path_in_baseline {
            return Some("user_delete_path_not_in_baseline");
        }
        if !self.remote_token_matches {
            return Some("user_delete_token_mismatch");
        }
        if !self.local_absent {
            return Some("user_delete_local_still_present");
        }
        None
    }
}

/// Builds a delete gate from durable baseline + session facts for one path.
///
/// # Errors
///
/// Validation when path is invalid (currently always succeeds for a parsed [`SyncPath`]).
pub fn user_delete_gate_for_path(ctx: &UserDeleteContext<'_>) -> Result<UserDeleteGate, LomoError> {
    let baseline_entry = ctx.baseline.get(ctx.path.as_str());
    let path_in_baseline = baseline_entry.is_some();
    let remote_token_matches = match (baseline_entry, ctx.observed_remote_token) {
        (Some(entry), Some(token)) => entry.remote_token == token,
        (Some(_), None) | (None, _) => false,
    };
    Ok(UserDeleteGate {
        session_kind: ctx.session_kind,
        remote_completeness: ctx.remote_completeness,
        baseline_established: ctx.baseline.is_established(),
        path_in_baseline,
        remote_token_matches,
        local_absent: !ctx.local_has_path,
    })
}

/// Request for tombstone-first user delete (grouped to keep argument count bounded).
#[derive(Clone, Debug)]
pub struct UserDeleteRequest<'a> {
    pub paths: &'a SyncPaths,
    pub fence: &'a SyncIdentityFence,
    pub baseline: &'a BaselineHead,
    pub session_kind: SessionKind,
    pub remote_completeness: SnapshotCompleteness,
    pub path: &'a SyncPath,
    pub local_has_path: bool,
    pub observed_remote_token: Option<&'a str>,
    pub content_digest: &'a ContentDigest,
}

/// Durable-first tombstone then returns the `EnsureAbsent` intent when gates pass.
///
/// Crash safety: tombstone is written **before** the intent is returned so a crash between
/// tombstone and remote delete leaves a recoverable permanent delete fact.
///
/// # Errors
///
/// Validation when gates fail; storage when tombstone write fails.
pub fn record_user_delete_tombstone_first(
    request: &UserDeleteRequest<'_>,
) -> Result<ProviderNeutralIntent, LomoError> {
    let ctx = UserDeleteContext {
        session_kind: request.session_kind,
        remote_completeness: request.remote_completeness,
        fence: request.fence,
        baseline: request.baseline,
        path: request.path,
        local_has_path: request.local_has_path,
        observed_remote_token: request.observed_remote_token,
    };
    let gate = user_delete_gate_for_path(&ctx)?;
    if let Some(code) = gate.reject_code() {
        return Err(validation(
            code,
            "user delete refused: hard gates not satisfied",
        ));
    }
    let token = request
        .baseline
        .get(request.path.as_str())
        .map(|entry| entry.remote_token.clone())
        .ok_or_else(|| {
            validation(
                "user_delete_path_not_in_baseline",
                "user delete requires baseline token",
            )
        })?;

    let mut tombstones = read_tombstones(request.paths)?;
    tombstones.upsert(
        request.path.as_str(),
        &request.fence.remote_dataset_id,
        request.content_digest.as_str(),
    );
    write_tombstones(request.paths, &tombstones)?;

    Ok(ProviderNeutralIntent::EnsureAbsent {
        path: request.path.clone(),
        expected_remote_token: token,
    })
}

/// Request for crash-recovery of a pending delete.
#[derive(Clone, Debug)]
pub struct RecoverDeleteRequest<'a> {
    pub fence: &'a SyncIdentityFence,
    pub baseline: &'a BaselineHead,
    pub tombstones: &'a TombstoneSet,
    pub session_kind: SessionKind,
    pub remote_completeness: SnapshotCompleteness,
    pub path: &'a SyncPath,
    pub local_has_path: bool,
    pub remote_token: Option<&'a str>,
    pub remote_digest: Option<&'a ContentDigest>,
}

/// After a crash: if tombstone exists and remote still has the path with matching digest, re-issue
/// `EnsureAbsent` when delete gates still hold; never invent deletes without tombstone + complete
/// listing.
///
/// # Errors
///
/// Validation when recovery is not authorized.
pub fn recover_pending_delete_intent(
    request: &RecoverDeleteRequest<'_>,
) -> Result<Option<ProviderNeutralIntent>, LomoError> {
    let Some(tombstone) = request.tombstones.get(request.path.as_str()) else {
        return Ok(None);
    };
    if tombstone.remote_dataset_id != request.fence.remote_dataset_id {
        return Err(validation(
            "tombstone_dataset_mismatch",
            "tombstone is bound to a different RemoteDatasetId",
        ));
    }
    if let Some(digest) = request.remote_digest
        && tombstone.content_digest == digest.as_str()
    {
        let ctx = UserDeleteContext {
            session_kind: request.session_kind,
            remote_completeness: request.remote_completeness,
            fence: request.fence,
            baseline: request.baseline,
            path: request.path,
            local_has_path: request.local_has_path,
            observed_remote_token: request.remote_token,
        };
        let gate = user_delete_gate_for_path(&ctx)?;
        if gate.allows_delete() {
            let token = request.remote_token.unwrap_or("").to_owned();
            if token.is_empty() {
                return Err(validation(
                    "user_delete_token_mismatch",
                    "recovery delete requires remote token",
                ));
            }
            return Ok(Some(ProviderNeutralIntent::EnsureAbsent {
                path: request.path.clone(),
                expected_remote_token: token,
            }));
        }
        if matches!(
            request.remote_completeness,
            SnapshotCompleteness::Incomplete
        ) {
            return Ok(None);
        }
    }
    Ok(None)
}

/// Delete-vs-edit: remote gone while local still has different bytes than baseline → conflict,
/// never silent `EnsureAbsent` of local work.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum DeleteVersusEdit {
    /// Remote missing, local missing, baseline present → pure delete (when gates pass).
    PureRemoteDelete,
    /// Remote missing, local present and differs from baseline → conflict (local edit wins review).
    LocalEditRemoteDelete,
    /// Remote missing, local matches baseline → remote delete may apply as pull-absent.
    RemoteDeleteLocalUnchanged,
    /// Not a delete-vs-edit situation.
    NotApplicable,
}

/// Classifies delete-vs-edit for one path given presence + digests.
#[must_use]
pub fn classify_delete_versus_edit(
    baseline_digest: Option<&ContentDigest>,
    local_digest: Option<&ContentDigest>,
    remote_present: bool,
) -> DeleteVersusEdit {
    if remote_present {
        return DeleteVersusEdit::NotApplicable;
    }
    let Some(base) = baseline_digest else {
        return DeleteVersusEdit::NotApplicable;
    };
    match local_digest {
        None => DeleteVersusEdit::PureRemoteDelete,
        Some(local) if local.as_str() == base.as_str() => {
            DeleteVersusEdit::RemoteDeleteLocalUnchanged
        }
        Some(_) => DeleteVersusEdit::LocalEditRemoteDelete,
    }
}

/// Plans a single path under delete-vs-edit rules when remote is absent.
///
/// # Errors
///
/// Validation when required digests are missing for a local-edit case.
pub fn plan_delete_versus_edit_intent(
    path: &SyncPath,
    baseline_digest: Option<&ContentDigest>,
    local_digest: Option<&ContentDigest>,
    baseline_remote_token: Option<&str>,
    may_delete: bool,
) -> Result<Option<ProviderNeutralIntent>, LomoError> {
    match classify_delete_versus_edit(baseline_digest, local_digest, false) {
        DeleteVersusEdit::LocalEditRemoteDelete => {
            let local = local_digest.cloned().ok_or_else(|| {
                validation(
                    "delete_vs_edit_local_missing",
                    "local edit remote delete requires local digest",
                )
            })?;
            // Remote body is absent: use baseline digest as the remote-side conflict marker so the
            // durable session still carries three-way digests without inventing empty-file content.
            let remote_marker = baseline_digest.cloned().ok_or_else(|| {
                validation(
                    "delete_vs_edit_baseline_missing",
                    "local edit remote delete requires baseline digest",
                )
            })?;
            Ok(Some(ProviderNeutralIntent::OpenConflict {
                path: path.clone(),
                local_digest: local,
                remote_digest: remote_marker,
                baseline_digest: baseline_digest.cloned(),
            }))
        }
        DeleteVersusEdit::PureRemoteDelete if may_delete => {
            let token = baseline_remote_token.unwrap_or("").to_owned();
            if token.is_empty() {
                return Ok(None);
            }
            Ok(Some(ProviderNeutralIntent::EnsureAbsent {
                path: path.clone(),
                expected_remote_token: token,
            }))
        }
        // Local still matches baseline while remote is gone: do not re-upload over a proven remote
        // delete. Local pull-absent apply is owned by later store/FFI packages.
        // Pure remote delete without may_delete, and non-applicable cases: no intent.
        DeleteVersusEdit::PureRemoteDelete
        | DeleteVersusEdit::RemoteDeleteLocalUnchanged
        | DeleteVersusEdit::NotApplicable => Ok(None),
    }
}

/// Identity reset: old tombstones lose authority for a new dataset id.
#[must_use]
pub fn tombstone_authoritative_for_fence(
    tombstones: &TombstoneSet,
    path: &str,
    fence: &SyncIdentityFence,
) -> bool {
    tombstones
        .get(path)
        .is_some_and(|entry| entry.remote_dataset_id == fence.remote_dataset_id)
}

/// Secret-free diagnostic export record (default export surface).
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct SyncDiagnosticExport {
    pub schema: u32,
    pub session_id: Option<String>,
    pub session_kind: Option<String>,
    pub conflict_revision: Option<u64>,
    pub entries: Vec<SyncDiagnosticEntry>,
    pub errors: Vec<SyncDiagnosticError>,
    pub request_telemetry: Vec<SyncRequestTelemetry>,
}

/// One path diagnostic line (digest/path/status only — no body).
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct SyncDiagnosticEntry {
    pub path: String,
    pub digest: Option<String>,
    pub status: String,
    pub remote_token_present: bool,
}

/// Structured error code without credentials or body text.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct SyncDiagnosticError {
    pub code: String,
    pub category: String,
    pub path: Option<String>,
}

/// Request telemetry counters (no URLs with secrets).
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct SyncRequestTelemetry {
    pub operation: String,
    pub http_status: Option<u16>,
    pub duration_ms: Option<u64>,
    pub bytes_transferred: Option<u64>,
}

const DIAGNOSTIC_FORBIDDEN_MARKERS: &[&str] = &[
    "password",
    "\"token\":\"",
    "authorization",
    "aws_secret",
    "secret_access",
    "private_key",
    "bearer ",
    "://",
    "userinfo",
];

impl SyncDiagnosticExport {
    /// Empty export shell.
    #[must_use]
    pub const fn empty() -> Self {
        Self {
            schema: crate::limits::SYNC_DURABLE_SCHEMA,
            session_id: None,
            session_kind: None,
            conflict_revision: None,
            entries: Vec::new(),
            errors: Vec::new(),
            request_telemetry: Vec::new(),
        }
    }

    /// Serializes to JSON for export. Never includes secret-bearing fields by construction.
    ///
    /// # Errors
    ///
    /// Validation when serialization fails.
    pub fn to_json(&self) -> Result<String, LomoError> {
        serde_json::to_string(self).map_err(|err| {
            validation(
                "diagnostic_encode_failed",
                &format!("cannot serialize diagnostic export: {err}"),
            )
        })
    }

    /// True when the JSON export contains no forbidden secret markers.
    #[must_use]
    pub fn is_secret_free_json(json: &str) -> bool {
        let lower = json.to_ascii_lowercase();
        // Allow the word "token" only as remote_token_present boolean field name — reject values.
        if lower.contains("\"token\":") {
            return false;
        }
        !DIAGNOSTIC_FORBIDDEN_MARKERS
            .iter()
            .any(|needle| lower.contains(needle))
    }
}

/// Builds a default diagnostic export from session / baseline / batch facts (no bodies).
#[must_use]
pub fn build_default_diagnostic_export(
    session_id: Option<&str>,
    session_kind: Option<SessionKind>,
    conflict_revision: Option<u64>,
    baseline: &BaselineHead,
    batch: Option<&PreparedRemoteBatch>,
    errors: &[SyncDiagnosticError],
    telemetry: &[SyncRequestTelemetry],
) -> SyncDiagnosticExport {
    let mut entries: Vec<SyncDiagnosticEntry> = baseline
        .entries
        .iter()
        .map(|entry| SyncDiagnosticEntry {
            path: entry.path.clone(),
            digest: Some(entry.digest.clone()),
            status: "baseline".to_owned(),
            // Do not export the token string — only presence.
            remote_token_present: !entry.remote_token.is_empty(),
        })
        .collect();
    if let Some(batch) = batch {
        for intent in &batch.intents {
            let (path, status, digest) = match intent {
                ProviderNeutralIntent::EnsurePresent { path, digest, .. } => {
                    (path.as_str(), "ensure_present", Some(digest.as_str()))
                }
                ProviderNeutralIntent::EnsureAbsent { path, .. } => {
                    (path.as_str(), "ensure_absent", None)
                }
                ProviderNeutralIntent::PullPresent { path, digest, .. } => {
                    (path.as_str(), "pull_present", Some(digest.as_str()))
                }
                ProviderNeutralIntent::OpenConflict {
                    path, local_digest, ..
                } => (path.as_str(), "open_conflict", Some(local_digest.as_str())),
                ProviderNeutralIntent::ReportUnrecognized { path } => {
                    (path.as_str(), "unrecognized", None)
                }
            };
            entries.push(SyncDiagnosticEntry {
                path: path.to_owned(),
                digest: digest.map(str::to_owned),
                status: status.to_owned(),
                remote_token_present: false,
            });
        }
    }
    SyncDiagnosticExport {
        schema: crate::limits::SYNC_DURABLE_SCHEMA,
        session_id: session_id.map(str::to_owned),
        session_kind: session_kind.map(|kind| match kind {
            SessionKind::FirstTakeover => "first_takeover".to_owned(),
            SessionKind::Migration => "migration".to_owned(),
            SessionKind::Incremental => "incremental".to_owned(),
        }),
        conflict_revision,
        entries,
        errors: errors.to_vec(),
        request_telemetry: telemetry.to_vec(),
    }
}

/// Writes diagnostic export JSON under `.lomo/sync/v1/diagnostics/`.
///
/// # Errors
///
/// Storage / validation.
pub fn write_diagnostic_export(
    paths: &SyncPaths,
    export: &SyncDiagnosticExport,
) -> Result<std::path::PathBuf, LomoError> {
    paths.ensure_layout()?;
    let dir = paths.root.join("diagnostics");
    fs::create_dir_all(&dir).map_err(|err| {
        storage(
            "diagnostic_dir_failed",
            &format!("cannot create diagnostics dir: {err}"),
        )
    })?;
    let json = export.to_json()?;
    if !SyncDiagnosticExport::is_secret_free_json(&json) {
        return Err(validation(
            "diagnostic_secret_leak",
            "diagnostic export refused: secret-like content detected",
        ));
    }
    let out = dir.join("export.json");
    fs::write(&out, json.as_bytes()).map_err(|err| {
        storage(
            "diagnostic_write_failed",
            &format!("cannot write diagnostic export: {err}"),
        )
    })?;
    Ok(out)
}

/// Offline old-device revival: old baseline fence mismatch must reject without clean-slate or delete.
///
/// # Errors
///
/// Validation on fence mismatch.
pub fn assert_fence_for_revival(
    durable_fence: &SyncIdentityFence,
    current: &SyncIdentityFence,
) -> Result<(), LomoError> {
    if durable_fence.workspace_generation != current.workspace_generation
        || durable_fence.remote_dataset_id != current.remote_dataset_id
        || durable_fence.remote_identity_digest != current.remote_identity_digest
    {
        return Err(validation(
            "sync_identity_mismatch",
            "offline revival rejected: durable fence does not match current WorkspaceGenerationId + RemoteDatasetId + RemoteIdentityDigest",
        ));
    }
    Ok(())
}

/// Clears durable sync control tree for identity reset (session/baseline/tombstone/conflict).
/// Never deletes user Markdown/media — only `.lomo/sync/v1` control records.
///
/// # Errors
///
/// Storage when removal fails.
pub fn reset_sync_control_tree(paths: &SyncPaths) -> Result<(), LomoError> {
    for path in [
        &paths.session,
        &paths.baseline,
        &paths.tombstones,
        &paths.conflicts,
    ] {
        if path.exists() {
            fs::remove_file(path).map_err(|err| {
                storage(
                    "sync_control_reset_failed",
                    &format!("cannot remove {}: {err}", path.display()),
                )
            })?;
        }
    }
    // Leave conflict artifacts; they are not user files but may be large — best-effort remove.
    if paths.conflict_artifacts.exists() {
        // behavior-contract: silent-result-ok: artifact tree cleanup is best-effort on identity reset.
        drop(fs::remove_dir_all(&paths.conflict_artifacts));
    }
    Ok(())
}

/// Helper for tests: workspace-relative check that a path is under the sync control root.
#[must_use]
pub fn path_is_sync_control(workspace_root: &Path, candidate: &Path) -> bool {
    let root = SyncPaths::for_workspace(workspace_root).root;
    candidate.starts_with(root)
}
