//! C callback direct-vector groups turned into JVM primitive arrays.
//!
//! The C bridge passes a direct vector as pointer plus element count. For a JVM
//! callback method, that is one primitive array argument with a concrete JNI
//! array type. The element type and array functions come from the lower C shape,
//! not from looking back at the Rust source type.
//!
//! This module validates the pointer/count group and records the Java argument
//! that represents it. Templates later allocate and fill the array from this
//! contract.

use crate::{
    bridge::{
        c::{self, Identifier},
        jni::{CallbackCParameter, JniType},
    },
    core::Result,
};

use super::super::{CallbackArgument, CallbackArgumentKind};

pub fn from_group(
    slot: &c::CallbackSlot,
    vector: &c::DirectVectorParameter,
) -> Result<CallbackArgument> {
    Ok(CallbackArgument {
        kind: CallbackArgumentKind::DirectVector {
            array: Identifier::escape(vector.name())?,
            pointer: CallbackCParameter::from_parameter(slot.parameter(vector.pointer()))?,
            length: CallbackCParameter::from_parameter(slot.parameter(vector.length()))?,
            jni_type: JniType::from_direct_vector_element(vector.element())?,
        },
    })
}
