//! C closure byte groups turned into JVM byte-array arguments.
//!
//! A closure call carries encoded arguments through the C bridge as borrowed
//! bytes. The generated JVM closure method should receive one byte array, not a
//! native pointer and length pair.
//!
//! This module validates that group and builds the `ClosureBytesArgument` used
//! by both the C trampoline signature and the Java call expression.

use crate::{
    bridge::{c, jni::ClosureBytesArgument},
    core::Result,
};

use super::ClosureCall;

pub fn from_group(
    call: ClosureCall<'_>,
    bytes: &c::ByteSliceParameter,
) -> Result<ClosureBytesArgument> {
    ClosureBytesArgument::from_bytes(
        call.parameter(bytes.pointer()),
        call.parameter(bytes.length()),
        bytes,
    )
}
