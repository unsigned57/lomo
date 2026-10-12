//! Workspace archive export/import through the store-owned ZIP contract.

use std::{
    ffi::OsString,
    path::{Path, PathBuf},
};

use lomo_core::LomoError;
use lomo_store::{
    ArchiveExportResult, RebuildResult, archive_activate, archive_export, archive_import,
};

use crate::{
    csprng::generate_hex_token, error::validation, lock::TransactionLock, session::WorkspaceSession,
};

impl WorkspaceSession {
    /// Exports Markdown, attachments, and portable `.lomo` facts to a plaintext ZIP.
    ///
    /// The export target is derived from the frozen session environment, never nominated by a
    /// caller: artifacts land in `<exchange_dir>/exports/`, the single export whitelist fixed
    /// when this session was opened. `file_name_hint` contributes only a bare file-name stem
    /// (`[A-Za-z0-9._-]`, `.zip` appended idempotently); `None` mints a collision-free name.
    ///
    /// `workspace_root` is the composition-root binding for this session's notes capability.
    ///
    /// # Errors
    /// Walk, zip, lock, and `archive_export_name_invalid` failures.
    pub fn export_archive(
        &self,
        workspace_root: &Path,
        file_name_hint: Option<&str>,
    ) -> Result<ArchiveExportResult, LomoError> {
        let archive_path = self
            .config
            .exchange_dir
            .join("exports")
            .join(export_file_name(file_name_hint)?);
        let _lock = TransactionLock::acquire(&self.config.runtime_dir)?;
        archive_export(workspace_root, &archive_path)
    }

    /// Imports an archive over the live workspace, then rebuilds the private projection.
    ///
    /// Failed inspect/import leaves the previous generation in place. Activate follows the
    /// store-owned rename/restore rules; the session projection is rebuilt only after a successful
    /// generation switch.
    ///
    /// # Errors
    /// Zip-slip, checksum, I/O, activate, and rebuild failures.
    pub fn import_archive(
        &self,
        workspace_root: &Path,
        archive_path: &Path,
        staging_root: &Path,
    ) -> Result<RebuildResult, LomoError> {
        let _lock = TransactionLock::acquire(&self.config.runtime_dir)?;
        archive_import(archive_path, staging_root)?;
        archive_activate(
            staging_root,
            workspace_root,
            &previous_generation_root(staging_root)?,
        )?;
        // An archive may carry a pre-migration `.lomo` tree; normalize the activated root to V2
        // before the projection consumes durable records.
        lomo_workspace::migrate_history_state_v1_to_v2(workspace_root)?;
        self.rebuild_locked(false)
    }
}

/// Resolves the artifact file name for one export under the session export root.
///
/// The whitelist of export directories is decided once at session-env construction; this
/// function is the only place a hint may contribute, and it may contribute nothing but the
/// stem of one file name. A supplied `.zip` suffix is stripped so the suffix law is
/// idempotent; any hint that is not a single portable file-name stem is rejected.
fn export_file_name(file_name_hint: Option<&str>) -> Result<String, LomoError> {
    let Some(raw) = file_name_hint else {
        return Ok(format!("lomo-archive-{}.zip", generate_hex_token(8)?));
    };
    let stem = raw.strip_suffix(".zip").unwrap_or(raw);
    let mut bytes = stem.bytes();
    let valid = !stem.is_empty()
        && stem.len() <= 96
        && bytes
            .next()
            .is_some_and(|first| first.is_ascii_alphanumeric())
        && stem
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'_'));
    if !valid {
        return Err(validation(
            "archive_export_name_invalid",
            "archive file name hint must be a single file name stem of [A-Za-z0-9._-] \
             starting with a letter or digit",
        ));
    }
    Ok(format!("{stem}.zip"))
}

fn previous_generation_root(staging_root: &Path) -> Result<PathBuf, LomoError> {
    let parent = staging_root.parent().ok_or_else(|| {
        validation(
            "archive_backup_path_invalid",
            "archive staging root must have a parent directory",
        )
    })?;
    let name = staging_root.file_name().ok_or_else(|| {
        validation(
            "archive_backup_path_invalid",
            "archive staging root must have a file name",
        )
    })?;
    let mut backup_name = OsString::from(name);
    backup_name.push(".previous");
    Ok(parent.join(backup_name))
}
