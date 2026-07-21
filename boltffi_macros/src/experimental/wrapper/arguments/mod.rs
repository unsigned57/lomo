use boltffi_binding::{ExecutionDecl, ExportedCallable};
use proc_macro2::TokenStream;

use crate::experimental::{
    error::Error,
    expansion::Expansion,
    rust_api,
    surface::RenderSurface,
    wrapper::{self, Render},
};

pub struct SyncRenderer;
pub struct AsyncRenderer;

pub struct Input<'expansion, 'lowered, S: RenderSurface> {
    callable: &'lowered ExportedCallable<S>,
    source: rust_api::Callable<'lowered>,
    failure: TokenStream,
    expansion: &'expansion Expansion<'lowered, S>,
}

impl<'expansion, 'lowered, S: RenderSurface> Input<'expansion, 'lowered, S> {
    pub fn new(
        callable: &'lowered ExportedCallable<S>,
        source: rust_api::Callable<'lowered>,
        failure: TokenStream,
        expansion: &'expansion Expansion<'lowered, S>,
    ) -> Self {
        Self {
            callable,
            source,
            failure,
            expansion,
        }
    }

    fn render(self) -> Result<Tokens, Error>
    where
        wrapper::param::Renderer: Render<
                S,
                wrapper::param::Input<'expansion, 'lowered, S>,
                Output = wrapper::param::Tokens,
            >,
    {
        let binding = self.callable;
        if binding.params().len() != self.source.parameter_count() {
            return Err(Error::SourceSyntaxMismatch(
                "source parameter count does not match binding parameter count",
            ));
        }

        let params = binding
            .params()
            .iter()
            .zip(self.source.parameters())
            .map(|(param, source)| {
                <wrapper::param::Renderer as Render<S, _>>::render(
                    wrapper::param::Renderer,
                    wrapper::param::Input::new(param, source, self.failure.clone(), self.expansion),
                )
            })
            .collect::<Result<Vec<_>, _>>()?;
        let items = params
            .iter()
            .flat_map(|param| param.items().iter().cloned())
            .collect();
        let ffi_parameters = params
            .iter()
            .flat_map(|param| param.ffi_parameters().iter().cloned())
            .collect();
        let conversions = params
            .iter()
            .flat_map(|param| param.conversions().iter().cloned())
            .collect();
        let writebacks = params
            .iter()
            .flat_map(|param| param.writebacks().iter().cloned())
            .collect();
        let rust_arguments = params
            .iter()
            .map(|param| param.argument().clone())
            .collect();

        Ok(Tokens {
            items,
            ffi_parameters,
            conversions,
            writebacks,
            rust_arguments,
        })
    }
}

pub struct Tokens {
    items: Vec<TokenStream>,
    ffi_parameters: Vec<TokenStream>,
    conversions: Vec<TokenStream>,
    writebacks: Vec<TokenStream>,
    rust_arguments: Vec<TokenStream>,
}

impl Tokens {
    pub fn items(&self) -> &[TokenStream] {
        &self.items
    }

    pub fn ffi_parameters(&self) -> &[TokenStream] {
        &self.ffi_parameters
    }

    pub fn conversions(&self) -> &[TokenStream] {
        &self.conversions
    }

    pub fn writebacks(&self) -> &[TokenStream] {
        &self.writebacks
    }

    pub fn rust_arguments(&self) -> &[TokenStream] {
        &self.rust_arguments
    }
}

impl<'expansion, 'lowered, S> Render<S, Input<'expansion, 'lowered, S>> for SyncRenderer
where
    S: RenderSurface,
    wrapper::param::Renderer:
        Render<S, wrapper::param::Input<'expansion, 'lowered, S>, Output = wrapper::param::Tokens>,
{
    type Output = Tokens;

    fn render(self, input: Input<'expansion, 'lowered, S>) -> Result<Self::Output, Error> {
        match input.callable.execution() {
            ExecutionDecl::Synchronous(_) => {}
            ExecutionDecl::Asynchronous(_) => {
                return Err(Error::UnsupportedExpansion("async function"));
            }
            _ => return Err(Error::UnsupportedExpansion("unknown execution")),
        }

        input.render()
    }
}

impl<'expansion, 'lowered, S> Render<S, Input<'expansion, 'lowered, S>> for AsyncRenderer
where
    S: RenderSurface,
    wrapper::param::Renderer:
        Render<S, wrapper::param::Input<'expansion, 'lowered, S>, Output = wrapper::param::Tokens>,
{
    type Output = Tokens;

    fn render(self, input: Input<'expansion, 'lowered, S>) -> Result<Self::Output, Error> {
        match input.callable.execution() {
            ExecutionDecl::Asynchronous(_) => {}
            ExecutionDecl::Synchronous(_) => {
                return Err(Error::UnsupportedExpansion("sync function"));
            }
            _ => return Err(Error::UnsupportedExpansion("unknown execution")),
        }

        input.render()
    }
}
