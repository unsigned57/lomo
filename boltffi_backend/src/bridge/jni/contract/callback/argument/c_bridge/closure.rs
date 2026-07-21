//! C callback closure groups turned into JVM closure handles.
//!
//! Rust can pass an inline closure while invoking a callback implemented on the
//! JVM side. The C callback slot represents that closure as the usual native
//! triple: call function, context pointer, and release function. Java should not
//! receive those three raw ABI pieces.
//!
//! This module checks that the slot group has the complete closure triple and
//! connects it to a registered closure signature. The output is one callback
//! argument that templates can expose as a JVM handle token.

use crate::{
    bridge::{
        c::{self, Identifier},
        jni::{CallbackCParameter, ClosureRegistration},
    },
    core::{Error, Result},
};

use super::super::{CallbackArgument, CallbackArgumentKind};

const JNI_BRIDGE: &str = "jni";

pub fn from_group(
    slot: &c::CallbackSlot,
    closure: &c::ClosureParameter,
    registrations: &[ClosureRegistration],
) -> Result<CallbackArgument> {
    let registration = registrations
        .iter()
        .find(|registration| registration.signature() == closure.signature())
        .ok_or(Error::BrokenBridgeContract {
            bridge: JNI_BRIDGE,
            invariant: "callback closure parameter has no JNI closure registration",
        })?;
    let handle = registration
        .callback_handle()
        .ok_or(Error::BrokenBridgeContract {
            bridge: JNI_BRIDGE,
            invariant: "callback closure parameter has no JNI closure handle",
        })?;

    Ok(CallbackArgument {
        kind: CallbackArgumentKind::Closure {
            handle: Identifier::parse(format!("__boltffi_{}_handle", closure.name()))?,
            call: CallbackCParameter::from_parameter(slot.parameter(closure.call()))?,
            context: CallbackCParameter::from_parameter(slot.parameter(closure.context()))?,
            release: CallbackCParameter::from_parameter(slot.parameter(closure.release()))?,
            handle_new: handle.new_function().clone(),
            handle_release: handle.release_function().clone(),
        },
    })
}
