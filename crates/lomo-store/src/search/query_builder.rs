//! Tantivy query construction and validation for `Fulltext` and `Fuzzy` search modes.

use std::fmt::Write as _;
use std::ops::Bound;

use tantivy::Term;
use tantivy::query::{
    AllQuery, BooleanQuery, BoostQuery, ConstScoreQuery, FuzzyTermQuery, InvertedIndexRangeQuery,
    Occur, PhraseQuery, Query, RangeQuery, RegexQuery, TermQuery,
};
use tantivy::schema::{Field, IndexRecordOption};

use crate::cursor::fingerprint_query;
use crate::error::validation;
use crate::query::{MemoFilters, MemoQueryBoundary, MemoSort, TagSelectionMode};
use crate::schema::TOKENIZER_VERSION;

use super::schema::{DOC_KIND_MEMO, SEARCH_SCHEMA_VERSION, SearchFields};
use super::tokenizer::{analyze_charabia_tokens, analyze_cjk_bigrams, is_cjk, is_emoji_char};

pub const MAX_SEARCH_QUERY_BYTES: usize = 4_096;
pub const MAX_SUBSEQUENCE_LEN: usize = 24;
pub const MAX_PINYIN_QUERY_LEN: usize = 32;

const BOOST_EXACT: f32 = 6.0;
const BOOST_PHRASE: f32 = 8.0;
const BOOST_PREFIX: f32 = 3.0;
const BOOST_CJK_BIGRAM: f32 = 1.5;
const BOOST_FUZZY: f32 = 3.0;
const BOOST_FUZZY_PREFIX: f32 = 2.0;
const BOOST_SUBSEQUENCE: f32 = 1.0;
const BOOST_PATH_EXACT: f32 = 2.5;
const BOOST_PATH_PREFIX: f32 = 1.5;
const BOOST_PATH_FUZZY: f32 = 1.0;
const BOOST_PATH_SUBSEQ: f32 = 0.5;
const BOOST_PINYIN_EXACT: f32 = 4.0;
const BOOST_PINYIN_PREFIX: f32 = 2.5;
const BOOST_PINYIN_INITIAL_PREFIX: f32 = 2.0;
const BOOST_PINYIN_FUZZY: f32 = 1.5;

/// Search matching semantics selected by the user or session API.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StoreSearchMode {
    Fulltext,
    Fuzzy,
}

impl StoreSearchMode {
    const fn tag(self) -> &'static str {
        match self {
            Self::Fulltext => "fulltext",
            Self::Fuzzy => "fuzzy",
        }
    }
}

/// Canonical query IR shared between Tantivy query compilation and excerpt highlighting.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchQueryPlan {
    pub chunks: Vec<SearchChunkPlan>,
}

/// One whitespace-delimited query chunk decomposed into CJK and non-CJK spans.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchChunkPlan {
    pub spans: Vec<SearchSpanPlan>,
}

/// Analyzed span inside one query chunk.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SearchSpanPlan {
    CjkRun {
        charabia_tokens: Vec<String>,
        bigrams: Vec<String>,
        unigrams: Vec<String>,
    },
    NonCjk {
        tokens: Vec<String>,
        raw_path: Option<String>,
    },
}

impl SearchQueryPlan {
    /// Parses and validates raw search text into the canonical structured query plan.
    ///
    /// # Errors
    ///
    /// Fails closed with `query_too_long` if trimmed query text exceeds [`MAX_SEARCH_QUERY_BYTES`].
    pub fn parse(text: Option<&str>) -> Result<Self, lomo_core::LomoError> {
        let Some(query_str) = validate_search_text(text)? else {
            return Ok(Self { chunks: Vec::new() });
        };

        let mut chunks = Vec::new();
        for chunk in query_str.split_whitespace() {
            if let Some(parsed) = parse_chunk_plan(chunk) {
                chunks.push(parsed);
            }
        }
        Ok(Self { chunks })
    }

    /// True when the query contains no searchable spans after trimming/tokenization.
    #[must_use]
    pub const fn is_empty(&self) -> bool {
        self.chunks.is_empty()
    }

