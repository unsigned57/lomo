use std::sync::Arc;

use lomo_core::LomoError;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::limits::{corruption, validation};

/// SHA-256 fingerprint of exact source bytes (including BOM when present).
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct SourceFingerprint(String);

impl SourceFingerprint {
    /// Computes the lowercase hex SHA-256 of `bytes`.
    #[must_use]
    pub fn of_bytes(bytes: &[u8]) -> Self {
        let digest = Sha256::digest(bytes);
        Self(format!("{digest:x}"))
    }

    /// Parses a lowercase hexadecimal SHA-256 digest.
    ///
    /// # Errors
    ///
    /// Returns a validation error unless the value is exactly 64 lowercase hexadecimal bytes.
    pub fn parse(raw: &str) -> Result<Self, LomoError> {
        if raw.len() != 64
            || !raw
                .bytes()
                .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
        {
            return Err(validation(
                "invalid_source_fingerprint",
                "source fingerprint must be 64 lowercase hexadecimal bytes",
            ));
        }
        Ok(Self(raw.to_owned()))
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// Inclusive-exclusive byte span into a source buffer.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct ByteSpan {
    start: usize,
    end: usize,
}

impl ByteSpan {
    /// Creates a span that is fully contained in a source of `source_len` bytes.
    ///
    /// # Errors
    ///
    /// Returns a validation error when `end < start` or `end` exceeds `source_len`.
    pub fn try_new(start: usize, end: usize, source_len: usize) -> Result<Self, LomoError> {
        if end < start || end > source_len {
            return Err(validation(
                "invalid_byte_span",
                "byte span must satisfy start <= end <= source length",
            ));
        }
        Ok(Self { start, end })
    }

    #[must_use]
    pub const fn start(self) -> usize {
        self.start
    }

    #[must_use]
    pub const fn end(self) -> usize {
        self.end
    }

    #[must_use]
    pub const fn len(self) -> usize {
        self.end - self.start
    }

    #[must_use]
    pub const fn is_empty(self) -> bool {
        self.start == self.end
    }
}

/// Byte-order mark observed at the start of a source buffer.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum BomKind {
    None,
    Utf8,
}

/// Newline kind observed in source text.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum NewlineKind {
    Lf,
    Crlf,
    Cr,
}

/// Dominant newline policy derived from source bytes.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum DominantNewline {
    None,
    Uniform(NewlineKind),
    Mixed,
}

/// Trailing newline / blank-line state at end of file.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct TrailingState {
    ends_with_newline: bool,
    trailing_blank_lines: u32,
}

impl TrailingState {
    #[must_use]
    pub const fn new(ends_with_newline: bool, trailing_blank_lines: u32) -> Self {
        Self {
            ends_with_newline,
            trailing_blank_lines,
        }
    }

    #[must_use]
    pub const fn ends_with_newline(self) -> bool {
        self.ends_with_newline
    }

    #[must_use]
    pub const fn trailing_blank_lines(self) -> u32 {
        self.trailing_blank_lines
    }
}

/// BOM, newline, and trailing facts for a validated source buffer.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct SourceTextState {
    bom: BomKind,
    dominant_newline: DominantNewline,
    trailing: TrailingState,
}

impl SourceTextState {
    #[must_use]
    pub const fn bom(self) -> BomKind {
        self.bom
    }

    #[must_use]
    pub const fn dominant_newline(self) -> DominantNewline {
        self.dominant_newline
    }

    #[must_use]
    pub const fn trailing(self) -> TrailingState {
        self.trailing
    }

