//! Source fields for encoded byte arguments in closure calls.
//!
//! Rust-to-JVM closure calls carry encoded payloads as pointer plus length C
//! parameters. The JVM method receives one `jbyteArray`, so generated C needs a
//! local buffer name, the source pointer, and the byte length.
//!
//! This module prepares that byte-array view from the closure contract. Encoding
//! has already been planned by the binding IR and C bridge.

use crate::bridge::{c::Identifier, jni::ClosureBytesArgument};

pub struct ClosureBytesArgumentView {
    pub name: Identifier,
    pub pointer: Identifier,
    pub length: Identifier,
    pub buffer: Identifier,
}

impl ClosureBytesArgumentView {
    pub fn from_argument(argument: &ClosureBytesArgument) -> Self {
        Self {
            name: argument.name().clone(),
            pointer: argument.pointer().clone(),
            length: argument.length().clone(),
            buffer: argument.buffer().clone(),
        }
    }
}
