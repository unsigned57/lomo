use boltffi_ffi_rules::transport::{
    EncodedReturnStrategy, ReturnInvocationContext, ReturnPlatform, ValueReturnMethod,
};
use proc_macro2::TokenStream;
use quote::{format_ident, quote};

use super::CallbackReturnType;
use super::lowered_return::LoweredCallbackReturn;
use crate::callbacks::snake_case_ident;
use crate::index::custom_types::CustomTypeRegistry;
use crate::index::custom_types::{contains_custom_types, from_wire_expr_owned, wire_type_for};
use crate::lowering::returns::lower::encoded_return_buffer_expression;
use crate::lowering::returns::model::{
    ReturnLoweringContext, ScalarReturnStrategy, ValueReturnStrategy,
};

const WASM_FOREIGN_CALLBACK_HANDLE_START: u32 = 0x8000_0000;

pub(super) struct LocalHandleExpander<'a> {
    item_trait: &'a syn::ItemTrait,
    trait_name: &'a syn::Ident,
    trait_name_snake: &'a syn::Ident,
    vtable_name: &'a syn::Ident,
    custom_types: &'a CustomTypeRegistry,
    return_lowering: &'a ReturnLoweringContext<'a>,
}

impl<'a> LocalHandleExpander<'a> {
    pub(super) fn new(
        item_trait: &'a syn::ItemTrait,
        trait_name: &'a syn::Ident,
        trait_name_snake: &'a syn::Ident,
        vtable_name: &'a syn::Ident,
        custom_types: &'a CustomTypeRegistry,
        return_lowering: &'a ReturnLoweringContext<'a>,
    ) -> Self {
        Self {
            item_trait,
            trait_name,
            trait_name_snake,
            vtable_name,
            custom_types,
            return_lowering,
        }
    }

