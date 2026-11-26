//! Tantivy schema definition, tokenizer registration, and memo/watermark document builders.

use tantivy::Index;
use tantivy::schema::{
    FAST, Field, INDEXED, IndexRecordOption, NumericOptions, STORED, STRING, Schema,
    TextFieldIndexing, TextOptions,
};

use super::tokenizer::{
    CharabiaTantivyTokenizer, CjkBigramTantivyTokenizer, WhitespaceLowerTokenizer,
    extract_pinyin_full_tokens, extract_pinyin_initial_tokens,
};

/// Schema format version stamped inside the Tantivy watermark document.
pub const SEARCH_SCHEMA_VERSION: u32 = 1;

pub const CHARABIA_TOKENIZER_NAME: &str = "lomo_charabia_zh";
pub const CJK_BIGRAM_TOKENIZER_NAME: &str = "lomo_cjk_bigram";
pub const WHITESPACE_LOWER_TOKENIZER_NAME: &str = "lomo_ws_lower";

pub const DOC_KIND_MEMO: u64 = 0;
pub const DOC_KIND_WATERMARK: u64 = 1;
pub const WATERMARK_DOC_ID: &str = "__lomo_watermark__";

pub const FIELD_MEMO_ID: &str = "memo_id";
pub const FIELD_IS_PINNED: &str = "is_pinned";
pub const FIELD_CREATED_AT_MS: &str = "created_at_ms";
pub const FIELD_UPDATED_AT_MS: &str = "updated_at_ms";

/// Handles for all fields in the Tantivy search schema.
#[derive(Debug, Clone, Copy)]
pub struct SearchFields {
    pub doc_kind: Field,
    pub memo_id: Field,
    pub body_zh: Field,
    pub body_cjk_bigram: Field,
    pub pinyin_full: Field,
    pub pinyin_initials: Field,
    pub tag_exact: Field,
    pub tag_subtree: Field,
    pub tag_text: Field,
    pub source_path_text: Field,
    pub source_path_raw: Field,
    pub has_todo: Field,
    pub has_attachment: Field,
    pub has_url: Field,
    pub is_pinned: Field,
    pub is_trashed: Field,
    pub is_pending: Field,
    pub created_at_ms: Field,
    pub updated_at_ms: Field,
    pub wm_search_generation: Field,
    pub wm_schema_version: Field,
    pub wm_tokenizer_version: Field,
    pub wm_synced_seq: Field,
    pub wm_high_water_revision: Field,
}

/// Alignment watermark committed atomically inside the Tantivy index.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Watermark {
    pub search_generation: String,
    pub schema_version: u32,
    pub tokenizer_version: u32,
    pub synced_seq: u64,
    pub high_water_revision: u64,
}

/// Memo snapshot fields streamed from SQLite into Tantivy during rebuild or incremental sync.
#[derive(Debug, Clone)]
#[expect(
    clippy::struct_excessive_bools,
    reason = "mirrors projected SQLite boolean filter columns"
)]
pub struct IndexableMemoRow {
    pub memo_id: String,
    pub source_path: String,
    pub body: String,
    pub tags: Vec<String>,
    pub has_todo: bool,
    pub has_attachment: bool,
    pub has_url: bool,
    pub is_pinned: bool,
    pub is_trashed: bool,
    pub is_pending: bool,
    pub created_at_ms: i64,
    pub updated_at_ms: i64,
}

