use boltffi_ffi_rules::classification::PassableCategory;
use quote::quote;

use crate::data::analysis::{EnumDataShape, StructDataShape};

pub(super) struct StructPassableExpansion<'a> {
    item_struct: &'a syn::ItemStruct,
}

pub(super) struct EnumPassableExpansion<'a> {
    item_enum: &'a syn::ItemEnum,
}

enum EnumPassableKind<'a> {
    Scalar {
        repr_type: syn::Ident,
        variants: &'a syn::punctuated::Punctuated<syn::Variant, syn::Token![,]>,
    },
    WireEncoded {
        enum_name: &'a syn::Ident,
    },
}

impl<'a> StructPassableExpansion<'a> {
    pub(super) fn new(item_struct: &'a syn::ItemStruct) -> Self {
        Self { item_struct }
    }

    pub(super) fn render(&self) -> proc_macro2::TokenStream {
        match StructDataShape::new(self.item_struct).passable_category() {
            PassableCategory::Blittable => {
                generate_passable_for_blittable_struct(&self.item_struct.ident)
            }
            _ => generate_passable_for_wire_encoded(&self.item_struct.ident),
        }
    }
}

impl<'a> EnumPassableExpansion<'a> {
    pub(super) fn new(item_enum: &'a syn::ItemEnum) -> Self {
        Self { item_enum }
    }

    pub(super) fn render(&self) -> proc_macro2::TokenStream {
        match self.passable_kind() {
            EnumPassableKind::Scalar {
                repr_type,
                variants,
            } => generate_passable_for_scalar_enum(&self.item_enum.ident, &repr_type, variants),
            EnumPassableKind::WireEncoded { enum_name } => {
                generate_passable_for_wire_encoded(enum_name)
            }
        }
    }

    fn passable_kind(&self) -> EnumPassableKind<'a> {
        let enum_shape = EnumDataShape::new(self.item_enum);
        match enum_shape.passable_category() {
            PassableCategory::Scalar => EnumPassableKind::Scalar {
                repr_type: enum_shape.effective_integer_repr(),
                variants: &self.item_enum.variants,
            },
            _ => EnumPassableKind::WireEncoded {
                enum_name: &self.item_enum.ident,
            },
        }
    }
}

fn generate_passable_for_scalar_enum(
    enum_name: &syn::Ident,
    repr_type: &syn::Ident,
    variants: &syn::punctuated::Punctuated<syn::Variant, syn::Token![,]>,
) -> proc_macro2::TokenStream {
    let match_arms: Vec<proc_macro2::TokenStream> = variants
        .iter()
        .map(|variant| {
            let variant_name = &variant.ident;
            quote! { value if value == (#enum_name::#variant_name as #repr_type) => #enum_name::#variant_name }
        })
        .collect();

    quote! {
        unsafe impl ::boltffi::__private::Passable for #enum_name {
            type In = #repr_type;
            type Out = #repr_type;

            fn pack(self) -> #repr_type {
                self as #repr_type
            }

            unsafe fn unpack(input: #repr_type) -> Self {
                match input {
                    #(#match_arms,)*
                    _ => ::core::panic!("invalid enum discriminant"),
                }
            }
        }

        impl ::boltffi::__private::VecTransport for #enum_name {
            fn pack_vec(vec: Vec<#enum_name>) -> ::boltffi::__private::FfiBuf {
                ::boltffi::__private::FfiBuf::from_vec(vec)
            }
            unsafe fn unpack_vec(ptr: *const u8, byte_len: usize) -> Vec<#enum_name> {
                let count = byte_len / ::core::mem::size_of::<#enum_name>();
                unsafe { ::core::slice::from_raw_parts(ptr as *const #enum_name, count) }.to_vec()
            }
        }
    }
}

fn generate_passable_for_wire_encoded(name: &syn::Ident) -> proc_macro2::TokenStream {
    quote! {
        unsafe impl ::boltffi::__private::WirePassable for #name {}

        impl ::boltffi::__private::VecTransport for #name {
            fn pack_vec(vec: Vec<#name>) -> ::boltffi::__private::FfiBuf {
                ::boltffi::__private::FfiBuf::wire_encode(&vec)
            }
            unsafe fn unpack_vec(ptr: *const u8, byte_len: usize) -> Vec<#name> {
                let bytes = unsafe { ::core::slice::from_raw_parts(ptr, byte_len) };
                ::boltffi::__private::wire::decode(bytes).expect("VecTransport::unpack: wire decode failed")
            }
        }
    }
}

fn generate_passable_for_blittable_struct(struct_name: &syn::Ident) -> proc_macro2::TokenStream {
    quote! {
        unsafe impl ::boltffi::__private::Passable for #struct_name {
            type In = #struct_name;
            type Out = #struct_name;

            fn pack(self) -> #struct_name {
                self
            }

            unsafe fn unpack(input: #struct_name) -> Self {
                input
            }
        }

        impl ::boltffi::__private::VecTransport for #struct_name {
            fn pack_vec(vec: Vec<#struct_name>) -> ::boltffi::__private::FfiBuf {
                ::boltffi::__private::FfiBuf::from_vec(vec)
            }
            unsafe fn unpack_vec(ptr: *const u8, byte_len: usize) -> Vec<#struct_name> {
                let count = byte_len / ::core::mem::size_of::<#struct_name>();
                unsafe { ::core::slice::from_raw_parts(ptr as *const #struct_name, count) }.to_vec()
            }
        }
    }
}
