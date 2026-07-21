//! Single-value C closure parameters as JVM scalar arguments.
//!
//! Plain closure arguments are not grouped protocols. They are one C parameter
//! with a scalar type that can be passed to the JVM method after the right JNI
//! cast.
//!
//! This module owns that scalar mapping for closure calls. It keeps the C
//! parameter and JNI primitive type together instead of making templates inspect
//! C types directly.

use crate::{bridge::c, core::Result};

use super::super::ClosureScalarArgument;
use super::ClosureCall;

pub fn from_index(
    call: ClosureCall<'_>,
    index: c::ParameterIndex,
) -> Result<ClosureScalarArgument> {
    ClosureScalarArgument::from_parameter(call.parameter(index))
}
