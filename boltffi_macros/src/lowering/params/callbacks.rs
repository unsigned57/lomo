use proc_macro2::Span;
use quote::quote;
use syn::Ident;

use boltffi_ffi_rules::callback as callback_naming;
use boltffi_ffi_rules::primitive::Primitive;
use boltffi_ffi_rules::transport::{
    EncodedReturnStrategy, ReturnInvocationContext, ReturnPlatform, ValueReturnMethod,
};

use super::ParamLoweringState;
use super::transform::is_primitive_vec_inner;
use crate::index::CrateIndex;
use crate::index::callback_traits::CallbackTraitRegistry;
use crate::index::custom_types::{
    contains_custom_types, from_wire_expr_owned, to_wire_expr_owned, wire_type_for,
};
use crate::lowering::returns::model::{ResolvedReturn, ReturnLoweringContext, ValueReturnStrategy};

struct CallbackBindingBuilder<'a> {
    return_lowering: &'a ReturnLoweringContext<'a>,
    callback_registry: &'a CallbackTraitRegistry,
}

pub(super) enum TraitObjectParamKind {
    Boxed,
    Arc,
    OptionBoxed,
    OptionArc,
}

struct ImplTraitResolution {
    foreign_type: proc_macro2::TokenStream,
    error: Option<proc_macro2::TokenStream>,
}

struct CallbackArgLowering {
    ffi_arg_types: Vec<proc_macro2::TokenStream>,
    prelude: proc_macro2::TokenStream,
    call_args: Vec<proc_macro2::TokenStream>,
}

enum CallbackTypeDescriptor {
    Void,
    String,
    Primitive(Primitive),
    Vec(Box<CallbackTypeDescriptor>),
    Option(Box<CallbackTypeDescriptor>),
    Result {
        ok: Box<CallbackTypeDescriptor>,
        err: Box<CallbackTypeDescriptor>,
    },
    Slice(Box<CallbackTypeDescriptor>),
    Named(String),
}

