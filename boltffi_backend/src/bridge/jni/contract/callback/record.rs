//! Direct records passed into JVM callback methods.
//!
//! Rust callback methods can pass direct records by value through the C callback
//! slot. Java cannot receive that C record layout directly, so the JNI bridge
//! copies the record bytes into a `jbyteArray` before invoking the JVM method.
//!
//! This contract names the C record value and the Java byte-array argument that
//! represents it. It keeps record callback handling aligned with direct-record
//! native method returns without making templates inspect record layouts.

use crate::bridge::c::Identifier;

/// Direct-record argument passed from Rust into a JVM callback method.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub struct CallbackRecordArgument<'argument> {
    array: &'argument Identifier,
    parameter: &'argument Identifier,
}

impl<'argument> CallbackRecordArgument<'argument> {
    pub(in crate::bridge::jni::contract::callback) fn new(
        array: &'argument Identifier,
        parameter: &'argument Identifier,
    ) -> Self {
        Self { array, parameter }
    }

    /// Returns the local JNI byte-array variable.
    pub fn array(&self) -> &Identifier {
        self.array
    }

    /// Returns the C record parameter.
    pub fn parameter(&self) -> &Identifier {
        self.parameter
    }
}
