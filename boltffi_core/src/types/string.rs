use core::mem::ManuallyDrop;

#[repr(C)]
pub struct FfiString {
    ptr: *mut u8,
    len: usize,
    cap: usize,
}

impl Default for FfiString {
    fn default() -> Self {
        Self {
            ptr: core::ptr::null_mut(),
            len: 0,
            cap: 0,
        }
    }
}

impl FfiString {
    pub fn from_string(string: String) -> Self {
        let bytes = string.into_bytes();
        let mut bytes = ManuallyDrop::new(bytes);
        Self {
            ptr: bytes.as_mut_ptr(),
            len: bytes.len(),
            cap: bytes.capacity(),
        }
    }

    pub fn into_string(self) -> Option<String> {
        if self.ptr.is_null() {
            return None;
        }
        let bytes = unsafe { Vec::from_raw_parts(self.ptr, self.len, self.cap) };
        core::mem::forget(self);
        String::from_utf8(bytes).ok()
    }

    pub fn as_str(&self) -> Option<&str> {
        if self.ptr.is_null() || self.len == 0 {
            return None;
        }
        let bytes = unsafe { core::slice::from_raw_parts(self.ptr, self.len) };
        core::str::from_utf8(bytes).ok()
    }

    pub fn len(&self) -> usize {
        self.len
    }

    pub fn is_empty(&self) -> bool {
        self.len == 0
    }
}

impl Drop for FfiString {
    fn drop(&mut self) {
        if !self.ptr.is_null() && self.cap > 0 {
            unsafe {
                let _ = Vec::from_raw_parts(self.ptr, self.len, self.cap);
            }
        }
    }
}

impl From<String> for FfiString {
    fn from(string: String) -> Self {
        Self::from_string(string)
    }
}

impl From<&str> for FfiString {
    fn from(string: &str) -> Self {
        Self::from_string(string.to_owned())
    }
}
