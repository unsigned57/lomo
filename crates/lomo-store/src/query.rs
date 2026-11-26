//! `query_memos` + filters + keyset tie-breaker + hydration + stats.

use std::collections::{BTreeMap, BTreeSet};
use std::path::Path;

use rusqlite::functions::FunctionFlags;
use rusqlite::types::Value;
use rusqlite::{Connection, OptionalExtension, Row, params, params_from_iter};

use lomo_core::PageSize;
use lomo_workspace::{decode_trash_record, trash_record_relative_path};

use crate::content_facts::fingerprint_content;
use crate::cursor::{PageCursor, fingerprint_query};
use crate::error::{conflict, corruption, from_sqlite, storage, validation};
use crate::schema::TOKENIZER_VERSION;
use crate::search::collector::{RankedDocKey, review_month, review_random_key, review_time_zone};

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum TagSelectionMode {
    #[default]
    Exact,
    Subtree,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum MemoSortField {
    #[default]
    CreatedAt,
    UpdatedAt,
    ReviewMonth,
    ReviewRandom,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum SortDirection {
    Ascending,
    #[default]
    Descending,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemoSort {
    pub field: MemoSortField,
    pub direction: SortDirection,
    pub seed: i64,
    pub time_zone: String,
}

impl Default for MemoSort {
    fn default() -> Self {
        Self {
            field: MemoSortField::default(),
            direction: SortDirection::default(),
            seed: 0,
            time_zone: "UTC".to_owned(),
        }
    }
}

impl MemoSort {
    pub(crate) const fn is_review(&self) -> bool {
        matches!(
            self.field,
            MemoSortField::ReviewMonth | MemoSortField::ReviewRandom
        )
    }

    pub(crate) fn fingerprint(&self) -> String {
        match (self.field, self.direction) {
            (MemoSortField::CreatedAt, SortDirection::Ascending) => "created_at:asc".to_owned(),
            (MemoSortField::CreatedAt, SortDirection::Descending) => "created_at:desc".to_owned(),
            (MemoSortField::UpdatedAt, SortDirection::Ascending) => "updated_at:asc".to_owned(),
            (MemoSortField::UpdatedAt, SortDirection::Descending) => "updated_at:desc".to_owned(),
            (MemoSortField::ReviewMonth, direction) => {
                format!(
                    "review_month:{direction:?}:{}:{}",
                    self.seed, self.time_zone
                )
            }
            (MemoSortField::ReviewRandom, direction) => {
                format!("review_random:{direction:?}:{}", self.seed)
            }
        }
    }

    fn primary_value(&self, summary: &MemoSummary) -> Result<i64, lomo_core::LomoError> {
        match self.field {
            MemoSortField::CreatedAt => Ok(summary.created_at_ms),
            MemoSortField::UpdatedAt => Ok(summary.updated_at_ms),
            MemoSortField::ReviewMonth => {
                review_month(summary.created_at_ms, &review_time_zone(&self.time_zone)?)
            }
            MemoSortField::ReviewRandom => Ok(review_random_key(&summary.memo_id, self.seed)),
        }
    }

    fn secondary_value(&self, summary: &MemoSummary) -> i64 {
        if self.field == MemoSortField::ReviewMonth {
            review_random_key(&summary.memo_id, self.seed)
        } else {
            summary.created_at_ms
        }
    }

    const fn pinned_column(&self) -> &'static str {
        if self.is_review() {
            "CAST(0 AS INTEGER)"
        } else {
            "m.is_pinned"
        }
    }

    fn primary_column(&self) -> String {
        match self.field {
            MemoSortField::CreatedAt => "m.created_at_ms".to_owned(),
            MemoSortField::UpdatedAt => "m.updated_at_ms".to_owned(),
            MemoSortField::ReviewMonth => "lomo_review_month(m.created_at_ms)".to_owned(),
            MemoSortField::ReviewRandom => format!("lomo_review_key(m.memo_id, {})", self.seed),
        }
    }

    fn secondary_column(&self) -> String {
        if self.field == MemoSortField::ReviewMonth {
            format!("lomo_review_key(m.memo_id, {})", self.seed)
        } else {
            "m.created_at_ms".to_owned()
        }
    }
}

fn register_review_functions(
    connection: &Connection,
    sort: &MemoSort,
) -> Result<(), lomo_core::LomoError> {
    if !sort.is_review() {
        return Ok(());
    }
    let flags = FunctionFlags::SQLITE_UTF8 | FunctionFlags::SQLITE_DETERMINISTIC;
    connection
        .create_scalar_function("lomo_review_key", 2, flags, |context| {
            let id = context.get::<String>(0)?;
            let seed = context.get::<i64>(1)?;
            Ok(review_random_key(&id, seed))
        })
        .map_err(|error| from_sqlite(&error))?;
    let zone = review_time_zone(&sort.time_zone)?;
    connection
        .create_scalar_function("lomo_review_month", 1, flags, move |context| {
            review_month(context.get::<i64>(0)?, &zone)
                .map_err(|error| rusqlite::Error::UserFunctionError(Box::new(error)))
        })
        .map_err(|error| from_sqlite(&error))?;
    Ok(())
}

/// Filters applied in the store query layer (parity with Room capabilities).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct MemoFilters {
    pub tag: Option<String>,
    pub tag_selection: TagSelectionMode,
    pub date_from_inclusive_ms: Option<i64>,
    pub date_until_exclusive_ms: Option<i64>,
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
            self.date_from_inclusive_ms,
            self.date_until_exclusive_ms,
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
    pub sort: MemoSort,
}

/// Inclusive ordering bound used to freeze a candidate window.
///
/// For example, a Daily Review session can retain this tuple without materializing all memo ids.
/// The tuple is the same one used by keyset cursors, so the boundary and cursor share one ordering
/// law.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemoQueryBoundary {
    pub sort_pinned: bool,
    pub sort_primary_ms: i64,
    pub sort_created_at_ms: i64,
    pub sort_memo_id: String,
}

impl MemoQueryBoundary {
    pub(crate) fn validate(&self) -> Result<(), lomo_core::LomoError> {
        if self.sort_memo_id.trim().is_empty() || self.sort_memo_id.len() > 512 {
            return Err(validation(
                "invalid_query_boundary",
                "query boundary memo id must be non-empty and bounded",
            ));
        }
        Ok(())
    }

    pub(crate) fn fingerprint(&self) -> String {
        format!(
            "pin={}|primary={}|created={}|id={}",
            self.sort_pinned, self.sort_primary_ms, self.sort_created_at_ms, self.sort_memo_id
        )
    }
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
    pub time_part: String,
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
    /// Row was published by a begun create whose durable commit has not landed yet.
    pub is_pending: bool,
    /// Full-document character count materialized at rebuild (UTF-16 code units).
    pub char_count: i64,
}

/// Bounded page result.
#[derive(Debug, Clone, PartialEq)]
pub struct MemoPage {
    pub items: Vec<MemoSummary>,
    pub next_cursor: Option<PageCursor>,
    pub prev_cursor: Option<PageCursor>,
    /// Count of query rows that sort strictly ahead of the first item (0 when the page is empty
    /// at head, or the full query count when an exclusive-after page is past the last row).
    pub items_before: u64,
    /// Count of query rows that sort strictly after the last item.
    pub items_after: u64,
    pub high_water_revision: u64,
    pub query_fingerprint: String,
}

/// Bounded-window side evidence for a refresh merge.
///
/// The known ids that still match the query but rank strictly outside the
/// reply window — `above` sorts before its head bound, `below` after its
/// tail bound, and both are in live query order.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct MemoWindowSides {
    pub above: Vec<String>,
    pub below: Vec<String>,
}

