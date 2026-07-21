use boltffi_ffi_rules::callable::ExecutionKind;

#[derive(Debug, Clone)]
pub struct DartNativeCallbackMethod {
    pub vtable_field_name: String,
    pub params: Vec<super::DartNativeFunctionParam>,
    pub return_type: super::DartNativeType,
    pub kind: ExecutionKind,
}

impl DartNativeCallbackMethod {
    pub fn is_async(&self) -> bool {
        matches!(self.kind, ExecutionKind::Async)
    }
}

#[derive(Debug, Clone)]
pub struct DartNativeCallback {
    pub vtable_struct_name: String,
    pub methods: Vec<DartNativeCallbackMethod>,
}

#[derive(Debug, Clone)]
pub struct DartCallbackMethod {
    pub name: String,
    pub params: Vec<super::DartFunctionParam>,
    pub ret_ty: super::DartType,
    pub kind: ExecutionKind,
}

impl DartCallbackMethod {
    pub fn is_async(&self) -> bool {
        matches!(self.kind, ExecutionKind::Async)
    }
}

#[derive(Debug, Clone)]
pub struct DartCallback {
    pub class_name: String,
    pub impl_class_name: String,
    pub handle_map_class_name: String,
    pub handle_map_instance_name: String,
    pub methods: Vec<DartCallbackMethod>,
    pub native: DartNativeCallback,
}
