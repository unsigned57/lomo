//! `query_memos` + filters + bm25/tie-breaker + stats.

use std::collections::BTreeMap;
use std::path::Path;

use rusqlite::types::Value;
use rusqlite::{Connection, OptionalExtension, params, params_from_iter};

use lomo_core::PageSize;

use crate::cursor::{PageCursor, fingerprint_query};
use crate::error::{corruption, from_sqlite, resource_limit, storage, validation};
use crate::schema::TOKENIZER_VERSION;
use crate::tokenizer::{QueryPlan, Tokenizer, UnicodeTokenizer};

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum TagSelectionMode {
    #[default]
    Exact,
    Subtree,
}

/// Filters applied in the store query layer (parity with Room capabilities).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct MemoFilters {
    pub tag: Option<String>,
    pub tag_selection: TagSelectionMode,
    pub date_from_ms: Option<i64>,
    pub date_to_ms: Option<i64>,
    pub has_todo: Option<bool>,
    pub has_attachment: Option<bool>,
    pub has_url: Option<bool>,
    pub pinned_only: bool,
    pub include_trash: bool,
    pub trash_only: bool,
}

impl MemoFilters {
    #[must_use]
    pub fn fingerprint(&self) -> String {
        format!(
            "tag={:?}|tag_subtree={}|from={:?}|to={:?}|todo={:?}|att={:?}|url={:?}|pin={}|trash={}|trash_only={}",
            self.tag,
            matches!(self.tag_selection, TagSelectionMode::Subtree),
            self.date_from_ms,
            self.date_to_ms,
            self.has_todo,
            self.has_attachment,
            self.has_url,
            self.pinned_only,
            self.include_trash,
            self.trash_only
        )
    }
}

/// Query request for bounded memo pages.
#[derive(Debug, Clone)]
pub struct MemoQuery {
    pub search_text: Option<String>,
    pub filters: MemoFilters,
}

/// One memo row projection for list UI (no full body).
#[derive(Debug, Clone, PartialEq)]
#[expect(
    clippy::struct_excessive_bools,
    reason = "memo flags are independent domain bits"
)]
pub struct MemoSummary {
    pub memo_id: String,
    pub source_path: String,
    pub file_fingerprint: String,
    pub updated_at_ms: i64,
    pub created_at_ms: i64,
    pub has_todo: bool,
    pub has_url: bool,
    pub has_attachment: bool,
    pub is_pinned: bool,
    pub is_trashed: bool,
    pub body_preview: String,
    pub content_revision: u64,
    pub rank: Option<f64>,
    /// Durable + content-projected tags for list/sidebar surfaces.
    pub tags: Vec<String>,
    /// Non-audio attachment relative paths (gallery/image surfaces).
    pub image_urls: Vec<String>,
    pub reminders: Vec<lomo_workspace::ReminderReference>,
}

/// Bounded page result.
#[derive(Debug, Clone, PartialEq)]
pub struct MemoPage {
    pub items: Vec<MemoSummary>,
    pub next_cursor: Option<PageCursor>,
    pub high_water_revision: u64,
    pub query_fingerprint: String,
}

/// Aggregate stats projection.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StoreStats {
    pub memo_count: i64,
    pub pinned_count: i64,
    pub trashed_count: i64,
    pub tag_count: i64,
}