/// Where a bounded memo page begins in the current query order.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MemoQueryStart<'a> {
    /// First row of the current query.
    Head,
    /// Exclusive keyset continuation after this publication-coupled cursor.
    After(&'a PageCursor),
    /// Exclusive keyset continuation before this publication-coupled cursor (prepend).
    Before(&'a PageCursor),
    /// Inclusive start at this memo identity, resolved against the current revision.
    AtMemo(&'a str),
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
    query_memos_with_boundary(
        connection,
        query,
        None,
        cursor,
        page_size,
        high_water_revision,
    )
}

/// Executes a bounded memo query with an inclusive ordering boundary.
///
/// # Errors
///
/// Returns validation for an invalid boundary or page size, `stale_cursor` for a cursor from a
/// different query snapshot, and storage/corruption errors from the projection database.
pub fn query_memos_with_boundary(
    connection: &Connection,
    query: &MemoQuery,
    boundary: Option<&MemoQueryBoundary>,
    cursor: Option<&PageCursor>,
    page_size: PageSize,
    high_water_revision: u64,
) -> Result<MemoPage, lomo_core::LomoError> {
    let start = cursor.map_or(MemoQueryStart::Head, MemoQueryStart::After);
    query_memos_starting_at(
        connection,
        query,
        boundary,
        start,
        page_size,
        high_water_revision,
    )
}

/// Executes a bounded memo query from an explicit start in the current query order.
///
/// Exclusive After/Before cursors stay coupled to one publication. `AtMemo` resolves the identity
/// against the current revision and starts inclusively there; a missing or filtered-out identity
/// falls back to [`MemoQueryStart::Head`].
///
/// # Errors
///
/// Returns validation for an invalid boundary or page size, `stale_cursor` for a cursor from a
/// different query snapshot, and storage/corruption errors from the projection database.
pub fn query_memos_starting_at(
    connection: &Connection,
    query: &MemoQuery,
    boundary: Option<&MemoQueryBoundary>,
    start: MemoQueryStart<'_>,
    page_size: PageSize,
    high_water_revision: u64,
) -> Result<MemoPage, lomo_core::LomoError> {
    reject_search_text(query)?;
    if let Some(boundary) = boundary {
        boundary.validate()?;
    }
    register_review_functions(connection, &query.sort)?;
    let query_fingerprint = fingerprint_for(query, boundary);
    let resolved = resolve_start(
        connection,
        query,
        boundary,
        start,
        &query_fingerprint,
        high_water_revision,
    )?;
    let mut items = query_summary_items(
        connection,
        query,
        boundary,
        &resolved,
        i64::from(page_size.get()),
    )?;

    attach_tags(connection, &mut items)?;
    let total = count_rows(connection, query, boundary, None)?;
    let (items_before, items_after) =
        page_ranks(connection, query, boundary, resolved.kind, &items, total)?;
    query_memo_page_tail(
        items,
        items_before,
        items_after,
        &query.sort,
        query_fingerprint,
        high_water_revision,
    )
}

pub fn query_memo_page_tail(
    items: Vec<MemoSummary>,
    items_before: u64,
    items_after: u64,
    sort: &MemoSort,
    query_fingerprint: String,
    high_water_revision: u64,
) -> Result<MemoPage, lomo_core::LomoError> {
    let prev_cursor = items
        .first()
        .filter(|_| items_before > 0)
        .map(|first| page_cursor_for(first, sort, query_fingerprint.clone(), high_water_revision))
        .transpose()?;
    let next_cursor = items
        .last()
        .filter(|_| items_after > 0)
        .map(|last| page_cursor_for(last, sort, query_fingerprint.clone(), high_water_revision))
        .transpose()?;

    Ok(MemoPage {
        items,
        next_cursor,
        prev_cursor,
        items_before,
        items_after,
        high_water_revision,
        query_fingerprint,
    })
}

pub fn query_memo_walk_page(
    connection: &Connection,
    query: &MemoQuery,
    cursor: Option<&PageCursor>,
    page_size: PageSize,
    revision: u64,
) -> Result<(Vec<MemoSummary>, Option<PageCursor>), lomo_core::LomoError> {
    reject_search_text(query)?;
    register_review_functions(connection, &query.sort)?;
    let fingerprint = fingerprint_for(query, None);
    let start = cursor.map_or(MemoQueryStart::Head, MemoQueryStart::After);
    let resolved = resolve_start(connection, query, None, start, &fingerprint, revision)?;
    let limit = i64::from(page_size.get());
    let mut items = query_summary_items(connection, query, None, &resolved, limit + 1)?;
    let next_cursor = if items.len()
        > usize::try_from(limit)
            .map_err(|error| validation("invalid_page_size", &error.to_string()))?
    {
        let _lookahead = items.pop();
        let last = items
            .last()
            .ok_or_else(|| corruption("invalid_memo_walk", "lookahead without a page"))?;
        Some(page_cursor_for(last, &query.sort, fingerprint, revision)?)
    } else {
        None
    };
    attach_tags(connection, &mut items)?;
    Ok((items, next_cursor))
}

/// Hydrates full SQLite `MemoSummary` projections for an ordered page of Tantivy `RankedDocKey`s.
pub fn hydrate_summaries_in_order(
    connection: &Connection,
    ranked: &[RankedDocKey],
    has_query: bool,
) -> Result<Vec<MemoSummary>, lomo_core::LomoError> {
    if ranked.is_empty() {
        return Ok(Vec::new());
    }
    let memo_ids: Vec<&str> = ranked.iter().map(|key| key.memo_id.as_str()).collect();
    let ids_json = serde_json::to_string(&memo_ids).map_err(|error| {
        corruption(
            "invalid_memo_id_batch",
            &format!("cannot encode Tantivy memo id batch: {error}"),
        )
    })?;
    let mut statement = connection
        .prepare(
            "SELECT m.memo_id, m.source_path, m.file_fingerprint, m.updated_at_ms, m.created_at_ms, \
             m.has_todo, m.has_url, m.has_attachment, \
             m.is_pinned, m.is_trashed, \
             m.body_preview, m.content_revision, NULL AS rank, m.reminders_json, \
             m.pending_operation_id IS NOT NULL, m.char_count, m.time_part, m.image_urls_json \
             FROM memo m \
             WHERE m.memo_id IN (SELECT value FROM json_each(?1))",
        )
        .map_err(|error| from_sqlite(&error))?;
    let rows = statement
        .query_map(params![ids_json], |row| Ok(memo_summary_from_row(row)))
        .map_err(|error| from_sqlite(&error))?;
    let mut by_id: BTreeMap<String, MemoSummary> = BTreeMap::new();
    for row in rows {
        let summary = row.map_err(|error| from_sqlite(&error))??;
        let _prev = by_id.insert(summary.memo_id.clone(), summary);
    }

    let mut ordered = Vec::with_capacity(ranked.len());
    for key in ranked {
        let mut summary = by_id.remove(&key.memo_id).ok_or_else(|| {
            conflict(
                "stale_snapshot",
                "search result disappeared from aligned SQLite snapshot",
            )
        })?;
        summary.rank = if has_query { key.rank } else { None };
        ordered.push(summary);
    }
    attach_tags(connection, &mut ordered)?;
    Ok(ordered)
}

fn query_summary_items(
    connection: &Connection,
    query: &MemoQuery,
    boundary: Option<&MemoQueryBoundary>,
    resolved: &ResolvedStart,
    limit: i64,
) -> Result<Vec<MemoSummary>, lomo_core::LomoError> {
    if let Some(cursor) = resolved.cursor.as_ref() {
        let _rank = cursor.validated_sort_rank(false)?;
    }

    let sql = build_sql(
        query,
        boundary,
        resolved
            .cursor
            .as_ref()
            .map(|cursor| (cursor, resolved.bound)),
        resolved.invert,
    )?;
    let mut stmt = connection.prepare(&sql).map_err(|err| from_sqlite(&err))?;
    let bindings = bind_query(query, boundary, resolved.cursor.as_ref(), Some(limit));
    let mut rows = stmt
        .query(params_from_iter(bindings.iter()))
        .map_err(|err| from_sqlite(&err))?;

    let mut items = Vec::new();
    while let Some(row) = rows.next().map_err(|err| from_sqlite(&err))? {
        items.push(memo_summary_from_row(row)?);
    }
    if resolved.invert {
        items.reverse();
    }

    Ok(items)
}

#[derive(Clone, Copy)]
enum CursorBound {
    ExclusiveAfter,
    ExclusiveBefore,
    InclusiveAfter,
}

#[derive(Clone, Copy)]
enum ResolvedKind {
    Head,
    After,
    Before,
    AtMemo,
}

struct ResolvedStart {
    cursor: Option<PageCursor>,
    bound: CursorBound,
    invert: bool,
    kind: ResolvedKind,
}

fn reject_search_text(query: &MemoQuery) -> Result<(), lomo_core::LomoError> {
    if query
        .search_text
        .as_deref()
        .is_some_and(|text| !text.trim().is_empty())
    {
        return Err(validation(
            "search_requires_store",
            "search_text queries must run through Store or StoreReader",
        ));
    }
    Ok(())
}

fn fingerprint_for(query: &MemoQuery, boundary: Option<&MemoQueryBoundary>) -> String {
    let filter_fp = format!(
        "{}|sort={}|boundary={}",
        query.filters.fingerprint(),
        query.sort.fingerprint(),
        boundary.map_or_else(|| "none".to_owned(), MemoQueryBoundary::fingerprint)
    );
    fingerprint_query(None, &filter_fp, TOKENIZER_VERSION)
}

fn page_cursor_for(
    summary: &MemoSummary,
    sort: &MemoSort,
    query_fingerprint: String,
    high_water_revision: u64,
) -> Result<PageCursor, lomo_core::LomoError> {
    Ok(PageCursor::new(
        query_fingerprint,
        summary.rank,
        summary.is_pinned && !sort.is_review(),
        sort.primary_value(summary)?,
        sort.secondary_value(summary),
        summary.memo_id.clone(),
        high_water_revision,
    ))
}

fn resolve_start(
    connection: &Connection,
    query: &MemoQuery,
    boundary: Option<&MemoQueryBoundary>,
    start: MemoQueryStart<'_>,
    query_fingerprint: &str,
    high_water_revision: u64,
) -> Result<ResolvedStart, lomo_core::LomoError> {
    match start {
        MemoQueryStart::Head => Ok(ResolvedStart {
            cursor: None,
            bound: CursorBound::ExclusiveAfter,
            invert: false,
            kind: ResolvedKind::Head,
        }),
        MemoQueryStart::After(cursor) => {
            cursor.validate_against(query_fingerprint, high_water_revision)?;
            Ok(ResolvedStart {
                cursor: Some(cursor.clone()),
                bound: CursorBound::ExclusiveAfter,
                invert: false,
                kind: ResolvedKind::After,
            })
        }
        MemoQueryStart::Before(cursor) => {
            cursor.validate_against(query_fingerprint, high_water_revision)?;
            Ok(ResolvedStart {
                cursor: Some(cursor.clone()),
                bound: CursorBound::ExclusiveBefore,
                invert: true,
                kind: ResolvedKind::Before,
            })
        }
        MemoQueryStart::AtMemo(memo_id) => {
            let Some(summary) = start_summary(connection, query, boundary, memo_id)? else {
                return Ok(ResolvedStart {
                    cursor: None,
                    bound: CursorBound::ExclusiveAfter,
                    invert: false,
                    kind: ResolvedKind::Head,
                });
            };
            Ok(ResolvedStart {
                cursor: Some(page_cursor_for(
                    &summary,
                    &query.sort,
                    query_fingerprint.to_owned(),
                    high_water_revision,
                )?),
                bound: CursorBound::InclusiveAfter,
                invert: false,
                kind: ResolvedKind::AtMemo,
            })
        }
    }
}

fn start_summary(
    connection: &Connection,
    query: &MemoQuery,
    boundary: Option<&MemoQueryBoundary>,
    memo_id: &str,
) -> Result<Option<MemoSummary>, lomo_core::LomoError> {
    let PredicateSql {
        where_sql,
        first_cursor_index,
    } = predicate_sql(query, boundary, None)?;
    let memo_idx = first_cursor_index;
    let sql = format!(
        "SELECT m.memo_id, m.source_path, m.file_fingerprint, m.updated_at_ms, m.created_at_ms, \
         m.has_todo, m.has_url, m.has_attachment, \
         m.is_pinned, m.is_trashed, \
         m.body_preview, m.content_revision, NULL AS rank, m.reminders_json, \
         m.pending_operation_id IS NOT NULL, m.char_count, m.time_part, m.image_urls_json \
         FROM memo m \
         WHERE {where_sql} AND m.memo_id = ?{memo_idx} \
         LIMIT 1"
    );
    let mut statement = connection.prepare(&sql).map_err(|err| from_sqlite(&err))?;
    let mut bindings = bind_query(query, boundary, None, None);
    bindings.push(Value::Text(memo_id.to_owned()));
    let mut rows = statement
        .query(params_from_iter(bindings.iter()))
        .map_err(|err| from_sqlite(&err))?;
    let Some(row) = rows.next().map_err(|err| from_sqlite(&err))? else {
        return Ok(None);
    };
    Ok(Some(memo_summary_from_row(row)?))
}

fn bind_query(
    query: &MemoQuery,
    boundary: Option<&MemoQueryBoundary>,
    cursor: Option<&PageCursor>,
    limit: Option<i64>,
) -> Vec<Value> {
    let mut bindings = Vec::new();
    if let Some(tag) = query.filters.tag.as_deref() {
        bindings.push(Value::Text(tag.to_owned()));
    }
    if let Some(boundary) = boundary {
        bindings.push(Value::Integer(i64::from(boundary.sort_pinned)));
        bindings.push(Value::Integer(boundary.sort_primary_ms));
        bindings.push(Value::Integer(boundary.sort_created_at_ms));
        bindings.push(Value::Text(boundary.sort_memo_id.clone()));
    }
    if let Some(cursor) = cursor {
        bindings.push(Value::Integer(i64::from(cursor.sort_pinned)));
        bindings.push(Value::Integer(cursor.sort_primary_ms));
        bindings.push(Value::Integer(cursor.sort_created_at_ms));
        bindings.push(Value::Text(cursor.sort_memo_id.clone()));
    }
    if let Some(limit) = limit {
        bindings.push(Value::Integer(limit));
    }
    bindings
}

fn page_ranks(
    connection: &Connection,
    query: &MemoQuery,
    boundary: Option<&MemoQueryBoundary>,
    kind: ResolvedKind,
    items: &[MemoSummary],
    total: u64,
) -> Result<(u64, u64), lomo_core::LomoError> {
    match (kind, items.first()) {
        (_, Some(first)) => {
            let before = if matches!(kind, ResolvedKind::Head) {
                0
            } else {
                count_rows(
                    connection,
                    query,
                    boundary,
                    Some((first, CursorBound::ExclusiveBefore)),
                )?
            };
            let after = total
                .checked_sub(before)
                .and_then(|rest| rest.checked_sub(items.len() as u64))
                .ok_or_else(|| {
                    corruption(
                        "inconsistent_page_rank",
                        "page rank exceeded the query count for the same snapshot",
                    )
                })?;
            Ok((before, after))
        }
        (ResolvedKind::After, None) => Ok((total, 0)),
        (ResolvedKind::Before, None) => Ok((0, total)),
        (_, None) => Ok((0, 0)),
    }
}

fn count_rows(
    connection: &Connection,
    query: &MemoQuery,
    boundary: Option<&MemoQueryBoundary>,
    rank_of: Option<(&MemoSummary, CursorBound)>,
) -> Result<u64, lomo_core::LomoError> {
    if boundary.is_none() && rank_of.is_none() {
        let mut unfiltered = query.filters.clone();
        unfiltered.include_trash = false;
        unfiltered.trash_only = false;
        if unfiltered == MemoFilters::default() {
            let stats = query_stats(connection)?;
            let count = if query.filters.trash_only {
                stats.trashed_count
            } else if query.filters.include_trash {
                stats.memo_count
            } else {
                stats.memo_count - stats.trashed_count
            };
            return u64::try_from(count)
                .map_err(|_overflow| corruption("invalid_memo_count", "negative aggregate count"));
        }
    }
    let cursor = rank_of
        .map(|(summary, bound)| {
            page_cursor_for(summary, &query.sort, String::new(), 0).map(|cursor| (cursor, bound))
        })
        .transpose()?;
    let PredicateSql { where_sql, .. } =
        predicate_sql(query, boundary, rank_of.map(|(_, bound)| bound))?;
    let sql = format!("SELECT COUNT(*) FROM memo m WHERE {where_sql}");
    let mut statement = connection.prepare(&sql).map_err(|err| from_sqlite(&err))?;
    let bindings = bind_query(
        query,
        boundary,
        cursor.as_ref().map(|(cursor, _)| cursor),
        None,
    );
    let count: i64 = statement
        .query_row(params_from_iter(bindings.iter()), |row| row.get(0))
        .map_err(|err| from_sqlite(&err))?;
    u64::try_from(count).map_err(|_overflow| {
        corruption(
            "negative_query_count",
            "SQLite returned a negative row count for a COUNT query",
        )
    })
}

fn memo_summary_from_row(row: &Row<'_>) -> Result<MemoSummary, lomo_core::LomoError> {
    let content_revision = row.get::<_, i64>(11).map_err(|err| from_sqlite(&err))?;
    Ok(MemoSummary {
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
        content_revision: u64::try_from(content_revision).map_err(|_overflow| {
            validation("invalid_content_revision", "content_revision out of u64")
        })?,
        rank: row
            .get::<_, Option<f64>>(12)
            .map_err(|err| from_sqlite(&err))?,
        tags: Vec::new(),
        image_urls: serde_json::from_str(
            &row.get::<_, String>(17).map_err(|err| from_sqlite(&err))?,
        )
        .map_err(|error| corruption("invalid_image_urls_projection", &error.to_string()))?,
        reminders: serde_json::from_str(
            &row.get::<_, String>(13).map_err(|err| from_sqlite(&err))?,
        )
        .map_err(|error| corruption("invalid_reminder_projection", &error.to_string()))?,
        is_pending: row.get::<_, i64>(14).map_err(|err| from_sqlite(&err))? != 0,
        char_count: row.get(15).map_err(|err| from_sqlite(&err))?,
        time_part: row.get(16).map_err(|err| from_sqlite(&err))?,
    })
}

fn build_sql(
    query: &MemoQuery,
    boundary: Option<&MemoQueryBoundary>,
    cursor: Option<(&PageCursor, CursorBound)>,
    invert: bool,
) -> Result<String, lomo_core::LomoError> {
    let bound = cursor.map(|(_, bound)| bound);
    let PredicateSql {
        where_sql,
        first_cursor_index,
    } = predicate_sql(query, boundary, bound)?;

    let cursor_binding_count = if bound.is_some() { 4 } else { 0 };
    let limit_idx = first_cursor_index + cursor_binding_count;
    let order_sql = order_sql(&query.sort, invert);

    Ok(format!(
        "SELECT m.memo_id, m.source_path, m.file_fingerprint, m.updated_at_ms, m.created_at_ms, \
         m.has_todo, m.has_url, m.has_attachment, \
         m.is_pinned, m.is_trashed, \
         m.body_preview, m.content_revision, NULL AS rank, m.reminders_json, \
         m.pending_operation_id IS NOT NULL, m.char_count, m.time_part, m.image_urls_json \
         FROM memo m \
         WHERE {where_sql} \
         ORDER BY {order_sql} \
         LIMIT ?{limit_idx}"
    ))
}

/// Shared row predicate for non-search `query_memos` and `query_count`.
struct PredicateSql {
    where_sql: String,
    /// Bind index where cursor bindings start (and where `LIMIT` goes when no cursor is bound).
    first_cursor_index: usize,
}

fn predicate_sql(
    query: &MemoQuery,
    boundary: Option<&MemoQueryBoundary>,
    cursor: Option<CursorBound>,
) -> Result<PredicateSql, lomo_core::LomoError> {
    let f = &query.filters;
    let mut where_clauses = filter_clauses(f)?;

    let first_dynamic_index = if f.tag.is_some() { 2 } else { 1 };
    if boundary.is_some() {
        where_clauses.push(boundary_predicate(&query.sort, first_dynamic_index));
    }
    let first_cursor_index = first_dynamic_index + usize::from(boundary.is_some()) * 4;
    if let Some(bound) = cursor {
        where_clauses.push(cursor_predicate(&query.sort, first_cursor_index, bound));
    }

    let where_sql = if where_clauses.is_empty() {
        "1=1".to_owned()
    } else {
        where_clauses.join(" AND ")
    };

    Ok(PredicateSql {
        where_sql,
        first_cursor_index,
    })
}

fn filter_clauses(filters: &MemoFilters) -> Result<Vec<String>, lomo_core::LomoError> {
    let mut clauses = Vec::new();
    if filters.trash_only {
        clauses.push("m.is_trashed = 1".to_owned());
    } else if !filters.include_trash {
        clauses.push("m.is_trashed = 0".to_owned());
    }
    if filters.pinned_only {
        clauses.push("m.is_pinned = 1".to_owned());
    }
    push_boolean_clause(&mut clauses, "m.has_todo", filters.has_todo);
    push_boolean_clause(&mut clauses, "m.has_attachment", filters.has_attachment);
    push_boolean_clause(&mut clauses, "m.has_url", filters.has_url);
    if let Some(from) = filters.date_from_inclusive_ms {
        clauses.push(format!("m.created_at_ms >= {from}"));
    }
    if let Some(until) = filters.date_until_exclusive_ms {
        clauses.push(format!("m.created_at_ms < {until}"));
    }
    if let Some(tag) = &filters.tag {
        if tag.is_empty() || tag.len() > 128 || tag.contains('\'') || tag.contains('\0') {
            return Err(validation("invalid_tag_filter", "tag filter is invalid"));
        }
        let predicate = if matches!(filters.tag_selection, TagSelectionMode::Subtree) {
            "(tg.name = ?1 OR tg.name LIKE (?1 || '/%'))"
        } else {
            "tg.name = ?1"
        };
        clauses.push(format!(
            "EXISTS (SELECT 1 FROM memo_tag mt JOIN tag tg ON tg.id = mt.tag_id WHERE mt.memo_id = m.memo_id AND {predicate})"
        ));
    }
    Ok(clauses)
}

fn push_boolean_clause(clauses: &mut Vec<String>, column: &str, value: Option<bool>) {
    if let Some(value) = value {
        clauses.push(format!("{column} = {}", u8::from(value)));
    }
}

fn order_sql(sort: &MemoSort, invert: bool) -> String {
    let primary_column = sort.primary_column();
    let secondary_column = sort.secondary_column();
    let pinned = sort.pinned_column();
    let direction = match (sort.direction, invert) {
        (SortDirection::Ascending, false) | (SortDirection::Descending, true) => "ASC",
        (SortDirection::Descending, false) | (SortDirection::Ascending, true) => "DESC",
    };
    let pin_direction = if invert { "ASC" } else { "DESC" };
    format!(
        "({pinned}) {pin_direction}, {primary_column} {direction}, {secondary_column} {direction}, m.memo_id {direction}"
    )
}

fn cursor_predicate(sort: &MemoSort, first_cursor_idx: usize, bound: CursorBound) -> String {
    let pin_idx = first_cursor_idx;
    let primary_idx = pin_idx + 1;
    let created_idx = primary_idx + 1;
    let memo_id_idx = created_idx + 1;
    let pin_cmp = match bound {
        CursorBound::ExclusiveBefore => ">",
        CursorBound::ExclusiveAfter | CursorBound::InclusiveAfter => "<",
    };
    let (temporal_cmp, id_cmp) = match (sort.direction, bound) {
        (SortDirection::Ascending, CursorBound::ExclusiveAfter)
        | (SortDirection::Descending, CursorBound::ExclusiveBefore) => (">", ">"),
        (SortDirection::Descending, CursorBound::ExclusiveAfter)
        | (SortDirection::Ascending, CursorBound::ExclusiveBefore) => ("<", "<"),
        (SortDirection::Ascending, CursorBound::InclusiveAfter) => (">", ">="),
        (SortDirection::Descending, CursorBound::InclusiveAfter) => ("<", "<="),
    };
    let primary_column = sort.primary_column();
    let secondary_column = sort.secondary_column();
    let pinned = sort.pinned_column();
    // Descending feed order is one index tuple. Expanding it into nested ORs
    // makes SQLite visit the entire active partition even for a rank-zero memo.
    if !sort.is_review() && sort.direction == SortDirection::Descending {
        let comparison = match bound {
            CursorBound::ExclusiveBefore => ">",
            CursorBound::ExclusiveAfter => "<",
            CursorBound::InclusiveAfter => "<=",
        };
        if sort.field == MemoSortField::CreatedAt {
            return format!(
                "(m.is_pinned, m.created_at_ms, m.memo_id) {comparison} (?{pin_idx}, ?{primary_idx}, ?{memo_id_idx})"
            );
        }
        return format!(
            "(m.is_pinned, m.updated_at_ms, m.created_at_ms, m.memo_id) {comparison} (?{pin_idx}, ?{primary_idx}, ?{created_idx}, ?{memo_id_idx})"
        );
    }
    format!(
        "({pinned} {pin_cmp} ?{pin_idx} OR ({pinned} = ?{pin_idx} AND \
         ({primary_column} {temporal_cmp} ?{primary_idx} OR \
          ({primary_column} = ?{primary_idx} AND \
           ({secondary_column} {temporal_cmp} ?{created_idx} OR \
            ({secondary_column} = ?{created_idx} AND m.memo_id {id_cmp} ?{memo_id_idx}))))))"
    )
}

/// Inclusive lexicographic predicate for a captured ordering boundary. Pin state is always the
/// leading descending key; temporal keys follow the selected sort direction.
fn boundary_predicate(sort: &MemoSort, first_idx: usize) -> String {
    let primary_idx = first_idx + 1;
    let created_idx = first_idx + 2;
    let memo_id_idx = first_idx + 3;
    let comparison = match sort.direction {
        SortDirection::Ascending => ">",
        SortDirection::Descending => "<",
    };
    let inclusive = match sort.direction {
        SortDirection::Ascending => ">=",
        SortDirection::Descending => "<=",
    };
    let primary_column = sort.primary_column();
    let secondary_column = sort.secondary_column();
    let pinned = sort.pinned_column();
    format!(
        "({pinned} < ?{first_idx} OR ({pinned} = ?{first_idx} AND \
         ({primary_column} {comparison} ?{primary_idx} OR \
          ({primary_column} = ?{primary_idx} AND \
           ({secondary_column} {comparison} ?{created_idx} OR \
            ({secondary_column} = ?{created_idx} AND m.memo_id {inclusive} ?{memo_id_idx}))))))"
    )
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
    let body = if summary.is_trashed
        && trash_record_relative_path(&summary.memo_id, &summary.source_path)
            .is_ok_and(|relative| workspace_root.join(relative.as_str()).is_file())
    {
        // The checksummed workspace trash record is the durable authority.  Never prefer a stale
        // or externally edited `trash/{id}.md` optimization while the record is present.
        read_trash_record_body(workspace_root, &summary)?
    } else {
        match std::fs::read_to_string(&body_path) {
            Ok(body) => body,
            Err(error) if summary.is_trashed && error.kind() == std::io::ErrorKind::NotFound => {
                // Legacy Direct workspaces may still have only the physical trash optimization;
                // use it until the next successful delete emits the canonical record.
                let trash_path = workspace_root
                    .join(lomo_workspace::LEGACY_TRASH_ROOT_NAME)
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
        }
    };
    Ok(Some(MemoSnapshot { summary, body }))
}

fn read_trash_record_body(
    workspace_root: &Path,
    summary: &MemoSummary,
) -> Result<String, lomo_core::LomoError> {
    let relative = trash_record_relative_path(&summary.memo_id, &summary.source_path)?;
    let path = workspace_root.join(relative.as_str());
    let bytes = std::fs::read(&path).map_err(|error| {
        storage(
            "memo_body_read_failed",
            &format!(
                "cannot read durable trash record {}: {error}",
                path.display()
            ),
        )
    })?;
    let record = decode_trash_record(&bytes)?;
    let identity_matches = record.memo_id == summary.memo_id;
    let projection_fingerprint_matches = record.source_fingerprint == summary.file_fingerprint;
    let body_fingerprint_matches = fingerprint_content(&record.body) == record.source_fingerprint;
    if !identity_matches || !projection_fingerprint_matches || !body_fingerprint_matches {
        return Err(corruption(
            "trash_record_projection_mismatch",
            "durable trash record does not match the projected memo snapshot",
        ));
    }
    Ok(record.body)
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
    let mut statement = connection
        .prepare(
            "SELECT m.memo_id, m.source_path, m.file_fingerprint, m.updated_at_ms, m.created_at_ms, \
                    m.has_todo, m.has_url, m.has_attachment, \
                    m.is_pinned, m.is_trashed, \
                    m.body_preview, m.content_revision, NULL AS rank, m.reminders_json, \
                    m.pending_operation_id IS NOT NULL, m.char_count, m.time_part, m.image_urls_json \
             FROM memo m \
             WHERE m.memo_id = ?1",
        )
        .map_err(|err| from_sqlite(&err))?;
    let mut rows = statement
        .query(params![memo_id])
        .map_err(|err| from_sqlite(&err))?;
    let Some(row) = rows.next().map_err(|err| from_sqlite(&err))? else {
        return Ok(None);
    };
    let mut summaries = [memo_summary_from_row(row)?];
    attach_tags(connection, &mut summaries)?;
    let summary = summaries
        .into_iter()
        .next()
        .ok_or_else(|| validation("memo_projection_missing", "summary vanished after attach"))?;
    Ok(Some(summary))
}

/// Loads one complete memo snapshot entirely from the app-private projection.
///
/// This is the SAF read path: user bytes cannot be reached through a filesystem path, so the
/// verified scan must have published the body in the same `SQLite` row as its summary. A migrated v3
/// row has `NULL` here and is rejected until a verified rebuild replaces it.
///
/// # Errors
///
/// Returns `SQLite` failures or `projection_rebuild_required` for a legacy/incomplete projection.
pub fn get_projected_memo(
    connection: &Connection,
    memo_id: &str,
) -> Result<Option<MemoSnapshot>, lomo_core::LomoError> {
    Ok(get_projected_memos(connection, &[memo_id.to_owned()])?
        .into_iter()
        .next())
}

/// One SQLite variable-binding page for `IN (...)` memo-id sets.
const MAX_BOUND_MEMO_IDS: usize = 256;

pub fn visit_projected_bodies(
    connection: &Connection,
    memo_ids: &[&str],
    mut visit: impl FnMut(&str, &str) -> Result<(), lomo_core::LomoError>,
) -> Result<(), lomo_core::LomoError> {
    if !(1..=MAX_BOUND_MEMO_IDS).contains(&memo_ids.len()) {
        return Err(validation(
            "invalid_projected_body_batch",
            "body reads require one nonempty binding page",
        ));
    }
    let placeholders = std::iter::repeat_n("?", memo_ids.len())
        .collect::<Vec<_>>()
        .join(",");
    let mut statement = connection
        .prepare(&format!(
            "SELECT memo_id, body FROM memo WHERE memo_id IN ({placeholders})"
        ))
        .map_err(|error| from_sqlite(&error))?;
    let mut rows = statement
        .query(params_from_iter(memo_ids))
        .map_err(|error| from_sqlite(&error))?;
    let mut found = 0;
    while let Some(row) = rows.next().map_err(|error| from_sqlite(&error))? {
        let id = row
            .get_ref(0)
            .map_err(|error| from_sqlite(&error))?
            .as_str()
            .map_err(|error| corruption("invalid_projection_memo_id", &error.to_string()))?;
        let body = row.get_ref(1).map_err(|error| from_sqlite(&error))?.as_str_or_null()
            .map_err(|error| corruption("invalid_projection_memo_body", &error.to_string()))?
            .ok_or_else(|| {
                validation(
                    "projection_rebuild_required",
                    "memo body is absent from the durable projection; a verified rebuild is required",
                )
            })?;
        visit(id, body)?;
        found += 1;
    }
    if found != memo_ids.len() {
        return Err(validation("memo_not_found", "search candidate disappeared"));
    }
    Ok(())
}

/// Loads complete memo snapshots for a candidate set in bounded batched statements.
///
/// Same projection contract as [`get_projected_memo`], but one statement pair per
/// `MAX_BOUND_MEMO_IDS` ids instead of per memo, so a search page or fuzzy candidate set
/// hydrates inside a constant number of reads. Unknown ids simply yield no row.
///
/// # Errors
///
/// Returns `SQLite` failures or `projection_rebuild_required` for a legacy/incomplete projection.
pub fn get_projected_memos(
    connection: &Connection,
    memo_ids: &[String],
) -> Result<Vec<MemoSnapshot>, lomo_core::LomoError> {
    if memo_ids.is_empty() {
        return Ok(Vec::new());
    }
    let mut snapshots = Vec::with_capacity(memo_ids.len());
    for chunk in memo_ids.chunks(MAX_BOUND_MEMO_IDS) {
        let placeholders = std::iter::repeat_n("?", chunk.len())
            .collect::<Vec<_>>()
            .join(",");
        let bindings = chunk
            .iter()
            .map(|id| Value::Text(id.clone()))
            .collect::<Vec<_>>();
        let mut statement = connection
            .prepare(&format!(
                "SELECT m.memo_id, m.source_path, m.file_fingerprint, m.updated_at_ms, \
                 m.created_at_ms, m.has_todo, m.has_url, m.has_attachment, \
                 m.is_pinned, m.is_trashed, \
                 m.body_preview, m.content_revision, NULL AS rank, m.reminders_json, \
                 m.pending_operation_id IS NOT NULL, m.char_count, m.time_part, \
                 m.image_urls_json, m.body \
                 FROM memo m WHERE m.memo_id IN ({placeholders})"
            ))
            .map_err(|err| from_sqlite(&err))?;
        let mut summaries = Vec::new();
        let mut bodies = Vec::new();
        {
            let mut rows = statement
                .query(params_from_iter(bindings.iter()))
                .map_err(|err| from_sqlite(&err))?;
            while let Some(row) = rows.next().map_err(|err| from_sqlite(&err))? {
                summaries.push(memo_summary_from_row(row)?);
                bodies.push(
                    row.get::<_, Option<String>>(18)
                        .map_err(|err| from_sqlite(&err))?,
                );
            }
        }
        attach_tags(connection, &mut summaries)?;
        for (summary, body) in summaries.into_iter().zip(bodies) {
            let body = body.ok_or_else(|| {
                validation(
                    "projection_rebuild_required",
                    "memo body is absent from the durable projection; a verified rebuild is required",
                )
            })?;
            snapshots.push(MemoSnapshot { summary, body });
        }
    }
    Ok(snapshots)
}

/// Minimal preview-render source row for batched preview rendering (no full body, no tags/images).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemoPreviewSource {
    pub memo_id: String,
    pub content_revision: u64,
    pub body_preview: String,
}

/// Reads `(memo_id, content_revision, body_preview)` for the requested memo ids in bounded statements.
///
/// # Errors
///
/// Returns `SQLite` storage failures or `invalid_content_revision` corruption if a stored revision
/// is negative.
pub fn get_memo_preview_sources(
    connection: &Connection,
    memo_ids: &[String],
) -> Result<BTreeMap<String, MemoPreviewSource>, lomo_core::LomoError> {
    let unique_ids: Vec<String> = memo_ids
        .iter()
        .filter(|id| !id.trim().is_empty())
        .cloned()
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect();
    if unique_ids.is_empty() {
        return Ok(BTreeMap::new());
    }
    let mut sources = BTreeMap::new();
    for chunk in unique_ids.chunks(MAX_BOUND_MEMO_IDS) {
        let placeholders = std::iter::repeat_n("?", chunk.len())
            .collect::<Vec<_>>()
            .join(",");
        let bindings = chunk
            .iter()
            .map(|id| Value::Text(id.clone()))
            .collect::<Vec<_>>();
        let mut statement = connection
            .prepare(&format!(
                "SELECT memo_id, content_revision, body_preview \
                 FROM memo WHERE memo_id IN ({placeholders})"
            ))
            .map_err(|err| from_sqlite(&err))?;
        let mut rows = statement
            .query(params_from_iter(bindings.iter()))
            .map_err(|err| from_sqlite(&err))?;
        while let Some(row) = rows.next().map_err(|err| from_sqlite(&err))? {
            let memo_id: String = row.get(0).map_err(|err| from_sqlite(&err))?;
            let raw_revision: i64 = row.get(1).map_err(|err| from_sqlite(&err))?;
            let content_revision = u64::try_from(raw_revision).map_err(|_overflow| {
                corruption(
                    "invalid_content_revision",
                    "content_revision must be non-negative",
                )
            })?;
            let body_preview: String = row.get(2).map_err(|err| from_sqlite(&err))?;
            sources.insert(
                memo_id.clone(),
                MemoPreviewSource {
                    memo_id,
                    content_revision,
                    body_preview,
                },
            );
        }
    }
    Ok(sources)
}

/// Minimal materialized gallery row (identity, timestamps, ordered image paths; no body/tags/reminders).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GalleryRow {
    pub memo_id: String,
    pub source_path: String,
    pub time_part: String,
    pub created_at_ms: i64,
    pub updated_at_ms: i64,
    pub is_pinned: bool,
    pub content_revision: u64,
    pub file_fingerprint: String,
    pub image_urls: Vec<String>,
}

/// Bounded lightweight gallery page backed by `idx_memo_gallery` (no `COUNT(*)`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GalleryPage {
    pub items: Vec<GalleryRow>,
    pub next_cursor: Option<PageCursor>,
    pub prev_cursor: Option<PageCursor>,
    pub high_water_revision: u64,
    pub query_fingerprint: String,
}

