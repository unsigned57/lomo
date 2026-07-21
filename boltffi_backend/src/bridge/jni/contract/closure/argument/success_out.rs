//! Success out-pointers passed into JVM-owned closures.
//!
//! A fallible closure returns the encoded error payload from the JVM method. If
//! it succeeds with a value, the value is written through a C out-pointer. This
//! argument keeps that pointer in the closure registration instead of treating
//! it as an ordinary scalar.

use crate::{
    bridge::{
        c::{self, Expression},
        jni::{ClosureCParameter, SuccessOutArgument},
    },
    core::Result,
};

/// Hidden success out-pointer argument for a fallible inline closure.
#[derive(Clone, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub struct ClosureSuccessOutArgument {
    parameter: ClosureCParameter,
    argument: SuccessOutArgument,
}

impl ClosureSuccessOutArgument {
    /// Creates a closure success out argument from its C out-pointer parameter.
    pub fn from_parameter(parameter: &c::Parameter) -> Result<Self> {
        Ok(Self {
            parameter: ClosureCParameter::from_parameter(parameter)?,
            argument: SuccessOutArgument::from_parameter(parameter)?,
        })
    }

    /// Returns the JVM-visible success out argument.
    pub fn argument(&self) -> &SuccessOutArgument {
        &self.argument
    }

    /// Returns the C parameters accepted by the closure call trampoline.
    pub fn c_parameters(&self) -> Vec<ClosureCParameter> {
        vec![self.parameter.clone()]
    }

    /// Returns the C parameters accepted by the Rust-owned closure handle entrypoint.
    pub fn handle_parameters(&self) -> Vec<ClosureCParameter> {
        vec![ClosureCParameter::new(
            self.parameter.name().clone(),
            self.argument.jni_type().as_type_fragment(),
        )]
    }

    /// Returns the expressions passed to the static JVM closure method.
    pub fn jvm_arguments(&self) -> Vec<Expression> {
        vec![Expression::cast(
            self.argument.jni_type().as_type_fragment(),
            Expression::identifier(self.parameter.name().clone()),
        )]
    }

    /// Returns the expressions passed into the Rust closure call function.
    pub fn rust_arguments(&self) -> Vec<Expression> {
        vec![Expression::cast(
            self.parameter.ty().clone(),
            Expression::identifier(self.parameter.name().clone()),
        )]
    }

    /// Returns the JNI method descriptor segment for this argument.
    pub fn jni_signature(&self) -> &'static str {
        self.argument.jni_type().signature()
    }
}
