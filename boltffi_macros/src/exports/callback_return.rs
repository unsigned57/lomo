use proc_macro2::TokenStream;
use quote::quote;
use syn::{ReturnType, Type};

use crate::index::callback_traits::CallbackTraitRegistry;
use crate::lowering::transport::{StandardContainer, TypeShapeExt};

#[derive(Clone)]
pub(crate) struct SyncCallbackReturn {
    ownership: CallbackReturnOwnership,
    is_optional: bool,
    local_handle_path: syn::Path,
}

#[derive(Clone, Copy)]
enum CallbackReturnOwnership {
    Boxed,
    Shared,
}

#[derive(Clone)]
struct CallbackReturnDescriptor {
    trait_path: syn::Path,
    shape: CallbackReturnShape,
}

#[derive(Clone, Copy)]
enum CallbackReturnShape {
    Direct { ownership: CallbackReturnOwnership },
    Optional { ownership: CallbackReturnOwnership },
}

#[derive(Clone)]
struct CallbackTraitObject {
    trait_path: syn::Path,
    ownership: CallbackReturnOwnership,
}

impl SyncCallbackReturn {
    pub(crate) fn native_ffi_return_type(&self) -> TokenStream {
        quote! { ::boltffi::__private::CallbackHandle }
    }

    pub(crate) fn wasm_ffi_return_type(&self) -> TokenStream {
        quote! { u32 }
    }

    pub(crate) fn native_invalid_arg_early_return_statement(&self) -> TokenStream {
        quote! {
            return ::boltffi::__private::CallbackHandle::NULL;
        }
    }

    pub(crate) fn wasm_invalid_arg_early_return_statement(&self) -> TokenStream {
        quote! {
            return 0u32;
        }
    }