    /// Canonical length-prefixed encoding of every chunk, span, and token preserving multiplicity
    /// and structural grouping so cursor fingerprints match actual retrieval and BM25 scoring.
    #[must_use]
    pub fn fingerprint_key(&self) -> Option<String> {
        if self.chunks.is_empty() {
            return None;
        }
        let mut out = String::new();
        for chunk in &self.chunks {
            out.push_str("C[");
            for span in &chunk.spans {
                match span {
                    SearchSpanPlan::CjkRun {
                        charabia_tokens,
                        bigrams,
                        unigrams,
                    } => {
                        out.push_str("J(");
                        write_len_items(&mut out, 'c', charabia_tokens);
                        write_len_items(&mut out, 'b', bigrams);
                        write_len_items(&mut out, 'u', unigrams);
                        out.push(')');
                    }
                    SearchSpanPlan::NonCjk { tokens, raw_path } => {
                        out.push_str("N(");
                        write_len_items(&mut out, 't', tokens);
                        if let Some(path) = raw_path {
                            let _written = write!(out, "p:{}:{path};", path.len());
                        }
                        out.push(')');
                    }
                }
            }
            out.push(']');
        }
        Some(out)
    }
}

fn write_len_items(out: &mut String, tag: char, items: &[String]) {
    for item in items {
        let _written = write!(out, "{tag}:{}:{item};", item.len());
    }
}

fn parse_chunk_plan(chunk: &str) -> Option<SearchChunkPlan> {
    let spans: Vec<SearchSpanPlan> = split_chunk_spans(chunk)
        .into_iter()
        .filter_map(parse_span_plan)
        .collect();
    (!spans.is_empty()).then_some(SearchChunkPlan { spans })
}

fn parse_span_plan(span: ChunkSpan) -> Option<SearchSpanPlan> {
    match span {
        ChunkSpan::CjkRun(run) => {
            let charabia_tokens: Vec<String> = analyze_charabia_tokens(&run)
                .into_iter()
                .map(|token| token.text)
                .collect();
            let mut bigrams = Vec::new();
            let mut unigrams = Vec::new();
            for token in analyze_cjk_bigrams(&run) {
                match token.text.chars().count() {
                    2 => bigrams.push(token.text),
                    1 => unigrams.push(token.text),
                    _ => {}
                }
            }
            if charabia_tokens.is_empty() && bigrams.is_empty() && unigrams.is_empty() {
                None
            } else {
                Some(SearchSpanPlan::CjkRun {
                    charabia_tokens,
                    bigrams,
                    unigrams,
                })
            }
        }
        ChunkSpan::NonCjkSlice(slice) => {
            let tokens: Vec<String> = analyze_charabia_tokens(&slice)
                .into_iter()
                .filter(|token| !token.text.is_empty())
                .map(|token| token.text)
                .collect();
            let raw_lower = slice.to_ascii_lowercase();
            let raw_path = raw_lower
                .contains(['_', '-', '.', '/'])
                .then_some(raw_lower);
            if tokens.is_empty() && raw_path.is_none() {
                None
            } else {
                Some(SearchSpanPlan::NonCjk { tokens, raw_path })
            }
        }
    }
}

/// Compiled Tantivy query together with its structural fingerprint input.
pub struct BuiltTantivyQuery {
    pub has_query: bool,
    pub query_fingerprint_key: Option<String>,
    pub query: Box<dyn Query>,
}

/// Validates filter bounds shared across SQLite and Tantivy queries.
pub(crate) fn validate_filters(filters: &MemoFilters) -> Result<(), lomo_core::LomoError> {
    if let Some(tag) = &filters.tag
        && (tag.is_empty() || tag.len() > 128 || tag.contains('\'') || tag.contains('\0'))
    {
        return Err(validation("invalid_tag_filter", "tag filter is invalid"));
    }
    Ok(())
}

/// Validates and trims optional raw search input without allocating query trees.
pub(crate) fn validate_search_text(
    text: Option<&str>,
) -> Result<Option<String>, lomo_core::LomoError> {
    let Some(raw) = text else {
        return Ok(None);
    };
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Ok(None);
    }
    if trimmed.len() > MAX_SEARCH_QUERY_BYTES {
        return Err(validation(
            "query_too_long",
            "search query exceeds 4096 UTF-8 bytes",
        ));
    }
    Ok(Some(trimmed.to_owned()))
}

