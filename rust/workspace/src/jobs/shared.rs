//! Shared helpers for workspace multi-phase drivers.

use std::path::{Path, PathBuf};
use std::sync::Arc;

use lomo_core::{
    ActionOutcome, DocumentKind, ExpectedFingerprint, JobDriver, JobDriverRegistry, PlatformAction,
    PlatformActionBatch, PlatformActionOutput, PlatformBatchResult, RelativeWorkspacePath,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::limits::{corruption, validation};
use crate::source::SourceFingerprint;
use crate::types::WorkspaceRelativePath;

use super::document::DocumentCommandDriver;
use super::scan::ScanDriver;

/// Driver kind strings registered with the engine.
#[must_use]
pub fn default_workspace_drivers() -> Vec<Arc<dyn JobDriver>> {
    vec![Arc::new(ScanDriver), Arc::new(DocumentCommandDriver)]
}

/// Registry containing scan + document-command drivers.
#[must_use]
pub fn workspace_driver_registry() -> JobDriverRegistry {
    JobDriverRegistry::new(default_workspace_drivers())
}

/// Converts a workspace relative path into a core platform path.
pub(super) fn to_core_path(
    path: &WorkspaceRelativePath,
) -> Result<RelativeWorkspacePath, lomo_core::LomoError> {
    RelativeWorkspacePath::parse(path.as_str())
}

/// Opaque exchange token for one workspace-session/job-scoped artifact.
pub(super) fn exchange_token_for(workspace_id: &str, job_id: &str, label: &str) -> String {
    let scope = hex_sha256(format!("{workspace_id}\0{job_id}").as_bytes());
    // Fixed-width scope prevents collisions between workspace journals that both allocate `job-1`
    // while keeping the protocol identifier below its 128-byte ceiling.
    let scope_prefix = scope.get(..32).unwrap_or(scope.as_str());
    format!("ex.{scope_prefix}.{label}")
}

pub(super) fn exchange_path(root: &Path, token: &str) -> PathBuf {
    root.join(token)
}

pub(super) fn write_exchange_bytes(
    root: &Path,
    token: &str,
    bytes: &[u8],
) -> Result<(u64, String), lomo_core::LomoError> {
    let _validated_token = lomo_core::ExchangeToken::parse(token)?;
    let path = exchange_path(root, token);
    let pending = exchange_path(root, &format!("{token}.pending"));
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|error| {
            exchange_storage_error(&format!("exchange parent cannot be created: {error}"))
        })?;
    }
    remove_pending_artifact(&pending)?;
    if let Err(error) = std::fs::write(&pending, bytes) {
        let cleanup = cleanup_pending_diagnostic(&pending);
        return Err(exchange_storage_error(&format!(
            "exchange pending artifact cannot be written: {error}; {cleanup}"
        )));
    }
    if let Err(error) = std::fs::rename(&pending, &path) {
        let cleanup = cleanup_pending_diagnostic(&pending);
        return Err(exchange_storage_error(&format!(
            "exchange artifact cannot be published atomically: {error}; {cleanup}"
        )));
    }
    let digest = hex_sha256(bytes);
    Ok((bytes.len() as u64, digest))
}

fn remove_pending_artifact(path: &Path) -> Result<(), lomo_core::LomoError> {
    match std::fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(exchange_storage_error(&format!(
            "stale exchange pending artifact cannot be removed: {error}"
        ))),
    }
}

fn cleanup_pending_diagnostic(path: &Path) -> String {
    match std::fs::remove_file(path) {
        Ok(()) => "pending artifact removed".to_owned(),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            "pending artifact was not created".to_owned()
        }
        Err(error) => format!("pending artifact cleanup failed: {error}"),
    }
}

fn exchange_storage_error(diagnostic: &str) -> lomo_core::LomoError {
    match lomo_core::LomoError::from_platform_boundary(
        lomo_core::ErrorCategory::Storage,
        "exchange_write_failed",
        lomo_core::RetryDisposition::AfterUserAction,
        None,
        None,
        diagnostic,
    ) {
        Ok(error) | Err(error) => error,
    }
}