pub const SIDEBAR_PROJECTION_SCHEMA: u32 = 1;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SidebarDateCount {
    pub date: String,
    pub count: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SidebarTagCount {
    pub name: String,
    pub count: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SidebarProjection {
    pub schema_version: u32,
    pub memo_count: i64,
    pub date_counts: Vec<SidebarDateCount>,
    pub tag_counts: Vec<SidebarTagCount>,
}

/// Executes a bounded memo query with optional `FTS` and filters.
///
/// # Errors
///
/// - `stale_cursor` when the cursor does not match query fingerprint / high-water / tokenizer.
/// - `resource_limit` / validation for empty or invalid page sizes (delegates to `PageSize`).
/// - storage errors from `SQLite`.
pub fn query_memos(
    connection: &Connection,
    query: &MemoQuery,
    cursor: Option<&PageCursor>,
    page_size: PageSize,
    high_water_revision: u64,
) -> Result<MemoPage, lomo_core::LomoError> {
    let plan = match query.search_text.as_deref() {
        Some(text) if !text.trim().is_empty() => UnicodeTokenizer.query_plan(text)?,
        _ => QueryPlan {
            terms: Vec::new(),
            match_expr: None,
        },
    };
    let filter_fp = query.filters.fingerprint();
    let query_fingerprint =
        fingerprint_query(plan.match_expr.as_deref(), &filter_fp, TOKENIZER_VERSION);

    let use_fts = plan.match_expr.is_some();
    let cursor_rank = if let Some(cur) = cursor {
        cur.validate_against(&query_fingerprint, high_water_revision)?;
        cur.validated_sort_rank(use_fts)?
    } else {
        None
    };

    let limit = i64::from(page_size.get());
    // Fetch one extra row to detect a next page without offset scanning.
    let fetch = limit
        .checked_add(1)
        .ok_or_else(|| resource_limit("page_overflow", "page size overflow"))?;

    let (sql, bind_search) = build_sql(query, &plan, cursor.is_some())?;
    let mut stmt = connection.prepare(&sql).map_err(|err| from_sqlite(&err))?;
    let mut bindings = Vec::new();
    if let Some(tag) = query.filters.tag.as_deref() {
        bindings.push(Value::Text(tag.to_owned()));
    }
    if let Some(match_expr) = bind_search {
        bindings.push(Value::Text(match_expr));
    }
    if let Some(cur) = cursor {
        if use_fts {
            bindings.push(Value::Real(cursor_rank.ok_or_else(|| {
                validation("invalid_page_cursor", "FTS page cursor must include rank")
            })?));
        }
        bindings.push(Value::Integer(cur.sort_updated_at_ms));
        bindings.push(Value::Text(cur.sort_memo_id.clone()));
    }
    bindings.push(Value::Integer(fetch));
    let mut rows = stmt
        .query(params_from_iter(bindings.iter()))
        .map_err(|err| from_sqlite(&err))?;

    let mut items = Vec::new();
    while let Some(row) = rows.next().map_err(|err| from_sqlite(&err))? {
        let summary = MemoSummary {
            memo_id: row.get(0).map_err(|err| from_sqlite(&err))?,
            source_path: row.get(1).map_err(|err| from_sqlite(&err))?,
            file_fingerprint: row.get(2).map_err(|err| from_sqlite(&err))?,
            updated_at_ms: row.get(3).map_err(|err| from_sqlite(&err))?,
            created_at_ms: row.get(4).map_err(|err| from_sqlite(&err))?,
            has_todo: row.get::<_, i64>(5).map_err(|err| from_sqlite(&err))? != 0,
            has_url: row.get::<_, i64>(6).map_err(|err| from_sqlite(&err))? != 0,
            has_attachment: row.get::<_, i64>(7).map_err(|err| from_sqlite(&err))? != 0,
            is_pinned: row.get::<_, i64>(8).map_err(|err| from_sqlite(&err))? != 0,
            is_trashed: row.get::<_, i64>(9).map_err(|err| from_sqlite(&err))? != 0,
            body_preview: row.get(10).map_err(|err| from_sqlite(&err))?,
            content_revision: {
                let v: i64 = row.get(11).map_err(|err| from_sqlite(&err))?;
                u64::try_from(v).map_err(|_overflow| {
                    validation("invalid_content_revision", "content_revision out of u64")
                })?
            },
            rank: row
                .get::<_, Option<f64>>(12)
                .map_err(|err| from_sqlite(&err))?,
            tags: Vec::new(),
            image_urls: Vec::new(),
            reminders: serde_json::from_str(
                &row.get::<_, String>(13).map_err(|err| from_sqlite(&err))?,
            )
            .map_err(|error| corruption("invalid_reminder_projection", &error.to_string()))?,
        };
        items.push(summary);
    }

    let mut next_cursor = None;
    if i64::try_from(items.len()).unwrap_or(i64::MAX) > limit {
        items.pop();
        if let Some(last) = items.last() {
            next_cursor = Some(PageCursor::new(
                query_fingerprint.clone(),
                last.rank,
                last.updated_at_ms,
                last.memo_id.clone(),
                high_water_revision,
            ));
        }
    }

    attach_tags_and_images(connection, &mut items)?;

    Ok(MemoPage {
        items,
        next_cursor,
        high_water_revision,
        query_fingerprint,
    })
}

#[expect(
    clippy::cognitive_complexity,
    reason = "filter clause assembly is flat match arms"
)]
fn build_sql(
    query: &MemoQuery,
    plan: &QueryPlan,
    has_cursor: bool,
) -> Result<(String, Option<String>), lomo_core::LomoError> {
    let mut where_clauses = Vec::new();
    let f = &query.filters;

    if f.trash_only {
        where_clauses
            .push("EXISTS (SELECT 1 FROM memo_trash t WHERE t.memo_id = m.memo_id)".to_owned());
    } else if !f.include_trash {
        where_clauses
            .push("NOT EXISTS (SELECT 1 FROM memo_trash t WHERE t.memo_id = m.memo_id)".to_owned());
    }
    if f.pinned_only {
        where_clauses
            .push("EXISTS (SELECT 1 FROM memo_pin p WHERE p.memo_id = m.memo_id)".to_owned());
    }
    if f.has_todo == Some(true) {
        where_clauses.push("m.has_todo = 1".to_owned());
    } else if f.has_todo == Some(false) {
        where_clauses.push("m.has_todo = 0".to_owned());
    }
    if f.has_attachment == Some(true) {
        where_clauses.push("m.has_attachment = 1".to_owned());
    } else if f.has_attachment == Some(false) {
        where_clauses.push("m.has_attachment = 0".to_owned());
    }
    if f.has_url == Some(true) {
        where_clauses.push("m.has_url = 1".to_owned());
    } else if f.has_url == Some(false) {
        where_clauses.push("m.has_url = 0".to_owned());
    }
    if let Some(from) = f.date_from_ms {
        where_clauses.push(format!("m.updated_at_ms >= {from}"));
    }
    if let Some(to) = f.date_to_ms {
        where_clauses.push(format!("m.updated_at_ms <= {to}"));
    }
    if let Some(tag) = &f.tag {
        // Tag names are constrained; bind the value and keep subtree semantics at the query owner.
        if tag.is_empty() || tag.len() > 128 || tag.contains('\'') || tag.contains('\0') {
            return Err(validation("invalid_tag_filter", "tag filter is invalid"));
        }
        let predicate = if matches!(f.tag_selection, TagSelectionMode::Subtree) {
            "(tg.name = ?1 OR tg.name LIKE (?1 || '/%'))"
        } else {
            "tg.name = ?1"
        };
        where_clauses.push(format!(
            "EXISTS (SELECT 1 FROM memo_tag mt JOIN tag tg ON tg.id = mt.tag_id WHERE mt.memo_id = m.memo_id AND {predicate})"
        ));
    }

    let use_fts = plan.match_expr.is_some();
    let has_tag = f.tag.is_some();
    let search_idx = if has_tag { 2 } else { 1 };
    if use_fts {
        where_clauses.push(format!("memo_fts MATCH ?{search_idx}"));
    }

    if has_cursor {
        where_clauses.push(cursor_predicate(use_fts, search_idx));
    }

    let where_sql = if where_clauses.is_empty() {
        "1=1".to_owned()
    } else {
        where_clauses.join(" AND ")
    };

    let rank_select = if use_fts {
        "bm25(memo_fts) AS rank"
    } else {
        "NULL AS rank"
    };

    let from_sql = if use_fts {
        "memo m JOIN memo_fts ON memo_fts.rowid = m.rowid"
    } else {
        "memo m"
    };

    let order_sql = if use_fts {
        // bm25 lower is better; unique tie-breaker on (updated_at_ms, memo_id).
        "rank ASC, m.updated_at_ms DESC, m.memo_id DESC"
    } else {
        "m.updated_at_ms DESC, m.memo_id DESC"
    };

    let limit_idx = if use_fts {
        search_idx + if has_cursor { 4 } else { 1 }
    } else if has_cursor {
        search_idx + 2
    } else {
        search_idx
    };

    let sql = format!(
        "SELECT m.memo_id, m.source_path, m.file_fingerprint, m.updated_at_ms, m.created_at_ms, \
         m.has_todo, m.has_url, m.has_attachment, \
         EXISTS(SELECT 1 FROM memo_pin p WHERE p.memo_id = m.memo_id), \
         EXISTS(SELECT 1 FROM memo_trash t WHERE t.memo_id = m.memo_id), \
         m.body_preview, m.content_revision, {rank_select}, m.reminders_json \
         FROM {from_sql} \
         WHERE {where_sql} \
         ORDER BY {order_sql} \
         LIMIT ?{limit_idx}"
    );

    Ok((sql, plan.match_expr.clone()))
}

