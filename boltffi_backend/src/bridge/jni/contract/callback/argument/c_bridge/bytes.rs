//! C callback byte groups turned into JVM byte-array arguments.
//!
//! Rust calls a callback vtable with the ABI selected by the C bridge. Encoded
//! callback payloads arrive there as two C parameters, a pointer and a length,
//! but the generated JVM callback method should see one byte-array argument.
//!
//! This module owns that one translation. It validates that the C slot group is
//! really the borrowed-byte shape and returns the single callback argument used
//! by the JVM method contract. The payload format itself is not decoded here;
//! codec planning already happened before the C bridge contract was built.

use crate::{
    bridge::{
        c::{self, Identifier},
        jni::CallbackCParameter,
    },
    core::Result,
};

use super::super::{CallbackArgument, CallbackArgumentKind};

pub fn from_group(
    slot: &c::CallbackSlot,
    bytes: &c::ByteSliceParameter,
) -> Result<CallbackArgument> {
    Ok(CallbackArgument {
        kind: CallbackArgumentKind::Bytes {
            name: Identifier::escape(bytes.name())?,
            pointer: CallbackCParameter::from_parameter(slot.parameter(bytes.pointer()))?,
            length: CallbackCParameter::from_parameter(slot.parameter(bytes.length()))?,
        },
    })
}
