//! Async callback completion contract.
//!
//! A JVM callback method marked async cannot produce its Rust-visible result
//! synchronously. Rust gives the JVM method a completion token, and the JVM
//! calls back into native code later with either a success payload or failure
//! information.
//!
//! This module keeps that protocol in one place: the callback argument that
//! carries the token, the payload shape accepted on success, and the native
//! invokers shared by callback methods with the same completion ABI.

mod argument;
mod invoker;
mod payload;

pub use argument::CallbackCompletionArgument;
pub use invoker::CallbackCompletionInvoker;
pub use payload::{CallbackCompletionPayload, CallbackCompletionPayloadValue};
