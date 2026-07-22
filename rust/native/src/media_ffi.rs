//! Stage-4 dark-build media + archive FFI conversion surface (P4-09).
//!
//! Path-only commands: stage/finalize/promote/manifest/orphan + archive
//! export/inspect/import/activate. No full media-byte FFI. Business rules stay in
//! `lomo-media` / `lomo-store`. Not wired into production Kotlin DI.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use boltffi::data;
use lomo_core::LomoError;
use lomo_media::{
    self as media, AttachmentRef, ContentDigest, MediaMime, MediaRelativePath, MediaSource,
    MediaStaged, PromotePlan, ReferenceSource, STAGE_DIR_NAME, allocate_recording_target,
    finalize_recording, promote_staged, stage_media, suggest_human_relative_path, sweep_orphans,
    wall_clock_ms,
};
use lomo_store::{
    archive_activate, archive_export, archive_import, archive_import_activate_rebuild,
    archive_inspect,
};

use crate::EngineError;
use crate::store_ffi::StoreRebuildResult;

fn boundary_err(code: &str, diagnostic: &str) -> LomoError {
    match LomoError::from_platform_boundary(
        lomo_core::ErrorCategory::Validation,
        code,
        lomo_core::RetryDisposition::Never,
        None,
        None,
        diagnostic,
    ) {
        Ok(error) | Err(error) => error,
    }
}

/// How the host supplied media bytes on disk (path only).
#[data]
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum MediaSourceKind {
    #[default]
    DirectPath,
    StagedTemp,
}

/// Staged media facts returned to the host (paths + digest wire forms).
#[data]
#[derive(Clone, Debug, Default)]
pub struct MediaStagedDto {
    pub digest: String,
    pub size: u64,
    pub mime: String,
    pub staging_path: String,
    pub human_name_hint: String,
    /// Owner-suggested final relative path (`media/...`); hosts must not invent digests basenames.
    pub suggested_final_relative_path: String,
}

/// One planned promote under a memo operation-id (path-only).
#[data]
#[derive(Clone, Debug, Default)]
pub struct MediaPromotePlanDto {
    pub operation_id: String,
    pub staged: MediaStagedDto,
    pub final_relative_path: String,
}

/// Result of `promote_staged`.
#[data]
#[derive(Clone, Debug, Default)]
pub struct MediaPromoteResultDto {
    pub operation_id: String,
    pub digest: String,
    pub mime: String,
    pub size: u64,
    pub final_absolute_path: String,
    pub final_relative_path: String,
}

/// One committed media file for orphan sweep / manifest.
#[data]
#[derive(Clone, Debug, Default)]
pub struct MediaCommittedEntryDto {
    pub digest: String,
    pub absolute_path: String,
}

/// Attachment reference for orphan refcount (path/digest wire).
#[data]
#[derive(Clone, Debug, Default)]
pub struct MediaAttachmentRefDto {
    pub digest: String,
    /// Opaque memo/history owner key (not a media path).
    pub owner_key: String,
    /// `current` | `trash` | `history`
    pub source: String,
}

/// Trash entry for orphan sweep input/output.
#[data]
#[derive(Clone, Debug, Default)]
pub struct MediaTrashEntryDto {
    pub digest: String,
    pub trash_path: String,
    pub trashed_at_ms: u64,
    pub expires_at_ms: u64,
}

/// Result of media orphan sweep (paths only).
#[data]
#[derive(Clone, Debug, Default)]
pub struct MediaOrphanSweepResultDto {
    pub moved_to_trash: Vec<MediaTrashEntryDto>,
    pub permanently_deleted_digests: Vec<String>,
    pub kept_live: u64,
}

/// Workspace media manifest snapshot (path + digest listing).
#[data]
#[derive(Clone, Debug, Default)]
pub struct MediaManifestDto {
    pub stage_dir_name: String,
    pub entries: Vec<MediaCommittedEntryDto>,
}

/// Archive export result (path + schema).
#[data]
#[derive(Clone, Debug, Default)]
pub struct ArchiveExportResultDto {
    pub archive_path: String,
    pub schema_version: u32,
    pub entry_count: u64,
}

/// Archive inspect/import staging result.
#[data]
#[derive(Clone, Debug, Default)]
pub struct ArchiveInspectResultDto {
    pub staging_root: String,
    pub schema_version: u32,
    pub entry_count: u64,
}

