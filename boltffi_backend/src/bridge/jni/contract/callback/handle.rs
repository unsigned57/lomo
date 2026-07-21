//! Callback handles passed into JVM callback methods.
//!
//! A callback method can receive another callback value from Rust. Native code
//! holds that value as a C callback handle, while Java needs an opaque token that
//! can be cloned, released, and called through generated bridge methods.
//!
//! This contract connects the original C handle parameter to the JVM token
//! created for it. Keeping both names together prevents callback templates from
//! treating callback handles like ordinary scalar values.

use crate::bridge::c::Identifier;

/// Callback-handle argument passed from Rust into a JVM callback method.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub struct CallbackHandleArgument<'argument> {
    handle: &'argument Identifier,
    parameter: &'argument Identifier,
}

impl<'argument> CallbackHandleArgument<'argument> {
    pub(in crate::bridge::jni::contract::callback) fn new(
        handle: &'argument Identifier,
        parameter: &'argument Identifier,
    ) -> Self {
        Self { handle, parameter }
    }

    /// Returns the local JVM callback-handle token.
    pub fn handle(&self) -> &Identifier {
        self.handle
    }

    /// Returns the C callback-handle parameter.
    pub fn parameter(&self) -> &Identifier {
        self.parameter
    }
}
