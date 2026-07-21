//! Borrowed bytes passed into JVM callback methods.
//!
//! Rust callback payloads that are encoded by the C bridge arrive as borrowed
//! bytes. The JVM callback method receives those bytes as a new `jbyteArray`,
//! because Java cannot safely observe the raw native pointer.
//!
//! This contract keeps the Java argument name beside the original C pointer and
//! length parameters. The template uses it to allocate the byte array and clean
//! up local references without re-reading the callback slot shape.

use crate::bridge::c::Identifier;

/// Borrowed byte-array argument passed from Rust into a JVM callback method.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub struct CallbackBytesArgument<'argument> {
    name: &'argument Identifier,
    pointer: &'argument Identifier,
    length: &'argument Identifier,
}

impl<'argument> CallbackBytesArgument<'argument> {
    pub(in crate::bridge::jni::contract::callback) fn new(
        name: &'argument Identifier,
        pointer: &'argument Identifier,
        length: &'argument Identifier,
    ) -> Self {
        Self {
            name,
            pointer,
            length,
        }
    }

    /// Returns the local JNI byte-array variable.
    pub fn name(&self) -> &Identifier {
        self.name
    }

    /// Returns the C byte pointer parameter.
    pub fn pointer(&self) -> &Identifier {
        self.pointer
    }

    /// Returns the C byte length parameter.
    pub fn length(&self) -> &Identifier {
        self.length
    }
}
