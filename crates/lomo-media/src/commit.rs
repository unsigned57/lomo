//! Promote staged media into final workspace paths under a memo operation-id.

use std::fs;
use std::path::{Path, PathBuf};

use lomo_core::LomoError;
use serde::{Deserialize, Serialize};

use crate::error::{storage, validation};
use crate::identity::{ContentDigest, MediaMime};
use crate::path::MediaRelativePath;
use crate::stage::MediaStaged;

/// Crash injection points for promote recovery tests.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PromoteCrashPoint {
    None,
    BeforeMove,
    AfterMoveBeforeRecord,
}

/// Planned promote of one staged item into a final relative path.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct PromotePlan {
    pub operation_id: String,
    pub staged: MediaStaged,
    pub final_relative_path: MediaRelativePath,
}

/// Result after a successful promote.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct PromoteResult {
    pub operation_id: String,
    pub digest: ContentDigest,
    pub mime: MediaMime,
    pub size: u64,
    pub final_absolute_path: PathBuf,
    pub final_relative_path: String,
}

/// Promotes staged media to `workspace_root/final_relative_path`.
///
/// Callers (store memo transaction) must only record `attachment_ref` after this returns Ok,
/// under the same `operation_id`. Crash points model half-success prevention tests.
///
/// # Errors
///
/// Returns validation/storage when staged file missing, path invalid, or move fails.
/// When `crash_point` is not `None`, returns a synthetic conflict error after the named step.
pub fn promote_staged(
    workspace_root: &Path,
    plan: &PromotePlan,
    crash_point: PromoteCrashPoint,
) -> Result<PromoteResult, LomoError> {
    if plan.operation_id.is_empty() || plan.operation_id.len() > 128 {
        return Err(validation(
            "invalid_promote_operation_id",
            "promote operation_id must be a non-empty bounded token",
        ));
    }

    let final_absolute = workspace_root.join(plan.final_relative_path.as_str());

    // Recovery complete-once: after a prior successful move, stage may be gone while final holds
    // the same digest. Accept without requiring the staged path (no half-success body path).
    if !plan.staged.staging_path.is_file() {
        if final_absolute.is_file() {
            let (existing, size) = ContentDigest::stream_from_path(&final_absolute)?;
            if existing == plan.staged.digest && size == plan.staged.size {
                if crash_point == PromoteCrashPoint::AfterMoveBeforeRecord {
                    return Err(crate::error::conflict(
                        "promote_crash_after_move_before_record",
                        "injected crash after move before attachment_ref record",
                    ));
                }
                return Ok(PromoteResult {
                    operation_id: plan.operation_id.clone(),
                    digest: plan.staged.digest.clone(),
                    mime: plan.staged.mime,
                    size: plan.staged.size,
                    final_absolute_path: final_absolute,
                    final_relative_path: plan.final_relative_path.as_str().to_owned(),
                });
            }
        }
        return Err(validation(
            "promote_staged_missing",
            "staged media file is missing; body must not reference it",
        ));
    }

    if let Some(parent) = final_absolute.parent() {
        fs::create_dir_all(parent).map_err(|error| {
            storage(
                "promote_dir_create_failed",
                &format!("failed to create final media parent: {error}"),
            )
        })?;
    }

    if crash_point == PromoteCrashPoint::BeforeMove {
        return Err(crate::error::conflict(
            "promote_crash_before_move",
            "injected crash before staged media move",
        ));
    }

    // Dedup: if final path already has same digest, drop stage and succeed.
    if final_absolute.is_file() {
        let (existing, _) = ContentDigest::stream_from_path(&final_absolute)?;
        if existing == plan.staged.digest {
            if plan.staged.staging_path.exists() {
                fs::remove_file(&plan.staged.staging_path).map_err(|error| {
                    storage(
                        "promote_stage_remove_failed",
                        &format!(
                            "failed to remove staged file after digest-dedup promote: {error}"
                        ),
                    )
                })?;
            }
            if crash_point == PromoteCrashPoint::AfterMoveBeforeRecord {
                return Err(crate::error::conflict(
                    "promote_crash_after_move_before_record",
                    "injected crash after move before attachment_ref record",
                ));
            }
            return Ok(PromoteResult {
                operation_id: plan.operation_id.clone(),
                digest: plan.staged.digest.clone(),
                mime: plan.staged.mime,
                size: plan.staged.size,
                final_absolute_path: final_absolute,
                final_relative_path: plan.final_relative_path.as_str().to_owned(),
            });
        }
        return Err(validation(
            "promote_final_path_conflict",
            "final media path exists with a different digest",
        ));
    }

    if fs::rename(&plan.staged.staging_path, &final_absolute).is_err() {
        // Cross-device fallback: stream copy then remove stage.
        copy_then_remove(&plan.staged.staging_path, &final_absolute)?;
    }

    if crash_point == PromoteCrashPoint::AfterMoveBeforeRecord {
        return Err(crate::error::conflict(
            "promote_crash_after_move_before_record",
            "injected crash after move before attachment_ref record",
        ));
    }

    Ok(PromoteResult {
        operation_id: plan.operation_id.clone(),
        digest: plan.staged.digest.clone(),
        mime: plan.staged.mime,
        size: plan.staged.size,
        final_absolute_path: final_absolute,
        final_relative_path: plan.final_relative_path.as_str().to_owned(),
    })
}

fn copy_then_remove(from: &Path, to: &Path) -> Result<(), LomoError> {
    use std::io::{Read, Write};
    let mut input = fs::File::open(from).map_err(|error| {
        storage(
            "promote_copy_open_failed",
            &format!("promote copy open failed: {error}"),
        )
    })?;
    let mut output = fs::File::create(to).map_err(|error| {
        storage(
            "promote_copy_create_failed",
            &format!("promote copy create failed: {error}"),
        )
    })?;
    let mut buffer = [0_u8; crate::identity::DIGEST_STREAM_CHUNK_BYTES];
    loop {
        let read = input.read(&mut buffer).map_err(|error| {
            storage(
                "promote_copy_read_failed",
                &format!("promote copy read failed: {error}"),
            )
        })?;
        if read == 0 {
            break;
        }
        let Some(chunk) = buffer.get(..read) else {
            return Err(validation(
                "promote_copy_chunk_out_of_bounds",
                "promote copy chunk bounds violated",
            ));
        };
        output.write_all(chunk).map_err(|error| {
            storage(
                "promote_copy_write_failed",
                &format!("promote copy write failed: {error}"),
            )
        })?;
    }
    fs::remove_file(from).map_err(|error| {
        storage(
            "promote_stage_remove_failed",
            &format!("failed to remove staged file after promote copy: {error}"),
        )
    })?;
    Ok(())
}
