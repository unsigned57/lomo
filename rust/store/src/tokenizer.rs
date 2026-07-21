//! Pure Rust Unicode tokenizer (CJK bigram + query plan). No JVM `UnicodeBlock`.

use crate::error::validation;
use crate::schema::TOKENIZER_VERSION;

/// Shipped tokenizer version for FTS / cursor coupling.
#[must_use]
pub const fn tokenizer_version() -> u32 {
    TOKENIZER_VERSION
}

/// Trait surface so future dictionary tokenizers can bump version + rebuild.
pub trait Tokenizer {
    /// Produces space-separated index tokens for the FTS `search_content` projection.
    fn index_tokens(&self, text: &str) -> String;

    /// Builds a bounded FTS5 MATCH expression / structured plan for a user query.
    ///
    /// # Errors
    ///
    /// Returns validation errors only for hard boundary failures (none for empty input — empty
    /// yields an empty plan).
    fn query_plan(&self, text: &str) -> Result<QueryPlan, lomo_core::LomoError>;
}

/// Default pure-Rust Unicode tokenizer (zero dictionary).
#[derive(Debug, Default, Clone, Copy)]
pub struct UnicodeTokenizer;

/// One structured query term used to assert non-unigram-OR multi-char CJK behavior.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum QueryTerm {
    /// Single CJK character as a prefix term (`字*`).
    CjkUnigram { token: String },
    /// Ordered adjacent bigrams for a multi-character CJK run (never unbounded unigram-OR).
    CjkAdjacentBigrams { bigrams: Vec<String> },
    /// Latin/ASCII word (phrase-prefix).
    Word { token: String },
    /// Emoji codepoint sequence preserved as a token.
    Emoji { token: String },
}

/// Observable query plan for FTS MATCH construction and contract tests.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QueryPlan {
    /// Structured terms in encounter order (deduped).
    pub terms: Vec<QueryTerm>,
    /// FTS5 MATCH expression, or `None` when there are no searchable terms.
    pub match_expr: Option<String>,
}

impl QueryPlan {
    /// Returns true when any multi-char CJK segment was expanded to unbounded unigram-OR.
    ///
    /// Contract helper: production plans must always return false.
    #[must_use]
    pub fn uses_unbounded_cjk_unigram_or(&self) -> bool {
        self.match_expr.as_ref().is_some_and(|expr| {
            // Detect patterns like `你* OR 好* OR 世*` for multi-char expansions.
            let upper = expr.to_ascii_uppercase();
            upper.contains(" OR ")
                && self.terms.iter().any(|term| {
                    matches!(term, QueryTerm::CjkAdjacentBigrams { bigrams } if !bigrams.is_empty())
                })
                && self.terms.iter().any(|term| matches!(term, QueryTerm::CjkUnigram { .. }))
        })
    }
}

const MAX_QUERY_TERMS: usize = 5;

impl Tokenizer for UnicodeTokenizer {
    fn index_tokens(&self, text: &str) -> String {
        let mut out = String::new();
        let chars: Vec<char> = text.chars().collect();
        let mut i = 0usize;
        while i < chars.len() {
            let Some(&ch) = chars.get(i) else {
                break;
            };
            if is_emoji_char(ch) {
                push_token(&mut out, &ch.to_string());
                i += 1;
            } else if is_cjk(ch) {
                let start = i;
                i += 1;
                while i < chars.len() {
                    let Some(&next) = chars.get(i) else {
                        break;
                    };
                    if !is_cjk(next) {
                        break;
                    }
                    i += 1;
                }
                let run = chars.get(start..i).unwrap_or(&[]);
                for unit in run {
                    push_token(&mut out, &unit.to_string());
                }
                if run.len() >= 2 {
                    for window in run.windows(2) {
                        let mut bigram = String::new();
                        if let (Some(a), Some(b)) = (window.first(), window.get(1)) {
                            bigram.push(*a);
                            bigram.push(*b);
                            push_token(&mut out, &bigram);
                        }
                    }
                }
            } else if is_word_char(ch) {
                let start = i;
                i += 1;
                while i < chars.len() {
                    let Some(&next) = chars.get(i) else {
                        break;
                    };
                    if !(is_word_char(next) && !is_cjk(next) && !is_emoji_char(next)) {
                        break;
                    }
                    i += 1;
                }
                let word: String = chars.get(start..i).unwrap_or(&[]).iter().collect();
                push_token(&mut out, &word);
            } else {
                i += 1;
            }
        }
        out
    }

