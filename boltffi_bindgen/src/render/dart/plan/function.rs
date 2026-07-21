use crate::ir::Receiver;

#[derive(Debug, Clone)]
pub struct DartNativeFunctionParam {
    pub name: String,
    pub native_type: super::DartNativeType,
}

#[derive(Debug, Clone)]
pub enum DartNativeFunctionCallMode {
    Sync,
    Async {
        poll_symbol: String,
        complete_symbol: String,
        complete_ty: super::DartNativeType,
        cancel_symbol: String,
        free_symbol: String,
    },
}

#[derive(Debug, Clone)]
pub struct DartNativeFunction {
    pub symbol: String,
    pub params: Vec<DartNativeFunctionParam>,
    pub return_type: super::DartNativeType,
    pub is_leaf: bool,
    pub call_mode: DartNativeFunctionCallMode,
}

#[derive(Debug, Clone)]
pub struct DartNative {
    pub functions: Vec<DartNativeFunction>,
}

#[derive(Debug, Clone)]
pub struct DartFunctionParam {
    pub name: String,
    pub ty: super::DartType,
}

#[derive(Debug, Clone)]
pub struct DartFunction {
    pub name: String,
    pub ffi_name: String,
    pub params: Vec<DartFunctionParam>,
    pub ret_ty: super::DartType,
    pub receiver: Receiver,
}

impl DartFunction {
    pub fn is_static(&self) -> bool {
        matches!(self.receiver, Receiver::Static)
    }
}
