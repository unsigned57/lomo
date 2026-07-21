use core::mem::MaybeUninit;

#[repr(C)]
pub struct FfiOption<T> {
    is_some: bool,
    value: MaybeUninit<T>,
}

impl<T> FfiOption<T> {
    pub fn some(value: T) -> Self {
        Self {
            is_some: true,
            value: MaybeUninit::new(value),
        }
    }

    pub fn none() -> Self {
        Self {
            is_some: false,
            value: MaybeUninit::uninit(),
        }
    }

    pub fn is_some(&self) -> bool {
        self.is_some
    }

    pub fn is_none(&self) -> bool {
        !self.is_some
    }

    pub fn into_option(self) -> Option<T> {
        if self.is_some {
            let value = unsafe { self.value.assume_init_read() };
            core::mem::forget(self);
            Some(value)
        } else {
            None
        }
    }
}

impl<T> Drop for FfiOption<T> {
    fn drop(&mut self) {
        if self.is_some {
            unsafe { self.value.assume_init_drop() };
        }
    }
}

impl<T> From<Option<T>> for FfiOption<T> {
    fn from(opt: Option<T>) -> Self {
        match opt {
            Some(v) => Self::some(v),
            None => Self::none(),
        }
    }
}

impl<T> From<FfiOption<T>> for Option<T> {
    fn from(opt: FfiOption<T>) -> Self {
        opt.into_option()
    }
}

impl<T> Default for FfiOption<T> {
    fn default() -> Self {
        Self::none()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn option_some_primitive() {
        let opt = FfiOption::some(42u32);
        assert!(opt.is_some());
        assert_eq!(opt.into_option(), Some(42));
    }

    #[test]
    fn option_none_primitive() {
        let opt: FfiOption<u32> = FfiOption::none();
        assert!(opt.is_none());
        assert_eq!(opt.into_option(), None);
    }

    #[test]
    fn option_some_string() {
        let opt = FfiOption::some(String::from("hello"));
        assert!(opt.is_some());
        assert_eq!(opt.into_option(), Some(String::from("hello")));
    }

    #[test]
    fn option_none_string() {
        let opt: FfiOption<String> = FfiOption::none();
        assert!(opt.is_none());
        assert_eq!(opt.into_option(), None);
    }

    #[test]
    fn option_from_some() {
        let opt: FfiOption<String> = Some(String::from("test")).into();
        assert!(opt.is_some());
        assert_eq!(opt.into_option(), Some(String::from("test")));
    }

    #[test]
    fn option_from_none() {
        let opt: FfiOption<String> = None.into();
        assert!(opt.is_none());
    }

    #[test]
    fn option_drop_on_error_path() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        static DROP_COUNT: AtomicUsize = AtomicUsize::new(0);

        struct DropCounter;
        impl Drop for DropCounter {
            fn drop(&mut self) {
                DROP_COUNT.fetch_add(1, Ordering::SeqCst);
            }
        }

        DROP_COUNT.store(0, Ordering::SeqCst);
        {
            let _opt = FfiOption::some(DropCounter);
        }
        assert_eq!(DROP_COUNT.load(Ordering::SeqCst), 1);

        DROP_COUNT.store(0, Ordering::SeqCst);
        {
            let _opt: FfiOption<DropCounter> = FfiOption::none();
        }
        assert_eq!(DROP_COUNT.load(Ordering::SeqCst), 0);
    }
}
