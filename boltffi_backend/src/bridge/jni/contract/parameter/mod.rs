//! Parameters accepted by generated `Java_*` native methods.
//!
//! JNI receives Java values, but the lower C bridge expects ABI groups. One Java
//! argument can therefore become several C arguments: a byte array becomes
//! pointer and length, a direct vector becomes pointer and element count, and an
//! inline closure becomes call, context, and release values.
//!
//! This module owns that grouping for native methods. It stores the
//! Java-facing parameter beside the C arguments produced from it, so method
//! rendering only forwards a prepared contract. It does not need to inspect the
//! original `ParamPlan` or reconstruct C parameter groups.

mod build;
mod native;

pub use native::{NativeParameter, NativeParameterKind};
