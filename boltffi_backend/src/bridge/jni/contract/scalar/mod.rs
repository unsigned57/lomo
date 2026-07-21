//! Scalar values that cross JNI without byte buffers.
//!
//! Primitive numbers and booleans can travel through JNI directly, but direct
//! does not mean untyped. The bridge still needs the JNI alias, the C bridge
//! spelling, array element functions for vectors, and casts where C and JNI use
//! different names for the same width.
//!
//! Parameters and returns share that scalar vocabulary while keeping their call
//! responsibilities separate. Parameters prepare values for the C bridge.
//! Returns prepare C bridge results for Java.

mod parameter;
mod return_value;

pub use parameter::ScalarParameter;
pub use return_value::ScalarReturn;
