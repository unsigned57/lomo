use serde::{Deserialize, Serialize};

use crate::CanonicalName;

/// Documentation text preserved from the Rust source.
///
/// Stored verbatim, including newlines and Markdown the author wrote. Each
/// target language reformats the text into its own documentation syntax
/// (KDoc for Kotlin, Javadoc for Java, docstrings for Python, and so on).
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct DocComment(String);

impl DocComment {
    /// Stores documentation text.
    pub fn new(text: impl Into<String>) -> Self {
        Self(text.into())
    }

    /// Returns the text.
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// Deprecation metadata preserved from the Rust source.
///
/// `since` is the version of the Rust crate that introduced the
/// deprecation; `message` is the human-readable reason. Either may be
/// absent if the source `#[deprecated]` attribute did not provide it.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct DeprecationInfo {
    message: Option<String>,
    since: Option<String>,
}

impl DeprecationInfo {
    /// Builds deprecation metadata.
    pub fn new(message: Option<String>, since: Option<String>) -> Self {
        Self { message, since }
    }

    /// Returns the deprecation message.
    pub fn message(&self) -> Option<&str> {
        self.message.as_deref()
    }

    /// Returns the version that introduced the deprecation.
    pub fn since(&self) -> Option<&str> {
        self.since.as_deref()
    }
}

/// A resolved integer literal value.
///
/// The source expression has already been evaluated to a concrete number,
/// so generated bindings can render the value without parsing Rust syntax.
/// Stored as `i128` so any signed or unsigned integer up to 64 bits
/// round-trips without loss.
#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
#[serde(transparent)]
pub struct IntegerValue(i128);

impl IntegerValue {
    /// Stores a resolved integer value.
    pub const fn new(value: i128) -> Self {
        Self(value)
    }

    /// Returns the value.
    pub const fn get(self) -> i128 {
        self.0
    }
}

/// A resolved floating-point literal value, stored as a bit pattern.
///
/// Storing the IEEE-754 bits rather than the `f64` directly makes equality,
/// hashing, and serialization deterministic: `+0.0` and `-0.0` are not
/// equal, NaNs compare equal to themselves, and round-tripping through
/// serde does not lose the distinction between two NaN payloads.
///
/// # Example
///
/// `FloatValue::from_f64(1.5).bits() == 0x3FF8000000000000`.
#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
#[serde(transparent)]
pub struct FloatValue(u64);

impl FloatValue {
    /// Stores an `f64` literal by its IEEE-754 bit pattern.
    pub const fn from_f64(value: f64) -> Self {
        Self(value.to_bits())
    }

    /// Returns the value as `f64`.
    pub const fn to_f64(self) -> f64 {
        f64::from_bits(self.0)
    }

    /// Returns the underlying bit pattern.
    pub const fn bits(self) -> u64 {
        self.0
    }
}

/// A literal value generated bindings can emit without calling Rust.
///
/// The classifier admits a default only when the value can be expressed
/// directly in every reasonable target language: primitive scalars,
/// strings, named enum variants, and the absence of a value. Anything that
/// would require running Rust at binding time (a `Vec::new()` call, a
/// computed expression) is not stored here.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
#[non_exhaustive]
pub enum DefaultValue {
    /// Boolean literal.
    Bool(bool),
    /// Integer literal.
    Integer(IntegerValue),
    /// Floating-point literal.
    Float(FloatValue),
    /// UTF-8 string literal.
    String(String),
    /// Named enum variant.
    EnumVariant {
        /// Enum carrying the variant.
        enum_name: CanonicalName,
        /// Variant selected as the default.
        variant_name: CanonicalName,
    },
    /// Absence of a value, for optional types.
    Null,
}

/// Source-derived metadata attached to a top-level declaration.
///
/// Documentation and deprecation are the two facts the binding contract
/// preserves at declaration scope. Everything else (source spans,
/// attributes, attribute arguments) is consumed by the classifier or
/// dropped.
///
/// # Example
///
/// A Rust `#[deprecated(since = "0.24.0", note = "use open instead")]`
/// attribute on an exported function becomes deprecation metadata here.
/// The backend can render that as a target-language deprecation annotation
/// without parsing Rust attributes.
#[derive(Clone, Debug, Default, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct DeclMeta {
    doc: Option<DocComment>,
    deprecated: Option<DeprecationInfo>,
}

impl DeclMeta {
    /// Builds declaration metadata.
    pub fn new(doc: Option<DocComment>, deprecated: Option<DeprecationInfo>) -> Self {
        Self { doc, deprecated }
    }

    /// Returns the doc comment.
    pub fn doc(&self) -> Option<&DocComment> {
        self.doc.as_ref()
    }

    /// Returns the deprecation info.
    pub fn deprecated(&self) -> Option<&DeprecationInfo> {
        self.deprecated.as_ref()
    }
}

/// Source-derived metadata attached to a field, parameter, or variant.
///
/// Same shape as [`DeclMeta`] plus a default value, which fields,
/// parameters, and variants can carry and top-level declarations cannot.
#[derive(Clone, Debug, Default, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct ElementMeta {
    doc: Option<DocComment>,
    deprecated: Option<DeprecationInfo>,
    default: Option<DefaultValue>,
}

impl ElementMeta {
    /// Builds element metadata.
    pub fn new(
        doc: Option<DocComment>,
        deprecated: Option<DeprecationInfo>,
        default: Option<DefaultValue>,
    ) -> Self {
        Self {
            doc,
            deprecated,
            default,
        }
    }

    /// Returns the doc comment.
    pub fn doc(&self) -> Option<&DocComment> {
        self.doc.as_ref()
    }

    /// Returns the deprecation info.
    pub fn deprecated(&self) -> Option<&DeprecationInfo> {
        self.deprecated.as_ref()
    }

    /// Returns the default value.
    pub fn default(&self) -> Option<&DefaultValue> {
        self.default.as_ref()
    }
}
