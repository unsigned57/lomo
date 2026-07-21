use super::{ArcFromCallbackHandle, BoxFromCallbackHandle};

/// Associates a Rust callback-facing type with its generated foreign wrapper.
///
/// The `Foreign` type is the wrapper that actually crosses the FFI boundary.
/// It must support whichever ownership recovery modes the generated callback
/// glue requires.
pub trait CallbackForeignType {
    /// Generated wrapper type that is passed across the FFI boundary.
    ///
    /// Implementations choose the foreign wrapper that matches the Rust-facing
    /// callback type. That wrapper must be able to rebuild both shared and
    /// unique ownership from a raw [`super::CallbackHandle`] because generated
    /// callback glue may need either recovery mode depending on how the
    /// callback is used.
    type Foreign: ArcFromCallbackHandle + BoxFromCallbackHandle;
}
