//! History-window attachment inputs for D6 orphan refcount (current ∪ trash ∪ history).
//!
//! Retained history revisions keep full Markdown snapshots in `revision_index`. Orphan sweep
//! must count attachments still referenced by **in-window** history so media stays live after the
//! current memo body no longer links them. Out-of-window revisions must not keep digests.

use crate::error::{corruption, from_sqlite, validation};
use rusqlite::{Connection, params};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemoHistoryRevision {
    pub record_id: String,
    pub revision: u64,
    pub created_at_ms: i64,
    pub content: String,
    pub file_fingerprint: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemoHistoryPage {
    pub items: Vec<MemoHistoryRevision>,
    pub next_cursor: Option<String>,
}

/// Lists durable memo revisions in descending revision order.
///
/// # Errors
///
/// Returns validation for malformed cursors/limits and storage errors for unreadable history.
pub fn list_memo_history(
    connection: &Connection,
    memo_id: &str,
    cursor: Option<&str>,
    limit: usize,
) -> Result<MemoHistoryPage, lomo_core::LomoError> {
    if memo_id.is_empty() || limit == 0 || limit > 256 {
        return Err(validation(
            "invalid_history_page",
            "memo id and page limit are invalid",
        ));
    }
    let offset = cursor
        .unwrap_or("0")
        .parse::<usize>()
        .map_err(|_parse_error| {
            validation(
                "invalid_history_cursor",
                "history cursor must be a decimal offset",
            )
        })?;
    let offset_i64 = i64::try_from(offset)
        .map_err(|_error| validation("invalid_history_cursor", "history cursor exceeds SQLite"))?;
    let fetch = limit
        .checked_add(1)
        .ok_or_else(|| validation("invalid_history_page", "history page limit overflow"))?;
    let fetch_i64 = i64::try_from(fetch).map_err(|_error| {
        validation("invalid_history_page", "history page limit exceeds SQLite")
    })?;
    let mut statement = connection
        .prepare(
            "SELECT revision,created_at_ms,content,file_fingerprint,history_record_id FROM revision_index \
             WHERE memo_id=?1 AND content IS NOT NULL AND file_fingerprint IS NOT NULL \
             ORDER BY revision DESC,history_record_id ASC LIMIT ?2 OFFSET ?3",
        )
        .map_err(|error| from_sqlite(&error))?;
    let rows = statement
        .query_map(params![memo_id, fetch_i64, offset_i64], |row| {
            Ok((
                row.get::<_, i64>(0)?,
                row.get::<_, i64>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, String>(3)?,
                row.get::<_, String>(4)?,
            ))
        })
        .map_err(|error| from_sqlite(&error))?;
    let mut items = Vec::with_capacity(fetch);
    for row in rows {
        let (revision, created_at_ms, content, file_fingerprint, record_id) =
            row.map_err(|error| from_sqlite(&error))?;
        items.push(MemoHistoryRevision {
            record_id,
            revision: u64::try_from(revision).map_err(|_error| {
                corruption(
                    "invalid_history_revision",
                    "negative projected history revision",
                )
            })?,
            created_at_ms,
            content,
            file_fingerprint,
        });
    }
    let has_more = items.len() > limit;
    items.truncate(limit);
    let next_cursor = has_more.then(|| offset.saturating_add(limit).to_string());
    Ok(MemoHistoryPage { items, next_cursor })
}

/// Default per-memo revision keep count for history media refs (D5/D6 retention window).
///
/// Product policy: keep the newest `N` history revisions per memo for restore + orphan keep-set.
/// Older durable records may still exist on disk until async prune, but they are **out of window**
/// for media refcount and must not pin digests.
pub const DEFAULT_HISTORY_MEDIA_RETENTION_REVISIONS: usize = 20;

/// One in-window history revision body as projected into `revision_index`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HistoryRevisionBody {
    /// Memo that owns the history revision.
    pub memo_id: String,
    /// Content revision recorded in the history body.
    pub revision: u64,
    /// Full Markdown snapshot body (`content` is never `NULL` in an admitted projection).
    pub content: String,
}

/// Lists the newest `retention_revisions` history bodies per memo in one bounded query.
///
/// The window ranks every projected revision, matching [`list_memo_history`] ordering, so the
/// keep-set is exactly the revisions a reader could page to. A `NULL` body inside the window means
/// the projection predates the v5 backfill and is reported as corruption rather than silently
/// shrinking the keep-set.
///
/// # Errors
///
/// Returns validation for a zero limit, corruption for missing in-window bodies, and storage for
/// projection read failures.
pub fn list_history_revision_bodies(
    connection: &Connection,
    retention_revisions: usize,
) -> Result<Vec<HistoryRevisionBody>, lomo_core::LomoError> {
    if retention_revisions == 0 {
        return Err(validation(
            "invalid_history_retention",
            "history media keep-set requires a non-zero revision window",
        ));
    }
    let retention = i64::try_from(retention_revisions)
        .map_err(|_error| validation("invalid_history_retention", "window exceeds SQLite"))?;
    let mut statement = connection
        .prepare(
            "SELECT memo_id, revision, content FROM (\
                SELECT memo_id, revision, content, \
                    ROW_NUMBER() OVER (\
                        PARTITION BY memo_id \
                        ORDER BY revision DESC, history_record_id ASC\
                    ) AS rn \
                FROM revision_index\
             ) WHERE rn <= ?1 \
             ORDER BY memo_id, revision DESC",
        )
        .map_err(|error| from_sqlite(&error))?;
    let rows = statement
        .query_map(params![retention], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, i64>(1)?,
                row.get::<_, Option<String>>(2)?,
            ))
        })
        .map_err(|error| from_sqlite(&error))?;
    let mut out = Vec::new();
    for row in rows {
        let (memo_id, revision, content) = row.map_err(|error| from_sqlite(&error))?;
        let content = content.ok_or_else(|| {
            corruption(
                "history_revision_body_missing",
                "in-window history revision has no projected body; rebuild before media refcount",
            )
        })?;
        out.push(HistoryRevisionBody {
            memo_id,
            revision: u64::try_from(revision).map_err(|_error| {
                corruption(
                    "invalid_history_revision",
                    "negative projected history revision",
                )
            })?,
            content,
        });
    }
    Ok(out)
}
