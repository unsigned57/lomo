#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct FfiSpan {
    pub ptr: *const u8,
    pub len: usize,
}

impl FfiSpan {
    pub const fn empty() -> Self {
        Self {
            ptr: core::ptr::null(),
            len: 0,
        }
    }

    pub unsafe fn as_bytes(&self) -> &[u8] {
        if self.ptr.is_null() || self.len == 0 {
            &[]
        } else {
            unsafe { core::slice::from_raw_parts(self.ptr, self.len) }
        }
    }
}

impl Default for FfiSpan {
    fn default() -> Self {
        Self::empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_span() {
        let span = FfiSpan::empty();
        assert!(span.ptr.is_null());
        assert_eq!(span.len, 0);
        assert_eq!(unsafe { span.as_bytes() }, &[]);
    }

    #[test]
    fn span_from_slice() {
        let data = [1u8, 2, 3, 4, 5];
        let span = FfiSpan {
            ptr: data.as_ptr(),
            len: data.len(),
        };
        assert_eq!(unsafe { span.as_bytes() }, &data);
    }
}