const GALLERY_FILTER_PREDICATE: &str =
    "m.is_trashed = 0 AND m.has_attachment = 1 AND m.image_urls_json <> '[]'";

fn gallery_cursor_for(
    row: &GalleryRow,
    query_fingerprint: String,
    high_water_revision: u64,
) -> PageCursor {
    PageCursor::new(
        query_fingerprint,
        None,
        row.is_pinned,
        row.created_at_ms,
        row.created_at_ms,
        row.memo_id.clone(),
        high_water_revision,
    )
}

fn gallery_row_from_sql(row: &Row<'_>) -> Result<GalleryRow, lomo_core::LomoError> {
    let raw_revision: i64 = row.get(6).map_err(|err| from_sqlite(&err))?;
    let content_revision = u64::try_from(raw_revision).map_err(|_overflow| {
        corruption(
            "invalid_content_revision",
            "content_revision must be non-negative",
        )
    })?;
    let image_urls_json: String = row.get(8).map_err(|err| from_sqlite(&err))?;
    let image_urls: Vec<String> = serde_json::from_str(&image_urls_json).map_err(|error| {
        corruption(
            "invalid_image_urls_projection",
            &format!("corrupt gallery image_urls_json: {error}"),
        )
    })?;
    Ok(GalleryRow {
        memo_id: row.get(0).map_err(|err| from_sqlite(&err))?,
        source_path: row.get(1).map_err(|err| from_sqlite(&err))?,
        time_part: row.get(2).map_err(|err| from_sqlite(&err))?,
        created_at_ms: row.get(3).map_err(|err| from_sqlite(&err))?,
        updated_at_ms: row.get(4).map_err(|err| from_sqlite(&err))?,
        is_pinned: row.get::<_, i64>(5).map_err(|err| from_sqlite(&err))? != 0,
        content_revision,
        file_fingerprint: row.get(7).map_err(|err| from_sqlite(&err))?,
        image_urls,
    })
}

