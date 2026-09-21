//! Workspace archive export/import through the store-owned ZIP contract.

use std::{
    ffi::OsString,
    path::{Path, PathBuf},
};

use lomo_core::LomoError;
use lomo_store::{
    ArchiveExportResult, RebuildResult, archive_activate, archive_export, archive_import,
};

use crate::{error::validation, lock::TransactionLock, session::WorkspaceSession};

impl WorkspaceSession {
    /// Exports Markdown, attachments, and portable `.lomo` facts to a plaintext ZIP.
    ///
    /// `workspace_root` is the composition-root binding for this session's notes capability.
    ///
    /// # Errors
    /// Walk, zip, and lock failures.
    pub fn export_archive(
        &self,
        workspace_root: &Path,
        archive_path: &Path,
    ) -> Result<ArchiveExportResult, LomoError> {
        let _lock = TransactionLock::acquire(&self.config.runtime_dir)?;
        archive_export(workspace_root, archive_path)
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
        self.rebuild_locked(false)
    }
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
