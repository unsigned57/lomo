//! Source fields for closures returned by JVM callback methods.
//!
//! When a callback method returns an inline closure, Rust expects C output
//! storage containing the closure call pointer, context, and release pointer.
//! The JVM side returns a handle token, so generated C must store the handle and
//! write the native closure fields back to the C callback slot.
//!
//! This module prepares that writeback view. The callback contract owns the
//! closure return shape; the template only receives the identifiers it must
//! print.

use crate::bridge::{
    c::{Identifier, Statement},
    jni::CallbackClosureReturn,
};

pub struct CallbackClosureReturnView {
    pub output: Identifier,
    pub storage: Identifier,
    pub invoke_field: Statement,
    pub invoke: Identifier,
    pub release: Identifier,
}

impl CallbackClosureReturnView {
    pub fn from_return(returned: &CallbackClosureReturn) -> Self {
        Self {
            output: returned.output().name().clone(),
            storage: returned.storage().clone(),
            invoke_field: returned.invoke_field().clone(),
            invoke: returned.invoke().clone(),
            release: returned.release().clone(),
        }
    }
}