fn gallery_side_exists(
    connection: &Connection,
    cmp: &str,
    row: &GalleryRow,
) -> Result<bool, lomo_core::LomoError> {
    let sql = format!(
        "SELECT EXISTS(SELECT 1 FROM memo m INDEXED BY idx_memo_gallery \
         WHERE {GALLERY_FILTER_PREDICATE} \
         AND (m.is_pinned, m.created_at_ms, m.memo_id) {cmp} (?1, ?2, ?3))"
    );
    let exists: i64 = connection
        .query_row(
            &sql,
            params![i64::from(row.is_pinned), row.created_at_ms, row.memo_id],
            |r| r.get(0),
        )
        .map_err(|err| from_sqlite(&err))?;
    Ok(exists != 0)
}

fn scan_gallery_rows(
    connection: &Connection,
    bound: Option<(i64, i64, String)>,
    cmp: &str,
    invert: bool,
    fetch_limit: i64,
    capacity: usize,
) -> Result<Vec<GalleryRow>, lomo_core::LomoError> {
    let order_sql = if invert {
        "ORDER BY m.is_pinned ASC, m.created_at_ms ASC, m.memo_id ASC"
    } else {
        "ORDER BY m.is_pinned DESC, m.created_at_ms DESC, m.memo_id DESC"
    };
    let bound_sql = if bound.is_some() {
        format!("AND (m.is_pinned, m.created_at_ms, m.memo_id) {cmp} (?2, ?3, ?4)")
    } else {
        String::new()
    };
    let sql = format!(
        "SELECT m.memo_id, m.source_path, m.time_part, m.created_at_ms, m.updated_at_ms, \
         m.is_pinned, m.content_revision, m.file_fingerprint, m.image_urls_json \
         FROM memo m INDEXED BY idx_memo_gallery WHERE {GALLERY_FILTER_PREDICATE} \
         {bound_sql} {order_sql} LIMIT ?1"
    );
    let mut statement = connection.prepare(&sql).map_err(|err| from_sqlite(&err))?;
    let mut rows = match bound {
        Some((pin, created_at_ms, memo_id)) => {
            statement.query(params![fetch_limit, pin, created_at_ms, memo_id])
        }
        None => statement.query(params![fetch_limit]),
    }
    .map_err(|err| from_sqlite(&err))?;
    let mut items = Vec::with_capacity(capacity);
    while let Some(row) = rows.next().map_err(|err| from_sqlite(&err))? {
        items.push(gallery_row_from_sql(row)?);
    }
    Ok(items)
}