    pub(super) fn expand(&self) -> Result<TokenStream, syn::Error> {
        let wasm_foreign_callback_handle_start = WASM_FOREIGN_CALLBACK_HANDLE_START;
        let local_state_name = format_ident!("__BoltffiLocal{}State", self.trait_name);
        let local_registry_name = format_ident!(
            "__BOLTFFI_LOCAL_{}_REGISTRY",
            self.trait_name_snake.to_string().to_uppercase()
        );
        let next_handle_name = format_ident!(
            "__BOLTFFI_LOCAL_{}_NEXT_HANDLE",
            self.trait_name_snake.to_string().to_uppercase()
        );
        let local_registry_lookup_name =
            format_ident!("__boltffi_local_{}_lookup", self.trait_name_snake);
        let local_vtable_name = format_ident!(
            "__BOLTFFI_LOCAL_{}_VTABLE",
            self.trait_name_snake.to_string().to_uppercase()
        );
        let free_function_name = format_ident!("__boltffi_local_{}_free", self.trait_name_snake);
        let clone_function_name = format_ident!("__boltffi_local_{}_clone", self.trait_name_snake);
        let local_handle_name = format_ident!("__boltffi_local_{}_handle", self.trait_name_snake);

        let local_method_expansions = self
            .item_trait
            .items
            .iter()
            .filter_map(|item| match item {
                syn::TraitItem::Fn(method) if method.sig.asyncness.is_none() => Some(method),
                _ => None,
            })
            .map(|method| {
                LocalHandleMethodExpander::new(
                    method,
                    self.trait_name_snake,
                    &local_state_name,
                    &local_registry_lookup_name,
                    self.custom_types,
                    self.return_lowering,
                )
                .expand()
            })
            .collect::<Result<Vec<_>, _>>()?;

        let local_method_names = local_method_expansions
            .iter()
            .map(|expansion| &expansion.function_name);
        let local_method_fields = local_method_expansions
            .iter()
            .map(|expansion| &expansion.vtable_field_name);
        let local_method_functions = local_method_expansions
            .iter()
            .map(|expansion| &expansion.function_tokens);
        let trait_name = self.trait_name;
        let vtable_name = self.vtable_name;

        Ok(quote! {
            #[cfg(not(target_arch = "wasm32"))]
            type #local_state_name = ::std::sync::Arc<dyn #trait_name>;

            #[cfg(target_arch = "wasm32")]
            type #local_state_name = ::std::sync::Arc<dyn #trait_name>;

            #[cfg(target_arch = "wasm32")]
            ::std::thread_local! {
                static #local_registry_name: ::std::cell::RefCell<::std::collections::BTreeMap<u32, #local_state_name>> =
                    ::std::cell::RefCell::new(::std::collections::BTreeMap::new());
                static #next_handle_name: ::std::cell::Cell<u32> = const { ::std::cell::Cell::new(1) };
            }

            #[cfg(not(target_arch = "wasm32"))]
            extern "C" fn #free_function_name(handle: u64) {
                if handle == 0 {
                    return;
                }

                unsafe {
                    drop(Box::from_raw(handle as *mut #local_state_name));
                }
            }

            #[cfg(not(target_arch = "wasm32"))]
            extern "C" fn #clone_function_name(handle: u64) -> u64 {
                let callback_impl = unsafe { &*(handle as *const #local_state_name) };
                Box::into_raw(Box::new(::std::sync::Arc::clone(callback_impl))) as u64
            }

            #[cfg(target_arch = "wasm32")]
            fn #local_registry_lookup_name(handle: u32) -> #local_state_name {
                #local_registry_name.with(|callback_registry| {
                    callback_registry
                        .borrow()
                        .get(&handle)
                        .cloned()
                        .unwrap_or_else(|| panic!("callback handle {} not found", handle))
                })
            }

            #[cfg(target_arch = "wasm32")]
            #[unsafe(no_mangle)]
            pub extern "C" fn #free_function_name(handle: u32) {
                if handle == 0 {
                    return;
                }

                #local_registry_name.with(|callback_registry| {
                    callback_registry.borrow_mut().remove(&handle);
                });
            }

            #[cfg(target_arch = "wasm32")]
            #[unsafe(no_mangle)]
            pub extern "C" fn #clone_function_name(handle: u32) -> u32 {
                if handle == 0 {
                    return 0;
                }

                let callback_impl = #local_registry_lookup_name(handle);
                #local_handle_name(callback_impl).handle() as u32
            }

            #(#local_method_functions)*

            #[cfg(not(target_arch = "wasm32"))]
            static #local_vtable_name: #vtable_name = #vtable_name {
                free: #free_function_name,
                clone: #clone_function_name,
                #(#local_method_fields: #local_method_names),*
            };

            #[cfg(not(target_arch = "wasm32"))]
            pub(crate) fn #local_handle_name(
                callback_impl: ::std::sync::Arc<dyn #trait_name>,
            ) -> ::boltffi::__private::CallbackHandle {
                ::boltffi::__private::CallbackHandle::new(
                    Box::into_raw(Box::new(callback_impl)) as u64,
                    &#local_vtable_name as *const #vtable_name as *const ::core::ffi::c_void,
                )
            }

            #[cfg(target_arch = "wasm32")]
            pub(crate) fn #local_handle_name(
                callback_impl: ::std::sync::Arc<dyn #trait_name>,
            ) -> ::boltffi::__private::CallbackHandle {
                let handle = #next_handle_name.with(|next_handle| {
                    let handle = next_handle.get();
                    let next_value = handle
                        .checked_add(1)
                        .filter(|candidate| *candidate != 0 && *candidate < #wasm_foreign_callback_handle_start)
                        .unwrap_or(1);
                    next_handle.set(next_value);
                    handle
                });

                #local_registry_name.with(|callback_registry| {
                    callback_registry.borrow_mut().insert(handle, callback_impl);
                });

                ::boltffi::__private::CallbackHandle::from_wasm_handle(handle)
            }
        })
    }
}

struct LocalHandleMethodExpander<'a> {
    method: &'a syn::TraitItemFn,
    trait_name_snake: &'a syn::Ident,
    local_state_name: &'a syn::Ident,
    local_registry_lookup_name: &'a syn::Ident,
    custom_types: &'a CustomTypeRegistry,
    return_lowering: &'a ReturnLoweringContext<'a>,
}

