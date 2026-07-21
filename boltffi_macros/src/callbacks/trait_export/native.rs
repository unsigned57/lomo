use boltffi_ffi_rules::transport::{ReturnInvocationContext, ReturnPlatform, ValueReturnMethod};
use proc_macro2::TokenStream;
use quote::quote;

use super::CallbackReturnType;
use super::lowered_return::LoweredCallbackReturn;
use crate::callbacks::snake_case_ident;
use crate::index::custom_types;
use crate::lowering::returns::model::{ReturnLoweringContext, ValueReturnStrategy};

pub(super) struct NativeCallbackMethodExpander<'a> {
    method: &'a syn::TraitItemFn,
    custom_types: &'a custom_types::CustomTypeRegistry,
    return_lowering: &'a ReturnLoweringContext<'a>,
}

impl<'a> NativeCallbackMethodExpander<'a> {
    pub(super) fn new(
        method: &'a syn::TraitItemFn,
        custom_types: &'a custom_types::CustomTypeRegistry,
        return_lowering: &'a ReturnLoweringContext<'a>,
    ) -> Self {
        Self {
            method,
            custom_types,
            return_lowering,
        }
    }

    pub(super) fn expand(
        &self,
        vtable_fields: &mut Vec<TokenStream>,
    ) -> Result<TokenStream, syn::Error> {
        if self.method.sig.asyncness.is_some() {
            self.expand_async(vtable_fields)
        } else {
            self.expand_sync(vtable_fields)
        }
    }

    fn expand_async(
        &self,
        vtable_fields: &mut Vec<TokenStream>,
    ) -> Result<TokenStream, syn::Error> {
        let method_name = &self.method.sig.ident;
        let method_name_snake = self.method_name_snake();
        let lowered_params = self.lowered_params();
        let ffi_param_types = &lowered_params.ffi_param_types;
        let param_names = &lowered_params.param_names;
        let call_args = &lowered_params.call_args;
        let prelude_stmts = &lowered_params.prelude_stmts;
        let return_type = self.return_type();
        let lowered_return =
            return_type.map(|ty| LoweredCallbackReturn::new(ty, self.return_lowering));
        let async_wire_return = lowered_return
            .as_ref()
            .is_some_and(LoweredCallbackReturn::uses_wire_payload);

        let callback_type = if let Some(return_type) = return_type {
            if async_wire_return {
                quote! {
                    extern "C" fn(
                        callback_data: u64,
                        result_ptr: *const u8,
                        result_len: usize,
                        status: ::boltffi::__private::FfiStatus
                    )
                }
            } else {
                let ffi_return_type = CallbackReturnType::new(return_type).ffi_type();
                quote! {
                    extern "C" fn(
                        callback_data: u64,
                        result: #ffi_return_type,
                        status: ::boltffi::__private::FfiStatus
                    )
                }
            }
        } else {
            quote! { extern "C" fn(callback_data: u64, status: ::boltffi::__private::FfiStatus) }
        };

        vtable_fields.push(quote! {
            pub #method_name_snake: extern "C" fn(
                handle: u64,
                #(#ffi_param_types,)*
                callback: #callback_type,
                callback_data: u64
            )
        });

        let output_type = return_type.map(|ty| quote! { -> #ty }).unwrap_or_default();
        let impl_body = match return_type {
            Some(return_type) => self.async_returning_impl_body(
                return_type,
                async_wire_return,
                &method_name_snake,
                call_args,
                prelude_stmts,
            ),
            None => self.async_void_impl_body(&method_name_snake, call_args, prelude_stmts),
        };

        Ok(quote! {
            async fn #method_name(&self, #(#param_names,)*) #output_type {
                #impl_body
            }
        })
    }

