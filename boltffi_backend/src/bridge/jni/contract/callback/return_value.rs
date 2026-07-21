//! Callback handles returned to the JVM.
//!
//! Rust can return a callback implementation through the C bridge. The C side
//! carries that value as an owned callback handle, while Java needs an opaque
//! token whose lifetime is managed by generated retain and release methods.
//!
//! This module owns the return-side handle conversion. Native methods, callback
//! returns, and async completion payloads can all reuse the same JVM token
//! contract instead of inventing local handle wrappers.

use boltffi_binding::CallbackId;

use crate::{
    bridge::c::{self, ArgumentList, Expression, Identifier, TypeFragment},
    core::Result,
};

/// JNI callback handle returned as an owned JVM token.
#[derive(Clone, Debug, Eq, Hash, PartialEq)]
#[non_exhaustive]
pub struct CallbackReturn {
    callback: CallbackId,
}

impl CallbackReturn {
    /// Returns the JNI method return type.
    pub fn jni_type(&self) -> TypeFragment {
        TypeFragment::new("jlong")
    }

    /// Returns the C result type used by the temporary result variable.
    pub fn c_result_type(&self) -> Result<TypeFragment> {
        TypeFragment::anonymous(&c::Type::CallbackHandle(self.callback))
    }

    /// Returns the expression returned from the JNI method.
    pub fn return_expression(&self, value: Expression) -> Result<Expression> {
        Ok(Expression::call(
            Identifier::parse("boltffi_jni_callback_handle_new_owned")?,
            ArgumentList::from_iter([Expression::identifier(Identifier::parse("env")?), value]),
        ))
    }

    /// Creates a callback return from one C callback-handle ABI type.
    pub fn from_c_type(ty: &c::Type) -> Option<Self> {
        match ty {
            c::Type::CallbackHandle(callback) => Some(Self {
                callback: *callback,
            }),
            _ => None,
        }
    }
}
