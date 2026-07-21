use proc_macro::TokenStream;
use quote::quote;
use syn::{ImplItem, ItemImpl, Type, parse_macro_input};

pub fn custom_ffi_impl(item: TokenStream) -> TokenStream {
    let item_impl = parse_macro_input!(item as ItemImpl);
    CustomFfiExpansion::new(item_impl)
        .and_then(|expansion| expansion.render())
        .unwrap_or_else(|error| error.to_compile_error())
        .into()
}

struct CustomFfiExpansion {
    item_impl: ItemImpl,
    self_type: Type,
    ffi_repr: Type,
}

impl CustomFfiExpansion {
    fn new(item_impl: ItemImpl) -> syn::Result<Self> {
        Self::validate_generics(&item_impl)?;
        Self::validate_trait(&item_impl)?;
        let self_type = Self::normalize_self_type(item_impl.self_ty.as_ref());
        let ffi_repr = Self::ffi_repr_type(&item_impl)?;
        Ok(Self {
            item_impl,
            self_type,
            ffi_repr,
        })
    }

    fn render(self) -> syn::Result<proc_macro2::TokenStream> {
        let item_impl = self.item_impl;
        let self_type = self.self_type;
        let ffi_repr = self.ffi_repr;

        Ok(quote! {
            #item_impl

            impl ::boltffi::__private::wire::WireEncode for #self_type {
                #[inline]
                fn is_fixed_size() -> bool {
                    <#ffi_repr as ::boltffi::__private::wire::WireEncode>::is_fixed_size()
                }

                #[inline]
                fn fixed_size() -> Option<usize> {
                    <#ffi_repr as ::boltffi::__private::wire::WireEncode>::fixed_size()
                }

                #[inline]
                fn wire_size(&self) -> usize {
                    let repr = <#self_type as ::boltffi::CustomFfiConvertible>::into_ffi(self);
                    <#ffi_repr as ::boltffi::__private::wire::WireEncode>::wire_size(&repr)
                }

                #[inline]
                fn encode_to(&self, buf: &mut [u8]) -> usize {
                    let repr = <#self_type as ::boltffi::CustomFfiConvertible>::into_ffi(self);
                    <#ffi_repr as ::boltffi::__private::wire::WireEncode>::encode_to(&repr, buf)
                }
            }

            impl ::boltffi::__private::wire::WireDecode for #self_type {
                #[inline]
                fn decode_from(buf: &[u8]) -> ::boltffi::__private::wire::DecodeResult<Self> {
                    let (repr, used) = <#ffi_repr as ::boltffi::__private::wire::WireDecode>::decode_from(buf)?;
                    let value = <#self_type as ::boltffi::CustomFfiConvertible>::try_from_ffi(repr)
                        .map_err(|_| ::boltffi::__private::wire::DecodeError::InvalidValue(
                            ::boltffi::__private::wire::InvalidWireValue::CustomConversion,
                        ))?;
                    Ok((value, used))
                }
            }
        })
    }

    fn validate_generics(item_impl: &ItemImpl) -> syn::Result<()> {
        if item_impl.generics.params.is_empty() {
            return Ok(());
        }

        Err(syn::Error::new_spanned(
            &item_impl.generics,
            "custom_ffi does not support generics",
        ))
    }

    fn validate_trait(item_impl: &ItemImpl) -> syn::Result<()> {
        let is_custom_ffi_convertible = item_impl
            .trait_
            .as_ref()
            .and_then(|(_, path, _)| path.segments.last())
            .is_some_and(|segment| segment.ident == "CustomFfiConvertible");

        if is_custom_ffi_convertible {
            return Ok(());
        }

        Err(syn::Error::new_spanned(
            item_impl,
            "custom_ffi must annotate an impl of CustomFfiConvertible",
        ))
    }

    fn ffi_repr_type(item_impl: &ItemImpl) -> syn::Result<Type> {
        item_impl
            .items
            .iter()
            .find_map(|item| match item {
                ImplItem::Type(associated_type) if associated_type.ident == "FfiRepr" => {
                    Some(associated_type.ty.clone())
                }
                _ => None,
            })
            .ok_or_else(|| {
                syn::Error::new_spanned(
                    item_impl,
                    "custom_ffi requires `type FfiRepr = ...;` in the impl block",
                )
            })
    }

    fn normalize_self_type(self_type: &Type) -> Type {
        match self_type {
            Type::Group(group) => group.elem.as_ref().clone(),
            _ => self_type.clone(),
        }
    }
}