fn staged_to_dto(staged: MediaStaged) -> MediaStagedDto {
    MediaStagedDto {
        digest: staged.digest.as_str().to_owned(),
        size: staged.size,
        mime: staged.mime.as_str().to_owned(),
        staging_path: staged.staging_path.to_string_lossy().into_owned(),
        human_name_hint: staged.human_name_hint,
        suggested_final_relative_path: staged.suggested_final_relative_path,
    }
}

fn staged_from_dto(dto: &MediaStagedDto) -> Result<MediaStaged, EngineError> {
    let digest = ContentDigest::parse(&dto.digest).map_err(EngineError::from)?;
    let mime = MediaMime::parse(&dto.mime).map_err(EngineError::from)?;
    let suggested = if dto.suggested_final_relative_path.is_empty() {
        // Recovery of older hosts: re-derive from mime + hint under owner policy.
        suggest_human_relative_path(
            Path::new(&dto.human_name_hint)
                .file_stem()
                .and_then(|s| s.to_str())
                .unwrap_or(&dto.human_name_hint),
            mime,
        )
        .map_err(EngineError::from)?
        .as_str()
        .to_owned()
    } else {
        // Validate host-supplied path still obeys media relative-path law.
        MediaRelativePath::parse(&dto.suggested_final_relative_path)
            .map_err(EngineError::from)?
            .as_str()
            .to_owned()
    };
    Ok(MediaStaged {
        digest,
        size: dto.size,
        mime,
        staging_path: PathBuf::from(&dto.staging_path),
        human_name_hint: dto.human_name_hint.clone(),
        suggested_final_relative_path: suggested,
    })
}

fn promote_plan_from_dto(dto: &MediaPromotePlanDto) -> Result<PromotePlan, EngineError> {
    let staged = staged_from_dto(&dto.staged)?;
    let final_relative_path =
        MediaRelativePath::parse(&dto.final_relative_path).map_err(EngineError::from)?;
    Ok(PromotePlan {
        operation_id: dto.operation_id.clone(),
        staged,
        final_relative_path,
    })
}

fn reference_source_from_wire(raw: &str) -> Result<ReferenceSource, EngineError> {
    match raw {
        "current" => Ok(ReferenceSource::CurrentMemo),
        "trash" => Ok(ReferenceSource::TrashMemo),
        "history" => Ok(ReferenceSource::HistoryVersion),
        _ => Err(EngineError::from(boundary_err(
            "invalid_media_reference_source",
            "attachment reference source must be current|trash|history",
        ))),
    }
}

/// Stages media from a host path into the media stage directory (path-only).
///
/// # Errors
///
/// Media validation/storage errors.
pub fn ffi_stage_media(
    media_root: &str,
    source_kind: MediaSourceKind,
    source_path: &str,
    human_name_hint: &str,
) -> Result<MediaStagedDto, EngineError> {
    let root = PathBuf::from(media_root);
    let path = PathBuf::from(source_path);
    let source = match source_kind {
        MediaSourceKind::DirectPath => MediaSource::DirectPath { path },
        MediaSourceKind::StagedTemp => MediaSource::StagedTemp { path },
    };
    let staged = stage_media(&root, source, human_name_hint).map_err(EngineError::from)?;
    Ok(staged_to_dto(staged))
}

/// Allocates a recording target under the stage directory (path-only).
///
/// # Errors
///
/// Media validation/storage errors.
pub fn ffi_allocate_recording_target(
    media_root: &str,
    extension: &str,
) -> Result<String, EngineError> {
    let path =
        allocate_recording_target(Path::new(media_root), extension).map_err(EngineError::from)?;
    Ok(path.to_string_lossy().into_owned())
}

/// Finalizes a recording path into staged media (path-only).
///
/// # Errors
///
/// Media validation/storage errors.
pub fn ffi_finalize_recording(
    media_root: &str,
    recording_path: &str,
    human_name_hint: &str,
) -> Result<MediaStagedDto, EngineError> {
    let staged = finalize_recording(
        Path::new(media_root),
        Path::new(recording_path),
        human_name_hint,
    )
    .map_err(EngineError::from)?;
    Ok(staged_to_dto(staged))
}

