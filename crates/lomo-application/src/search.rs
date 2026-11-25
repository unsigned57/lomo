//! Dual-mode retrieval: FTS fulltext plus pinyin/fuzzy ranking with epoch cancellation.

use std::collections::HashMap;
use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex};

use lomo_core::{LomoError, PageSize};
use lomo_store::{MemoFilters, MemoQuery, MemoQueryStart, MemoSummary, PageCursor};
use serde::{Deserialize, Serialize};

use crate::{
    error::validation, paging::collect_summaries, search_excerpt::FuzzyMatcher,
    session::WorkspaceSession,
};

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
            // A mid-flight supersede aborts as `Cancelled` — the epoch is the
            // only cancellation source inside the fuzzy lane, so the category
            // maps straight back to `Discarded` and no partial page escapes.
            SearchMode::Fuzzy => match fuzzy(self, request) {
                Ok(page) => page,
                Err(error) if error.category() == lomo_core::ErrorCategory::Cancelled => {
                    let active_epoch = self.search_epoch.load(Ordering::Acquire);
                    return Ok(SearchOutcome::Discarded {
                        query_epoch: request.query_epoch,
                        active_epoch,
                    });
                }
                Err(error) => return Err(error),
            },
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
///
/// `positions` resolves a cursor's memo id to its rank without a linear scan.
/// `needle`/`filter_fingerprint` record what produced the set so a later,
/// strictly longer query can derive its candidates from this result instead of
/// rescanning the corpus: fuzzy hits are ordered-subsequence matches, so
/// extending the needle can only shrink the hit set.
#[derive(Clone, Debug)]
pub struct FuzzyResult {
    fingerprint: String,
    revision: u64,
    needle: String,
    filter_fingerprint: String,
    summaries: Arc<[MemoSummary]>,
    hits: Arc<[(usize, i64)]>,
    positions: Arc<HashMap<String, usize>>,
}

impl FuzzyResult {
    /// Number of ranked hits in the snapshot.
    #[doc(hidden)]
    #[must_use]
    pub fn hit_count(&self) -> usize {
        self.hits.len()
    }
}

/// Scoring is memoized per (fingerprint, revision): a fresh query string, a
/// changed filter or a projection write each key a new entry; pagination of
/// one query reuses its entry. The bound keeps every open query's snapshot
/// while evicting superseded revisions — least-recently-used first.
#[doc(hidden)]
pub const FUZZY_CACHE_ENTRIES: std::num::NonZeroUsize = match std::num::NonZeroUsize::new(4) {
    Some(entries) => entries,
    None => unreachable!(),
};

/// Observable counters behind `WorkspaceSession::fuzzy_scan_stats`.
///
/// The receipt each keystroke leaves: whether it reused a snapshot, derived
/// from a cached prefix ancestor, or paid a full corpus scan; whether a newer
/// epoch cut it short; and how many candidates the scorer touched.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct FuzzyScanStats {
    /// Queries served by an exact `(fingerprint, revision)` result.
    pub snapshot_hits: u64,
    /// Scans that rescored only a cached prefix-ancestor's hit set.
    pub seeded_scans: u64,
    /// Scans over the whole candidate corpus — no usable ancestor.
    pub full_scans: u64,
    /// Scans abandoned by a newer epoch; partial hits were dropped uncached.
    pub superseded_scans: u64,
    /// Per-candidate `fuzzy_excerpt` evaluations performed.
    pub candidates_scored: u64,
}

fn scan_lock(
    session: &WorkspaceSession,
) -> Result<std::sync::MutexGuard<'_, FuzzyScanStats>, LomoError> {
    session
        .fuzzy_scan
        .lock()
        .map_err(|error| crate::error::storage("fuzzy_scan_poisoned", error.to_string()))
}

fn fuzzy_scan_superseded() -> LomoError {
    crate::error::cancelled(
        "fuzzy_scan_superseded",
        "a newer search epoch superseded this scan; partial work was dropped",
    )
}

/// Body views are borrowed from one SQLite row. Each binding batch acquires its own checked
/// snapshot, so a rebuild can retire the reader between batches and cancellation drops the batch.
fn visit_fuzzy_bodies<'a, T>(
    session: &WorkspaceSession,
    revision: u64,
    mut candidates: impl Iterator<Item = Result<(&'a MemoSummary, T), LomoError>>,
    mut visit: impl FnMut(T, &MemoSummary, &str) -> Result<(), LomoError>,
) -> Result<(), LomoError> {
    loop {
        let mut batch: HashMap<_, _> = candidates
            .by_ref()
            .take(256)
            .map(|candidate| {
                candidate.map(|(summary, key)| (summary.memo_id.as_str(), (summary, key)))
            })
            .collect::<Result<_, _>>()?;
        if batch.is_empty() {
            return Ok(());
        }
        let ids: Vec<_> = batch.keys().copied().collect();
        session.with_reader(|store| {
            store.visit_projected_bodies(revision, &ids, |id, body| {
                let (summary, key) = batch.remove(id).ok_or_else(inconsistent_fuzzy_hit)?;
                visit(key, summary, body)
            })
        })?;
    }
}

