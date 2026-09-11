//! Dual-mode retrieval: FTS fulltext plus pinyin/fuzzy ranking with epoch cancellation.

use std::sync::atomic::Ordering;

use lomo_core::{LomoError, PageSize};
use lomo_store::{MemoQuery, MemoSummary, PageCursor};
use pinyin::ToPinyin;
use serde::{Deserialize, Serialize};

use crate::error::validation;
use crate::paging::{collect_summaries, default_query};
use crate::session::WorkspaceSession;

const FUZZY_THRESHOLD: i64 = 10;

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SearchMode {
    Fulltext,
    Fuzzy,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct SearchRequest {
    pub query_epoch: u64,
    pub mode: SearchMode,
    pub text: String,
    pub cursor: Option<PageCursor>,
    pub page_size: PageSize,
}

#[derive(Clone, Debug, PartialEq)]
pub struct SearchHit {
    pub memo_id: String,
    pub score: i64,
    pub summary: MemoSummary,
}

#[derive(Clone, Debug, PartialEq)]
pub struct SearchPage {
    pub query_epoch: u64,
    pub mode: SearchMode,
    pub items: Vec<SearchHit>,
    pub next_cursor: Option<PageCursor>,
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

fn fulltext(session: &WorkspaceSession, request: &SearchRequest) -> Result<SearchPage, LomoError> {
    let query = MemoQuery {
        search_text: nonempty(&request.text),
        filters: lomo_store::MemoFilters::default(),
        sort: lomo_store::MemoSort::default(),
    };
    let page = session.with_store(|store| {
        store.query_memos(&query, request.cursor.as_ref(), request.page_size)
    })?;
    Ok(SearchPage {
        query_epoch: request.query_epoch,
        mode: SearchMode::Fulltext,
        items: page
            .items
            .into_iter()
            .map(|summary| SearchHit {
                score: rank_score(summary.rank),
                memo_id: summary.memo_id.clone(),
                summary,
            })
            .collect(),
        next_cursor: page.next_cursor,
    })
}

fn fuzzy(session: &WorkspaceSession, request: &SearchRequest) -> Result<SearchPage, LomoError> {
    let mut hits = scored_hits(session, &request.text)?;
    hits.sort_by(|left, right| {
        right
            .score
            .cmp(&left.score)
            .then(right.summary.created_at_ms.cmp(&left.summary.created_at_ms))
            .then(left.memo_id.cmp(&right.memo_id))
    });
    paginate_fuzzy(request, &hits)
}

fn scored_hits(session: &WorkspaceSession, text: &str) -> Result<Vec<SearchHit>, LomoError> {
    let summaries = collect_summaries(session, &default_query())?;
    let mut hits = Vec::new();
    let needle = text.trim();
    for summary in summaries {
        let snapshot = session
            .with_store(|store| store.get_projected_memo(&summary.memo_id))?
            .ok_or_else(|| validation("memo_not_found", "search candidate disappeared"))?;
        let score = if needle.is_empty() {
            0
        } else {
            score_memo(&summary, &snapshot.body, needle)
        };
        if needle.is_empty() || score >= FUZZY_THRESHOLD {
            hits.push(SearchHit {
                memo_id: summary.memo_id.clone(),
                score,
                summary,
            });
        }
    }
    Ok(hits)
}

fn paginate_fuzzy(request: &SearchRequest, hits: &[SearchHit]) -> Result<SearchPage, LomoError> {
    let fingerprint = format!("fuzzy|{}|{}", request.text.trim(), request.query_epoch);
    let start = match &request.cursor {
        None => 0,
        Some(cursor) => {
            if cursor.query_fingerprint != fingerprint {
                return Err(validation(
                    "stale_cursor",
                    "fuzzy cursor does not match this query",
                ));
            }
            hits.iter()
                .position(|hit| hit.memo_id == cursor.sort_memo_id)
                .map_or(0, |index| index.saturating_add(1))
        }
    };
    let limit = usize::try_from(request.page_size.get())
        .map_err(|error| validation("invalid_page_size", error.to_string()))?;
    let end = start.saturating_add(limit).min(hits.len());
    let page_items: Vec<SearchHit> = hits.get(start..end).unwrap_or(&[]).to_vec();
    let next_cursor = hits.get(end).map(|hit| {
        PageCursor::new(
            fingerprint,
            None,
            hit.summary.is_pinned,
            hit.summary.created_at_ms,
            hit.summary.created_at_ms,
            hit.memo_id.clone(),
            0,
        )
    });
    Ok(SearchPage {
        query_epoch: request.query_epoch,
        mode: SearchMode::Fuzzy,
        items: page_items,
        next_cursor,
    })
}

fn score_memo(summary: &MemoSummary, body: &str, needle: &str) -> i64 {
    let raw = format!("{} {}", summary.source_path, body);
    let pinyin = pinyin_index(&raw);
    fuzzy_score(&raw, needle).max(fuzzy_score(&pinyin, needle))
}

fn pinyin_index(content: &str) -> String {
    let mut full = String::new();
    let mut abbr = String::new();
    for syllable in content.to_pinyin().flatten() {
        full.push_str(syllable.plain());
        abbr.push_str(syllable.first_letter());
    }
    format!("{full} {abbr}")
}

fn fuzzy_score(haystack: &str, needle: &str) -> i64 {
    let hay: Vec<char> = haystack.chars().flat_map(char::to_lowercase).collect();
    let ned: Vec<char> = needle.chars().flat_map(char::to_lowercase).collect();
    if ned.is_empty() || ned.len() > hay.len() {
        return 0;
    }
    if hay
        .windows(ned.len())
        .any(|window| window == ned.as_slice())
    {
        return 80_i64.saturating_add(i64::try_from(ned.len()).unwrap_or(i64::MAX));
    }
    subsequence_score(&hay, &ned)
}

fn subsequence_score(hay: &[char], ned: &[char]) -> i64 {
    let mut score = 0_i64;
    let mut consecutive = 0_i64;
    let mut index = 0_usize;
    for needle in ned {
        let mut found = false;
        while index < hay.len() {
            let Some(hay_ch) = hay.get(index) else {
                break;
            };
            index = index.saturating_add(1);
            if hay_ch == needle {
                consecutive = consecutive.saturating_add(1);
                score = score
                    .saturating_add(8)
                    .saturating_add(consecutive.saturating_mul(4));
                found = true;
                break;
            }
            consecutive = 0;
        }
        if !found {
            return 0;
        }
    }
    score
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
