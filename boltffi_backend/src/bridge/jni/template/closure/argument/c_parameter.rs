//! Source fields for closure trampoline C parameters.
//!
//! Rust calls a JVM-owned closure through a generated C function pointer. That
//! trampoline signature must match the C bridge closure call exactly, including
//! grouped parameters for encoded values, direct vectors, and nested closures.
//!
//! This module carries the already-validated C declarations into the template.
//! It is intentionally narrow: signature printing belongs here, argument setup
//! belongs in the sibling argument modules.

use crate::bridge::{c::Statement, jni::ClosureCParameter};

pub struct ClosureCParameterView {
    pub declaration: Statement,
}

impl ClosureCParameterView {
    pub fn from_parameter(parameter: ClosureCParameter) -> Self {
        Self {
            declaration: parameter.declaration().clone(),
        }
    }
}
