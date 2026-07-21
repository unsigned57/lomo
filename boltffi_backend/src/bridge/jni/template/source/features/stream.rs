//! Support-fragment selection driven by stream protocols.
//!
//! Most stream operations render as ordinary native methods. Direct batch
//! helpers are different because they allocate a native item buffer, ask the C
//! bridge to fill it, and copy the used bytes into a Java array.
//!
//! This module records whether any rendered stream uses that direct-batch path,
//! so the root template includes the extra source fragment only when needed.

use crate::bridge::jni::template::stream::DirectStreamBatchView;

pub struct StreamFeatures {
    pub returns_direct_batches: bool,
}

impl StreamFeatures {
    pub fn from_direct_batches(direct_batches: &[DirectStreamBatchView]) -> Self {
        Self {
            returns_direct_batches: !direct_batches.is_empty(),
        }
    }
}
