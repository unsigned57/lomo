//! Production `BoltFFI` facade for Lomo.
//!
//! Keeps the official macro path (`::boltffi::__private`) while depending on
//! `boltffi_core` with default features disabled so unused url/uuid codecs never
//! enter the Android link graph.

pub use boltffi_core::{
    ArcFromCallbackHandle, BoxFromCallbackHandle, CallbackForeignType, CallbackHandle,
    CustomFfiConvertible, CustomTypeConversionError, EventSubscription, FfiType, InternedString,
    InternedStringPool, InternedStringRepr, StreamProducer, UnexpectedFfiCallbackError, custom_ffi,
    custom_type, data, default, error, export, ffi_stream, interned_string_pool, name, scaffolding,
    skip,
};

#[doc(hidden)]
pub mod __dart_sync_ffi {
    pub struct SyncFfiScope;

    impl SyncFfiScope {
        #[inline]
        #[must_use]
        pub const fn enter() -> Self {
            Self
        }
    }
}

/// Keeps a source record in bindgen's metadata build and drops it from every other build,
/// so records never reach a shipped binary.
#[cfg(boltffi_source_records)]
#[doc(hidden)]
#[macro_export]
macro_rules! __source_record {
    ($($record:tt)*) => { $($record)* };
}

#[cfg(not(boltffi_source_records))]
#[doc(hidden)]
#[macro_export]
macro_rules! __source_record {
    ($($record:tt)*) => {};
}

#[doc(hidden)]
pub mod __private {
    pub use crate::__source_record as source_record;
    pub use boltffi_core::capture;
    pub use boltffi_core::{
        ArcFromCallbackHandle, AsyncCallback, AsyncCallbackString, AsyncCallbackVoid,
        BoxFromCallbackHandle, CallbackForeignType, CallbackHandle, CallbackLocalHandle,
        CallbackMarker, ClassHandle, CustomReprTag, CustomType, EventSubscription, FfiBuf, FfiSpan,
        FfiStatus, ForeignCall, InternedString, InternedStringPool, InternedStringRepr,
        NativeCallbackOwner, Passable, ReprArg, ReprArgOf, RustFutureContinuationCallback,
        RustFutureHandle, StreamContinuationCallback, StreamPollResult, SubscriptionHandle,
        UnexpectedFfiCallbackError, UnexpectedFfiCallbackPayload, VecTransport, WaitResult,
        WirePassable, callback_arc, callback_box, rustfuture, set_last_error, set_last_error_debug,
        set_last_error_display, set_last_error_len, take_last_error, wire,
    };
    pub use boltffi_core::{CfgEval, lane_resume};
}
