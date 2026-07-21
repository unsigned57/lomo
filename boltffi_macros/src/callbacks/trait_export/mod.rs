use boltffi_ffi_rules::naming;
use proc_macro::TokenStream;
use quote::{format_ident, quote};
use syn::Type;

mod local_handle;
mod lowered_return;
mod native;
mod wasm;

use self::local_handle::LocalHandleExpander;
use self::native::NativeCallbackMethodExpander;
use self::wasm::{WasmCallbackMethodExpander, WasmMethodExpansion};

use crate::callbacks::snake_case_ident;
use crate::index::CrateIndex;
use crate::lowering::returns::model::ReturnLoweringContext;

pub(super) struct CallbackReturnType<'a> {
    rust_type: &'a Type,
}

pub(super) struct ParsedResultTypes {
    pub(super) err: Type,
}

impl<'a> CallbackReturnType<'a> {
    pub(super) fn new(rust_type: &'a Type) -> Self {
        Self { rust_type }
    }

    pub(super) fn ffi_type(&self) -> proc_macro2::TokenStream {
        let rust_type = self.rust_type;
        quote!(<#rust_type as ::boltffi::__private::Passable>::Out)
    }

    pub(super) fn result_types(&self) -> Option<ParsedResultTypes> {
        let Type::Path(type_path) = self.rust_type else {
            return None;
        };
        let result_segment = type_path.path.segments.last()?;
        if result_segment.ident != "Result" {
            return None;
        }
        let syn::PathArguments::AngleBracketed(arguments) = &result_segment.arguments else {
            return None;
        };
        let mut types = arguments.args.iter().filter_map(|argument| match argument {
            syn::GenericArgument::Type(ty) => Some(ty.clone()),
            _ => None,
        });
        types.next()?;
        Some(ParsedResultTypes { err: types.next()? })
    }
}

pub fn ffi_trait_impl(item: TokenStream) -> TokenStream {
    let item_trait = syn::parse_macro_input!(item as syn::ItemTrait);
    expand_ffi_trait(item_trait)
        .unwrap_or_else(|error| error.to_compile_error())
        .into()
}

fn expand_ffi_trait(item_trait: syn::ItemTrait) -> Result<proc_macro2::TokenStream, syn::Error> {
    let crate_index = CrateIndex::for_current_crate()?;
    let class_types = crate_index.class_types().clone();
    let custom_types = crate_index.custom_types().clone();
    let data_types = crate_index.data_types().clone();
    let return_lowering = ReturnLoweringContext::new(&custom_types, &data_types, &class_types);
    let trait_name = &item_trait.ident;
    let trait_name_snake = snake_case_ident(trait_name);
    let vtable_name = syn::Ident::new(&format!("{}VTable", trait_name), trait_name.span());
    let foreign_name = syn::Ident::new(&format!("Foreign{}", trait_name), trait_name.span());
    let vtable_static = syn::Ident::new(
        &format!("{}_VTABLE", trait_name_snake.to_string().to_uppercase()),
        trait_name.span(),
    );
    let register_fn = syn::Ident::new(
        &format!(
            "{}_register_{}_vtable",
            naming::ffi_prefix(),
            trait_name_snake
        ),
        trait_name.span(),
    );
    let create_fn = syn::Ident::new(
        &format!(
            "{}_create_{}_handle",
            naming::ffi_prefix(),
            trait_name_snake
        ),
        trait_name.span(),
    );

    let has_async_trait_attr = item_trait.attrs.iter().any(|attr| {
        attr.path()
            .segments
            .last()
            .is_some_and(|s| s.ident == "async_trait")
    });

    let async_trait_attr = item_trait
        .attrs
        .iter()
        .find(|attr| {
            attr.path()
                .segments
                .last()
                .is_some_and(|s| s.ident == "async_trait")
        })
        .cloned();

    let mut vtable_fields = vec![
        quote! { pub free: extern "C" fn(handle: u64) },
        quote! { pub clone: extern "C" fn(handle: u64) -> u64 },
    ];

    let has_async_methods = item_trait
        .items
        .iter()
        .any(|item| matches!(item, syn::TraitItem::Fn(method) if method.sig.asyncness.is_some()));

    let is_object_safe = !has_async_methods || has_async_trait_attr;

    let foreign_impls = item_trait
        .items
        .iter()
        .filter_map(|item| match item {
            syn::TraitItem::Fn(method) => Some(method),
            _ => None,
        })
        .map(|method| {
            NativeCallbackMethodExpander::new(method, &custom_types, &return_lowering)
                .expand(&mut vtable_fields)
        })
        .collect::<Result<Vec<_>, _>>()?;

    let wasm_expansions: Vec<WasmMethodExpansion> = item_trait
        .items
        .iter()
        .filter_map(|item| match item {
            syn::TraitItem::Fn(method) => Some(method),
            _ => None,
        })
        .map(|method| {
            WasmCallbackMethodExpander::new(
                method,
                &trait_name_snake,
                &custom_types,
                &return_lowering,
            )
            .expand()
        })
        .collect::<Result<Vec<_>, _>>()?;

    let wasm_extern_imports: Vec<_> = wasm_expansions.iter().map(|e| &e.extern_import).collect();
    let wasm_impl_bodies: Vec<_> = wasm_expansions.iter().map(|e| &e.impl_body).collect();
    let wasm_complete_exports: Vec<_> = wasm_expansions
        .iter()
        .filter_map(|e| e.complete_export.as_ref())
        .collect();

    let wasm_free_import = format_ident!("__boltffi_callback_{}_free", trait_name_snake);
    let wasm_clone_import = format_ident!("__boltffi_callback_{}_clone", trait_name_snake);
    let wasm_create_fn = format_ident!(
        "{}_create_{}_handle",
        naming::ffi_prefix(),
        trait_name_snake
    );

    let expanded = quote! {
        #item_trait

        #[cfg(not(target_arch = "wasm32"))]
        #[repr(C)]
        pub struct #vtable_name {
            #(#vtable_fields),*
        }

        #[cfg(not(target_arch = "wasm32"))]
        #[derive(Debug)]
        pub struct #foreign_name {
            vtable: *const #vtable_name,
            handle: u64,
        }

