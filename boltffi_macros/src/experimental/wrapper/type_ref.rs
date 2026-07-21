use boltffi_binding::{BuiltinType, Primitive, TypeRef};
use proc_macro2::TokenStream;
use quote::quote;

use crate::experimental::{error::Error, surface::RenderSurface, wrapper::Render};

pub struct Renderer;

impl<S: RenderSurface> Render<S, &TypeRef> for Renderer {
    type Output = TokenStream;

    fn render(self, ty: &TypeRef) -> Result<Self::Output, Error> {
        match ty {
            TypeRef::Primitive(primitive) => self.primitive(*primitive),
            TypeRef::String => Ok(quote! { String }),
            TypeRef::Bytes => Ok(quote! { Vec<u8> }),
            TypeRef::Builtin(kind) => self.builtin(*kind),
            TypeRef::Optional(inner) => {
                let inner = <Renderer as Render<S, &TypeRef>>::render(Renderer, inner.as_ref())?;
                Ok(quote! { Option<#inner> })
            }
            TypeRef::Sequence(element) => {
                let element =
                    <Renderer as Render<S, &TypeRef>>::render(Renderer, element.as_ref())?;
                Ok(quote! { Vec<#element> })
            }
            TypeRef::Result { ok, err } => {
                let ok = <Renderer as Render<S, &TypeRef>>::render(Renderer, ok.as_ref())?;
                let err = <Renderer as Render<S, &TypeRef>>::render(Renderer, err.as_ref())?;
                Ok(quote! { Result<#ok, #err> })
            }
            _ => Err(Error::UnsupportedExpansion("type reference")),
        }
    }
}

impl Renderer {
    pub fn primitive(self, primitive: Primitive) -> Result<TokenStream, Error> {
        Ok(match primitive {
            Primitive::Bool => quote! { bool },
            Primitive::I8 => quote! { i8 },
            Primitive::U8 => quote! { u8 },
            Primitive::I16 => quote! { i16 },
            Primitive::U16 => quote! { u16 },
            Primitive::I32 => quote! { i32 },
            Primitive::U32 => quote! { u32 },
            Primitive::I64 => quote! { i64 },
            Primitive::U64 => quote! { u64 },
            Primitive::ISize => quote! { isize },
            Primitive::USize => quote! { usize },
            Primitive::F32 => quote! { f32 },
            Primitive::F64 => quote! { f64 },
            _ => return Err(Error::UnsupportedExpansion("unknown primitive")),
        })
    }

    pub fn builtin(self, kind: BuiltinType) -> Result<TokenStream, Error> {
        Ok(match kind {
            BuiltinType::Duration => quote! { ::std::time::Duration },
            BuiltinType::SystemTime => quote! { ::std::time::SystemTime },
            BuiltinType::Uuid => quote! { ::uuid::Uuid },
            BuiltinType::Url => quote! { ::url::Url },
        })
    }
}