/// Builds the canonical Tantivy schema and field handles.
#[must_use]
pub fn build_search_schema() -> (Schema, SearchFields) {
    let mut builder = Schema::builder();
    let charabia_opts = TextOptions::default().set_indexing_options(
        TextFieldIndexing::default()
            .set_tokenizer(CHARABIA_TOKENIZER_NAME)
            .set_index_option(IndexRecordOption::WithFreqsAndPositions),
    );
    let bigram_opts = TextOptions::default().set_indexing_options(
        TextFieldIndexing::default()
            .set_tokenizer(CJK_BIGRAM_TOKENIZER_NAME)
            .set_index_option(IndexRecordOption::WithFreqsAndPositions),
    );
    let ws_lower_opts = TextOptions::default().set_indexing_options(
        TextFieldIndexing::default()
            .set_tokenizer(WHITESPACE_LOWER_TOKENIZER_NAME)
            .set_index_option(IndexRecordOption::WithFreqs),
    );
    let i64_fast = NumericOptions::default().set_indexed().set_fast();

    let fields = SearchFields {
        doc_kind: builder.add_u64_field("doc_kind", INDEXED | STORED | FAST),
        memo_id: builder.add_text_field(FIELD_MEMO_ID, STRING | STORED | FAST),
        body_zh: builder.add_text_field("body_zh", charabia_opts.clone()),
        body_cjk_bigram: builder.add_text_field("body_cjk_bigram", bigram_opts),
        pinyin_full: builder.add_text_field("pinyin_full", ws_lower_opts.clone()),
        pinyin_initials: builder.add_text_field("pinyin_initials", ws_lower_opts.clone()),
        tag_exact: builder.add_text_field("tag_exact", STRING),
        tag_subtree: builder.add_text_field("tag_subtree", STRING),
        tag_text: builder.add_text_field("tag_text", charabia_opts.clone()),
        source_path_text: builder.add_text_field("source_path_text", charabia_opts),
        source_path_raw: builder.add_text_field("source_path_raw", ws_lower_opts),
        has_todo: builder.add_u64_field("has_todo", INDEXED),
        has_attachment: builder.add_u64_field("has_attachment", INDEXED),
        has_url: builder.add_u64_field("has_url", INDEXED),
        is_pinned: builder.add_u64_field(FIELD_IS_PINNED, INDEXED | FAST),
        is_trashed: builder.add_u64_field("is_trashed", INDEXED | FAST),
        is_pending: builder.add_u64_field("is_pending", INDEXED | FAST),
        created_at_ms: builder.add_i64_field(FIELD_CREATED_AT_MS, i64_fast.clone()),
        updated_at_ms: builder.add_i64_field(FIELD_UPDATED_AT_MS, i64_fast),
        wm_search_generation: builder.add_text_field("wm_search_generation", STRING | STORED),
        wm_schema_version: builder.add_u64_field("wm_schema_version", STORED),
        wm_tokenizer_version: builder.add_u64_field("wm_tokenizer_version", STORED),
        wm_synced_seq: builder.add_u64_field("wm_synced_seq", STORED),
        wm_high_water_revision: builder.add_u64_field("wm_high_water_revision", STORED),
    };
    (builder.build(), fields)
}

/// Registers Lomo's custom Charabia, CJK bigram, and lowercase whitespace tokenizers on `index`.
pub fn register_tokenizers(index: &Index) {
    let tokenizers = index.tokenizers();
    tokenizers.register(CHARABIA_TOKENIZER_NAME, CharabiaTantivyTokenizer);
    tokenizers.register(CJK_BIGRAM_TOKENIZER_NAME, CjkBigramTantivyTokenizer);
    tokenizers.register(WHITESPACE_LOWER_TOKENIZER_NAME, WhitespaceLowerTokenizer);
}

