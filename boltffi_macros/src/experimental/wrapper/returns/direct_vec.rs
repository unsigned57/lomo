use boltffi_binding::{Native, Wasm32};
use proc_macro2::TokenStream;
use quote::quote;
use syn::Type;

use crate::experimental::{error::Error, wrapper::Render};

use super::Tokens;

pub struct Renderer;
pub struct Failure;
pub struct FailureInput;
pub struct Empty;
pub struct Incoming;

pub struct Input {
    value: syn::Ident,
    rust_element: Type,
}

pub struct IncomingInput {
    rust_element: Type,
    value: TokenStream,
}

impl Input {
    pub fn new(value: syn::Ident, rust_element: Type) -> Self {
        Self {
            value,
            rust_element,
        }
    }
}

impl IncomingInput {
    pub fn new(rust_element: Type, value: TokenStream) -> Self {
        Self {
            rust_element,
            value,
        }
    }
}

impl Render<Native, Input> for Renderer {
    type Output = Tokens;

    fn render(self, input: Input) -> Result<Self::Output, Error> {
        let value = input.value;
        let element = input.rust_element;
        Ok(Tokens {
            items: Vec::new(),
            ffi_parameters: Vec::new(),
            return_type: quote! { -> ::boltffi::__private::FfiBuf },
            body: quote! {
                <#element as ::boltffi::__private::VecTransport>::pack_vec(#value)
            },
        })
    }
}

impl Render<Wasm32, Input> for Renderer {
    type Output = Tokens;

    fn render(self, input: Input) -> Result<Self::Output, Error> {
        let value = input.value;
        let element = input.rust_element;
        Ok(Tokens {
            items: Vec::new(),
            ffi_parameters: Vec::new(),
            return_type: quote! {},
            body: quote! {
                let __boltffi_buf = <#element as ::boltffi::__private::VecTransport>::pack_vec(#value);
                ::boltffi::__private::write_return_slot(
                    __boltffi_buf.as_ptr() as u32,
                    __boltffi_buf.len() as u32,
                    __boltffi_buf.cap() as u32,
                    __boltffi_buf.align() as u32
                );
                core::mem::forget(__boltffi_buf);
            },
        })
    }
}

impl Render<Native, FailureInput> for Failure {
    type Output = TokenStream;

    fn render(self, _input: FailureInput) -> Result<Self::Output, Error> {
        let empty = <Renderer as Render<Native, Empty>>::render(Renderer, Empty)?;
        let body = empty.body();
        Ok(quote! {
            return #body;
        })
    }
}

impl Render<Wasm32, FailureInput> for Failure {
    type Output = TokenStream;

    fn render(self, _input: FailureInput) -> Result<Self::Output, Error> {
        let empty = <Renderer as Render<Wasm32, Empty>>::render(Renderer, Empty)?;
        let body = empty.body();
        Ok(quote! {
            return #body;
        })
    }
}

impl Render<Native, Empty> for Renderer {
    type Output = Tokens;

    fn render(self, _input: Empty) -> Result<Self::Output, Error> {
        Ok(Tokens {
            items: Vec::new(),
            ffi_parameters: Vec::new(),
            return_type: quote! { -> ::boltffi::__private::FfiBuf },
            body: quote! { ::boltffi::__private::FfiBuf::default() },
        })
    }
}

impl Render<Wasm32, Empty> for Renderer {
    type Output = Tokens;

    fn render(self, _input: Empty) -> Result<Self::Output, Error> {
        Ok(Tokens {
            items: Vec::new(),
            ffi_parameters: Vec::new(),
            return_type: quote! {},
            body: TokenStream::new(),
        })
    }
}

impl Render<Native, IncomingInput> for Incoming {
    type Output = TokenStream;

    fn render(self, input: IncomingInput) -> Result<Self::Output, Error> {
        let element = input.rust_element;
        let value = input.value;
        Ok(quote! {
            {
                let __boltffi_result = #value;
                if __boltffi_result.as_ptr().is_null() || __boltffi_result.len() == 0 {
                    Vec::new()
                } else {
                    let __boltffi_byte_len = __boltffi_result.len();
                    let __boltffi_element_size =
                        ::core::mem::size_of::<<#element as ::boltffi::__private::Passable>::In>();
                    if __boltffi_byte_len % __boltffi_element_size == 0 {
                        unsafe {
                            <#element as ::boltffi::__private::VecTransport>::unpack_vec(
                                __boltffi_result.as_ptr(),
                                __boltffi_byte_len,
                            )
                        }
                    } else {
                        panic!(
                            "invalid callback method Vec<{}> return byte length {} for element size {}",
                            ::core::any::type_name::<#element>(),
                            __boltffi_byte_len,
                            __boltffi_element_size,
                        )
                    }
                }
            }
        })
    }
}

impl Render<Wasm32, IncomingInput> for Incoming {
    type Output = TokenStream;

    fn render(self, input: IncomingInput) -> Result<Self::Output, Error> {
        let element = input.rust_element;
        let value = input.value;
        Ok(quote! {
            {
                #value;
                unsafe {
                    ::boltffi::__private::take_return_slot_vec::<#element>()
                }
            }
        })
    }
}
