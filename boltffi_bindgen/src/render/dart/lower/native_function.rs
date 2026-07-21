use boltffi_ffi_rules::transport::ParamValueStrategy;

use crate::{
    ir::{AbiCall, AbiParam, AbiType, CallMode, ParamRole},
    render::dart::{
        DartNativeFunction, DartNativeFunctionCallMode, DartNativeFunctionParam, DartNativeType,
        NamingConvention,
    },
};

impl<'a> super::DartLowerer<'a> {
    pub(super) fn lower_native_function_param(
        &self,
        abi_param: &AbiParam,
    ) -> DartNativeFunctionParam {
        let name = match &abi_param.role {
            ParamRole::Input { contract, .. } => match contract.value_strategy() {
                ParamValueStrategy::DirectBuffer(..)
                | ParamValueStrategy::WireEncoded(..)
                | ParamValueStrategy::Utf8String
                | ParamValueStrategy::CompositeValue => {
                    format!(
                        "{}Ptr",
                        NamingConvention::param_name(abi_param.name.as_str())
                    )
                }
                _ => NamingConvention::param_name(abi_param.name.as_str()),
            },
            ParamRole::OutDirect => String::from("_p$outPtr"),
            ParamRole::OutLen { .. } => String::from("_p$outLen"),
            _ => NamingConvention::param_name(abi_param.name.as_str()),
        };

        DartNativeFunctionParam {
            name,
            native_type: DartNativeType::from_abi_param(abi_param),
        }
    }

    pub(super) fn lower_one_native_function(&self, abi_call: &AbiCall) -> DartNativeFunction {
        let symbol = abi_call.symbol.to_string();

        let params = abi_call
            .params
            .iter()
            .map(|p| self.lower_native_function_param(p))
            .collect();

        let is_not_leaf = abi_call.params.iter().any(|p| {
            matches!(
                p.abi_type,
                AbiType::InlineCallbackFn { .. } | AbiType::CallbackHandle
            )
        });

        let call_mode = match &abi_call.mode {
            CallMode::Sync => DartNativeFunctionCallMode::Sync,
            CallMode::Async(call) => DartNativeFunctionCallMode::Async {
                poll_symbol: call.poll.to_string(),
                complete_symbol: call.complete.to_string(),
                complete_ty: DartNativeType::from_return_shape_and_error_transport(
                    &call.result,
                    &call.error,
                ),
                cancel_symbol: call.cancel.to_string(),
                free_symbol: call.free.to_string(),
            },
        };

        DartNativeFunction {
            symbol,
            params,
            return_type: match &call_mode {
                DartNativeFunctionCallMode::Sync => {
                    DartNativeType::from_return_shape_and_error_transport(
                        &abi_call.returns,
                        &abi_call.error,
                    )
                }
                DartNativeFunctionCallMode::Async { .. } => {
                    DartNativeType::Pointer(Box::new(DartNativeType::Void))
                }
            },
            is_leaf: !is_not_leaf,
            call_mode,
        }
    }

