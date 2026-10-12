//! History-window attachment inputs for D6 orphan refcount (current ∪ trash ∪ history).
//!
//! Retained history revisions keep full Markdown snapshots in `revision_index`. Orphan sweep
//! must count attachments still referenced by **in-window** history so media stays live after the
//! current memo body no longer links them. Out-of-window revisions must not keep digests.

use crate::cursor::HistoryPageCursor;
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
/// Paging is keyset-based: the opaque cursor carries the previous page's last
/// `(revision, history_record_id)` bound under `revision DESC, history_record_id ASC`, so a
/// continuation enters exactly after that row instead of re-scanning an OFFSET prefix. The cursor
/// is bound to this memo and to the store high-water revision it was minted under — malformed,
/// foreign-memo, stale, or out-of-range bounds fail closed rather than silently restarting.
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
    let high_water_revision = crate::read_meta_u64(connection, "high_water_revision")?;
    let bound = match cursor {
        Some(raw) => Some(resolve_history_bound(
            connection,
            memo_id,
            raw,
            high_water_revision,
        )?),
        None => None,
    };
    let fetch = limit
        .checked_add(1)
        .ok_or_else(|| validation("invalid_history_page", "history page limit overflow"))?;
    let fetch_i64 = i64::try_from(fetch).map_err(|_error| {
        validation("invalid_history_page", "history page limit exceeds SQLite")
    })?;
    // Keyset bound for `revision DESC, history_record_id ASC`: strictly-older revision, or the
    // same revision at a strictly-later record id. `WHERE cursor > ?` never re-scans the prefix.
    let (sql, bound_args): (&str, Option<(i64, &str)>) = match &bound {
        Some((revision, record_id)) => (
            "SELECT revision,created_at_ms,content,file_fingerprint,history_record_id \
             FROM revision_index \
             WHERE memo_id=?1 AND content IS NOT NULL AND file_fingerprint IS NOT NULL \
             AND (revision < ?3 OR (revision = ?3 AND history_record_id > ?4)) \
             ORDER BY revision DESC,history_record_id ASC LIMIT ?2",
            Some((*revision, record_id.as_str())),
        ),
        None => (
            "SELECT revision,created_at_ms,content,file_fingerprint,history_record_id \
             FROM revision_index \
             WHERE memo_id=?1 AND content IS NOT NULL AND file_fingerprint IS NOT NULL \
             ORDER BY revision DESC,history_record_id ASC LIMIT ?2",
            None,
        ),
    };
    let mut statement = connection
        .prepare(sql)
        .map_err(|error| from_sqlite(&error))?;
    let mut items = Vec::with_capacity(fetch);
    let collect =
        |row: &rusqlite::Row<'_>| -> rusqlite::Result<(i64, i64, String, String, String)> {
            Ok((
                row.get::<_, i64>(0)?,
                row.get::<_, i64>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, String>(3)?,
                row.get::<_, String>(4)?,
            ))
        };
    let rows = match bound_args {
        Some((revision, record_id)) => statement
            .query_map(params![memo_id, fetch_i64, revision, record_id], collect)
            .map_err(|error| from_sqlite(&error))?,
        None => statement
            .query_map(params![memo_id, fetch_i64], collect)
            .map_err(|error| from_sqlite(&error))?,
    };
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
    let next_cursor = match (has_more, items.last()) {
        (true, Some(tail)) => Some(
            HistoryPageCursor::new(
                memo_id.to_owned(),
                tail.revision,
                tail.record_id.clone(),
                high_water_revision,
            )
            .encode()?,
        ),
        _ => None,
    };
    Ok(MemoHistoryPage { items, next_cursor })
}

/// Decodes an opaque history cursor, validates it against the memo and store revision it pages
/// under, and proves the bound row is still a pageable revision — the projection is append-only
/// within one high-water, so a bound naming an absent or filtered-out row is forged evidence.
fn resolve_history_bound(
    connection: &Connection,
    memo_id: &str,
    raw: &str,
    high_water_revision: u64,
) -> Result<(i64, String), lomo_core::LomoError> {
    let decoded = HistoryPageCursor::decode(raw)?;
    decoded.validate_against(memo_id, high_water_revision)?;
    let last_revision = i64::try_from(decoded.last_revision).map_err(|_error| {
        validation(
            "invalid_history_cursor",
            "history cursor revision exceeds SQLite",
        )
    })?;
    let bound_exists: bool = connection
        .query_row(
            "SELECT EXISTS(SELECT 1 FROM revision_index \
             WHERE memo_id=?1 AND revision=?2 AND history_record_id=?3 \
             AND content IS NOT NULL AND file_fingerprint IS NOT NULL)",
            params![memo_id, last_revision, &decoded.last_history_record_id],
            |row| row.get::<_, i64>(0),
        )
        .map_err(|error| from_sqlite(&error))?
        != 0;
    if !bound_exists {
        return Err(validation(
            "invalid_history_cursor",
            "history cursor bound is not a projected revision",
        ));
    }
    Ok((last_revision, decoded.last_history_record_id))
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

/// One materialized in-window history attachment reference.
///
/// Rows are written by the rebuild indexer (`index_scanned_history_revision`) so orphan
/// protection reads projected facts instead of re-parsing retained revision bodies.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HistoryAttachmentRef {
    /// Memo that owns the revision.
    pub memo_id: String,
    /// Durable history generation (`revision_index.revision`).
    pub revision: u64,
    /// Canonical workspace-relative attachment path.
    pub relative_path: String,
}

/// Lists the newest `retention_revisions` attachment references per memo in one bounded query.
///
/// The keep-set window is the same `ROW_NUMBER` ranking [`list_history_revision_bodies`] uses, so
/// projected refs and projected bodies never disagree about which revisions are in-window.
///
/// # Errors
///
/// Returns validation for a zero limit and storage errors for projection read failures.
pub fn list_history_attachment_refs(
    connection: &Connection,
    retention_revisions: usize,
) -> Result<Vec<HistoryAttachmentRef>, lomo_core::LomoError> {
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
            "SELECT ref.memo_id, ref.revision, ref.relative_path FROM history_attachment_ref ref \
             JOIN (\
                SELECT memo_id, revision, \
                    ROW_NUMBER() OVER (\
                        PARTITION BY memo_id \
                        ORDER BY revision DESC, history_record_id ASC\
                    ) AS rn \
                FROM revision_index\
             ) window ON window.memo_id = ref.memo_id AND window.revision = ref.revision \
             WHERE window.rn <= ?1 \
             ORDER BY ref.memo_id, ref.revision, ref.relative_path",
        )
        .map_err(|error| from_sqlite(&error))?;
    let rows = statement
        .query_map(params![retention], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, i64>(1)?,
                row.get::<_, String>(2)?,
            ))
        })
        .map_err(|error| from_sqlite(&error))?;
    let mut out = Vec::new();
    for row in rows {
        let (memo_id, revision, relative_path) = row.map_err(|error| from_sqlite(&error))?;
        out.push(HistoryAttachmentRef {
            memo_id,
            revision: u64::try_from(revision).map_err(|_error| {
                corruption(
                    "invalid_history_revision",
                    "negative projected history revision",
                )
            })?,
            relative_path,
        });
    }
    Ok(out)
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
