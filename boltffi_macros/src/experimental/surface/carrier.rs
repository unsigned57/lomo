use boltffi_binding::{HandlePresence, Native, Surface, Wasm32, native, wasm32};
use proc_macro2::TokenStream;
use quote::quote;
use syn::Ident;

use crate::experimental::{error::Error, rust_api};

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

/// How opaque handles cross on a surface.
///
/// The carrier value names the integer or struct an opaque handle occupies
/// on its surface. This trait owns every spelling derived from that choice:
/// the slot type and failure sentinel, how a callback handle parameter is
/// rebuilt from the slot value, and how a Rust-local callback handle leaves
/// through the return slot.
pub trait HandleCrossings: Surface {
    /// Returns a carrier's slot type and failure sentinel.
    fn carrier_tokens(carrier: Self::HandleCarrier) -> Result<CarrierTokens, Error>;

    /// Rebuilds the runtime callback handle from a parameter slot value.
    fn callback_param_binding(ident: &Ident) -> TokenStream;

    /// Produces the return-slot value for a Rust-local callback handle.
    fn callback_return(
        local_handle: &TokenStream,
        form: rust_api::CallbackCarrier,
        presence: HandlePresence,
        value: &Ident,
        zero: &TokenStream,
    ) -> Result<TokenStream, Error>;
}

impl HandleCrossings for Native {
    fn carrier_tokens(carrier: native::HandleCarrier) -> Result<CarrierTokens, Error> {
        match carrier {
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

    fn callback_param_binding(ident: &Ident) -> TokenStream {
        quote! { #ident }
    }

    fn callback_return(
        local_handle: &TokenStream,
        form: rust_api::CallbackCarrier,
        presence: HandlePresence,
        value: &Ident,
        zero: &TokenStream,
    ) -> Result<TokenStream, Error> {
        match (form, presence) {
            (rust_api::CallbackCarrier::BoxedDyn, HandlePresence::Required) => Ok(quote! {
                #local_handle(::std::sync::Arc::from(#value))
            }),
            (rust_api::CallbackCarrier::ArcDyn, HandlePresence::Required) => Ok(quote! {
                #local_handle(#value)
            }),
            (rust_api::CallbackCarrier::BoxedDyn, HandlePresence::Nullable) => Ok(quote! {
                #value
                    .map(|__boltffi_callback| {
                        #local_handle(::std::sync::Arc::from(__boltffi_callback))
                    })
                    .unwrap_or(#zero)
            }),
            (rust_api::CallbackCarrier::ArcDyn, HandlePresence::Nullable) => Ok(quote! {
                #value
                    .map(#local_handle)
                    .unwrap_or(#zero)
            }),
            _ => Err(Error::UnsupportedExpansion(
                "unknown callback handle presence",
            )),
        }
    }
}

impl HandleCrossings for Wasm32 {
    fn carrier_tokens(carrier: wasm32::HandleCarrier) -> Result<CarrierTokens, Error> {
        match carrier {
            wasm32::HandleCarrier::U32 => Ok(CarrierTokens {
                ty: quote! { u32 },
                zero: quote! { 0 },
            }),
            _ => Err(Error::UnsupportedExpansion("unknown wasm handle carrier")),
        }
    }

    fn callback_param_binding(ident: &Ident) -> TokenStream {
        quote! { ::boltffi::__private::CallbackHandle::from_wasm_handle(#ident) }
    }

    fn callback_return(
        local_handle: &TokenStream,
        form: rust_api::CallbackCarrier,
        presence: HandlePresence,
        value: &Ident,
        zero: &TokenStream,
    ) -> Result<TokenStream, Error> {
        match (form, presence) {
            (rust_api::CallbackCarrier::BoxedDyn, HandlePresence::Required) => Ok(quote! {
                #local_handle(::std::sync::Arc::from(#value)).handle() as u32
            }),
            (rust_api::CallbackCarrier::ArcDyn, HandlePresence::Required) => Ok(quote! {
                #local_handle(#value).handle() as u32
            }),
            (rust_api::CallbackCarrier::BoxedDyn, HandlePresence::Nullable) => Ok(quote! {
                #value
                    .map(|__boltffi_callback| {
                        #local_handle(::std::sync::Arc::from(__boltffi_callback)).handle() as u32
                    })
                    .unwrap_or(#zero)
            }),
            (rust_api::CallbackCarrier::ArcDyn, HandlePresence::Nullable) => Ok(quote! {
                #value
                    .map(|__boltffi_callback| #local_handle(__boltffi_callback).handle() as u32)
                    .unwrap_or(#zero)
            }),
            _ => Err(Error::UnsupportedExpansion(
                "unknown callback handle presence",
            )),
        }
    }
}
