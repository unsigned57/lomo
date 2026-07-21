use boltffi_ffi_rules::naming;

use crate::{
    ir::{
        AbiStream, CallId, ClassDef, ClassId, PrimitiveType, StreamDef, StreamId,
        StreamItemTransport,
    },
    render::dart::{
        DartClass, DartNativeFunction, DartNativeFunctionCallMode, DartNativeFunctionKind,
        DartNativeFunctionParam, DartNativeType, DartStream, DartType, NamingConvention,
    },
};

impl<'a> super::DartLowerer<'a> {
    fn abi_stream_for(&self, id: &StreamId, class_id: &ClassId) -> Option<&AbiStream> {
        self.abi
            .streams
            .iter()
            .find(|s| &s.stream_id == id && &s.class_id == class_id)
    }

    fn lower_one_stream(&self, stream: &StreamDef, class_id: &ClassId) -> DartStream {
        let abi_stream = self.abi_stream_for(&stream.id, class_id).unwrap();

        let StreamItemTransport::WireEncoded { decode_ops } = &abi_stream.item;

        let mut pop_batch_params = vec![DartNativeFunctionParam {
            name: "handle".to_string(),
            native_type: DartNativeType::Pointer(Box::new(DartNativeType::Void)),
        }];
        match abi_stream.item_size {
            Some(_) => {
                pop_batch_params.extend([
                    DartNativeFunctionParam {
                        name: "output_ptr".to_string(),
                        native_type: DartNativeType::Pointer(Box::new(
                            DartNativeType::from_transport(&abi_stream.item_transport),
                        )),
                    },
                    DartNativeFunctionParam {
                        name: "output_capacity".to_string(),
                        native_type: DartNativeType::Primitive(PrimitiveType::USize),
                    },
                ]);
            }
            None => pop_batch_params.extend([DartNativeFunctionParam {
                name: "max_count".to_string(),
                native_type: DartNativeType::Primitive(PrimitiveType::USize),
            }]),
        }
        let pop_batch_return_type = match abi_stream.item_size {
            Some(_) => DartNativeType::Primitive(PrimitiveType::USize),
            None => DartNativeType::OwnedBuffer,
        };
        let pop_batch_fn = DartNativeFunction {
            symbol: abi_stream.pop_batch.to_string(),
            params: pop_batch_params,
            return_type: pop_batch_return_type,
            is_leaf: true,
            call_mode: DartNativeFunctionCallMode::Sync,
        };

        DartStream {
            name: NamingConvention::function_name(stream.id.as_str()),
            item_ty: DartType::from_type_expr(&stream.item_type, &self.ffi.catalog),
            item_read_seq: decode_ops.clone(),
            ffi_item_ty: DartNativeType::from_transport(&abi_stream.item_transport),
            ffi_item_size: abi_stream.item_size,
            subscribe_fn: DartNativeFunction {
                symbol: abi_stream.subscribe.to_string(),
                params: vec![DartNativeFunctionParam {
                    name: "handle".to_string(),
                    native_type: DartNativeType::Pointer(Box::new(DartNativeType::Void)),
                }],
                return_type: DartNativeType::Pointer(Box::new(DartNativeType::Void)),
                is_leaf: true,
                call_mode: DartNativeFunctionCallMode::Sync,
            },
            poll_fn: DartNativeFunction {
                symbol: abi_stream.poll.to_string(),
                params: vec![
                    DartNativeFunctionParam {
                        name: "handle".to_string(),
                        native_type: DartNativeType::Pointer(Box::new(DartNativeType::Void)),
                    },
                    DartNativeFunctionParam {
                        name: "callback_data".to_string(),
                        native_type: DartNativeType::Primitive(PrimitiveType::U64),
                    },
                    DartNativeFunctionParam {
                        name: "callback".to_string(),
                        native_type: DartNativeType::Function {
                            kind: DartNativeFunctionKind::Callback,
                            params: vec![DartNativeType::Primitive(PrimitiveType::I8)],
                            return_ty: Box::new(DartNativeType::Void),
                        },
                    },
                ],
                return_type: DartNativeType::Void,
                is_leaf: false,
                call_mode: DartNativeFunctionCallMode::Sync,
            },
            pop_batch_fn,
            wait_fn: DartNativeFunction {
                symbol: abi_stream.wait.to_string(),
                params: vec![
                    DartNativeFunctionParam {
                        name: "handle".to_string(),
                        native_type: DartNativeType::Pointer(Box::new(DartNativeType::Void)),
                    },
                    DartNativeFunctionParam {
                        name: "timeout_milliseconds".to_string(),
                        native_type: DartNativeType::Primitive(PrimitiveType::U32),
                    },
                ],
                return_type: DartNativeType::Primitive(PrimitiveType::I32),
                is_leaf: true,
                call_mode: DartNativeFunctionCallMode::Sync,
            },
            unsubscribe_fn: DartNativeFunction {
                symbol: abi_stream.unsubscribe.to_string(),
                params: vec![DartNativeFunctionParam {
                    name: "handle".to_string(),
                    native_type: DartNativeType::Pointer(Box::new(DartNativeType::Void)),
                }],
                return_type: DartNativeType::Void,
                is_leaf: false,
                call_mode: DartNativeFunctionCallMode::Sync,
            },
            free_fn: DartNativeFunction {
                symbol: abi_stream.free.to_string(),
                params: vec![DartNativeFunctionParam {
                    name: "handle".to_string(),
                    native_type: DartNativeType::Pointer(Box::new(DartNativeType::Void)),
                }],
                return_type: DartNativeType::Void,
                is_leaf: false,
                call_mode: DartNativeFunctionCallMode::Sync,
            },
            mode: stream.mode,
        }
    }

    fn lower_one_class(&self, class: &ClassDef) -> DartClass {
        let constructors = class
            .constructors
            .iter()
            .enumerate()
            .map(|(i, ctor)| {
                self.lower_constructor(
                    ctor,
                    CallId::Constructor {
                        class_id: class.id.clone(),
                        index: i,
                    },
                )
            })
            .collect();

        let methods = class
            .methods
            .iter()
            .map(|meth| {
                self.lower_method(
                    meth,
                    CallId::Method {
                        class_id: class.id.clone(),
                        method_id: meth.id.clone(),
                    },
                )
            })
            .collect();

        let streams = class
            .streams
            .iter()
            .map(|s| self.lower_one_stream(s, &class.id))
            .collect();

        DartClass {
            name: NamingConvention::class_name(class.id.as_str()),
            create_symbol: naming::class_ffi_new(class.id.as_str()).to_string(),
            free_symbol: naming::class_ffi_free(class.id.as_str()).to_string(),
            constructors,
            methods,
            streams,
        }
    }

    pub(super) fn lower_classes(&self) -> Vec<DartClass> {
        self.ffi
            .catalog
            .all_classes()
            .map(|c| self.lower_one_class(c))
            .collect()
    }
}
