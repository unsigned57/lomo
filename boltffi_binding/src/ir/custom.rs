use std::num::NonZeroUsize;

use serde::{Deserialize, Serialize};

use crate::NamePart;

/// Rust conversion expressions for one custom type declaration.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct CustomTypeConverters {
    into_ffi: CustomTypeConverter,
    try_from_ffi: CustomTypeConverter,
}

impl CustomTypeConverters {
    /// Builds a pair of Rust conversion expressions.
    pub fn new(into_ffi: CustomTypeConverter, try_from_ffi: CustomTypeConverter) -> Self {
        Self {
            into_ffi,
            try_from_ffi,
        }
    }

    /// Returns the converter from the Rust type to its representation.
    pub fn into_ffi(&self) -> &CustomTypeConverter {
        &self.into_ffi
    }

    /// Returns the fallible converter from the representation to the Rust type.
    pub fn try_from_ffi(&self) -> &CustomTypeConverter {
        &self.try_from_ffi
    }
}

/// A Rust expression used as a custom type converter.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
#[non_exhaustive]
pub enum CustomTypeConverter {
    /// A converter named by a Rust path.
    Path(CustomConverterPath),
    /// A `CustomFfiConvertible` method on the remote Rust type.
    TraitMethod(CustomTraitMethodConverter),
    /// A converter written as an inline Rust expression.
    Expression(CustomConverterExpression),
}

impl CustomTypeConverter {
    /// Builds a converter path.
    pub fn path(path: CustomConverterPath) -> Self {
        Self::Path(path)
    }

    /// Builds a `CustomFfiConvertible` method converter.
    pub fn trait_method(receiver: CustomConverterPath, method: impl Into<NamePart>) -> Self {
        Self::TraitMethod(CustomTraitMethodConverter::new(receiver, method))
    }

    /// Builds an inline converter expression.
    pub fn expression(expression: CustomConverterExpression) -> Self {
        Self::Expression(expression)
    }
}

/// A converter method selected from `CustomFfiConvertible`.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct CustomTraitMethodConverter {
    receiver: CustomConverterPath,
    method: NamePart,
}

impl CustomTraitMethodConverter {
    /// Builds a trait-method converter.
    pub fn new(receiver: CustomConverterPath, method: impl Into<NamePart>) -> Self {
        Self {
            receiver,
            method: method.into(),
        }
    }

    /// Returns the remote Rust type implementing `CustomFfiConvertible`.
    pub fn receiver(&self) -> &CustomConverterPath {
        &self.receiver
    }

    /// Returns the converter method name.
    pub fn method(&self) -> &NamePart {
        &self.method
    }
}

/// A Rust path used as a custom type converter.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct CustomConverterPath {
    root: CustomConverterPathRoot,
    segments: Vec<NamePart>,
}

impl CustomConverterPath {
    /// Builds a converter path from its root qualifier and segments.
    pub fn new(root: CustomConverterPathRoot, segments: Vec<NamePart>) -> Self {
        Self { root, segments }
    }

    /// Returns the path root.
    pub const fn root(&self) -> CustomConverterPathRoot {
        self.root
    }

    /// Returns the path segments.
    pub fn segments(&self) -> &[NamePart] {
        &self.segments
    }
}

/// The root qualifier of a custom converter path.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
#[non_exhaustive]
pub enum CustomConverterPathRoot {
    /// A relative path.
    Relative,
    /// A path starting at `crate`.
    Crate,
    /// A path starting at `self`.
    Self_,
    /// A path starting at one or more `super` segments.
    Super(NonZeroUsize),
    /// A path starting at the extern prelude.
    Absolute,
}

/// Source text for an inline custom converter expression.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct CustomConverterExpression {
    source: String,
}

impl CustomConverterExpression {
    /// Builds an inline converter expression.
    pub fn new(source: impl Into<String>) -> Self {
        Self {
            source: source.into(),
        }
    }

    /// Returns the Rust expression source.
    pub fn source(&self) -> &str {
        &self.source
    }
}
