//! Callback vtable method contract.
//!
//! Each callback trait method becomes one C vtable slot that Rust can call. The
//! JNI bridge forwards that slot to a static JVM method, so the contract must
//! carry both sides of the call: the C slot signature and the JVM method
//! descriptor, arguments, cached method id, return path, and completion helpers.
//!
//! Keeping the method contract here prevents callback templates from rebuilding
//! slot layout or guessing how fallible, async, closure-returning, and
//! byte-buffer-returning methods should behave.

mod closure_return;
mod contract;

pub use closure_return::CallbackClosureReturn;
pub use contract::CallbackMethod;
