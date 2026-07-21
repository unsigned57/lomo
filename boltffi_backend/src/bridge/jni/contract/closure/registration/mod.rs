//! Registered JNI closure signatures.
//!
//! Inline closures are identified by signature, not by the declaration where
//! they appear. The same signature should produce one JVM bridge class, one call
//! trampoline, and one release trampoline across the generated source file.
//!
//! This module exposes that deduplicated registration contract to the rest of
//! the JNI bridge. Construction lives under `contract` so discovery and storage
//! stay together.

mod contract;

pub use contract::ClosureRegistration;
