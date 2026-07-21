//! Callback method arguments passed from Rust into the JVM.
//!
//! A Rust callback invocation reaches this bridge as a C vtable call. The slot
//! parameters are ABI-shaped, not Java-shaped: some are single values, some are
//! pointer/length pairs, and some are grouped handle protocols. The generated
//! JVM method needs a cleaner list of Java values.
//!
//! This module owns that argument contract. It records the Java-visible shape of
//! each callback argument and keeps the original C pieces close enough for the
//! C callback template to build local arrays, handles, and completion tokens.

mod c_bridge;
mod jvm;
mod jvm_setup;

use crate::bridge::{
    c::Identifier,
    jni::{CallbackCParameter, CallbackCompletionPayload, JniType, SuccessOutArgument},
};

/// One callback vtable argument forwarded to a JVM callback method.
#[derive(Clone, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub struct CallbackArgument {
    kind: CallbackArgumentKind,
}

#[derive(Clone, Debug, Eq, PartialEq)]
enum CallbackArgumentKind {
    Value {
        parameter: CallbackCParameter,
        jni_type: JniType,
    },
    SuccessOut {
        parameter: CallbackCParameter,
        argument: SuccessOutArgument,
    },
    Bytes {
        name: Identifier,
        pointer: CallbackCParameter,
        length: CallbackCParameter,
    },
    DirectVector {
        array: Identifier,
        pointer: CallbackCParameter,
        length: CallbackCParameter,
        jni_type: JniType,
    },
    Record {
        array: Identifier,
        parameter: CallbackCParameter,
    },
    CallbackHandle {
        handle: Identifier,
        parameter: CallbackCParameter,
    },
    Closure {
        handle: Identifier,
        call: CallbackCParameter,
        context: CallbackCParameter,
        release: CallbackCParameter,
        handle_new: Identifier,
        handle_release: Identifier,
    },
    Completion {
        callback: CallbackCParameter,
        context: CallbackCParameter,
        payload: Option<CallbackCompletionPayload>,
    },
}
