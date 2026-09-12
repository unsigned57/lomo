//! Staged media bytes freeze into the same transaction as the Markdown they belong to.

use lomo_core::{LomoError, OperationId, RelativeWorkspacePath};
use lomo_media::{ContentDigest, PromotePlan};
use lomo_store::{project_content_facts, select_pending_promotes};

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

pub fn discard_private_staging(plans: &[PromotePlan]) {
    for plan in plans {
        if plan.staged.staging_path.is_file() {
            // behavior-contract: silent-result-ok: dest bytes are already frozen in the
            // transaction; leftover private stage files are orphans, not workspace authority.
            drop(std::fs::remove_file(&plan.staged.staging_path));
        }
    }
}

fn planned_attachment(plan: &PromotePlan) -> Result<PlannedFile, LomoError> {
    let path = RelativeWorkspacePath::parse(plan.final_relative_path.as_str())?;
    let bytes = std::fs::read(&plan.staged.staging_path).map_err(|error| {
        storage(
            "promote_staged_missing",
            format!("staged media file is missing; body must not reference it: {error}"),
        )
    })?;
    let digest = ContentDigest::of_slice(&bytes);
    let size = u64::try_from(bytes.len()).map_err(|_error| {
        validation(
            "promote_staged_size_overflow",
            "staged media byte length does not fit the promote size width",
        )
    })?;
    if digest != plan.staged.digest || size != plan.staged.size {
        return Err(validation(
            "promote_staged_digest_mismatch",
            "staged media bytes do not match the frozen digest and size",
        ));
    }
    Ok(PlannedFile::new(path, None, bytes))
}

fn require_referenced_attachments(
    io: &WorkspaceIo<'_>,
    content: &str,
    planned: &[PlannedFile],
) -> Result<(), LomoError> {
    let facts = project_content_facts(content)?;
    for relative in facts.attachment_paths {
        let Ok(path) = RelativeWorkspacePath::parse(&relative) else {
            continue;
        };
        if planned.iter().any(|file| file.path() == &path) {
            continue;
        }
        if io.read(&path)?.is_none() {
            return Err(validation(
                "attachment_file_missing_after_promote",
                "memo body references an attachment path that is not a committed file; refuse body/`attachment_ref`",
            ));
        }
    }
    Ok(())
}
