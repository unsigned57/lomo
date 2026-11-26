//! Bounded keyset collectors, `MemoSort` evaluation, `AtMemo`, count, and window-side proofs.

use std::cmp::Ordering;
use std::collections::BinaryHeap;

use sha2::{Digest, Sha256};
use tantivy::collector::{Collector, Count, SegmentCollector};
use tantivy::columnar::{Column, StrColumn};
use tantivy::query::{BooleanQuery, ConstScoreQuery, Occur, Query, TermQuery, TermSetQuery};
use tantivy::schema::IndexRecordOption;
use tantivy::{DocId, Score, Searcher, SegmentOrdinal, SegmentReader, Term};

use crate::cursor::PageCursor;
use crate::error::{corruption, storage, validation};
use crate::query::{
    MemoQueryBoundary, MemoQueryStart, MemoSort, MemoSortField, MemoWindowSides, SortDirection,
};

use super::schema::{
    FIELD_CREATED_AT_MS, FIELD_IS_PINNED, FIELD_MEMO_ID, FIELD_UPDATED_AT_MS, SearchFields,
};

/// Total-ordered sort key computed from Tantivy fast fields and relevance score.
#[derive(Debug, Clone)]
pub struct RankedDocKey {
    pub rank: Option<f64>,
    pub is_pinned: bool,
    pub primary_ms: i64,
    pub secondary_ms: i64,
    pub memo_id: String,
}

impl PartialEq for RankedDocKey {
    fn eq(&self, other: &Self) -> bool {
        self.rank.map(f64::to_bits) == other.rank.map(f64::to_bits)
            && self.is_pinned == other.is_pinned
            && self.primary_ms == other.primary_ms
            && self.secondary_ms == other.secondary_ms
            && self.memo_id == other.memo_id
    }
}

impl Eq for RankedDocKey {}

/// Result of collecting one bounded search page plus exact rank window counts.
#[derive(Debug, Clone)]
pub struct CollectedSearchPage {
    pub items: Vec<RankedDocKey>,
    pub total: u64,
    pub items_before: u64,
    pub items_after: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum CollectMode {
    Head,
    ExclusiveAfter,
    InclusiveAfter,
    ExclusiveBefore,
}

#[derive(Clone, Copy)]
struct NumericPrefix {
    rank: Option<f64>,
    is_pinned: bool,
    primary_ms: i64,
    secondary_ms: i64,
}

#[derive(Clone)]
pub(crate) struct SortEvaluator {
    field: MemoSortField,
    direction: SortDirection,
    seed: i64,
    review_zone: Option<jiff::tz::TimeZone>,
    has_query: bool,
}

impl SortEvaluator {
    pub(crate) fn new(sort: &MemoSort, has_query: bool) -> Result<Self, lomo_core::LomoError> {
        let review_zone = if sort.field == MemoSortField::ReviewMonth {
            Some(review_time_zone(&sort.time_zone)?)
        } else {
            None
        };
        Ok(Self {
            field: sort.field,
            direction: sort.direction,
            seed: sort.seed,
            review_zone,
            has_query,
        })
    }

    const fn is_review(&self) -> bool {
        matches!(
            self.field,
            MemoSortField::ReviewMonth | MemoSortField::ReviewRandom
        )
    }

    fn cmp_numeric_prefix(&self, left: NumericPrefix, right: NumericPrefix) -> Ordering {
        if self.has_query {
            let score_ord = match (left.rank, right.rank) {
                (Some(a), Some(b)) => b.total_cmp(&a),
                (Some(_), None) => Ordering::Less,
                (None, Some(_)) => Ordering::Greater,
                (None, None) => Ordering::Equal,
            };
            if score_ord != Ordering::Equal {
                return score_ord;
            }
        }
        let pin_ord = right.is_pinned.cmp(&left.is_pinned);
        if pin_ord != Ordering::Equal {
            return pin_ord;
        }
        let primary_ord = compare_in_direction(&left.primary_ms, &right.primary_ms, self.direction);
        if primary_ord != Ordering::Equal {
            return primary_ord;
        }
        compare_in_direction(&left.secondary_ms, &right.secondary_ms, self.direction)
    }