/// Queries one lightweight gallery cursor page using `idx_memo_gallery` (`LIMIT page_size + 1`,
/// opposite-side `EXISTS`, zero `COUNT(*)` and zero body/tags/reminders transfers).
///
/// # Errors
///
/// Returns `stale_cursor` / `invalid_page_cursor` for invalid cursors, `corruption` if
/// `image_urls_json` or `content_revision` is corrupt, and `SQLite` storage failures.
pub fn query_gallery_page(
    connection: &Connection,
    start: MemoQueryStart<'_>,
    page_size: PageSize,
    high_water_revision: u64,
) -> Result<GalleryPage, lomo_core::LomoError> {
    let query_fingerprint = fingerprint_query(None, "gallery:v1", TOKENIZER_VERSION);
    let limit_usize = usize::try_from(page_size.get()).map_err(|_overflow| {
        validation("invalid_page_size", "gallery page size exceeds usize range")
    })?;
    let fetch_limit = i64::from(page_size.get()) + 1;

    let (bound_tuple, cmp, invert, is_head_start): (Option<(i64, i64, String)>, &str, bool, bool) =
        match start {
            MemoQueryStart::Head => (None, "<", false, true),
            MemoQueryStart::After(cursor) | MemoQueryStart::Before(cursor) => {
                cursor.validate_against(&query_fingerprint, high_water_revision)?;
                cursor.validated_sort_rank(false)?;
                let invert = matches!(start, MemoQueryStart::Before(_));
                (
                    Some((
                        i64::from(cursor.sort_pinned),
                        cursor.sort_primary_ms,
                        cursor.sort_memo_id.clone(),
                    )),
                    if invert { ">" } else { "<" },
                    invert,
                    false,
                )
            }
            MemoQueryStart::AtMemo(memo_id) => {
                let anchor: Option<(i64, i64, String)> = connection
                    .query_row(
                        &format!(
                            "SELECT m.is_pinned, m.created_at_ms, m.memo_id FROM memo m \
                             WHERE m.memo_id = ?1 AND {GALLERY_FILTER_PREDICATE}"
                        ),
                        params![memo_id],
                        |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
                    )
                    .optional()
                    .map_err(|err| from_sqlite(&err))?;
                anchor.map_or((None, "<", false, true), |tuple| {
                    (Some(tuple), "<=", false, false)
                })
            }
        };

    let mut items = scan_gallery_rows(
        connection,
        bound_tuple,
        cmp,
        invert,
        fetch_limit,
        limit_usize.saturating_add(1),
    )?;
    let has_more_in_scan_dir = items.len() > limit_usize;
    if has_more_in_scan_dir {
        items.truncate(limit_usize);
    }
    if invert {
        items.reverse();
    }

    let (has_before, has_after) = match (items.first(), items.last()) {
        (Some(first), Some(last)) => {
            if invert {
                let after = gallery_side_exists(connection, "<", last)?;
                (has_more_in_scan_dir, after)
            } else {
                let before = if is_head_start {
                    false
                } else {
                    gallery_side_exists(connection, ">", first)?
                };
                (before, has_more_in_scan_dir)
            }
        }
        _ => (false, false),
    };

    let prev_cursor = items
        .first()
        .filter(|_| has_before)
        .map(|first| gallery_cursor_for(first, query_fingerprint.clone(), high_water_revision));
    let next_cursor = items
        .last()
        .filter(|_| has_after)
        .map(|last| gallery_cursor_for(last, query_fingerprint.clone(), high_water_revision));

    Ok(GalleryPage {
        items,
        next_cursor,
        prev_cursor,
        high_water_revision,
        query_fingerprint,
    })
}

