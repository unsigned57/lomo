/// Owns a JavaScript callback handle that must be released back to the host.
///
/// Generated `wasm32` bindings use this type when a Rust value needs to hold a
/// JavaScript callback handle beyond the current call. Dropping the owner calls
/// the matching generated free function exactly once.
pub struct WasmCallbackOwner {
    /// JavaScript callback identifier managed by the generated bindings.
    handle: u32,
    /// Generated free function that releases `handle` back to the host.
    free_fn: unsafe extern "C" fn(u32),
}

impl WasmCallbackOwner {
    /// Creates an owned JavaScript callback handle.
    ///
    /// The `handle` parameter is the callback identifier previously assigned by
    /// the generated TypeScript bindings. The `free_fn` parameter must be the
    /// matching generated release function for that callback family. Dropping
    /// the returned owner will call `free_fn(handle)` exactly once.
    #[inline]
    pub fn new(handle: u32, free_fn: unsafe extern "C" fn(u32)) -> Self {
        Self { handle, free_fn }
    }

    /// Returns the underlying JavaScript callback identifier.
    ///
    /// This does not transfer ownership. The returned identifier remains owned
    /// by `self` and will still be released on drop.
    #[inline]
    pub fn handle(&self) -> u32 {
        self.handle
    }
}

impl Drop for WasmCallbackOwner {
    fn drop(&mut self) {
        unsafe { (self.free_fn)(self.handle) }
    }
}

unsafe impl Send for WasmCallbackOwner {}
unsafe impl Sync for WasmCallbackOwner {}

/// Passes through a JavaScript callback handle created by generated TypeScript.
///
/// The generated `wasm32` bindings call this when registering a JavaScript
/// callback with Rust. The function does not allocate or transform the handle;
/// it only gives the generated Rust glue a stable entry point with C ABI.
///
/// The `js_handle` parameter is the callback identifier assigned on the
/// JavaScript side. The same value is returned unchanged so generated Rust glue
/// can treat it as a wasm callback handle.
#[unsafe(no_mangle)]
pub extern "C" fn boltffi_create_callback_handle(js_handle: u32) -> u32 {
    js_handle
}
