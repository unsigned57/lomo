use askama::Template;
use boltffi_ffi_rules::callable::ExecutionKind;
use boltffi_ffi_rules::naming;

use crate::ir::abi::{
    AbiCall, AbiCallbackInvocation, AbiCallbackMethod, AbiContract, AbiParam, AbiStream, CallId,
    CallMode, ErrorTransport, ParamRole, ReturnShape,
};
use crate::ir::contract::FfiContract;
use crate::ir::definitions::EnumRepr;
use crate::ir::plan::{AbiType, Mutability, SpanContent, Transport};
use crate::ir::types::{PrimitiveType, TypeExpr};

use super::emit;
use super::plan::{CCallbackMethod, CEnumVariant, CField};
use super::templates::{
    AsyncFunctionTemplate, CallbackVtableTemplate, ClassDestructorTemplate,
    CompositeStructTemplate, EnumTemplate, PreambleTemplate, StreamTemplate, SyncFunctionTemplate,
};

pub struct CHeaderLowerer<'a> {
    contract: &'a FfiContract,
    abi: &'a AbiContract,
    prefix: &'static str,
}

impl<'a> CHeaderLowerer<'a> {
    pub fn new(contract: &'a FfiContract, abi: &'a AbiContract) -> Self {
        Self {
            contract,
            abi,
            prefix: naming::ffi_prefix(),
        }
    }

    pub fn generate(&self) -> String {
        let has_async = self
            .abi
            .calls
            .iter()
            .any(|c| matches!(c.mode, CallMode::Async(_)));
        let has_streams = !self.abi.streams.is_empty();

        let mut out = PreambleTemplate {
            prefix: self.prefix,
            has_async,
            has_streams,
        }
        .render()
        .unwrap();

        out.push_str(&self.forward_declarations());
        out.push_str(&self.composite_struct_typedefs());
        out.push_str(&self.enum_typedefs());
        out.push_str(&self.callback_vtables());
        out.push_str(&self.function_declarations());
        out.push_str(&self.value_type_declarations());
        out.push_str(&self.class_declarations());
        out.push_str(&self.free_functions());

        out
    }

    fn forward_declarations(&self) -> String {
        let declarations: String = self
            .contract
            .catalog
            .all_classes()
            .map(|class_def| format!("{};\n", emit::class_handle_c_type(class_def.id.as_str())))
            .collect();

        if declarations.is_empty() {
            String::new()
        } else {
            format!("\n{}\n", declarations)
        }
    }

    fn composite_struct_typedefs(&self) -> String {
        self.abi
            .records
            .iter()
            .filter(|r| r.is_blittable)
            .filter_map(|abi_record| {
                let def = self.contract.catalog.resolve_record(&abi_record.id)?;
                let fields: Vec<CField> = def
                    .fields
                    .iter()
                    .filter_map(|f| match &f.type_expr {
                        TypeExpr::Primitive(p) => Some(CField {
                            name: f.name.as_str().to_string(),
                            c_type: emit::primitive_c_type(*p),
                        }),
                        _ => None,
                    })
                    .collect();

                Some(
                    CompositeStructTemplate {
                        name: abi_record.id.as_str(),
                        fields: &fields,
                    }
                    .render()
                    .unwrap(),
                )
            })
            .collect()
    }

    fn enum_typedefs(&self) -> String {
        self.abi
            .enums
            .iter()
            .filter(|e| e.is_c_style)
            .map(|e| {
                let tag_type = self
                    .contract
                    .catalog
                    .resolve_enum(&e.id)
                    .and_then(|def| match &def.repr {
                        EnumRepr::CStyle { tag_type, .. } => Some(*tag_type),
                        _ => None,
                    })
                    .unwrap_or(PrimitiveType::I32);

                let tag_c = emit::primitive_c_type(tag_type);
                let variants: Vec<CEnumVariant> = e
                    .variants
                    .iter()
                    .map(|v| CEnumVariant {
                        name: v.name.as_str(),
                        discriminant: v.discriminant,
                    })
                    .collect();

                EnumTemplate {
                    name: e.id.as_str(),
                    tag_c_type: &tag_c,
                    variants: &variants,
                }
                .render()
                .unwrap()
            })
            .collect()
    }

    fn callback_vtables(&self) -> String {
        self.abi
            .callbacks
            .iter()
            .map(|cb| self.render_callback_vtable(cb))
            .collect()
    }

