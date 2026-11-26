//! Tantivy + Charabia Chinese segmentation, CJK bigram fallback, and fuzzy pinyin search.

pub mod collector;
pub mod coordinator;
pub mod query_builder;
pub mod schema;
pub mod tokenizer;

pub use coordinator::{SearchCoordinator, SearchIndexStats, SearchPageRequest};
pub use query_builder::{
    MAX_PINYIN_QUERY_LEN, MAX_SEARCH_QUERY_BYTES, MAX_SUBSEQUENCE_LEN, SearchChunkPlan,
    SearchQueryPlan, SearchSpanPlan, StoreSearchMode, is_bounded_subsequence_token,
    is_pinyin_query_token,
};
pub use schema::source_path_raw_spans;
pub use tokenizer::{
    AnalyzedToken, analyze_charabia_tokens, analyze_cjk_bigrams, extract_pinyin_full_tokens,
    extract_pinyin_initial_tokens, is_cjk, is_emoji_char, normalize_cjk_char, tokenizer_version,
};
