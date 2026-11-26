//! Shared Charabia + jieba-rs Chinese tokenizer, CJK bigrams, emoji, and heteronym pinyin.

use std::borrow::Cow;
use std::collections::{HashMap, HashSet};
use std::sync::LazyLock;

use charabia::Language;
use hanconv::TextDictionary;
use pinyin::ToPinyinMulti;
use tantivy::tokenizer::{Token as TantivyToken, TokenStream, Tokenizer as TantivyTokenizer};

use crate::schema::TOKENIZER_VERSION;

const ALLOWED_LANGUAGES: &[Language] = &[Language::Cmn];
const MAX_PINYIN_TOKENS_PER_FIELD: usize = 4_096;
const MAX_PINYIN_COMBOS_PER_UNIT: usize = 16;
const MAX_CHAR_READINGS: usize = 4;
const MAX_PINYIN_WORD_CHARS: usize = 8;
const MAX_PINYIN_COMBO_RUN_CHARS: usize = 12;
const MAX_PINYIN_RUN_CHARS: usize = 32;

/// Force Chinese segmentation on `Script::Cj` regardless of short-query heuristics.
static CHARABIA_ZH_TOKENIZER: LazyLock<charabia::Tokenizer<'static>> = LazyLock::new(|| {
    let mut builder = charabia::TokenizerBuilder::<Vec<u8>>::default();
    let _configured = builder.allow_list(ALLOWED_LANGUAGES);
    builder.into_tokenizer()
});

/// Idempotent `OpenCC` Traditional/HK/TW -> Simplified single-character lookup table.
static T2S_CHAR_MAP: LazyLock<HashMap<char, char>> = LazyLock::new(build_t2s_char_map);

/// Token with normalized lemma and UTF-8 byte offsets into the analyzed source slice.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AnalyzedToken {
    pub text: String,
    pub byte_start: usize,
    pub byte_end: usize,
}

#[derive(Debug, Clone)]
pub(crate) struct PositionedUnit {
    pub text: String,
    pub byte_start: usize,
    pub byte_end: usize,
    pub position: usize,
}

#[derive(Debug, Clone, Copy)]
struct CjkCharUnit {
    norm: char,
    byte_start: usize,
    byte_end: usize,
}

/// Shipped tokenizer version for Tantivy index watermarks and `PageCursor` coupling.
#[must_use]
pub const fn tokenizer_version() -> u32 {
    TOKENIZER_VERSION
}

/// CJK ideographs + Hiragana + Katakana + Hangul syllables (Unicode block ranges, pure Rust).
#[must_use]
pub const fn is_cjk(ch: char) -> bool {
    let cp = ch as u32;
    matches!(
        cp,
        0x3040..=0x309F // Hiragana
            | 0x30A0..=0x30FF // Katakana
            | 0x3400..=0x4DBF // CJK Ext A
            | 0x4E00..=0x9FFF // CJK Unified
            | 0xF900..=0xFAFF // CJK Compatibility
            | 0xAC00..=0xD7AF // Hangul syllables
            | 0x20000..=0x2A6DF // CJK Ext B
            | 0x2A700..=0x2B73F
            | 0x2B740..=0x2B81F
            | 0x2B820..=0x2CEAF
            | 0x2F800..=0x2FA1F
    )
}

/// Returns true for emoji and symbol codepoints preserved outside standard word classification.
#[must_use]
pub const fn is_emoji_char(ch: char) -> bool {
    let cp = ch as u32;
    matches!(
        cp,
        0x1F000..=0x1FFFF // SMP emoji/symbols
            | 0x2600..=0x27BF // dingbats / misc symbols
            | 0x2300..=0x23FF // misc technical
    ) || matches!(ch, '\u{FE0F}' | '\u{200D}')
}

/// Analyzes `text` using Charabia Chinese segmentation + `OpenCC` Traditional/Simplified
/// normalization, lowercasing Latin words and preserving emoji tokens with exact source UTF-8
/// byte spans.
#[must_use]
pub fn analyze_charabia_tokens(text: &str) -> Vec<AnalyzedToken> {
    let (normalized, boundaries) = normalize_text_for_charabia(text);
    let mut out = Vec::new();
    for token in CHARABIA_ZH_TOKENIZER.tokenize(normalized.as_ref()) {
        let Some(orig_start) = map_byte_offset(token.byte_start, boundaries.as_deref()) else {
            continue;
        };
        let Some(orig_end) = map_byte_offset(token.byte_end, boundaries.as_deref()) else {
            continue;
        };
        if !text.is_char_boundary(orig_start)
            || !text.is_char_boundary(orig_end)
            || orig_start >= orig_end
        {
            continue;
        }
        if token.is_word() || token.is_stopword() {
            let lemma = token.lemma().trim();
            if has_searchable_char(lemma) {
                out.push(AnalyzedToken {
                    text: lemma.to_owned(),
                    byte_start: orig_start,
                    byte_end: orig_end,
                });
            }
            continue;
        }
        let Some(raw) = text.get(orig_start..orig_end) else {
            continue;
        };
        push_emoji_tokens(raw, orig_start, &mut out);
    }
    out
}

