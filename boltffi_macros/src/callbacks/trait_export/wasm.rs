use proc_macro2::TokenStream;
use quote::{format_ident, quote};
use syn::{FnArg, Pat, ReturnType};

use super::CallbackReturnType;
use super::lowered_return::LoweredCallbackReturn;
use crate::callbacks::snake_case_ident;
use crate::index::custom_types;
use crate::lowering::returns::model::{
    DirectBufferReturnMethod, EncodedReturnStrategy, ReturnInvocationContext,
    ReturnLoweringContext, ReturnPlatform, ValueReturnStrategy,
};

pub(super) struct WasmMethodExpansion {
    pub(super) extern_import: TokenStream,
    pub(super) impl_body: TokenStream,
    pub(super) complete_export: Option<TokenStream>,
}

pub(super) struct WasmCallbackMethodExpander<'a> {
    method: &'a syn::TraitItemFn,
    trait_name_snake: &'a syn::Ident,
    custom_types: &'a custom_types::CustomTypeRegistry,
    return_lowering: &'a ReturnLoweringContext<'a>,
}

impl<'a> WasmCallbackMethodExpander<'a> {
    pub(super) fn new(
        method: &'a syn::TraitItemFn,
        trait_name_snake: &'a syn::Ident,
        custom_types: &'a custom_types::CustomTypeRegistry,
        return_lowering: &'a ReturnLoweringContext<'a>,
    ) -> Self {
        Self {
            method,
            trait_name_snake,
            custom_types,
            return_lowering,
        }
    }

    pub(super) fn expand(&self) -> Result<WasmMethodExpansion, syn::Error> {
        if self.method.sig.asyncness.is_some() {
            self.expand_async()
        } else {
            self.expand_sync()
        }
    }

