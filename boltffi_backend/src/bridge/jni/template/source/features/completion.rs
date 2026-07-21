//! Support-fragment selection driven by async callback completions.
//!
//! Async callback completion methods can carry success payloads back to Rust.
//! When those payloads are encoded bytes or direct records, the generated source
//! needs the same byte-array and record-array support used by normal callback
//! returns.
//!
//! This module derives those requirements from completion views. It keeps async
//! completion support tied to the rendered methods rather than to a separate
//! guess about callback shapes.

use crate::bridge::jni::template::callback::CallbackCompletionInvokerView;

pub struct CompletionFeatures {
    pub uses_byte_arrays: bool,
    pub uses_record_arrays: bool,
}

impl CompletionFeatures {
    pub fn from_invokers(completions: &[CallbackCompletionInvokerView]) -> Self {
        Self {
            uses_byte_arrays: completions
                .iter()
                .any(|completion| completion.payload_bytes || completion.payload_record),
            uses_record_arrays: completions
                .iter()
                .any(|completion| completion.payload_record),
        }
    }
}
