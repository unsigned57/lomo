//! Stage-4 dark-build media + archive FFI conversion surface (P4-09).
//!
//! Path-only commands: stage/finalize/promote/manifest/orphan + archive
//! export/inspect/import/activate. No full media-byte FFI. Business rules stay in
//! `lomo-media` / `lomo-store`. Not wired into production Kotlin DI.

use std::{
    collections::BTreeMap,
    path::{Path, PathBuf},
};

use boltffi::data;
use lomo_core::LomoError;
use lomo_media::{
    self as media, ArtifactId, AttachmentRef, ContentDigest, MediaMime, MediaRelativePath,
    MediaSource, MediaStaged, PromotePlan, ReferenceSource, STAGE_DIR_NAME, StageLease,
    StageLedger, StageOwnerKind, allocate_recording_target, finalize_recording, promote_staged,
    stage_directory, stage_media, suggest_human_relative_path, sweep_orphans, wall_clock_ms,
};
use lomo_store::{
    archive_activate, archive_export, archive_import, archive_import_activate_rebuild,
    archive_inspect,
};

use crate::{EngineError, store_ffi::StoreRebuildResult};

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

/// Who currently holds staged bytes (owner vocabulary for the stage ledger).
#[data]
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum MediaStageOwnerKindDto {
    /// An in-progress editor draft that imported the media.
    #[default]
    Draft,
    /// A frozen memo operation committing the staged media.
    PendingOperation,
    /// A sync/LAN receive session staging inbound media.
    IncomingTransfer,
    /// A committed document reference keeping bytes alive.
    CommittedReference,
}

/// One lease over a staged artifact.
#[data]
#[derive(Clone, Debug, Default)]
pub struct MediaStageLeaseDto {
    pub artifact_id: String,
    pub owner_kind: MediaStageOwnerKindDto,
    pub owner_id: String,
}

/// Durable staged artifact record returned to the host.
#[data]
#[derive(Clone, Debug, Default)]
pub struct MediaStageRecordDto {
    pub artifact_id: String,
    pub digest: String,
    pub size: u64,
    pub mime: String,
    pub staging_path: String,
    pub human_name_hint: String,
    /// Collision-free workspace-relative destination resolved by the media owner.
    pub suggested_final_relative_path: String,
    pub leases: Vec<MediaStageLeaseDto>,
    /// False when the staged bytes vanished; the draft must surface a recoverable failure.
    pub staged_bytes_present: bool,
}

/// Result of releasing one stage lease.
#[data]
#[derive(Clone, Debug, Default)]
pub struct MediaStageReleaseDto {
    pub artifact_id: String,
    pub remaining_leases: u64,
    pub bytes_deleted: bool,
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

pub(crate) fn promote_plan_from_dto(dto: &MediaPromotePlanDto) -> Result<PromotePlan, EngineError> {
    let staged = staged_from_dto(&dto.staged)?;
    let final_relative_path =
        MediaRelativePath::parse(&dto.final_relative_path).map_err(EngineError::from)?;
    Ok(PromotePlan {
        operation_id: dto.operation_id.clone(),
        staged,
        final_relative_path,
    })
}

/// Compares a wire candidate with the Rust-selected plans without exposing the internal media
/// types through the generated binding surface.
pub(crate) fn pending_promote_is_selected(
    dto: &MediaPromotePlanDto,
    selected: &[PromotePlan],
) -> bool {
    let Ok(plan) = promote_plan_from_dto(dto) else {
        // The same DTO list was validated before selection.  Returning false here is defensive
        // only for a future caller that violates that boundary; it must not invent a selection.
        return false;
    };
    selected.iter().any(|candidate| candidate == &plan)
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

/// Records a freshly staged artifact in the durable stage ledger and acquires one lease.
///
/// `workspace_root` is the Direct workspace used to resolve destination collisions; pass it as
/// `None` when only a private stage root exists.
///
/// # Errors
///
/// Media validation/storage errors.
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI boundary owns the staged DTO"
)]
pub fn ffi_record_stage_lease(
    workspace_root: Option<&str>,
    staged: MediaStagedDto,
    owner_kind: MediaStageOwnerKindDto,
    owner_id: &str,
) -> Result<MediaStageRecordDto, EngineError> {
    let staged = staged_from_dto(&staged)?;
    let stage_dir =
        lomo_media::stage_directory_of(&staged.staging_path).map_err(EngineError::from)?;
    let lease = stage_lease(ArtifactId::of_digest(&staged.digest), owner_kind, owner_id)?;
    let mut ledger = StageLedger::load(&stage_dir).map_err(EngineError::from)?;
    let record = ledger
        .record(workspace_root.map(Path::new), &staged, lease)
        .map_err(EngineError::from)?;
    Ok(stage_record_to_dto(&record))
}

