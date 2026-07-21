use proc_macro2::TokenStream;
use quote::quote;
use syn::ItemEnum;

use crate::data::expansion::passable::EnumPassableExpansion;
use crate::data::expansion::wire::generate_enum_wire_impls;
use crate::index::custom_types;

pub(super) struct EnumDataExpansion {
    item_enum: ItemEnum,
}

impl EnumDataExpansion {
    pub(super) fn new(mut item_enum: ItemEnum) -> syn::Result<Self> {
        Self::ensure_repr(&mut item_enum);
        Ok(Self { item_enum })
    }

    pub(super) fn render(self) -> syn::Result<TokenStream> {
        let custom_types = custom_types::registry_for_current_crate()?;
        let wire_impls = generate_enum_wire_impls(&self.item_enum, &custom_types);
        let passable_impl = EnumPassableExpansion::new(&self.item_enum).render();
        let item_enum = self.item_enum;

        Ok(quote! {
            #item_enum
            #wire_impls
            #passable_impl
        })
    }

    fn ensure_repr(item_enum: &mut ItemEnum) {
        if item_enum
            .attrs
            .iter()
            .any(|attribute| attribute.path().is_ident("repr"))
        {
            return;
        }

        let repr_attribute = if item_enum
            .variants
            .iter()
            .any(|variant| !variant.fields.is_empty())
        {
            syn::parse_quote!(#[repr(C, i32)])
        } else {
            syn::parse_quote!(#[repr(i32)])
        };
        item_enum.attrs.insert(0, repr_attribute);
    }
}
