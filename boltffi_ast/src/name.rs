use std::fmt;
use std::num::NonZeroUsize;

use serde::{Deserialize, Serialize};

use crate::{ConstExpr, TypeExpr};

/// One normalized part of a canonical BoltFFI name.
///
/// A source name can be written in Rust as `HTTPRequest`, `http_request`, or
/// through a future naming attribute. `NamePart` stores the scanner's canonical
/// part after that spelling has been normalized.
#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
pub struct NamePart(String);

impl NamePart {
    /// Builds a canonical name part.
    ///
    /// The `part` parameter is expected to be the scanner's normalized spelling
    /// for one segment of a declaration name. This function keeps the value
    /// unchanged so normalization remains visible and testable at the scan
    /// layer.
    ///
    /// Returns a name part that can be reused by canonical names, paths, and
    /// attributes.
    pub fn new(part: impl Into<String>) -> Self {
        Self(part.into())
    }

    /// Returns the stored canonical spelling.
    ///
    /// The returned value is the canonical part text.
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl fmt::Display for NamePart {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.0)
    }
}

impl From<String> for NamePart {
    fn from(part: String) -> Self {
        Self::new(part)
    }
}

impl From<&str> for NamePart {
    fn from(part: &str) -> Self {
        Self::new(part)
    }
}

/// A canonical source name used by declarations, fields, parameters, and variants.
///
/// Names are stored as parts so `http_request` and `HTTPRequest` can share one
/// normalized representation while still allowing consumers to choose their own
/// display spelling.
#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
pub struct CanonicalName {
    /// Normalized parts in declaration order.
    pub parts: Vec<NamePart>,
}

impl CanonicalName {
    /// Builds a canonical name from normalized parts.
    ///
    /// The `parts` parameter should already be split and normalized by the
    /// scanner. Empty names are allowed here so scan errors can still carry a
    /// partially built AST with useful spans.
    ///
    /// Returns a canonical name built from the provided parts.
    pub fn new(parts: Vec<NamePart>) -> Self {
        Self { parts }
    }

    /// Builds a canonical name from one source part.
    ///
    /// The `part` parameter becomes the only part of the name.
    ///
    /// Returns the shape used for simple field names and single-segment item
    /// names.
    pub fn single(part: impl Into<NamePart>) -> Self {
        Self {
            parts: vec![part.into()],
        }
    }

    /// Iterates over the canonical parts.
    ///
    /// The iterator yields the parts in source order and performs no allocation.
    pub fn parts(&self) -> impl Iterator<Item = &NamePart> {
        self.parts.iter()
    }

    /// Joins the canonical parts with `::` for diagnostics and stable IDs.
    ///
    /// This is a display helper for humans and metadata keys.
    pub fn as_path_string(&self) -> String {
        self.parts
            .iter()
            .map(NamePart::as_str)
            .collect::<Vec<_>>()
            .join("::")
    }
}

/// A source name with its exact Rust spelling and canonical API projection.
///
/// `spelling` preserves the token text the scanner saw, such as `HTTPRequest`
/// or `r#type`. `canonical` stores the normalized API name used by binding
/// contracts and generated language names.
#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
pub struct SourceName {
    spelling: String,
    canonical: CanonicalName,
}

impl SourceName {
    /// Builds a source name from exact spelling and canonical name.
    pub fn new(spelling: impl Into<String>, canonical: CanonicalName) -> Self {
        Self {
            spelling: spelling.into(),
            canonical,
        }
    }

    /// Builds a source name when only a canonical name is available.
    pub fn from_canonical(canonical: CanonicalName) -> Self {
        Self {
            spelling: canonical.as_path_string(),
            canonical,
        }
    }

    /// Returns the exact Rust source spelling.
    pub fn spelling(&self) -> &str {
        &self.spelling
    }

    /// Returns the canonical API name.
    pub const fn canonical(&self) -> &CanonicalName {
        &self.canonical
    }

    /// Iterates over canonical name parts.
    pub fn parts(&self) -> impl Iterator<Item = &NamePart> {
        self.canonical.parts()
    }