/// Reads the one canonical fingerprint shared by every memo projected from a source document.
///
/// # Errors
///
/// Returns corruption when sibling rows disagree, because no caller may guess which document
/// revision is authoritative.
pub fn source_document_fingerprint(
    connection: &Connection,
    source_path: &str,
) -> Result<Option<String>, lomo_core::LomoError> {
    distinct_source_fingerprints(connection, source_path, None)
}

/// Reads the canonical fingerprint every OTHER memo row of one source document attests.
///
/// The row identified by `memo_id` never attests for itself: a trashed memo whose durable
/// record is being re-merged must not keep its own drifted fingerprint as "the document
/// fingerprint" once no sibling row can back it.
///
/// # Errors
///
/// Same contract as [`source_document_fingerprint`].
pub fn sibling_document_fingerprint(
    connection: &Connection,
    source_path: &str,
    memo_id: &str,
) -> Result<Option<String>, lomo_core::LomoError> {
    distinct_source_fingerprints(connection, source_path, Some(memo_id))
}

fn distinct_source_fingerprints(
    connection: &Connection,
    source_path: &str,
    exclude_memo_id: Option<&str>,
) -> Result<Option<String>, lomo_core::LomoError> {
    let _path = lomo_workspace::WorkspaceRelativePath::parse(source_path)?;
    let mut statement = connection
        .prepare(if exclude_memo_id.is_some() {
            "SELECT DISTINCT file_fingerprint FROM memo WHERE source_path = ?1 \
             AND memo_id <> ?2 AND pending_operation_id IS NULL \
             ORDER BY file_fingerprint LIMIT 2"
        } else {
            "SELECT DISTINCT file_fingerprint FROM memo WHERE source_path = ?1 \
             AND pending_operation_id IS NULL ORDER BY file_fingerprint LIMIT 2"
        })
        .map_err(|error| from_sqlite(&error))?;
    let fingerprints: Vec<String> = if let Some(memo_id) = exclude_memo_id {
        statement
            .query_map(params![source_path, memo_id], |row| row.get::<_, String>(0))
            .map_err(|error| from_sqlite(&error))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(|error| from_sqlite(&error))?
    } else {
        statement
            .query_map(params![source_path], |row| row.get::<_, String>(0))
            .map_err(|error| from_sqlite(&error))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(|error| from_sqlite(&error))?
    };
    match fingerprints.as_slice() {
        [] => Ok(None),
        [fingerprint] => Ok(Some(fingerprint.clone())),
        _ => Err(corruption(
            "inconsistent_source_document_fingerprint",
            "memos from one source document disagree on its fingerprint",
        )),
    }
}

