//! Encoded arguments passed into JVM-owned closures.
//!
//! Rust calls a JVM-owned closure through a generated C trampoline. Encoded
//! arguments reach that trampoline as pointer plus length, while the JVM closure
//! method expects one byte-array argument.
//!
//! This module keeps the byte-array argument and its backing C parameters
//! together. It does not interpret the encoded payload; the closure registration
//! already came from the binding and C bridge plans.

use crate::{
    bridge::c::{self, Expression, Identifier, TypeFragment},
    core::Result,
};

use super::ClosureCParameter;

/// Encoded inline-closure argument crossing the JNI bridge as a Java byte array.
#[derive(Clone, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub struct ClosureBytesArgument {
    name: Identifier,
    pointer: ClosureCParameter,
    length: ClosureCParameter,
    buffer: Identifier,
}

impl ClosureBytesArgument {
    pub(in crate::bridge::jni::contract::closure) fn from_bytes(
        pointer: &c::Parameter,
        length: &c::Parameter,
        bytes: &c::ByteSliceParameter,
    ) -> Result<Self> {
        let name = Identifier::escape(bytes.name())?;
        Ok(Self {
            buffer: Identifier::parse(format!("{name}_buffer"))?,
            name,
            pointer: ClosureCParameter::from_parameter(pointer)?,
            length: ClosureCParameter::from_parameter(length)?,
        })
    }

    /// Returns the Java byte-array local variable name.
    pub fn name(&self) -> &Identifier {
        &self.name
    }

    /// Returns the byte pointer parameter name.
    pub fn pointer(&self) -> &Identifier {
        self.pointer.name()
    }

    /// Returns the byte length parameter name.
    pub fn length(&self) -> &Identifier {
        self.length.name()
    }

    /// Returns the owned buffer local variable name.
    pub fn buffer(&self) -> &Identifier {
        &self.buffer
    }

    /// Returns the C parameters accepted by the closure call trampoline.
    pub fn c_parameters(&self) -> Vec<ClosureCParameter> {
        vec![self.pointer.clone(), self.length.clone()]
    }

    /// Returns the C parameters accepted by the Rust-owned closure handle entrypoint.
    pub fn handle_parameters(&self) -> Vec<ClosureCParameter> {
        vec![ClosureCParameter::new(
            self.name.clone(),
            TypeFragment::new("jbyteArray"),
        )]
    }

    /// Returns the expressions passed to the static JVM closure method.
    pub fn jvm_arguments(&self) -> Vec<Expression> {
        vec![Expression::identifier(self.name.clone())]
    }

    /// Returns the expressions passed into the Rust closure call function.
    pub fn rust_arguments(&self) -> Vec<Expression> {
        vec![
            Expression::new(format!("{}.ptr", self.buffer)),
            Expression::new(format!("{}.len", self.buffer)),
        ]
    }

    /// Returns the JNI method descriptor segment for this argument.
    pub const fn jni_signature(&self) -> &'static str {
        "[B"
    }
}