/// Promotes one staged item to a final relative path (path-only).
///
/// Prefer routing promote through memo `pending_promotes` for production transactions;
/// this surface exists for dark-build host tests and recovery tooling.
///
/// # Errors
///
/// Media validation/storage errors.
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI boundary owns the promote plan DTO"
)]
pub fn ffi_promote_media(
    workspace_root: &str,
    plan: MediaPromotePlanDto,
) -> Result<MediaPromoteResultDto, EngineError> {
    let inner = promote_plan_from_dto(&plan)?;
    let result = promote_staged(
        Path::new(workspace_root),
        &inner,
        media::PromoteCrashPoint::None,
    )
    .map_err(EngineError::from)?;
    Ok(MediaPromoteResultDto {
        operation_id: result.operation_id,
        digest: result.digest.as_str().to_owned(),
        mime: result.mime.as_str().to_owned(),
        size: result.size,
        final_absolute_path: result.final_absolute_path.to_string_lossy().into_owned(),
        final_relative_path: result.final_relative_path,
    })
}

/// Lists committed media files under `media/` (path + digest wire). No byte bodies.
///
/// # Errors
///
/// Storage errors when walking the media tree fails.
pub fn ffi_query_media_manifest(workspace_root: &str) -> Result<MediaManifestDto, EngineError> {
    let root = Path::new(workspace_root);
    let media_dir = root.join("media");
    let mut entries = Vec::new();
    if media_dir.is_dir() {
        collect_media_files(&media_dir, &mut entries)?;
    }
    Ok(MediaManifestDto {
        stage_dir_name: STAGE_DIR_NAME.to_owned(),
        entries,
    })
}

fn collect_media_files(
    dir: &Path,
    out: &mut Vec<MediaCommittedEntryDto>,
) -> Result<(), EngineError> {
    let read = std::fs::read_dir(dir).map_err(|error| {
        EngineError::from(boundary_err(
            "media_manifest_walk_failed",
            &format!("cannot read media dir: {error}"),
        ))
    })?;
    for entry in read {
        let entry = entry.map_err(|error| {
            EngineError::from(boundary_err(
                "media_manifest_entry_failed",
                &format!("cannot read media entry: {error}"),
            ))
        })?;
        let path = entry.path();
        let file_type = entry.file_type().map_err(|error| {
            EngineError::from(boundary_err(
                "media_manifest_type_failed",
                &format!("cannot stat media entry: {error}"),
            ))
        })?;
        if file_type.is_dir() {
            let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("");
            // Do not walk media-trash or stage dirs as committed live media.
            if name == ".lomo-media-trash"
                || name == ".lomo-media-stage"
                || name == ".lomo-media-delete-intents"
            {
                continue;
            }
            collect_media_files(&path, out)?;
        } else if file_type.is_file() {
            let (digest, _size) =
                ContentDigest::stream_from_path(&path).map_err(EngineError::from)?;
            out.push(MediaCommittedEntryDto {
                digest: digest.as_str().to_owned(),
                absolute_path: path.to_string_lossy().into_owned(),
            });
        }
    }
    Ok(())
}

/// Runs orphan sweep with host-supplied committed map + refs (path-only).
///
/// # Errors
///
/// Media storage/validation errors.
pub fn ffi_media_orphan_sweep(
    media_root: &str,
    committed: Vec<MediaCommittedEntryDto>,
    refs: Vec<MediaAttachmentRefDto>,
    existing_trash: Vec<MediaTrashEntryDto>,
    now_ms: Option<u64>,
    recovery_window_ms: u64,
) -> Result<MediaOrphanSweepResultDto, EngineError> {
    let mut committed_map = BTreeMap::new();
    for entry in committed {
        let digest = ContentDigest::parse(&entry.digest).map_err(EngineError::from)?;
        committed_map.insert(digest, PathBuf::from(entry.absolute_path));
    }
    let mut attachment_refs = Vec::with_capacity(refs.len());
    for r in refs {
        let digest = ContentDigest::parse(&r.digest).map_err(EngineError::from)?;
        let source = reference_source_from_wire(&r.source)?;
        attachment_refs.push(AttachmentRef {
            digest,
            source,
            owner_key: r.owner_key,
        });
    }
    let trash: Vec<_> = existing_trash
        .into_iter()
        .map(|t| {
            ContentDigest::parse(&t.digest).map(|digest| media::MediaTrashEntry {
                digest,
                trash_path: PathBuf::from(t.trash_path),
                trashed_at_ms: t.trashed_at_ms,
                expires_at_ms: t.expires_at_ms,
            })
        })
        .collect::<Result<Vec<_>, _>>()
        .map_err(EngineError::from)?;
    let now = now_ms.unwrap_or_else(wall_clock_ms);
    let result = sweep_orphans(
        Path::new(media_root),
        &committed_map,
        &attachment_refs,
        &trash,
        now,
        recovery_window_ms,
    )
    .map_err(EngineError::from)?;
    Ok(MediaOrphanSweepResultDto {
        moved_to_trash: result
            .moved_to_trash
            .into_iter()
            .map(|e| MediaTrashEntryDto {
                digest: e.digest.as_str().to_owned(),
                trash_path: e.trash_path.to_string_lossy().into_owned(),
                trashed_at_ms: e.trashed_at_ms,
                expires_at_ms: e.expires_at_ms,
            })
            .collect(),
        permanently_deleted_digests: result
            .permanently_deleted
            .into_iter()
            .map(|i| i.digest.as_str().to_owned())
            .collect(),
        kept_live: result.kept_live,
    })
}

