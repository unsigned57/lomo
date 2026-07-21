use std::ffi::c_void;

/// Raw callback handle exchanged between generated bindings and Rust.
///
/// On native targets the handle carries both an opaque handle value and a
/// vtable pointer used to dispatch callback methods. On `wasm32` the generated
/// bindings use only the numeric handle and the vtable pointer is always null.
///
/// This type does not own the callback by itself. Ownership is recovered
/// through [`crate::callback::ArcFromCallbackHandle`] or
/// [`crate::callback::BoxFromCallbackHandle`], depending on the callback mode
/// the generated wrapper expects.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct CallbackHandle {
    /// Opaque callback identity carried across the boundary.
    ///
    /// On native targets this is usually a boxed or reference-counted pointer
    /// converted to an integer-sized token. On `wasm32` it is the numeric
    /// callback identifier managed by the generated JavaScript bindings.
    handle: u64,
    /// Raw dispatch table pointer used for native callback method calls.
    ///
    /// This is null on `wasm32`, where callback dispatch goes through imported
    /// JavaScript functions instead of a native vtable.
    vtable: *const c_void,
}

unsafe impl Send for CallbackHandle {}
unsafe impl Sync for CallbackHandle {}

impl CallbackHandle {
    /// Null callback handle used to represent the absence of a callback.
    pub const NULL: Self = Self {
        handle: 0,
        vtable: std::ptr::null(),
    };

    /// Creates a callback handle from its raw parts.
    ///
    /// This is mainly used by generated wrapper code that already owns a valid
    /// callback handle and matching dispatch table.
    ///
    /// The `handle` argument is the opaque callback identity that later
    /// callback calls will pass back into Rust. The `vtable` argument must
    /// point at the matching native dispatch table for that same callback
    /// identity. On `wasm32`, callers pass a null `vtable`.
    #[inline]
    pub const fn new(handle: u64, vtable: *const c_void) -> Self {
        Self { handle, vtable }
    }

    /// Returns the opaque callback identity carried by this handle.
    ///
    /// Generated bindings pass this value back into callback entry points when
    /// they need to invoke, clone, or release the callback.
    #[inline]
    pub fn handle(&self) -> u64 {
        self.handle
    }

    /// Returns the raw dispatch table pointer stored in this handle.
    ///
    /// Native callback wrappers use this pointer to find the generated method
    /// trampolines for the callback. On `wasm32` this always returns null.
    #[inline]
    pub fn vtable(&self) -> *const c_void {
        self.vtable
    }

    /// Reports whether this handle represents the absence of a callback.
    ///
    /// Native targets require both a non-zero handle and a non-null vtable.
    /// `wasm32` uses the numeric handle alone because callback dispatch is
    /// routed through imported JavaScript functions instead of a native vtable.
    #[inline]
    pub fn is_null(&self) -> bool {
        #[cfg(target_arch = "wasm32")]
        {
            return self.handle == 0;
        }

        #[cfg(not(target_arch = "wasm32"))]
        {
            self.handle == 0 || self.vtable.is_null()
        }
    }

    #[cfg(target_arch = "wasm32")]
    /// Creates a callback handle from a JavaScript callback identifier.
    ///
    /// The `handle` parameter is the identifier assigned by the generated
    /// TypeScript bindings. The returned [`CallbackHandle`] carries that
    /// identifier and a null vtable because wasm callback dispatch is resolved
    /// through imports instead of a native dispatch table.
    #[inline]
    pub fn from_wasm_handle(handle: u32) -> Self {
        Self {
            handle: handle as u64,
            vtable: std::ptr::null(),
        }
    }
}

impl Default for CallbackHandle {
    fn default() -> Self {
        Self::NULL
    }
}

impl std::fmt::Debug for CallbackHandle {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("CallbackHandle")
            .field("handle", &self.handle)
            .field("vtable", &self.vtable)
            .finish()
    }
}

#[cfg(test)]
mod tests {
    use super::CallbackHandle;

    #[cfg(not(target_arch = "wasm32"))]
    #[test]
    fn native_handle_with_null_vtable_is_null() {
        let handle = CallbackHandle::new(7, std::ptr::null());
        assert!(handle.is_null());
    }

    #[cfg(target_arch = "wasm32")]
    #[test]
    fn wasm_handle_is_null_only_when_handle_is_zero() {
        let handle = CallbackHandle::from_wasm_handle(7);
        assert!(!handle.is_null());
        assert!(CallbackHandle::from_wasm_handle(0).is_null());
    }
}