/// Computes a deterministic cursor fingerprint coupling structural query plan, mode, filters,
/// sort, boundary, schema version, tokenizer version, and the exact Tantivy scoring snapshot.
#[must_use]
pub fn search_fingerprint(
    query_key: Option<&str>,
    mode: StoreSearchMode,
    filters: &MemoFilters,
    sort: &MemoSort,
    boundary: Option<&MemoQueryBoundary>,
    index_snapshot: &str,
) -> String {
    let filter_fp = format!(
        "mode={}|schema={}|{}|sort={}|boundary={}|snapshot={}:{index_snapshot}",
        mode.tag(),
        SEARCH_SCHEMA_VERSION,
        filters.fingerprint(),
        sort.fingerprint(),
        boundary.map_or_else(|| "none".to_owned(), MemoQueryBoundary::fingerprint),
        index_snapshot.len(),
    );
    fingerprint_query(query_key, &filter_fp, TOKENIZER_VERSION)
}

/// Compiles filters + optional `Fulltext`/`Fuzzy` query clauses into one scored Tantivy [`Query`].
///
/// Filter clauses are wrapped in [`ConstScoreQuery`] with score `0.0` so filters never perturb
/// relevance ranking, leaving text channels purely positive when `has_query` is true.
///
/// # Errors
///
/// Returns validation errors for invalid tag filters, oversized queries, or invalid fuzzy regex patterns.
pub fn build_tantivy_query(
    fields: &SearchFields,
    text: Option<&str>,
    mode: StoreSearchMode,
    filters: &MemoFilters,
) -> Result<BuiltTantivyQuery, lomo_core::LomoError> {
    validate_filters(filters)?;
    let plan = SearchQueryPlan::parse(text)?;
    let filter_query = build_filter_query(fields, filters);

    if plan.is_empty() {
        return Ok(BuiltTantivyQuery {
            has_query: false,
            query_fingerprint_key: None,
            query: Box::new(ConstScoreQuery::new(filter_query, 0.0)),
        });
    }

    let mut chunk_queries = Vec::with_capacity(plan.chunks.len());
    for chunk in &plan.chunks {
        if let Some(chunk_query) = compile_chunk_plan(fields, chunk, mode)? {
            chunk_queries.push((Occur::Must, chunk_query));
        }
    }

    if chunk_queries.is_empty() {
        return Ok(BuiltTantivyQuery {
            has_query: false,
            query_fingerprint_key: None,
            query: Box::new(ConstScoreQuery::new(filter_query, 0.0)),
        });
    }

    let query_fingerprint_key = plan.fingerprint_key();
    let text_query = Box::new(BooleanQuery::new(chunk_queries));
    let combined = BooleanQuery::new(vec![
        (
            Occur::Must,
            Box::new(ConstScoreQuery::new(filter_query, 0.0)),
        ),
        (Occur::Must, text_query),
    ]);

    Ok(BuiltTantivyQuery {
        has_query: true,
        query_fingerprint_key,
        query: Box::new(combined),
    })
}

fn build_filter_query(fields: &SearchFields, filters: &MemoFilters) -> Box<dyn Query> {
    let mut clauses: Vec<(Occur, Box<dyn Query>)> = vec![(
        Occur::Must,
        Box::new(TermQuery::new(
            Term::from_field_u64(fields.doc_kind, DOC_KIND_MEMO),
            IndexRecordOption::Basic,
        )),
    )];

    if filters.trash_only {
        clauses.push((Occur::Must, u64_term_query(fields.is_trashed, 1)));
    } else if !filters.include_trash {
        clauses.push((Occur::Must, u64_term_query(fields.is_trashed, 0)));
    }
    if filters.pinned_only {
        clauses.push((Occur::Must, u64_term_query(fields.is_pinned, 1)));
    }
    if let Some(value) = filters.has_todo {
        clauses.push((
            Occur::Must,
            u64_term_query(fields.has_todo, u64::from(value)),
        ));
    }
    if let Some(value) = filters.has_attachment {
        clauses.push((
            Occur::Must,
            u64_term_query(fields.has_attachment, u64::from(value)),
        ));
    }
    if let Some(value) = filters.has_url {
        clauses.push((
            Occur::Must,
            u64_term_query(fields.has_url, u64::from(value)),
        ));
    }
    if filters.date_from_inclusive_ms.is_some() || filters.date_until_exclusive_ms.is_some() {
        let lower = filters
            .date_from_inclusive_ms
            .map_or(Bound::Unbounded, |ms| {
                Bound::Included(Term::from_field_i64(fields.created_at_ms, ms))
            });
        let upper = filters
            .date_until_exclusive_ms
            .map_or(Bound::Unbounded, |ms| {
                Bound::Excluded(Term::from_field_i64(fields.created_at_ms, ms))
            });
        clauses.push((Occur::Must, Box::new(RangeQuery::new(lower, upper))));
    }
    if let Some(tag) = &filters.tag {
        let field = match filters.tag_selection {
            TagSelectionMode::Exact => fields.tag_exact,
            TagSelectionMode::Subtree => fields.tag_subtree,
        };
        clauses.push((
            Occur::Must,
            Box::new(TermQuery::new(
                Term::from_field_text(field, tag),
                IndexRecordOption::Basic,
            )),
        ));
    }

    if clauses.is_empty() {
        Box::new(AllQuery)
    } else {
        Box::new(BooleanQuery::new(clauses))
    }
}