fn cursor_predicate(use_fts: bool, search_idx: usize) -> String {
    if use_fts {
        format!(
            "(bm25(memo_fts) > ?{} OR (bm25(memo_fts) = ?{} AND \
             (m.updated_at_ms < ?{} OR (m.updated_at_ms = ?{} AND m.memo_id < ?{}))))",
            search_idx + 1,
            search_idx + 1,
            search_idx + 2,
            search_idx + 2,
            search_idx + 3,
        )
    } else {
        format!(
            "(m.updated_at_ms < ?{} OR (m.updated_at_ms = ?{} AND m.memo_id < ?{}))",
            search_idx,
            search_idx,
            search_idx + 1,
        )
    }
}

/// Full memo snapshot for `get_memo` (list summary + Markdown body from the workspace file).
#[derive(Debug, Clone, PartialEq)]
pub struct MemoSnapshot {
    pub summary: MemoSummary,
    /// Markdown body read from `workspace_root` / `source_path`.
    pub body: String,
}

/// Loads one memo projection by id and reads its Markdown body from `workspace_root`.
///
/// # Errors
///
/// Storage errors from `SQLite` or body I/O. Returns `Ok(None)` when the memo is absent.
/// A present projection with an unreadable body fails closed (never a silent empty body).
pub fn get_memo(
    connection: &Connection,
    workspace_root: &Path,
    memo_id: &str,
) -> Result<Option<MemoSnapshot>, lomo_core::LomoError> {
    let Some(summary) = get_memo_projection(connection, memo_id)? else {
        return Ok(None);
    };
    let body_path = workspace_root.join(&summary.source_path);
    let body = match std::fs::read_to_string(&body_path) {
        Ok(body) => body,
        Err(error) if summary.is_trashed && error.kind() == std::io::ErrorKind::NotFound => {
            // A trashed memo is physically recoverable under the workspace trash root while its
            // projection retains the canonical memo source path. Reading that durable location
            // is the domain state; an unreadable non-trashed body remains fail-closed.
            let trash_path = workspace_root
                .join("trash")
                .join(format!("{}.md", summary.memo_id));
            std::fs::read_to_string(&trash_path).map_err(|trash_error| {
                storage(
                    "memo_body_read_failed",
                    &format!(
                        "cannot read trashed memo body {}: {trash_error}",
                        trash_path.display()
                    ),
                )
            })?
        }
        Err(error) => {
            return Err(storage(
                "memo_body_read_failed",
                &format!("cannot read memo body {}: {error}", body_path.display()),
            ));
        }
    };
    Ok(Some(MemoSnapshot { summary, body }))
}

