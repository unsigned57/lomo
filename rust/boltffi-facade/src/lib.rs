//! Production `BoltFFI` facade for Lomo.
//!
//! Keeps the official macro path (`::boltffi::__private`) while depending on
//! `boltffi_core` with default features disabled so unused url/uuid codecs never
//! enter the Android link graph.

pub use boltffi_core::{
    ArcFromCallbackHandle, BoxFromCallbackHandle, CallbackForeignType, CallbackHandle,
    CustomFfiConvertible, CustomTypeConversionError, Data, EventSubscription, FfiType,
    StreamProducer, UnexpectedFfiCallbackError, custom_ffi, custom_type, data, default, error,
    export, ffi_stream, name, skip,
};

#[doc(hidden)]
pub mod __private {
    pub use boltffi_core::{
        ArcFromCallbackHandle, AsyncCallback, AsyncCallbackString, AsyncCallbackVoid,
        BoxFromCallbackHandle, CallbackForeignType, CallbackHandle, EventSubscription, FfiBuf,
        FfiSpan, FfiStatus, NativeCallbackOwner, Passable, RustFutureContinuationCallback,
        RustFutureHandle, StreamContinuationCallback, StreamPollResult, SubscriptionHandle,
        VecTransport, WaitResult, WirePassable, rustfuture, set_last_error, take_last_error, wire,
    };
}