    fn expand_sync(&self, vtable_fields: &mut Vec<TokenStream>) -> Result<TokenStream, syn::Error> {
        let method_name = &self.method.sig.ident;
        let method_name_snake = self.method_name_snake();
        let lowered_params = self.lowered_params();
        let ffi_param_types = &lowered_params.ffi_param_types;
        let param_names = &lowered_params.param_names;
        let call_args = &lowered_params.call_args;
        let prelude_stmts = &lowered_params.prelude_stmts;
        let return_type = self.return_type();
        let lowered_return =
            return_type.map(|ty| LoweredCallbackReturn::new(ty, self.return_lowering));
        let wire_return = lowered_return.as_ref().is_some_and(|return_shape| {
            matches!(
                return_shape.value_return_method(
                    ReturnInvocationContext::CallbackVtable,
                    ReturnPlatform::Native,
                ),
                ValueReturnMethod::WriteToOutBufferParts
            )
        });

        let out_params = if let Some(return_type) = return_type {
            if wire_return {
                quote! { out_ptr: *mut *mut u8, out_len: *mut usize, }
            } else {
                let ffi_return_type = CallbackReturnType::new(return_type).ffi_type();
                quote! { out: *mut #ffi_return_type, }
            }
        } else {
            quote! {}
        };

        vtable_fields.push(quote! {
            pub #method_name_snake: extern "C" fn(
                handle: u64,
                #(#ffi_param_types,)*
                #out_params
                status: *mut ::boltffi::__private::FfiStatus
            )
        });

        let output_type = return_type.map(|ty| quote! { -> #ty }).unwrap_or_default();
        let impl_body = match return_type {
            Some(return_type) => self.sync_returning_impl_body(
                return_type,
                wire_return,
                &method_name_snake,
                call_args,
                prelude_stmts,
            ),
            None => self.sync_void_impl_body(&method_name_snake, call_args, prelude_stmts),
        };

        Ok(quote! {
            fn #method_name(&self, #(#param_names,)*) #output_type {
                #impl_body
            }
        })
    }

    fn async_returning_impl_body(
        &self,
        return_type: &syn::Type,
        async_wire_return: bool,
        method_name_snake: &syn::Ident,
        call_args: &[TokenStream],
        prelude_stmts: &[TokenStream],
    ) -> TokenStream {
        let error_expr = CallbackReturnType::new(return_type)
            .result_types()
            .map(|result_types| {
                let err_ty = result_types.err;
            quote! {
                Err(<#err_ty as ::core::convert::From<::boltffi::UnexpectedFfiCallbackError>>::from(
                    ::boltffi::UnexpectedFfiCallbackError::new(error_msg)
                ))
            }
        });

        let (callback_body, poll_body) = if async_wire_return {
            let poll_error_branch = error_expr
                .clone()
                .map(|expr| {
                    quote! {
                        if callback_status.is_err() {
                            let error_msg: String = ::boltffi::__private::wire::decode(&bytes)
                                .unwrap_or_else(|_| "unknown callback error".into());
                            return std::task::Poll::Ready(#expr);
                        }
                    }
                })
                .unwrap_or_default();

            (
                quote! {
                    extern "C" fn callback(
                        data: u64,
                        result_ptr: *const u8,
                        result_len: usize,
                        status: ::boltffi::__private::FfiStatus
                    ) {
                        let bytes = unsafe { ::core::slice::from_raw_parts(result_ptr, result_len) }.to_vec();
                        let ctx = unsafe { Arc::from_raw(data as *const AsyncContext) };
                        let waker = ctx
                            .state
                            .lock()
                            .ok()
                            .and_then(|mut guard| {
                                guard.result_bytes = Some(bytes);
                                guard.status = status;
                                guard.waker.take()
                            });
                        if let Some(waker) = waker {
                            waker.wake();
                        }
                    }
                },
                quote! {
                    std::future::poll_fn(move |cx| {
                        let mut guard = ctx.state.lock().expect("async callback mutex poisoned");
                        if let Some(bytes) = guard.result_bytes.take() {
                            let callback_status = guard.status;
                            #poll_error_branch
                            let result: #return_type = ::boltffi::__private::wire::decode(&bytes)
                                .expect("wire decode async callback return");
                            std::task::Poll::Ready(result)
                        } else {
                            guard.waker = Some(cx.waker().clone());
                            std::task::Poll::Pending
                        }
                    }).await
                },
            )
        } else {
            let poll_error_branch = error_expr
                .map(|expr| {
                    quote! {
                        if callback_status.is_err() {
                            let error_msg = "callback returned error status".to_string();
                            return std::task::Poll::Ready(#expr);
                        }
                    }
                })
                .unwrap_or_default();

            let ffi_return_type = CallbackReturnType::new(return_type).ffi_type();
            (
                quote! {
                    extern "C" fn callback(
                        data: u64,
                        result: #ffi_return_type,
                        status: ::boltffi::__private::FfiStatus
                    ) {
                        let ctx = unsafe { Arc::from_raw(data as *const AsyncContext<#return_type>) };
                        let waker = ctx
                            .state
                            .lock()
                            .ok()
                            .and_then(|mut guard| {
                                guard.result = Some(unsafe {
                                    <#return_type as ::boltffi::__private::Passable>::unpack(result)
                                });
                                guard.status = status;
                                guard.waker.take()
                            });
                        if let Some(waker) = waker {
                            waker.wake();
                        }
                    }
                },
                quote! {
                    std::future::poll_fn(move |cx| {
                        let mut guard = ctx.state.lock().expect("async callback mutex poisoned");
                        if let Some(result) = guard.result.take() {
                            let callback_status = guard.status;
                            #poll_error_branch
                            std::task::Poll::Ready(result)
                        } else {
                            guard.waker = Some(cx.waker().clone());
                            std::task::Poll::Pending
                        }
                    }).await
                },
            )
        };

        let async_state = if async_wire_return {
            quote! {
                struct AsyncState {
                    result_bytes: Option<Vec<u8>>,
                    status: ::boltffi::__private::FfiStatus,
                    waker: Option<Waker>,
                }

                struct AsyncContext {
                    state: Mutex<AsyncState>,
                }

                let ctx = Arc::new(AsyncContext {
                    state: Mutex::new(AsyncState {
                        result_bytes: None,
                        status: ::boltffi::__private::FfiStatus::OK,
                        waker: None,
                    }),
                });
            }
        } else {
            quote! {
                struct AsyncState<T> {
                    result: Option<T>,
                    status: ::boltffi::__private::FfiStatus,
                    waker: Option<Waker>,
                }

                struct AsyncContext<T> {
                    state: Mutex<AsyncState<T>>,
                }

                let ctx = Arc::new(AsyncContext::<#return_type> {
                    state: Mutex::new(AsyncState {
                        result: None,
                        status: ::boltffi::__private::FfiStatus::OK,
                        waker: None,
                    }),
                });
            }
        };

        quote! {
            use std::sync::{Arc, Mutex};
            use std::task::Waker;

            #async_state

            #callback_body

            let ctx_ptr = Arc::into_raw(Arc::clone(&ctx)) as u64;
            {
                #(#prelude_stmts)*
                unsafe {
                    ((*self.vtable).#method_name_snake)(
                        self.handle,
                        #(#call_args,)*
                        callback,
                        ctx_ptr
                    );
                }
            }

            #poll_body
        }
    }

    fn async_void_impl_body(
        &self,
        method_name_snake: &syn::Ident,
        call_args: &[TokenStream],
        prelude_stmts: &[TokenStream],
    ) -> TokenStream {
        quote! {
            use std::sync::{Arc, Mutex};
            use std::task::Waker;

            struct AsyncState {
                completed: bool,
                status: ::boltffi::__private::FfiStatus,
                waker: Option<Waker>,
            }

            struct AsyncContext {
                state: Mutex<AsyncState>,
            }

            let ctx = Arc::new(AsyncContext {
                state: Mutex::new(AsyncState {
                    completed: false,
                    status: ::boltffi::__private::FfiStatus::OK,
                    waker: None,
                }),
            });

                    extern "C" fn callback(
                        data: u64,
                        callback_status: ::boltffi::__private::FfiStatus
                    ) {
                        let ctx = unsafe { Arc::from_raw(data as *const AsyncContext) };
                        let waker = ctx
                            .state
                            .lock()
                            .ok()
                            .and_then(|mut guard| {
                                guard.completed = true;
                                guard.status = callback_status;
                                guard.waker.take()
                            });
                if let Some(waker) = waker {
                    waker.wake();
                }
            }

            let ctx_ptr = Arc::into_raw(Arc::clone(&ctx)) as u64;
            {
                #(#prelude_stmts)*
                unsafe {
                    ((*self.vtable).#method_name_snake)(
                        self.handle,
                        #(#call_args,)*
                        callback,
                        ctx_ptr
                    );
                }
            }

            std::future::poll_fn(move |cx| {
                let mut guard = ctx.state.lock().expect("async callback mutex poisoned");
                if guard.completed {
                    std::task::Poll::Ready(())
                } else {
                    guard.waker = Some(cx.waker().clone());
                    std::task::Poll::Pending
                }
            }).await
        }
    }

    fn sync_returning_impl_body(
        &self,
        return_type: &syn::Type,
        wire_return: bool,
        method_name_snake: &syn::Ident,
        call_args: &[TokenStream],
        prelude_stmts: &[TokenStream],
    ) -> TokenStream {
        let error_expr = CallbackReturnType::new(return_type)
            .result_types()
            .map(|result_types| {
                let err_ty = result_types.err;
            quote! {
                return Err(<#err_ty as ::core::convert::From<::boltffi::UnexpectedFfiCallbackError>>::from(
                    ::boltffi::UnexpectedFfiCallbackError::new("sync callback returned error status")
                ));
            }
        });

        if wire_return {
            quote! {
                #(#prelude_stmts)*
                unsafe extern "C" {
                    fn free(ptr: *mut ::core::ffi::c_void);
                }
                let mut out_ptr: *mut u8 = ::core::ptr::null_mut();
                let mut out_len: usize = 0;
                let mut callback_status = ::boltffi::__private::FfiStatus::default();
                unsafe {
                    ((*self.vtable).#method_name_snake)(
                        self.handle,
                        #(#call_args,)*
                        &mut out_ptr,
                        &mut out_len,
                        &mut callback_status
                    );
                }
                if callback_status.is_err() {
                    if !out_ptr.is_null() {
                        unsafe { free(out_ptr.cast()) };
                    }
                    #error_expr
                }
                let decode_result = {
                    let out_bytes = if out_ptr.is_null() {
                        &[]
                    } else {
                        unsafe { ::core::slice::from_raw_parts(out_ptr, out_len) }
                    };
                    ::boltffi::__private::wire::decode(out_bytes)
                };
                if !out_ptr.is_null() {
                    unsafe { free(out_ptr.cast()) };
                }
                decode_result.expect("wire decode callback return")
            }
        } else {
            let ffi_return_type = CallbackReturnType::new(return_type).ffi_type();
            quote! {
                #(#prelude_stmts)*
                let mut out: #ffi_return_type = Default::default();
                let mut callback_status = ::boltffi::__private::FfiStatus::default();
                unsafe {
                    ((*self.vtable).#method_name_snake)(
                        self.handle,
                        #(#call_args,)*
                        &mut out as *mut _,
                        &mut callback_status
                    );
                }
                if callback_status.is_err() {
                    #error_expr
                }
                unsafe { <#return_type as ::boltffi::__private::Passable>::unpack(out) }
            }
        }
    }

    fn sync_void_impl_body(
        &self,
        method_name_snake: &syn::Ident,
        call_args: &[TokenStream],
        prelude_stmts: &[TokenStream],
    ) -> TokenStream {
        quote! {
            #(#prelude_stmts)*
            let mut callback_status = ::boltffi::__private::FfiStatus::default();
            unsafe {
                ((*self.vtable).#method_name_snake)(
                    self.handle,
                    #(#call_args,)*
                    &mut callback_status
                );
            }
        }
    }

    fn lowered_params(&self) -> NativeLoweredParams {
        self.method
            .sig
            .inputs
            .iter()
            .filter_map(|input| match input {
                syn::FnArg::Typed(pat_type) => match pat_type.pat.as_ref() {
                    syn::Pat::Ident(pat_ident) => {
                        Some((pat_ident.ident.clone(), pat_type.ty.clone()))
                    }
                    _ => None,
                },
                syn::FnArg::Receiver(_) => None,
            })
            .map(|(param_name, param_type)| self.lower_param(&param_name, &param_type))
            .fold(NativeLoweredParams::default(), |mut lowered, param| {
                lowered.ffi_param_types.push(param.ffi_param);
                lowered.param_names.push(param.rust_param);
                lowered.call_args.push(param.call_arg);
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
    ) -> NativeCallbackParamLowering {
        let rust_param = quote! { #param_name: #param_type };
        let direct_ffi_type = CallbackReturnType::new(param_type).ffi_type();
        if matches!(
            self.return_lowering
                .lower_type(param_type)
                .value_return_strategy(),
            ValueReturnStrategy::Scalar(_)
        ) {
            return NativeCallbackParamLowering {
                ffi_param: quote! { #param_name: #direct_ffi_type },
                rust_param,
                call_arg: quote! { <#param_type as ::boltffi::__private::Passable>::pack(#param_name) },
                prelude: None,
            };
        }

        let ptr_name = syn::Ident::new(&format!("{}_ptr", param_name), param_name.span());
        let len_name = syn::Ident::new(&format!("{}_len", param_name), param_name.span());
        let wire_name = syn::Ident::new(&format!("{}_wire", param_name), param_name.span());

        let prelude = if custom_types::contains_custom_types(param_type, self.custom_types) {
            let wire_type = custom_types::wire_type_for(param_type, self.custom_types);
            let wire_value_name =
                syn::Ident::new(&format!("{}_wire_value", param_name), param_name.span());
            let to_wire =
                custom_types::to_wire_expr_owned(param_type, self.custom_types, param_name);
            quote! {
                let #wire_value_name: #wire_type = { #to_wire };
                let #wire_name = ::boltffi::__private::wire::encode(&#wire_value_name);
            }
        } else {
            quote! { let #wire_name = ::boltffi::__private::wire::encode(&#param_name); }
        };

        NativeCallbackParamLowering {
            ffi_param: quote! { #ptr_name: *const u8, #len_name: usize },
            rust_param,
            call_arg: quote! { #wire_name.as_ptr(), #wire_name.len() },
            prelude: Some(prelude),
        }
    }

    fn method_name_snake(&self) -> syn::Ident {
        snake_case_ident(&self.method.sig.ident)
    }

    fn return_type(&self) -> Option<&syn::Type> {
        match &self.method.sig.output {
            syn::ReturnType::Default => None,
            syn::ReturnType::Type(_, ty) => Some(ty.as_ref()),
        }
    }
}

#[derive(Default)]
struct NativeLoweredParams {
    ffi_param_types: Vec<TokenStream>,
    param_names: Vec<TokenStream>,
    call_args: Vec<TokenStream>,
    prelude_stmts: Vec<TokenStream>,
}

struct NativeCallbackParamLowering {
    ffi_param: TokenStream,
    rust_param: TokenStream,
    call_arg: TokenStream,
    prelude: Option<TokenStream>,
}