    fn render_callback_vtable(&self, cb: &AbiCallbackInvocation) -> String {
        let methods: Vec<CCallbackMethod> = cb
            .methods
            .iter()
            .map(|m| CCallbackMethod {
                field_name: m.vtable_field.as_str().to_string(),
                params: self.callback_method_params(m),
            })
            .collect();

        CallbackVtableTemplate {
            vtable_type: cb.vtable_type.as_str(),
            register_fn: cb.register_fn.as_str(),
            create_fn: cb.create_fn.as_str(),
            methods: &methods,
        }
        .render()
        .unwrap()
    }

    fn is_callback_handle(param: &AbiParam) -> bool {
        matches!(
            &param.role,
            ParamRole::Input {
                transport: Transport::Callback { .. },
                ..
            }
        )
    }

    fn callback_method_params(&self, method: &AbiCallbackMethod) -> String {
        let mut parts = vec!["uint64_t handle".to_string()];

        parts.extend(
            method
                .params
                .iter()
                .filter(|p| !Self::is_callback_handle(p))
                .map(|p| self.param_c(p)),
        );

        if method.execution_kind() == ExecutionKind::Async {
            let ret_params = self.async_callback_return_params(&method.returns);
            parts.push(format!("void (*callback)(uint64_t{})", ret_params));
            parts.push("uint64_t callback_data".to_string());
        } else {
            parts.push("FfiStatus *_out_status".to_string());
        }

        parts.join(", ")
    }

    fn async_callback_return_params(&self, returns: &ReturnShape) -> String {
        let value_params = match &returns.transport {
            None => String::new(),
            Some(Transport::Scalar(origin)) => {
                format!(", {}", emit::primitive_c_type(origin.primitive()))
            }
            Some(Transport::Span(SpanContent::Scalar(origin))) => {
                format!(
                    ", const {}*, uintptr_t",
                    emit::primitive_c_type(origin.primitive())
                )
            }
            _ => ", const uint8_t*, uintptr_t".to_string(),
        };
        format!("{value_params}, FfiStatus")
    }

    fn function_declarations(&self) -> String {
        self.abi
            .calls
            .iter()
            .filter(|call| matches!(call.id, CallId::Function(_)))
            .map(|call| self.render_call(call))
            .collect()
    }

    fn value_type_declarations(&self) -> String {
        let declarations: String = self
            .abi
            .calls
            .iter()
            .filter(|call| {
                matches!(
                    call.id,
                    CallId::RecordConstructor { .. }
                        | CallId::RecordMethod { .. }
                        | CallId::EnumConstructor { .. }
                        | CallId::EnumMethod { .. }
                )
            })
            .map(|call| self.render_call(call))
            .collect();

        if declarations.is_empty() {
            declarations
        } else {
            format!("{declarations}\n")
        }
    }

    fn class_declarations(&self) -> String {
        let mut out = String::new();

        for class_def in self.contract.catalog.all_classes() {
            let class_id = &class_def.id;
            let class_prefix = format!(
                "{}_{}",
                self.prefix,
                naming::to_snake_case(class_id.as_str())
            );

            let ctors: Vec<_> = self
                .abi
                .calls
                .iter()
                .filter(|c| {
                    matches!(&c.id, CallId::Constructor { class_id: cid, .. } if cid == class_id)
                })
                .collect();

            let methods: Vec<_> = self
                .abi
                .calls
                .iter()
                .filter(
                    |c| matches!(&c.id, CallId::Method { class_id: cid, .. } if cid == class_id),
                )
                .collect();

            let streams: Vec<_> = self
                .abi
                .streams
                .iter()
                .filter(|s| s.class_id == *class_id)
                .collect();

            for call in &ctors {
                out.push_str(&self.render_call(call));
            }

            out.push_str(
                &ClassDestructorTemplate {
                    symbol: &format!("{}_free", class_prefix),
                    handle_type: &emit::class_handle_c_type(class_id.as_str()),
                }
                .render()
                .unwrap(),
            );

            for call in &methods {
                out.push_str(&self.render_call(call));
            }

            for stream in &streams {
                out.push_str(&self.render_stream(stream));
            }

            if !ctors.is_empty() || !methods.is_empty() || !streams.is_empty() {
                out.push('\n');
            }
        }

        out
    }

