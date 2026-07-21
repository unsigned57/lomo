use std::collections::HashMap;

#[derive(Debug, Clone)]
pub struct FfiContract {
    pub module_name: String,
    pub prefix: String,
    pub functions: HashMap<String, FfiFunction>,
    pub classes: HashMap<String, FfiClass>,
    pub callback_bridges: Vec<CallbackBridge>,
}

impl FfiContract {
    pub fn new(module_name: impl Into<String>, prefix: impl Into<String>) -> Self {
        Self {
            module_name: module_name.into(),
            prefix: prefix.into(),
            functions: HashMap::new(),
            classes: HashMap::new(),
            callback_bridges: Vec::new(),
        }
    }

    pub fn add_function(&mut self, func: FfiFunction) {
        self.functions.insert(func.ffi_name.clone(), func);
    }

    pub fn add_class(&mut self, class: FfiClass) {
        self.classes.insert(class.name.clone(), class);
    }

    pub fn add_callback_bridge(&mut self, bridge: CallbackBridge) {
        self.callback_bridges.push(bridge);
    }

    pub fn get_function(&self, ffi_name: &str) -> Option<&FfiFunction> {
        self.functions.get(ffi_name)
    }

    pub fn get_class(&self, name: &str) -> Option<&FfiClass> {
        self.classes.get(name)
    }

    pub fn is_callback_bridge_retain(&self, pattern: &str) -> bool {
        let is_known_bridge = self
            .callback_bridges
            .iter()
            .any(|b| pattern.contains(&b.bridge_class));

        let is_async_pattern = pattern.contains("ContinuationBox")
            || pattern.contains("Continuation(")
            || (pattern.contains("passRetained(") && pattern.contains("box"))
            || (pattern.contains("passRetained(") && pattern.contains("Box"));

        is_known_bridge || is_async_pattern
    }

    pub fn is_destructor(&self, ffi_name: &str) -> bool {
        self.functions
            .get(ffi_name)
            .map(|f| matches!(f.semantics, FunctionSemantics::Destructor { .. }))
            .unwrap_or(false)
    }

    pub fn is_constructor(&self, ffi_name: &str) -> bool {
        self.functions
            .get(ffi_name)
            .map(|f| matches!(f.semantics, FunctionSemantics::Constructor { .. }))
            .unwrap_or(false)
    }
}

impl Default for FfiContract {
    fn default() -> Self {
        Self::new("unknown", "ffi")
    }
}

#[derive(Debug, Clone)]
pub struct FfiFunction {
    pub rust_name: String,
    pub ffi_name: String,
    pub inputs: Vec<FfiParam>,
    pub output: FfiOutput,
    pub semantics: FunctionSemantics,
}

impl FfiFunction {
    pub fn new(rust_name: impl Into<String>, ffi_name: impl Into<String>) -> Self {
        Self {
            rust_name: rust_name.into(),
            ffi_name: ffi_name.into(),
            inputs: Vec::new(),
            output: FfiOutput::Void,
            semantics: FunctionSemantics::Pure,
        }
    }

    pub fn with_output(mut self, output: FfiOutput) -> Self {
        self.output = output;
        self
    }

    pub fn with_semantics(mut self, semantics: FunctionSemantics) -> Self {
        self.semantics = semantics;
        self
    }

    pub fn with_param(mut self, param: FfiParam) -> Self {
        self.inputs.push(param);
        self
    }

    pub fn returns_owned_memory(&self) -> bool {
        matches!(
            self.output,
            FfiOutput::OutParam {
                ownership: Ownership::Returned,
                ..
            }
        )
    }

    pub fn takes_ownership(&self) -> bool {
        self.inputs.iter().any(|p| p.ownership == Ownership::Owned)
    }
}

#[derive(Debug, Clone)]
pub struct FfiClass {
    pub name: String,
    pub handle_type: String,
    pub constructor: Option<String>,
    pub destructor: Option<String>,
    pub methods: Vec<String>,
}

impl FfiClass {
    pub fn new(name: impl Into<String>) -> Self {
        Self {
            name: name.into(),
            handle_type: "UInt64".to_string(),
            constructor: None,
            destructor: None,
            methods: Vec::new(),
        }
    }

