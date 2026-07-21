//! JVM call shape for callback arguments.
//!
//! After callback arguments have been grouped from the C slot, the generated C
//! code still needs the JVM-facing spellings: the descriptor segment for the
//! static Java method and the C expressions passed to `CallStatic*Method`.
//!
//! This module provides that projection from `CallbackArgument`. It is not a
//! classifier. The argument kind was already chosen by the C-group conversion;
//! this code only exposes the descriptor and call expressions that follow from
//! that contract.

use crate::bridge::c::{Expression, TypeFragment};

use super::{CallbackArgument, CallbackArgumentKind};

impl CallbackArgument {
    /// Returns the JNI method descriptor segment for this argument.
    pub fn jni_signature(&self) -> &'static str {
        match &self.kind {
            CallbackArgumentKind::Value { jni_type, .. } => jni_type.signature(),
            CallbackArgumentKind::SuccessOut { argument, .. } => argument.jni_type().signature(),
            CallbackArgumentKind::Bytes { .. } | CallbackArgumentKind::Record { .. } => "[B",
            CallbackArgumentKind::DirectVector { jni_type, .. } => jni_type.array_signature(),
            CallbackArgumentKind::CallbackHandle { .. } | CallbackArgumentKind::Closure { .. } => {
                "J"
            }
            CallbackArgumentKind::Completion { .. } => "JJ",
        }
    }

    /// Returns the expressions passed to the static JVM callback method.
    pub fn jni_arguments(&self) -> Vec<Expression> {
        match &self.kind {
            CallbackArgumentKind::Value {
                parameter,
                jni_type,
            } => vec![Expression::cast(
                jni_type.as_type_fragment(),
                Expression::identifier(parameter.name().clone()),
            )],
            CallbackArgumentKind::SuccessOut {
                parameter,
                argument,
            } => vec![Expression::cast(
                argument.jni_type().as_type_fragment(),
                Expression::identifier(parameter.name().clone()),
            )],
            CallbackArgumentKind::Bytes { name, .. } => {
                vec![Expression::identifier(name.clone())]
            }
            CallbackArgumentKind::DirectVector { array, .. } => {
                vec![Expression::identifier(array.clone())]
            }
            CallbackArgumentKind::Record { array, .. } => {
                vec![Expression::identifier(array.clone())]
            }
            CallbackArgumentKind::CallbackHandle { handle, .. } => {
                vec![Expression::identifier(handle.clone())]
            }
            CallbackArgumentKind::Closure { handle, .. } => {
                vec![Expression::identifier(handle.clone())]
            }
            CallbackArgumentKind::Completion {
                callback, context, ..
            } => {
                let jlong = TypeFragment::new("jlong");
                vec![
                    Expression::cast(
                        jlong.clone(),
                        Expression::identifier(callback.name().clone()),
                    ),
                    Expression::cast(jlong, Expression::identifier(context.name().clone())),
                ]
            }
        }
    }
}