        #[cfg(target_arch = "wasm32")]
        #[derive(Debug)]
        pub struct #foreign_name {
            handle: u32,
        }

        unsafe impl Send for #foreign_name {}
        unsafe impl Sync for #foreign_name {}

        #[cfg(not(target_arch = "wasm32"))]
        impl Drop for #foreign_name {
            fn drop(&mut self) {
                unsafe { ((*self.vtable).free)(self.handle) };
            }
        }

        #[cfg(not(target_arch = "wasm32"))]
        impl Clone for #foreign_name {
            fn clone(&self) -> Self {
                let new_handle = unsafe { ((*self.vtable).clone)(self.handle) };
                Self {
                    vtable: self.vtable,
                    handle: new_handle,
                }
            }
        }

        #[cfg(not(target_arch = "wasm32"))]
        #async_trait_attr
        impl #trait_name for #foreign_name {
            #(#foreign_impls)*
        }

        #[cfg(not(target_arch = "wasm32"))]
        static #vtable_static: std::sync::atomic::AtomicPtr<#vtable_name> =
            std::sync::atomic::AtomicPtr::new(std::ptr::null_mut());

        #[cfg(not(target_arch = "wasm32"))]
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn #register_fn(vtable: *const #vtable_name) {
            #vtable_static.store(vtable as *mut _, std::sync::atomic::Ordering::Release);
        }

        #[cfg(not(target_arch = "wasm32"))]
        #[unsafe(no_mangle)]
        pub extern "C" fn #create_fn(handle: u64) -> ::boltffi::__private::CallbackHandle {
            let vtable = #vtable_static.load(std::sync::atomic::Ordering::Acquire);
            if vtable.is_null() {
                return ::boltffi::__private::CallbackHandle::NULL;
            }
            ::boltffi::__private::CallbackHandle::new(handle, vtable as *const std::ffi::c_void)
        }

        #[cfg(target_arch = "wasm32")]
        #[link(wasm_import_module = "env")]
        unsafe extern "C" {
            fn #wasm_free_import(handle: u32);
            fn #wasm_clone_import(handle: u32) -> u32;
            #(#wasm_extern_imports)*
        }

        #[cfg(target_arch = "wasm32")]
        impl Drop for #foreign_name {
            fn drop(&mut self) {
                unsafe { #wasm_free_import(self.handle) };
            }
        }

        #[cfg(target_arch = "wasm32")]
        impl Clone for #foreign_name {
            fn clone(&self) -> Self {
                let new_handle = unsafe { #wasm_clone_import(self.handle) };
                Self { handle: new_handle }
            }
        }

        #[cfg(target_arch = "wasm32")]
        #async_trait_attr
        impl #trait_name for #foreign_name {
            #(#wasm_impl_bodies)*
        }

        #[cfg(target_arch = "wasm32")]
        #[unsafe(no_mangle)]
        pub extern "C" fn #wasm_create_fn(js_handle: u32) -> u32 {
            js_handle
        }

        #(#wasm_complete_exports)*
    };

    let concrete_impl = quote! {
        #[cfg(not(target_arch = "wasm32"))]
        impl ::boltffi::__private::ArcFromCallbackHandle for #foreign_name {
            unsafe fn arc_from_callback_handle(handle: ::boltffi::__private::CallbackHandle) -> std::sync::Arc<Self> {
                debug_assert!(!handle.is_null());
                std::sync::Arc::new(Self {
                    vtable: handle.vtable() as *const #vtable_name,
                    handle: handle.handle(),
                })
            }
        }

        #[cfg(not(target_arch = "wasm32"))]
        impl ::boltffi::__private::BoxFromCallbackHandle for #foreign_name {
            unsafe fn box_from_callback_handle(handle: ::boltffi::__private::CallbackHandle) -> Box<Self> {
                debug_assert!(!handle.is_null());
                Box::new(Self {
                    vtable: handle.vtable() as *const #vtable_name,
                    handle: handle.handle(),
                })
            }
        }

        #[cfg(target_arch = "wasm32")]
        impl ::boltffi::__private::ArcFromCallbackHandle for #foreign_name {
            unsafe fn arc_from_callback_handle(handle: ::boltffi::__private::CallbackHandle) -> std::sync::Arc<Self> {
                debug_assert!(!handle.is_null());
                std::sync::Arc::new(Self {
                    handle: handle.handle() as u32,
                })
            }
        }

        #[cfg(target_arch = "wasm32")]
        impl ::boltffi::__private::BoxFromCallbackHandle for #foreign_name {
            unsafe fn box_from_callback_handle(handle: ::boltffi::__private::CallbackHandle) -> Box<Self> {
                debug_assert!(!handle.is_null());
                Box::new(Self {
                    handle: handle.handle() as u32,
                })
            }
        }
    };

    let dyn_impl = if is_object_safe {
        quote! {
            #[cfg(not(target_arch = "wasm32"))]
            impl ::boltffi::__private::ArcFromCallbackHandle for dyn #trait_name {
                unsafe fn arc_from_callback_handle(handle: ::boltffi::__private::CallbackHandle) -> std::sync::Arc<Self> {
                    debug_assert!(!handle.is_null());
                    let foreign = #foreign_name {
                        vtable: handle.vtable() as *const #vtable_name,
                        handle: handle.handle(),
                    };
                    std::sync::Arc::new(foreign)
                }
            }

            #[cfg(not(target_arch = "wasm32"))]
            impl ::boltffi::__private::BoxFromCallbackHandle for dyn #trait_name {
                unsafe fn box_from_callback_handle(handle: ::boltffi::__private::CallbackHandle) -> Box<Self> {
                    debug_assert!(!handle.is_null());
                    let foreign = #foreign_name {
                        vtable: handle.vtable() as *const #vtable_name,
                        handle: handle.handle(),
                    };
                    Box::new(foreign)
                }
            }

            #[cfg(target_arch = "wasm32")]
            impl ::boltffi::__private::ArcFromCallbackHandle for dyn #trait_name {
                unsafe fn arc_from_callback_handle(handle: ::boltffi::__private::CallbackHandle) -> std::sync::Arc<Self> {
                    debug_assert!(!handle.is_null());
                    let foreign = #foreign_name {
                        handle: handle.handle() as u32,
                    };
                    std::sync::Arc::new(foreign)
                }
            }

            #[cfg(target_arch = "wasm32")]
            impl ::boltffi::__private::BoxFromCallbackHandle for dyn #trait_name {
                unsafe fn box_from_callback_handle(handle: ::boltffi::__private::CallbackHandle) -> Box<Self> {
                    debug_assert!(!handle.is_null());
                    let foreign = #foreign_name {
                        handle: handle.handle() as u32,
                    };
                    Box::new(foreign)
                }
            }
        }
    } else {
        quote! {}
    };

    let foreign_type_impl = if is_object_safe {
        quote! {
            impl ::boltffi::__private::CallbackForeignType for dyn #trait_name {
                type Foreign = #foreign_name;
            }
        }
    } else {
        quote! {}
    };

    let local_handle_impl = if is_object_safe && !has_async_methods {
        LocalHandleExpander::new(
            &item_trait,
            trait_name,
            &trait_name_snake,
            &vtable_name,
            &custom_types,
            &return_lowering,
        )
        .expand()?
    } else {
        quote! {}
    };

    Ok(quote! {
        #expanded
        #concrete_impl
        #dyn_impl
        #foreign_type_impl
        #local_handle_impl
    })
}
