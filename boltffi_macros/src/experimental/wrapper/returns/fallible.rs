use boltffi_binding::{DirectValueType, ErrorDecl, OutOfRust, ReadPlan, ReturnDecl, ReturnPlan};
use proc_macro2::{Span, TokenStream};
use quote::quote;
use syn::Ident;

use crate::experimental::{
    error::Error,
    expansion::Expansion,
    rust_api,
    surface::RenderSurface,
    wrapper::{self, Render, names},
};

use super::{RustInvocation, Tokens, closure, encoded, handle};

pub struct Renderer;
pub struct Success;

pub struct Input<'expansion, 'lowered, S: RenderSurface> {
    returns: &'lowered ReturnDecl<S, OutOfRust>,
    error: &'lowered ErrorDecl<S, OutOfRust>,
    source: rust_api::Return<'lowered>,
    invocation: RustInvocation,
    expansion: &'expansion Expansion<'lowered, S>,
}

pub struct SuccessInput<'expansion, 'lowered, S: RenderSurface> {
    returns: &'lowered ReturnDecl<S, OutOfRust>,
    source: rust_api::Fallible<'lowered>,
    owner: Ident,
    span: Span,
    expansion: &'expansion Expansion<'lowered, S>,
}

impl<'expansion, 'lowered, S: RenderSurface> SuccessInput<'expansion, 'lowered, S> {
    pub fn new(
        returns: &'lowered ReturnDecl<S, OutOfRust>,
        source: rust_api::Fallible<'lowered>,
        owner: Ident,
        expansion: &'expansion Expansion<'lowered, S>,
    ) -> Self {
        let span = owner.span();
        Self {
            returns,
            source,
            owner,
            span,
            expansion,
        }
    }
}

impl<'expansion, 'lowered, S: RenderSurface> Input<'expansion, 'lowered, S> {
    pub fn new(
        returns: &'lowered ReturnDecl<S, OutOfRust>,
        error: &'lowered ErrorDecl<S, OutOfRust>,
        source: rust_api::Return<'lowered>,
        invocation: RustInvocation,
        expansion: &'expansion Expansion<'lowered, S>,
    ) -> Self {
        Self {
            returns,
            error,
            source,
            invocation,
            expansion,
        }
    }
}

impl<'expansion, 'lowered, S> Render<S, Input<'expansion, 'lowered, S>> for Renderer
where
    S: RenderSurface,
    encoded::Renderer: Render<S, encoded::Input<'expansion, 'lowered, 'lowered, S>, Output = encoded::Tokens>
        + Render<S, encoded::Empty<S>, Output = encoded::Tokens>,
    Success: Render<S, SuccessInput<'expansion, 'lowered, S>, Output = SuccessTokens>,
    handle::Value: Render<
            S,
            handle::ValueInput<'expansion, 'lowered, S, S::HandleCarrier>,
            Output = handle::ValueTokens,
        >,
{
    type Output = Tokens;

    fn render(self, input: Input<'expansion, 'lowered, S>) -> Result<Self::Output, Error> {
        match input.error {
            ErrorDecl::EncodedViaReturnSlot { codec, shape, .. } => EncodedError::new(
                input.returns,
                codec,
                *shape,
                input.source.fallible()?,
                input.invocation,
                input.expansion,
            )
            .tokens(),
            ErrorDecl::StatusViaReturnSlot { .. } => {
                Err(Error::UnsupportedExpansion("status error return"))
            }
            ErrorDecl::StatusViaOutPointer { .. } => {
                Err(Error::UnsupportedExpansion("status error out-pointer"))
            }
            ErrorDecl::EncodedViaOutPointer { .. } => {
                Err(Error::UnsupportedExpansion("encoded error out-pointer"))
            }
            ErrorDecl::None(_) => Err(Error::UnsupportedExpansion("missing error channel")),
            _ => Err(Error::UnsupportedExpansion("unknown error channel")),
        }
    }
}