/// Active memo ids projected from one source document path (excludes trash and in-flight ops).
///
/// # Errors
///
/// Path validation or `SQLite` projection failures.
pub fn active_memo_ids_for_source_path(
    connection: &Connection,
    source_path: &str,
) -> Result<Vec<String>, lomo_core::LomoError> {
    let _path = lomo_workspace::WorkspaceRelativePath::parse(source_path)?;
    let mut statement = connection
        .prepare(
            "SELECT memo_id FROM memo WHERE source_path = ?1 AND is_trashed = 0 \
             AND pending_operation_id IS NULL ORDER BY memo_id",
        )
        .map_err(|error| from_sqlite(&error))?;
    let ids = statement
        .query_map(params![source_path], |row| row.get::<_, String>(0))
        .map_err(|error| from_sqlite(&error))?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|error| from_sqlite(&error))?;
    Ok(ids)
}

/// Every memo id projected from one source document path in any lifecycle state — active or
/// trashed — excluding in-flight pending rows.
///
/// A deleted source document retires all of them: trashed rows are re-derived from their
/// durable trash records, which carry the only fingerprint authority left once the document
/// is gone.
///
/// # Errors
///
/// Path validation or `SQLite` projection failures.
pub fn memo_ids_for_source_path(
    connection: &Connection,
    source_path: &str,
) -> Result<Vec<String>, lomo_core::LomoError> {
    let _path = lomo_workspace::WorkspaceRelativePath::parse(source_path)?;
    let mut statement = connection
        .prepare(
            "SELECT memo_id FROM memo WHERE source_path = ?1 \
             AND pending_operation_id IS NULL ORDER BY memo_id",
        )
        .map_err(|error| from_sqlite(&error))?;
    let ids = statement
        .query_map(params![source_path], |row| row.get::<_, String>(0))
        .map_err(|error| from_sqlite(&error))?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|error| from_sqlite(&error))?;
    Ok(ids)
}

/// Loads tag names for one bounded page in a single statement.
fn attach_tags(
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

    for item in items {
        item.tags = tags_by_memo.remove(&item.memo_id).ok_or_else(|| {
            validation(
                "memo_tag_owner_missing",
                "requested page item was missing from the bulk tag projection",
            )
        })?;
    }
    Ok(())
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
            "SELECT COUNT(*) FROM memo m WHERE m.is_trashed = 0",
            [],
            |row| row.get(0),
        )
        .map_err(|err| from_sqlite(&err))?;

    let mut date_statement = connection
        .prepare(
            "SELECT date(m.created_at_ms / 1000, 'unixepoch', 'localtime'), COUNT(*) \
             FROM memo m WHERE m.is_trashed = 0 GROUP BY 1 ORDER BY 1",
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
             JOIN memo m ON m.memo_id = mt.memo_id \
             WHERE m.is_trashed = 0 GROUP BY tg.name ORDER BY COUNT(*) DESC, tg.name",
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

/// Counts the rows `query_memos` would return for this query without transferring any of them.
///
/// # Errors
///
/// Storage errors or invalid tag filters (same predicate set as `query_memos`).
pub fn query_count(
    connection: &Connection,
    query: &MemoQuery,
) -> Result<u64, lomo_core::LomoError> {
    reject_search_text(query)?;
    register_review_functions(connection, &query.sort)?;
    count_rows(connection, query, None, None)
}

/// Placement evidence for a bounded-window refresh merge.
///
/// Which of `memo_ids` still satisfy `query`, split by where they sort
/// relative to the reply window — [`MemoWindowSides::above`] ranks strictly
/// before `head`, [`MemoWindowSides::below`] ranks strictly after `tail`,
/// and both lists are in live query order.
///
/// The bounds are the reply's own minted cursors reused as pure ordering
/// bounds: no revision or fingerprint validation applies, because a bound is
/// position evidence from an adjacent snapshot, not a pagination
/// continuation. A `None` bound means the reply window already touches that
/// edge (rank 0 / the last row), so its side is empty by definition.
///
/// # Errors
///
/// Storage errors or invalid tag filters (same predicate set as `query_memos`).
pub fn memos_matching_window(
    connection: &Connection,
    query: &MemoQuery,
    memo_ids: &[String],
    head: Option<&PageCursor>,
    tail: Option<&PageCursor>,
) -> Result<MemoWindowSides, lomo_core::LomoError> {
    reject_search_text(query)?;
    if memo_ids.is_empty() || (head.is_none() && tail.is_none()) {
        return Ok(MemoWindowSides::default());
    }
    register_review_functions(connection, &query.sort)?;
    // The id list travels as one JSON parameter — a single statement keeps the
    // ORDER BY global across the whole set instead of one ordering per chunk.
    let ids_json = serde_json::to_string(memo_ids).map_err(|error| {
        validation(
            "invalid_memo_id_set",
            &format!("cannot encode the memo id set: {error}"),
        )
    })?;
    Ok(MemoWindowSides {
        above: matching_window_side(
            connection,
            query,
            &ids_json,
            head.map(|cursor| (cursor, CursorBound::ExclusiveBefore)),
        )?,
        below: matching_window_side(
            connection,
            query,
            &ids_json,
            tail.map(|cursor| (cursor, CursorBound::ExclusiveAfter)),
        )?,
    })
}

fn matching_window_side(
    connection: &Connection,
    query: &MemoQuery,
    ids_json: &str,
    bound: Option<(&PageCursor, CursorBound)>,
) -> Result<Vec<String>, lomo_core::LomoError> {
    let Some((cursor, cursor_bound)) = bound else {
        return Ok(Vec::new());
    };
    let _rank = cursor.validated_sort_rank(false)?;
    let PredicateSql {
        where_sql,
        first_cursor_index,
    } = predicate_sql(query, None, Some(cursor_bound))?;
    let ids_idx = first_cursor_index + 4;
    let sql = format!(
        "SELECT m.memo_id FROM memo m WHERE {where_sql} \
         AND m.memo_id IN (SELECT value FROM json_each(?{ids_idx})) \
         ORDER BY {}",
        order_sql(&query.sort, false),
    );
    let mut bindings = bind_query(query, None, Some(cursor), None);
    bindings.push(Value::Text(ids_json.to_owned()));
    let mut statement = connection.prepare(&sql).map_err(|err| from_sqlite(&err))?;
    let rows = statement
        .query_map(params_from_iter(bindings.iter()), |row| {
            row.get::<_, String>(0)
        })
        .map_err(|err| from_sqlite(&err))?;
    let mut ids = Vec::new();
    for row in rows {
        ids.push(row.map_err(|err| from_sqlite(&err))?);
    }
    Ok(ids)
}

/// One compact materialized statistics row (no body bytes cross the boundary).
///
/// Carries the memo identity and its deduplicated tag facts so statistics aggregation reads
/// exactly one joined scan instead of re-reading bodies or issuing per-memo tag queries.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemoStatisticsRow {
    pub memo_id: String,
    pub created_at_ms: i64,
    pub word_count: i64,
    pub char_count: i64,
    pub tags: Vec<String>,
}

