//! Root template input for the generated JNI C source file.
//!
//! The JNI bridge emits one C file. That file is assembled from a root Askama
//! template plus focused fragments for lifecycle hooks, native methods,
//! callbacks, closures, streams, continuations, byte arrays, direct records, and
//! handle storage.
//!
//! This module builds the root template input from the finished JNI contract. It
//! decides which source fragments are present in the file, not which ABI shapes
//! are supported. By the time this module runs, every method, callback, closure,
//! stream, and return has already been selected and validated by the contract
//! layer.

mod features;
mod file;
mod success_out;

pub use file::SourceFile;
pub use success_out::SuccessOutWriterView;
