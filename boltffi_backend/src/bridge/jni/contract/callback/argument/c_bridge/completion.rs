//! C callback completion groups for async JVM callbacks.
//!
//! An async callback method cannot return its result from the vtable call. Rust
//! gives the JVM side a completion callback and context instead. The JVM method
//! keeps that token and calls a generated completion method when the async work
//! succeeds or fails.
//!
//! This module validates the C completion group and turns it into the single JVM
//! callback argument that carries the completion token. The result payload shape
//! is kept beside the argument so later rendering knows which completion helper
//! must exist.

use crate::{
    bridge::{
        c,
        jni::{CallbackCParameter, CallbackCompletionPayload},
    },
    core::{Error, Result},
};

use super::super::{CallbackArgument, CallbackArgumentKind};

const JNI_BRIDGE: &str = "jni";

pub fn from_group(
    slot: &c::CallbackSlot,
    completion: &c::CallbackCompletionParameter,
    callbacks: &[c::Callback],
) -> Result<CallbackArgument> {
    let callback = slot.parameter(completion.callback());
    let payload = match callback.ty() {
        c::Type::FunctionPointer { params, .. } => match params.as_slice() {
            [c::Type::MutPointer(context), c::Type::Status]
                if matches!(context.as_ref(), c::Type::Void) =>
            {
                None
            }
            [c::Type::MutPointer(context), c::Type::Status, payload]
                if matches!(context.as_ref(), c::Type::Void) =>
            {
                Some(CallbackCompletionPayload::from_c_type(payload, callbacks)?)
            }
            _ => {
                return Err(Error::BrokenBridgeContract {
                    bridge: JNI_BRIDGE,
                    invariant: "callback completion function pointer has unexpected parameters",
                });
            }
        },
        _ => {
            return Err(Error::BrokenBridgeContract {
                bridge: JNI_BRIDGE,
                invariant: "callback completion parameter is not a function pointer",
            });
        }
    };
    Ok(CallbackArgument {
        kind: CallbackArgumentKind::Completion {
            callback: CallbackCParameter::from_parameter(callback)?,
            context: CallbackCParameter::from_parameter(slot.parameter(completion.context()))?,
            payload,
        },
    })
}
