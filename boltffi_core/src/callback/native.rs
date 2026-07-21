use std::ffi::c_void;

pub struct NativeCallbackOwner {
    context: *mut c_void,
    release: unsafe extern "C" fn(*mut c_void),
}

impl NativeCallbackOwner {
    #[inline]
    pub fn new(context: *mut c_void, release: unsafe extern "C" fn(*mut c_void)) -> Self {
        Self { context, release }
    }

    #[inline]
    pub fn context(&self) -> *mut c_void {
        self.context
    }
}

impl Drop for NativeCallbackOwner {
    fn drop(&mut self) {
        unsafe { (self.release)(self.context) }
    }
}

unsafe impl Send for NativeCallbackOwner {}
unsafe impl Sync for NativeCallbackOwner {}
