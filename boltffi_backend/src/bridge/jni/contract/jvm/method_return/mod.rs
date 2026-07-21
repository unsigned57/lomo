//! Return contract for static JVM method calls.
//!
//! Callback vtable slots and closure trampolines both call static JVM methods.
//! Their return values can be void, scalar, byte-array backed buffers, direct
//! records, callback handles, or closure handles. This module keeps the JNI
//! method descriptor, C return type, and failure value for that return shape in
//! one typed contract.

mod build;
mod failure;

use crate::bridge::{
    c::{Identifier, TypeFragment},
    jni::JniType,
};

/// Return contract for a static JVM method called from generated C.
#[derive(Clone, Debug, Eq, Hash, PartialEq)]
#[non_exhaustive]
pub enum JvmMethodReturn {
    /// The JVM method returns `void`.
    Void {
        /// C return type of the generated trampoline.
        c_type: TypeFragment,
    },
    /// The JVM method returns one JNI scalar value.
    Value {
        /// C return type of the generated trampoline.
        c_type: TypeFragment,
        /// JNI scalar type returned by `CallStatic*Method`.
        jni_type: JniType,
    },
    /// The JVM method returns a Java byte array copied into `FfiBuf_u8`.
    Bytes {
        /// C return type of the generated trampoline.
        c_type: TypeFragment,
    },
    /// The JVM method returns a Java byte array copied into one C record.
    Record {
        /// C return type of the generated trampoline.
        c_type: TypeFragment,
    },
    /// The JVM method returns a callback object handle.
    CallbackHandle {
        /// C return type of the generated trampoline.
        c_type: TypeFragment,
        /// C callback handle constructor.
        create_handle: Identifier,
    },
    /// The JVM method returns a closure handle and the C trampoline returns `FfiStatus`.
    Closure {
        /// C return type of the generated trampoline.
        c_type: TypeFragment,
    },
}

impl JvmMethodReturn {
    /// Returns the generated C return type.
    pub fn c_type(&self) -> &TypeFragment {
        match self {
            Self::Void { c_type }
            | Self::Value { c_type, .. }
            | Self::Bytes { c_type }
            | Self::Record { c_type }
            | Self::CallbackHandle { c_type, .. }
            | Self::Closure { c_type } => c_type,
        }
    }

    /// Returns the JNI C return type.
    pub fn jni_type(&self) -> TypeFragment {
        match self {
            Self::Void { c_type } => c_type.clone(),
            Self::Value { jni_type, .. } => jni_type.as_type_fragment(),
            Self::Bytes { .. } | Self::Record { .. } => TypeFragment::new("jbyteArray"),
            Self::CallbackHandle { .. } | Self::Closure { .. } => TypeFragment::new("jlong"),
        }
    }

    /// Returns the JNI method descriptor return segment.
    pub fn signature(&self) -> &'static str {
        match self {
            Self::Void { .. } => "V",
            Self::Value { jni_type, .. } => jni_type.signature(),
            Self::Bytes { .. } | Self::Record { .. } => "[B",
            Self::CallbackHandle { .. } | Self::Closure { .. } => "J",
        }
    }

    /// Returns whether the static JVM method returns no value.
    pub fn is_void(&self) -> bool {
        matches!(self, Self::Void { .. })
    }

    /// Returns whether the Java return value is a byte array.
    pub fn returns_byte_array(&self) -> bool {
        matches!(self, Self::Bytes { .. } | Self::Record { .. })
    }

    /// Returns whether the byte-array return value is copied into `FfiBuf_u8`.
    pub fn returns_bytes(&self) -> bool {
        matches!(self, Self::Bytes { .. })
    }

    /// Returns whether the byte-array return value is copied into a direct record.
    pub fn returns_record(&self) -> bool {
        matches!(self, Self::Record { .. })
    }

    /// Returns whether the JVM method returns a callback handle token.
    pub fn returns_callback_handle(&self) -> bool {
        matches!(self, Self::CallbackHandle { .. })
    }

    /// Returns whether the JVM method returns an inline closure handle.
    pub fn returns_closure(&self) -> bool {
        matches!(self, Self::Closure { .. })
    }

    /// Returns the C callback handle constructor for callback handle returns.
    pub fn callback_handle_constructor(&self) -> Option<&Identifier> {
        match self {
            Self::CallbackHandle { create_handle, .. } => Some(create_handle),
            Self::Void { .. }
            | Self::Value { .. }
            | Self::Bytes { .. }
            | Self::Record { .. }
            | Self::Closure { .. } => None,
        }
    }

    /// Returns the `CallStatic*Method` suffix for non-void returns.
    pub fn call_method_suffix(&self) -> Option<&'static str> {
        match self {
            Self::Void { .. } => None,
            Self::Value { jni_type, .. } => Some(jni_type.call_method_suffix()),
            Self::Bytes { .. } | Self::Record { .. } => Some("Object"),
            Self::CallbackHandle { .. } | Self::Closure { .. } => Some("Long"),
        }
    }
}