    fn cmp_forward(&self, left: &RankedDocKey, right: &RankedDocKey) -> Ordering {
        let prefix_ord = self.cmp_numeric_prefix(
            NumericPrefix {
                rank: left.rank,
                is_pinned: left.is_pinned,
                primary_ms: left.primary_ms,
                secondary_ms: left.secondary_ms,
            },
            NumericPrefix {
                rank: right.rank,
                is_pinned: right.is_pinned,
                primary_ms: right.primary_ms,
                secondary_ms: right.secondary_ms,
            },
        );
        if prefix_ord != Ordering::Equal {
            return prefix_ord;
        }
        match self.direction {
            SortDirection::Ascending => left.memo_id.cmp(&right.memo_id),
            SortDirection::Descending => right.memo_id.cmp(&left.memo_id),
        }
    }

    fn cmp_temporal_forward(&self, left: &RankedDocKey, right: &RankedDocKey) -> Ordering {
        let pin_ord = right.is_pinned.cmp(&left.is_pinned);
        if pin_ord != Ordering::Equal {
            return pin_ord;
        }
        let primary_ord = compare_in_direction(&left.primary_ms, &right.primary_ms, self.direction);
        if primary_ord != Ordering::Equal {
            return primary_ord;
        }
        let secondary_ord =
            compare_in_direction(&left.secondary_ms, &right.secondary_ms, self.direction);
        if secondary_ord != Ordering::Equal {
            return secondary_ord;
        }
        match self.direction {
            SortDirection::Ascending => left.memo_id.cmp(&right.memo_id),
            SortDirection::Descending => right.memo_id.cmp(&left.memo_id),
        }
    }

    fn key_from_cursor(&self, cursor: &PageCursor) -> Result<RankedDocKey, lomo_core::LomoError> {
        let rank = cursor.validated_sort_rank(self.has_query)?;
        Ok(RankedDocKey {
            rank,
            is_pinned: cursor.sort_pinned && !self.is_review(),
            primary_ms: cursor.sort_primary_ms,
            secondary_ms: cursor.sort_created_at_ms,
            memo_id: cursor.sort_memo_id.clone(),
        })
    }

    fn key_from_boundary(boundary: &MemoQueryBoundary) -> RankedDocKey {
        RankedDocKey {
            rank: None,
            is_pinned: boundary.sort_pinned,
            primary_ms: boundary.sort_primary_ms,
            secondary_ms: boundary.sort_created_at_ms,
            memo_id: boundary.sort_memo_id.clone(),
        }
    }

    fn build_non_review_prefix(
        &self,
        score: Score,
        is_pinned: bool,
        created_at_ms: i64,
        updated_at_ms: i64,
    ) -> Result<NumericPrefix, lomo_core::LomoError> {
        let rank = self.normalize_rank(score)?;
        let primary_ms = match self.field {
            MemoSortField::CreatedAt => created_at_ms,
            MemoSortField::UpdatedAt => updated_at_ms,
            MemoSortField::ReviewMonth => {
                let zone = self.review_zone.as_ref().ok_or_else(|| {
                    validation("invalid_review_time_zone", "missing review time zone")
                })?;
                review_month(created_at_ms, zone)?
            }
            MemoSortField::ReviewRandom => 0,
        };
        Ok(NumericPrefix {
            rank,
            is_pinned: is_pinned && !self.is_review(),
            primary_ms,
            secondary_ms: created_at_ms,
        })
    }

    fn normalize_rank(&self, score: Score) -> Result<Option<f64>, lomo_core::LomoError> {
        if !self.has_query {
            return Ok(None);
        }
        let val = f64::from(score);
        if !val.is_finite() {
            return Err(corruption(
                "non_finite_search_score",
                "Tantivy returned a non-finite score",
            ));
        }
        Ok(Some(val.max(f64::from(f32::MIN_POSITIVE))))
    }

