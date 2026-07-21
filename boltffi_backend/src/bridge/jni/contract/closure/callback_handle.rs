//! JVM handles for Rust-owned closures inside callbacks.
//!
//! A JVM callback method can receive a closure owned by Rust. Java cannot call
//! the native call pointer directly, so the JNI bridge exposes a handle token
//! plus generated native methods that invoke and release the underlying closure.
//!
//! This module builds those handle methods from the registered closure
//! signature. It keeps callback-carried closures on the same registration path
//! as normal inline closures.

use boltffi_binding::ClosureSignature;

use crate::{
    bridge::{
        c::{self, Identifier, Statement},
        jni::{JniSymbolName, JvmClassPath},
    },
    core::Result,
};

use super::names::ClosureNames;

/// JNI handle contract for a Rust-owned closure passed into a JVM callback.
///
/// Callback trait methods can receive inline closure parameters from Rust. The
/// JNI bridge stores the closure function pointer, context pointer, and release
/// hook behind one `jlong` so JVM code can call or release it later.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CallbackClosureHandle {
    ty: Identifier,
    new: Identifier,
    ref_: Identifier,
    release: Identifier,
    call_symbol: JniSymbolName,
    release_symbol: JniSymbolName,
    call_field: Statement,
}

impl CallbackClosureHandle {
    /// Creates the JNI handle contract for one closure signature.
    pub fn new(
        class: &JvmClassPath,
        signature: &ClosureSignature,
        call_type: &c::Type,
    ) -> Result<Self> {
        let names = ClosureNames::new(signature);
        Ok(Self {
            ty: names.handle_type()?,
            new: names.handle_new()?,
            ref_: names.handle_ref()?,
            release: names.handle_release()?,
            call_symbol: names.handle_call_symbol(class)?,
            release_symbol: names.handle_release_symbol(class)?,
            call_field: c::TypeFragment::declaration(call_type, "call")?,
        })
    }

    /// Returns the C struct type storing the closure triple.
    pub fn ty(&self) -> &Identifier {
        &self.ty
    }

    /// Returns the C helper that allocates a closure handle.
    pub fn new_function(&self) -> &Identifier {
        &self.new
    }

    /// Returns the C helper that borrows a closure handle.
    pub fn ref_function(&self) -> &Identifier {
        &self.ref_
    }

    /// Returns the C helper that releases a closure handle.
    pub fn release_function(&self) -> &Identifier {
        &self.release
    }

    /// Returns the JNI native method that invokes the closure.
    pub fn call_symbol(&self) -> &JniSymbolName {
        &self.call_symbol
    }

    /// Returns the JNI native method that releases the closure.
    pub fn release_symbol(&self) -> &JniSymbolName {
        &self.release_symbol
    }

    /// Returns the C function-pointer field declaration.
    pub fn call_field(&self) -> &Statement {
        &self.call_field
    }
}
