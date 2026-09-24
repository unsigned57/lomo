//! Dual-mode retrieval: FTS fulltext plus pinyin/fuzzy ranking with epoch cancellation.

use std::sync::atomic::Ordering;

use lomo_core::{LomoError, PageSize};
use lomo_store::{MemoFilters, MemoQuery, MemoSummary, PageCursor};
use serde::{Deserialize, Serialize};

use crate::{error::validation, paging::collect_summaries, session::WorkspaceSession};

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SearchMode {
    Fulltext,
    Fuzzy,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SearchRequest {
    pub query_epoch: u64,
    pub mode: SearchMode,
    pub text: String,
    pub filters: MemoFilters,
    pub cursor: Option<PageCursor>,
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
    pub total: u64,
}

#[derive(Clone, Debug, PartialEq)]
pub enum SearchOutcome {
    Ready(SearchPage),
    Discarded { query_epoch: u64, active_epoch: u64 },
}

impl WorkspaceSession {
    /// Runs one dual-mode search. Stale epochs are discarded without touching the projection.
    ///
    /// # Errors
    /// Propagates query validation and projection failures.
    pub fn search(&self, request: &SearchRequest) -> Result<SearchOutcome, LomoError> {
        validate_filters(&request.filters)?;
        if request.text.len() > 4096 {
            return Err(validation(
                "query_too_long",
                "search query exceeds 4096 UTF-8 bytes",
            ));
        }
        if let Some(active) = stale_epoch(&self.search_epoch, request.query_epoch) {
            return Ok(SearchOutcome::Discarded {
                query_epoch: request.query_epoch,
                active_epoch: active,
            });
        }
        let page = match request.mode {
            SearchMode::Fulltext => fulltext(self, request)?,
            SearchMode::Fuzzy => fuzzy(self, request)?,
        };
        if let Some(active) = stale_epoch(&self.search_epoch, request.query_epoch) {
            return Ok(SearchOutcome::Discarded {
                query_epoch: request.query_epoch,
                active_epoch: active,
            });
        }
        Ok(SearchOutcome::Ready(page))
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

fn fulltext(session: &WorkspaceSession, request: &SearchRequest) -> Result<SearchPage, LomoError> {
    let query = MemoQuery {
        search_text: nonempty(&request.text),
        filters: request.filters.clone(),
        sort: lomo_store::MemoSort::default(),
    };
    session.with_reader(|store| {
        let page = store.query_memos(&query, request.cursor.as_ref(), request.page_size)?;
        let total = store.query_count(&query)?;
        let memo_ids = page
            .items
            .iter()
            .map(|summary| summary.memo_id.clone())
            .collect::<Vec<_>>();
        let mut snapshots = store
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
                let excerpt = crate::search_excerpt::fulltext_excerpt(
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
            .collect::<Result<_, LomoError>>()?;
        Ok(SearchPage {
            query_epoch: request.query_epoch,
            mode: SearchMode::Fulltext,
            items,
            next_cursor: page.next_cursor,
            total,
        })
    })
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

fn fuzzy(session: &WorkspaceSession, request: &SearchRequest) -> Result<SearchPage, LomoError> {
    let revision = session.with_store(|store| Ok(store.high_water_revision()))?;
    let mut hits = scored_hits(session, &request.text, &request.filters)?;
    if revision != session.with_store(|store| Ok(store.high_water_revision()))? {
        return Err(validation(
            "stale_search_result",
            "projection changed while searching; repeat the query",
        ));
    }
    hits.sort_by(|left, right| {
        right
            .score
            .cmp(&left.score)
            .then(right.summary.created_at_ms.cmp(&left.summary.created_at_ms))
            .then(left.memo_id.cmp(&right.memo_id))
    });
    paginate_fuzzy(request, &hits, revision)
}

fn scored_hits(
    session: &WorkspaceSession,
    text: &str,
    filters: &MemoFilters,
) -> Result<Vec<SearchHit>, LomoError> {
    let query = MemoQuery {
        search_text: None,
        filters: filters.clone(),
        sort: lomo_store::MemoSort::default(),
    };
    let summaries = collect_summaries(session, &query)?;
    let memo_ids = summaries
        .iter()
        .map(|summary| summary.memo_id.clone())
        .collect::<Vec<_>>();
    let mut snapshots = session
        .with_reader(|store| store.get_projected_memos(&memo_ids))?
        .into_iter()
        .map(|snapshot| (snapshot.summary.memo_id.clone(), snapshot))
        .collect::<std::collections::BTreeMap<_, _>>();
    let mut hits = Vec::new();
    let needle = text.trim();
    for summary in summaries {
        let snapshot = snapshots
            .remove(&summary.memo_id)
            .ok_or_else(|| validation("memo_not_found", "search candidate disappeared"))?;
        verify_version(&summary, &snapshot.summary)?;
        if let Some((score, excerpt)) =
            crate::search_excerpt::fuzzy_excerpt(&summary.source_path, &snapshot.body, needle)?
        {
            hits.push(SearchHit {
                memo_id: summary.memo_id.clone(),
                score,
                summary,
                excerpt,
            });
        }
    }
    Ok(hits)
}

fn paginate_fuzzy(
    request: &SearchRequest,
    hits: &[SearchHit],
    revision: u64,
) -> Result<SearchPage, LomoError> {
    let fingerprint = format!(
        "fuzzy|{}|{}",
        request.text.trim(),
        request.filters.fingerprint()
    );
    let start = match &request.cursor {
        None => 0,
        Some(cursor) => {
            cursor.validate_against(&fingerprint, revision)?;
            hits.iter()
                .position(|hit| hit.memo_id == cursor.sort_memo_id)
                .ok_or_else(|| {
                    validation("stale_cursor", "fuzzy cursor memo is no longer a candidate")
                })?
                .saturating_add(1)
        }
    };
    let limit = usize::try_from(request.page_size.get())
        .map_err(|error| validation("invalid_page_size", error.to_string()))?;
    let end = start.saturating_add(limit).min(hits.len());
    let page_items: Vec<SearchHit> = hits
        .iter()
        .skip(start)
        .take(end.saturating_sub(start))
        .cloned()
        .collect();
    let next_cursor = (end < hits.len())
        .then(|| page_items.last())
        .flatten()
        .map(|hit| {
            PageCursor::new(
                fingerprint,
                None,
                hit.summary.is_pinned,
                hit.summary.created_at_ms,
                hit.summary.created_at_ms,
                hit.memo_id.clone(),
                revision,
            )
        });
    Ok(SearchPage {
        query_epoch: request.query_epoch,
        mode: SearchMode::Fuzzy,
        items: page_items,
        next_cursor,
        total: u64::try_from(hits.len())
            .map_err(|error| validation("search_count_overflow", error.to_string()))?,
    })
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
    let Some(value) = rank.filter(|value| value.is_finite()) else {
        return 0;
    };
    let rounded = value.round();
    #[expect(
        clippy::cast_possible_truncation,
        reason = "FTS rank is a display score; finite f64 rounds into i64 range for list ordering"
    )]
    let score = rounded as i64;
    score
}