/// Loads one memo projection by id without touching workspace user bytes.
///
/// # Errors
///
/// Returns `SQLite` failures. `Ok(None)` means the projection has no such memo.
pub fn get_memo_projection(
    connection: &Connection,
    memo_id: &str,
) -> Result<Option<MemoSummary>, lomo_core::LomoError> {
    let row = connection
        .query_row(
            "SELECT m.memo_id, m.source_path, m.file_fingerprint, m.updated_at_ms, m.created_at_ms, \
                    m.has_todo, m.has_url, m.has_attachment, \
                    CASE WHEN p.memo_id IS NULL THEN 0 ELSE 1 END, \
                    CASE WHEN t.memo_id IS NULL THEN 0 ELSE 1 END, \
                    m.body_preview, m.content_revision, m.reminders_json \
             FROM memo m \
             LEFT JOIN memo_pin p ON p.memo_id = m.memo_id \
             LEFT JOIN memo_trash t ON t.memo_id = m.memo_id \
             WHERE m.memo_id = ?1",
            params![memo_id],
            |row| {
                Ok(MemoSummary {
                    memo_id: row.get(0)?,
                    source_path: row.get(1)?,
                    file_fingerprint: row.get(2)?,
                    updated_at_ms: row.get(3)?,
                    created_at_ms: row.get(4)?,
                    has_todo: row.get::<_, i64>(5)? != 0,
                    has_url: row.get::<_, i64>(6)? != 0,
                    has_attachment: row.get::<_, i64>(7)? != 0,
                    is_pinned: row.get::<_, i64>(8)? != 0,
                    is_trashed: row.get::<_, i64>(9)? != 0,
                    body_preview: row.get(10)?,
                    content_revision: {
                        let rev: i64 = row.get(11)?;
                        u64::try_from(rev).unwrap_or(0)
                    },
                    rank: None,
                    tags: Vec::new(),
                    image_urls: Vec::new(),
                    reminders: serde_json::from_str(&row.get::<_, String>(12)?).map_err(|error| {
                        rusqlite::Error::FromSqlConversionFailure(
                            12,
                            rusqlite::types::Type::Text,
                            Box::new(error),
                        )
                    })?,
                })
            },
        )
        .optional()
        .map_err(|err| from_sqlite(&err))?;
    let Some(mut summary) = row else {
        return Ok(None);
    };
    let mut one = [summary];
    attach_tags_and_images(connection, &mut one)?;
    summary = one
        .into_iter()
        .next()
        .ok_or_else(|| validation("memo_projection_missing", "summary vanished after attach"))?;
    Ok(Some(summary))
}

