//! Dual-mode retrieval: FTS fulltext plus pinyin/fuzzy ranking with epoch cancellation.

use std::sync::atomic::Ordering;

use lomo_core::{LomoError, PageSize};
use lomo_store::{MemoFilters, MemoQuery, MemoQueryStart, MemoSummary, PageCursor};
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
    /// Exclusive continuation cursor for the next page of the same scored order.
    pub cursor: Option<PageCursor>,
    /// Inclusive start at this memo's own rank in the scored order — the
    /// anchored refresh window's entry point. A memo absent from the hit set
    /// falls back to the head, mirroring `MemoQueryStart::AtMemo`. Mutually
    /// exclusive with `cursor`: one request carries exactly one start.
    pub anchor: Option<String>,
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
    /// The ordering bound strictly above this page's first hit — position
    /// evidence for a refresh merge's above-window side, never a navigation
    /// cursor (search pages cannot be walked backwards). `None` when the page
    /// head is rank 0; fuzzy pages carry hit positions instead.
    pub prev_cursor: Option<PageCursor>,
    /// Matches ranking before this page — fulltext forwards the store's own
    /// rank count; fuzzy counts the page's offset in the scored hit list.
    pub items_before: u64,
    /// Matches ranking after this page — `total` minus the returned window and
    /// everything above it. A caller aggregating pages must use this, never
    /// `total - items.len()`, which double-counts pages already landed.
    pub items_after: u64,
    pub total: u64,
}

