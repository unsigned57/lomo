use crate::exports::common::{
    exported_methods, impl_type_name, is_factory_constructor, is_result_of_self_type_path,
};

use boltffi_ffi_rules::naming;
use proc_macro::TokenStream;
use quote::quote;
use syn::{FnArg, ReturnType, Type};

use crate::index::CrateIndex;
use crate::index::callback_traits::CallbackTraitRegistry;
use crate::index::custom_types;
use crate::lowering::params::{FfiParams, transform_method_params};
use crate::lowering::returns::lower::encoded_return_body;
use crate::lowering::returns::model::{ResolvedReturn, ReturnLoweringContext};

enum RecordMethodKind {
    Constructor,
    InstanceRef,
    InstanceMut,
    Static,
}

struct RecordImplExpansion {
    input: syn::ItemImpl,
    type_name: syn::Ident,
    record_name: String,
    return_lowering: ReturnLoweringContext<'static>,
    callback_registry: CallbackTraitRegistry,
}

struct RecordMethodDescriptor {
    kind: RecordMethodKind,
    resolved_output: ReturnType,
}

struct RecordMethodExpansion<'a> {
    type_name: &'a syn::Ident,
    record_name: &'a str,
    method: &'a syn::ImplItemFn,
    descriptor: RecordMethodDescriptor,
    return_lowering: &'a ReturnLoweringContext<'a>,
    callback_registry: &'a CallbackTraitRegistry,
}

impl RecordMethodDescriptor {
    fn from_method(method: &syn::ImplItemFn, type_name: &syn::Ident) -> Self {
        let kind = match method.sig.inputs.first() {
            Some(FnArg::Receiver(receiver)) => {
                if receiver.mutability.is_some() {
                    RecordMethodKind::InstanceMut
                } else {
                    RecordMethodKind::InstanceRef
                }
            }
            _ => {
                if is_factory_constructor(method, type_name) {
                    RecordMethodKind::Constructor
                } else {
                    RecordMethodKind::Static
                }
            }
        };

        let resolved_output = Self::resolve_return_type(&method.sig.output, type_name);
        Self {
            kind,
            resolved_output,
        }
    }

    fn resolve_return_type(output: &ReturnType, type_name: &syn::Ident) -> ReturnType {
        match output {
            ReturnType::Default => ReturnType::Default,
            ReturnType::Type(arrow, rust_type) => {
                let resolved = Self::resolve_type(rust_type, type_name);
                ReturnType::Type(*arrow, Box::new(resolved))
            }
        }
    }

    fn resolve_type(rust_type: &Type, type_name: &syn::Ident) -> Type {
        match rust_type {
            Type::Path(type_path) => {
                let mut resolved_path = type_path.clone();
                resolved_path.path.segments.iter_mut().for_each(|segment| {
                    if segment.ident == "Self" {
                        segment.ident = type_name.clone();
                    }
                    if let syn::PathArguments::AngleBracketed(arguments) = &mut segment.arguments {
                        arguments.args.iter_mut().for_each(|argument| {
                            if let syn::GenericArgument::Type(inner_type) = argument {
                                *inner_type = Self::resolve_type(inner_type, type_name);
                            }
                        });
                    }
                });
                Type::Path(resolved_path)
            }
            Type::Reference(reference) => {
                let mut resolved_reference = reference.clone();
                resolved_reference.elem = Box::new(Self::resolve_type(&reference.elem, type_name));
                Type::Reference(resolved_reference)
            }
            Type::Tuple(tuple) => {
                let mut resolved_tuple = tuple.clone();
                resolved_tuple
                    .elems
                    .iter_mut()
                    .for_each(|element| *element = Self::resolve_type(element, type_name));
                Type::Tuple(resolved_tuple)
            }
            _ => rust_type.clone(),
        }
    }
}

impl RecordImplExpansion {
    fn new(input: syn::ItemImpl) -> syn::Result<Self> {
        let type_name = impl_type_name(&input).ok_or_else(|| {
            syn::Error::new_spanned(&input, "#[data(impl)] requires a named type")
        })?;
        let crate_index = CrateIndex::for_current_crate()?;
        let class_types = Box::leak(Box::new(crate_index.class_types().clone()));
        let custom_types = Box::leak(Box::new(crate_index.custom_types().clone()));
        let callback_registry = crate_index.callback_traits().clone();
        let data_types = Box::leak(Box::new(crate_index.data_types().clone()));
        let return_lowering = ReturnLoweringContext::new(custom_types, data_types, class_types);
        let record_name = type_name.to_string();

        Ok(Self {
            input,
            type_name,
            record_name,
            return_lowering,
            callback_registry,
        })
    }