/// Loads tag names and non-audio attachment paths for one bounded page in two statements.
fn attach_tags_and_images(
    connection: &Connection,
    items: &mut [MemoSummary],
) -> Result<(), lomo_core::LomoError> {
    if items.is_empty() {
        return Ok(());
    }
    let memo_ids = items
        .iter()
        .map(|item| Value::Text(item.memo_id.clone()))
        .collect::<Vec<_>>();
    let placeholders = std::iter::repeat_n("?", memo_ids.len())
        .collect::<Vec<_>>()
        .join(",");
    let mut tags_by_memo = items
        .iter()
        .map(|item| (item.memo_id.clone(), Vec::<String>::new()))
        .collect::<BTreeMap<_, _>>();
    let mut stmt = connection
        .prepare(&format!(
            "SELECT mt.memo_id, tg.name FROM memo_tag mt \
             JOIN tag tg ON tg.id = mt.tag_id \
             WHERE mt.memo_id IN ({placeholders}) \
             ORDER BY mt.memo_id, tg.name"
        ))
        .map_err(|err| from_sqlite(&err))?;
    let rows = stmt
        .query_map(params_from_iter(memo_ids.iter()), |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
        })
        .map_err(|err| from_sqlite(&err))?;
    for row in rows {
        let (memo_id, tag) = row.map_err(|err| from_sqlite(&err))?;
        let tags = tags_by_memo.get_mut(&memo_id).ok_or_else(|| {
            validation(
                "memo_tag_owner_missing",
                "bulk tag query returned an owner outside the requested page",
            )
        })?;
        tags.push(tag);
    }

    let mut images_by_memo = items
        .iter()
        .map(|item| (item.memo_id.clone(), Vec::<String>::new()))
        .collect::<BTreeMap<_, _>>();
    let mut stmt = connection
        .prepare(&format!(
            "SELECT memo_id, relative_path FROM attachment_ref \
             WHERE memo_id IN ({placeholders}) \
             ORDER BY memo_id, relative_path"
        ))
        .map_err(|err| from_sqlite(&err))?;
    let rows = stmt
        .query_map(params_from_iter(memo_ids.iter()), |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
        })
        .map_err(|err| from_sqlite(&err))?;
    for row in rows {
        let (memo_id, path) = row.map_err(|err| from_sqlite(&err))?;
        if !is_audio_target(&path) {
            let paths = images_by_memo.get_mut(&memo_id).ok_or_else(|| {
                validation(
                    "memo_attachment_owner_missing",
                    "bulk attachment query returned an owner outside the requested page",
                )
            })?;
            paths.push(path);
        }
    }
    for item in items {
        item.tags = tags_by_memo.remove(&item.memo_id).ok_or_else(|| {
            validation(
                "memo_tag_owner_missing",
                "requested page item was missing from the bulk tag projection",
            )
        })?;
        item.image_urls = images_by_memo.remove(&item.memo_id).ok_or_else(|| {
            validation(
                "memo_attachment_owner_missing",
                "requested page item was missing from the bulk attachment projection",
            )
        })?;
    }
    Ok(())
}