    fn finish_doc_key(&self, prefix: NumericPrefix, memo_id: String) -> RankedDocKey {
        let primary_ms = if self.field == MemoSortField::ReviewRandom {
            review_random_key(&memo_id, self.seed)
        } else {
            prefix.primary_ms
        };
        let secondary_ms = if self.field == MemoSortField::ReviewMonth {
            review_random_key(&memo_id, self.seed)
        } else {
            prefix.secondary_ms
        };
        RankedDocKey {
            rank: prefix.rank,
            is_pinned: prefix.is_pinned,
            primary_ms,
            secondary_ms,
            memo_id,
        }
    }
}

fn compare_in_direction<T: Ord>(left: &T, right: &T, direction: SortDirection) -> Ordering {
    match direction {
        SortDirection::Ascending => left.cmp(right),
        SortDirection::Descending => right.cmp(left),
    }
}

/// Computes deterministic review shuffle hash key matching SQLite `lomo_review_key`.
#[must_use]
pub(crate) fn review_random_key(memo_id: &str, seed: i64) -> i64 {
    let mut hash = Sha256::new();
    hash.update(b"lomo-review-v1");
    hash.update(seed.to_be_bytes());
    hash.update(memo_id.as_bytes());
    let bytes: [u8; 32] = hash.finalize().into();
    let [
        first,
        second,
        third,
        fourth,
        fifth,
        sixth,
        seventh,
        eighth,
        ..,
    ] = bytes;
    i64::from_be_bytes([first, second, third, fourth, fifth, sixth, seventh, eighth])
}

/// Parses review timezone string using the workspace `jiff` normalization rules.
pub(crate) fn review_time_zone(name: &str) -> Result<jiff::tz::TimeZone, lomo_core::LomoError> {
    if matches!(
        name,
        "Z" | "UT"
            | "UTC"
            | "GMT"
            | "GMT0"
            | "UCT"
            | "Etc/UTC"
            | "Etc/GMT"
            | "Etc/GMT+0"
            | "Etc/GMT-0"
            | "Etc/GMT0"
    ) {
        return Ok(jiff::tz::TimeZone::UTC);
    }
    let normalized = ["UTC", "GMT", "UT"]
        .into_iter()
        .find_map(|prefix| name.strip_prefix(prefix))
        .filter(|tail| tail.starts_with(['+', '-']))
        .unwrap_or(name);
    jiff::fmt::temporal::DateTimeParser::new()
        .parse_time_zone(normalized)
        .map_err(|error| validation("invalid_review_time_zone", &error.to_string()))
}

/// Computes `(year * 12 + month)` in the target review timezone.
pub(crate) fn review_month(
    timestamp_ms: i64,
    zone: &jiff::tz::TimeZone,
) -> Result<i64, lomo_core::LomoError> {
    let timestamp = jiff::Timestamp::from_millisecond(timestamp_ms)
        .map_err(|error| validation("invalid_review_timestamp", &error.to_string()))?;
    let date = timestamp.to_zoned(zone.clone()).date();
    Ok(i64::from(date.year()) * 12 + i64::from(date.month()))
}

/// Executes a bounded keyset search page (handling `Head`, `After`, `Before`, `AtMemo`,
/// optional `boundary`, `items_before`, `items_after`, and `total`).
///
/// # Errors
///
/// Propagates invalid sort/review parameters, stale cursor checks, or Tantivy search errors.
#[expect(
    clippy::too_many_arguments,
    reason = "collection parameters mirror store query boundary contracts"
)]
pub fn collect_search_page(
    searcher: &Searcher,
    fields: &SearchFields,
    query: &dyn Query,
    sort: &MemoSort,
    has_query: bool,
    boundary: Option<&MemoQueryBoundary>,
    start: MemoQueryStart<'_>,
    limit: usize,
    query_fingerprint: &str,
    high_water_revision: u64,
) -> Result<CollectedSearchPage, lomo_core::LomoError> {
    let evaluator = SortEvaluator::new(sort, has_query)?;
    let boundary_key = boundary.map(SortEvaluator::key_from_boundary);

    let (mode, bound_key) = match start {
        MemoQueryStart::Head => (CollectMode::Head, None),
        MemoQueryStart::After(cursor) => {
            cursor.validate_against(query_fingerprint, high_water_revision)?;
            (
                CollectMode::ExclusiveAfter,
                Some(evaluator.key_from_cursor(cursor)?),
            )
        }
        MemoQueryStart::Before(cursor) => {
            cursor.validate_against(query_fingerprint, high_water_revision)?;
            (
                CollectMode::ExclusiveBefore,
                Some(evaluator.key_from_cursor(cursor)?),
            )
        }
        MemoQueryStart::AtMemo(memo_id) => collect_at_memo_key(
            searcher,
            fields,
            query,
            &evaluator,
            boundary_key.as_ref(),
            memo_id,
        )?
        .map_or((CollectMode::Head, None), |target_key| {
            (CollectMode::InclusiveAfter, Some(target_key))
        }),
    };

    let collector = SearchPageCollector {
        evaluator: evaluator.clone(),
        boundary_key,
        mode,
        bound_key,
        limit,
    };
    let fruit = searcher
        .search(query, &collector)
        .map_err(|error| storage("tantivy_search_failed", &format!("search failed: {error}")))?;
    if let Some(error) = fruit.error {
        return Err(error);
    }

    let total = fruit.total;
    let before_bound = fruit.before_bound;
    let equal_bound = fruit.equal_bound;
    let mut items = fruit.heap_items;
    items.sort_by(|left, right| evaluator.cmp_forward(left, right));
    let count = u64::try_from(items.len())
        .map_err(|error| corruption("page_len_overflow", &error.to_string()))?;

    let (items_before, items_after) =
        compute_page_ranks(mode, before_bound, equal_bound, count, total)?;
    Ok(CollectedSearchPage {
        items,
        total,
        items_before,
        items_after,
    })
}

