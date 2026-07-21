//! Callback traits implemented on the JVM side.
//!
//! A BoltFFI callback trait is a named protocol. Rust calls it through a C
//! vtable, while the JVM bridge dispatches each vtable slot to a static method
//! on a generated callback class. This module is the contract between those two
//! worlds: C slot parameters, JVM argument values, method descriptors, cached
//! method ids, return handling, and async completion hooks all live here.
//!
//! This is separate from `closure` because the ownership model is different. A
//! callback trait has a stable declaration and vtable slots. An inline closure
//! is just a signature plus call, context, and release pointers. Mixing those
//! paths would make the bridge guess who owns a handle, when a JVM global
//! reference must be retained, and which generated class should receive the
//! call.

mod argument;
mod bytes;
mod c_parameter;
mod closure;
mod completion;
mod direct_vector;
mod handle;
mod handle_method;
mod lifecycle;
mod method;
mod parameter;
mod record;
mod registration;
mod return_value;

pub use argument::CallbackArgument;
pub use bytes::CallbackBytesArgument;
pub use c_parameter::CallbackCParameter;
pub use closure::CallbackClosureArgument;
pub use completion::{
    CallbackCompletionArgument, CallbackCompletionInvoker, CallbackCompletionPayload,
    CallbackCompletionPayloadValue,
};
pub use direct_vector::CallbackDirectVectorArgument;
pub use handle::CallbackHandleArgument;
pub use handle_method::{
    CallbackHandleClosureReturn, CallbackHandleCompletion, CallbackHandleMethod,
};
pub use lifecycle::CallbackHandleLifecycle;
pub use method::{CallbackClosureReturn, CallbackMethod};
pub use parameter::CallbackParameter;
pub use record::CallbackRecordArgument;
pub use registration::CallbackRegistration;
pub use return_value::CallbackReturn;
