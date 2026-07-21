use quote::quote;

use crate::lowering::returns::model::DirectBufferReturnMethod;

#[derive(Clone, Copy)]
pub(crate) enum ExportSafety {
    Safe,
    Unsafe,
}

pub(crate) enum ReceiverParameter<'a> {
    None,
    Handle(&'a syn::Ident),
}

pub(crate) enum ExportCondition {
    Wasm,
    NonWasm,
    Always,
}

pub(crate) struct ExportBody {
    pub(crate) return_type: proc_macro2::TokenStream,
    pub(crate) body: proc_macro2::TokenStream,
}

pub(crate) struct ExternExport<'a> {
    pub(crate) visibility: &'a syn::Visibility,
    pub(crate) export_name: &'a syn::Ident,
    pub(crate) safety: ExportSafety,
    pub(crate) receiver: ReceiverParameter<'a>,
    pub(crate) params: &'a [proc_macro2::TokenStream],
    pub(crate) allow_ptr_deref: bool,
    pub(crate) body: ExportBody,
}

pub(crate) struct DualPlatformExternExport<'a> {
    pub(crate) wasm: ExternExport<'a>,
    pub(crate) native: ExternExport<'a>,
}

pub(crate) struct DirectBufferCarrier {
    return_method: DirectBufferReturnMethod,
}

impl DirectBufferCarrier {
    pub(crate) fn new(return_method: DirectBufferReturnMethod) -> Self {
        Self { return_method }
    }

    pub(crate) fn return_type(&self) -> proc_macro2::TokenStream {
        match self.return_method {
            DirectBufferReturnMethod::Packed => quote! { u64 },
            DirectBufferReturnMethod::Descriptor => quote! { ::boltffi::__private::FfiBuf },
        }
    }

    pub(crate) fn lower_body(
        &self,
        encode_body: proc_macro2::TokenStream,
    ) -> proc_macro2::TokenStream {
        match self.return_method {
            DirectBufferReturnMethod::Packed => quote! {
                let __boltffi_buf: ::boltffi::__private::FfiBuf = { #encode_body };
                __boltffi_buf.into_packed()
            },
            DirectBufferReturnMethod::Descriptor => encode_body,
        }
    }
}

impl<'a> DualPlatformExternExport<'a> {
    pub(crate) fn render(self) -> proc_macro2::TokenStream {
        let wasm_export = self.wasm.render(ExportCondition::Wasm);
        let native_export = self.native.render(ExportCondition::NonWasm);
        quote! {
            #wasm_export
            #native_export
        }
    }
}

impl<'a> ExternExport<'a> {
    pub(crate) fn async_entry(
        visibility: &'a syn::Visibility,
        export_name: &'a syn::Ident,
        params: &'a [proc_macro2::TokenStream],
        body: proc_macro2::TokenStream,
    ) -> Self {
        let safety = if params.is_empty() {
            ExportSafety::Safe
        } else {
            ExportSafety::Unsafe
        };

        Self {
            visibility,
            export_name,
            safety,
            receiver: ReceiverParameter::None,
            params,
            allow_ptr_deref: false,
            body: ExportBody {
                return_type: quote! { -> ::boltffi::__private::RustFutureHandle },
                body,
            },
        }
    }

    pub(crate) fn render(self, condition: ExportCondition) -> proc_macro2::TokenStream {
        let visibility = self.visibility;
        let export_name = self.export_name;
        let return_type = self.body.return_type;
        let body = self.body.body;
        let receiver = self.receiver.parameter_tokens();
        let params = self.params;
        let cfg_attr = match condition {
            ExportCondition::Wasm => Some(quote! { #[cfg(target_arch = "wasm32")] }),
            ExportCondition::NonWasm => Some(quote! { #[cfg(not(target_arch = "wasm32"))] }),
            ExportCondition::Always => None,
        };
        let allow_attr = self.allow_ptr_deref.then(|| {
            quote! { #[allow(clippy::not_unsafe_ptr_arg_deref)] }
        });
        let safety = match self.safety {
            ExportSafety::Safe => quote! {},
            ExportSafety::Unsafe => quote! { unsafe },
        };
        let signature_params = match (receiver, params.is_empty()) {
            (Some(receiver_parameter), true) => quote! { #receiver_parameter },
            (Some(receiver_parameter), false) => quote! { #receiver_parameter, #(#params),* },
            (None, true) => quote! {},
            (None, false) => quote! { #(#params),* },
        };

        quote! {
            #cfg_attr
            #allow_attr
            #[unsafe(no_mangle)]
            #visibility #safety extern "C" fn #export_name(
                #signature_params
            ) #return_type {
                #body
            }
        }
    }
}

impl<'a> ReceiverParameter<'a> {
    fn parameter_tokens(self) -> Option<proc_macro2::TokenStream> {
        match self {
            Self::None => None,
            Self::Handle(type_name) => Some(quote! { handle: *mut #type_name }),
        }
    }
}