struct LocalHandleMethodExpansion {
    function_name: syn::Ident,
    vtable_field_name: syn::Ident,
    function_tokens: TokenStream,
}

impl<'a> LocalHandleMethodExpander<'a> {
    fn new(
        method: &'a syn::TraitItemFn,
        trait_name_snake: &'a syn::Ident,
        local_state_name: &'a syn::Ident,
        local_registry_lookup_name: &'a syn::Ident,
        custom_types: &'a CustomTypeRegistry,
        return_lowering: &'a ReturnLoweringContext<'a>,
    ) -> Self {
        Self {
            method,
            trait_name_snake,
            local_state_name,
            local_registry_lookup_name,
            custom_types,
            return_lowering,
        }
    }

    fn expand(&self) -> Result<LocalHandleMethodExpansion, syn::Error> {
        let method_name = &self.method.sig.ident;
        let method_name_snake = snake_case_ident(method_name);
        let function_name = format_ident!(
            "__boltffi_local_{}_{}",
            self.trait_name_snake,
            method_name_snake
        );
        let lowered_params = self.lowered_params();
        let ffi_params = &lowered_params.ffi_params;
        let decode_steps = &lowered_params.decode_steps;
        let call_args = &lowered_params.call_args;
        let local_state_name = self.local_state_name;
        let invoke_expression = quote! {
            callback_impl.#method_name(#(#call_args),*)
        };

        let (ffi_return_params, return_tokens) = match &self.method.sig.output {
            syn::ReturnType::Default => (
                vec![quote! { out_status: *mut ::boltffi::__private::FfiStatus }],
                self.expand_void_return(&invoke_expression),
            ),
            syn::ReturnType::Type(_, return_type) => {
                self.expand_returning_method(return_type, &invoke_expression)?
            }
        };

        let wasm_tokens =
            self.expand_wasm_method(&function_name, ffi_params, decode_steps, &invoke_expression)?;

        Ok(LocalHandleMethodExpansion {
            function_name: function_name.clone(),
            vtable_field_name: method_name_snake,
            function_tokens: quote! {
                #[cfg(not(target_arch = "wasm32"))]
                extern "C" fn #function_name(
                    handle: u64
                    #(, #ffi_params)*
                    #(, #ffi_return_params)*
                ) {
                    let callback_impl = unsafe { &*(handle as *const #local_state_name) };
                    #(#decode_steps)*
                    #return_tokens
                }

                #wasm_tokens
            },
        })
    }

    fn expand_void_return(&self, invoke_expression: &TokenStream) -> TokenStream {
        quote! {
            #invoke_expression;
            if !out_status.is_null() {
                unsafe {
                    *out_status = ::boltffi::__private::FfiStatus::OK;
                }
            }
        }
    }

    fn expand_returning_method(
        &self,
        return_type: &syn::Type,
        invoke_expression: &TokenStream,
    ) -> Result<(Vec<TokenStream>, TokenStream), syn::Error> {
        let resolved_return = self.return_lowering.lower_type(return_type);
        let lowered_return = LoweredCallbackReturn::new(return_type, self.return_lowering);
        let uses_wire_payload = matches!(
            lowered_return.value_return_method(
                ReturnInvocationContext::CallbackVtable,
                ReturnPlatform::Native,
            ),
            ValueReturnMethod::WriteToOutBufferParts
        );
        let result_name = format_ident!("callback_result");

        if uses_wire_payload {
            let buffer_setup = match resolved_return.value_return_strategy() {
                ValueReturnStrategy::CompositeValue => quote! {
                    let packed_result = ::boltffi::__private::Passable::pack(#result_name);
                    let callback_bytes = unsafe {
                        ::core::slice::from_raw_parts(
                            (&packed_result as *const <#return_type as ::boltffi::__private::Passable>::Out)
                                .cast::<u8>(),
                            ::core::mem::size_of::<<#return_type as ::boltffi::__private::Passable>::Out>(),
                        )
                    };
                },
                ValueReturnStrategy::Buffer(strategy) => {
                    let encoded_buffer = encoded_return_buffer_expression(
                        return_type,
                        strategy,
                        &result_name,
                        Some(self.custom_types),
                    );
                    quote! {
                        let callback_buffer = { #encoded_buffer };
                        let callback_bytes = unsafe { callback_buffer.as_byte_slice() };
                    }
                }
                ValueReturnStrategy::ObjectHandle | ValueReturnStrategy::CallbackHandle => {
                    return Err(syn::Error::new_spanned(
                        return_type,
                        "boltffi: local callback handle trampolines do not support handle returns yet",
                    ));
                }
                ValueReturnStrategy::Void | ValueReturnStrategy::Scalar(_) => {
                    return Err(syn::Error::new_spanned(
                        return_type,
                        "boltffi: unsupported local callback return strategy",
                    ));
                }
            };

            return Ok((
                vec![
                    quote! { result_out_ptr: *mut *mut u8 },
                    quote! { result_out_len: *mut usize },
                    quote! { out_status: *mut ::boltffi::__private::FfiStatus },
                ],
                quote! {
                    let #result_name: #return_type = #invoke_expression;
                    #buffer_setup

                    if !result_out_ptr.is_null() {
                        unsafe {
                            *result_out_ptr = ::core::ptr::null_mut();
                        }
                    }

                    if !result_out_len.is_null() {
                        unsafe {
                            *result_out_len = 0;
                        }
                    }

                    if !callback_bytes.is_empty() {
                        unsafe extern "C" {
                            fn malloc(size: usize) -> *mut ::core::ffi::c_void;
                        }

                        let copied_bytes = unsafe { malloc(callback_bytes.len()) as *mut u8 };
                        if copied_bytes.is_null() {
                            if !out_status.is_null() {
                                unsafe {
                                    *out_status = ::boltffi::__private::FfiStatus::INTERNAL_ERROR;
                                }
                            }
                            return;
                        }

                        unsafe {
                            ::core::ptr::copy_nonoverlapping(
                                callback_bytes.as_ptr(),
                                copied_bytes,
                                callback_bytes.len(),
                            );
                        }

                        if !result_out_ptr.is_null() {
                            unsafe {
                                *result_out_ptr = copied_bytes;
                            }
                        }

                        if !result_out_len.is_null() {
                            unsafe {
                                *result_out_len = callback_bytes.len();
                            }
                        }
                    }

                    if !out_status.is_null() {
                        unsafe {
                            *out_status = ::boltffi::__private::FfiStatus::OK;
                        }
                    }
                },
            ));
        }

        let ffi_return_type = quote! { <#return_type as ::boltffi::__private::Passable>::Out };
        let packed_result_expression = match resolved_return.value_return_strategy() {
            ValueReturnStrategy::Scalar(ScalarReturnStrategy::PrimitiveValue)
            | ValueReturnStrategy::Scalar(ScalarReturnStrategy::CStyleEnumTag)
            | ValueReturnStrategy::CompositeValue => quote! {
                ::boltffi::__private::Passable::pack(#result_name)
            },
            ValueReturnStrategy::ObjectHandle | ValueReturnStrategy::CallbackHandle => {
                return Err(syn::Error::new_spanned(
                    return_type,
                    "boltffi: local callback handle trampolines do not support handle returns yet",
                ));
            }
            ValueReturnStrategy::Void | ValueReturnStrategy::Buffer(_) => {
                return Err(syn::Error::new_spanned(
                    return_type,
                    "boltffi: unsupported local callback return strategy",
                ));
            }
        };

        Ok((
            vec![
                quote! { result_out: *mut #ffi_return_type },
                quote! { out_status: *mut ::boltffi::__private::FfiStatus },
            ],
            quote! {
                let #result_name: #return_type = #invoke_expression;
                if !result_out.is_null() {
                    unsafe {
                        *result_out = #packed_result_expression;
                    }
                }
                if !out_status.is_null() {
                    unsafe {
                        *out_status = ::boltffi::__private::FfiStatus::OK;
                    }
                }
            },
        ))
    }

    fn expand_wasm_method(
        &self,
        function_name: &syn::Ident,
        ffi_params: &[TokenStream],
        decode_steps: &[TokenStream],
        invoke_expression: &TokenStream,
    ) -> Result<TokenStream, syn::Error> {
        let local_registry_lookup_name = self.local_registry_lookup_name;

        match &self.method.sig.output {
            syn::ReturnType::Default => Ok(quote! {
                #[cfg(target_arch = "wasm32")]
                #[unsafe(no_mangle)]
                pub extern "C" fn #function_name(
                    handle: u32
                    #(, #ffi_params)*
                ) {
                    let callback_impl = #local_registry_lookup_name(handle);
                    #(#decode_steps)*
                    #invoke_expression;
                }
            }),
            syn::ReturnType::Type(_, return_type) => self.expand_wasm_returning_method(
                function_name,
                local_registry_lookup_name,
                ffi_params,
                decode_steps,
                return_type,
                invoke_expression,
            ),
        }
    }

    fn expand_wasm_returning_method(
        &self,
        function_name: &syn::Ident,
        local_registry_lookup_name: &syn::Ident,
        ffi_params: &[TokenStream],
        decode_steps: &[TokenStream],
        return_type: &syn::Type,
        invoke_expression: &TokenStream,
    ) -> Result<TokenStream, syn::Error> {
        let resolved_return = self.return_lowering.lower_type(return_type);
        let lowered_return = LoweredCallbackReturn::new(return_type, self.return_lowering);
        let uses_wire_payload = matches!(
            lowered_return.value_return_method(
                ReturnInvocationContext::CallbackVtable,
                ReturnPlatform::Wasm,
            ),
            ValueReturnMethod::WriteToOutBufferParts
        );
        let packed_utf8_return =
            lowered_return.encoded_return_strategy() == Some(EncodedReturnStrategy::Utf8String);
        let callback_result_name = format_ident!("callback_result");

        if uses_wire_payload {
            let wire_encode_expression = match resolved_return.value_return_strategy() {
                ValueReturnStrategy::CompositeValue
                | ValueReturnStrategy::Buffer(_)
                | ValueReturnStrategy::Scalar(ScalarReturnStrategy::PrimitiveValue)
                | ValueReturnStrategy::Scalar(ScalarReturnStrategy::CStyleEnumTag) => quote! {
                    ::boltffi::__private::FfiBuf::wire_encode(&#callback_result_name)
                },
                ValueReturnStrategy::ObjectHandle | ValueReturnStrategy::CallbackHandle => {
                    return Err(syn::Error::new_spanned(
                        return_type,
                        "boltffi: local wasm callback trampolines do not support handle returns yet",
                    ));
                }
                ValueReturnStrategy::Void => {
                    return Err(syn::Error::new_spanned(
                        return_type,
                        "boltffi: unsupported local wasm callback return strategy",
                    ));
                }
            };
            let packed_result_expression = if packed_utf8_return {
                quote! {
                    ::boltffi::__private::FfiBuf::from_vec(#callback_result_name.into_bytes())
                }
            } else {
                wire_encode_expression
            };

            return Ok(quote! {
                #[cfg(target_arch = "wasm32")]
                #[unsafe(no_mangle)]
                pub extern "C" fn #function_name(
                    handle: u32
                    #(, #ffi_params)*
                ) -> u64 {
                    let callback_impl = #local_registry_lookup_name(handle);
                    #(#decode_steps)*
                    let #callback_result_name: #return_type = #invoke_expression;
                    let callback_buffer = #packed_result_expression;
                    callback_buffer.into_packed()
                }
            });
        }

        let ffi_return_type = quote! { <#return_type as ::boltffi::__private::Passable>::Out };
        let packed_result_expression = match resolved_return.value_return_strategy() {
            ValueReturnStrategy::Scalar(ScalarReturnStrategy::PrimitiveValue)
            | ValueReturnStrategy::Scalar(ScalarReturnStrategy::CStyleEnumTag)
            | ValueReturnStrategy::CompositeValue => quote! {
                ::boltffi::__private::Passable::pack(#callback_result_name)
            },
            ValueReturnStrategy::ObjectHandle | ValueReturnStrategy::CallbackHandle => {
                return Err(syn::Error::new_spanned(
                    return_type,
                    "boltffi: local wasm callback trampolines do not support handle returns yet",
                ));
            }
            ValueReturnStrategy::Void | ValueReturnStrategy::Buffer(_) => {
                return Err(syn::Error::new_spanned(
                    return_type,
                    "boltffi: unsupported local wasm callback return strategy",
                ));
            }
        };

        Ok(quote! {
            #[cfg(target_arch = "wasm32")]
            #[unsafe(no_mangle)]
            pub extern "C" fn #function_name(
                handle: u32
                #(, #ffi_params)*
            ) -> #ffi_return_type {
                let callback_impl = #local_registry_lookup_name(handle);
                #(#decode_steps)*
                let #callback_result_name: #return_type = #invoke_expression;
                #packed_result_expression
            }
        })
    }

    fn lowered_params(&self) -> LocalHandleMethodParams {
        self.method
            .sig
            .inputs
            .iter()
            .filter_map(|input| match input {
                syn::FnArg::Typed(pat_type) => Some(pat_type),
                syn::FnArg::Receiver(_) => None,
            })
            .filter_map(|pat_type| match pat_type.pat.as_ref() {
                syn::Pat::Ident(ident) => Some((ident.ident.clone(), pat_type.ty.as_ref().clone())),
                _ => None,
            })
            .map(|(param_name, param_type)| self.lower_param(&param_name, &param_type))
            .fold(
                LocalHandleMethodParams::default(),
                |mut lowered_params, lowered_param| {
                    lowered_params.ffi_params.extend(lowered_param.ffi_params);
                    lowered_params
                        .decode_steps
                        .extend(lowered_param.decode_steps);
                    lowered_params.call_args.push(lowered_param.call_arg);
                    lowered_params
                },
            )
    }

    fn lower_param(&self, param_name: &syn::Ident, param_type: &syn::Type) -> LocalHandleParam {
        let direct_ffi_type = CallbackReturnType::new(param_type).ffi_type();
        if matches!(
            self.return_lowering
                .lower_type(param_type)
                .value_return_strategy(),
            ValueReturnStrategy::Scalar(_)
        ) {
            return LocalHandleParam {
                ffi_params: vec![quote! { #param_name: #direct_ffi_type }],
                decode_steps: vec![quote! {
                    let #param_name: #param_type = unsafe {
                        <#param_type as ::boltffi::__private::Passable>::unpack(#param_name)
                    };
                }],
                call_arg: quote! { #param_name },
            };
        }

        let pointer_name = format_ident!("{}_ptr", param_name);
        let length_name = format_ident!("{}_len", param_name);

        let decode_steps = if Self::is_borrowed_utf8_str(param_type) {
            let owned_string_name = format_ident!("{}_owned", param_name);
            vec![quote! {
                let #owned_string_name: String = ::boltffi::__private::wire::decode(unsafe {
                    ::core::slice::from_raw_parts(#pointer_name, #length_name)
                })
                .expect("wire decode local callback parameter");
                let #param_name: #param_type = &#owned_string_name;
            }]
        } else if contains_custom_types(param_type, self.custom_types) {
            let wire_type = wire_type_for(param_type, self.custom_types);
            let wire_value_name = format_ident!("{}_wire_value", param_name);
            let from_wire_expression =
                from_wire_expr_owned(param_type, self.custom_types, &wire_value_name);
            vec![quote! {
                let #wire_value_name: #wire_type = ::boltffi::__private::wire::decode(unsafe {
                    ::core::slice::from_raw_parts(#pointer_name, #length_name)
                })
                .expect("wire decode local callback parameter");
                let #param_name: #param_type = { #from_wire_expression };
            }]
        } else {
            vec![quote! {
                let #param_name: #param_type = ::boltffi::__private::wire::decode(unsafe {
                    ::core::slice::from_raw_parts(#pointer_name, #length_name)
                })
                .expect("wire decode local callback parameter");
            }]
        };

        LocalHandleParam {
            ffi_params: vec![
                quote! { #pointer_name: *const u8 },
                quote! { #length_name: usize },
            ],
            decode_steps,
            call_arg: quote! { #param_name },
        }
    }

    fn is_borrowed_utf8_str(param_type: &syn::Type) -> bool {
        let syn::Type::Reference(reference_type) = param_type else {
            return false;
        };

        reference_type.mutability.is_none()
            && matches!(
                reference_type.elem.as_ref(),
                syn::Type::Path(type_path)
                    if type_path
                        .path
                        .segments
                        .last()
                        .is_some_and(|segment| segment.ident == "str")
            )
    }
}

#[derive(Default)]
struct LocalHandleMethodParams {
    ffi_params: Vec<TokenStream>,
    decode_steps: Vec<TokenStream>,
    call_args: Vec<TokenStream>,
}

struct LocalHandleParam {
    ffi_params: Vec<TokenStream>,
    decode_steps: Vec<TokenStream>,
    call_arg: TokenStream,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::index::class_types::ClassTypeRegistry;
    use crate::index::custom_types::CustomTypeRegistry;
    use crate::index::data_types::DataTypeRegistry;
    use crate::lowering::returns::model::ReturnLoweringContext;
    use quote::{format_ident, quote};
    use syn::parse_quote;

    fn return_lowering() -> ReturnLoweringContext<'static> {
        let class_types = Box::leak(Box::new(ClassTypeRegistry::default()));
        let custom_types = Box::leak(Box::new(CustomTypeRegistry::default()));
        let data_types = Box::leak(Box::new(DataTypeRegistry::default()));
        ReturnLoweringContext::new(custom_types, data_types, class_types)
    }

    fn local_handle_method_expander(
        method: &'static syn::TraitItemFn,
    ) -> LocalHandleMethodExpander<'static> {
        let trait_name_snake = Box::leak(Box::new(format_ident!("progress_handler")));
        let local_state_name = Box::leak(Box::new(format_ident!(
            "__BoltffiLocalProgressHandlerState"
        )));
        let local_registry_lookup_name = Box::leak(Box::new(format_ident!(
            "__boltffi_local_progress_handler_lookup"
        )));
        let custom_types = Box::leak(Box::new(CustomTypeRegistry::default()));
        let return_lowering = Box::leak(Box::new(return_lowering()));

        LocalHandleMethodExpander::new(
            method,
            trait_name_snake,
            local_state_name,
            local_registry_lookup_name,
            custom_types,
            return_lowering,
        )
    }

    fn decode_tokens_for(param_type: syn::Type) -> String {
        let method: &'static syn::TraitItemFn = Box::leak(Box::new(parse_quote! {
            fn on_complete(&self, value: String);
        }));
        let expander = local_handle_method_expander(method);
        let lowered_param = expander.lower_param(&format_ident!("value"), &param_type);
        let decode_steps = lowered_param.decode_steps;
        quote! { #(#decode_steps)* }.to_string()
    }

    #[test]
    fn borrowed_str_callback_params_decode_into_owned_string_storage() {
        let decode_tokens = decode_tokens_for(parse_quote!(&str));

        assert!(
            decode_tokens
                .contains("let value_owned : String = :: boltffi :: __private :: wire :: decode")
        );
        assert!(decode_tokens.contains("let value : & str = & value_owned ;"));
    }

    #[test]
    fn owned_string_callback_params_keep_direct_decode_path() {
        let decode_tokens = decode_tokens_for(parse_quote!(String));

        assert!(
            decode_tokens
                .contains("let value : String = :: boltffi :: __private :: wire :: decode")
        );
        assert!(!decode_tokens.contains("value_owned"));
    }
}
