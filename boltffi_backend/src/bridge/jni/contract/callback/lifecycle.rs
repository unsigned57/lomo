use crate::{
    bridge::{
        c::Identifier,
        jni::{JniSymbolName, JvmClassPath},
    },
    core::Result,
};

/// JNI native methods that retain and release Rust-owned callback handles.
#[derive(Clone, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub struct CallbackHandleLifecycle {
    clone_method: Identifier,
    clone_symbol: Identifier,
    release_method: Identifier,
    release_symbol: Identifier,
}

impl CallbackHandleLifecycle {
    /// Creates the lifecycle native methods for the generated JVM class.
    pub fn new(class: &JvmClassPath) -> Result<Self> {
        let clone_method = Identifier::parse("boltffi_callback_handle_clone")?;
        let release_method = Identifier::parse("boltffi_callback_handle_release")?;
        Ok(Self {
            clone_symbol: JniSymbolName::native_method(class, clone_method.as_str())?
                .as_identifier()
                .clone(),
            release_symbol: JniSymbolName::native_method(class, release_method.as_str())?
                .as_identifier()
                .clone(),
            clone_method,
            release_method,
        })
    }

    /// Returns the native method that clones a Rust-owned callback handle.
    pub fn clone_method(&self) -> &Identifier {
        &self.clone_method
    }

    /// Returns the exported JNI symbol that clones a Rust-owned callback handle.
    pub fn clone_symbol(&self) -> &Identifier {
        &self.clone_symbol
    }

    /// Returns the native method that releases a Rust-owned callback handle.
    pub fn release_method(&self) -> &Identifier {
        &self.release_method
    }

    /// Returns the exported JNI symbol that releases a Rust-owned callback handle.
    pub fn release_symbol(&self) -> &Identifier {
        &self.release_symbol
    }
}