pub(super) fn read_exchange_bytes(
    root: &Path,
    token: &str,
) -> Result<Vec<u8>, lomo_core::LomoError> {
    let path = exchange_path(root, token);
    std::fs::read(&path).map_err(|error| {
        lomo_core::LomoError::from_platform_boundary(
            lomo_core::ErrorCategory::Storage,
            "exchange_read_failed",
            lomo_core::RetryDisposition::AfterUserAction,
            None,
            None,
            &format!("exchange artifact cannot be read: {error}"),
        )
        .unwrap_or_else(|e| e)
    })
}

pub(super) fn hex_sha256(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}

pub(super) fn source_fingerprint_of(bytes: &[u8]) -> SourceFingerprint {
    SourceFingerprint::of_bytes(bytes)
}

/// Filename stem for Lomo identity (`2024-01-01.md` → `2024-01-01`).
pub(super) fn filename_stem(path: &str) -> Result<String, lomo_core::LomoError> {
    let name = path
        .rsplit('/')
        .next()
        .filter(|segment| !segment.is_empty())
        .ok_or_else(|| validation("invalid_workspace_path", "path must include a file name"))?;
    let stem = name.strip_suffix(".md").unwrap_or(name);
    if stem.is_empty() {
        return Err(validation(
            "invalid_filename_stem",
            "markdown filename stem must be non-empty",
        ));
    }
    Ok(stem.to_owned())
}

pub(super) fn is_markdown_file(path: &str) -> bool {
    path.as_bytes()
        .windows(3)
        .any(|window| window.eq_ignore_ascii_case(b".md"))
        && path.rsplit('/').next().is_some_and(|name| {
            name.len() > 3
                && name
                    .as_bytes()
                    .get(name.len() - 3..)
                    .is_some_and(|suffix| suffix.eq_ignore_ascii_case(b".md"))
        })
}

pub(super) fn first_applied_output<'a>(
    batch: &'a PlatformActionBatch,
    result: &'a PlatformBatchResult,
    index: usize,
) -> Result<&'a PlatformActionOutput, lomo_core::LomoError> {
    let action = batch.actions().get(index).ok_or_else(|| {
        corruption(
            "job_batch_index_missing",
            "driver expected a platform action that is missing from the batch",
        )
    })?;
    let action_result = result.action_results().get(index).ok_or_else(|| {
        corruption(
            "job_result_index_missing",
            "driver expected a platform result that is missing from the batch result",
        )
    })?;
    if action_result.action_id() != action.id() {
        return Err(corruption(
            "job_result_action_mismatch",
            "platform result action id does not match the planned batch",
        ));
    }
    match action_result.outcome() {
        ActionOutcome::Applied(output) | ActionOutcome::AlreadySatisfied(output) => Ok(output),
        ActionOutcome::Failed(error) => Err(error.clone()),
    }
}

pub(super) fn listed_page(
    output: &PlatformActionOutput,
) -> Result<&lomo_core::MetadataPage, lomo_core::LomoError> {
    match output {
        PlatformActionOutput::Listed { page } => Ok(page),
        PlatformActionOutput::Stat { .. }
        | PlatformActionOutput::DirectoryReady { .. }
        | PlatformActionOutput::ReadToExchange { .. }
        | PlatformActionOutput::WriteComplete { .. }
        | PlatformActionOutput::MoveComplete { .. }
        | PlatformActionOutput::DeleteComplete { .. } => Err(corruption(
            "expected_listed_output",
            "scan driver expected a Listed platform output",
        )),
    }
}

pub(super) fn read_to_exchange_output(
    output: &PlatformActionOutput,
) -> Result<(&lomo_core::DocumentMetadata, &lomo_core::ExchangeArtifact), lomo_core::LomoError> {
    match output {
        PlatformActionOutput::ReadToExchange {
            source_metadata,
            artifact,
        } => Ok((source_metadata, artifact)),
        PlatformActionOutput::Stat { .. }
        | PlatformActionOutput::Listed { .. }
        | PlatformActionOutput::DirectoryReady { .. }
        | PlatformActionOutput::WriteComplete { .. }
        | PlatformActionOutput::MoveComplete { .. }
        | PlatformActionOutput::DeleteComplete { .. } => Err(corruption(
            "expected_read_to_exchange_output",
            "document driver expected a ReadToExchange platform output",
        )),
    }
}