/// Builds a Tantivy memo document without storing redundant full body text.
#[must_use]
pub fn build_memo_document(
    fields: &SearchFields,
    row: &IndexableMemoRow,
) -> tantivy::TantivyDocument {
    let mut doc = tantivy::TantivyDocument::new();
    doc.add_u64(fields.doc_kind, DOC_KIND_MEMO);
    doc.add_text(fields.memo_id, &row.memo_id);
    doc.add_text(fields.body_zh, &row.body);
    doc.add_text(fields.body_cjk_bigram, &row.body);

    let full_pinyin = extract_pinyin_full_tokens(&row.body).join(" ");
    if !full_pinyin.is_empty() {
        doc.add_text(fields.pinyin_full, &full_pinyin);
    }
    let initial_pinyin = extract_pinyin_initial_tokens(&row.body).join(" ");
    if !initial_pinyin.is_empty() {
        doc.add_text(fields.pinyin_initials, &initial_pinyin);
    }

    if !row.source_path.is_empty() {
        doc.add_text(fields.source_path_text, &row.source_path);
        let raw_tokens = source_path_raw_tokens(&row.source_path);
        if !raw_tokens.is_empty() {
            doc.add_text(fields.source_path_raw, &raw_tokens);
        }
    }

    for tag in &row.tags {
        doc.add_text(fields.tag_exact, tag);
        for ancestor in tag_ancestors(tag) {
            doc.add_text(fields.tag_subtree, &ancestor);
        }
    }
    if !row.tags.is_empty() {
        let joined_tags = row.tags.join(" ");
        doc.add_text(fields.tag_text, &joined_tags);
    }

    doc.add_u64(fields.has_todo, u64::from(row.has_todo));
    doc.add_u64(fields.has_attachment, u64::from(row.has_attachment));
    doc.add_u64(fields.has_url, u64::from(row.has_url));
    doc.add_u64(fields.is_pinned, u64::from(row.is_pinned));
    doc.add_u64(fields.is_trashed, u64::from(row.is_trashed));
    doc.add_u64(fields.is_pending, u64::from(row.is_pending));
    doc.add_i64(fields.created_at_ms, row.created_at_ms);
    doc.add_i64(fields.updated_at_ms, row.updated_at_ms);
    doc
}

/// Builds the Tantivy watermark document stored alongside memo documents.
#[must_use]
pub fn build_watermark_document(
    fields: &SearchFields,
    watermark: &Watermark,
) -> tantivy::TantivyDocument {
    let mut doc = tantivy::TantivyDocument::new();
    doc.add_u64(fields.doc_kind, DOC_KIND_WATERMARK);
    doc.add_text(fields.memo_id, WATERMARK_DOC_ID);
    doc.add_text(fields.wm_search_generation, &watermark.search_generation);
    doc.add_u64(
        fields.wm_schema_version,
        u64::from(watermark.schema_version),
    );
    doc.add_u64(
        fields.wm_tokenizer_version,
        u64::from(watermark.tokenizer_version),
    );
    doc.add_u64(fields.wm_synced_seq, watermark.synced_seq);
    doc.add_u64(fields.wm_high_water_revision, watermark.high_water_revision);
    doc
}

/// Extracts lowercased raw-path spans `(token, utf8_byte_range)` matching `source_path_raw`
/// indexing: the full path, every non-empty `/` or `\` component, and the final file stem.
#[must_use]
pub fn source_path_raw_spans(source_path: &str) -> Vec<(String, std::ops::Range<usize>)> {
    if source_path.is_empty() {
        return Vec::new();
    }
    let mut spans = vec![(source_path.to_ascii_lowercase(), 0..source_path.len())];
    let mut last_component: Option<(usize, &str)> = None;
    let mut start = 0usize;
    for (idx, ch) in source_path.char_indices() {
        if ch == '/' || ch == '\\' {
            if start < idx
                && let Some(component) = source_path.get(start..idx)
            {
                spans.push((component.to_ascii_lowercase(), start..idx));
                last_component = Some((start, component));
            }
            start = idx + ch.len_utf8();
        }
    }
    if start < source_path.len()
        && let Some(component) = source_path.get(start..)
    {
        spans.push((component.to_ascii_lowercase(), start..source_path.len()));
        last_component = Some((start, component));
    }
    if let Some((comp_start, name)) = last_component
        && let Some(dot) = name.rfind('.')
        && dot > 0
        && let Some(stem) = name.get(..dot)
    {
        spans.push((stem.to_ascii_lowercase(), comp_start..comp_start + dot));
    }
    spans
}

fn source_path_raw_tokens(source_path: &str) -> String {
    source_path_raw_spans(source_path)
        .into_iter()
        .map(|(token, _range)| token)
        .collect::<Vec<_>>()
        .join(" ")
}

fn tag_ancestors(tag: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut acc = String::with_capacity(tag.len());
    for part in tag.split('/') {
        if part.is_empty() {
            continue;
        }
        if !acc.is_empty() {
            acc.push('/');
        }
        acc.push_str(part);
        out.push(acc.clone());
    }
    if out.is_empty() && !tag.is_empty() {
        out.push(tag.to_owned());
    }
    out
}