/// Analyzes contiguous CJK runs into normalized single-char unigrams and adjacent 2-char bigrams.
#[must_use]
pub fn analyze_cjk_bigrams(text: &str) -> Vec<AnalyzedToken> {
    let mut out = Vec::new();
    for run in collect_cjk_runs(text) {
        for (idx, first) in run.iter().enumerate() {
            out.push(AnalyzedToken {
                text: first.norm.to_string(),
                byte_start: first.byte_start,
                byte_end: first.byte_end,
            });
            if let Some(second) = run.get(idx + 1) {
                let mut bigram = String::with_capacity(8);
                bigram.push(first.norm);
                bigram.push(second.norm);
                out.push(AnalyzedToken {
                    text: bigram,
                    byte_start: first.byte_start,
                    byte_end: second.byte_end,
                });
            }
        }
    }
    out
}

pub(crate) fn analyze_cjk_bigram_units(text: &str) -> Vec<PositionedUnit> {
    let mut out = Vec::new();
    let mut position = 0usize;
    for run in collect_cjk_runs(text) {
        for (idx, first) in run.iter().enumerate() {
            out.push(PositionedUnit {
                text: first.norm.to_string(),
                byte_start: first.byte_start,
                byte_end: first.byte_end,
                position,
            });
            if let Some(second) = run.get(idx + 1) {
                let mut bigram = String::with_capacity(8);
                bigram.push(first.norm);
                bigram.push(second.norm);
                out.push(PositionedUnit {
                    text: bigram,
                    byte_start: first.byte_start,
                    byte_end: second.byte_end,
                    position,
                });
            }
            position += 1;
        }
        position += 1;
    }
    out
}

/// Extracts bounded lowercase full-pinyin tokens (Charabia words + contiguous CJK runs + syllables)
/// supporting Chinese heteronyms (`pinyin` crate with `heteronym` enabled).
#[must_use]
pub fn extract_pinyin_full_tokens(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut seen = HashSet::new();

    for word in analyze_charabia_tokens(text) {
        if out.len() >= MAX_PINYIN_TOKENS_PER_FIELD {
            return out;
        }
        let chars: Vec<char> = word.text.chars().collect();
        if chars.len() >= 2
            && chars.len() <= MAX_PINYIN_WORD_CHARS
            && chars.iter().all(|c| is_cjk(*c))
        {
            push_full_combos_for_slice(&chars, MAX_PINYIN_COMBOS_PER_UNIT, &mut seen, &mut out);
        }
    }

    for run in collect_cjk_runs(text) {
        if out.len() >= MAX_PINYIN_TOKENS_PER_FIELD {
            break;
        }
        let chars: Vec<char> = run.iter().map(|unit| unit.norm).collect();
        push_primary_full_run(&chars, &mut seen, &mut out);
        push_run_sliding_full(&chars, &mut seen, &mut out);
        push_single_syllables(&chars, &mut seen, &mut out);
    }
    out
}

/// Extracts bounded lowercase pinyin initial sequences (Charabia words + 2..=8 CJK sliding runs)
/// supporting Chinese heteronyms.
#[must_use]
pub fn extract_pinyin_initial_tokens(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut seen = HashSet::new();

    for word in analyze_charabia_tokens(text) {
        if out.len() >= MAX_PINYIN_TOKENS_PER_FIELD {
            return out;
        }
        let chars: Vec<char> = word.text.chars().collect();
        if chars.len() >= 2
            && chars.len() <= MAX_PINYIN_WORD_CHARS
            && chars.iter().all(|c| is_cjk(*c))
        {
            push_initial_combos_for_slice(&chars, MAX_PINYIN_COMBOS_PER_UNIT, &mut seen, &mut out);
        }
    }

    for run in collect_cjk_runs(text) {
        if out.len() >= MAX_PINYIN_TOKENS_PER_FIELD {
            break;
        }
        let chars: Vec<char> = run.iter().map(|unit| unit.norm).collect();
        push_primary_initial_run(&chars, &mut seen, &mut out);
        push_run_sliding_initials(&chars, &mut seen, &mut out);
    }
    out
}