fn inconsistent_fuzzy_hit() -> LomoError {
    validation(
        "fuzzy_snapshot_inconsistent",
        "ranked fuzzy hit does not resolve within its projection snapshot",
    )
}

fn score_fuzzy(
    session: &WorkspaceSession,
    revision: u64,
    summaries: &[MemoSummary],
    indices: impl Iterator<Item = usize>,
    matcher: &FuzzyMatcher,
    cancel: &dyn Fn() -> bool,
) -> Result<Vec<(usize, i64)>, LomoError> {
    let candidates = indices.map(|index| {
        summaries
            .get(index)
            .map(|summary| (summary, index))
            .ok_or_else(inconsistent_fuzzy_hit)
    });
    let mut hits = Vec::new();
    let mut evaluated = 0_u64;
    let outcome = visit_fuzzy_bodies(session, revision, candidates, |index, summary, body| {
        if cancel() {
            return Err(fuzzy_scan_superseded());
        }
        if let Some(score) = matcher.score(&summary.source_path, body) {
            hits.push((index, score));
        }
        evaluated += 1;
        Ok(())
    });
    scan_lock(session)?.candidates_scored += evaluated;
    outcome?;
    if cancel() {
        return Err(fuzzy_scan_superseded());
    }
    Ok(hits)
}

fn fuzzy(session: &WorkspaceSession, request: &SearchRequest) -> Result<SearchPage, LomoError> {
    let epoch = request.query_epoch;
    // The epoch is the cancellation source: `stale_epoch` claims `epoch`, so
    // a larger observed value means a newer query superseded this one — the
    // same `is_cancelled` check, polled inside every expensive loop.
    let cancel = || session.search_epoch.load(Ordering::Acquire) > epoch;
    let result = fuzzy_snapshot(session, &request.text, &request.filters, &cancel)?;
    paginate_fuzzy(session, request, &result, &cancel)
}

/// The scored, sorted hit set for `text`/`filters` at one projection revision —
/// shared between pagination and membership checks so both read the same
/// snapshot (cache hit or a fresh scored read).
#[doc(hidden)]
pub fn fuzzy_snapshot(
    session: &WorkspaceSession,
    text: &str,
    filters: &MemoFilters,
    cancel: &dyn Fn() -> bool,
) -> Result<FuzzyResult, LomoError> {
    let revision = session.with_store(|store| Ok(store.high_water_revision()))?;
    let filter_fingerprint = filters.fingerprint();
    let needle = text.trim();
    let fingerprint = format!("fuzzy|{needle}|{filter_fingerprint}");
    if let Some(result) = cached_fuzzy(&session.fuzzy_results, &fingerprint, revision)? {
        scan_lock(session)?.snapshot_hits += 1;
        return Ok(result);
    }
    // The longest cached needle that prefixes this one is the narrowest valid
    // seed: ordered-subsequence matching can only shrink a hit set as the
    // needle grows, so rescanning the ancestor's hits is equivalent to a full
    // scan — and bounded by the ancestor's hit count, not the corpus size.
    let outcome = (|| {
        let seed = fuzzy_prefix_base(
            &session.fuzzy_results,
            needle,
            &filter_fingerprint,
            revision,
        )?;
        let summaries = if let Some(base) = &seed {
            Arc::clone(&base.summaries)
        } else {
            let query = MemoQuery {
                search_text: None,
                filters: filters.clone(),
                sort: lomo_store::MemoSort::default(),
            };
            let walk = collect_summaries(session, &query, cancel)?;
            ensure_search_revision(revision, walk.revision)?;
            walk.items
        };
        let matcher = FuzzyMatcher::new(needle);
        let hits = if let Some(base) = &seed {
            score_fuzzy(
                session,
                revision,
                &summaries,
                base.hits.iter().map(|&(index, _)| index),
                &matcher,
                cancel,
            )?
        } else {
            score_fuzzy(
                session,
                revision,
                &summaries,
                0..summaries.len(),
                &matcher,
                cancel,
            )?
        };
        Ok::<_, LomoError>((summaries, hits, seed.is_some()))
    })();
    {
        let mut stats = scan_lock(session)?;
        match &outcome {
            Ok((_, _, true)) => stats.seeded_scans += 1,
            Ok((_, _, false)) => stats.full_scans += 1,
            Err(error) if error.category() == lomo_core::ErrorCategory::Cancelled => {
                stats.superseded_scans += 1;
            }
            Err(_) => {}
        }
        drop(stats);
    }
    let (summaries, hits, _) = outcome?;
    ensure_search_revision(
        revision,
        session.with_store(|store| Ok(store.high_water_revision()))?,
    )?;
    cache_fuzzy(
        &session.fuzzy_results,
        fingerprint,
        revision,
        needle.to_owned(),
        filter_fingerprint,
        summaries,
        hits,
    )
}

fn ensure_search_revision(expected: u64, actual: u64) -> Result<(), LomoError> {
    if expected != actual {
        return Err(validation(
            "stale_search_result",
            "projection changed while searching; repeat the query",
        ));
    }
    Ok(())
}

