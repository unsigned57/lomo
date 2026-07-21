use boltffi_binding::{Native, Surface, Wasm32, native, wasm32};
use proc_macro2::TokenStream;
use quote::quote;

use crate::experimental::error::Error;

/// A render lane for an encoded value occupying parameter slots.
#[derive(Clone, Copy)]
pub enum ParamCrossing {
    /// Borrowed pointer-plus-length pair across two adjacent slots.
    Slice,
}

/// A render lane for an encoded value leaving through the return slot.
#[derive(Clone, Copy)]
pub enum ReturnCrossing {
    /// Owned buffer descriptor returned by value.
    Buffer,
    /// Buffer descriptor folded into a single `u64`.
    Packed,
}

/// How encoded buffers cross on a surface.
///
/// The IR records which [`Surface::BufferShape`] each encoded crossing
/// uses; this trait resolves that value to the render lane the wrapper
/// emits, rejecting shapes the surface cannot place in the requested
/// position.
pub trait BufferCrossings: Surface {
    /// Resolves the parameter-slot lane for a shape.
    fn param_crossing(shape: Self::BufferShape) -> Result<ParamCrossing, Error>;

    /// Resolves the return-slot lane for a shape.
    fn return_crossing(shape: Self::BufferShape) -> Result<ReturnCrossing, Error>;
}

impl ReturnCrossing {
    /// Returns the Rust type occupying the return slot.
    pub fn value_type(self) -> TokenStream {
        match self {
            Self::Buffer => quote! { ::boltffi::__private::FfiBuf },
            Self::Packed => quote! { u64 },
        }
    }

    /// Returns the wrapper return-type tokens.
    pub fn return_type(self) -> TokenStream {
        let value_type = self.value_type();
        quote! { -> #value_type }
    }

    /// Wraps an owned buffer expression into the return-slot value.
    pub fn value(self, buffer: TokenStream) -> TokenStream {
        match self {
            Self::Buffer => buffer,
            Self::Packed => quote! { #buffer.into_packed() },
        }
    }

    /// Returns the empty return-slot value.
    pub fn empty(self) -> TokenStream {
        match self {
            Self::Buffer => quote! { ::boltffi::__private::FfiBuf::default() },
            Self::Packed => quote! { ::boltffi::__private::FfiBuf::default().into_packed() },
        }
    }
}

impl BufferCrossings for Native {
    fn param_crossing(shape: native::BufferShape) -> Result<ParamCrossing, Error> {
        match shape {
            native::BufferShape::Slice => Ok(ParamCrossing::Slice),
            native::BufferShape::Buffer | native::BufferShape::BufferPointer => Err(
                Error::UnsupportedExpansion("native encoded parameter shape"),
            ),
            _ => Err(Error::UnsupportedExpansion(
                "unknown native encoded parameter shape",
            )),
        }
    }

    fn return_crossing(shape: native::BufferShape) -> Result<ReturnCrossing, Error> {
        match shape {
            native::BufferShape::Buffer => Ok(ReturnCrossing::Buffer),
            native::BufferShape::Slice | native::BufferShape::BufferPointer => {
                Err(Error::UnsupportedExpansion("native encoded return shape"))
            }
            _ => Err(Error::UnsupportedExpansion(
                "unknown native encoded return shape",
            )),
        }
    }
}

impl BufferCrossings for Wasm32 {
    fn param_crossing(shape: wasm32::BufferShape) -> Result<ParamCrossing, Error> {
        match shape {
            wasm32::BufferShape::Slice => Ok(ParamCrossing::Slice),
            wasm32::BufferShape::Packed => {
                Err(Error::UnsupportedExpansion("wasm encoded parameter shape"))
            }
            _ => Err(Error::UnsupportedExpansion(
                "unknown wasm encoded parameter shape",
            )),
        }
    }

    fn return_crossing(shape: wasm32::BufferShape) -> Result<ReturnCrossing, Error> {
        match shape {
            wasm32::BufferShape::Packed => Ok(ReturnCrossing::Packed),
            wasm32::BufferShape::Slice => {
                Err(Error::UnsupportedExpansion("wasm encoded return shape"))
            }
            _ => Err(Error::UnsupportedExpansion(
                "unknown wasm encoded return shape",
            )),
        }
    }
}
