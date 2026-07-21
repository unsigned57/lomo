//! C callback parameter groups as JVM callback arguments.
//!
//! The C bridge groups callback slot parameters by ABI meaning before JNI sees
//! them. A byte payload is a pointer plus length, a closure is a call/context
//! pair plus release, and an async method receives a completion token. The JVM
//! callback method should not see those raw groups directly.
//!
//! This module is the boundary between those two views. Each child module owns
//! one C group shape and produces one typed `CallbackArgument`, so callback
//! method construction never has to re-learn how raw C parameters fit together.

mod bytes;
mod closure;
mod completion;
mod direct_vector;
mod success_out;
mod value;

use crate::{
    bridge::{c, jni::ClosureRegistration},
    core::{Error, Result},
};

use super::CallbackArgument;

const JNI_BRIDGE: &str = "jni";

impl CallbackArgument {
    pub(in crate::bridge::jni::contract::callback) fn from_group(
        slot: &c::CallbackSlot,
        group: &c::ParameterGroup,
        callbacks: &[c::Callback],
        closures: &[ClosureRegistration],
    ) -> Result<Self> {
        match group {
            c::ParameterGroup::Value(index) => value::from_parameter(slot.parameter(*index)),
            c::ParameterGroup::ByteSlice(slice) => bytes::from_group(slot, slice),
            c::ParameterGroup::DirectVector(vector) => direct_vector::from_group(slot, vector),
            c::ParameterGroup::DirectWriteback(_) => Err(Error::BrokenBridgeContract {
                bridge: JNI_BRIDGE,
                invariant: "callback method argument cannot be a direct-record writeback",
            }),
            c::ParameterGroup::EncodedWriteback(_) => Err(Error::BrokenBridgeContract {
                bridge: JNI_BRIDGE,
                invariant: "callback method argument cannot be an encoded writeback",
            }),
            c::ParameterGroup::SuccessOut(index) => {
                success_out::from_parameter(slot.parameter(*index))
            }
            c::ParameterGroup::CompletionStatusOut(_) => Err(Error::BrokenBridgeContract {
                bridge: JNI_BRIDGE,
                invariant: "callback method argument cannot be a status out-pointer",
            }),
            c::ParameterGroup::CallbackCompletion(completion) => {
                completion::from_group(slot, completion, callbacks)
            }
            c::ParameterGroup::Closure(closure) => closure::from_group(slot, closure, closures),
            c::ParameterGroup::ClosureReturn(_) => Err(Error::BrokenBridgeContract {
                bridge: JNI_BRIDGE,
                invariant: "callback method argument cannot be a closure return out-pointer",
            }),
            c::ParameterGroup::Continuation(_) => Err(Error::BrokenBridgeContract {
                bridge: JNI_BRIDGE,
                invariant: "callback method argument cannot be a poll continuation",
            }),
        }
    }
}
