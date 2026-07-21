//! C closure groups for nested closure arguments.
//!
//! A closure call can carry another closure. In the C ABI that nested closure is
//! the native call/context/release group. In the JVM call it is one handle token
//! tied to another registered closure signature.
//!
//! This module validates the native group, resolves the nested signature, and
//! builds the JVM handle argument used by the closure trampoline.

use crate::{
    bridge::{
        c,
        jni::{CallbackClosureHandle, ClosureCParameter, ClosureHandleArgument, JvmClassPath},
    },
    core::Result,
};

use super::super::super::names::ClosureNames;
use super::ClosureCall;

pub fn from_group(
    class: &JvmClassPath,
    call: ClosureCall<'_>,
    nested: &c::ClosureParameter,
) -> Result<ClosureHandleArgument> {
    let names = ClosureNames::new(nested.signature());
    let handle = CallbackClosureHandle::new(
        class,
        nested.signature(),
        call.parameter(nested.call()).ty(),
    )?;
    ClosureHandleArgument::new(
        nested.name(),
        ClosureCParameter::from_parameter(call.parameter(nested.call()))?,
        ClosureCParameter::from_parameter(call.parameter(nested.context()))?,
        ClosureCParameter::from_parameter(call.parameter(nested.release()))?,
        &handle,
        names.call()?,
        names.release()?,
    )
}
