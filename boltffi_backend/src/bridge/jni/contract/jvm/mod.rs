//! Return contracts for static JVM methods called from generated C.
//!
//! Callback vtable slots and inline closure trampolines both call static JVM
//! methods. After the call, the generated C must translate the Java result back
//! into the C ABI that Rust expects: scalar value, byte buffer, direct record,
//! callback handle, closure handle, status, or no value.
//!
//! This module owns that return-side contract for JVM method calls. It is shared
//! by callbacks and closures so both paths use the same descriptor and failure
//! behavior.

mod method_return;

pub use method_return::JvmMethodReturn;
