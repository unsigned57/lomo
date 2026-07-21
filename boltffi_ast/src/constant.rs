use serde::{Deserialize, Serialize};

use crate::{
    ConstExpr, ConstantId, DeprecationInfo, DocComment, Source, SourceName, SourceSpan, TypeExpr,
    UserAttr,
};

/// A constant exported in the source contract.
///
/// Constants keep the declared type and the expression the scanner was able to
/// preserve. This lets generated bindings expose named values without treating
/// them as zero-argument functions.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct ConstantDef {
    /// Stable constant identity derived from the canonical Rust path.
    pub id: ConstantId,
    /// Source constant name.
    pub name: SourceName,
    /// Declared Rust source type.
    pub type_expr: TypeExpr,
    /// Source expression used as the constant value.
    pub value: ConstExpr,
    /// User attributes preserved from the constant.
    pub user_attrs: Vec<UserAttr>,
    /// Documentation attached to the constant.
    pub doc: Option<DocComment>,
    /// Deprecation metadata attached to the constant.
    pub deprecated: Option<DeprecationInfo>,
    /// Visibility and source location for diagnostics.
    pub source: Source,
    /// Span available during macro expansion.
    #[serde(default, skip_serializing, skip_deserializing)]
    pub source_span: Option<SourceSpan>,
}

impl ConstantDef {
    /// Builds a constant definition.
    ///
    /// The `id` parameter is the stable constant ID. The `name` parameter is the
    /// canonical constant name. The `rust_type` and `value` parameters record
    /// the source declaration.
    ///
    /// Returns a constant with no attributes, documentation, or deprecation.
    pub fn new(
        id: ConstantId,
        name: impl Into<SourceName>,
        type_expr: TypeExpr,
        value: ConstExpr,
    ) -> Self {
        Self {
            id,
            name: name.into(),
            type_expr,
            value,
            user_attrs: Vec::new(),
            doc: None,
            deprecated: None,
            source: Source::exported(),
            source_span: None,
        }
    }
}
