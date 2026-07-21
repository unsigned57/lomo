use boltffi_ast::ConstantDef;
use boltffi_binding::{ConstantDecl, ConstantValueDecl};
use proc_macro2::TokenStream;

use crate::experimental::{
    error::Error,
    expansion::{DeclarationPair, Expansion},
    rust_api,
    surface::RenderSurface,
    wrapper::{self, Render, export, names},
};

pub struct Renderer<'expansion, 'lowered, S: RenderSurface> {
    pair: DeclarationPair<'lowered, ConstantDef, ConstantDecl<S>>,
    expansion: &'expansion Expansion<'lowered, S>,
}

impl<'expansion, 'lowered, S> Renderer<'expansion, 'lowered, S>
where
    S: RenderSurface,
    wrapper::arguments::SyncRenderer: Render<
            S,
            wrapper::arguments::Input<'expansion, 'lowered, S>,
            Output = wrapper::arguments::Tokens,
        >,
    wrapper::returns::Failure:
        Render<S, wrapper::returns::FailureInput<'expansion, 'lowered, S>, Output = TokenStream>,
    wrapper::returns::Renderer: Render<
            S,
            wrapper::returns::Input<'expansion, 'lowered, S>,
            Output = wrapper::returns::Tokens,
        >,
    wrapper::async_call::Renderer:
        Render<S, wrapper::async_call::Input<'expansion, 'lowered, S>, Output = TokenStream>,
{
    pub fn new(
        pair: DeclarationPair<'lowered, ConstantDef, ConstantDecl<S>>,
        expansion: &'expansion Expansion<'lowered, S>,
    ) -> Self {
        Self { pair, expansion }
    }

    pub fn render(self) -> Result<TokenStream, Error> {
        match self.pair.binding().value() {
            ConstantValueDecl::Inline { .. } => Ok(TokenStream::new()),
            ConstantValueDecl::Accessor { symbol, callable } => {
                let source = self.pair.source();
                export::Renderer::new(
                    symbol,
                    callable,
                    rust_api::Callable::constant(source),
                    export::RustCall::constant(Self::constant_ident(source)?),
                    export::ReceiverTokens::none(),
                    rust_api::VisibilityTokens::new(&source.source.visibility).into_tokens()?,
                    self.expansion,
                )
                .render()
            }
            _ => Err(Error::UnsupportedExpansion(
                "unknown constant value delivery",
            )),
        }
    }

    fn constant_ident(source: &ConstantDef) -> Result<syn::Ident, Error> {
        names::SourceSpelling::new(&source.name)
            .ident("source constant name is not a Rust identifier")
    }
}
