//! Async poll continuation parameters for JNI native methods.
//!
//! The lower C bridge polls async work with two values: caller-owned callback
//! data and a completion function pointer. Java only supplies the callback data.
//! The JNI bridge supplies the fixed native completion function because that
//! function is part of the generated C source, not a Java value.
//!
//! This module keeps those two C arguments behind one Java parameter. Native
//! method rendering can then pass a prepared continuation contract instead of
//! remembering that one `jlong` Java argument expands to data plus callback.

use crate::{
    bridge::{
        c::{self, Expression, Identifier, TypeFragment},
        jni::ScalarParameter,
    },
    core::Result,
};

/// A Java callback-data token expanded to the C async poll continuation pair.
///
/// The first C argument is the Java token. The second C argument is the fixed
/// JNI continuation callback emitted by the bridge source file.
#[derive(Clone, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub struct ContinuationParameter {
    data: ScalarParameter,
}

impl ContinuationParameter {
    /// Returns the generated JNI parameter name.
    pub fn name(&self) -> &Identifier {
        self.data.name()
    }

    /// Returns the JNI parameter type.
    pub fn ty(&self) -> TypeFragment {
        self.data.ty().as_type_fragment()
    }

    /// Returns C bridge call arguments produced from this JNI parameter.
    pub fn c_arguments(&self) -> Result<Vec<Expression>> {
        Ok(vec![
            self.data.c_argument()?,
            Expression::identifier(Identifier::parse("boltffi_jni_continuation_callback")?),
        ])
    }

    /// Creates a JNI continuation parameter from a C continuation parameter group.
    pub fn from_c_group(group: &c::ContinuationParameter, function: &c::Function) -> Result<Self> {
        ScalarParameter::from_c_parameter(function.parameter(group.data()))
            .map(|data| Self { data })
    }
}
