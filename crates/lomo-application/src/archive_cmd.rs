//! Workspace archive export/import through the store-owned ZIP contract.

use std::{
    ffi::OsString,
    path::{Path, PathBuf},
};

use lomo_core::LomoError;
use lomo_store::{
    ArchiveExportResult, RebuildResult, archive_activate, archive_export, archive_import,
};

use crate::{csprng::generate_hex_token, error::validation, session::WorkspaceSession};

#[derive(Clone, Copy)]
enum ArchiveRoot<'a> {
    Workspace,
    Media(&'a lomo_core::CapabilityToken),
}

impl WorkspaceSession {
    /// Exports Markdown, attachments, and portable `.lomo` facts to a plaintext ZIP.
    ///
    /// The export target is derived from the frozen session environment, never nominated by a
    /// caller: artifacts land in `<exchange_dir>/exports/`, the single export whitelist fixed
    /// when this session was opened. `file_name_hint` contributes only a bare file-name stem
    /// (`[A-Za-z0-9._-]`, `.zip` appended idempotently); `None` mints a collision-free name.
    ///
    /// Source bytes come through bound platform capabilities for both SAF and Direct roots.
    /// Additional selected media roots contribute image, audio and video files under their
    /// relative names, including historical/unreferenced media. Identical duplicates coalesce;
    /// conflicting bytes at the same archive path fail instead of losing one source.
    ///
    /// # Errors
    /// Walk, zip, lock, and `archive_export_name_invalid` failures.
    pub fn export_archive(
        &self,
        file_name_hint: Option<&str>,
        media_capabilities: &[lomo_core::CapabilityToken],
    ) -> Result<ArchiveExportResult, LomoError> {
        let archive_path = self
            .config
            .exchange_dir
            .join("exports")
            .join(export_file_name(file_name_hint)?);
        let _held = self.write_behind.exclusive()?;
        let snapshot = self
            .config
            .exchange_dir
            .join(format!("archive-snapshot-{}", generate_hex_token(8)?));
        std::fs::create_dir_all(&snapshot).map_err(|error| {
            crate::error::storage("archive_snapshot_create_failed", error.to_string())
        })?;
        let result = (|| {
            self.snapshot_archive_root(&snapshot, ArchiveRoot::Workspace)?;
            for capability in media_capabilities {
                self.snapshot_archive_root(&snapshot, ArchiveRoot::Media(capability))?;
            }
            archive_export(&snapshot, &archive_path)
        })();
        let cleanup = std::fs::remove_dir_all(&snapshot).map_err(|error| {
            crate::error::storage("archive_snapshot_cleanup_failed", error.to_string())
        });
        match (result, cleanup) {
            (Ok(exported), Ok(())) => Ok(exported),
            (Err(error), _) | (Ok(_), Err(error)) => Err(error),
        }
    }

    fn snapshot_archive_root(
        &self,
        snapshot: &Path,
        root: ArchiveRoot<'_>,
    ) -> Result<(), LomoError> {
        let capability = match root {
            ArchiveRoot::Workspace => &self.config.capability,
            ArchiveRoot::Media(capability) => capability,
        };
        let mut config = self.config.clone();
        config.capability = capability.clone();
        let evidence = crate::rebuild::list_document_listing(&config, &self.executor)?;
        for metadata in evidence.admitted_listing()? {
            let lomo_core::WorkspaceTarget::Relative(path) = metadata.target() else {
                return Err(validation(
                    "archive_root_entry",
                    "export listing contains a root file",
                ));
            };
            if matches!(root, ArchiveRoot::Media(_))
                && (path.as_str().starts_with(".lomo/")
                    || lomo_workspace::attachment_media_class(path.as_str())
                        == lomo_workspace::MediaClass::File)
            {
                continue;
            }
            let token = format!("ex_{}", generate_hex_token(16)?);
            let action = lomo_core::PlatformAction::read_to_exchange(
                lomo_core::ActionId::parse(&format!("archive-{}", generate_hex_token(16)?))?,
                capability.clone(),
                path.clone(),
                &token,
                lomo_core::ExpectedFingerprint::absent(),
            )?;
            let output = self.io().execute(action)?;
            let lomo_core::PlatformActionOutput::ReadToExchange { artifact, .. } = output else {
                return Err(validation(
                    "archive_read_output_invalid",
                    "export requires a verified streaming read",
                ));
            };
            let source = self.config.exchange_dir.join(artifact.token().as_str());
            let target = snapshot.join(path.as_str());
            if target.exists() {
                let (existing, _) = lomo_media::ContentDigest::stream_from_path(&target)?;
                let (incoming, _) = lomo_media::ContentDigest::stream_from_path(&source)?;
                std::fs::remove_file(&source).map_err(|error| {
                    crate::error::storage("archive_snapshot_cleanup_failed", error.to_string())
                })?;
                if existing != incoming {
                    return Err(crate::error::conflict(
                        "archive_media_path_collision",
                        format!("different media share archive path {}", path.as_str()),
                    ));
                }
                continue;
            }
            if let Some(parent) = target.parent() {
                std::fs::create_dir_all(parent).map_err(|error| {
                    crate::error::storage("archive_snapshot_create_failed", error.to_string())
                })?;
            }
            std::fs::rename(source, target).map_err(|error| {
                crate::error::storage("archive_snapshot_write_failed", error.to_string())
            })?;
        }
        let after = crate::rebuild::list_document_listing(&config, &self.executor)?;
        if evidence.listing_digest() != after.listing_digest() {
            return Err(crate::error::conflict(
                "archive_source_changed",
                "workspace changed during export",
            ));
        }
        Ok(())
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
        let _held = self.write_behind.exclusive()?;
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
