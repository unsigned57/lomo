use askama::Template as AskamaTemplate;

use crate::{
    bridge::{
        c::{Identifier, Literal, Statement},
        python_cext::{LoadedFunction, PythonCExtBridgeContract},
    },
    core::Result,
};

#[derive(AskamaTemplate)]
#[template(path = "bridge/python_cext/loader.c", escape = "none")]
struct LoaderTemplate {
    c_header: Literal,
    loader_function: Identifier,
    free_function: Identifier,
    functions: Vec<FunctionView>,
}

struct FunctionView {
    symbol: Literal,
    typedef_name: Identifier,
    typedef_declaration: Statement,
    storage_name: Identifier,
}

pub struct Loader;

impl Loader {
    pub fn render(contract: &PythonCExtBridgeContract) -> Result<String> {
        Ok(LoaderTemplate {
            c_header: Literal::string(contract.c_header().as_str()),
            loader_function: contract.loader_method().c_function().clone(),
            free_function: contract.symbols().free_function().clone(),
            functions: contract
                .functions()
                .iter()
                .map(FunctionView::from_function)
                .collect::<Result<Vec<_>>>()?,
        }
        .render()?)
    }
}

impl FunctionView {
    fn from_function(function: &LoadedFunction) -> Result<Self> {
        Ok(Self {
            symbol: Literal::string(function.function().name()),
            typedef_name: function.typedef_name().clone(),
            typedef_declaration: Statement::function_pointer_typedef(
                function.function(),
                function.typedef_name().as_str(),
            )?,
            storage_name: function.storage_name().clone(),
        })
    }
}
