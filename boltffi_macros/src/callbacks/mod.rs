use boltffi_ffi_rules::naming;

pub(crate) mod trait_export;

pub(crate) fn snake_case_ident(ident: &syn::Ident) -> syn::Ident {
    syn::Ident::new(&naming::to_snake_case(&ident.to_string()), ident.span())
}