    fn query_plan(&self, text: &str) -> Result<QueryPlan, lomo_core::LomoError> {
        let trimmed = text.trim();
        if trimmed.is_empty() {
            return Ok(QueryPlan {
                terms: Vec::new(),
                match_expr: None,
            });
        }
        if trimmed.len() > 4_096 {
            return Err(validation(
                "query_too_long",
                "search query exceeds 4096 UTF-8 bytes",
            ));
        }

        let mut terms = Vec::new();
        let chars: Vec<char> = trimmed.chars().collect();
        let mut i = 0usize;
        while i < chars.len() {
            let Some(&ch) = chars.get(i) else {
                break;
            };
            if is_emoji_char(ch) {
                terms.push(QueryTerm::Emoji {
                    token: ch.to_string(),
                });
                i += 1;
            } else if is_cjk(ch) {
                let start = i;
                i += 1;
                while i < chars.len() {
                    let Some(&next) = chars.get(i) else {
                        break;
                    };
                    if !is_cjk(next) {
                        break;
                    }
                    i += 1;
                }
                let run: String = chars.get(start..i).unwrap_or(&[]).iter().collect();
                if run.chars().count() == 1 {
                    terms.push(QueryTerm::CjkUnigram { token: run });
                } else {
                    let run_chars: Vec<char> = run.chars().collect();
                    let mut bigrams = Vec::with_capacity(run_chars.len().saturating_sub(1));
                    for window in run_chars.windows(2) {
                        if let (Some(a), Some(b)) = (window.first(), window.get(1)) {
                            let mut bg = String::new();
                            bg.push(*a);
                            bg.push(*b);
                            bigrams.push(bg);
                        }
                    }
                    terms.push(QueryTerm::CjkAdjacentBigrams { bigrams });
                }
            } else if is_word_char(ch) {
                let start = i;
                i += 1;
                while i < chars.len() {
                    let Some(&next) = chars.get(i) else {
                        break;
                    };
                    if !(is_word_char(next) && !is_cjk(next) && !is_emoji_char(next)) {
                        break;
                    }
                    i += 1;
                }
                let mut word: String = chars.get(start..i).unwrap_or(&[]).iter().collect();
                let upper = word.to_ascii_uppercase();
                if matches!(upper.as_str(), "AND" | "OR" | "NOT") {
                    word = word.to_ascii_lowercase();
                }
                terms.push(QueryTerm::Word { token: word });
            } else {
                i += 1;
            }
        }

        terms.truncate(MAX_QUERY_TERMS);
        dedupe_terms(&mut terms);

        let match_expr = if terms.is_empty() {
            None
        } else {
            Some(build_match_expr(&terms))
        };

        let plan = QueryPlan { terms, match_expr };
        if plan.uses_unbounded_cjk_unigram_or() {
            return Err(validation(
                "illegal_cjk_unigram_or",
                "multi-char CJK must not expand to unbounded unigram-OR",
            ));
        }
        Ok(plan)
    }
}

fn build_match_expr(terms: &[QueryTerm]) -> String {
    let mut parts = Vec::with_capacity(terms.len());
    for term in terms {
        match term {
            QueryTerm::CjkUnigram { token } => {
                parts.push(format!("{}*", escape_bare(token)));
            }
            QueryTerm::CjkAdjacentBigrams { bigrams } => {
                // Ordered adjacent phrase of bigrams — never unbounded unigram-OR.
                // Index layout emits unigrams then bigrams per CJK run, so bigrams are contiguous.
                let phrase = bigrams
                    .iter()
                    .map(|bg| escape_phrase(bg))
                    .collect::<Vec<_>>()
                    .join(" ");
                parts.push(format!("\"{phrase}\""));
            }
            QueryTerm::Word { token } | QueryTerm::Emoji { token } => {
                parts.push(format!("\"{}\"*", escape_phrase(token)));
            }
        }
    }
    parts.join(" ")
}

fn escape_phrase(token: &str) -> String {
    token.replace('"', "\"\"")
}

fn escape_bare(token: &str) -> String {
    // Unigrams are single CJK chars; still strip FTS specials.
    token
        .chars()
        .filter(|ch| !matches!(ch, '"' | '*' | '(' | ')' | ':'))
        .collect()
}

fn push_token(out: &mut String, token: &str) {
    if token.is_empty() {
        return;
    }
    if !out.is_empty() {
        out.push(' ');
    }
    out.push_str(token);
}

fn dedupe_terms(terms: &mut Vec<QueryTerm>) {
    let mut seen = Vec::new();
    terms.retain(|term| {
        if seen.contains(term) {
            false
        } else {
            seen.push(term.clone());
            true
        }
    });
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

fn is_word_char(ch: char) -> bool {
    ch.is_ascii_alphanumeric() || (!ch.is_ascii() && ch.is_alphanumeric() && !is_cjk(ch))
}

/// Convenience: index tokens via the default tokenizer.
#[must_use]
pub fn index_tokens(text: &str) -> String {
    UnicodeTokenizer.index_tokens(text)
}

/// Convenience: query plan via the default tokenizer.
///
/// # Errors
///
/// Propagates tokenizer validation errors.
pub fn query_plan(text: &str) -> Result<QueryPlan, lomo_core::LomoError> {
    UnicodeTokenizer.query_plan(text)
}
