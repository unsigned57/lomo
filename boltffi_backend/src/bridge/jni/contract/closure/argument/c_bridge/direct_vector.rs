//! C closure direct-vector groups turned into JVM primitive arrays.
//!
//! Direct-vector closure arguments cross the C ABI as pointer plus element
//! count. The JVM closure method receives a primitive array with a matching JNI
//! element type.
//!
//! This module validates the C group and records the direct-vector argument
//! contract shared by Rust-to-Java closure calls and Java-to-Rust closure-handle
//! calls.

use crate::{
    bridge::{c, jni::ClosureDirectVectorArgument},
    core::Result,
};

use super::ClosureCall;

pub fn from_group(
    call: ClosureCall<'_>,
    vector: &c::DirectVectorParameter,
) -> Result<ClosureDirectVectorArgument> {
    ClosureDirectVectorArgument::from_vector(
        call.parameter(vector.pointer()),
        call.parameter(vector.length()),
        vector,
    )
}