    fn render(self) -> proc_macro2::TokenStream {
        let input = self.input;
        let original_impl = quote! { #input };
        let method_exports = exported_methods(&input)
            .filter_map(|method| {
                RecordMethodExpansion::new(
                    &self.type_name,
                    &self.record_name,
                    method,
                    &self.return_lowering,
                    &self.callback_registry,
                )
                .render()
            })
            .collect::<Vec<_>>();

        quote! {
            #original_impl
            #(#method_exports)*
        }
    }
}

impl<'a> RecordMethodExpansion<'a> {
    fn new(
        type_name: &'a syn::Ident,
        record_name: &'a str,
        method: &'a syn::ImplItemFn,
        return_lowering: &'a ReturnLoweringContext<'a>,
        callback_registry: &'a CallbackTraitRegistry,
    ) -> Self {
        Self {
            type_name,
            record_name,
            method,
            descriptor: RecordMethodDescriptor::from_method(method, type_name),
            return_lowering,
            callback_registry,
        }
    }

    fn render(&self) -> Option<proc_macro2::TokenStream> {
        match self.descriptor.kind {
            RecordMethodKind::Constructor => self.render_constructor(),
            RecordMethodKind::InstanceRef => self.render_instance(false),
            RecordMethodKind::InstanceMut => self.render_instance(true),
            RecordMethodKind::Static => self.render_static(),
        }
    }

    fn render_constructor(&self) -> Option<proc_macro2::TokenStream> {
        generate_record_constructor_export(
            self.type_name,
            self.record_name,
            self.method,
            self.return_lowering,
            self.callback_registry,
        )
    }

    fn render_instance(&self, is_mut: bool) -> Option<proc_macro2::TokenStream> {
        generate_record_instance_export(
            self.type_name,
            self.record_name,
            self.method,
            is_mut,
            self.return_lowering,
            self.callback_registry,
        )
    }

