//! Failure values for JVM method dispatch.
//!
//! A callback or closure trampoline can fail before Rust receives a normal
//! result, for example when Java throws while the native C function still has a
//! non-void return type. The generated C body must then return a valid fallback
//! value with the correct ABI type.
//!
//! This module derives that fallback from the typed JVM return contract. It
//! keeps exception paths aligned with the same return shape used by successful
//! dispatch.

use crate::bridge::{
    c::{Expression, Literal},
    jni::JvmMethodReturn,
};

impl JvmMethodReturn {
    /// Returns the C expression used when JVM dispatch fails.
    pub fn failure_value(&self) -> Option<Expression> {
        match self {
            Self::Void { .. } => None,
            Self::Value { jni_type, .. } => Some(Expression::literal(jni_type.failure_value())),
            Self::Bytes { c_type }
            | Self::Record { c_type }
            | Self::CallbackHandle { c_type, .. } => Some(Expression::cast(
                c_type.clone(),
                Expression::literal(Literal::compound_zero()),
            )),
            Self::Closure { c_type } => Some(Expression::cast(
                c_type.clone(),
                Expression::literal(Literal::status_failure()),
            )),
        }
    }

    /// Returns the JNI expression used when a Rust-owned closure call fails.
    pub fn jni_failure_value(&self) -> Option<Expression> {
        match self {
            Self::Void { .. } => None,
            Self::Value { jni_type, .. } => Some(Expression::literal(jni_type.failure_value())),
            Self::Bytes { .. } | Self::Record { .. } => {
                Some(Expression::literal(Literal::null_pointer()))
            }
            Self::CallbackHandle { .. } | Self::Closure { .. } => {
                Some(Expression::literal(Literal::integer_zero()))
            }
        }
    }
}