/// Counts total matching memos under optional boundary constraints.
///
/// # Errors
///
/// Propagates invalid sort/review parameters or Tantivy search errors.
pub fn collect_search_count(
    searcher: &Searcher,
    fields: &SearchFields,
    query: &dyn Query,
    sort: &MemoSort,
    boundary: Option<&MemoQueryBoundary>,
) -> Result<u64, lomo_core::LomoError> {
    if boundary.is_none() {
        let count = searcher
            .search(query, &Count)
            .map_err(|error| storage("tantivy_count_failed", &format!("count failed: {error}")))?;
        return u64::try_from(count)
            .map_err(|error| corruption("search_count_overflow", &error.to_string()));
    }
    let page = collect_search_page(
        searcher,
        fields,
        query,
        sort,
        false,
        boundary,
        MemoQueryStart::Head,
        0,
        "",
        0,
    )?;
    Ok(page.total)
}

/// Computes `MemoWindowSides` (`above` < `head`, `below` > `tail` in forward query order) for
/// `memo_ids` using a bounded [`TermSetQuery`].
///
/// # Errors
///
/// Propagates cursor rank validation, review sort errors, or Tantivy search errors.
#[expect(
    clippy::too_many_arguments,
    reason = "window-side check matches Store::matching_memo_window parameter set"
)]
pub fn collect_window_sides(
    searcher: &Searcher,
    fields: &SearchFields,
    query: &dyn Query,
    sort: &MemoSort,
    has_query: bool,
    memo_ids: &[String],
    head: Option<&PageCursor>,
    tail: Option<&PageCursor>,
) -> Result<MemoWindowSides, lomo_core::LomoError> {
    if memo_ids.is_empty() || (head.is_none() && tail.is_none()) {
        return Ok(MemoWindowSides::default());
    }
    let evaluator = SortEvaluator::new(sort, has_query)?;
    let head_key = head
        .map(|cursor| evaluator.key_from_cursor(cursor))
        .transpose()?;
    let tail_key = tail
        .map(|cursor| evaluator.key_from_cursor(cursor))
        .transpose()?;

    let id_terms = memo_ids
        .iter()
        .map(|memo_id| Term::from_field_text(fields.memo_id, memo_id));
    let restricted = BooleanQuery::new(vec![
        (
            Occur::Must,
            Box::new(ConstScoreQuery::new(
                Box::new(TermSetQuery::new(id_terms)),
                0.0,
            )),
        ),
        (Occur::Must, query.box_clone()),
    ]);

    let page = collect_all_matching_keys(searcher, &restricted, &evaluator, None)?;
    let mut above_keys = Vec::new();
    let mut below_keys = Vec::new();
    for key in page {
        if let Some(ref h) = head_key
            && evaluator.cmp_forward(&key, h) == Ordering::Less
        {
            above_keys.push(key.clone());
        }
        if let Some(ref t) = tail_key
            && evaluator.cmp_forward(&key, t) == Ordering::Greater
        {
            below_keys.push(key);
        }
    }
    above_keys.sort_by(|left, right| evaluator.cmp_forward(left, right));
    below_keys.sort_by(|left, right| evaluator.cmp_forward(left, right));
    Ok(MemoWindowSides {
        above: above_keys.into_iter().map(|key| key.memo_id).collect(),
        below: below_keys.into_iter().map(|key| key.memo_id).collect(),
    })
}

