//! Source fields for nested closure handles.
//!
//! A closure argument can itself be another inline closure. The generated C
//! cannot pass raw call, context, and release pointers to Java directly, so it
//! wraps them in a JVM-visible handle token with native call and release helper
//! methods.
//!
//! This module prepares those handle fields from the closure contract. Ownership
//! remains in the contract; this view only gives the templates the names they
//! need to allocate and release the handle.

use crate::bridge::{c::Identifier, jni::ClosureHandleArgument};

pub struct ClosureHandleArgumentView {
    pub handle: Identifier,
    pub call: Identifier,
    pub context: Identifier,
    pub release: Identifier,
    pub handle_new: Identifier,
    pub handle_release: Identifier,
}

impl ClosureHandleArgumentView {
    pub fn from_argument(argument: &ClosureHandleArgument) -> Self {
        Self {
            handle: argument.handle().clone(),
            call: argument.call().clone(),
            context: argument.context().clone(),
            release: argument.release().clone(),
            handle_new: argument.handle_new().clone(),
            handle_release: argument.handle_release().clone(),
        }
    }
}