    fn expand_sync(&self) -> Result<WasmMethodExpansion, syn::Error> {
        let method_name = &self.method.sig.ident;
        let import_name = format_ident!(
            "__boltffi_callback_{}_{}",
            self.trait_name_snake,
            self.method_name_snake()
        );
        let lowered_params = self.lowered_params();
        let ffi_param_types = &lowered_params.ffi_param_types;
        let param_names = &lowered_params.param_names;
        let call_args = &lowered_params.call_args;
        let prelude_stmts = &lowered_params.prelude_stmts;
        let return_type = self.return_type();
        let lowered_return = return_type
            .as_ref()
            .map(|ty| LoweredCallbackReturn::new(ty, self.return_lowering));

        let packed_utf8_return = lowered_return.as_ref().is_some_and(|lowered_return| {
            lowered_return.encoded_return_strategy() == Some(EncodedReturnStrategy::Utf8String)
                && lowered_return.direct_buffer_return_method(
                    ReturnInvocationContext::SyncExport,
                    ReturnPlatform::Wasm,
                ) == Some(DirectBufferReturnMethod::Packed)
        });
        let wire_return = lowered_return
            .as_ref()
            .is_some_and(LoweredCallbackReturn::uses_wire_payload);

        let (extern_import, impl_body) = if let Some(ref return_type) = return_type {
            if packed_utf8_return {
                (
                    quote! {
                        fn #import_name(handle: u32, #(#ffi_param_types),*) -> u64;
                    },
                    quote! {
                        #(#prelude_stmts)*
                        let packed = unsafe { #import_name(self.handle, #(#call_args),*) };
                        unsafe { ::boltffi::__private::take_packed_utf8_string(packed) }
                    },
                )
            } else if wire_return {
                (
                    quote! {
                        fn #import_name(
                            handle: u32,
                            out_buf_ptr: *mut ::boltffi::__private::WasmCallbackOutBuf,
                            #(#ffi_param_types),*
                        );
                    },
                    quote! {
                        #(#prelude_stmts)*
                        let mut out_buf = ::boltffi::__private::WasmCallbackOutBuf::empty();
                        unsafe {
                            #import_name(
                                self.handle,
                                &mut out_buf as *mut _,
                                #(#call_args),*
                            );
                        }
                        let out_bytes = unsafe { out_buf.as_slice() };
                        ::boltffi::__private::wire::decode(out_bytes)
                            .expect("wire decode wasm callback return")
                    },
                )
            } else {
                let ffi_return_type = CallbackReturnType::new(return_type).ffi_type();
                (
                    quote! {
                        fn #import_name(handle: u32, #(#ffi_param_types),*) -> #ffi_return_type;
                    },
                    quote! {
                        #(#prelude_stmts)*
                        unsafe {
                            <#return_type as ::boltffi::__private::Passable>::unpack(
                                #import_name(self.handle, #(#call_args),*)
                            )
                        }
                    },
                )
            }
        } else {
            (
                quote! {
                    fn #import_name(handle: u32, #(#ffi_param_types),*);
                },
                quote! {
                    #(#prelude_stmts)*
                    unsafe { #import_name(self.handle, #(#call_args),*) }
                },
            )
        };

        let output_type = return_type
            .as_ref()
            .map(|ty| quote! { -> #ty })
            .unwrap_or_default();

        Ok(WasmMethodExpansion {
            extern_import,
            impl_body: quote! {
                fn #method_name(&self, #(#param_names,)*) #output_type {
                    #impl_body
                }
            },
            complete_export: None,
        })
    }

    fn expand_async(&self) -> Result<WasmMethodExpansion, syn::Error> {
        let method_name = &self.method.sig.ident;
        let method_name_snake = self.method_name_snake();
        let start_import_name = format_ident!(
            "__boltffi_callback_{}_{}_start",
            self.trait_name_snake,
            method_name_snake
        );
        let complete_export_name = format_ident!(
            "boltffi_callback_{}_{}_complete",
            self.trait_name_snake,
            method_name_snake
        );
        let lowered_params = self.lowered_params();
        let ffi_param_types = &lowered_params.ffi_param_types;
        let param_names = &lowered_params.param_names;
        let call_args = &lowered_params.call_args;
        let prelude_stmts = &lowered_params.prelude_stmts;
        let return_type = self.return_type();

        let extern_import = quote! {
            fn #start_import_name(handle: u32, request_id: u32, #(#ffi_param_types),*);
        };

        let complete_export = quote! {
            #[cfg(target_arch = "wasm32")]
            #[unsafe(no_mangle)]
            pub unsafe extern "C" fn #complete_export_name(
                request_id: u32,
                completion_code: i32,
                data_ptr: u32,
                data_len: u32,
                data_cap: u32,
            ) -> i32 {
                ::boltffi::__private::AsyncCallbackRegistry::current().complete_from_ffi(
                    request_id,
                    completion_code,
                    data_ptr,
                    data_len,
                    data_cap,
                )
            }
        };

        let output_type = return_type
            .as_ref()
            .map(|ty| quote! { -> #ty })
            .unwrap_or_default();
        let poll_body = self.async_poll_body(return_type.as_ref());

        let impl_body = quote! {
            let callback_registry = ::boltffi::__private::AsyncCallbackRegistry::current();
            let request_id = callback_registry.allocate();
            let _guard = ::boltffi::__private::AsyncCallbackRequestGuard::new(request_id);
            {
                #(#prelude_stmts)*
                unsafe {
                    #start_import_name(
                        self.handle,
                        request_id.as_u32(),
                        #(#call_args),*
                    );
                }
            }
            #poll_body
        };

        Ok(WasmMethodExpansion {
            extern_import,
            impl_body: quote! {
                async fn #method_name(&self, #(#param_names,)*) #output_type {
                    #impl_body
                }
            },
            complete_export: Some(complete_export),
        })
    }

    fn async_poll_body(&self, return_type: Option<&syn::Type>) -> TokenStream {
        match return_type {
            Some(return_type) => {
                if let Some(result_types) = CallbackReturnType::new(return_type).result_types() {
                    let err_type = result_types.err;
                    quote! {
                        std::future::poll_fn(move |cx| {
                            callback_registry.set_waker(request_id, cx.waker().clone());
                            match callback_registry.take_completion(request_id) {
                                Some(result) => {
                                    if !result.code.is_success() {
                                        let error_msg = if result.data.is_empty() {
                                            "async callback failed".to_string()
                                        } else {
                                            ::boltffi::__private::wire::decode::<String>(&result.data)
                                                .unwrap_or_else(|_| "async callback failed".to_string())
                                        };
                                        return std::task::Poll::Ready(Err(
                                            <#err_type as ::core::convert::From<::boltffi::UnexpectedFfiCallbackError>>::from(
                                                ::boltffi::UnexpectedFfiCallbackError::new(error_msg)
                                            )
                                        ));
                                    }
                                    let value: #return_type = ::boltffi::__private::wire::decode(&result.data)
                                        .expect("wire decode async callback return");
                                    std::task::Poll::Ready(value)
                                }
                                None => std::task::Poll::Pending,
                            }
                        }).await
                    }
                } else {
                    quote! {
                        std::future::poll_fn(move |cx| {
                            callback_registry.set_waker(request_id, cx.waker().clone());
                            match callback_registry.take_completion(request_id) {
                                Some(result) => {
                                    if !result.code.is_success() {
                                        let error_msg = if result.data.is_empty() {
                                            "async callback failed".to_string()
                                        } else {
                                            ::boltffi::__private::wire::decode::<String>(&result.data)
                                                .unwrap_or_else(|_| "async callback failed".to_string())
                                        };
                                        panic!("async callback failed: {}", error_msg);
                                    }
                                    let value: #return_type = ::boltffi::__private::wire::decode(&result.data)
                                        .expect("wire decode async callback return");
                                    std::task::Poll::Ready(value)
                                }
                                None => std::task::Poll::Pending,
                            }
                        }).await
                    }
                }
            }
            None => {
                quote! {
                    std::future::poll_fn(move |cx| {
                        callback_registry.set_waker(request_id, cx.waker().clone());
                        match callback_registry.take_completion(request_id) {
                            Some(result) => {
                                if !result.code.is_success() {
                                    let error_msg = if result.data.is_empty() {
                                        "async callback failed".to_string()
                                    } else {
                                        ::boltffi::__private::wire::decode::<String>(&result.data)
                                            .unwrap_or_else(|_| "async callback failed".to_string())
                                    };
                                    panic!("async callback failed: {}", error_msg);
                                }
                                std::task::Poll::Ready(())
                            }
                            None => std::task::Poll::Pending,
                        }
                    }).await
                }
            }
        }
    }

    fn lowered_params(&self) -> WasmLoweredParams {
        self.method
            .sig
            .inputs
            .iter()
            .filter_map(|input| match input {
                FnArg::Typed(pat_type) => match pat_type.pat.as_ref() {
                    Pat::Ident(pat_ident) => Some((pat_ident.ident.clone(), pat_type.ty.clone())),
                    _ => None,
                },
                FnArg::Receiver(_) => None,
            })
            .map(|(param_name, param_type)| self.lower_param(&param_name, &param_type))
            .fold(WasmLoweredParams::default(), |mut lowered, param| {
                param
                    .ffi_params
                    .into_iter()
                    .for_each(|ffi_param| lowered.ffi_param_types.push(ffi_param));
                lowered.param_names.push(param.rust_param);
                param
                    .call_args
                    .into_iter()
                    .for_each(|call_arg| lowered.call_args.push(call_arg));
                param
                    .prelude
                    .into_iter()
                    .for_each(|prelude| lowered.prelude_stmts.push(prelude));
                lowered
            })
    }

    fn lower_param(
        &self,
        param_name: &syn::Ident,
        param_type: &syn::Type,
    ) -> WasmCallbackParamLowering {
        let rust_param = quote! { #param_name: #param_type };
        let direct_ffi_type = CallbackReturnType::new(param_type).ffi_type();
        if matches!(
            self.return_lowering
                .lower_type(param_type)
                .value_return_strategy(),
            ValueReturnStrategy::Scalar(_)
        ) {
            return WasmCallbackParamLowering {
                ffi_params: vec![quote! { #param_name: #direct_ffi_type }],
                rust_param,
                call_args: vec![
                    quote! { <#param_type as ::boltffi::__private::Passable>::pack(#param_name) },
                ],
                prelude: None,
            };
        }

        let ptr_name = format_ident!("{}_ptr", param_name);
        let len_name = format_ident!("{}_len", param_name);
        let wire_name = format_ident!("{}_wire", param_name);

        let prelude = if custom_types::contains_custom_types(param_type, self.custom_types) {
            let wire_type = custom_types::wire_type_for(param_type, self.custom_types);
            let wire_value_name = format_ident!("{}_wire_value", param_name);
            let to_wire =
                custom_types::to_wire_expr_owned(param_type, self.custom_types, param_name);
            quote! {
                let #wire_value_name: #wire_type = { #to_wire };
                let #wire_name = ::boltffi::__private::wire::encode(&#wire_value_name);
            }
        } else {
            quote! { let #wire_name = ::boltffi::__private::wire::encode(&#param_name); }
        };

        WasmCallbackParamLowering {
            ffi_params: vec![quote! { #ptr_name: *const u8 }, quote! { #len_name: u32 }],
            rust_param,
            call_args: vec![
                quote! { #wire_name.as_ptr() },
                quote! { #wire_name.len() as u32 },
            ],
            prelude: Some(prelude),
        }
    }

    fn method_name_snake(&self) -> syn::Ident {
        snake_case_ident(&self.method.sig.ident)
    }

    fn return_type(&self) -> Option<syn::Type> {
        match &self.method.sig.output {
            ReturnType::Default => None,
            ReturnType::Type(_, ty) => Some((**ty).clone()),
        }
    }
}

#[derive(Default)]
struct WasmLoweredParams {
    ffi_param_types: Vec<TokenStream>,
    param_names: Vec<TokenStream>,
    call_args: Vec<TokenStream>,
    prelude_stmts: Vec<TokenStream>,
}

struct WasmCallbackParamLowering {
    ffi_params: Vec<TokenStream>,
    rust_param: TokenStream,
    call_args: Vec<TokenStream>,
    prelude: Option<TokenStream>,
}