fn collect_at_memo_key(
    searcher: &Searcher,
    fields: &SearchFields,
    query: &dyn Query,
    evaluator: &SortEvaluator,
    boundary_key: Option<&RankedDocKey>,
    memo_id: &str,
) -> Result<Option<RankedDocKey>, lomo_core::LomoError> {
    let target = ConstScoreQuery::new(
        Box::new(TermQuery::new(
            Term::from_field_text(fields.memo_id, memo_id),
            IndexRecordOption::Basic,
        )),
        0.0,
    );
    let intersected = BooleanQuery::new(vec![
        (Occur::Must, Box::new(target)),
        (Occur::Must, query.box_clone()),
    ]);
    let keys = collect_all_matching_keys(searcher, &intersected, evaluator, boundary_key)?;
    Ok(keys.into_iter().next())
}

fn collect_all_matching_keys(
    searcher: &Searcher,
    query: &dyn Query,
    evaluator: &SortEvaluator,
    boundary_key: Option<&RankedDocKey>,
) -> Result<Vec<RankedDocKey>, lomo_core::LomoError> {
    let collector = SearchPageCollector {
        evaluator: evaluator.clone(),
        boundary_key: boundary_key.cloned(),
        mode: CollectMode::Head,
        bound_key: None,
        limit: usize::MAX,
    };
    let fruit = searcher
        .search(query, &collector)
        .map_err(|error| storage("tantivy_search_failed", &format!("search failed: {error}")))?;
    if let Some(error) = fruit.error {
        return Err(error);
    }
    Ok(fruit.heap_items)
}

fn compute_page_ranks(
    mode: CollectMode,
    before_bound: u64,
    equal_bound: u64,
    count: u64,
    total: u64,
) -> Result<(u64, u64), lomo_core::LomoError> {
    if count == 0 {
        return Ok(match mode {
            CollectMode::ExclusiveAfter => (total, 0),
            CollectMode::ExclusiveBefore => (0, total),
            CollectMode::Head | CollectMode::InclusiveAfter => (0, 0),
        });
    }
    let before = match mode {
        CollectMode::Head => 0,
        CollectMode::InclusiveAfter => before_bound,
        CollectMode::ExclusiveAfter => before_bound + equal_bound,
        CollectMode::ExclusiveBefore => before_bound.checked_sub(count).ok_or_else(|| {
            corruption(
                "inconsistent_page_rank",
                "before-cursor page count exceeded matching prefix",
            )
        })?,
    };
    let after = match mode {
        CollectMode::ExclusiveBefore => total.checked_sub(before_bound).ok_or_else(|| {
            corruption(
                "inconsistent_page_rank",
                "before-cursor prefix exceeded query count",
            )
        })?,
        CollectMode::Head | CollectMode::InclusiveAfter | CollectMode::ExclusiveAfter => total
            .checked_sub(before)
            .and_then(|rest| rest.checked_sub(count))
            .ok_or_else(|| {
                corruption(
                    "inconsistent_page_rank",
                    "page rank exceeded query count in the same snapshot",
                )
            })?,
    };
    Ok((before, after))
}

struct HeapItem {
    key: RankedDocKey,
    evaluator: SortEvaluator,
    invert: bool,
}

impl PartialEq for HeapItem {
    fn eq(&self, other: &Self) -> bool {
        self.key == other.key
    }
}

impl Eq for HeapItem {}

impl PartialOrd for HeapItem {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

impl Ord for HeapItem {
    fn cmp(&self, other: &Self) -> Ordering {
        let ord = self.evaluator.cmp_forward(&self.key, &other.key);
        if self.invert { ord.reverse() } else { ord }
    }
}

struct SearchPageCollector {
    evaluator: SortEvaluator,
    boundary_key: Option<RankedDocKey>,
    mode: CollectMode,
    bound_key: Option<RankedDocKey>,
    limit: usize,
}

struct SearchPageFruit {
    heap_items: Vec<RankedDocKey>,
    total: u64,
    before_bound: u64,
    equal_bound: u64,
    error: Option<lomo_core::LomoError>,
}

struct SegmentPageCollector {
    evaluator: SortEvaluator,
    boundary_key: Option<RankedDocKey>,
    mode: CollectMode,
    bound_key: Option<RankedDocKey>,
    limit: usize,
    memo_id_col: StrColumn,
    is_pinned_col: Column<u64>,
    created_at_col: Column<i64>,
    updated_at_col: Column<i64>,
    heap: BinaryHeap<HeapItem>,
    total: u64,
    before_bound: u64,
    equal_bound: u64,
    scratch: Vec<u8>,
    error: Option<lomo_core::LomoError>,
}

impl Collector for SearchPageCollector {
    type Fruit = SearchPageFruit;
    type Child = SegmentPageCollector;