struct EncodedError<'expansion, 'lowered, S: RenderSurface> {
    returns: &'lowered ReturnDecl<S, OutOfRust>,
    error_codec: &'lowered ReadPlan,
    error_shape: S::BufferShape,
    source: rust_api::Fallible<'lowered>,
    invocation: RustInvocation,
    expansion: &'expansion Expansion<'lowered, S>,
}

impl<'expansion, 'lowered, S: RenderSurface> EncodedError<'expansion, 'lowered, S> {
    fn new(
        returns: &'lowered ReturnDecl<S, OutOfRust>,
        error_codec: &'lowered ReadPlan,
        error_shape: S::BufferShape,
        source: rust_api::Fallible<'lowered>,
        invocation: RustInvocation,
        expansion: &'expansion Expansion<'lowered, S>,
    ) -> Self {
        Self {
            returns,
            error_codec,
            error_shape,
            source,
            invocation,
            expansion,
        }
    }

    fn tokens(self) -> Result<Tokens, Error>
    where
        encoded::Renderer: Render<S, encoded::Input<'expansion, 'lowered, 'lowered, S>, Output = encoded::Tokens>
            + Render<S, encoded::Empty<S>, Output = encoded::Tokens>,
        Success: Render<S, SuccessInput<'expansion, 'lowered, S>, Output = SuccessTokens>,
        handle::Value: Render<
                S,
                handle::ValueInput<'expansion, 'lowered, S, S::HandleCarrier>,
                Output = handle::ValueTokens,
            >,
    {
        let locals = names::Locals::new(self.invocation.span);
        let error_ident = locals.error();
        let error = <encoded::Renderer as Render<S, _>>::render(
            encoded::Renderer,
            encoded::Input::new(
                self.error_codec,
                self.error_shape,
                error_ident.clone(),
                self.expansion,
            ),
        )?;
        let empty_error = <encoded::Renderer as Render<S, _>>::render(
            encoded::Renderer,
            encoded::Empty::new(self.error_shape),
        )?;
        let return_type = error.return_type().clone();
        let error_value = error.value();
        let empty_error_value = empty_error.value();
        let success = <Success as Render<S, _>>::render(
            Success,
            SuccessInput::new(
                self.returns,
                self.source,
                self.invocation.owner.clone(),
                self.expansion,
            ),
        )?;
        let (success_items, success_ffi_parameters, success_pattern, success_body) =
            success.into_parts();
        let RustInvocation {
            span,
            call,
            conversions,
            writebacks,
            ..
        } = self.invocation;
        let result = names::Locals::new(span).result();
        let result_value = if writebacks.is_empty() {
            quote! { #call }
        } else {
            quote! {
                {
                    let #result = #call;
                    #(#writebacks)*
                    #result
                }
            }
        };

        Ok(Tokens {
            items: success_items,
            ffi_parameters: success_ffi_parameters,
            return_type,
            body: quote! {
                #(#conversions)*
                match #result_value {
                    Ok(#success_pattern) => {
                        #success_body
                        #empty_error_value
                    }
                    Err(#error_ident) => {
                        #error_value
                    }
                }
            },
        })
    }
}