fn u64_term_query(field: Field, value: u64) -> Box<dyn Query> {
    Box::new(TermQuery::new(
        Term::from_field_u64(field, value),
        IndexRecordOption::Basic,
    ))
}

#[derive(Debug, Clone)]
enum ChunkSpan {
    CjkRun(String),
    NonCjkSlice(String),
}

fn compile_chunk_plan(
    fields: &SearchFields,
    chunk: &SearchChunkPlan,
    mode: StoreSearchMode,
) -> Result<Option<Box<dyn Query>>, lomo_core::LomoError> {
    let mut sub_clauses: Vec<(Occur, Box<dyn Query>)> = Vec::new();
    for span in &chunk.spans {
        match span {
            SearchSpanPlan::CjkRun {
                charabia_tokens,
                bigrams,
                unigrams,
            } => {
                if let Some(query) = build_cjk_run_query(fields, charabia_tokens, bigrams, unigrams)
                {
                    sub_clauses.push((Occur::Must, query));
                }
            }
            SearchSpanPlan::NonCjk { tokens, raw_path } => {
                let mut token_clauses: Vec<(Occur, Box<dyn Query>)> = Vec::new();
                for token in tokens {
                    if let Some(query) = build_word_or_emoji_query(fields, token, mode)? {
                        token_clauses.push((Occur::Must, query));
                    }
                }
                if let Some(raw_lower) = raw_path {
                    let raw_path_query = build_raw_path_clause(fields, raw_lower, mode)?;
                    if token_clauses.is_empty() {
                        sub_clauses.push((Occur::Must, raw_path_query));
                    } else {
                        let parsed_query: Box<dyn Query> =
                            Box::new(BooleanQuery::new(token_clauses));
                        sub_clauses.push((
                            Occur::Must,
                            Box::new(BooleanQuery::union(vec![parsed_query, raw_path_query])),
                        ));
                    }
                } else {
                    match token_clauses.len() {
                        0 => {}
                        1 => {
                            if let Some((_, single)) = token_clauses.pop() {
                                sub_clauses.push((Occur::Must, single));
                            }
                        }
                        _ => sub_clauses
                            .push((Occur::Must, Box::new(BooleanQuery::new(token_clauses)))),
                    }
                }
            }
        }
    }
    match sub_clauses.len() {
        0 => Ok(None),
        1 => Ok(sub_clauses.pop().map(|(_, single)| single)),
        _ => Ok(Some(Box::new(BooleanQuery::new(sub_clauses)))),
    }
}

fn build_raw_path_clause(
    fields: &SearchFields,
    raw_lower: &str,
    mode: StoreSearchMode,
) -> Result<Box<dyn Query>, lomo_core::LomoError> {
    let mut branches: Vec<Box<dyn Query>> = Vec::new();
    let raw_term = Term::from_field_text(fields.source_path_raw, raw_lower);
    branches.push(boosted(
        TermQuery::new(raw_term.clone(), IndexRecordOption::WithFreqs),
        BOOST_PATH_EXACT,
    ));
    branches.push(boosted(
        prefix_range_query(fields.source_path_raw, raw_lower),
        BOOST_PATH_PREFIX,
    ));
    if mode == StoreSearchMode::Fuzzy {
        let len = raw_lower.chars().count();
        if len >= 3 {
            let dist = if len >= 6 { 2 } else { 1 };
            branches.push(boosted(
                FuzzyTermQuery::new(raw_term, dist, true),
                BOOST_PATH_FUZZY,
            ));
        }
        if is_bounded_subsequence_token(raw_lower) {
            let pattern = subsequence_regex_pattern(raw_lower);
            let regex =
                RegexQuery::from_pattern(&pattern, fields.source_path_raw).map_err(|error| {
                    validation(
                        "invalid_fuzzy_query",
                        &format!("invalid fuzzy path subsequence pattern: {error}"),
                    )
                })?;
            branches.push(boosted(regex, BOOST_PATH_SUBSEQ));
        }
    }
    Ok(Box::new(BooleanQuery::union(branches)))
}

