//! Direct-record JNI contract.
//!
//! A direct record is neither a scalar nor an encoded byte buffer. Java sees a
//! fixed-size byte array, while the C bridge sees the concrete record ABI type.
//! The JNI bridge has to copy between those shapes and sometimes copy the
//! mutated value back.
//!
//! This module keeps those responsibilities split into value layout, method
//! parameters, and mutation writeback. Other bridge paths can reuse the same
//! direct-record facts without reinterpreting record fields.

mod parameter;
mod value;
mod writeback;

pub use parameter::RecordParameter;
pub use value::RecordValue;
pub use writeback::RecordWriteback;