    pub(crate) fn lower_native_result_expression(
        &self,
        callback_expression: TokenStream,
    ) -> TokenStream {
        let local_handle_path = &self.local_handle_path;

        match (self.ownership, self.is_optional) {
            (CallbackReturnOwnership::Boxed, false) => quote! {
                #local_handle_path(::std::sync::Arc::from(#callback_expression))
            },
            (CallbackReturnOwnership::Shared, false) => quote! {
                #local_handle_path(#callback_expression)
            },
            (CallbackReturnOwnership::Boxed, true) => quote! {
                #callback_expression
                    .map(|callback_impl| #local_handle_path(::std::sync::Arc::from(callback_impl)))
                    .unwrap_or(::boltffi::__private::CallbackHandle::NULL)
            },
            (CallbackReturnOwnership::Shared, true) => quote! {
                #callback_expression
                    .map(#local_handle_path)
                    .unwrap_or(::boltffi::__private::CallbackHandle::NULL)
            },
        }
    }

    pub(crate) fn lower_wasm_result_expression(
        &self,
        callback_expression: TokenStream,
    ) -> TokenStream {
        let local_handle_expression = self.lower_native_result_expression(callback_expression);
        quote! {
            (#local_handle_expression).handle() as u32
        }
    }
}

impl CallbackReturnDescriptor {
    fn parse(rust_type: &Type) -> Option<Self> {
        CallbackTraitObject::parse(rust_type)
            .map(Self::direct)
            .or_else(|| Self::parse_optional(rust_type))
    }

    fn direct(trait_object: CallbackTraitObject) -> Self {
        Self {
            trait_path: trait_object.trait_path,
            shape: CallbackReturnShape::Direct {
                ownership: trait_object.ownership,
            },
        }
    }

    fn parse_optional(rust_type: &Type) -> Option<Self> {
        let Some(StandardContainer::Option(inner_type)) =
            rust_type.type_descriptor().standard_container()
        else {
            return None;
        };
        let trait_object = CallbackTraitObject::parse(inner_type)?;
        Some(Self {
            trait_path: trait_object.trait_path,
            shape: CallbackReturnShape::Optional {
                ownership: trait_object.ownership,
            },
        })
    }

    fn resolve(
        self,
        rust_type: &Type,
        callback_registry: &CallbackTraitRegistry,
    ) -> syn::Result<Option<SyncCallbackReturn>> {
        let Some(resolution) = callback_registry.resolve(&self.trait_path) else {
            return Ok(None);
        };

        if !resolution.supports_local_handle {
            return Err(syn::Error::new_spanned(
                rust_type,
                "boltffi: sync callback returns require an object-safe exported callback trait without async methods",
            ));
        }

        Ok(Some(SyncCallbackReturn {
            ownership: self.shape.ownership(),
            is_optional: self.shape.is_optional(),
            local_handle_path: resolution.local_handle_path,
        }))
    }
}

impl CallbackReturnShape {
    fn ownership(self) -> CallbackReturnOwnership {
        match self {
            Self::Direct { ownership } | Self::Optional { ownership } => ownership,
        }
    }

    fn is_optional(self) -> bool {
        matches!(self, Self::Optional { .. })
    }
}

impl CallbackTraitObject {
    fn parse(rust_type: &Type) -> Option<Self> {
        Self::parse_container(rust_type, "Box", CallbackReturnOwnership::Boxed)
            .or_else(|| Self::parse_container(rust_type, "Arc", CallbackReturnOwnership::Shared))
    }

    fn parse_container(
        rust_type: &Type,
        container_name: &str,
        ownership: CallbackReturnOwnership,
    ) -> Option<Self> {
        let Type::Path(type_path) = rust_type else {
            return None;
        };
        let container_segment = type_path.path.segments.last()?;
        if container_segment.ident != container_name {
            return None;
        }
        let syn::PathArguments::AngleBracketed(arguments) = &container_segment.arguments else {
            return None;
        };
        let inner_type = arguments.args.iter().find_map(|argument| match argument {
            syn::GenericArgument::Type(inner_type) => Some(inner_type),
            _ => None,
        })?;
        let Type::TraitObject(trait_object) = inner_type else {
            return None;
        };
        let trait_path = trait_object.bounds.iter().find_map(|bound| match bound {
            syn::TypeParamBound::Trait(trait_bound) => Some(trait_bound.path.clone()),
            _ => None,
        })?;
        Some(Self {
            trait_path,
            ownership,
        })
    }
}

pub(crate) fn resolve_sync_callback_return(
    output: &ReturnType,
    callback_registry: &CallbackTraitRegistry,
) -> syn::Result<Option<SyncCallbackReturn>> {
    let ReturnType::Type(_, rust_type) = output else {
        return Ok(None);
    };

    let Some(descriptor) = CallbackReturnDescriptor::parse(rust_type) else {
        return Ok(None);
    };

    descriptor.resolve(rust_type, callback_registry)
}

#[cfg(test)]
mod tests {
    use quote::ToTokens;
    use syn::{Type, parse_quote};

    use super::{CallbackReturnDescriptor, CallbackReturnOwnership, CallbackReturnShape};

    #[test]
    fn parses_direct_boxed_callback_return() {
        let rust_type: Type = parse_quote!(Box<dyn ProgressListener>);
        let descriptor =
            CallbackReturnDescriptor::parse(&rust_type).expect("callback return should parse");

        assert_eq!(
            descriptor.trait_path.to_token_stream().to_string(),
            "ProgressListener"
        );
        assert!(matches!(
            descriptor.shape,
            CallbackReturnShape::Direct {
                ownership: CallbackReturnOwnership::Boxed
            }
        ));
    }

    #[test]
    fn parses_optional_shared_callback_return() {
        let rust_type: Type = parse_quote!(Option<Arc<dyn ProgressListener>>);
        let descriptor =
            CallbackReturnDescriptor::parse(&rust_type).expect("callback return should parse");

        assert_eq!(
            descriptor.trait_path.to_token_stream().to_string(),
            "ProgressListener"
        );
        assert!(matches!(
            descriptor.shape,
            CallbackReturnShape::Optional {
                ownership: CallbackReturnOwnership::Shared
            }
        ));
    }

    #[test]
    fn ignores_non_callback_returns() {
        let rust_type: Type = parse_quote!(String);

        assert!(CallbackReturnDescriptor::parse(&rust_type).is_none());
    }
}
