use boltffi_binding::{CodecNode, Native, ReadPlan, Wasm32, native, wasm32};
use proc_macro2::TokenStream;
use quote::quote;

use crate::experimental::{
    error::Error,
    expansion::Expansion,
    surface::RenderSurface,
    wrapper::{Render, encoded},
};

pub struct Renderer;

pub struct Input<'expansion, 'codec, 'lowered, S: RenderSurface> {
    codec: &'codec CodecNode,
    shape: S::BufferShape,
    value: syn::Ident,
    value_binding: RustValueBinding,
    expansion: &'expansion Expansion<'lowered, S>,
}

impl<'expansion, 'codec, 'lowered, S: RenderSurface> Input<'expansion, 'codec, 'lowered, S> {
    pub fn new(
        codec: &'codec ReadPlan,
        shape: S::BufferShape,
        value: syn::Ident,
        expansion: &'expansion Expansion<'lowered, S>,
    ) -> Self {
        Self {
            codec: codec.root(),
            shape,
            value,
            value_binding: RustValueBinding::Owned,
            expansion,
        }
    }

    pub fn string(
        codec: &'codec ReadPlan,
        shape: S::BufferShape,
        value: syn::Ident,
        expansion: &'expansion Expansion<'lowered, S>,
    ) -> Self {
        Self::new(codec, shape, value, expansion)
    }

    pub fn borrowed(
        codec: &'codec ReadPlan,
        shape: S::BufferShape,
        value: syn::Ident,
        expansion: &'expansion Expansion<'lowered, S>,
    ) -> Self {
        Self {
            codec: codec.root(),
            shape,
            value,
            value_binding: RustValueBinding::Borrowed,
            expansion,
        }
    }

    pub fn root(
        codec: &'codec CodecNode,
        shape: S::BufferShape,
        value: syn::Ident,
        expansion: &'expansion Expansion<'lowered, S>,
    ) -> Self {
        Self {
            codec,
            shape,
            value,
            value_binding: RustValueBinding::Owned,
            expansion,
        }
    }
}

pub struct Tokens {
    value_type: TokenStream,
    return_type: TokenStream,
    value: TokenStream,
}

impl Tokens {
    pub fn return_type(&self) -> &TokenStream {
        &self.return_type
    }

    pub fn value(&self) -> &TokenStream {
        &self.value
    }

    pub fn return_type_without_arrow(&self) -> TokenStream {
        self.value_type.clone()
    }
}

pub struct Empty<S: RenderSurface> {
    shape: S::BufferShape,
}

impl<S: RenderSurface> Empty<S> {
    pub fn new(shape: S::BufferShape) -> Self {
        Self { shape }
    }
}

impl<'expansion, 'codec, 'lowered> Render<Native, Input<'expansion, 'codec, 'lowered, Native>>
    for Renderer
{
    type Output = Tokens;

    fn render(
        self,
        input: Input<'expansion, 'codec, 'lowered, Native>,
    ) -> Result<Self::Output, Error> {
        self.render_native(
            input.codec,
            input.shape,
            input.value,
            input.value_binding,
            input.expansion,
        )
    }
}

impl Render<Native, Empty<Native>> for Renderer {
    type Output = Tokens;

    fn render(self, input: Empty<Native>) -> Result<Self::Output, Error> {
        match input.shape {
            native::BufferShape::Buffer => Ok(Tokens {
                value_type: quote! { ::boltffi::__private::FfiBuf },
                return_type: quote! { -> ::boltffi::__private::FfiBuf },
                value: quote! { ::boltffi::__private::FfiBuf::default() },
            }),
            native::BufferShape::Slice | native::BufferShape::BufferPointer => {
                Err(Error::UnsupportedExpansion("native encoded return shape"))
            }
            _ => Err(Error::UnsupportedExpansion(
                "unknown native encoded return shape",
            )),
        }
    }
}

impl<'expansion, 'codec, 'lowered> Render<Wasm32, Input<'expansion, 'codec, 'lowered, Wasm32>>
    for Renderer
{
    type Output = Tokens;

    fn render(
        self,
        input: Input<'expansion, 'codec, 'lowered, Wasm32>,
    ) -> Result<Self::Output, Error> {
        self.render_wasm(
            input.codec,
            input.shape,
            input.value,
            input.value_binding,
            input.expansion,
        )
    }
}

impl Render<Wasm32, Empty<Wasm32>> for Renderer {
    type Output = Tokens;

    fn render(self, input: Empty<Wasm32>) -> Result<Self::Output, Error> {
        match input.shape {
            wasm32::BufferShape::Packed => Ok(Tokens {
                value_type: quote! { u64 },
                return_type: quote! { -> u64 },
                value: quote! { ::boltffi::__private::FfiBuf::default().into_packed() },
            }),
            wasm32::BufferShape::Slice => {
                Err(Error::UnsupportedExpansion("wasm encoded return shape"))
            }
            _ => Err(Error::UnsupportedExpansion(
                "unknown wasm encoded return shape",
            )),
        }
    }
}

impl Renderer {
    fn render_native<'lowered>(
        self,
        codec: &CodecNode,
        shape: native::BufferShape,
        value: syn::Ident,
        value_binding: RustValueBinding,
        expansion: &Expansion<'lowered, Native>,
    ) -> Result<Tokens, Error> {
        match shape {
            native::BufferShape::Buffer => {
                let value = value_binding.buffer(codec, expansion, value)?;
                Ok(Tokens {
                    value_type: quote! { ::boltffi::__private::FfiBuf },
                    return_type: quote! { -> ::boltffi::__private::FfiBuf },
                    value,
                })
            }
            native::BufferShape::Slice | native::BufferShape::BufferPointer => {
                Err(Error::UnsupportedExpansion("native encoded return shape"))
            }
            _ => Err(Error::UnsupportedExpansion(
                "unknown native encoded return shape",
            )),
        }
    }

    fn render_wasm<'lowered>(
        self,
        codec: &CodecNode,
        shape: wasm32::BufferShape,
        value: syn::Ident,
        value_binding: RustValueBinding,
        expansion: &Expansion<'lowered, Wasm32>,
    ) -> Result<Tokens, Error> {
        match shape {
            wasm32::BufferShape::Packed => {
                let buffer = value_binding.buffer(codec, expansion, value)?;
                Ok(Tokens {
                    value_type: quote! { u64 },
                    return_type: quote! { -> u64 },
                    value: quote! { #buffer.into_packed() },
                })
            }
            wasm32::BufferShape::Slice => {
                Err(Error::UnsupportedExpansion("wasm encoded return shape"))
            }
            _ => Err(Error::UnsupportedExpansion(
                "unknown wasm encoded return shape",
            )),
        }
    }
}

#[derive(Clone, Copy)]
enum RustValueBinding {
    Owned,
    Borrowed,
}

impl RustValueBinding {
    fn buffer<'lowered, S: RenderSurface>(
        self,
        codec: &CodecNode,
        expansion: &Expansion<'lowered, S>,
        value: syn::Ident,
    ) -> Result<TokenStream, Error> {
        let value = quote! { #value };
        match self {
            Self::Owned => encoded::outgoing::Value::new(codec, expansion).buffer(value),
            Self::Borrowed => {
                encoded::outgoing::Value::new(codec, expansion).borrowed_buffer(value)
            }
        }
    }
}