/// Tantivy tokenizer adapter backed by Charabia Chinese segmentation + emoji preservation.
#[derive(Debug, Default, Clone)]
pub struct CharabiaTantivyTokenizer;

impl TantivyTokenizer for CharabiaTantivyTokenizer {
    type TokenStream<'a> = VecTokenStream;

    fn token_stream<'a>(&'a mut self, text: &'a str) -> Self::TokenStream<'a> {
        let tokens = analyze_charabia_tokens(text)
            .into_iter()
            .enumerate()
            .map(|(position, token)| TantivyToken {
                offset_from: token.byte_start,
                offset_to: token.byte_end,
                position,
                text: token.text,
                position_length: 1,
            })
            .collect();
        VecTokenStream::new(tokens)
    }
}

/// Tantivy tokenizer adapter emitting normalized CJK unigrams and adjacent 2-char bigrams.
#[derive(Debug, Default, Clone)]
pub struct CjkBigramTantivyTokenizer;

impl TantivyTokenizer for CjkBigramTantivyTokenizer {
    type TokenStream<'a> = VecTokenStream;

    fn token_stream<'a>(&'a mut self, text: &'a str) -> Self::TokenStream<'a> {
        let tokens = analyze_cjk_bigram_units(text)
            .into_iter()
            .map(|unit| TantivyToken {
                offset_from: unit.byte_start,
                offset_to: unit.byte_end,
                position: unit.position,
                text: unit.text,
                position_length: 1,
            })
            .collect();
        VecTokenStream::new(tokens)
    }
}

/// Tantivy whitespace + ASCII lowercase tokenizer for pre-computed pinyin fields.
#[derive(Debug, Default, Clone)]
pub struct WhitespaceLowerTokenizer;

impl TantivyTokenizer for WhitespaceLowerTokenizer {
    type TokenStream<'a> = VecTokenStream;

    fn token_stream<'a>(&'a mut self, text: &'a str) -> Self::TokenStream<'a> {
        let mut tokens = Vec::new();
        let mut current_start: Option<usize> = None;
        for (idx, ch) in text.char_indices() {
            if ch.is_whitespace() {
                if let Some(start) = current_start.take()
                    && let Some(slice) = text.get(start..idx)
                {
                    let position = tokens.len();
                    tokens.push(TantivyToken {
                        offset_from: start,
                        offset_to: idx,
                        position,
                        text: slice.to_ascii_lowercase(),
                        position_length: 1,
                    });
                }
            } else if current_start.is_none() {
                current_start = Some(idx);
            }
        }
        if let Some(start) = current_start
            && let Some(slice) = text.get(start..)
        {
            let position = tokens.len();
            tokens.push(TantivyToken {
                offset_from: start,
                offset_to: text.len(),
                position,
                text: slice.to_ascii_lowercase(),
                position_length: 1,
            });
        }
        VecTokenStream::new(tokens)
    }
}

/// Materialized Tantivy [`TokenStream`] over pre-analyzed tokens.
#[derive(Debug, Clone)]
pub struct VecTokenStream {
    tokens: Vec<TantivyToken>,
    next_index: usize,
    current: TantivyToken,
}

impl VecTokenStream {
    fn new(tokens: Vec<TantivyToken>) -> Self {
        Self {
            tokens,
            next_index: 0,
            current: TantivyToken::default(),
        }
    }
}

impl TokenStream for VecTokenStream {
    fn advance(&mut self) -> bool {
        let Some(token) = self.tokens.get(self.next_index) else {
            return false;
        };
        self.current = token.clone();
        self.next_index += 1;
        true
    }

    fn token(&self) -> &TantivyToken {
        &self.current
    }

    fn token_mut(&mut self) -> &mut TantivyToken {
        &mut self.current
    }
}

fn has_searchable_char(text: &str) -> bool {
    !text.is_empty()
        && text
            .chars()
            .any(|ch| ch.is_alphanumeric() || is_cjk(ch) || is_emoji_char(ch))
}

fn push_emoji_tokens(raw: &str, base_offset: usize, out: &mut Vec<AnalyzedToken>) {
    for (rel_offset, ch) in raw.char_indices() {
        if is_emoji_char(ch) && !matches!(ch, '\u{FE0F}' | '\u{200D}') {
            let start = base_offset + rel_offset;
            out.push(AnalyzedToken {
                text: ch.to_string(),
                byte_start: start,
                byte_end: start + ch.len_utf8(),
            });
        }
    }
}