/// The narrowest already-scored set whose needle prefixes `needle` — the only
/// correct seed, since any non-prefix entry could exclude hits the new needle
/// still matches. `iter` never promotes: the seed is a spent ancestor, not the
/// live entry — its LRU position must age out naturally.
fn fuzzy_prefix_base(
    cache: &Mutex<lru::LruCache<(String, u64), FuzzyResult>>,
    needle: &str,
    filter_fingerprint: &str,
    revision: u64,
) -> Result<Option<FuzzyResult>, LomoError> {
    let cache = cache
        .lock()
        .map_err(|error| crate::error::storage("fuzzy_cache_poisoned", error.to_string()))?;
    Ok(cache
        .iter()
        .filter(|((_, cached_revision), result)| {
            *cached_revision == revision
                && result.filter_fingerprint == filter_fingerprint
                && result.needle.len() < needle.len()
                && needle.starts_with(result.needle.as_str())
        })
        .max_by_key(|(_, result)| result.needle.len())
        .map(|(_, result)| result.clone()))
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
    let result = fuzzy_snapshot(session, text, filters, crate::paging::NEVER_CANCELLED)?;
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

#[doc(hidden)]
pub fn cached_fuzzy(
    cache: &Mutex<lru::LruCache<(String, u64), FuzzyResult>>,
    fingerprint: &str,
    revision: u64,
) -> Result<Option<FuzzyResult>, LomoError> {
    let mut cache = cache
        .lock()
        .map_err(|error| crate::error::storage("fuzzy_cache_poisoned", error.to_string()))?;
    // `get` promotes the hit — sequential pagination never evicts the
    // snapshot it is walking.
    Ok(cache.get(&(fingerprint.to_owned(), revision)).cloned())
}

#[doc(hidden)]
pub fn cache_fuzzy(
    cache: &Mutex<lru::LruCache<(String, u64), FuzzyResult>>,
    fingerprint: String,
    revision: u64,
    needle: String,
    filter_fingerprint: String,
    summaries: Arc<[MemoSummary]>,
    hits: Vec<(usize, i64)>,
) -> Result<FuzzyResult, LomoError> {
    let mut ranked = hits
        .into_iter()
        .map(|(index, score)| {
            let summary = summaries.get(index).ok_or_else(inconsistent_fuzzy_hit)?;
            Ok::<_, LomoError>((index, score, summary))
        })
        .collect::<Result<Vec<_>, _>>()?;
    ranked.sort_unstable_by(|left, right| {
        right
            .1
            .cmp(&left.1)
            .then(right.2.created_at_ms.cmp(&left.2.created_at_ms))
            .then(left.2.memo_id.cmp(&right.2.memo_id))
    });
    let positions = ranked
        .iter()
        .enumerate()
        .map(|(position, &(_, _, summary))| (summary.memo_id.clone(), position))
        .collect();
    let hits = ranked
        .into_iter()
        .map(|(index, score, _)| (index, score))
        .collect();
    let result = FuzzyResult {
        fingerprint: fingerprint.clone(),
        revision,
        needle,
        filter_fingerprint,
        summaries,
        hits,
        positions: Arc::new(positions),
    };
    // `put` records the entry as most-recently-used and evicts the LRU tail
    // once capacity is exceeded — the bound lives in the cache, not here.
    let _replaced = cache
        .lock()
        .map_err(|error| crate::error::storage("fuzzy_cache_poisoned", error.to_string()))?
        .put((fingerprint, revision), result.clone());
    Ok(result)
}

fn paginate_fuzzy(
    session: &WorkspaceSession,
    request: &SearchRequest,
    result: &FuzzyResult,
    cancel: &dyn Fn() -> bool,
) -> Result<SearchPage, LomoError> {
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
    let window = hits.get(start..end).ok_or_else(inconsistent_fuzzy_hit)?;
    let candidates = window
        .iter()
        .enumerate()
        .map(|(position, &(index, score))| {
            let summary = result
                .summaries
                .get(index)
                .ok_or_else(inconsistent_fuzzy_hit)?;
            Ok::<_, LomoError>((summary, (position, score)))
        });
    let matcher = FuzzyMatcher::new(&result.needle);
    let mut page = Vec::with_capacity(window.len());
    visit_fuzzy_bodies(
        session,
        result.revision,
        candidates,
        |(position, score), summary, body| {
            if cancel() {
                return Err(fuzzy_scan_superseded());
            }
            let excerpt = matcher.excerpt(&summary.source_path, body, score)?;
            page.push((
                position,
                SearchHit {
                    memo_id: summary.memo_id.clone(),
                    score,
                    summary: summary.clone(),
                    excerpt,
                },
            ));
            Ok(())
        },
    )?;
    ensure_search_revision(
        result.revision,
        session.with_store(|store| Ok(store.high_water_revision()))?,
    )?;
    page.sort_unstable_by_key(|(position, _)| *position);
    let page_items: Vec<_> = page.into_iter().map(|(_, item)| item).collect();
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