    fn render_call(&self, call: &AbiCall) -> String {
        let params = self.call_params_c(call);
        match &call.mode {
            CallMode::Sync => {
                let return_type = self.return_c_type(&call.returns, &call.error);
                SyncFunctionTemplate {
                    return_type: &return_type,
                    symbol: call.symbol.as_str(),
                    params: &params,
                }
                .render()
                .unwrap()
            }
            CallMode::Async(async_call) => {
                let complete_ret = self.async_complete_return_type(&async_call.result);
                AsyncFunctionTemplate {
                    symbol: call.symbol.as_str(),
                    params: &params,
                    poll: async_call.poll.as_str(),
                    complete: async_call.complete.as_str(),
                    complete_return_type: &complete_ret,
                    cancel: async_call.cancel.as_str(),
                    free: async_call.free.as_str(),
                }
                .render()
                .unwrap()
            }
        }
    }

    fn render_stream(&self, stream: &AbiStream) -> String {
        let pop_batch_decl = match &stream.item_transport {
            Transport::Scalar(origin) => format!(
                "uintptr_t {}(SubscriptionHandle sub, {}* output_ptr, uintptr_t output_capacity);",
                stream.pop_batch.as_str(),
                emit::primitive_c_type(origin.primitive())
            ),
            Transport::Composite(layout) => format!(
                "uintptr_t {}(SubscriptionHandle sub, ___{}* output_ptr, uintptr_t output_capacity);",
                stream.pop_batch.as_str(),
                layout.record_id.as_str()
            ),
            _ => format!(
                "FfiBuf_u8 {}(SubscriptionHandle sub, uintptr_t max_count);",
                stream.pop_batch.as_str()
            ),
        };
        StreamTemplate {
            subscribe: stream.subscribe.as_str(),
            pop_batch_decl: &pop_batch_decl,
            wait: stream.wait.as_str(),
            poll: stream.poll.as_str(),
            unsubscribe: stream.unsubscribe.as_str(),
            free: stream.free.as_str(),
            handle_type: &emit::class_handle_c_type(stream.class_id.as_str()),
        }
        .render()
        .unwrap()
    }

    fn return_c_type(&self, returns: &ReturnShape, error: &ErrorTransport) -> String {
        if let Some(Transport::Handle { class_id, .. }) = &returns.transport {
            return format!("{} *", emit::class_handle_c_type(class_id.as_str()));
        }

        if matches!(returns.transport, Some(Transport::Callback { .. })) {
            return "BoltFFICallbackHandle".to_string();
        }

        if matches!(error, ErrorTransport::Encoded { .. }) {
            return "FfiBuf_u8".to_string();
        }

        match &returns.transport {
            None => {
                if matches!(error, ErrorTransport::StatusCode) {
                    "FfiStatus".to_string()
                } else {
                    "void".to_string()
                }
            }
            Some(Transport::Scalar(origin)) => emit::primitive_c_type(origin.primitive()),
            Some(Transport::Composite(layout)) => format!("___{}", layout.record_id.as_str()),
            Some(Transport::Span(_)) => "FfiBuf_u8".to_string(),
            Some(Transport::Handle { .. } | Transport::Callback { .. }) => unreachable!(),
        }
    }

    fn async_complete_return_type(&self, result: &ReturnShape) -> String {
        match &result.transport {
            None => "void".to_string(),
            Some(Transport::Scalar(origin)) => emit::primitive_c_type(origin.primitive()),
            Some(Transport::Composite(layout)) => format!("___{}", layout.record_id.as_str()),
            Some(Transport::Span(_)) => "FfiBuf_u8".to_string(),
            Some(Transport::Handle { class_id, .. }) => {
                format!("{} *", emit::class_handle_c_type(class_id.as_str()))
            }
            Some(Transport::Callback { .. }) => "BoltFFICallbackHandle".to_string(),
        }
    }

    fn call_params_c(&self, call: &AbiCall) -> String {
        if call.params.is_empty() && !matches!(call.error, ErrorTransport::StatusCode) {
            return "void".to_string();
        }

        let mut parts: Vec<String> = call.params.iter().map(|p| self.param_c(p)).collect();

        if matches!(call.error, ErrorTransport::StatusCode) {
            parts.push("FfiStatus *out_status".to_string());
        }

        parts.join(", ")
    }

    fn param_c(&self, param: &AbiParam) -> String {
        let name = naming::escape_c_keyword(param.name.as_str());
        let c_type = emit::abi_type_c(&param.abi_type);

        match &param.role {
            ParamRole::OutDirect | ParamRole::OutLen { .. } => format!("{c_type} *{name}"),
            ParamRole::CallbackContext { .. } => format!("void* {name}"),
            ParamRole::Input {
                mutability: Mutability::Mutable,
                transport: Transport::Span(SpanContent::Scalar(_)),
                ..
            } if matches!(param.abi_type, AbiType::Pointer(_)) => {
                format!("{c_type} {name}")
            }
            ParamRole::Input { .. } if matches!(param.abi_type, AbiType::Pointer(_)) => {
                format!("const {c_type} {name}")
            }
            _ if c_type.contains("(*)") => c_type.replace("(*)", &format!("(*{})", name)),
            _ => format!("{c_type} {name}"),
        }
    }