pub(super) fn write_complete_output(
    output: &PlatformActionOutput,
) -> Result<&lomo_core::DocumentMetadata, lomo_core::LomoError> {
    match output {
        PlatformActionOutput::WriteComplete { metadata } => Ok(metadata),
        PlatformActionOutput::Stat { .. }
        | PlatformActionOutput::Listed { .. }
        | PlatformActionOutput::DirectoryReady { .. }
        | PlatformActionOutput::ReadToExchange { .. }
        | PlatformActionOutput::MoveComplete { .. }
        | PlatformActionOutput::DeleteComplete { .. } => Err(corruption(
            "expected_write_complete_output",
            "document driver expected a WriteComplete platform output",
        )),
    }
}

/// Opaque durable cursor for workspace scan pagination (Rust-owned JSON, not for Kotlin parse).
#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
pub(super) struct ScanCursorV2 {
    pub v: u32,
    pub root_path: Option<String>,
    pub list_cursor: Option<String>,
    pub pending_paths: Vec<String>,
    pub pending_index: usize,
    pub current_file: Option<FileMemoCursor>,
    pub emitted: u64,
}

impl ScanCursorV2 {
    pub(super) const VERSION: u32 = 2;

    pub(super) fn encode(&self) -> Result<String, lomo_core::LomoError> {
        serde_json::to_string(self).map_err(|_error| {
            corruption(
                "scan_cursor_encode_failed",
                "workspace scan cursor cannot be serialized",
            )
        })
    }

    pub(super) fn decode(raw: &str) -> Result<Self, lomo_core::LomoError> {
        let cursor: Self = serde_json::from_str(raw).map_err(|_error| {
            validation(
                "invalid_workspace_scan_cursor",
                "workspace scan cursor is not a valid opaque cursor",
            )
        })?;
        if cursor.v != Self::VERSION {
            return Err(validation(
                "unknown_workspace_scan_cursor",
                "workspace scan cursor schema is unknown",
            ));
        }
        if cursor.pending_index > cursor.pending_paths.len() {
            return Err(validation(
                "invalid_workspace_scan_cursor",
                "workspace scan cursor pending index is outside the listed file page",
            ));
        }
        if let Some(path) = cursor.root_path.as_deref() {
            let _validated = WorkspaceRelativePath::parse(path)?;
        }
        for path in &cursor.pending_paths {
            let _validated = WorkspaceRelativePath::parse(path)?;
        }
        if let Some(file) = &cursor.current_file {
            let _validated_path = WorkspaceRelativePath::parse(&file.path)?;
            let _validated_fingerprint = SourceFingerprint::parse(&file.source_fingerprint)?;
            if file.next_memo_index == 0 {
                return Err(validation(
                    "invalid_workspace_scan_cursor",
                    "workspace scan cursor file offset must follow an emitted memo",
                ));
            }
        }
        Ok(cursor)
    }
}

/// Exact resume point inside one stable source revision.
#[derive(Clone, Debug, Serialize, Deserialize, Eq, PartialEq)]
pub(super) struct FileMemoCursor {
    pub path: String,
    pub source_fingerprint: String,
    pub next_memo_index: usize,
}

pub(super) fn is_file_metadata(metadata: &lomo_core::DocumentMetadata) -> bool {
    metadata.kind() == DocumentKind::File
}

pub(super) fn plan_read(
    action_id: lomo_core::ActionId,
    capability: lomo_core::CapabilityToken,
    path: RelativeWorkspacePath,
    exchange_token: &str,
    expected: ExpectedFingerprint,
) -> Result<PlatformAction, lomo_core::LomoError> {
    PlatformAction::read_to_exchange(action_id, capability, path, exchange_token, expected)
}
