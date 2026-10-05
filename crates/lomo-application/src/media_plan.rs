//! Staged media joins the transaction as identity plus a recoverable source, never as bytes.

use std::path::Path;

use lomo_core::{
    LomoError, OperationId, RelativeWorkspacePath, Sha256Digest, StagedArtifactSource,
};
use lomo_media::{
    ArtifactId, ContentDigest, PromotePlan, StageLease, StageLedger, StageOwnerKind,
    stage_directory_of,
};
use lomo_store::{project_content_facts, select_pending_promotes};
use lomo_workspace::canonical_attachment_path;

use crate::{
    error::{storage, validation},
    transaction::PlannedFile,
    workspace_io::WorkspaceIo,
};

pub fn plan_attachment_files(
    io: &WorkspaceIo<'_>,
    operation_id: &OperationId,
    content: &str,
    pending: &[PromotePlan],
) -> Result<Vec<PlannedFile>, LomoError> {
    for plan in pending {
        if plan.operation_id != operation_id.as_str() {
            return Err(validation(
                "promote_operation_id_mismatch",
                "pending promote operation_id must match the memo operation-id",
            ));
        }
    }
    let selected = select_pending_promotes(content, pending)?;
    let mut files = Vec::new();
    for plan in &selected {
        files.push(planned_attachment(plan)?);
    }
    require_referenced_attachments(io, content, &files)?;
    Ok(files)
}

/// Releases the pending-operation claim once the operation is committed. Unselected promotes
/// still carry the operation lease, so this runs over the whole request set, not just the files
/// that entered the plan.
pub fn discard_private_staging(plans: &[PromotePlan]) {
    for plan in plans {
        release_lease(
            &plan.staged.staging_path,
            plan.staged.digest.as_str(),
            &plan.operation_id,
        );
    }
}

/// Drops the pending-operation claim on every artifact source in a committed record. Recovery
/// reaches this through the frozen record, which carries source identity but no bytes.
pub fn release_committed_artifacts(operation_id: &OperationId, files: &[PlannedFile]) {
    for file in files {
        let PlannedFile::ArtifactWrite { source, .. } = file else {
            continue;
        };
        release_lease(
            Path::new(source.path()),
            source.digest().as_str(),
            operation_id.as_str(),
        );
    }
}

/// Releases one pending-operation lease, reclaiming staged bytes only when no holder remains.
/// An artifact the ledger never recorded is an orphan: unleased private stage bytes go away.
fn release_lease(staging_path: &Path, artifact: &str, owner_id: &str) {
    let Ok(stage_dir) = stage_directory_of(staging_path) else {
        return;
    };
    let Ok(mut ledger) = StageLedger::load(&stage_dir) else {
        // Fail closed: never delete bytes when the owner ledger cannot be read.
        return;
    };
    let Ok(artifact_id) = ArtifactId::parse(artifact) else {
        return;
    };
    let Ok(lease) = StageLease::new(
        artifact_id.clone(),
        StageOwnerKind::PendingOperation,
        owner_id,
    ) else {
        return;
    };
    match ledger.release(&stage_dir, &lease) {
        Err(error) if error.code() == "media_stage_artifact_unknown" => {
            if !ledger.holds_leases(&artifact_id) && staging_path.is_file() {
                // behavior-contract: silent-result-ok: leftover unleased stage bytes are
                // orphans, not workspace authority; reclaim is best-effort.
                drop(std::fs::remove_file(staging_path));
            }
        }
        Ok(_) | Err(_) => {}
    }
}

fn planned_attachment(plan: &PromotePlan) -> Result<PlannedFile, LomoError> {
    let path = RelativeWorkspacePath::parse(plan.final_relative_path.as_str())?;
    if plan.staged.size > crate::resource::MAX_FILE_BYTES {
        return Err(crate::error::resource_limit(
            "promote_staged_too_large",
            "staged attachment exceeds the per-file budget",
        ));
    }
    let metadata = std::fs::metadata(&plan.staged.staging_path).map_err(|error| {
        storage(
            "promote_staged_missing",
            format!("staged media file is missing; body must not reference it: {error}"),
        )
    })?;
    if !metadata.is_file() {
        return Err(validation(
            "promote_staged_missing",
            "staged media path is not a regular file; body must not reference it",
        ));
    }
    // Re-hash the retained source by streaming: the plan must witness real bytes without
    // loading them into memory.
    let (digest, size) = ContentDigest::stream_from_path(&plan.staged.staging_path)?;
    if digest != plan.staged.digest || size != plan.staged.size {
        return Err(validation(
            "promote_staged_digest_mismatch",
            "staged media bytes do not match the frozen digest and size",
        ));
    }
    retain_stage_lease(plan)?;
    let source = StagedArtifactSource::new(
        plan.staged.staging_path.to_str().ok_or_else(|| {
            validation(
                "promote_staged_path_invalid",
                "staged media path is not valid UTF-8",
            )
        })?,
        plan.staged.size,
        Sha256Digest::parse(plan.staged.digest.as_str())?,
    )?;
    Ok(PlannedFile::ArtifactWrite { path, source })
}

/// Planning an artifact write claims the staged source for this operation, so the durable
/// record keeps the bytes recoverable across a crash between plan and commit.
fn retain_stage_lease(plan: &PromotePlan) -> Result<(), LomoError> {
    let stage_dir = stage_directory_of(&plan.staged.staging_path)?;
    let mut ledger = StageLedger::load(&stage_dir)?;
    let lease = StageLease::new(
        ArtifactId::of_digest(&plan.staged.digest),
        StageOwnerKind::PendingOperation,
        &plan.operation_id,
    )?;
    ledger.acquire(&plan.staged, lease)
}

fn require_referenced_attachments(
    io: &WorkspaceIo<'_>,
    content: &str,
    planned: &[PlannedFile],
) -> Result<(), LomoError> {
    let facts = project_content_facts(content)?;
    for relative in facts.attachment_paths {
        // One canonical representation: `media/./x` and `media//x` must resolve to the same
        // committed file check as `media/x`. A destination that cannot name a workspace file
        // (external URL, empty, escapes the root) has nothing to require.
        let Some(canonical) = canonical_attachment_path(&relative) else {
            continue;
        };
        let path = RelativeWorkspacePath::parse(&canonical)?;
        if planned.iter().any(|file| file.path() == &path) {
            continue;
        }
        if io.stat(&path)?.is_none() {
            return Err(validation(
                "attachment_file_missing_after_promote",
                "memo body references an attachment path that is not a committed file; refuse body/`attachment_ref`",
            ));
        }
    }
    Ok(())
}
