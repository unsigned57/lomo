use boltffi_ast::{
    DefaultValue as SourceDefaultValue, DeprecationInfo as SourceDeprecationInfo,
    DocComment as SourceDocComment, FloatLiteral as SourceFloatLiteral,
};

use crate::{
    DeclMeta, DefaultValue, DeprecationInfo, DocComment, ElementMeta, FloatValue, IntegerValue,
};

use super::{LowerError, error::UnsupportedType};

pub fn decl_meta(
    doc: Option<&SourceDocComment>,
    deprecated: Option<&SourceDeprecationInfo>,
) -> DeclMeta {
    DeclMeta::new(doc.map(DocComment::from), deprecated.map(Into::into))
}

pub fn element_meta(
    doc: Option<&SourceDocComment>,
    deprecated: Option<&SourceDeprecationInfo>,
    default: Option<&SourceDefaultValue>,
) -> Result<ElementMeta, LowerError> {
    Ok(ElementMeta::new(
        doc.map(DocComment::from),
        deprecated.map(Into::into),
        default.map(DefaultValue::try_from).transpose()?,
    ))
}

impl From<&SourceDocComment> for DocComment {
    fn from(doc: &SourceDocComment) -> Self {
        Self::new(doc.as_str())
    }
}

impl From<&SourceDeprecationInfo> for DeprecationInfo {
    fn from(deprecated: &SourceDeprecationInfo) -> Self {
        Self::new(deprecated.note.clone(), deprecated.since.clone())
    }
}

impl TryFrom<&SourceDefaultValue> for DefaultValue {
    type Error = LowerError;

    fn try_from(default: &SourceDefaultValue) -> Result<Self, Self::Error> {
        match default {
            SourceDefaultValue::Bool(value) => Ok(DefaultValue::Bool(*value)),
            SourceDefaultValue::Integer(value) => {
                Ok(DefaultValue::Integer(IntegerValue::new(value.value)))
            }
            SourceDefaultValue::Float(literal) => parse_float_literal(literal)
                .map(DefaultValue::Float)
                .ok_or_else(|| LowerError::unsupported_type(UnsupportedType::DefaultValue)),
            SourceDefaultValue::String(value) => Ok(DefaultValue::String(value.clone())),
            SourceDefaultValue::None => Ok(DefaultValue::Null),
            SourceDefaultValue::Bytes(_) | SourceDefaultValue::Path(_) => {
                Err(LowerError::unsupported_type(UnsupportedType::DefaultValue))
            }
        }
    }
}

/// Parses a Rust-source float literal spelling into an
/// [`FloatValue`] by IEEE-754 bit pattern.
///
/// Strips the `f32`/`f64` type suffix and any digit separators, then
/// parses through `f64::from_str`. Returns `None` for unparseable
/// literals; callers route that to a categorical rejection. `FloatValue`
/// stores the f64 bits, so f32 source literals round-trip through f64.
pub fn parse_float_literal(literal: &SourceFloatLiteral) -> Option<FloatValue> {
    let raw = literal.source.as_str();
    let trimmed = raw
        .trim_end_matches("f64")
        .trim_end_matches("f32")
        .trim_end_matches('_');
    let normalized: String = trimmed
        .chars()
        .filter(|character| *character != '_')
        .collect();
    normalized.parse::<f64>().ok().map(FloatValue::from_f64)
}