fn single_char(text: &str) -> Option<char> {
    let mut chars = text.chars();
    let ch = chars.next()?;
    chars.next().is_none().then_some(ch)
}

fn insert_tw_hk_variants(map: &mut HashMap<char, char>, simplified_targets: &HashSet<char>) {
    for (variant_raw, canon_raw) in TextDictionary::TWVariants
        .iter_inverse()
        .chain(TextDictionary::HKVariants.iter_inverse())
    {
        let (Some(variant), Some(canon)) = (single_char(variant_raw), single_char(canon_raw))
        else {
            continue;
        };
        if variant == canon || map.contains_key(&variant) || simplified_targets.contains(&variant) {
            continue;
        }
        let resolved = map.get(&canon).copied().unwrap_or(canon);
        if resolved != variant {
            map.insert(variant, resolved);
        }
    }
}

fn collapse_transitive_targets(map: &mut HashMap<char, char>) {
    let keys: Vec<char> = map.keys().copied().collect();
    for key in keys {
        let Some(mut target) = map.get(&key).copied() else {
            continue;
        };
        for _ in 0..8 {
            let Some(next) = map.get(&target).copied() else {
                break;
            };
            if next == target {
                break;
            }
            target = next;
        }
        map.insert(key, target);
    }
}

fn build_t2s_char_map() -> HashMap<char, char> {
    let mut map = HashMap::new();
    let mut simplified_targets = HashSet::new();
    for (trad_raw, simp_raw) in TextDictionary::TSCharacters.iter() {
        let (Some(trad), Some(simp)) = (single_char(trad_raw), single_char(simp_raw)) else {
            continue;
        };
        simplified_targets.insert(simp);
        if trad != simp {
            map.insert(trad, simp);
        }
    }
    insert_tw_hk_variants(&mut map, &simplified_targets);
    collapse_transitive_targets(&mut map);
    map
}

/// Normalizes a single Traditional/HK/TW CJK character to its canonical Simplified form.
#[must_use]
pub fn normalize_cjk_char(ch: char) -> char {
    T2S_CHAR_MAP.get(&ch).copied().unwrap_or(ch)
}

fn normalize_text_for_charabia(text: &str) -> (Cow<'_, str>, Option<Vec<(usize, usize)>>) {
    if !text.chars().any(|ch| T2S_CHAR_MAP.contains_key(&ch)) {
        return (Cow::Borrowed(text), None);
    }
    let mut normalized = String::with_capacity(text.len());
    let mut boundaries = Vec::with_capacity(text.chars().count() + 1);
    boundaries.push((0, 0));
    for (orig_start, ch) in text.char_indices() {
        normalized.push(normalize_cjk_char(ch));
        boundaries.push((normalized.len(), orig_start + ch.len_utf8()));
    }
    (Cow::Owned(normalized), Some(boundaries))
}

fn map_byte_offset(offset: usize, boundaries: Option<&[(usize, usize)]>) -> Option<usize> {
    let Some(pairs) = boundaries else {
        return Some(offset);
    };
    let Ok(idx) = pairs.binary_search_by_key(&offset, |&(norm_b, _)| norm_b) else {
        return None;
    };
    pairs.get(idx).map(|&(_, orig_b)| orig_b)
}

fn collect_cjk_runs(text: &str) -> Vec<Vec<CjkCharUnit>> {
    let mut runs = Vec::new();
    let mut current = Vec::new();
    for (byte_start, ch) in text.char_indices() {
        if is_cjk(ch) {
            current.push(CjkCharUnit {
                norm: normalize_cjk_char(ch),
                byte_start,
                byte_end: byte_start + ch.len_utf8(),
            });
        } else if !current.is_empty() {
            runs.push(std::mem::take(&mut current));
        }
    }
    if !current.is_empty() {
        runs.push(current);
    }
    runs
}

fn char_full_readings(ch: char) -> Vec<&'static str> {
    let Some(multi) = ch.to_pinyin_multi() else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for item in multi.into_iter().take(MAX_CHAR_READINGS) {
        let plain = item.plain();
        if !plain.is_empty() && !out.contains(&plain) {
            out.push(plain);
        }
    }
    out
}