/// Exports archive v2 from a workspace root (path-only).
///
/// # Errors
///
/// Archive export errors.
pub fn ffi_archive_export(
    workspace_root: &str,
    archive_path: &str,
) -> Result<ArchiveExportResultDto, EngineError> {
    let result = archive_export(Path::new(workspace_root), Path::new(archive_path))
        .map_err(EngineError::from)?;
    Ok(ArchiveExportResultDto {
        archive_path: result.archive_path.to_string_lossy().into_owned(),
        schema_version: result.manifest.schema_version,
        entry_count: result.manifest.entries.len() as u64,
    })
}

/// Inspects an archive into a fresh staging root (does not touch live).
///
/// # Errors
///
/// Archive inspect errors.
pub fn ffi_archive_inspect(
    archive_path: &str,
    staging_root: &str,
) -> Result<ArchiveInspectResultDto, EngineError> {
    let result = archive_inspect(Path::new(archive_path), Path::new(staging_root))
        .map_err(EngineError::from)?;
    Ok(ArchiveInspectResultDto {
        staging_root: result.staging_root.to_string_lossy().into_owned(),
        schema_version: result.manifest.schema_version,
        entry_count: result.manifest.entries.len() as u64,
    })
}

/// Imports (inspect alias) into staging.
///
/// # Errors
///
/// Same as inspect.
pub fn ffi_archive_import(
    archive_path: &str,
    staging_root: &str,
) -> Result<ArchiveInspectResultDto, EngineError> {
    let result = archive_import(Path::new(archive_path), Path::new(staging_root))
        .map_err(EngineError::from)?;
    Ok(ArchiveInspectResultDto {
        staging_root: result.staging_root.to_string_lossy().into_owned(),
        schema_version: result.manifest.schema_version,
        entry_count: result.manifest.entries.len() as u64,
    })
}

/// Atomically activates green staging as live (path-only).
///
/// # Errors
///
/// Activate validation/storage errors.
pub fn ffi_archive_activate(
    staging_root: &str,
    live_root: &str,
    backup_root: &str,
) -> Result<(), EngineError> {
    archive_activate(
        Path::new(staging_root),
        Path::new(live_root),
        Path::new(backup_root),
    )
    .map_err(EngineError::from)
}

/// Import → activate → rebuild projection on the activated live root.
///
/// # Errors
///
/// Import, activate, or rebuild errors.
pub fn ffi_archive_import_activate_rebuild(
    archive_path: &str,
    staging_root: &str,
    live_root: &str,
    backup_root: &str,
    batch_size: u32,
) -> Result<StoreRebuildResult, EngineError> {
    let batch = if batch_size == 0 {
        64
    } else {
        usize::try_from(batch_size).unwrap_or(64)
    };
    let result = archive_import_activate_rebuild(
        Path::new(archive_path),
        Path::new(staging_root),
        Path::new(live_root),
        Path::new(backup_root),
        batch,
    )
    .map_err(EngineError::from)?;
    Ok(StoreRebuildResult {
        memos_indexed: result.memos_indexed,
        file_count: result.file_count,
        attachment_count: result.attachment_count,
        workspace_digest: result.workspace_digest,
        store_digest: result.store_digest,
        corrupt_lomo_isolated: result.corrupt_lomo_isolated,
        high_water_revision: result.high_water_revision,
    })
}

/// Converts promote plan DTOs for memo apply (`pending_promotes` wire).
///
/// # Errors
///
/// Returns an engine error when any plan DTO fails validation or conversion.
pub fn pending_promotes_from_ffi(
    plans: &[MediaPromotePlanDto],
) -> Result<Vec<PromotePlan>, EngineError> {
    plans.iter().map(promote_plan_from_dto).collect()
}
