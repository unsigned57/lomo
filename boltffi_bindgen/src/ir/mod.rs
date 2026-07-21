//! Intermediate representation for FFI code generation.
//!
//! The IR pipeline converts parsed Rust API definitions into target-language source code
//! in two stages:
//!
//! 1. [`build_contract`] scans a `Module` and produces an [`FfiContract`] containing
//!    every record, enum, class, callback, and function the crate exports.
//!
//! 2. [`Lowerer`] takes that contract and produces an [`AbiContract`] where every type
//!    has been resolved to its wire layout and every function has concrete parameter
//!    strategies and read/write operation sequences.
//!
//! Backends in [`render`](crate::render) consume the [`AbiContract`] and emit source code.
//! They never see [`CodecPlan`], which stays internal to the lowering step.

pub mod build;
pub mod codec;
pub mod contract;
pub mod definitions;
pub mod ids;
pub mod lower;
pub mod ops;
pub mod plan;
pub mod types;
pub mod validate;

pub use build::build_contract;
pub use codec::*;
pub use contract::*;
pub use definitions::*;
pub use ids::*;
pub use lower::Lowerer;
pub use ops::*;
pub use plan::*;
pub use types::*;
pub use validate::{ValidationError, validate_contract};
pub mod abi;
pub use abi::*;
