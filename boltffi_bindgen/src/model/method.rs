use boltffi_ffi_rules::callable::{CallableForm, ExecutionKind};
use serde::{Deserialize, Serialize};

use super::types::{Deprecation, Receiver, ReturnType, Type};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Method {
    pub name: String,
    pub receiver: Receiver,
    pub inputs: Vec<Parameter>,
    pub returns: ReturnType,
    pub execution_kind: ExecutionKind,
    pub doc: Option<String>,
    pub deprecated: Option<Deprecation>,
}

impl Method {
    pub fn new(name: impl Into<String>, receiver: Receiver) -> Self {
        Self {
            name: name.into(),
            receiver,
            inputs: Vec::new(),
            returns: ReturnType::Void,
            execution_kind: ExecutionKind::Sync,
            doc: None,
            deprecated: None,
        }
    }

    pub fn with_param(mut self, param: Parameter) -> Self {
        self.inputs.push(param);
        self
    }

    pub fn with_return(mut self, returns: ReturnType) -> Self {
        self.returns = returns;
        self
    }

    pub fn with_output(mut self, ty: Type) -> Self {
        self.returns = ReturnType::value(ty);
        self
    }

    pub fn make_async(mut self) -> Self {
        self.execution_kind = ExecutionKind::Async;
        self
    }

    pub fn with_doc(mut self, doc: impl Into<String>) -> Self {
        self.doc = Some(doc.into());
        self
    }

    pub fn maybe_doc(self, doc: Option<String>) -> Self {
        match doc {
            Some(d) => self.with_doc(d),
            None => self,
        }
    }

    pub fn maybe_async(self, is_async: bool) -> Self {
        if is_async { self.make_async() } else { self }
    }

    pub fn callable_form(&self) -> CallableForm {
        self.receiver.callable_form()
    }

    pub fn execution_kind(&self) -> ExecutionKind {
        self.execution_kind
    }

    pub fn is_async(&self) -> bool {
        self.execution_kind == ExecutionKind::Async
    }

    pub fn maybe_return(self, returns: Option<ReturnType>) -> Self {
        match returns {
            Some(r) => self.with_return(r),
            None => self,
        }
    }

    pub fn with_deprecated(mut self, deprecation: Deprecation) -> Self {
        self.deprecated = Some(deprecation);
        self
    }

    pub fn throws(&self) -> bool {
        self.returns.throws()
    }

    pub fn is_static(&self) -> bool {
        self.callable_form() == CallableForm::StaticMethod
    }

    pub fn is_mutating(&self) -> bool {
        self.receiver.is_mutable()
    }

    pub fn is_deprecated(&self) -> bool {
        self.deprecated.is_some()
    }

    pub fn has_return_value(&self) -> bool {
        self.returns.has_return_value()
    }

    pub fn has_callbacks(&self) -> bool {
        self.inputs
            .iter()
            .any(|p| matches!(p.param_type, Type::Closure(_)))
    }

    pub fn callback_params(&self) -> impl Iterator<Item = &Parameter> {
        self.inputs
            .iter()
            .filter(|p| matches!(p.param_type, Type::Closure(_)))
    }

    pub fn non_callback_params(&self) -> impl Iterator<Item = &Parameter> {
        self.inputs
            .iter()
            .filter(|p| !matches!(p.param_type, Type::Closure(_)))
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Parameter {
    pub name: String,
    pub param_type: Type,
}

impl Parameter {
    pub fn new(name: impl Into<String>, param_type: Type) -> Self {
        Self {
            name: name.into(),
            param_type,
        }
    }
}
