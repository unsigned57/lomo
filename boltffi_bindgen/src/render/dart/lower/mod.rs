use crate::{
    ir::{
        AbiCall, AbiContract, CallId, ConstructorDef, FfiContract, FunctionId, MethodDef, ParamDef,
    },
    render::dart::{
        DartConstructor, DartConstructorKind, DartFunction, DartFunctionParam, DartLibrary,
        DartNative, DartType, NamingConvention,
    },
};

mod callback;
mod class;
mod custom_type;
mod enumeration;
mod native_function;
mod record;

pub struct DartLowerer<'a> {
    ffi: &'a FfiContract,
    abi: &'a AbiContract,
    package_name: &'a str,
}

impl<'a> DartLowerer<'a> {
    pub fn new(ffi: &'a FfiContract, abi: &'a AbiContract, package_name: &'a str) -> Self {
        Self {
            ffi,
            abi,
            package_name,
        }
    }

    pub fn abi_call_for_function(&self, function: &FunctionId) -> &AbiCall {
        self.abi
            .calls
            .iter()
            .find(|c| match &c.id {
                CallId::Function(id) => id == function,
                _ => false,
            })
            .unwrap()
    }

    pub fn abi_call_for_call_id(&self, call_id: &CallId) -> &AbiCall {
        self.abi.calls.iter().find(|c| &c.id == call_id).unwrap()
    }

    fn lower_param(&self, param: &ParamDef) -> DartFunctionParam {
        DartFunctionParam {
            name: NamingConvention::param_name(param.name.as_str()),
            ty: DartType::from_type_expr(&param.type_expr, &self.ffi.catalog),
        }
    }

    fn lower_constructor(&self, ctor: &ConstructorDef, id: CallId) -> DartConstructor {
        let abi_call = self.abi_call_for_call_id(&id);

        let native = self.lower_one_native_function(abi_call);

        DartConstructor {
            native,
            params: ctor
                .params()
                .iter()
                .map(|param| self.lower_param(param))
                .collect(),
            kind: match ctor {
                ConstructorDef::Default { .. } => DartConstructorKind::Default,
                ConstructorDef::NamedFactory { name, .. }
                | ConstructorDef::NamedInit { name, .. } => DartConstructorKind::Named {
                    name: NamingConvention::function_name(name.as_str()),
                },
            },
            is_fallible: ctor.is_fallible(),
        }
    }

    fn lower_method(&self, meth: &MethodDef, id: CallId) -> DartFunction {
        let abi_call = self.abi_call_for_call_id(&id);

        DartFunction {
            name: NamingConvention::function_name(meth.id.as_str()),
            ffi_name: abi_call.symbol.to_string(),
            params: meth.params.iter().map(|p| self.lower_param(p)).collect(),
            ret_ty: DartType::from_return_def(&meth.returns, &self.ffi.catalog),
            receiver: meth.receiver,
        }
    }

    pub fn library(&self) -> DartLibrary {
        let custom_types = self.lower_custom_types();
        let records = self.lower_records();
        let native_functions = self.lower_native_functions();
        let enums = self.lower_enums();
        let callbacks = self.lower_callbacks();
        let classes = self.lower_classes();

        DartLibrary {
            custom_types,
            native: DartNative {
                functions: native_functions,
            },
            records,
            enums,
            callbacks,
            classes,
        }
    }
}