impl<'expansion, 'lowered, S> Render<S, SuccessInput<'expansion, 'lowered, S>> for Success
where
    S: RenderSurface,
    encoded::Renderer:
        Render<S, encoded::Input<'expansion, 'lowered, 'lowered, S>, Output = encoded::Tokens>,
    closure::Write:
        Render<S, closure::WriteInput<'expansion, 'lowered, S>, Output = closure::WriteTokens>,
    handle::Value: Render<
            S,
            handle::ValueInput<'expansion, 'lowered, S, S::HandleCarrier>,
            Output = handle::ValueTokens,
        >,
{
    type Output = SuccessTokens;

    fn render(self, input: SuccessInput<'expansion, 'lowered, S>) -> Result<Self::Output, Error> {
        let locals = names::Locals::new(input.span);
        let success_ident = locals.success();
        match input.returns.plan() {
            ReturnPlan::Void => Ok(SuccessTokens {
                items: Vec::new(),
                ffi_parameters: Vec::new(),
                pattern: quote! { () },
                body: TokenStream::new(),
            }),
            ReturnPlan::DirectViaOutPointer {
                ty: DirectValueType::Primitive(primitive),
            } => {
                let out = locals.return_out();
                let ty = wrapper::type_ref::Renderer.primitive(*primitive)?;
                Ok(SuccessTokens {
                    items: Vec::new(),
                    ffi_parameters: vec![quote! { #out: *mut #ty }],
                    pattern: quote! { #success_ident },
                    body: quote! {
                        if !#out.is_null() {
                            unsafe {
                                ::core::ptr::write(#out, #success_ident);
                            }
                        }
                    },
                })
            }
            ReturnPlan::DirectViaOutPointer { .. } => {
                let out = locals.return_out();
                let ok = input.source.ok_written_type()?;
                Ok(SuccessTokens {
                    items: Vec::new(),
                    ffi_parameters: vec![quote! {
                        #out: *mut <#ok as ::boltffi::__private::Passable>::Out
                    }],
                    pattern: quote! { #success_ident },
                    body: quote! {
                        if !#out.is_null() {
                            unsafe {
                                ::core::ptr::write(
                                    #out,
                                    <#ok as ::boltffi::__private::Passable>::pack(#success_ident),
                                );
                            }
                        }
                    },
                })
            }
            ReturnPlan::EncodedViaOutPointer { codec, shape, .. } => {
                let out = locals.return_out();
                let encoded = <encoded::Renderer as Render<S, _>>::render(
                    encoded::Renderer,
                    encoded::Input::new(codec, *shape, success_ident.clone(), input.expansion),
                )?;
                let out_ty = encoded.return_type_without_arrow();
                let encoded_value = encoded.value();
                Ok(SuccessTokens {
                    items: Vec::new(),
                    ffi_parameters: vec![quote! { #out: *mut #out_ty }],
                    pattern: quote! { #success_ident },
                    body: quote! {
                        if !#out.is_null() {
                            unsafe {
                                ::core::ptr::write(#out, #encoded_value);
                            }
                        }
                    },
                })
            }
            ReturnPlan::HandleViaOutPointer {
                target,
                carrier,
                presence,
            } => {
                let handle_return = input.source.ok_handle_return(target, *presence)?;
                let out = locals.return_out();
                let handle = <handle::Value as Render<S, _>>::render(
                    handle::Value,
                    handle::ValueInput::new(
                        input.expansion,
                        target,
                        *carrier,
                        *presence,
                        success_ident.clone(),
                        handle_return,
                    ),
                )?;
                let out_ty = handle.ty();
                let handle_value = handle.value();
                Ok(SuccessTokens {
                    items: Vec::new(),
                    ffi_parameters: vec![quote! { #out: *mut #out_ty }],
                    pattern: quote! { #success_ident },
                    body: quote! {
                        if !#out.is_null() {
                            unsafe {
                                ::core::ptr::write(#out, #handle_value);
                            }
                        }
                    },
                })
            }
            ReturnPlan::ClosureViaOutPointer(closure) => {
                let source_closure = input.source.ok_closure(closure.presence())?;
                let writer = <closure::Write as Render<S, _>>::render(
                    closure::Write,
                    closure::WriteInput::success(
                        closure,
                        source_closure,
                        success_ident.clone(),
                        input.owner,
                        input.expansion,
                    ),
                )?;
                let (items, ffi_parameters, body) = writer.into_parts();
                Ok(SuccessTokens {
                    items,
                    ffi_parameters,
                    pattern: quote! { #success_ident },
                    body,
                })
            }
            _ => Err(Error::UnsupportedExpansion("fallible return shape")),
        }
    }
}

pub struct SuccessTokens {
    items: Vec<TokenStream>,
    ffi_parameters: Vec<TokenStream>,
    pattern: TokenStream,
    body: TokenStream,
}

impl SuccessTokens {
    pub fn into_parts(self) -> (Vec<TokenStream>, Vec<TokenStream>, TokenStream, TokenStream) {
        (self.items, self.ffi_parameters, self.pattern, self.body)
    }
}