    /// Joins the canonical parts with `::`.
    pub fn as_path_string(&self) -> String {
        self.canonical.as_path_string()
    }
}

impl From<CanonicalName> for SourceName {
    fn from(canonical: CanonicalName) -> Self {
        Self::from_canonical(canonical)
    }
}

impl From<&SourceName> for CanonicalName {
    fn from(name: &SourceName) -> Self {
        name.canonical.clone()
    }
}

/// The root qualifier used by a Rust path.
///
/// `Foo`, `crate::Foo`, `self::Foo`, `super::Foo`, and `::foo::Foo` can name
/// different things. The qualifier keeps that spelling visible.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub enum PathRoot {
    /// A relative path such as `Foo` or `module::Foo`.
    Relative,
    /// A path starting at the current crate.
    Crate,
    /// A path starting at the current module.
    Self_,
    /// A path starting at one or more parent modules.
    Super(NonZeroUsize),
    /// A path starting at the extern prelude.
    Absolute,
}

/// A Rust path with generic arguments preserved on each segment.
///
/// Paths appear in user attributes, custom converters, const expressions, and
/// generic type syntax. Segment-level arguments preserve shapes such as
/// `std::borrow::Cow<'a, str>` without flattening them into a string.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct Path {
    /// Where path resolution starts.
    pub root: PathRoot,
    /// Segments from root to leaf.
    pub segments: Vec<PathSegment>,
}

impl Path {
    /// Builds a path from its root qualifier and segments.
    ///
    /// The `root` parameter records how the path was written. The `segments`
    /// parameter stores the named path components in source order.
    ///
    /// Returns an unresolved source path.
    pub fn new(root: PathRoot, segments: Vec<PathSegment>) -> Self {
        Self { root, segments }
    }

    /// Builds a relative path with a single segment.
    ///
    /// The `name` parameter becomes the final path segment and carries no
    /// generic arguments.
    ///
    /// Returns the path form for simple names such as `Point`.
    pub fn single(name: impl Into<NamePart>) -> Self {
        Self {
            root: PathRoot::Relative,
            segments: vec![PathSegment::new(name)],
        }
    }

    /// Returns the final segment, if the path has one.
    ///
    /// This is useful for diagnostics where the final name is the clearest part
    /// of a path.
    pub fn last(&self) -> Option<&PathSegment> {
        self.segments.last()
    }
}

/// One segment of a Rust path.
///
/// Generic arguments live on the segment that owns them, so `Result<T, E>` is a
/// single segment named `Result` with two arguments.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct PathSegment {
    /// The canonical spelling of this path segment.
    pub name: NamePart,
    /// Generic arguments attached to this segment.
    pub arguments: Vec<GenericArgument>,
}

impl PathSegment {
    /// Builds a path segment without generic arguments.
    ///
    /// The `name` parameter is stored as the segment name.
    ///
    /// Returns a segment for non-generic path components.
    pub fn new(name: impl Into<NamePart>) -> Self {
        Self {
            name: name.into(),
            arguments: Vec::new(),
        }
    }

    /// Builds a path segment with explicit generic arguments.
    ///
    /// The `name` parameter identifies the segment. The `arguments` parameter
    /// preserves the generic argument list in source order.
    ///
    /// Returns a segment for path components such as `Vec<T>`.
    pub fn with_arguments(name: impl Into<NamePart>, arguments: Vec<GenericArgument>) -> Self {
        Self {
            name: name.into(),
            arguments,
        }
    }
}

/// A generic argument attached to a path segment.
///
/// Type, const, and associated-type arguments carry different Rust meaning, so
/// each gets its own variant.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub enum GenericArgument {
    /// A type argument such as `T` in `Vec<T>`.
    Type(TypeExpr),
    /// A const argument such as `N` in `[u8; N]`.
    Const(ConstExpr),
    /// An associated type equality such as `Item = String`.
    AssociatedType {
        /// The associated type being assigned.
        name: NamePart,
        /// The type written on the right side of the equality.
        type_expr: TypeExpr,
    },
}