fn char_initial_readings(ch: char) -> Vec<&'static str> {
    let Some(multi) = ch.to_pinyin_multi() else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for item in multi.into_iter().take(MAX_CHAR_READINGS) {
        let letter = item.first_letter();
        if !letter.is_empty() && !out.contains(&letter) {
            out.push(letter);
        }
    }
    out
}

fn expand_cartesian(per_char: &[Vec<&'static str>], max_combos: usize) -> Vec<String> {
    if per_char.is_empty() || per_char.iter().any(Vec::is_empty) {
        return Vec::new();
    }
    let mut acc = vec![String::new()];
    for choices in per_char {
        let mut next = Vec::with_capacity(acc.len().saturating_mul(choices.len()).min(max_combos));
        for prefix in &acc {
            for choice in choices {
                if next.len() >= max_combos {
                    break;
                }
                let mut combined = String::with_capacity(prefix.len() + choice.len());
                combined.push_str(prefix);
                combined.push_str(choice);
                next.push(combined);
            }
            if next.len() >= max_combos {
                break;
            }
        }
        acc = next;
    }
    acc
}

fn push_unique_token(token: String, seen: &mut HashSet<String>, out: &mut Vec<String>) {
    if out.len() >= MAX_PINYIN_TOKENS_PER_FIELD || token.is_empty() {
        return;
    }
    if seen.insert(token.clone()) {
        out.push(token);
    }
}

fn push_full_combos_for_slice(
    chars: &[char],
    max_combos: usize,
    seen: &mut HashSet<String>,
    out: &mut Vec<String>,
) {
    let per_char: Vec<Vec<&'static str>> = chars.iter().map(|ch| char_full_readings(*ch)).collect();
    for combo in expand_cartesian(&per_char, max_combos) {
        push_unique_token(combo, seen, out);
    }
}

fn push_primary_full_run(chars: &[char], seen: &mut HashSet<String>, out: &mut Vec<String>) {
    if chars.len() < 2 || chars.len() > MAX_PINYIN_RUN_CHARS {
        return;
    }
    if chars.len() <= MAX_PINYIN_COMBO_RUN_CHARS {
        push_full_combos_for_slice(chars, MAX_PINYIN_COMBOS_PER_UNIT, seen, out);
        return;
    }
    let mut joined = String::new();
    for ch in chars {
        let Some(first) = char_full_readings(*ch).into_iter().next() else {
            return;
        };
        joined.push_str(first);
    }
    push_unique_token(joined, seen, out);
}

fn push_run_sliding_full(chars: &[char], seen: &mut HashSet<String>, out: &mut Vec<String>) {
    for window_len in 2..=3 {
        for window in chars.windows(window_len) {
            if out.len() >= MAX_PINYIN_TOKENS_PER_FIELD {
                return;
            }
            push_full_combos_for_slice(window, 4, seen, out);
        }
    }
}

fn push_single_syllables(chars: &[char], seen: &mut HashSet<String>, out: &mut Vec<String>) {
    for ch in chars {
        for reading in char_full_readings(*ch) {
            push_unique_token(reading.to_owned(), seen, out);
        }
    }
}

fn push_initial_combos_for_slice(
    chars: &[char],
    max_combos: usize,
    seen: &mut HashSet<String>,
    out: &mut Vec<String>,
) {
    let per_char: Vec<Vec<&'static str>> =
        chars.iter().map(|ch| char_initial_readings(*ch)).collect();
    for combo in expand_cartesian(&per_char, max_combos) {
        push_unique_token(combo, seen, out);
    }
}

fn push_primary_initial_run(chars: &[char], seen: &mut HashSet<String>, out: &mut Vec<String>) {
    if chars.len() < 2 || chars.len() > MAX_PINYIN_RUN_CHARS {
        return;
    }
    if chars.len() <= MAX_PINYIN_COMBO_RUN_CHARS {
        push_initial_combos_for_slice(chars, MAX_PINYIN_COMBOS_PER_UNIT, seen, out);
        return;
    }
    let mut joined = String::new();
    for ch in chars {
        let Some(first) = char_initial_readings(*ch).into_iter().next() else {
            return;
        };
        joined.push_str(first);
    }
    push_unique_token(joined, seen, out);
}

fn push_run_sliding_initials(chars: &[char], seen: &mut HashSet<String>, out: &mut Vec<String>) {
    for window_len in 2..=MAX_PINYIN_WORD_CHARS {
        for window in chars.windows(window_len) {
            if out.len() >= MAX_PINYIN_TOKENS_PER_FIELD {
                return;
            }
            push_initial_combos_for_slice(window, 8, seen, out);
        }
    }
}