    fn inspect(bytes: &[u8]) -> Self {
        let (bom, body) = match bytes {
            [0xEF, 0xBB, 0xBF, rest @ ..] => (BomKind::Utf8, rest),
            other => (BomKind::None, other),
        };
        let mut saw_lf = false;
        let mut saw_crlf = false;
        let mut saw_cr = false;
        let mut index = 0usize;
        while index < body.len() {
            match body[index] {
                b'\n' => {
                    saw_lf = true;
                    index += 1;
                }
                b'\r' => {
                    if body.get(index + 1) == Some(&b'\n') {
                        saw_crlf = true;
                        index += 2;
                    } else {
                        saw_cr = true;
                        index += 1;
                    }
                }
                _ => index += 1,
            }
        }
        let dominant_newline = match (saw_lf, saw_crlf, saw_cr) {
            (false, false, false) => DominantNewline::None,
            (true, false, false) => DominantNewline::Uniform(NewlineKind::Lf),
            (false, true, false) => DominantNewline::Uniform(NewlineKind::Crlf),
            (false, false, true) => DominantNewline::Uniform(NewlineKind::Cr),
            _ => DominantNewline::Mixed,
        };
        let trailing = trailing_state(body);
        Self {
            bom,
            dominant_newline,
            trailing,
        }
    }
}

fn trailing_state(body: &[u8]) -> TrailingState {
    if body.is_empty() {
        return TrailingState::new(false, 0);
    }
    let ends_with_newline = matches!(body.last(), Some(b'\n' | b'\r'));
    if !ends_with_newline {
        return TrailingState::new(false, 0);
    }

    let mut blank_lines = 0u32;
    let mut index = body.len();
    while index > 0 {
        let previous = index - 1;
        match body[previous] {
            b'\n' => {
                if previous > 0 && body[previous - 1] == b'\r' {
                    index = previous - 1;
                } else {
                    index = previous;
                }
                blank_lines = blank_lines.saturating_add(1);
            }
            b'\r' => {
                index = previous;
                blank_lines = blank_lines.saturating_add(1);
            }
            _ => break,
        }
    }
    // The final newline ends the last content line; only additional newline runs are blank lines.
    let trailing_blank_lines = blank_lines.saturating_sub(1);
    TrailingState::new(true, trailing_blank_lines)
}

/// Strict UTF-8 source bytes with fingerprint and text-state metadata.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SourceBytes {
    bytes: Arc<[u8]>,
    text: Arc<str>,
    fingerprint: SourceFingerprint,
    text_state: SourceTextState,
}

impl SourceBytes {
    /// Validates UTF-8 and captures fingerprint plus BOM/newline/trailing facts.
    ///
    /// # Errors
    ///
    /// Returns a corruption error when `bytes` are not valid UTF-8. Invalid input is never
    /// replaced, emptied, or partially accepted.
    pub fn try_from_bytes(bytes: impl Into<Vec<u8>>) -> Result<Self, LomoError> {
        let owned = bytes.into();
        let text = std::str::from_utf8(&owned).map_err(|_error| {
            corruption(
                "source_not_utf8",
                "source bytes must be valid UTF-8 without replacement",
            )
        })?;
        let text: Arc<str> = Arc::from(text);
        let fingerprint = SourceFingerprint::of_bytes(&owned);
        let text_state = SourceTextState::inspect(&owned);
        Ok(Self {
            bytes: Arc::from(owned),
            text,
            fingerprint,
            text_state,
        })
    }

    /// Validates a UTF-8 string as source bytes.
    ///
    /// # Errors
    ///
    /// Inherits [`Self::try_from_bytes`] failures.
    pub fn try_from_str(text: &str) -> Result<Self, LomoError> {
        Self::try_from_bytes(text.as_bytes().to_vec())
    }

    #[must_use]
    pub fn as_bytes(&self) -> &[u8] {
        &self.bytes
    }

    /// Returns the validated UTF-8 view.
    ///
    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.text
    }

    #[must_use]
    pub fn len(&self) -> usize {
        self.bytes.len()
    }

    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.bytes.is_empty()
    }

    #[must_use]
    pub const fn fingerprint(&self) -> &SourceFingerprint {
        &self.fingerprint
    }

    #[must_use]
    pub const fn text_state(&self) -> SourceTextState {
        self.text_state
    }

    /// Slices this source by an already-validated span.
    ///
    /// # Errors
    ///
    /// Returns a validation error when the span is outside this source.
    pub fn slice(&self, span: ByteSpan) -> Result<&str, LomoError> {
        let checked = ByteSpan::try_new(span.start(), span.end(), self.len())?;
        Ok(&self.as_str()[checked.start()..checked.end()])
    }
}
