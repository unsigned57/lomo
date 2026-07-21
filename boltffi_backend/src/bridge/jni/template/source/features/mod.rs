//! Support-fragment selection for the generated JNI C file.
//!
//! The root source template contains optional support blocks: exception helpers,
//! byte-array copying, direct-record copying, callback handle storage, closure
//! handle storage, continuations, stream helpers, status checks, and lifecycle
//! hooks. Emitting every block for every crate would make the generated file
//! noisy and easier to break.
//!
//! This module decides which support blocks are needed from the finished
//! template views. It deliberately scans views, not `Bindings` or `TypeRef`, so
//! feature selection follows the contract that will actually be rendered.

mod callback;
mod closure;
mod completion;
mod method;
mod source;
mod stream;

pub use source::SourceFeatures;