/// Reads the materialized statistics rows for every active memo in one joined scan.
///
/// The LEFT JOIN fans out one row per (memo, tag); consecutive same-memo rows fold into a single
/// [`MemoStatisticsRow`] whose `tags` arrive in canonical (sorted) order.
///
/// # Errors
///
/// Storage errors when the memo projection is unreadable.
pub fn query_memo_statistics_rows(
    connection: &Connection,
) -> Result<Vec<MemoStatisticsRow>, lomo_core::LomoError> {
    let mut statement = connection
        .prepare(
            "SELECT m.memo_id, m.created_at_ms, m.word_count, m.char_count, tg.name \
             FROM memo m \
             LEFT JOIN memo_tag mt ON mt.memo_id = m.memo_id \
             LEFT JOIN tag tg ON tg.id = mt.tag_id \
             WHERE m.is_trashed = 0 \
             ORDER BY m.memo_id, tg.name",
        )
        .map_err(|err| from_sqlite(&err))?;
    let mut rows = statement
        .query(params![])
        .map_err(|err| from_sqlite(&err))?;
    let mut out: Vec<MemoStatisticsRow> = Vec::new();
    while let Some(row) = rows.next().map_err(|err| from_sqlite(&err))? {
        let memo_id = row.get::<_, String>(0).map_err(|err| from_sqlite(&err))?;
        let tag = row
            .get::<_, Option<String>>(4)
            .map_err(|err| from_sqlite(&err))?;
        match out.last_mut() {
            Some(last) if last.memo_id == memo_id => {
                if let Some(tag) = tag {
                    last.tags.push(tag);
                }
            }
            _ => out.push(MemoStatisticsRow {
                memo_id,
                created_at_ms: row.get(1).map_err(|err| from_sqlite(&err))?,
                word_count: row.get(2).map_err(|err| from_sqlite(&err))?,
                char_count: row.get(3).map_err(|err| from_sqlite(&err))?,
                tags: tag.into_iter().collect(),
            }),
        }
    }
    Ok(out)
}

/// One projected attachment reference with its owner's lifecycle bit.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProjectedAttachmentRef {
    /// Memo that owns the reference.
    pub memo_id: String,
    /// Canonical relative attachment path exactly as the memo body names it.
    pub relative_path: String,
    /// Whether the owning memo is currently trashed.
    pub is_trashed: bool,
}

/// Lists every projected attachment reference in one scan.
///
/// `attachment_ref` rows cover all attachment types (images and audio) and are refreshed with the
/// owning memo's projection, so trashed memos still contribute their last projected references.
///
/// # Errors
///
/// Storage errors when the projection cannot be read.
pub fn list_projected_attachment_refs(
    connection: &Connection,
) -> Result<Vec<ProjectedAttachmentRef>, lomo_core::LomoError> {
    let mut statement = connection
        .prepare(
            "SELECT a.memo_id, a.relative_path, m.is_trashed \
             FROM attachment_ref a JOIN memo m ON m.memo_id = a.memo_id \
             ORDER BY a.memo_id, a.relative_path",
        )
        .map_err(|err| from_sqlite(&err))?;
    let rows = statement
        .query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, i64>(2)?,
            ))
        })
        .map_err(|err| from_sqlite(&err))?;
    let mut out = Vec::new();
    for row in rows {
        let (memo_id, relative_path, is_trashed) = row.map_err(|err| from_sqlite(&err))?;
        out.push(ProjectedAttachmentRef {
            memo_id,
            relative_path,
            is_trashed: is_trashed != 0,
        });
    }
    Ok(out)
}

/// Verified listing snapshot committed with the last projection update, keyed by path.
///
/// The reconcile engine diffs a fresh listing against this map to scope work to changed paths.
/// An empty map is itself evidence: the last committed projection predates snapshot persistence
/// (or saw an empty workspace), so the caller must decide admission, not this query.
///
/// # Errors
///
/// Storage errors when the projection cannot be read.
pub fn workspace_listing_snapshot(
    connection: &Connection,
) -> Result<BTreeMap<String, String>, lomo_core::LomoError> {
    let mut statement = connection
        .prepare("SELECT path, digest FROM file_listing")
        .map_err(|err| from_sqlite(&err))?;
    let rows = statement
        .query_map([], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
        })
        .map_err(|err| from_sqlite(&err))?;
    let mut out = BTreeMap::new();
    for row in rows {
        let (path, digest) = row.map_err(|err| from_sqlite(&err))?;
        out.insert(path, digest);
    }
    Ok(out)
}

/// Resolves the memo owning a durable history record by its content-addressed record id.
///
/// # Errors
///
/// Storage errors when the projection cannot be read.
pub fn history_record_owner(
    connection: &Connection,
    record_id: &str,
) -> Result<Option<String>, lomo_core::LomoError> {
    connection
        .query_row(
            "SELECT memo_id FROM revision_index WHERE history_record_id=?1 \
             ORDER BY memo_id LIMIT 1",
            [record_id],
            |row| row.get(0),
        )
        .optional()
        .map_err(|err| from_sqlite(&err))
}

/// Memo identities currently carrying a durable trash record.
///
/// # Errors
///
/// Storage errors when the projection cannot be read.
pub fn trashed_memo_ids(connection: &Connection) -> Result<BTreeSet<String>, lomo_core::LomoError> {
    let mut statement = connection
        .prepare("SELECT memo_id FROM memo_trash ORDER BY memo_id")
        .map_err(|err| from_sqlite(&err))?;
    let rows = statement
        .query_map([], |row| row.get::<_, String>(0))
        .map_err(|err| from_sqlite(&err))?;
    let mut out = BTreeSet::new();
    for row in rows {
        out.insert(row.map_err(|err| from_sqlite(&err))?);
    }
    Ok(out)
}

/// Committed `memo_pin` rows keyed by memo identity.
///
/// Pin rows are app-private projection state: the incremental reconcile carries one forward
/// for exactly the identities durable state never answers for — the same rows
/// `copy_saf_private_state` copies into a materialized projection when the identity sits
/// outside `attested_ids`.
///
/// # Errors
///
/// Storage errors when the projection cannot be read.
pub fn pinned_memo_timestamps(
    connection: &Connection,
) -> Result<BTreeMap<String, i64>, lomo_core::LomoError> {
    let mut statement = connection
        .prepare("SELECT memo_id,pinned_at_ms FROM memo_pin ORDER BY memo_id")
        .map_err(|err| from_sqlite(&err))?;
    let rows = statement
        .query_map([], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?))
        })
        .map_err(|err| from_sqlite(&err))?;
    let mut out = BTreeMap::new();
    for row in rows {
        let (memo_id, pinned_at_ms) = row.map_err(|err| from_sqlite(&err))?;
        out.insert(memo_id, pinned_at_ms);
    }
    Ok(out)
}

/// Memo identities currently carrying a durable purge tombstone.
///
/// # Errors
///
/// Storage errors when the projection cannot be read.
pub fn purged_memo_ids(connection: &Connection) -> Result<BTreeSet<String>, lomo_core::LomoError> {
    let mut statement = connection
        .prepare("SELECT memo_id FROM purged_memo ORDER BY memo_id")
        .map_err(|err| from_sqlite(&err))?;
    let rows = statement
        .query_map([], |row| row.get::<_, String>(0))
        .map_err(|err| from_sqlite(&err))?;
    let mut out = BTreeSet::new();
    for row in rows {
        out.insert(row.map_err(|err| from_sqlite(&err))?);
    }
    Ok(out)
}

/// Source document paths for the given memo identities, including trashed rows.
///
/// # Errors
///
/// Storage errors when the projection cannot be read.
pub fn memo_source_path(
    connection: &Connection,
    memo_id: &str,
) -> Result<Option<String>, lomo_core::LomoError> {
    connection
        .query_row(
            "SELECT source_path FROM memo WHERE memo_id=?1",
            [memo_id],
            |row| row.get(0),
        )
        .optional()
        .map_err(|err| from_sqlite(&err))
}

/// Only active reminder facts, without loading unrelated memo bodies or attachments.
#[derive(Debug, Clone)]
pub struct MemoReminderProjection {
    pub memo_id: String,
    pub reminders: Vec<lomo_workspace::ReminderReference>,
}

/// Reads the indexed active reminder partition.
///
/// # Errors
/// Returns storage failures or malformed typed reminder projections.
pub fn query_reminder_projections(
    connection: &Connection,
) -> Result<Vec<MemoReminderProjection>, lomo_core::LomoError> {
    let mut statement = connection.prepare(
        "SELECT memo_id,reminders_json FROM memo INDEXED BY idx_memo_reminders WHERE is_trashed=0 AND pending_operation_id IS NULL AND reminders_json!='[]'"
    ).map_err(|error| from_sqlite(&error))?;
    let rows = statement
        .query_map([], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
        })
        .map_err(|error| from_sqlite(&error))?;
    rows.map(|row| {
        let (memo_id, json) = row.map_err(|error| from_sqlite(&error))?;
        let reminders = serde_json::from_str(&json)
            .map_err(|error| corruption("invalid_reminder_projection", &error.to_string()))?;
        Ok(MemoReminderProjection { memo_id, reminders })
    })
    .collect()
}
