use std::ops::{Deref, DerefMut};
use std::ptr::NonNull;

/// Owns a heap value behind a stable raw handle pointer.
///
/// This is the core handle type used by generated FFI glue for Rust-owned
/// values. It keeps `Box<T>` ownership on the Rust side while exposing a raw
/// pointer that foreign code can pass back into later calls.
///
/// `HandleBox<T>` does not add reference counting or interior synchronization.
/// It is a direct owning wrapper over one heap allocation.
pub struct HandleBox<T> {
    /// Non-null pointer to the heap allocation owned by this handle.
    ///
    /// The pointer always comes from `Box<T>` ownership transfer and remains
    /// valid for the lifetime of the handle unless ownership is moved out with
    /// [`HandleBox::into_raw`] or [`HandleBox::into_non_null`].
    ptr: NonNull<T>,
}

impl<T> HandleBox<T> {
    /// Allocates `value` on the heap and returns an owning handle.
    ///
    /// The `value` parameter becomes the single heap allocation owned by the
    /// returned handle. Foreign code can hold the raw pointer later, but Rust
    /// retains responsibility for eventually reclaiming that allocation.
    #[inline]
    pub fn new(value: T) -> Self {
        let boxed = Box::new(value);
        Self {
            ptr: unsafe { NonNull::new_unchecked(Box::into_raw(boxed)) },
        }
    }

    /// Returns the raw pointer for this handle without transferring ownership.
    ///
    /// The pointer remains owned by `self`. Callers may pass it across the
    /// boundary as an opaque handle, but they must not free it or reconstruct
    /// ownership from it while this `HandleBox<T>` is still alive.
    #[inline]
    pub fn as_ptr(&self) -> *mut T {
        self.ptr.as_ptr()
    }

    /// Returns the raw pointer for this handle as [`NonNull<T>`] without transferring ownership.
    ///
    /// This behaves like [`HandleBox::as_ptr`], but preserves the non-null
    /// invariant in the type system for callers that work with `NonNull<T>`.
    #[inline]
    pub fn as_non_null(&self) -> NonNull<T> {
        self.ptr
    }

    /// Transfers ownership of the handle to the caller as a raw pointer.
    ///
    /// The caller becomes responsible for eventually reconstructing ownership
    /// with [`HandleBox::from_raw`] or `Box::from_raw`.
    #[inline]
    pub fn into_raw(self) -> *mut T {
        let ptr = self.into_non_null();
        ptr.as_ptr()
    }

    /// Transfers ownership of the handle to the caller as [`NonNull<T>`].
    ///
    /// After this call, `self` no longer owns the allocation. The caller must
    /// eventually transfer that ownership back into Rust and destroy it exactly
    /// once.
    #[inline]
    pub fn into_non_null(self) -> NonNull<T> {
        let ptr = self.ptr;
        core::mem::forget(self);
        ptr
    }

    /// Rebuilds handle ownership from a raw pointer.
    ///
    /// Returns `None` for a null pointer so callers can preserve nullable-handle
    /// semantics without branching before the call.
    ///
    /// # Safety
    ///
    /// The `ptr` parameter must have been produced by [`HandleBox::into_raw`]
    /// or an equivalent `Box<T>` transfer, and it must still represent a live
    /// owned allocation. Passing a pointer that was already reclaimed, or that
    /// never came from Rust ownership transfer, is undefined behavior.
    #[inline]
    pub unsafe fn from_raw(ptr: *mut T) -> Option<Self> {
        NonNull::new(ptr).map(|pointer| Self { ptr: pointer })
    }

    /// Rebuilds handle ownership from a non-null pointer.
    ///
    /// # Safety
    ///
    /// The `ptr` parameter must point to a live allocation whose ownership has
    /// been transferred out of Rust in the same way as
    /// [`HandleBox::into_non_null`]. The allocation must not already be owned
    /// by another `HandleBox<T>` or `Box<T>`.
    #[inline]
    pub unsafe fn from_non_null(ptr: NonNull<T>) -> Self {
        Self { ptr }
    }
}

impl<T> AsRef<T> for HandleBox<T> {
    /// Borrows the value behind the handle without changing ownership.
    ///
    /// The returned reference is valid for as long as `self` is borrowed. This
    /// does not transfer or duplicate the underlying heap allocation.
    #[inline]
    fn as_ref(&self) -> &T {
        unsafe { self.ptr.as_ref() }
    }
}

impl<T> AsMut<T> for HandleBox<T> {
    /// Mutably borrows the value behind the handle without changing ownership.
    ///
    /// The returned reference gives direct mutable access to the heap value
    /// while `self` remains the sole owner of the allocation.
    #[inline]
    fn as_mut(&mut self) -> &mut T {
        unsafe { self.ptr.as_mut() }
    }
}

impl<T> Deref for HandleBox<T> {
    type Target = T;

    /// Borrows the value behind the handle through deref coercion.
    ///
    /// This forwards to [`HandleBox::as_ref`] so generic code can treat the
    /// handle like `&T` when ownership transfer is not needed.
    #[inline]
    fn deref(&self) -> &Self::Target {
        self.as_ref()
    }
}

impl<T> DerefMut for HandleBox<T> {
    /// Mutably borrows the value behind the handle through deref coercion.
    ///
    /// This forwards to [`HandleBox::as_mut`] so generic code can treat the
    /// handle like `&mut T` while ownership remains with the handle.
    #[inline]
    fn deref_mut(&mut self) -> &mut Self::Target {
        self.as_mut()
    }
}

impl<T> From<T> for HandleBox<T> {
    /// Allocates a value on the heap and wraps it in a handle.
    ///
    /// The `value` parameter is moved into a new `HandleBox<T>` in the same way
    /// as calling [`HandleBox::new`].
    #[inline]
    fn from(value: T) -> Self {
        Self::new(value)
    }
}

impl<T> Drop for HandleBox<T> {
    fn drop(&mut self) {
        unsafe {
            let _ = Box::from_raw(self.ptr.as_ptr());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn handle_roundtrip() {
        let handle = HandleBox::new(42u32);
        let ptr = handle.into_raw();
        let recovered = unsafe { HandleBox::from_raw(ptr) }.unwrap();
        assert_eq!(*recovered.as_ref(), 42);
    }

    #[test]
    fn handle_null() {
        let result: Option<HandleBox<u32>> = unsafe { HandleBox::from_raw(core::ptr::null_mut()) };
        assert!(result.is_none());
    }

    #[test]
    fn handle_non_null_roundtrip() {
        let handle = HandleBox::from(42u32);
        let ptr = handle.into_non_null();
        let recovered = unsafe { HandleBox::from_non_null(ptr) };
        assert_eq!(*recovered, 42);
    }

    #[test]
    fn handle_accessors_return_same_pointer() {
        let handle = HandleBox::new(42u32);
        assert_eq!(handle.as_ptr(), handle.as_non_null().as_ptr());
    }
}