    fn free_functions(&self) -> String {
        format!(
            "\nvoid {p}_free_string(FfiString s);\n\
             void {p}_free_buf(FfiBuf_u8 buf);\n\
             FfiStatus {p}_last_error_message(FfiString *out);\n\
             void {p}_clear_last_error(void);\n",
            p = self.prefix,
        )
    }
}

#[cfg(test)]
mod tests {
    use crate::ir;
    use crate::model::{
        CallbackTrait, Class, Constructor, ConstructorParam, Enumeration, Function, Method, Module,
        Parameter, Primitive, Receiver, Record, RecordField, ReturnType, TraitMethod,
        TraitMethodParam, Type, Variant,
    };

    use super::CHeaderLowerer;

    fn generate_header(module: &mut Module) -> String {
        let contract = ir::build_contract(module);
        let abi = ir::Lowerer::new(&contract).to_abi_contract();
        CHeaderLowerer::new(&contract, &abi).generate()
    }

    #[test]
    fn preamble_contains_core_typedefs() {
        let mut module = Module::new("test");
        let header = generate_header(&mut module);
        assert!(header.contains("typedef struct FfiStatus"));
        assert!(header.contains("typedef struct FfiString"));
        assert!(header.contains("typedef struct FfiBuf_u8"));
        assert!(header.contains("typedef struct BoltFFICallbackHandle"));
        assert!(header.contains("#pragma once"));
    }

    #[test]
    fn c_style_enum_produces_typedef_and_defines() {
        let mut module = Module::new("test").with_enum(
            Enumeration::new("Color")
                .with_variant(Variant::new("red").with_discriminant(0))
                .with_variant(Variant::new("green").with_discriminant(1))
                .with_variant(Variant::new("blue").with_discriminant(2)),
        );
        let header = generate_header(&mut module);
        assert!(header.contains("typedef int32_t ___Color;"));
        assert!(header.contains("#define ___Color_red 0"));
        assert!(header.contains("#define ___Color_green 1"));
        assert!(header.contains("#define ___Color_blue 2"));
    }

    #[test]
    fn blittable_record_produces_struct_typedef() {
        let mut module = Module::new("test").with_record(
            Record::new("Point")
                .with_field(RecordField::new("x", Type::Primitive(Primitive::F64)))
                .with_field(RecordField::new("y", Type::Primitive(Primitive::F64))),
        );
        let header = generate_header(&mut module);
        assert!(header.contains("typedef struct {"));
        assert!(header.contains("double x;"));
        assert!(header.contains("double y;"));
        assert!(header.contains("} ___Point;"));
    }

    #[test]
    fn sync_function_with_primitive_param() {
        let mut module = Module::new("test").with_function(
            Function::new("add")
                .with_param(Parameter::new("a", Type::Primitive(Primitive::I32)))
                .with_param(Parameter::new("b", Type::Primitive(Primitive::I32)))
                .with_output(Type::Primitive(Primitive::I32)),
        );
        let header = generate_header(&mut module);
        assert!(header.contains("int32_t boltffi_add(int32_t a, int32_t b);"));
    }

    #[test]
    fn pointer_sized_vec_param_uses_native_integer_pointer_signature() {
        let mut module = Module::new("test").with_function(
            Function::new("echo_vec_isize")
                .with_param(Parameter::new(
                    "v",
                    Type::Vec(Box::new(Type::Primitive(Primitive::ISize))),
                ))
                .with_output(Type::Vec(Box::new(Type::Primitive(Primitive::ISize)))),
        );
        let header = generate_header(&mut module);
        assert!(header.contains("boltffi_echo_vec_isize(const intptr_t* v, uintptr_t v_len);"));
    }

    #[test]
    fn class_produces_constructor_destructor_method() {
        let mut module = Module::new("test").with_class(
            Class::new("Player")
                .with_constructor(Constructor::new())
                .with_method(
                    Method::new("get_score", Receiver::Ref)
                        .with_output(Type::Primitive(Primitive::I32)),
                ),
        );
        let header = generate_header(&mut module);
        assert!(header.contains("struct BoltFFIPlayerHandle;"));
        assert!(header.contains("struct BoltFFIPlayerHandle * boltffi_player_new("));
        assert!(header.contains("void boltffi_player_free(struct BoltFFIPlayerHandle *handle);"));
        assert!(header.contains(
            "int32_t boltffi_player_get_score(const struct BoltFFIPlayerHandle * self);"
        ));
    }

