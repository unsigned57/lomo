use boltffi_ffi_rules::{callable::ExecutionKind, transport::ValueReturnStrategy};

use crate::{
    ir::{
        AbiCallbackInvocation, AbiCallbackMethod, CallbackId, CallbackKind, CallbackMethodDef,
        CallbackTraitDef, ParamRole, PrimitiveType, Transport,
    },
    render::dart::{
        DartCallback, DartCallbackMethod, DartNativeCallback, DartNativeCallbackMethod,
        DartNativeFunctionKind, DartNativeFunctionParam, DartNativeType, DartType,
        NamingConvention,
    },
};

impl<'a> super::DartLowerer<'a> {
    fn abi_callback_for(&self, id: &CallbackId) -> Option<&AbiCallbackInvocation> {
        self.abi.callbacks.iter().find(|cb| cb.callback_id == *id)
    }

    fn lower_native_callback_method(&self, m: &AbiCallbackMethod) -> DartNativeCallbackMethod {
        assert!(matches!(
            m.params[0].role,
            ParamRole::Input {
                transport: Transport::Callback { .. },
                ..
            }
        ));

        let mut params = vec![DartNativeFunctionParam {
            name: "_p$handle".to_string(),
            native_type: DartNativeType::Primitive(PrimitiveType::U64),
        }];

        params.extend(
            m.params[1..]
                .iter()
                .map(|p| self.lower_native_function_param(p)),
        );

        match m.execution_kind {
            ExecutionKind::Sync => {
                params.push(DartNativeFunctionParam {
                    name: "_p$outStatus".to_string(),
                    native_type: DartNativeType::Pointer(Box::new(DartNativeType::Status)),
                });
            }
            ExecutionKind::Async => {
                let mut callback_params = vec![];

                if !matches!(
                    m.returns.return_contract().value_strategy(),
                    ValueReturnStrategy::Void
                ) {
                    callback_params.extend([
                        // result bytes ptr
                        DartNativeType::Pointer(Box::new(DartNativeType::Primitive(
                            PrimitiveType::U8,
                        ))),
                        // result bytes len
                        DartNativeType::Primitive(PrimitiveType::USize),
                    ])
                }
                callback_params.push(
                    // This should be FFIStatus but we choose i32 as it's a valid repr
                    DartNativeType::Primitive(PrimitiveType::I32),
                );

                params.extend([
                    DartNativeFunctionParam {
                        name: "_p$callback".to_string(),
                        native_type: DartNativeType::Function {
                            kind: DartNativeFunctionKind::Callback,
                            params: callback_params,
                            return_ty: Box::new(DartNativeType::Void),
                        },
                    },
                    DartNativeFunctionParam {
                        name: "_p$callbackData".to_string(),
                        native_type: DartNativeType::Primitive(PrimitiveType::U64),
                    },
                ]);
            }
        };

        let return_type =
            DartNativeType::from_return_shape_and_error_transport(&m.returns, &m.error);

        DartNativeCallbackMethod {
            vtable_field_name: NamingConvention::property_name(m.vtable_field.as_str()),
            params,
            return_type,
            kind: m.execution_kind,
        }
    }

    fn lower_callback_method(&self, cb: &CallbackMethodDef) -> DartCallbackMethod {
        let params = cb.params.iter().map(|p| self.lower_param(p)).collect();

        DartCallbackMethod {
            name: NamingConvention::function_name(cb.id.as_str()),
            params,
            ret_ty: DartType::from_return_def(&cb.returns, &self.ffi.catalog),
            kind: cb.execution_kind,
        }
    }

    fn lower_one_callback(&self, cb_def: &CallbackTraitDef) -> DartCallback {
        let abi_cb = self.abi_callback_for(&cb_def.id).unwrap();

        let class_name = NamingConvention::class_name(cb_def.id.as_str());
        let impl_class_name = format!("_I${}", class_name);
        let vtable_struct_name = format!(
            "_I${}",
            NamingConvention::class_name(abi_cb.vtable_type.as_str())
        );
        let handle_map_class_name = format!("{}HandleMap", impl_class_name);
        let handle_map_instance_name = format!("_k${}HandleMap", class_name);

        let methods = cb_def
            .methods
            .iter()
            .map(|m| self.lower_callback_method(m))
            .collect();

        let native_methods = abi_cb
            .methods
            .iter()
            .map(|m| self.lower_native_callback_method(m))
            .collect();

        DartCallback {
            class_name,
            impl_class_name,
            handle_map_class_name,
            handle_map_instance_name,
            methods,
            native: DartNativeCallback {
                vtable_struct_name,
                methods: native_methods,
            },
        }
    }

    pub(super) fn lower_callbacks(&self) -> Vec<DartCallback> {
        self.ffi
            .catalog
            .all_callbacks()
            .filter(|cb| matches!(cb.kind, CallbackKind::Trait))
            .map(|cb| self.lower_one_callback(cb))
            .collect()
    }
}
