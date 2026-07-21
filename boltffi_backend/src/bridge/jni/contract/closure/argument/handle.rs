//! Nested closure handles passed into JVM-owned closures.
//!
//! A closure argument can itself be another closure. Native code carries the
//! nested closure as call, context, and release values; the JVM closure method
//! receives one handle token with generated call and release helpers.
//!
//! This module keeps that handle token tied to the native closure pieces and the
//! registered nested signature. The template can then expose the nested closure
//! without rebuilding the closure ABI.

use crate::{
    bridge::{
        c::{Expression, Identifier, TypeFragment},
        jni::{CallbackClosureHandle, ClosureCParameter},
    },
    core::Result,
};

/// Nested closure argument passed through a JNI closure bridge.
#[derive(Clone, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub struct ClosureHandleArgument {
    handle: Identifier,
    parameter: Identifier,
    call: ClosureCParameter,
    context: ClosureCParameter,
    release: ClosureCParameter,
    handle_new: Identifier,
    handle_release: Identifier,
    call_function: Identifier,
    release_function: Identifier,
}

impl ClosureHandleArgument {
    /// Creates a nested closure argument from its C ABI triple and JNI handle contract.
    pub fn new(
        name: &str,
        call: ClosureCParameter,
        context: ClosureCParameter,
        release: ClosureCParameter,
        handle: &CallbackClosureHandle,
        call_function: Identifier,
        release_function: Identifier,
    ) -> Result<Self> {
        Ok(Self {
            handle: Identifier::parse(format!("__boltffi_{name}_handle"))?,
            parameter: Identifier::escape(name)?,
            call,
            context,
            release,
            handle_new: handle.new_function().clone(),
            handle_release: handle.release_function().clone(),
            call_function,
            release_function,
        })
    }

    /// Returns the local JVM handle variable.
    pub fn handle(&self) -> &Identifier {
        &self.handle
    }

    /// Returns the nested closure call parameter.
    pub fn call(&self) -> &Identifier {
        self.call.name()
    }

    /// Returns the nested closure context parameter.
    pub fn context(&self) -> &Identifier {
        self.context.name()
    }

    /// Returns the nested closure release parameter.
    pub fn release(&self) -> &Identifier {
        self.release.name()
    }

    /// Returns the helper that stores this nested closure behind a JNI handle.
    pub fn handle_new(&self) -> &Identifier {
        &self.handle_new
    }

    /// Returns the helper that releases the nested closure handle.
    pub fn handle_release(&self) -> &Identifier {
        &self.handle_release
    }

    /// Returns the C parameters accepted by the foreign closure call trampoline.
    pub fn c_parameters(&self) -> Vec<ClosureCParameter> {
        vec![
            self.call.clone(),
            self.context.clone(),
            self.release.clone(),
        ]
    }

    /// Returns the JNI parameters accepted by the Rust-owned closure handle entrypoint.
    pub fn handle_parameters(&self) -> Vec<ClosureCParameter> {
        vec![ClosureCParameter::new(
            self.parameter.clone(),
            TypeFragment::new("jlong"),
        )]
    }

    /// Returns the expressions passed to the static JVM closure method.
    pub fn jvm_arguments(&self) -> Vec<Expression> {
        vec![Expression::identifier(self.handle.clone())]
    }

    /// Returns the expressions passed into the Rust closure call function.
    pub fn rust_arguments(&self) -> Vec<Expression> {
        vec![
            Expression::identifier(self.call_function.clone()),
            Expression::cast(
                TypeFragment::new("void *"),
                Expression::identifier(self.parameter.clone()),
            ),
            Expression::identifier(self.release_function.clone()),
        ]
    }

    /// Returns the JNI method descriptor segment for this closure handle.
    pub const fn jni_signature(&self) -> &'static str {
        "J"
    }
}
