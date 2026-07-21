//! Stream protocols exposed through JNI.
//!
//! A stream is a protocol, not a single function. The C bridge exposes start,
//! poll, wait, batch, unsubscribe, and free operations with shared handle
//! ownership rules. The JVM side needs those operations grouped under one
//! generated stream API so callers cannot mix symbols from different streams.
//!
//! This module owns the JNI view of that protocol. It names the native methods,
//! keeps their handle parameters consistent, and adds a direct-batch method when
//! the stream item layout can be copied as one Java byte array.

mod direct_batch;
mod protocol;

pub use direct_batch::DirectStreamBatchMethod;
pub use protocol::StreamProtocolMethods;
