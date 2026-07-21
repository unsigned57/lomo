//! Defines the FFI input and output types used for Rust values.
//!
//! `Passable` tells generated wrappers what they receive from foreign code and
//! what they return back across the boundary.
//!
//! Direct scalar values such as `i32` and `bool` use themselves as both input
//! and output.
//!
//! `String` uses `FfiSpan` on input and `FfiBuf` on output.
//!
//! Structured values that implement the wire traits also use `FfiSpan` and
//! `FfiBuf`, then reconstruct the Rust value from those bytes.

mod sequence;
mod value;
mod wire;

pub use sequence::VecTransport;
pub use value::Passable;
pub use wire::WirePassable;