/// Lists the durable stage records currently leased by one exact holder.
///
/// # Errors
///
/// Media storage/corruption errors.
pub fn ffi_stage_records_for_owner(
    media_root: &str,
    owner_kind: MediaStageOwnerKindDto,
    owner_id: &str,
) -> Result<Vec<MediaStageRecordDto>, EngineError> {
    let ledger = load_ledger(media_root)?;
    Ok(ledger
        .records_for_owner(owner_kind_from_dto(owner_kind), owner_id)
        .iter()
        .map(stage_record_to_dto)
        .collect())
}

/// Transfers one holder's lease to another without deleting staged bytes.
///
/// # Errors
///
/// Media validation/storage errors.
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI boundary owns the lease DTOs"
)]
pub fn ffi_transfer_stage_lease(
    media_root: &str,
    from: MediaStageLeaseDto,
    to: MediaStageLeaseDto,
) -> Result<MediaStageReleaseDto, EngineError> {
    let from = stage_lease_from_dto(&from)?;
    let to = stage_lease_from_dto(&to)?;
    let stage_dir = stage_directory(Path::new(media_root));
    let mut ledger = StageLedger::load(&stage_dir).map_err(EngineError::from)?;
    let outcome = ledger
        .transfer(&stage_dir, &from, to)
        .map_err(EngineError::from)?;
    Ok(stage_release_to_dto(&outcome))
}

/// Releases one lease; staged bytes are deleted only when no lease remains.
///
/// # Errors
///
/// Media validation/storage errors.
#[expect(
    clippy::needless_pass_by_value,
    reason = "BoltFFI boundary owns the lease DTO"
)]
pub fn ffi_release_stage_lease(
    media_root: &str,
    lease: MediaStageLeaseDto,
) -> Result<MediaStageReleaseDto, EngineError> {
    let lease = stage_lease_from_dto(&lease)?;
    let stage_dir = stage_directory(Path::new(media_root));
    let mut ledger = StageLedger::load(&stage_dir).map_err(EngineError::from)?;
    let outcome = ledger
        .release(&stage_dir, &lease)
        .map_err(EngineError::from)?;
    Ok(stage_release_to_dto(&outcome))
}

fn load_ledger(media_root: &str) -> Result<StageLedger, EngineError> {
    let stage_dir = stage_directory(Path::new(media_root));
    StageLedger::load(&stage_dir).map_err(EngineError::from)
}

const fn owner_kind_from_dto(kind: MediaStageOwnerKindDto) -> StageOwnerKind {
    match kind {
        MediaStageOwnerKindDto::Draft => StageOwnerKind::Draft,
        MediaStageOwnerKindDto::PendingOperation => StageOwnerKind::PendingOperation,
        MediaStageOwnerKindDto::IncomingTransfer => StageOwnerKind::IncomingTransfer,
        MediaStageOwnerKindDto::CommittedReference => StageOwnerKind::CommittedReference,
    }
}

fn stage_lease(
    artifact_id: ArtifactId,
    owner_kind: MediaStageOwnerKindDto,
    owner_id: &str,
) -> Result<StageLease, EngineError> {
    StageLease::new(artifact_id, owner_kind_from_dto(owner_kind), owner_id)
        .map_err(EngineError::from)
}

fn stage_lease_from_dto(dto: &MediaStageLeaseDto) -> Result<StageLease, EngineError> {
    let artifact_id = ArtifactId::parse(&dto.artifact_id).map_err(EngineError::from)?;
    stage_lease(artifact_id, dto.owner_kind, &dto.owner_id)
}

fn stage_record_to_dto(record: &lomo_media::StageRecord) -> MediaStageRecordDto {
    MediaStageRecordDto {
        artifact_id: record.artifact_id.as_str().to_owned(),
        digest: record.digest.as_str().to_owned(),
        size: record.size,
        mime: record.mime.as_str().to_owned(),
        staging_path: record.staging_path.to_string_lossy().into_owned(),
        human_name_hint: record.human_name_hint.clone(),
        suggested_final_relative_path: record.suggested_final_relative_path.clone(),
        leases: record
            .leases
            .iter()
            .map(|lease| MediaStageLeaseDto {
                artifact_id: lease.artifact_id.as_str().to_owned(),
                owner_kind: owner_kind_to_dto(lease.owner_kind),
                owner_id: lease.owner_id.clone(),
            })
            .collect(),
        staged_bytes_present: record.is_present(),
    }
}

const fn owner_kind_to_dto(kind: StageOwnerKind) -> MediaStageOwnerKindDto {
    match kind {
        StageOwnerKind::Draft => MediaStageOwnerKindDto::Draft,
        StageOwnerKind::PendingOperation => MediaStageOwnerKindDto::PendingOperation,
        StageOwnerKind::IncomingTransfer => MediaStageOwnerKindDto::IncomingTransfer,
        StageOwnerKind::CommittedReference => MediaStageOwnerKindDto::CommittedReference,
    }
}

fn stage_release_to_dto(outcome: &lomo_media::StageRelease) -> MediaStageReleaseDto {
    MediaStageReleaseDto {
        artifact_id: outcome.artifact_id.as_str().to_owned(),
        remaining_leases: outcome.remaining_leases,
        bytes_deleted: outcome.bytes_deleted,
    }
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
        rewritten: result.rewritten,
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
