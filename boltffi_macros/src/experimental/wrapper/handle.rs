use boltffi_binding::{CallbackLocalFunction, Native, Wasm32, native, wasm32};
use proc_macro2::TokenStream;
use quote::quote;
use syn::{Ident, parse_str};

use crate::experimental::{error::Error, wrapper::Render};

/// Renders the integer type that carries a handle across the FFI boundary.
///
/// Each target represents an opaque handle as one of its own carrier kinds, so
/// the rule is selected by the `(S, CarrierInput<C>)` pair: `Native` resolves
/// `u64`, `usize`, or a `CallbackHandle`, while `Wasm32` resolves `u32`. A
/// carrier kind the target does not recognize is rejected as an unsupported
/// expansion.
///
/// # Example
///
/// For `Native`, `native::HandleCarrier::U64` renders to the type `u64`, and
/// for `Wasm32`, `wasm32::HandleCarrier::U32` renders to `u32`.
pub struct Carrier;

/// The carrier kind to render for a target.
///
/// # Example
///
/// `CarrierInput::new(native::HandleCarrier::USize)` selects the `Native`
/// rule and renders the `usize` carrier.
pub struct CarrierInput<C> {
    carrier: C,
}

impl<C> CarrierInput<C> {
    /// Wraps a target carrier kind for rendering.
    pub fn new(carrier: C) -> Self {
        Self { carrier }
    }
}

/// The Rust representation of a handle carrier.
///
/// Pairs the carrier's value type with the sentinel returned when a handle
/// cannot be produced, so a fallible wrapper emits the type for its signature
/// and the sentinel on its failure path from one rendered value.
///
/// # Example
///
/// `native::HandleCarrier::CallbackHandle` renders `ty` as
/// `::boltffi::__private::CallbackHandle` and `zero` as
/// `::boltffi::__private::CallbackHandle::NULL`.
pub struct CarrierTokens {
    ty: TokenStream,
    zero: TokenStream,
}

impl CarrierTokens {
    /// Returns the carrier's Rust value type.
    pub fn ty(&self) -> &TokenStream {
        &self.ty
    }

    /// Returns the sentinel for a handle that could not be produced.
    pub fn zero(&self) -> &TokenStream {
        &self.zero
    }
}

impl Render<Native, CarrierInput<native::HandleCarrier>> for Carrier {
    type Output = CarrierTokens;

    fn render(self, input: CarrierInput<native::HandleCarrier>) -> Result<Self::Output, Error> {
        match input.carrier {
            native::HandleCarrier::U64 => Ok(CarrierTokens {
                ty: quote! { u64 },
                zero: quote! { 0 },
            }),
            native::HandleCarrier::USize => Ok(CarrierTokens {
                ty: quote! { usize },
                zero: quote! { 0 },
            }),
            native::HandleCarrier::CallbackHandle => Ok(CarrierTokens {
                ty: quote! { ::boltffi::__private::CallbackHandle },
                zero: quote! { ::boltffi::__private::CallbackHandle::NULL },
            }),
            _ => Err(Error::UnsupportedExpansion("unknown native handle carrier")),
        }
    }
}

impl Render<Wasm32, CarrierInput<wasm32::HandleCarrier>> for Carrier {
    type Output = CarrierTokens;

    fn render(self, input: CarrierInput<wasm32::HandleCarrier>) -> Result<Self::Output, Error> {
        match input.carrier {
            wasm32::HandleCarrier::U32 => Ok(CarrierTokens {
                ty: quote! { u32 },
                zero: quote! { 0 },
            }),
            _ => Err(Error::UnsupportedExpansion("unknown wasm handle carrier")),
        }
    }
}

/// A generated Rust path to a local callback function.
pub struct CallbackLocalPath {
    function: CallbackLocalFunction,
}

impl CallbackLocalPath {
    /// Creates a path renderer for a lowered local callback function.
    pub fn new(function: &CallbackLocalFunction) -> Self {
        Self {
            function: function.clone(),
        }
    }

    /// Returns the generated helper identifier.
    pub fn tokens(self) -> Result<TokenStream, Error> {
        let ident = self
            .function
            .segments()
            .last()
            .map(|segment| parse_str::<Ident>(segment.as_str()))
            .transpose()
            .map_err(|_| Error::SourceSyntaxMismatch("callback local handle path is not Rust"))?
            .ok_or(Error::SourceSyntaxMismatch(
                "callback local handle path is empty",
            ))?;
        Ok(quote! { #ident })
    }
}