impl CallbackTypeDescriptor {
    fn parse(rust_type: &syn::Type) -> Self {
        match rust_type {
            syn::Type::Tuple(tuple) if tuple.elems.is_empty() => Self::Void,
            syn::Type::Reference(reference) => Self::parse_reference(reference),
            syn::Type::Slice(slice) => Self::Slice(Box::new(Self::parse(&slice.elem))),
            syn::Type::Path(type_path) => Self::parse_path(&type_path.path),
            _ => Self::Named(quote!(#rust_type).to_string().replace(' ', "")),
        }
    }

    fn parse_reference(reference: &syn::TypeReference) -> Self {
        match reference.elem.as_ref() {
            syn::Type::Path(type_path)
                if Self::path_last_segment_name(&type_path.path).as_deref() == Some("str") =>
            {
                Self::String
            }
            syn::Type::Slice(slice) => Self::Slice(Box::new(Self::parse(&slice.elem))),
            other => Self::parse(other),
        }
    }

    fn parse_path(type_path: &syn::Path) -> Self {
        let Some(last_segment) = type_path.segments.last() else {
            return Self::Named(String::new());
        };

        let type_name = last_segment.ident.to_string();
        if let Ok(primitive) = type_name.parse::<Primitive>() {
            return Self::Primitive(primitive);
        }

        match type_name.as_str() {
            "String" => Self::String,
            "Vec" => Self::parse_single_argument(last_segment)
                .map(|inner| Self::Vec(Box::new(inner)))
                .unwrap_or_else(|| Self::Named(type_name)),
            "Option" => Self::parse_single_argument(last_segment)
                .map(|inner| Self::Option(Box::new(inner)))
                .unwrap_or_else(|| Self::Named(type_name)),
            "Result" => {
                Self::parse_result_arguments(last_segment).unwrap_or(Self::Named(type_name))
            }
            _ => Self::Named(type_name),
        }
    }

    fn parse_single_argument(segment: &syn::PathSegment) -> Option<Self> {
        Self::generic_type_arguments(segment)
            .and_then(|mut arguments| (arguments.len() == 1).then(|| arguments.remove(0)))
            .map(|rust_type| Self::parse(&rust_type))
    }

    fn parse_result_arguments(segment: &syn::PathSegment) -> Option<Self> {
        let mut arguments = Self::generic_type_arguments(segment)?;
        (arguments.len() == 2).then(|| Self::Result {
            ok: Box::new(Self::parse(&arguments.remove(0))),
            err: Box::new(Self::parse(&arguments.remove(0))),
        })
    }

    fn generic_type_arguments(segment: &syn::PathSegment) -> Option<Vec<syn::Type>> {
        match &segment.arguments {
            syn::PathArguments::AngleBracketed(arguments) => Some(
                arguments
                    .args
                    .iter()
                    .filter_map(|argument| match argument {
                        syn::GenericArgument::Type(rust_type) => Some(rust_type.clone()),
                        _ => None,
                    })
                    .collect(),
            ),
            _ => None,
        }
    }

    fn path_last_segment_name(type_path: &syn::Path) -> Option<String> {
        type_path
            .segments
            .last()
            .map(|segment| segment.ident.to_string())
    }

    fn into_type_id(self) -> callback_naming::TypeId {
        match self {
            Self::Void => callback_naming::TypeId::Void,
            Self::String => callback_naming::TypeId::String,
            Self::Primitive(primitive) => callback_naming::TypeId::Primitive(primitive),
            Self::Vec(inner) => callback_naming::TypeId::Vec(Box::new(inner.into_type_id())),
            Self::Option(inner) => callback_naming::TypeId::Option(Box::new(inner.into_type_id())),
            Self::Result { ok, err } => callback_naming::TypeId::Result {
                ok: Box::new(ok.into_type_id()),
                err: Box::new(err.into_type_id()),
            },
            Self::Slice(inner) => callback_naming::TypeId::Slice(Box::new(inner.into_type_id())),
            Self::Named(name) => callback_naming::TypeId::Named(name),
        }
    }
}

impl<'a> CallbackBindingBuilder<'a> {
    fn new(
        return_lowering: &'a ReturnLoweringContext<'a>,
        callback_registry: &'a CallbackTraitRegistry,
    ) -> Self {
        Self {
            return_lowering,
            callback_registry,
        }
    }

    fn callback_arg_wire_value(
        &self,
        arg_type: &syn::Type,
        arg_name: &Ident,
        wire_name: &Ident,
        index: usize,
        param_name: &Ident,
    ) -> proc_macro2::TokenStream {
        if contains_custom_types(arg_type, self.return_lowering.custom_types()) {
            let wire_type = wire_type_for(arg_type, self.return_lowering.custom_types());
            let wire_value_ident = Ident::new(&format!("__wire_value{}", index), param_name.span());
            let to_wire_conversion =
                to_wire_expr_owned(arg_type, self.return_lowering.custom_types(), arg_name);
            quote! {
                let #wire_value_ident: #wire_type = { #to_wire_conversion };
                let #wire_name = ::boltffi::__private::wire::encode(&#wire_value_ident);
            }
        } else {
            quote! {
                let #wire_name = ::boltffi::__private::wire::encode(&#arg_name);
            }
        }
    }

    fn lower_callback_arg(
        &self,
        arg_type: &syn::Type,
        arg_name: &Ident,
        index: usize,
        callback_name: &Ident,
    ) -> CallbackArgLowering {
        if matches!(
            self.return_lowering
                .lower_type(arg_type)
                .value_return_strategy(),
            ValueReturnStrategy::Scalar(_)
        ) {
            return CallbackArgLowering {
                ffi_arg_types: vec![quote! { <#arg_type as ::boltffi::__private::Passable>::Out }],
                prelude: quote! {},
                call_args: vec![
                    quote! { <#arg_type as ::boltffi::__private::Passable>::pack(#arg_name) },
                ],
            };
        }

        let arg_type_string = quote!(#arg_type).to_string().replace(' ', "");
        if is_primitive_vec_inner(&arg_type_string) {
            return CallbackArgLowering {
                ffi_arg_types: vec![quote! { #arg_type }],
                prelude: quote! {},
                call_args: vec![quote! { #arg_name }],
            };
        }

        let wire_name = Ident::new(&format!("__wire{}", index), callback_name.span());
        let prelude =
            self.callback_arg_wire_value(arg_type, arg_name, &wire_name, index, callback_name);

        CallbackArgLowering {
            ffi_arg_types: vec![quote! { *const u8 }, quote! { usize }],
            prelude,
            call_args: vec![quote! { #wire_name.as_ptr() }, quote! { #wire_name.len() }],
        }
    }

    fn generate_wasm_closure_codegen(
        &self,
        name: &Ident,
        arg_types: &[syn::Type],
        returns: Option<&syn::Type>,
        ffi_callback_args: &[proc_macro2::TokenStream],
    ) -> proc_macro2::TokenStream {
        let type_ids: Vec<callback_naming::TypeId> = arg_types
            .iter()
            .map(|arg_type| CallbackTypeDescriptor::parse(arg_type).into_type_id())
            .collect();

        let return_type_id = returns
            .map(|return_type| CallbackTypeDescriptor::parse(return_type).into_type_id())
            .unwrap_or(callback_naming::TypeId::Void);

        let callback_id_snake =
            callback_naming::closure_callback_id_snake(&type_ids, &return_type_id);
        let call_import_name = callback_naming::callback_wasm_import_call(&callback_id_snake);
        let free_import_name = callback_naming::callback_wasm_import_free(&callback_id_snake);

        let call_import_ident = Ident::new(&call_import_name, name.span());
        let free_import_ident = Ident::new(&free_import_name, name.span());
        let owner_name = Ident::new(&format!("__{}_owner", name), name.span());

        let (arg_names, wire_values, call_args) = arg_types
            .iter()
            .enumerate()
            .map(|(index, arg_type)| {
                let arg_name = Ident::new(&format!("__arg{}", index), name.span());
                let lowering = self.lower_callback_arg(arg_type, &arg_name, index, name);
                (arg_name, lowering.prelude, lowering.call_args)
            })
            .fold(
                (Vec::new(), Vec::new(), Vec::new()),
                |(mut arg_names, mut wire_values, mut call_args),
                 (arg_name, wire_value, lowered_call_args)| {
                    arg_names.push(arg_name);
                    wire_values.push(wire_value);
                    lowered_call_args
                        .into_iter()
                        .for_each(|call_arg| call_args.push(call_arg));
                    (arg_names, wire_values, call_args)
                },
            );

        let closure_params: Vec<proc_macro2::TokenStream> = arg_names
            .iter()
            .zip(arg_types.iter())
            .map(|(arg_name, arg_type)| quote! { #arg_name: #arg_type })
            .collect();

        let closure_params_tokens = if closure_params.is_empty() {
            quote! {}
        } else {
            let first_param = &closure_params[0];
            let rest_params = &closure_params[1..];
            quote! { #first_param #(, #rest_params)* }
        };

        let extern_params: Vec<proc_macro2::TokenStream> = ffi_callback_args
            .iter()
            .enumerate()
            .map(|(index, ffi_callback_arg)| {
                let param_name = Ident::new(&format!("__p{}", index), name.span());
                quote! { #param_name: #ffi_callback_arg }
            })
            .collect();

        let extern_params_tokens = if extern_params.is_empty() {
            quote! {}
        } else {
            let first_param = &extern_params[0];
            let rest_params = &extern_params[1..];
            quote! { , #first_param #(, #rest_params)* }
        };

        let return_abi = returns.map(|return_type| self.return_lowering.lower_type(return_type));
        let wasm_return_method = return_abi.as_ref().map(|return_abi| {
            return_abi
                .value_return_method(ReturnInvocationContext::InlineClosure, ReturnPlatform::Wasm)
        });
        let packed_utf8_return = return_abi.as_ref().is_some_and(|return_abi| {
            return_abi.encoded_return_strategy() == Some(EncodedReturnStrategy::Utf8String)
        });

        if packed_utf8_return {
            let return_type = returns.unwrap();

            quote! {
                #[cfg(target_arch = "wasm32")]
                let #name = {
                    #[allow(improper_ctypes)]
                    unsafe extern "C" {
                        fn #call_import_ident(handle: u32 #extern_params_tokens) -> u64;
                        fn #free_import_ident(handle: u32);
                    }
                    let #owner_name = ::boltffi::__private::WasmCallbackOwner::new(#name, #free_import_ident);
                    move |#closure_params_tokens| -> #return_type {
                        #(#wire_values)*
                        let packed = unsafe { #call_import_ident(#owner_name.handle() #(, #call_args)*) };
                        unsafe { ::boltffi::__private::take_packed_utf8_string(packed) }
                    }
                };
            }
        } else if matches!(
            wasm_return_method,
            None | Some(ValueReturnMethod::DirectReturn)
        ) {
            let ffi_return_type = match (returns, return_abi.as_ref()) {
                (Some(return_type), Some(return_abi)) if is_passable_return(return_abi) => {
                    quote! { -> <#return_type as ::boltffi::__private::Passable>::Out }
                }
                (Some(return_type), _) => quote! { -> #return_type },
                (None, _) => quote! {},
            };
            let closure_return_type = returns
                .map(|return_type| quote! { -> #return_type })
                .unwrap_or_default();
            let direct_return = match (returns, return_abi.as_ref()) {
                (Some(return_type), Some(return_abi)) if is_passable_return(return_abi) => quote! {
                    unsafe {
                        <#return_type as ::boltffi::__private::Passable>::unpack(
                            #call_import_ident(#owner_name.handle() #(, #call_args)*)
                        )
                    }
                },
                _ => {
                    quote! { unsafe { #call_import_ident(#owner_name.handle() #(, #call_args)*) } }
                }
            };

            quote! {
                #[cfg(target_arch = "wasm32")]
                let #name = {
                    #[allow(improper_ctypes)]
                    unsafe extern "C" {
                        fn #call_import_ident(handle: u32 #extern_params_tokens) #ffi_return_type;
                        fn #free_import_ident(handle: u32);
                    }
                    let #owner_name = ::boltffi::__private::WasmCallbackOwner::new(#name, #free_import_ident);
                    move |#closure_params_tokens| #closure_return_type {
                        #(#wire_values)*
                        #direct_return
                    }
                };
            }
        } else {
            let return_type = returns.unwrap();
            let from_wire = self.wire_decoded_callback_return_expr(return_type);

            quote! {
                #[cfg(target_arch = "wasm32")]
                let #name = {
                    #[allow(improper_ctypes)]
                    unsafe extern "C" {
                        fn #call_import_ident(handle: u32, out: *mut ::boltffi::__private::FfiBuf #extern_params_tokens);
                        fn #free_import_ident(handle: u32);
                    }
                    let #owner_name = ::boltffi::__private::WasmCallbackOwner::new(#name, #free_import_ident);
                    move |#closure_params_tokens| -> #return_type {
                        #(#wire_values)*
                        let mut __out_buf = ::boltffi::__private::FfiBuf::empty();
                        unsafe { #call_import_ident(#owner_name.handle(), &mut __out_buf #(, #call_args)*) };
                        let __result_bytes = unsafe {
                            ::core::slice::from_raw_parts(__out_buf.as_ptr(), __out_buf.len())
                        };
                        #from_wire
                    }
                };
            }
        }
    }

    fn impl_trait_resolution(&self, trait_path: &syn::Path) -> ImplTraitResolution {
        if let Some(resolution) = self.callback_registry.resolve(trait_path) {
            let foreign_path = resolution.foreign_path;
            if resolution.is_object_safe {
                return ImplTraitResolution {
                    foreign_type: quote! {
                        <dyn #trait_path as ::boltffi::__private::CallbackForeignType>::Foreign
                    },
                    error: None,
                };
            }
            return ImplTraitResolution {
                foreign_type: quote! { #foreign_path },
                error: None,
            };
        }

        let foreign_path = CrateIndex::for_current_crate()
            .map(|crate_index| crate_index.path_resolver().resolve_foreign_path(trait_path))
            .unwrap_or_else(|_| {
                let mut unresolved_foreign_path = trait_path.clone();
                if let Some(last_segment) = unresolved_foreign_path.segments.last_mut() {
                    last_segment.ident = syn::Ident::new(
                        &format!("Foreign{}", last_segment.ident),
                        last_segment.ident.span(),
                    );
                }
                unresolved_foreign_path
            });
        let trait_name = quote!(#trait_path).to_string();
        let message = format!(
            "boltffi: cannot resolve callback trait `impl {}`. If this is a cross-crate async callback, use the full module path or make the trait object-safe with #[async_trait], e.g. `impl crate::path::to::{}` or `Box<dyn {}>`.",
            trait_name, trait_name, trait_name
        );
        let message_lit = syn::LitStr::new(&message, Span::call_site());
        ImplTraitResolution {
            foreign_type: quote! { #foreign_path },
            error: Some(quote! { compile_error!(#message_lit); }),
        }
    }

    fn wire_decoded_callback_return_expr(
        &self,
        return_type: &syn::Type,
    ) -> proc_macro2::TokenStream {
        if contains_custom_types(return_type, self.return_lowering.custom_types()) {
            let wire_type = wire_type_for(return_type, self.return_lowering.custom_types());
            let wire_result_ident = Ident::new("__wire_result", Span::call_site());
            let from_wire_conversion = from_wire_expr_owned(
                return_type,
                self.return_lowering.custom_types(),
                &wire_result_ident,
            );
            quote! {
                let #wire_result_ident: #wire_type = ::boltffi::__private::wire::decode(__result_bytes)
                    .expect("closure return: wire decode failed");
                #from_wire_conversion
            }
        } else {
            quote! {
                ::boltffi::__private::wire::decode(__result_bytes)
                    .expect("closure return: wire decode failed")
            }
        }
    }
}

pub(super) struct SyncCallbackParamLowerer<'a> {
    builder: CallbackBindingBuilder<'a>,
}

impl<'a> SyncCallbackParamLowerer<'a> {
    pub(super) fn new(
        return_lowering: &'a ReturnLoweringContext<'a>,
        callback_registry: &'a CallbackTraitRegistry,
    ) -> Self {
        Self {
            builder: CallbackBindingBuilder::new(return_lowering, callback_registry),
        }
    }

    pub(super) fn lower_callback_param(
        &self,
        acc: &mut ParamLoweringState,
        name: &Ident,
        arg_types: &[syn::Type],
        returns: &Option<syn::Type>,
    ) {
        let callback_name = Ident::new(&format!("{}_cb", name), name.span());
        let user_data_name = Ident::new(&format!("{}_ud", name), name.span());

        let (ffi_callback_args, arg_names, callback_call_args, wire_values) =
            arg_types.iter().enumerate().fold(
                (Vec::new(), Vec::new(), Vec::new(), Vec::new()),
                |(
                    mut ffi_callback_args,
                    mut arg_names,
                    mut callback_call_args,
                    mut wire_values,
                ),
                 (index, arg_type)| {
                    let arg_name = Ident::new(&format!("__arg{}", index), name.span());
                    let lowering = self
                        .builder
                        .lower_callback_arg(arg_type, &arg_name, index, name);
                    lowering
                        .ffi_arg_types
                        .into_iter()
                        .for_each(|ffi_arg_type| ffi_callback_args.push(ffi_arg_type));
                    wire_values.push(lowering.prelude);
                    lowering
                        .call_args
                        .into_iter()
                        .for_each(|call_arg| callback_call_args.push(call_arg));
                    arg_names.push(arg_name);
                    (
                        ffi_callback_args,
                        arg_names,
                        callback_call_args,
                        wire_values,
                    )
                },
            );

        let closure_return_abi = returns
            .as_ref()
            .map(|return_type| self.builder.return_lowering.lower_type(return_type));
        let closure_return_method = closure_return_abi.as_ref().map(|return_abi| {
            return_abi.value_return_method(
                ReturnInvocationContext::InlineClosure,
                ReturnPlatform::Native,
            )
        });
        let ffi_return_type = returns
            .as_ref()
            .zip(closure_return_abi.as_ref())
            .map(
                |(return_type, return_abi)| match (closure_return_method, return_abi) {
                    (Some(ValueReturnMethod::DirectReturn), return_abi)
                        if return_abi.encoded_return_strategy().is_some() =>
                    {
                        quote! { -> ::boltffi::__private::FfiBuf }
                    }
                    (Some(ValueReturnMethod::DirectReturn), _) => {
                        quote! { -> <#return_type as ::boltffi::__private::Passable>::Out }
                    }
                    (Some(ValueReturnMethod::WriteToReturnSlot), _) => {
                        quote! { -> ::boltffi::__private::FfiBuf }
                    }
                    (None, _) => quote! {},
                    (Some(other), _) => {
                        unreachable!("unsupported foreign callable return method: {other:?}")
                    }
                },
            )
            .unwrap_or_default();
        let closure_return_type = returns
            .as_ref()
            .map(|return_type| quote! { -> #return_type })
            .unwrap_or_default();
        let native_callback_invocation = returns
            .as_ref()
            .zip(closure_return_abi.as_ref())
            .map(|(return_type, return_abi)| match (closure_return_method, return_abi) {
                (Some(ValueReturnMethod::DirectReturn), return_abi)
                    if return_abi.encoded_return_strategy().is_some() =>
                {
                    let decode_expr =
                        self.builder.wire_decoded_callback_return_expr(return_type);
                    quote! {
                        {
                            let __result_buf = #callback_name(#user_data_name, #(#callback_call_args),*);
                            let __result_bytes = unsafe { __result_buf.as_byte_slice() };
                            #decode_expr
                        }
                    }
                }
                (Some(ValueReturnMethod::WriteToReturnSlot), _) => {
                    let decode_expr =
                        self.builder.wire_decoded_callback_return_expr(return_type);
                    quote! {
                        {
                            let __result_buf = #callback_name(#user_data_name, #(#callback_call_args),*);
                            let __result_bytes = unsafe { __result_buf.as_byte_slice() };
                            #decode_expr
                        }
                    }
                }
                (Some(ValueReturnMethod::DirectReturn), _) => {
                    quote! {
                        unsafe {
                            <#return_type as ::boltffi::__private::Passable>::unpack(
                                #callback_name(#user_data_name, #(#callback_call_args),*)
                            )
                        }
                    }
                }
                (None, _) => quote! { #callback_name(#user_data_name, #(#callback_call_args),*) },
                (Some(other), _) => {
                    unreachable!("unsupported foreign callable return method: {other:?}")
                }
            })
            .unwrap_or_else(|| quote! { #callback_name(#user_data_name, #(#callback_call_args),*) });

        let closure_params: Vec<proc_macro2::TokenStream> = arg_names
            .iter()
            .zip(arg_types.iter())
            .map(|(arg_name, arg_type)| quote! { #arg_name: #arg_type })
            .collect();

        acc.ffi_params.push(quote! {
            #[cfg(not(target_arch = "wasm32"))]
            #callback_name: extern "C" fn(*mut ::core::ffi::c_void, #(#ffi_callback_args),*) #ffi_return_type,
            #[cfg(not(target_arch = "wasm32"))]
            #user_data_name: *mut ::core::ffi::c_void,
            #[cfg(target_arch = "wasm32")]
            #name: u32
        });

        let wasm_closure_codegen = self.builder.generate_wasm_closure_codegen(
            name,
            arg_types,
            returns.as_ref(),
            &ffi_callback_args,
        );

        acc.setup.push(quote! {
            #[cfg(not(target_arch = "wasm32"))]
            let #name = |#(#closure_params),*| #closure_return_type {
                #(#wire_values)*
                #native_callback_invocation
            };
            #wasm_closure_codegen
        });

        acc.call_args.push(quote! { #name });
    }

    pub(super) fn lower_impl_trait_param(
        &self,
        acc: &mut ParamLoweringState,
        name: &Ident,
        trait_path: &syn::Path,
    ) {
        let resolution = self.builder.impl_trait_resolution(trait_path);
        let foreign_type = resolution.foreign_type;

        acc.ffi_params.push(quote! {
            #[cfg(not(target_arch = "wasm32"))]
            #name: ::boltffi::__private::CallbackHandle,
            #[cfg(target_arch = "wasm32")]
            #name: u32
        });

        if let Some(error) = resolution.error {
            acc.setup.push(error);
        }
        acc.setup.push(quote! {
            #[cfg(not(target_arch = "wasm32"))]
            assert!(!#name.is_null(), concat!(stringify!(#name), ": null callback handle"));
            #[cfg(target_arch = "wasm32")]
            let #name = ::boltffi::__private::CallbackHandle::from_wasm_handle(#name);
            let #name = unsafe {
                <#foreign_type as ::boltffi::__private::BoxFromCallbackHandle>::box_from_callback_handle(#name)
            };
        });
        acc.call_args.push(quote! { *#name });
    }

    pub(super) fn lower_trait_object_param(
        &self,
        acc: &mut ParamLoweringState,
        name: &Ident,
        trait_path: &syn::Path,
        kind: TraitObjectParamKind,
    ) {
        acc.ffi_params.push(quote! {
            #[cfg(not(target_arch = "wasm32"))]
            #name: ::boltffi::__private::CallbackHandle,
            #[cfg(target_arch = "wasm32")]
            #name: u32
        });
        let setup = match kind {
            TraitObjectParamKind::Boxed => quote! {
                #[cfg(not(target_arch = "wasm32"))]
                assert!(!#name.is_null(), concat!(stringify!(#name), ": null callback handle"));
                #[cfg(target_arch = "wasm32")]
                let #name = ::boltffi::__private::CallbackHandle::from_wasm_handle(#name);
                let #name: Box<dyn #trait_path> = unsafe {
                    <dyn #trait_path as ::boltffi::__private::BoxFromCallbackHandle>::box_from_callback_handle(#name)
                };
            },
            TraitObjectParamKind::Arc => quote! {
                #[cfg(not(target_arch = "wasm32"))]
                assert!(!#name.is_null(), concat!(stringify!(#name), ": null callback handle"));
                #[cfg(target_arch = "wasm32")]
                let #name = ::boltffi::__private::CallbackHandle::from_wasm_handle(#name);
                let #name: ::std::sync::Arc<dyn #trait_path> = unsafe {
                    <dyn #trait_path as ::boltffi::__private::ArcFromCallbackHandle>::arc_from_callback_handle(#name)
                };
            },
            TraitObjectParamKind::OptionBoxed => quote! {
                #[cfg(target_arch = "wasm32")]
                let #name = ::boltffi::__private::CallbackHandle::from_wasm_handle(#name);
                let #name: Option<Box<dyn #trait_path>> = if #name.is_null() {
                    None
                } else {
                    Some(unsafe {
                        <dyn #trait_path as ::boltffi::__private::BoxFromCallbackHandle>::box_from_callback_handle(#name)
                    })
                };
            },
            TraitObjectParamKind::OptionArc => quote! {
                #[cfg(target_arch = "wasm32")]
                let #name = ::boltffi::__private::CallbackHandle::from_wasm_handle(#name);
                let #name: Option<::std::sync::Arc<dyn #trait_path>> = if #name.is_null() {
                    None
                } else {
                    Some(unsafe {
                        <dyn #trait_path as ::boltffi::__private::ArcFromCallbackHandle>::arc_from_callback_handle(#name)
                    })
                };
            },
        };
        acc.setup.push(setup);
        acc.call_args.push(quote! { #name });
    }
}

fn is_passable_return(resolved_return: &ResolvedReturn) -> bool {
    resolved_return.is_passable_value()
}

pub(super) struct AsyncCallbackParamLowerer<'a> {
    builder: CallbackBindingBuilder<'a>,
}

impl<'a> AsyncCallbackParamLowerer<'a> {
    pub(super) fn new(
        return_lowering: &'a ReturnLoweringContext<'a>,
        callback_registry: &'a CallbackTraitRegistry,
    ) -> Self {
        Self {
            builder: CallbackBindingBuilder::new(return_lowering, callback_registry),
        }
    }

    pub(super) fn lower_impl_trait_param(
        &self,
        acc: &mut ParamLoweringState,
        name: &Ident,
        trait_path: &syn::Path,
    ) {
        let resolution = self.builder.impl_trait_resolution(trait_path);
        let foreign_type = resolution.foreign_type;

        acc.ffi_params.push(quote! {
            #[cfg(not(target_arch = "wasm32"))]
            #name: ::boltffi::__private::CallbackHandle,
            #[cfg(target_arch = "wasm32")]
            #name: u32
        });

        if let Some(error) = resolution.error {
            acc.setup.push(error);
        }
        let boxed_name = Ident::new(&format!("{}_boxed", name), name.span());
        acc.setup.push(quote! {
            #[cfg(not(target_arch = "wasm32"))]
            assert!(!#name.is_null(), concat!(stringify!(#name), ": null callback handle"));
            #[cfg(target_arch = "wasm32")]
            let #name = ::boltffi::__private::CallbackHandle::from_wasm_handle(#name);
            let #boxed_name = unsafe {
                <#foreign_type as ::boltffi::__private::BoxFromCallbackHandle>::box_from_callback_handle(#name)
            };
        });
        acc.move_vars.push(boxed_name.clone());
        acc.call_args.push(quote! { *#boxed_name });
    }
}
