use serde::{Deserialize, Serialize};

use crate::Path;

/// An integer literal written in Rust source.
///
/// Rust integer literals can carry suffixes and bases. Keeping both the parsed
/// value and the original spelling preserves what the author wrote while still
/// giving consumers a numeric value to compare.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct IntegerLiteral {
    /// Parsed integer value after Rust literal parsing.
    pub value: i128,
    /// Source spelling of the literal.
    pub source: String,
}

impl IntegerLiteral {
    /// Builds an integer literal from its parsed value and source spelling.
    ///
    /// The `value` parameter is the numeric value. The `source` parameter is the
    /// literal spelling collected from Rust source.
    ///
    /// Returns an integer literal suitable for discriminants, defaults, and
    /// constants.
    pub fn new(value: i128, source: impl Into<String>) -> Self {
        Self {
            value,
            source: source.into(),
        }
    }
}

/// A floating-point literal written in Rust source.
///
/// Floating literals are kept by spelling so suffixes, exponent notation, and
/// precision remain available alongside the declaration type.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct FloatLiteral {
    /// Source spelling of the floating-point literal.
    pub source: String,
}

impl FloatLiteral {
    /// Builds a floating-point literal from source text.
    ///
    /// The `source` parameter is stored unchanged so suffixes and exponent
    /// spelling remain visible to diagnostics.
    ///
    /// Returns a floating literal from its source spelling.
    pub fn new(source: impl Into<String>) -> Self {
        Self {
            source: source.into(),
        }
    }
}

/// A literal value written in source.
///
/// Literals appear in enum discriminants, defaults, and constants. The variants
/// mirror the literal families BoltFFI needs to preserve today.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub enum Literal {
    /// A boolean literal.
    Bool(bool),
    /// An integer literal.
    Integer(IntegerLiteral),
    /// A floating-point literal.
    Float(FloatLiteral),
    /// A string literal after Rust escape processing.
    String(String),
    /// A byte string literal after Rust escape processing.
    Bytes(Vec<u8>),
}

/// A constant expression small enough to preserve structurally.
///
/// The AST models the expression forms that commonly appear in exported
/// metadata, while `Raw` keeps uncommon expressions available for error
/// reporting and future support.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub enum ConstExpr {
    /// A literal constant.
    Literal(Literal),
    /// A path constant such as an enum variant.
    Path(Path),
    /// An array constant.
    Array(Vec<ConstExpr>),
    /// A tuple constant.
    Tuple(Vec<ConstExpr>),
    /// A raw expression kept for diagnostics until the scanner grows a richer
    /// representation for it.
    Raw(String),
}

/// A default value written on a field or parameter.
///
/// Defaults have call-site meaning: generated bindings may expose them as
/// default arguments or initializer values.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub enum DefaultValue {
    /// The Rust `true` or `false` literal.
    Bool(bool),
    /// An integer default.
    Integer(IntegerLiteral),
    /// A floating-point default.
    Float(FloatLiteral),
    /// A string default.
    String(String),
    /// A byte string default.
    Bytes(Vec<u8>),
    /// A default that names another item, most often an enum variant.
    Path(Path),
    /// A `None` default for optional values.
    None,
}