fn is_audio_target(target: &str) -> bool {
    Path::new(target)
        .extension()
        .and_then(|ext| ext.to_str())
        .is_some_and(|ext| {
            matches!(
                ext.to_ascii_lowercase().as_str(),
                "m4a" | "mp3" | "ogg" | "wav" | "aac"
            )
        })
}

/// Reads aggregate stats projection.
///
/// # Errors
///
/// Storage errors when the stats table is missing or unreadable.
pub fn query_stats(connection: &Connection) -> Result<StoreStats, lomo_core::LomoError> {
    let read = |key: &str| -> Result<i64, lomo_core::LomoError> {
        connection
            .query_row(
                "SELECT value_i64 FROM stats WHERE key = ?1",
                params![key],
                |row| row.get(0),
            )
            .optional()
            .map_err(|err| from_sqlite(&err))?
            .ok_or_else(|| validation("missing_stats_key", &format!("stats key missing: {key}")))
    };
    Ok(StoreStats {
        memo_count: read("memo_count")?,
        pinned_count: read("pinned_count")?,
        trashed_count: read("trashed_count")?,
        tag_count: read("tag_count")?,
    })
}

/// Reads the complete active sidebar projection without paging through memo bodies.
///
/// # Errors
///
/// Storage errors when the memo/tag projection is unreadable.
pub fn query_sidebar_projection(
    connection: &Connection,
) -> Result<SidebarProjection, lomo_core::LomoError> {
    let memo_count = connection
        .query_row(
            "SELECT COUNT(*) FROM memo m LEFT JOIN memo_trash t ON t.memo_id = m.memo_id \
             WHERE t.memo_id IS NULL",
            [],
            |row| row.get(0),
        )
        .map_err(|err| from_sqlite(&err))?;

    let mut date_statement = connection
        .prepare(
            "SELECT date(m.created_at_ms / 1000, 'unixepoch', 'localtime'), COUNT(*) \
             FROM memo m LEFT JOIN memo_trash t ON t.memo_id = m.memo_id \
             WHERE t.memo_id IS NULL GROUP BY 1 ORDER BY 1",
        )
        .map_err(|err| from_sqlite(&err))?;
    let date_rows = date_statement
        .query_map([], |row| {
            Ok(SidebarDateCount {
                date: row.get(0)?,
                count: row.get(1)?,
            })
        })
        .map_err(|err| from_sqlite(&err))?;
    let mut date_counts = Vec::new();
    for row in date_rows {
        date_counts.push(row.map_err(|err| from_sqlite(&err))?);
    }

    let mut tag_statement = connection
        .prepare(
            "SELECT tg.name, COUNT(*) FROM memo_tag mt \
             JOIN tag tg ON tg.id = mt.tag_id \
             LEFT JOIN memo_trash t ON t.memo_id = mt.memo_id \
             WHERE t.memo_id IS NULL GROUP BY tg.name ORDER BY COUNT(*) DESC, tg.name",
        )
        .map_err(|err| from_sqlite(&err))?;
    let tag_rows = tag_statement
        .query_map([], |row| {
            Ok(SidebarTagCount {
                name: row.get(0)?,
                count: row.get(1)?,
            })
        })
        .map_err(|err| from_sqlite(&err))?;
    let mut tag_counts = Vec::new();
    for row in tag_rows {
        tag_counts.push(row.map_err(|err| from_sqlite(&err))?);
    }

    Ok(SidebarProjection {
        schema_version: SIDEBAR_PROJECTION_SCHEMA,
        memo_count,
        date_counts,
        tag_counts,
    })
}

/// Recomputes stats from base tables (used after batch projection updates).
pub fn recompute_stats(connection: &Connection) -> Result<(), lomo_core::LomoError> {
    connection
        .execute_batch(
            r"
            UPDATE stats SET value_i64 = (SELECT COUNT(*) FROM memo) WHERE key = 'memo_count';
            UPDATE stats SET value_i64 = (SELECT COUNT(*) FROM memo_pin) WHERE key = 'pinned_count';
            UPDATE stats SET value_i64 = (SELECT COUNT(*) FROM memo_trash) WHERE key = 'trashed_count';
            UPDATE stats SET value_i64 = (SELECT COUNT(*) FROM tag) WHERE key = 'tag_count';
            ",
        )
        .map_err(|err| from_sqlite(&err))?;
    Ok(())
}