fn split_chunk_spans(chunk: &str) -> Vec<ChunkSpan> {
    let mut spans = Vec::new();
    let mut current = String::new();
    let mut current_is_cjk: Option<bool> = None;

    for ch in chunk.chars() {
        let ch_is_cjk = is_cjk(ch);
        if let Some(flag) = current_is_cjk
            && flag != ch_is_cjk
        {
            let text = std::mem::take(&mut current);
            spans.push(if flag {
                ChunkSpan::CjkRun(text)
            } else {
                ChunkSpan::NonCjkSlice(text)
            });
        }
        current_is_cjk = Some(ch_is_cjk);
        current.push(ch);
    }
    if let Some(flag) = current_is_cjk
        && !current.is_empty()
    {
        spans.push(if flag {
            ChunkSpan::CjkRun(current)
        } else {
            ChunkSpan::NonCjkSlice(current)
        });
    }
    spans
}

fn build_cjk_run_query(
    fields: &SearchFields,
    charabia_tokens: &[String],
    bigrams: &[String],
    unigrams: &[String],
) -> Option<Box<dyn Query>> {
    let mut branches: Vec<Box<dyn Query>> = Vec::new();
    if let Some(primary) = build_charabia_cjk_branch(fields, charabia_tokens) {
        branches.push(primary);
    }
    if let Some(fallback) = build_bigram_fallback_branch(fields, bigrams, unigrams) {
        branches.push(fallback);
    }

    if branches.is_empty() {
        None
    } else {
        Some(Box::new(BooleanQuery::union(branches)))
    }
}

fn build_charabia_cjk_branch(fields: &SearchFields, tokens: &[String]) -> Option<Box<dyn Query>> {
    match tokens {
        [] => None,
        [single] => {
            let mut choices: Vec<Box<dyn Query>> = Vec::new();
            for (field, exact_b, prefix_b) in [
                (fields.body_zh, BOOST_EXACT, BOOST_PREFIX),
                (fields.tag_text, BOOST_EXACT, BOOST_PREFIX),
                (fields.source_path_text, BOOST_PATH_EXACT, BOOST_PATH_PREFIX),
            ] {
                let term = Term::from_field_text(field, single);
                choices.push(boosted(
                    TermQuery::new(term, IndexRecordOption::WithFreqs),
                    exact_b,
                ));
                choices.push(boosted(prefix_range_query(field, single), prefix_b));
            }
            Some(Box::new(BooleanQuery::union(choices)))
        }
        many => {
            let mut branches: Vec<Box<dyn Query>> = Vec::new();
            for (field, boost) in [
                (fields.body_zh, BOOST_PHRASE),
                (fields.tag_text, BOOST_PHRASE),
                (fields.source_path_text, BOOST_PATH_EXACT),
            ] {
                let terms: Vec<Term> = many
                    .iter()
                    .map(|token| Term::from_field_text(field, token))
                    .collect();
                branches.push(boosted(PhraseQuery::new(terms), boost));
            }
            let conjunction: Vec<(Occur, Box<dyn Query>)> = many
                .iter()
                .map(|token| {
                    let body_term = boosted(
                        TermQuery::new(
                            Term::from_field_text(fields.body_zh, token),
                            IndexRecordOption::WithFreqs,
                        ),
                        BOOST_EXACT,
                    );
                    let tag_term = boosted(
                        TermQuery::new(
                            Term::from_field_text(fields.tag_text, token),
                            IndexRecordOption::WithFreqs,
                        ),
                        BOOST_EXACT,
                    );
                    let path_term = boosted(
                        TermQuery::new(
                            Term::from_field_text(fields.source_path_text, token),
                            IndexRecordOption::WithFreqs,
                        ),
                        BOOST_PATH_EXACT,
                    );
                    let union: Box<dyn Query> =
                        Box::new(BooleanQuery::union(vec![body_term, tag_term, path_term]));
                    (Occur::Must, union)
                })
                .collect();
            branches.push(Box::new(BooleanQuery::new(conjunction)));
            Some(Box::new(BooleanQuery::union(branches)))
        }
    }
}