    fn for_segment(
        &self,
        _segment_local_id: SegmentOrdinal,
        segment: &SegmentReader,
    ) -> tantivy::Result<Self::Child> {
        let fast = segment.fast_fields();
        let memo_id_col = fast.str(FIELD_MEMO_ID)?.ok_or_else(|| {
            tantivy::TantivyError::SchemaError("missing memo_id str fast field".to_owned())
        })?;
        let is_pinned_col = fast.u64(FIELD_IS_PINNED)?;
        let created_at_col = fast.i64(FIELD_CREATED_AT_MS)?;
        let updated_at_col = fast.i64(FIELD_UPDATED_AT_MS)?;
        Ok(SegmentPageCollector {
            evaluator: self.evaluator.clone(),
            boundary_key: self.boundary_key.clone(),
            mode: self.mode,
            bound_key: self.bound_key.clone(),
            limit: self.limit,
            memo_id_col,
            is_pinned_col,
            created_at_col,
            updated_at_col,
            heap: BinaryHeap::new(),
            total: 0,
            before_bound: 0,
            equal_bound: 0,
            scratch: Vec::with_capacity(64),
            error: None,
        })
    }

    fn requires_scoring(&self) -> bool {
        self.evaluator.has_query
    }

    fn merge_fruits(
        &self,
        segment_fruits: Vec<SearchPageFruit>,
    ) -> tantivy::Result<SearchPageFruit> {
        let invert = self.mode == CollectMode::ExclusiveBefore;
        let mut heap: BinaryHeap<HeapItem> = BinaryHeap::new();
        let mut total = 0_u64;
        let mut before_bound = 0_u64;
        let mut equal_bound = 0_u64;
        let mut first_error = None;

        for part in segment_fruits {
            if first_error.is_none() && part.error.is_some() {
                first_error = part.error;
            }
            total += part.total;
            before_bound += part.before_bound;
            equal_bound += part.equal_bound;
            for key in part.heap_items {
                push_bounded_heap(&mut heap, key, &self.evaluator, invert, self.limit);
            }
        }

        Ok(SearchPageFruit {
            heap_items: heap.into_iter().map(|entry| entry.key).collect(),
            total,
            before_bound,
            equal_bound,
            error: first_error,
        })
    }
}

impl SegmentCollector for SegmentPageCollector {
    type Fruit = SearchPageFruit;

    fn collect(&mut self, doc: DocId, score: Score) {
        if self.error.is_some() {
            return;
        }
        let prefix = match self.extract_numeric_prefix(doc, score) {
            Ok(prefix) => prefix,
            Err(error) => {
                self.error = Some(error);
                return;
            }
        };

        // Deferred memo_id decode: when collecting a bounded Head page without review/boundary
        // and the heap is already full, a strictly worse numeric prefix cannot displace worst.
        if self.can_skip_for_head(prefix) {
            self.total += 1;
            return;
        }

        let memo_id = match self.decode_memo_id(doc) {
            Ok(id) => id,
            Err(error) => {
                self.error = Some(error);
                return;
            }
        };
        let key = self.evaluator.finish_doc_key(prefix, memo_id);

        if let Some(ref boundary) = self.boundary_key
            && self.evaluator.cmp_temporal_forward(&key, boundary) == Ordering::Less
        {
            return;
        }
        self.total += 1;
        self.route_key(key);
    }

