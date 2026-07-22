//! History-window attachment paths for D6 orphan refcount (current ∪ trash ∪ history).
//!
//! Durable history records under `.lomo/history/v1` hold full Markdown snapshots. Orphan sweep
//! must count digests still referenced by **in-window** history so media stays live after the
//! current memo body no longer links them. Out-of-window revisions must not keep digests.

use std::collections::HashMap;
use std::fs;
use std::path::Path;
use std::time::UNIX_EPOCH;

use crate::content_facts::project_content_facts;
use crate::error::storage;
use crate::lomo_format::{HistoryBody, LomoPaths, LomoRecordKind, read_record};

/// Default per-memo revision keep count for history media refs (D5/D6 retention window).
///
/// Product policy: keep the newest `N` history revisions per memo for restore + orphan keep-set.
/// Older durable records may still exist on disk until async prune, but they are **out of window**
/// for media refcount and must not pin digests.
pub const DEFAULT_HISTORY_MEDIA_RETENTION_REVISIONS: usize = 20;

/// One attachment path referenced by an in-window history revision body.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HistoryAttachmentRef {
    /// Memo that owns the history revision.
    pub memo_id: String,
    /// Content revision recorded in the history body.
    pub revision: u64,
    /// Relative attachment path from the history Markdown (same form as `attachment_ref`).
    pub relative_path: String,
    /// Stable owner key for media orphan wire (`memo_id@r{revision}`).
    pub owner_key: String,
}

/// Scans durable history records and projects attachment paths from **in-window** bodies only.
///
/// Retention (explicit): per `memo_id`, only the newest
/// [`DEFAULT_HISTORY_MEDIA_RETENTION_REVISIONS`] revisions contribute keep-set digests. Out-of-window
/// history on disk is ignored for orphan refcount even if the `.rec` still exists.
///
/// Corrupt / non-history records are skipped (same isolate-tolerant posture as rebuild listing).
/// Empty or missing history directory yields an empty list.
///
/// # Errors
///
/// Returns storage when the history directory cannot be listed.
pub fn list_history_attachment_refs(
    workspace_root: &Path,
) -> Result<Vec<HistoryAttachmentRef>, lomo_core::LomoError> {
    list_history_attachment_refs_with_retention(
        workspace_root,
        DEFAULT_HISTORY_MEDIA_RETENTION_REVISIONS,
    )
}

/// Same as [`list_history_attachment_refs`] with an explicit per-memo revision keep count.
///
/// `retention_revisions == 0` yields an empty keep-set (all history out of window).
///
/// # Errors
///
/// Returns storage when the history directory cannot be listed.
pub fn list_history_attachment_refs_with_retention(
    workspace_root: &Path,
    retention_revisions: usize,
) -> Result<Vec<HistoryAttachmentRef>, lomo_core::LomoError> {
    let paths = LomoPaths::for_workspace(workspace_root);
    if !paths.history.exists() {
        return Ok(Vec::new());
    }
    let read = fs::read_dir(&paths.history).map_err(|err| {
        storage(
            "lomo_history_list_failed",
            &format!("cannot list history for media refs: {err}"),
        )
    })?;

    // Collect decoded history bodies first so we can apply per-memo revision windows.
    let mut by_memo: HashMap<String, Vec<(u64, HistoryBody, i64)>> = HashMap::new();
    for entry in read {
        let entry = entry.map_err(|err| {
            storage(
                "lomo_history_list_failed",
                &format!("cannot read history entry for media refs: {err}"),
            )
        })?;
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("rec") {
            continue;
        }
        let Ok(record) = read_record(&path) else {
            continue;
        };
        if record.payload.kind != LomoRecordKind::History {
            continue;
        }
        let Ok(body) = serde_json::from_str::<HistoryBody>(&record.payload.body_json) else {
            continue;
        };
        // Prefer body.revision ordering; break ties with file mtime so older same-revision
        // corrupt renames do not displace a fresher write (defensive).
        // behavior-contract: silent-result-ok: mtime is only a sort tie-break; missing metadata
        // must not fail the keep-set scan.
        let mtime_ms =
            fs::metadata(&path)
                .and_then(|meta| meta.modified())
                .map_or(0_i64, |modified| {
                    modified
                        .duration_since(UNIX_EPOCH)
                        .map_or(0_i64, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
                });
        by_memo
            .entry(body.memo_id.clone())
            .or_default()
            .push((body.revision, body, mtime_ms));
    }

    let mut out = Vec::new();
    for (_memo_id, mut revisions) in by_memo {
        // Newest revision first; mtime breaks ties.
        revisions.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| b.2.cmp(&a.2)));
        if retention_revisions == 0 {
            continue;
        }
        revisions.truncate(retention_revisions);
        for (revision, body, _mtime) in revisions {
            let Ok(facts) = project_content_facts(&body.content) else {
                continue;
            };
            for relative_path in facts.attachment_paths {
                out.push(HistoryAttachmentRef {
                    owner_key: format!("{}@r{revision}", body.memo_id),
                    memo_id: body.memo_id.clone(),
                    revision,
                    relative_path,
                });
            }
        }
    }

    out.sort_by(|a, b| {
        (a.memo_id.as_str(), a.revision, a.relative_path.as_str()).cmp(&(
            b.memo_id.as_str(),
            b.revision,
            b.relative_path.as_str(),
        ))
    });
    out.dedup_by(|a, b| {
        a.memo_id == b.memo_id && a.revision == b.revision && a.relative_path == b.relative_path
    });
    Ok(out)
}
