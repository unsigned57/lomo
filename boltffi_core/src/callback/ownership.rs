use std::sync::Arc;

use super::CallbackHandle;

/// Rebuilds shared callback ownership from a raw [`CallbackHandle`].
///
/// Generated bindings use this trait for callback wrappers that are held behind
/// `Arc` and may be cloned across the boundary.
///
/// # Safety
///
/// `handle` must come from the matching generated foreign callback type. Passing
/// a handle created for a different callback wrapper, or reusing a handle after
/// its ownership was already consumed, is undefined behavior.
pub trait ArcFromCallbackHandle {
    /// Rebuilds shared ownership of a callback wrapper from a raw callback handle.
    ///
    /// The `handle` parameter must identify a live callback wrapper of the
    /// implementing type. Implementations reconstruct `Arc<Self>` without
    /// allocating a new callback object, so the returned value shares the same
    /// callback state that originally crossed the boundary.
    unsafe fn arc_from_callback_handle(handle: CallbackHandle) -> Arc<Self>;
}

/// Rebuilds unique callback ownership from a raw [`CallbackHandle`].
///
/// Generated bindings use this trait for callback wrappers that are moved
/// across the boundary and later recovered as `Box<Self>`.
///
/// # Safety
///
/// `handle` must come from the matching generated foreign callback type and
/// must still represent a live boxed value. Calling this more than once for the
/// same owned handle is undefined behavior.
pub trait BoxFromCallbackHandle {
    /// Rebuilds unique ownership of a callback wrapper from a raw callback handle.
    ///
    /// The `handle` parameter must identify a live boxed callback wrapper of
    /// the implementing type. Implementations consume that ownership and return
    /// the original `Box<Self>` rather than creating a fresh allocation.
    unsafe fn box_from_callback_handle(handle: CallbackHandle) -> Box<Self>;
}
