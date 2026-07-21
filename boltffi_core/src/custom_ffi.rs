use crate::wire::{WireDecode, WireEncode};

#[allow(clippy::wrong_self_convention)]
pub trait CustomFfiConvertible: Sized {
    type FfiRepr: WireEncode + WireDecode;
    type Error;

    fn into_ffi(&self) -> Self::FfiRepr;
    fn try_from_ffi(repr: Self::FfiRepr) -> Result<Self, Self::Error>;
}