    pub fn with_destructor(mut self, ffi_name: impl Into<String>) -> Self {
        self.destructor = Some(ffi_name.into());
        self
    }

    pub fn with_constructor(mut self, ffi_name: impl Into<String>) -> Self {
        self.constructor = Some(ffi_name.into());
        self
    }
}

#[derive(Debug, Clone)]
pub struct CallbackBridge {
    pub trait_name: String,
    pub bridge_class: String,
    pub release_handled_by_rust: bool,
}

impl CallbackBridge {
    pub fn new(trait_name: impl Into<String>, bridge_class: impl Into<String>) -> Self {
        Self {
            trait_name: trait_name.into(),
            bridge_class: bridge_class.into(),
            release_handled_by_rust: true,
        }
    }
}

#[derive(Debug, Clone)]
pub struct FfiParam {
    pub name: String,
    pub param_type: FfiType,
    pub ownership: Ownership,
}

impl FfiParam {
    pub fn new(name: impl Into<String>, param_type: FfiType) -> Self {
        Self {
            name: name.into(),
            param_type,
            ownership: Ownership::Borrowed,
        }
    }

    pub fn owned(mut self) -> Self {
        self.ownership = Ownership::Owned;
        self
    }

    pub fn borrowed(mut self) -> Self {
        self.ownership = Ownership::Borrowed;
        self
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FfiOutput {
    Void,
    Status,
    Value(FfiType),
    OutParam {
        param_type: FfiType,
        ownership: Ownership,
    },
    VecPattern {
        len_fn: String,
        copy_fn: String,
        element_type: FfiType,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Ownership {
    #[default]
    Borrowed,
    Owned,
    Returned,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FunctionSemantics {
    Pure,
    Constructor { class: String },
    Destructor { class: String },
    Method { class: String },
    StaticMethod { class: String },
    CallbackBridge { trait_name: String },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FfiType {
    Void,
    Bool,
    I8,
    I16,
    I32,
    I64,
    U8,
    U16,
    U32,
    U64,
    F32,
    F64,
    String,
    Bytes,
    Pointer {
        element: Box<FfiType>,
        mutable: bool,
    },
    Handle(String),
    Record(String),
    Enum(String),
    Vec(Box<FfiType>),
    Option(Box<FfiType>),
    Callback {
        arg: Box<FfiType>,
    },
}

impl FfiType {
    pub fn is_primitive(&self) -> bool {
        matches!(
            self,
            Self::Bool
                | Self::I8
                | Self::I16
                | Self::I32
                | Self::I64
                | Self::U8
                | Self::U16
                | Self::U32
                | Self::U64
                | Self::F32
                | Self::F64
        )
    }

    pub fn is_handle(&self) -> bool {
        matches!(self, Self::Handle(_))
    }

    pub fn is_string(&self) -> bool {
        matches!(self, Self::String)
    }

    pub fn requires_cleanup(&self) -> bool {
        matches!(
            self,
            Self::String | Self::Bytes | Self::Vec(_) | Self::Pointer { .. }
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_contract_builder() {
        let mut contract = FfiContract::new("bench_boltffi", "boltffi");

        contract.add_function(
            FfiFunction::new("echo_string", "boltffi_echo_string").with_output(
                FfiOutput::OutParam {
                    param_type: FfiType::String,
                    ownership: Ownership::Returned,
                },
            ),
        );

        contract.add_class(
            FfiClass::new("DataStore")
                .with_constructor("boltffi_data_store_new")
                .with_destructor("boltffi_data_store_free"),
        );

        contract.add_callback_bridge(CallbackBridge::new(
            "AsyncDataFetcher",
            "AsyncDataFetcherBridge",
        ));

        assert!(contract.get_function("boltffi_echo_string").is_some());
        assert!(contract.get_class("DataStore").is_some());
        assert!(contract.is_callback_bridge_retain("AsyncDataFetcherBridge"));
    }

    #[test]
    fn test_ownership_semantics() {
        let func = FfiFunction::new("take_buffer", "boltffi_take_buffer")
            .with_param(FfiParam::new("data", FfiType::Bytes).owned());

        assert!(func.takes_ownership());
    }
}
