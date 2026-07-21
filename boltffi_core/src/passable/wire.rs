use crate::types::{FfiBuf, FfiSpan};
use crate::wire::{self, WireDecode, WireEncode};

use super::Passable;

/// Marks a type that crosses the ABI as an owned wire buffer or borrowed wire span.
///
/// A `WirePassable` value is not passed directly as a primitive ABI value. Instead,
/// it is reconstructed from bytes on input and serialized back to bytes on output.
/// The byte-level contract is provided by [`WireDecode`] and [`WireEncode`].
pub unsafe trait WirePassable: WireEncode + WireDecode + Sized {}

unsafe impl<T: WirePassable> Passable for T {
    type In = FfiSpan;
    type Out = FfiBuf;

    unsafe fn unpack(input: FfiSpan) -> Self {
        let bytes = unsafe { input.as_bytes() };
        wire::decode(bytes).expect("wire decode failed in Passable::unpack")
    }

    fn pack(self) -> FfiBuf {
        FfiBuf::wire_encode(&self)
    }
}