    pub(super) fn lower_native_functions(&self) -> Vec<DartNativeFunction> {
        self.ffi
            .functions
            .iter()
            .map(|f| {
                let abi_call = self.abi_call_for_function(&f.id);
                self.lower_one_native_function(abi_call)
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use boltffi_ffi_rules::callable::ExecutionKind;

    use crate::{
        ir::{
            CallbackId, CallbackKind, CallbackTraitDef, FunctionDef, FunctionId, ParamDef,
            ParamName, ParamPassing, PrimitiveType, ReturnDef, TypeExpr,
        },
        render::dart::test,
    };

    use super::*;

    #[test]
    pub fn native_function_primitive_in() {
        let mut ffi = test::empty_contract();
        ffi.functions.insert(
            0,
            FunctionDef {
                id: FunctionId::new("echo_u64"),
                params: vec![ParamDef {
                    name: ParamName::new("v"),
                    type_expr: TypeExpr::Primitive(PrimitiveType::U64),
                    passing: ParamPassing::Value,
                    doc: None,
                }],
                returns: ReturnDef::Void,
                execution_kind: ExecutionKind::Sync,
                doc: None,
                deprecated: None,
            },
        );

        let library = test::lower(&ffi);

        assert!(matches!(
            library.native.functions[0].params[0].native_type,
            DartNativeType::Primitive(PrimitiveType::U64)
        ));

        assert_eq!(
            library.native.functions[0].params[0]
                .native_type
                .dart_sub_type(),
            "int".to_string()
        );
    }

    #[test]
    pub fn native_function_primitive_out() {
        let mut ffi = test::empty_contract();
        ffi.functions.insert(
            0,
            FunctionDef {
                id: FunctionId::new("echo_f32"),
                params: vec![],
                returns: ReturnDef::Value(TypeExpr::Primitive(PrimitiveType::F32)),
                execution_kind: ExecutionKind::Sync,
                doc: None,
                deprecated: None,
            },
        );
        let library = test::lower(&ffi);

        assert!(matches!(
            library.native.functions[0].return_type,
            DartNativeType::Primitive(PrimitiveType::F32)
        ));
        assert_eq!(
            library.native.functions[0].return_type.dart_sub_type(),
            "double".to_string()
        );
    }

    #[test]
    pub fn native_function_void_out() {
        let mut ffi = test::empty_contract();
        ffi.functions.insert(
            0,
            FunctionDef {
                id: FunctionId::new("noop"),
                params: vec![],
                returns: ReturnDef::Void,
                execution_kind: ExecutionKind::Sync,
                doc: None,
                deprecated: None,
            },
        );
        let library = test::lower(&ffi);

        assert!(matches!(
            library.native.functions[0].return_type,
            DartNativeType::Void,
        ));
        assert_eq!(
            library.native.functions[0].return_type.dart_sub_type(),
            "void".to_string()
        );
    }

    #[test]
    pub fn native_function_closure_in() {
        let mut ffi = test::empty_contract();
        ffi.catalog.insert_callback(CallbackTraitDef {
            id: CallbackId::new("ClosureCb"),
            methods: vec![],
            kind: CallbackKind::Closure,
            doc: None,
        });
        ffi.functions.insert(
            0,
            FunctionDef {
                id: FunctionId::new("function_with_callback"),
                params: vec![ParamDef {
                    name: ParamName::new("cb"),
                    type_expr: TypeExpr::Callback(CallbackId::new("ClosureCb")),
                    passing: ParamPassing::ImplTrait,
                    doc: None,
                }],
                returns: ReturnDef::Void,
                execution_kind: ExecutionKind::Sync,
                doc: None,
                deprecated: None,
            },
        );
        let library = test::lower(&ffi);

        assert!(
            library.native.functions[0].params[0]
                .native_type
                .native_type()
                .contains("$$ffi.Pointer<$$ffi.NativeFunction<")
        );
        assert!(!library.native.functions[0].is_leaf);
    }

    #[test]
    pub fn native_function_async() {
        let mut ffi = test::empty_contract();
        ffi.functions.push(FunctionDef {
            id: FunctionId::new("async_add"),
            params: vec![
                ParamDef {
                    name: ParamName::new("a"),
                    type_expr: TypeExpr::Primitive(PrimitiveType::I32),
                    passing: ParamPassing::Value,
                    doc: None,
                },
                ParamDef {
                    name: ParamName::new("b"),
                    type_expr: TypeExpr::Primitive(PrimitiveType::I32),
                    passing: ParamPassing::Value,
                    doc: None,
                },
            ],
            returns: ReturnDef::Value(TypeExpr::Primitive(PrimitiveType::I32)),
            execution_kind: ExecutionKind::Async,
            deprecated: None,
            doc: None,
        });

        let library = test::lower(&ffi);

        let func = &library.native.functions[0];
        match &func.call_mode {
            DartNativeFunctionCallMode::Sync => panic!("CallMode should be async"),
            DartNativeFunctionCallMode::Async {
                poll_symbol,
                complete_symbol,
                complete_ty,
                cancel_symbol,
                free_symbol,
            } => {
                assert_eq!(poll_symbol, "boltffi_async_add_poll");
                assert_eq!(complete_symbol, "boltffi_async_add_complete");
                assert!(matches!(
                    complete_ty,
                    DartNativeType::Primitive(PrimitiveType::I32)
                ));
                assert_eq!(complete_symbol, "boltffi_async_add_complete");
                assert_eq!(cancel_symbol, "boltffi_async_add_cancel");
                assert_eq!(free_symbol, "boltffi_async_add_free");
            }
        };
    }
}