fn build_bigram_fallback_branch(
    fields: &SearchFields,
    bigrams: &[String],
    unigrams: &[String],
) -> Option<Box<dyn Query>> {
    match bigrams {
        [] => unigrams.first().map(|uni| {
            boosted(
                TermQuery::new(
                    Term::from_field_text(fields.body_cjk_bigram, uni),
                    IndexRecordOption::WithFreqs,
                ),
                BOOST_CJK_BIGRAM,
            )
        }),
        [single] => Some(boosted(
            TermQuery::new(
                Term::from_field_text(fields.body_cjk_bigram, single),
                IndexRecordOption::WithFreqs,
            ),
            BOOST_CJK_BIGRAM,
        )),
        many => {
            let terms: Vec<Term> = many
                .iter()
                .map(|bg| Term::from_field_text(fields.body_cjk_bigram, bg))
                .collect();
            Some(boosted(PhraseQuery::new(terms), BOOST_CJK_BIGRAM))
        }
    }
}

fn build_word_or_emoji_query(
    fields: &SearchFields,
    token: &str,
    mode: StoreSearchMode,
) -> Result<Option<Box<dyn Query>>, lomo_core::LomoError> {
    if token.is_empty() {
        return Ok(None);
    }

    if token.chars().all(is_emoji_char) {
        let branches = [
            (fields.body_zh, BOOST_EXACT),
            (fields.tag_text, BOOST_EXACT),
            (fields.source_path_text, BOOST_PATH_EXACT),
        ]
        .into_iter()
        .map(|(field, boost)| {
            boosted(
                TermQuery::new(
                    Term::from_field_text(field, token),
                    IndexRecordOption::WithFreqs,
                ),
                boost,
            )
        })
        .collect();
        return Ok(Some(Box::new(BooleanQuery::union(branches))));
    }

    let mut branches: Vec<Box<dyn Query>> = Vec::new();
    for (field, exact_b, prefix_b) in [
        (fields.body_zh, BOOST_EXACT, BOOST_PREFIX),
        (fields.tag_text, BOOST_EXACT, BOOST_PREFIX),
        (fields.source_path_text, BOOST_PATH_EXACT, BOOST_PATH_PREFIX),
        (fields.source_path_raw, BOOST_PATH_EXACT, BOOST_PATH_PREFIX),
    ] {
        let term = Term::from_field_text(field, token);
        branches.push(boosted(
            TermQuery::new(term, IndexRecordOption::WithFreqs),
            exact_b,
        ));
        branches.push(boosted(prefix_range_query(field, token), prefix_b));
    }

    if mode == StoreSearchMode::Fuzzy {
        append_fuzzy_branches(fields, token, &mut branches)?;
    }

    Ok(Some(Box::new(BooleanQuery::union(branches))))
}

fn append_fuzzy_branches(
    fields: &SearchFields,
    token: &str,
    branches: &mut Vec<Box<dyn Query>>,
) -> Result<(), lomo_core::LomoError> {
    let char_len = token.chars().count();
    if char_len >= 3 {
        let distance = if char_len >= 6 { 2 } else { 1 };
        for (field, fuzzy_b, prefix_b) in [
            (fields.body_zh, BOOST_FUZZY, BOOST_FUZZY_PREFIX),
            (fields.tag_text, BOOST_FUZZY, BOOST_FUZZY_PREFIX),
            (fields.source_path_text, BOOST_PATH_FUZZY, BOOST_PATH_SUBSEQ),
            (fields.source_path_raw, BOOST_PATH_FUZZY, BOOST_PATH_SUBSEQ),
        ] {
            let term = Term::from_field_text(field, token);
            branches.push(boosted(
                FuzzyTermQuery::new(term.clone(), distance, true),
                fuzzy_b,
            ));
            if char_len >= 4 {
                branches.push(boosted(FuzzyTermQuery::new_prefix(term, 1, true), prefix_b));
            }
        }
    }

    if is_bounded_subsequence_token(token) {
        let pattern = subsequence_regex_pattern(token);
        for (field, boost) in [
            (fields.body_zh, BOOST_SUBSEQUENCE),
            (fields.tag_text, BOOST_SUBSEQUENCE),
            (fields.source_path_text, BOOST_PATH_SUBSEQ),
            (fields.source_path_raw, BOOST_PATH_SUBSEQ),
        ] {
            let regex = RegexQuery::from_pattern(&pattern, field).map_err(|error| {
                validation(
                    "invalid_fuzzy_query",
                    &format!("invalid fuzzy subsequence pattern: {error}"),
                )
            })?;
            branches.push(boosted(regex, boost));
        }
    }

    if is_pinyin_query_token(token) {
        append_pinyin_branches(fields, token, branches);
    }

    Ok(())
}