    fn harvest(self) -> SearchPageFruit {
        SearchPageFruit {
            heap_items: self.heap.into_iter().map(|entry| entry.key).collect(),
            total: self.total,
            before_bound: self.before_bound,
            equal_bound: self.equal_bound,
            error: self.error,
        }
    }
}

impl SegmentPageCollector {
    fn extract_numeric_prefix(
        &self,
        doc: DocId,
        score: Score,
    ) -> Result<NumericPrefix, lomo_core::LomoError> {
        let is_pinned = self.is_pinned_col.first(doc).ok_or_else(|| {
            corruption("missing_search_fast_field", "missing is_pinned fast field")
        })? != 0;
        let created_at_ms = self.created_at_col.first(doc).ok_or_else(|| {
            corruption(
                "missing_search_fast_field",
                "missing created_at_ms fast field",
            )
        })?;
        let updated_at_ms = self.updated_at_col.first(doc).ok_or_else(|| {
            corruption(
                "missing_search_fast_field",
                "missing updated_at_ms fast field",
            )
        })?;
        self.evaluator
            .build_non_review_prefix(score, is_pinned, created_at_ms, updated_at_ms)
    }

    fn can_skip_for_head(&self, candidate: NumericPrefix) -> bool {
        if self.mode != CollectMode::Head
            || self.boundary_key.is_some()
            || self.evaluator.field == MemoSortField::ReviewRandom
            || self.evaluator.field == MemoSortField::ReviewMonth
            || self.heap.len() < self.limit
        {
            return false;
        }
        let Some(worst) = self.heap.peek() else {
            return false;
        };
        let worst_prefix = NumericPrefix {
            rank: worst.key.rank,
            is_pinned: worst.key.is_pinned,
            primary_ms: worst.key.primary_ms,
            secondary_ms: worst.key.secondary_ms,
        };
        self.evaluator.cmp_numeric_prefix(candidate, worst_prefix) == Ordering::Greater
    }

    fn decode_memo_id(&mut self, doc: DocId) -> Result<String, lomo_core::LomoError> {
        let ord = self.memo_id_col.term_ords(doc).next().ok_or_else(|| {
            corruption(
                "missing_search_fast_field",
                "missing memo_id str fast field",
            )
        })?;
        self.scratch.clear();
        let found = self
            .memo_id_col
            .ord_to_bytes(ord, &mut self.scratch)
            .map_err(|error| {
                storage(
                    "search_fast_field_read_failed",
                    &format!("failed to decode memo_id fast field: {error}"),
                )
            })?;
        if !found {
            return Err(corruption(
                "missing_search_fast_field",
                "memo_id dictionary ordinal absent",
            ));
        }
        String::from_utf8(self.scratch.clone()).map_err(|error| {
            corruption(
                "invalid_search_memo_id",
                &format!("memo_id fast field is not UTF-8: {error}"),
            )
        })
    }

    fn route_key(&mut self, key: RankedDocKey) {
        match self.mode {
            CollectMode::Head => {
                push_bounded_heap(&mut self.heap, key, &self.evaluator, false, self.limit);
            }
            CollectMode::InclusiveAfter | CollectMode::ExclusiveAfter => {
                let Some(ref bound) = self.bound_key else {
                    return;
                };
                match self.evaluator.cmp_forward(&key, bound) {
                    Ordering::Less => self.before_bound += 1,
                    Ordering::Equal if self.mode == CollectMode::ExclusiveAfter => {
                        self.equal_bound += 1;
                    }
                    Ordering::Equal | Ordering::Greater => {
                        push_bounded_heap(&mut self.heap, key, &self.evaluator, false, self.limit);
                    }
                }
            }
            CollectMode::ExclusiveBefore => {
                let Some(ref bound) = self.bound_key else {
                    return;
                };
                if self.evaluator.cmp_forward(&key, bound) == Ordering::Less {
                    self.before_bound += 1;
                    push_bounded_heap(&mut self.heap, key, &self.evaluator, true, self.limit);
                }
            }
        }
    }
}

fn push_bounded_heap(
    heap: &mut BinaryHeap<HeapItem>,
    key: RankedDocKey,
    evaluator: &SortEvaluator,
    invert: bool,
    limit: usize,
) {
    if limit == 0 {
        return;
    }
    let item = HeapItem {
        key,
        evaluator: evaluator.clone(),
        invert,
    };
    if heap.len() < limit {
        heap.push(item);
        return;
    }
    if let Some(worst) = heap.peek()
        && item < *worst
    {
        let _discarded = heap.pop();
        heap.push(item);
    }
}