#[derive(Clone, Debug, PartialEq)]
pub enum SearchOutcome {
    Ready(Box<SearchPage>),
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
        if request.cursor.is_some() && request.anchor.is_some() {
            return Err(validation(
                "ambiguous_search_start",
                "a search page starts from a cursor or an anchor memo, never both",
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

fn fulltext(session: &WorkspaceSession, request: &SearchRequest) -> Result<SearchPage, LomoError> {
    let query = MemoQuery {
        search_text: nonempty(&request.text),
        filters: request.filters.clone(),
        sort: lomo_store::MemoSort::default(),
    };
    session.with_reader(|store| {
        let page = match &request.anchor {
            // An anchored refresh starts at the memo's own rank — the store
            // resolves the identity against the live revision, so the window
            // covers the reading position instead of the ranked head.
            Some(anchor) if request.cursor.is_none() => store.query_memos_starting_at(
                &query,
                None,
                MemoQueryStart::AtMemo(anchor.as_str()),
                request.page_size,
            )?,
            _ => store.query_memos(&query, request.cursor.as_ref(), request.page_size)?,
        };
        // The page's rank bounds already carry the total — a second COUNT(*)
        // would rescan the same FTS match set for a number we have.
        let total = page
            .items_before
            .saturating_add(u64::try_from(page.items.len()).unwrap_or(u64::MAX))
            .saturating_add(page.items_after);
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
            prev_cursor: page.prev_cursor,
            items_before: page.items_before,
            items_after: page.items_after,
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

/// One scored fuzzy result set — the unit a continuation cursor pages through.
/// `positions` resolves a cursor's memo id to its rank without a linear scan.
#[derive(Clone, Debug)]
pub struct FuzzyResult {
    fingerprint: String,
    revision: u64,
    hits: std::sync::Arc<[SearchHit]>,
    positions: std::sync::Arc<std::collections::HashMap<String, usize>>,
}

/// Scoring is memoized per (fingerprint, revision): a fresh query string, a
/// changed filter or a projection write each key a new entry; pagination of
/// one query reuses its entry. The bound keeps every open query's snapshot
/// while evicting superseded revisions.
const FUZZY_CACHE_ENTRIES: usize = 4;

fn fuzzy(session: &WorkspaceSession, request: &SearchRequest) -> Result<SearchPage, LomoError> {
    let result = fuzzy_snapshot(session, &request.text, &request.filters)?;
    paginate_fuzzy(request, &result)
}

/// The scored, sorted hit set for `text`/`filters` at one projection revision —
/// shared between pagination and membership checks so both read the same
/// snapshot (cache hit or a fresh scored read).
fn fuzzy_snapshot(
    session: &WorkspaceSession,
    text: &str,
    filters: &MemoFilters,
) -> Result<FuzzyResult, LomoError> {
    let revision = session.with_store(|store| Ok(store.high_water_revision()))?;
    let fingerprint = format!("fuzzy|{}|{}", text.trim(), filters.fingerprint());
    if let Some(result) = cached_fuzzy(session, &fingerprint, revision)? {
        return Ok(result);
    }
    let mut hits = scored_hits(session, text, filters)?;
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
    cache_fuzzy(session, fingerprint, revision, hits)
}

/// Placement evidence for the fuzzy path: which of `memo_ids` still appear in
/// the same scored result set the pages are cut from, split by where they rank
/// against the reply window — `above` holds hit positions strictly before
/// `window_start`, `below` positions at or past `window_end`, both in hit
/// order. An absent id left the result set.
///
/// # Errors
///
/// Propagates projection reads, filter validation, and `stale_search_result`
/// when the projection moves mid-flight.
pub(crate) fn fuzzy_window_sides(
    session: &WorkspaceSession,
    text: &str,
    filters: &MemoFilters,
    memo_ids: &[String],
    window_start: u64,
    window_end: u64,
) -> Result<lomo_store::MemoWindowSides, LomoError> {
    let result = fuzzy_snapshot(session, text, filters)?;
    let mut ranked: Vec<(usize, &String)> = memo_ids
        .iter()
        .filter_map(|id| result.positions.get(id.as_str()).map(|pos| (*pos, id)))
        .collect();
    ranked.sort_by_key(|(pos, _)| *pos);
    let mut sides = lomo_store::MemoWindowSides::default();
    for (pos, id) in ranked {
        let pos = u64::try_from(pos)
            .map_err(|error| validation("search_count_overflow", error.to_string()))?;
        if pos < window_start {
            sides.above.push(id.clone());
        } else if pos >= window_end {
            sides.below.push(id.clone());
        }
    }
    Ok(sides)
}

fn cached_fuzzy(
    session: &WorkspaceSession,
    fingerprint: &str,
    revision: u64,
) -> Result<Option<FuzzyResult>, LomoError> {
    let mut results = session
        .fuzzy_results
        .lock()
        .map_err(|error| crate::error::storage("fuzzy_cache_poisoned", error.to_string()))?;
    let Some(position) = results
        .iter()
        .position(|entry| entry.fingerprint == fingerprint && entry.revision == revision)
    else {
        return Ok(None);
    };
    // A hit becomes most-recently-used — sequential pagination never evicts
    // the snapshot it is walking.
    let entry = results.remove(position).ok_or_else(|| {
        crate::error::storage("fuzzy_cache_lost", "memoized result vanished mid-read")
    })?;
    results.push_back(entry.clone());
    drop(results);
    Ok(Some(entry))
}

fn cache_fuzzy(
    session: &WorkspaceSession,
    fingerprint: String,
    revision: u64,
    hits: Vec<SearchHit>,
) -> Result<FuzzyResult, LomoError> {
    let positions = hits
        .iter()
        .enumerate()
        .map(|(index, hit)| (hit.memo_id.clone(), index))
        .collect();
    let result = FuzzyResult {
        fingerprint,
        revision,
        hits: std::sync::Arc::from(hits),
        positions: std::sync::Arc::new(positions),
    };
    let mut results = session
        .fuzzy_results
        .lock()
        .map_err(|error| crate::error::storage("fuzzy_cache_poisoned", error.to_string()))?;
    results.push_back(result.clone());
    while results.len() > FUZZY_CACHE_ENTRIES {
        results.pop_front();
    }
    drop(results);
    Ok(result)
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

fn paginate_fuzzy(request: &SearchRequest, result: &FuzzyResult) -> Result<SearchPage, LomoError> {
    let hits = &result.hits;
    let start = match (&request.cursor, &request.anchor) {
        (Some(cursor), _) => {
            cursor.validate_against(&result.fingerprint, result.revision)?;
            result
                .positions
                .get(&cursor.sort_memo_id)
                .copied()
                .ok_or_else(|| {
                    validation("stale_cursor", "fuzzy cursor memo is no longer a candidate")
                })?
                .saturating_add(1)
        }
        // `AtMemo` semantics for the scored order: resolve the identity's rank
        // inside this snapshot; a non-candidate falls back to head, never an
        // error.
        (None, Some(anchor)) => result.positions.get(anchor.as_str()).copied().unwrap_or(0),
        (None, None) => 0,
    };
    let limit = usize::try_from(request.page_size.get())
        .map_err(|error| validation("invalid_page_size", error.to_string()))?;
    let end = start.saturating_add(limit).min(hits.len());
    // `positions` indexes `hits` and `end` is clamped, so the slice is in
    // bounds by construction; a miss would mean the cached result tore.
    let page_items: Vec<SearchHit> = hits.get(start..end).unwrap_or(&[]).to_vec();
    let next_cursor = (end < hits.len())
        .then(|| page_items.last())
        .flatten()
        .map(|hit| {
            PageCursor::new(
                result.fingerprint.clone(),
                None,
                hit.summary.is_pinned,
                hit.summary.created_at_ms,
                hit.summary.created_at_ms,
                hit.memo_id.clone(),
                result.revision,
            )
        });
    Ok(SearchPage {
        query_epoch: request.query_epoch,
        mode: SearchMode::Fuzzy,
        items: page_items,
        next_cursor,
        prev_cursor: None,
        items_before: u64::try_from(start)
            .map_err(|error| validation("search_count_overflow", error.to_string()))?,
        items_after: u64::try_from(hits.len().saturating_sub(end))
            .map_err(|error| validation("search_count_overflow", error.to_string()))?,
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