    #[test]
    fn value_type_methods_and_constructors_are_declared() {
        let mut module = Module::new("test")
            .with_record(
                Record::new("Point")
                    .with_field(RecordField::new("x", Type::Primitive(Primitive::F64)))
                    .with_field(RecordField::new("y", Type::Primitive(Primitive::F64)))
                    .with_constructor(
                        Constructor::new()
                            .with_param(ConstructorParam::new("x", Type::Primitive(Primitive::F64)))
                            .with_param(ConstructorParam::new(
                                "y",
                                Type::Primitive(Primitive::F64),
                            )),
                    )
                    .with_method(
                        Method::new("origin", Receiver::None)
                            .with_output(Type::Record("Point".into())),
                    )
                    .with_method(
                        Method::new("distance", Receiver::Ref)
                            .with_output(Type::Primitive(Primitive::F64)),
                    ),
            )
            .with_enum(
                Enumeration::new("Direction")
                    .with_variant(Variant::new("north").with_discriminant(0))
                    .with_variant(Variant::new("south").with_discriminant(1))
                    .with_method(
                        Method::new("cardinal", Receiver::None)
                            .with_output(Type::Enum("Direction".into())),
                    )
                    .with_method(
                        Method::new("opposite", Receiver::Ref)
                            .with_output(Type::Enum("Direction".into())),
                    ),
            );
        let header = generate_header(&mut module);
        assert!(header.contains("___Point boltffi_point_new(double x, double y);"));
        assert!(header.contains("___Point boltffi_point_origin(void);"));
        assert!(header.contains("double boltffi_point_distance("));
        assert!(header.contains("boltffi_direction_cardinal(void);"));
        assert!(header.contains("boltffi_direction_opposite("));
    }

    #[test]
    fn async_function_produces_five_declarations() {
        let mut module = Module::new("test").with_function(
            Function::new("fetch_data")
                .with_output(Type::Primitive(Primitive::I32))
                .make_async(),
        );
        let header = generate_header(&mut module);
        assert!(header.contains("RustFutureHandle boltffi_fetch_data("));
        assert!(header.contains("boltffi_fetch_data_poll("));
        assert!(header.contains("boltffi_fetch_data_complete("));
        assert!(header.contains("boltffi_fetch_data_cancel("));
        assert!(header.contains("boltffi_fetch_data_free("));
        assert!(header.contains("typedef const void* RustFutureHandle;"));
    }

    #[test]
    fn async_blittable_record_complete_uses_direct_record_type() {
        let mut module = Module::new("test")
            .with_record(
                Record::new("Point")
                    .with_field(RecordField::new("x", Type::Primitive(Primitive::F64)))
                    .with_field(RecordField::new("y", Type::Primitive(Primitive::F64))),
            )
            .with_function(
                Function::new("fetch_point")
                    .with_output(Type::Record("Point".into()))
                    .make_async(),
            );
        let header = generate_header(&mut module);
        assert!(header.contains("___Point boltffi_fetch_point_complete("));
    }

    #[test]
    fn free_functions_present() {
        let mut module = Module::new("test");
        let header = generate_header(&mut module);
        assert!(header.contains("boltffi_free_string(FfiString s);"));
        assert!(header.contains("boltffi_free_buf(FfiBuf_u8 buf);"));
        assert!(header.contains("boltffi_last_error_message(FfiString *out);"));
        assert!(header.contains("boltffi_clear_last_error(void);"));
    }

    #[test]
    fn callback_method_status_param_does_not_collide_with_out_status() {
        let mut module = Module::new("test").with_callback_trait(
            CallbackTrait::new("ValueListener").with_method(
                TraitMethod::new("on_value")
                    .with_param(TraitMethodParam::new(
                        "status",
                        Type::Primitive(Primitive::I32),
                    ))
                    .with_return(ReturnType::Void),
            ),
        );
        let header = generate_header(&mut module);
        assert!(
            header.contains("_out_status"),
            "sync callback method should use _out_status to avoid collision with user param 'status'"
        );
        let status_count = header.matches("status").count();
        assert!(
            status_count >= 2,
            "header should contain both user param 'status' and '_out_status'"
        );
    }
}
