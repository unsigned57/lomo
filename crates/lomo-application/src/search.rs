//! Unified dual-mode Tantivy retrieval (`Fulltext` & `Fuzzy`) with epoch cancellation.

use std::sync::atomic::Ordering;

use lomo_core::{LomoError, PageSize};
use lomo_store::{
    MemoFilters, MemoQueryStart, MemoSort, MemoSummary, PageCursor, SearchPageRequest,
    StoreSearchMode,
};
use serde::{Deserialize, Serialize};

use crate::{error::validation, session::WorkspaceSession};

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SearchMode {
    Fulltext,
    Fuzzy,
}

impl From<SearchMode> for StoreSearchMode {
    fn from(value: SearchMode) -> Self {
        match value {
            SearchMode::Fulltext => Self::Fulltext,
            SearchMode::Fuzzy => Self::Fuzzy,
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SearchRequest {
    pub query_epoch: u64,
    pub mode: SearchMode,
    pub text: String,
    pub filters: MemoFilters,
    pub sort: MemoSort,
    /// Exclusive continuation cursor for the next or previous page of the same scored order.
    pub cursor: Option<PageCursor>,
    /// Inclusive start at this memo's own rank in the scored order — the
    /// anchored refresh window's entry point. A memo absent from the hit set
    /// falls back to the head, mirroring `MemoQueryStart::AtMemo`. Mutually
    /// exclusive with `cursor`: one request carries exactly one start.
    pub anchor: Option<String>,
    /// When `true` with `Some(cursor)`, loads the page strictly preceding `cursor` (`MemoQueryStart::Before`).
    pub backward: bool,
    pub page_size: PageSize,
}

#[derive(Clone, Debug, PartialEq)]
pub struct SearchHit {
    pub memo_id: String,
    pub score: i64,
    pub summary: MemoSummary,
    pub excerpt: crate::search_excerpt::SearchExcerpt,
}

#[derive(Clone, Debug, PartialEq)]
pub struct SearchPage {
    pub query_epoch: u64,
    pub mode: SearchMode,
    pub items: Vec<SearchHit>,
    pub next_cursor: Option<PageCursor>,
    /// Ordering bound strictly above this page's first hit when `items_before > 0`.
    pub prev_cursor: Option<PageCursor>,
    /// Count of matches ranking strictly ahead of this page.
    pub items_before: u64,
    /// Count of matches ranking strictly after this page.
    pub items_after: u64,
    pub total: u64,
}

#[derive(Clone, Debug, PartialEq)]
pub enum SearchOutcome {
    Ready(Box<SearchPage>),
    Discarded { query_epoch: u64, active_epoch: u64 },
}

impl WorkspaceSession {
    /// Runs one unified Tantivy search across `Fulltext` or `Fuzzy`. Stale epochs are discarded
    /// without touching the projection.
    ///
    /// # Errors
    /// Propagates query validation, snapshot conflict, and projection failures.
    pub fn search(&self, request: &SearchRequest) -> Result<SearchOutcome, LomoError> {
        validate_filters(&request.filters)?;
        if request.text.len() > 4096 {
            return Err(validation(
                "query_too_long",
                "search query exceeds 4096 UTF-8 bytes",
            ));
        }
        if request.cursor.is_some() && request.anchor.is_some() {
            return Err(validation(
                "ambiguous_search_start",
                "a search page starts from a cursor or an anchor memo, never both",
            ));
        }
        if request.backward && request.cursor.is_none() {
            return Err(validation(
                "invalid_search_start",
                "backward search paging requires a page cursor",
            ));
        }
        if let Some(active) = stale_epoch(&self.search_epoch, request.query_epoch) {
            return Ok(SearchOutcome::Discarded {
                query_epoch: request.query_epoch,
                active_epoch: active,
            });
        }
        let page = execute_search(self, request)?;
        let current = self.search_epoch.load(Ordering::Acquire);
        if request.query_epoch < current {
            return Ok(SearchOutcome::Discarded {
                query_epoch: request.query_epoch,
                active_epoch: current,
            });
        }
        Ok(SearchOutcome::Ready(Box::new(page)))
    }
}

fn stale_epoch(slot: &std::sync::atomic::AtomicU64, query_epoch: u64) -> Option<u64> {
    loop {
        let current = slot.load(Ordering::Acquire);
        if query_epoch < current {
            return Some(current);
        }
        if slot
            .compare_exchange(current, query_epoch, Ordering::AcqRel, Ordering::Acquire)
            .is_ok()
        {
            return None;
        }
    }
}

pub(crate) fn validate_filters(filters: &MemoFilters) -> Result<(), LomoError> {
    if filters
        .date_from_inclusive_ms
        .zip(filters.date_until_exclusive_ms)
        .is_some_and(|(from, until)| from >= until)
    {
        return Err(validation(
            "invalid_date_range",
            "search range must have a start before its end",
        ));
    }
    if let Some(tag) = &filters.tag {
        let source = format!("#{tag}");
        let parsed =
            lomo_workspace::render_markdown(&lomo_workspace::SourceBytes::try_from_str(&source)?)?;
        if parsed.tag_names() != std::slice::from_ref(tag) {
            return Err(validation(
                "invalid_tag",
                "tag filter must be one complete tag name without #",
            ));
        }
    }
    Ok(())
}

fn execute_search(
    session: &WorkspaceSession,
    request: &SearchRequest,
) -> Result<SearchPage, LomoError> {
    let start = match (&request.anchor, request.cursor.as_ref(), request.backward) {
        (Some(anchor), None, false) => MemoQueryStart::AtMemo(anchor.as_str()),
        (None, Some(cursor), true) => MemoQueryStart::Before(cursor),
        (None, Some(cursor), false) => MemoQueryStart::After(cursor),
        _ => MemoQueryStart::Head,
    };
    let trimmed = nonempty(&request.text);
    let store_mode = StoreSearchMode::from(request.mode);

    let search_page = session.with_reader(|reader| {
        let page = reader.search_memos(SearchPageRequest {
            text: trimmed.as_deref(),
            mode: store_mode,
            filters: &request.filters,
            sort: &request.sort,
            boundary: None,
            start,
            page_size: request.page_size,
        })?;
        let total = page
            .items_before
            .saturating_add(u64::try_from(page.items.len()).unwrap_or(u64::MAX))
            .saturating_add(page.items_after);
        let memo_ids = page
            .items
            .iter()
            .map(|summary| summary.memo_id.clone())
            .collect::<Vec<_>>();
        let mut snapshots = reader
            .get_projected_memos(&memo_ids)?
            .into_iter()
            .map(|snapshot| (snapshot.summary.memo_id.clone(), snapshot))
            .collect::<std::collections::BTreeMap<_, _>>();
        let items = page
            .items
            .into_iter()
            .map(|summary| {
                let snapshot = snapshots
                    .remove(&summary.memo_id)
                    .ok_or_else(|| validation("memo_not_found", "search result disappeared"))?;
                verify_version(&summary, &snapshot.summary)?;
                let excerpt = crate::search_excerpt::search_excerpt(
                    request.mode,
                    &summary.source_path,
                    &snapshot.body,
                    &request.text,
                )?;
                Ok(SearchHit {
                    score: rank_score(summary.rank),
                    memo_id: summary.memo_id.clone(),
                    summary,
                    excerpt,
                })
            })
            .collect::<Result<Vec<_>, LomoError>>()?;
        Ok(SearchPage {
            query_epoch: request.query_epoch,
            mode: request.mode,
            items,
            next_cursor: page.next_cursor,
            prev_cursor: page.prev_cursor,
            items_before: page.items_before,
            items_after: page.items_after,
            total,
        })
    })?;
    session.maybe_compact_search_changelog()?;
    Ok(search_page)
}

fn verify_version(expected: &MemoSummary, actual: &MemoSummary) -> Result<(), LomoError> {
    if expected.content_revision != actual.content_revision
        || expected.file_fingerprint != actual.file_fingerprint
    {
        return Err(validation(
            "stale_search_result",
            "memo changed while searching; repeat the query",
        ));
    }
    Ok(())
}

fn nonempty(text: &str) -> Option<String> {
    let trimmed = text.trim();
    if trimmed.is_empty() {
        None
    } else {
        Some(trimmed.to_owned())
    }
}

fn rank_score(rank: Option<f64>) -> i64 {
    let Some(value) = rank.filter(|value| value.is_finite() && *value > 0.0) else {
        return 0;
    };
    let scaled = (value * 1000.0).round().max(1.0);
    #[expect(
        clippy::cast_possible_truncation,
        reason = "scaled Tantivy positive relevance score fits safely in i64"
    )]
    let score = scaled as i64;
    score
}
