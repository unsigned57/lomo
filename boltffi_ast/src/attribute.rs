use serde::{Deserialize, Serialize};

use crate::{ConstExpr, Path, Primitive};

/// A Rust attribute that BoltFFI keeps with the item it was written on.
///
/// Some attributes are meaningful to BoltFFI today, while others are useful
/// context for generated documentation and validation. Keeping the path and
/// input together lets consumers inspect attributes without reparsing the
/// original token stream.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct UserAttr {
    /// Attribute path, such as `serde::rename`.
    pub path: Path,
    /// Attribute input after the path.
    pub input: AttributeInput,
}

impl UserAttr {
    /// Builds an attribute from its path and input.
    ///
    /// The `path` parameter identifies the attribute. The `input` parameter
    /// stores the tokens or structured constant expression written after it.
    ///
    /// Returns the attribute exactly at the level the scanner understands it.
    pub fn new(path: Path, input: AttributeInput) -> Self {
        Self { path, input }
    }
}

/// The input written after an attribute path.
///
/// Common forms use structured values. More complex forms can stay as tokens
/// until a consumer needs to understand them.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub enum AttributeInput {
    /// An attribute without input, such as `#[data]`.
    Empty,
    /// A name-value attribute input.
    Value(ConstExpr),
    /// A parenthesized list of nested attribute inputs.
    List(Vec<AttributeInput>),
    /// Raw tokens kept for attribute forms outside the structured subset.
    Tokens(String),
}

/// Representation hints written with `#[repr(...)]`.
///
/// Rust permits more than one `repr` attribute on the same item and more than
/// one argument inside a single attribute. `ReprAttr` stores the parsed items in
/// source order so callers can inspect the whole representation request without
/// walking raw attributes again.
#[derive(Clone, Debug, Default, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct ReprAttr {
    /// Items written inside all `repr` attributes attached to the declaration.
    pub items: Vec<ReprItem>,
}

impl ReprAttr {
    /// Builds representation hints from parsed `repr` items.
    ///
    /// The `items` parameter keeps items in source order across every `repr`
    /// attribute on the same declaration.
    ///
    /// Returns representation metadata in the same order it was written.
    pub fn new(items: Vec<ReprItem>) -> Self {
        Self { items }
    }

    /// Returns a representation with no `repr` hints.
    ///
    /// Use this for declarations where the author did not write `#[repr(...)]`.
    pub fn none() -> Self {
        Self::default()
    }
}

/// One argument inside `#[repr(...)]`.
///
/// Each variant mirrors a spelling Rust accepts in representation attributes,
/// while `Other` keeps uncommon or future arguments visible to diagnostics.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub enum ReprItem {
    /// `repr(C)`.
    C,
    /// `repr(transparent)`.
    Transparent,
    /// An integer representation such as `repr(u8)` or `repr(i32)`.
    Primitive(Primitive),
    /// `repr(packed)` or `repr(packed(N))`.
    Packed(Option<u16>),
    /// `repr(align(N))`.
    Align(u16),
    /// A repr item outside the structured subset.
    Other(String),
}