fn append_pinyin_branches(fields: &SearchFields, token: &str, branches: &mut Vec<Box<dyn Query>>) {
    let full_term = Term::from_field_text(fields.pinyin_full, token);
    branches.push(boosted(
        TermQuery::new(full_term.clone(), IndexRecordOption::WithFreqs),
        BOOST_PINYIN_EXACT,
    ));
    branches.push(boosted(
        prefix_range_query(fields.pinyin_full, token),
        BOOST_PINYIN_PREFIX,
    ));
    if token.len() >= 4 {
        let dist = if token.len() >= 6 { 2 } else { 1 };
        branches.push(boosted(
            FuzzyTermQuery::new(full_term, dist, true),
            BOOST_PINYIN_FUZZY,
        ));
    }

    let initial_term = Term::from_field_text(fields.pinyin_initials, token);
    branches.push(boosted(
        TermQuery::new(initial_term, IndexRecordOption::WithFreqs),
        BOOST_PINYIN_EXACT,
    ));
    branches.push(boosted(
        prefix_range_query(fields.pinyin_initials, token),
        BOOST_PINYIN_INITIAL_PREFIX,
    ));
}

/// Unbounded single-term FST prefix match without `PhrasePrefixQuery`'s `max_expansions` cutoff.
fn prefix_range_query(field: Field, prefix: &str) -> InvertedIndexRangeQuery {
    let lower = Bound::Included(Term::from_field_text(field, prefix));
    let upper = next_utf8_prefix(prefix).map_or(Bound::Unbounded, |end| {
        Bound::Excluded(Term::from_field_text(field, &end))
    });
    InvertedIndexRangeQuery::new(lower, upper)
}

fn next_utf8_prefix(prefix: &str) -> Option<String> {
    let mut chars: Vec<char> = prefix.chars().collect();
    while let Some(last) = chars.pop() {
        if last == char::MAX {
            continue;
        }
        let next_cp = match (last as u32).checked_add(1) {
            Some(0xD800..=0xDFFF) => 0xE000,
            Some(cp) => cp,
            None => continue,
        };
        if let Some(next_char) = char::from_u32(next_cp) {
            chars.push(next_char);
            return Some(chars.into_iter().collect());
        }
    }
    None
}

/// Returns true when `token` admits bounded within-term subsequence matching in fuzzy mode.
#[must_use]
pub fn is_bounded_subsequence_token(token: &str) -> bool {
    let len = token.chars().count();
    (2..=MAX_SUBSEQUENCE_LEN).contains(&len)
        && token
            .chars()
            .all(|ch| ch.is_ascii_alphanumeric() || matches!(ch, '_' | '-' | '.' | '/'))
}

/// Returns true when `token` is eligible for fuzzy full/initial pinyin expansion.
#[must_use]
pub fn is_pinyin_query_token(token: &str) -> bool {
    token.chars().all(|ch| ch.is_ascii_lowercase())
        && (2..=MAX_PINYIN_QUERY_LEN).contains(&token.len())
}

fn subsequence_regex_pattern(token: &str) -> String {
    let mut pattern = String::with_capacity(token.len() * 4 + 2);
    pattern.push_str(".*");
    for ch in token.chars() {
        push_escaped_regex_char(ch, &mut pattern);
        pattern.push_str(".*");
    }
    pattern
}

fn push_escaped_regex_char(ch: char, out: &mut String) {
    if matches!(
        ch,
        '\\' | '.' | '+' | '*' | '?' | '(' | ')' | '|' | '[' | ']' | '{' | '}' | '^' | '$'
    ) {
        out.push('\\');
    }
    out.push(ch);
}

fn boosted<Q: Query + 'static>(query: Q, boost: f32) -> Box<dyn Query> {
    Box::new(BoostQuery::new(Box::new(query), boost))
}
