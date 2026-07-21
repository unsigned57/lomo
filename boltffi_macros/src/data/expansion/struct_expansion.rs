use proc_macro2::TokenStream;
use quote::{format_ident, quote};
use syn::ItemStruct;

use crate::data::expansion::field_attrs::BoltffiFieldAttributes;
use crate::data::expansion::passable::StructPassableExpansion;
use crate::data::expansion::wire::generate_wire_impls;
use crate::index::custom_types;

pub(super) struct StructDataExpansion {
    item_struct: ItemStruct,
}

impl StructDataExpansion {
    pub(super) fn new(mut item_struct: ItemStruct) -> syn::Result<Self> {
        Self::ensure_repr_c(&mut item_struct);
        BoltffiFieldAttributes::strip_from_fields(&mut item_struct.fields);
        Ok(Self { item_struct })
    }

    pub(super) fn render(self) -> syn::Result<TokenStream> {
        let custom_types = custom_types::registry_for_current_crate()?;
        let struct_name = &self.item_struct.ident;
        let free_fn_name = format_ident!("boltffi_free_buf_{}", struct_name);
        let wire_impls = generate_wire_impls(&self.item_struct, &custom_types);
        let passable_impl = StructPassableExpansion::new(&self.item_struct).render();
        let item_struct = self.item_struct;

        Ok(quote! {
            #item_struct
            #wire_impls
            #passable_impl

            #[cfg(not(test))]
            #[unsafe(no_mangle)]
            pub extern "C" fn #free_fn_name(buf: ::boltffi::__private::FfiBuf) {
                drop(buf);
            }
        })
    }

    fn ensure_repr_c(item_struct: &mut ItemStruct) {
        if item_struct
            .attrs
            .iter()
            .any(|attribute| attribute.path().is_ident("repr"))
        {
            return;
        }

        item_struct.attrs.insert(0, syn::parse_quote!(#[repr(C)]));
    }
}