    fn render_static(&self) -> Option<proc_macro2::TokenStream> {
        generate_record_static_export(
            self.type_name,
            self.record_name,
            self.method,
            self.return_lowering,
            self.callback_registry,
        )
    }
}

fn generate_record_constructor_export(
    type_name: &syn::Ident,
    record_name: &str,
    method: &syn::ImplItemFn,
    return_lowering: &ReturnLoweringContext<'_>,
    callback_registry: &CallbackTraitRegistry,
) -> Option<proc_macro2::TokenStream> {
    let custom_types = return_lowering.custom_types();
    let method_name = &method.sig.ident;
    let export_name = if method_name == "new" {
        naming::class_ffi_new(record_name)
    } else {
        naming::method_ffi_name(record_name, &method_name.to_string())
    };
    let export_name = syn::Ident::new(export_name.as_str(), method_name.span());

    let method_descriptor = RecordMethodDescriptor::from_method(method, type_name);
    let return_abi = return_lowering.lower_output(&method_descriptor.resolved_output);
    let on_error = return_abi.invalid_arg_early_return_statement();

    let inputs = method.sig.inputs.iter().cloned();
    let FfiParams {
        ffi_params,
        conversions,
        call_args,
    } = transform_method_params(inputs, return_lowering, callback_registry, &on_error);

    let call_expr = quote! { #type_name::#method_name(#(#call_args),*) };

    let is_fallible = matches!(
        &method_descriptor.resolved_output,
        ReturnType::Type(_, ty)
            if matches!(ty.as_ref(), Type::Path(tp) if is_result_of_self_type_path(&tp.path, type_name))
    );

    generate_value_return_export(
        &export_name,
        &ffi_params,
        &conversions,
        call_expr,
        is_fallible,
        &return_abi,
        custom_types,
    )
}

fn generate_record_instance_export(
    type_name: &syn::Ident,
    record_name: &str,
    method: &syn::ImplItemFn,
    is_mut: bool,
    return_lowering: &ReturnLoweringContext<'_>,
    callback_registry: &CallbackTraitRegistry,
) -> Option<proc_macro2::TokenStream> {
    let custom_types = return_lowering.custom_types();
    let method_name = &method.sig.ident;
    let export_name = naming::method_ffi_name(record_name, &method_name.to_string());
    let export_name = syn::Ident::new(export_name.as_str(), method_name.span());

    let method_descriptor = RecordMethodDescriptor::from_method(method, type_name);
    let return_abi = return_lowering.lower_output(&method_descriptor.resolved_output);
    let on_error = return_abi.invalid_arg_early_return_statement();

    let self_ident = syn::Ident::new("self_value", method_name.span());
    let self_input: syn::FnArg = syn::parse_quote!(#self_ident: #type_name);
    let all_inputs = std::iter::once(self_input).chain(method.sig.inputs.iter().skip(1).cloned());
    let FfiParams {
        ffi_params: all_ffi_params,
        conversions: mut all_conversions,
        call_args,
    } = transform_method_params(all_inputs, return_lowering, callback_registry, &on_error);

    if is_mut {
        all_conversions.push(quote! {
            let mut #self_ident = #self_ident;
        });
    }

    let method_call_args = call_args.into_iter().skip(1).collect::<Vec<_>>();
    let call_expr = quote! { #self_ident.#method_name(#(#method_call_args),*) };

    if is_mut {
        return generate_mut_instance_export(
            &export_name,
            type_name,
            &all_ffi_params,
            &all_conversions,
            call_expr,
            &return_abi,
            method_name,
        );
    }

    let (body, return_type, is_wire_encoded) = build_return_arms(
        &return_abi,
        call_expr,
        &all_conversions,
        custom_types,
        method_name,
    )?;

    Some(emit_ffi_function(
        &export_name,
        &all_ffi_params,
        body,
        return_type,
        is_wire_encoded,
    ))
}

fn generate_mut_instance_export(
    export_name: &syn::Ident,
    type_name: &syn::Ident,
    ffi_params: &[proc_macro2::TokenStream],
    conversions: &[proc_macro2::TokenStream],
    call_expr: proc_macro2::TokenStream,
    return_abi: &ResolvedReturn,
    method_name: &syn::Ident,
) -> Option<proc_macro2::TokenStream> {
    if return_abi.is_unit() {
        let body = quote! {
            #(#conversions)*
            #call_expr;
            ::boltffi::__private::Passable::pack(self_value)
        };
        let return_type = quote! { -> <#type_name as ::boltffi::__private::Passable>::Out };

        Some(emit_ffi_function(
            export_name,
            ffi_params,
            body,
            return_type,
            false,
        ))
    } else {
        Some(
            syn::Error::new_spanned(
                method_name,
                "&mut self methods on records that return values are not yet supported",
            )
            .to_compile_error(),
        )
    }
}

fn generate_record_static_export(
    type_name: &syn::Ident,
    record_name: &str,
    method: &syn::ImplItemFn,
    return_lowering: &ReturnLoweringContext<'_>,
    callback_registry: &CallbackTraitRegistry,
) -> Option<proc_macro2::TokenStream> {
    let custom_types = return_lowering.custom_types();
    let method_name = &method.sig.ident;
    let export_name = naming::method_ffi_name(record_name, &method_name.to_string());
    let export_name = syn::Ident::new(export_name.as_str(), method_name.span());

    let method_descriptor = RecordMethodDescriptor::from_method(method, type_name);
    let return_abi = return_lowering.lower_output(&method_descriptor.resolved_output);
    let on_error = return_abi.invalid_arg_early_return_statement();

    let all_inputs = method.sig.inputs.iter().cloned();
    let FfiParams {
        ffi_params,
        conversions,
        call_args,
    } = transform_method_params(all_inputs, return_lowering, callback_registry, &on_error);

    let call_expr = quote! { #type_name::#method_name(#(#call_args),*) };

    let (body, return_type, is_wire_encoded) = build_return_arms(
        &return_abi,
        call_expr,
        &conversions,
        custom_types,
        method_name,
    )?;

    Some(emit_ffi_function(
        &export_name,
        &ffi_params,
        body,
        return_type,
        is_wire_encoded,
    ))
}

fn generate_value_return_export(
    export_name: &syn::Ident,
    ffi_params: &[proc_macro2::TokenStream],
    conversions: &[proc_macro2::TokenStream],
    call_expr: proc_macro2::TokenStream,
    is_fallible: bool,
    return_abi: &ResolvedReturn,
    custom_types: &custom_types::CustomTypeRegistry,
) -> Option<proc_macro2::TokenStream> {
    let on_error = return_abi.invalid_arg_early_return_statement();

    let unwrapped_call = if is_fallible {
        quote! {
            match #call_expr {
                Ok(value) => value,
                Err(error) => {
                    ::boltffi::__private::set_last_error(format!("{error:?}"));
                    return #on_error;
                }
            }
        }
    } else {
        call_expr.clone()
    };

    let passable_call = if conversions.is_empty() {
        unwrapped_call
    } else {
        quote! {
            #(#conversions)*
            #unwrapped_call
        }
    };

    if return_abi.is_passable_value() {
        let rust_type = return_abi.rust_type();
        let body = quote! {
            ::boltffi::__private::Passable::pack({ #passable_call })
        };
        let return_type = quote! { -> <#rust_type as ::boltffi::__private::Passable>::Out };

        Some(emit_ffi_function(
            export_name,
            ffi_params,
            body,
            return_type,
            false,
        ))
    } else if let Some(strategy) = return_abi.encoded_return_strategy() {
        let inner_ty = return_abi.rust_type();
        let result_ident = syn::Ident::new("result", export_name.span());
        let body = encoded_return_body(
            inner_ty,
            strategy,
            &result_ident,
            call_expr,
            conversions,
            custom_types,
        );
        Some(emit_ffi_function(
            export_name,
            ffi_params,
            body,
            quote! { -> ::boltffi::__private::FfiBuf },
            true,
        ))
    } else {
        Some(
            syn::Error::new_spanned(
                export_name,
                "record constructors must return Self or Result<Self, E>",
            )
            .to_compile_error(),
        )
    }
}

fn build_return_arms(
    return_abi: &ResolvedReturn,
    call_expr: proc_macro2::TokenStream,
    conversions: &[proc_macro2::TokenStream],
    custom_types: &custom_types::CustomTypeRegistry,
    method_name: &syn::Ident,
) -> Option<(proc_macro2::TokenStream, proc_macro2::TokenStream, bool)> {
    let has_conversions = !conversions.is_empty();

    if return_abi.is_unit() {
        let body = if has_conversions {
            quote! {
                #(#conversions)*
                #call_expr;
                ::boltffi::__private::FfiStatus::OK
            }
        } else {
            quote! {
                #call_expr;
                ::boltffi::__private::FfiStatus::OK
            }
        };
        Some((body, quote! { -> ::boltffi::__private::FfiStatus }, false))
    } else if return_abi.is_primitive_scalar() {
        let rust_type = return_abi.rust_type();
        let fn_output = quote! { -> #rust_type };
        let body = if has_conversions {
            quote! {
                #(#conversions)*
                #call_expr
            }
        } else {
            call_expr
        };
        Some((body, fn_output, false))
    } else if let Some(strategy) = return_abi.encoded_return_strategy() {
        let inner_ty = return_abi.rust_type();
        let result_ident = syn::Ident::new("result", method_name.span());
        let body = encoded_return_body(
            inner_ty,
            strategy,
            &result_ident,
            call_expr,
            conversions,
            custom_types,
        );
        Some((body, quote! { -> ::boltffi::__private::FfiBuf }, true))
    } else if return_abi.is_passable_value() {
        let rust_type = return_abi.rust_type();
        let body = if has_conversions {
            quote! {
                #(#conversions)*
                ::boltffi::__private::Passable::pack(#call_expr)
            }
        } else {
            quote! {
                ::boltffi::__private::Passable::pack(#call_expr)
            }
        };
        let return_type = quote! { -> <#rust_type as ::boltffi::__private::Passable>::Out };
        Some((body, return_type, false))
    } else {
        None
    }
}

fn emit_ffi_function(
    export_name: &syn::Ident,
    ffi_params: &[proc_macro2::TokenStream],
    body: proc_macro2::TokenStream,
    return_type: proc_macro2::TokenStream,
    is_wire_encoded: bool,
) -> proc_macro2::TokenStream {
    if is_wire_encoded {
        return emit_encoded_ffi_function(export_name, ffi_params, body);
    }

    if ffi_params.is_empty() {
        quote! {
            #[unsafe(no_mangle)]
            pub extern "C" fn #export_name() #return_type {
                #body
            }
        }
    } else {
        quote! {
            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn #export_name(#(#ffi_params),*) #return_type {
                #body
            }
        }
    }
}

fn emit_encoded_ffi_function(
    export_name: &syn::Ident,
    ffi_params: &[proc_macro2::TokenStream],
    body: proc_macro2::TokenStream,
) -> proc_macro2::TokenStream {
    if ffi_params.is_empty() {
        quote! {
            #[cfg(target_arch = "wasm32")]
            #[unsafe(no_mangle)]
            pub extern "C" fn #export_name() -> u64 {
                let __boltffi_buf: ::boltffi::__private::FfiBuf = { #body };
                __boltffi_buf.into_packed()
            }

            #[cfg(not(target_arch = "wasm32"))]
            #[unsafe(no_mangle)]
            pub extern "C" fn #export_name() -> ::boltffi::__private::FfiBuf {
                #body
            }
        }
    } else {
        quote! {
            #[cfg(target_arch = "wasm32")]
            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn #export_name(
                #(#ffi_params),*
            ) -> u64 {
                let __boltffi_buf: ::boltffi::__private::FfiBuf = { #body };
                __boltffi_buf.into_packed()
            }

            #[cfg(not(target_arch = "wasm32"))]
            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn #export_name(
                #(#ffi_params),*
            ) -> ::boltffi::__private::FfiBuf {
                #body
            }
        }
    }
}

pub fn data_impl_block(item: TokenStream) -> TokenStream {
    let input = match syn::parse::<syn::ItemImpl>(item) {
        Ok(parsed) => parsed,
        Err(error) => return error.to_compile_error().into(),
    };

    RecordImplExpansion::new(input)
        .map(RecordImplExpansion::render)
        .unwrap_or_else(|error| error.to_compile_error())
        .into()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::index::callback_traits::CallbackTraitRegistry;
    use crate::index::class_types::ClassTypeRegistry;
    use crate::index::custom_types::CustomTypeRegistry;
    use crate::index::data_types::{DataTypeCategory, DataTypeRegistry};
    use crate::lowering::returns::model::ReturnLoweringContext;

    fn parse_impl(code: &str) -> syn::ItemImpl {
        syn::parse_str(code).expect("failed to parse impl block")
    }

    fn return_lowering() -> ReturnLoweringContext<'static> {
        let class_types = Box::leak(Box::new(ClassTypeRegistry::default()));
        let custom_types = Box::leak(Box::new(CustomTypeRegistry::default()));
        let data_types = Box::leak(Box::new(DataTypeRegistry::with_entries(&[
            ("UserProfile", DataTypeCategory::WireEncoded),
            ("Filter", DataTypeCategory::WireEncoded),
        ])));
        ReturnLoweringContext::new(custom_types, data_types, class_types)
    }

    fn callback_registry() -> &'static CallbackTraitRegistry {
        Box::leak(Box::new(CallbackTraitRegistry::default()))
    }

    #[test]
    fn record_instance_method_with_borrowed_wire_params_lowers_to_buffers_and_storage() {
        let impl_block = parse_impl(
            r#"
            impl Inventory {
                pub fn summarize(&self, profile: &UserProfile, filter: &Filter) -> String {
                    let _ = (profile, filter);
                    String::new()
                }
            }
            "#,
        );
        let method = impl_block
            .items
            .iter()
            .find_map(|item| match item {
                syn::ImplItem::Fn(method) => Some(method),
                _ => None,
            })
            .expect("record instance method should exist");
        let type_name = syn::Ident::new("Inventory", proc_macro2::Span::call_site());

        let generated = generate_record_instance_export(
            &type_name,
            "Inventory",
            method,
            false,
            &return_lowering(),
            callback_registry(),
        )
        .expect("record instance export should be generated")
        .to_string();

        assert!(generated.contains("profile_storage_ptr : * const u8"));
        assert!(generated.contains("profile_storage_len : usize"));
        assert!(generated.contains("filter_storage_ptr : * const u8"));
        assert!(generated.contains("filter_storage_len : usize"));
        assert!(generated.contains("let profile_storage : UserProfile"));
        assert!(generated.contains("let filter_storage : Filter"));
        assert!(generated.contains("let profile = & profile_storage"));
        assert!(generated.contains("let filter = & filter_storage"));
        assert!(!generated.contains("profile : & UserProfile"));
        assert!(!generated.contains("filter : & Filter"));
    }
}
