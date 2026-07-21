use boltffi_ffi_rules::callable::ExecutionKind;
use serde::{Deserialize, Serialize};

use super::types::{Deprecation, ReturnType, Type};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CallbackTrait {
    pub name: String,
    pub methods: Vec<TraitMethod>,
    pub doc: Option<String>,
    pub deprecated: Option<Deprecation>,
}

impl CallbackTrait {
    pub fn new(name: impl Into<String>) -> Self {
        Self {
            name: name.into(),
            methods: Vec::new(),
            doc: None,
            deprecated: None,
        }
    }

    pub fn with_method(mut self, method: TraitMethod) -> Self {
        self.methods.push(method);
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

    pub fn sync_methods(&self) -> impl Iterator<Item = &TraitMethod> {
        self.methods
            .iter()
            .filter(|method| method.execution_kind == ExecutionKind::Sync)
    }

    pub fn async_methods(&self) -> impl Iterator<Item = &TraitMethod> {
        self.methods
            .iter()
            .filter(|method| method.execution_kind == ExecutionKind::Async)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TraitMethod {
    pub name: String,
    pub inputs: Vec<TraitMethodParam>,
    pub returns: ReturnType,
    pub execution_kind: ExecutionKind,
    pub doc: Option<String>,
}

impl TraitMethod {
    pub fn new(name: impl Into<String>) -> Self {
        Self {
            name: name.into(),
            inputs: Vec::new(),
            returns: ReturnType::Void,
            execution_kind: ExecutionKind::Sync,
            doc: None,
        }
    }

    pub fn with_param(mut self, param: TraitMethodParam) -> Self {
        self.inputs.push(param);
        self
    }

    pub fn with_return(mut self, returns: ReturnType) -> Self {
        self.returns = returns;
        self
    }

    pub fn with_doc(mut self, doc: impl Into<String>) -> Self {
        self.doc = Some(doc.into());
        self
    }

    pub fn make_async(mut self) -> Self {
        self.execution_kind = ExecutionKind::Async;
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

    pub fn is_async(&self) -> bool {
        self.execution_kind == ExecutionKind::Async
    }

    pub fn maybe_return(self, returns: Option<ReturnType>) -> Self {
        match returns {
            Some(r) => self.with_return(r),
            None => self,
        }
    }

    pub fn throws(&self) -> bool {
        self.returns.throws()
    }

    pub fn has_return(&self) -> bool {
        self.returns.has_return_value()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TraitMethodParam {
    pub name: String,
    pub param_type: Type,
}

impl TraitMethodParam {
    pub fn new(name: impl Into<String>, param_type: Type) -> Self {
        Self {
            name: name.into(),
            param_type,
        }
    }
}
