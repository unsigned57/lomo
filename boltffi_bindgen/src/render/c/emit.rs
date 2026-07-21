use crate::ir::plan::AbiType;
use crate::ir::types::PrimitiveType;

pub fn primitive_c_type(p: PrimitiveType) -> String {
    match p {
        PrimitiveType::Bool => "bool".to_string(),
        PrimitiveType::I8 => "int8_t".to_string(),
        PrimitiveType::U8 => "uint8_t".to_string(),
        PrimitiveType::I16 => "int16_t".to_string(),
        PrimitiveType::U16 => "uint16_t".to_string(),
        PrimitiveType::I32 => "int32_t".to_string(),
        PrimitiveType::U32 => "uint32_t".to_string(),
        PrimitiveType::I64 => "int64_t".to_string(),
        PrimitiveType::U64 => "uint64_t".to_string(),
        PrimitiveType::F32 => "float".to_string(),
        PrimitiveType::F64 => "double".to_string(),
        PrimitiveType::ISize => "intptr_t".to_string(),
        PrimitiveType::USize => "uintptr_t".to_string(),
    }
}

pub fn class_handle_c_type(class_name: &str) -> String {
    format!("struct BoltFFI{}Handle", class_name)
}

pub fn abi_type_c(abi_type: &AbiType) -> String {
    match abi_type {
        AbiType::Void => "void".to_string(),
        AbiType::Bool => "bool".to_string(),
        AbiType::I8 => "int8_t".to_string(),
        AbiType::U8 => "uint8_t".to_string(),
        AbiType::I16 => "int16_t".to_string(),
        AbiType::U16 => "uint16_t".to_string(),
        AbiType::I32 => "int32_t".to_string(),
        AbiType::U32 => "uint32_t".to_string(),
        AbiType::I64 => "int64_t".to_string(),
        AbiType::U64 => "uint64_t".to_string(),
        AbiType::F32 => "float".to_string(),
        AbiType::F64 => "double".to_string(),
        AbiType::ISize => "intptr_t".to_string(),
        AbiType::USize => "uintptr_t".to_string(),
        AbiType::Pointer(element) => format!("{}*", primitive_c_type(*element)),
        AbiType::OwnedBuffer => "FfiBuf_u8".to_string(),
        AbiType::InlineCallbackFn {
            params,
            return_type,
        } => {
            let mut param_types = vec!["void*".to_string()];
            param_types.extend(params.iter().map(|p| match p {
                AbiType::Pointer(element) => format!("const {}*", primitive_c_type(*element)),
                other => abi_type_c(other),
            }));
            let c_return = abi_type_c(return_type);
            format!("{} (*)({})", c_return, param_types.join(", "))
        }
        AbiType::Handle(class_id) => format!("const {} *", class_handle_c_type(class_id.as_str())),
        AbiType::CallbackHandle => "BoltFFICallbackHandle".to_string(),
        AbiType::Struct(record_id) => format!("___{}", record_id.as_str()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn inline_callback_void_return_emits_void_fn_ptr() {
        let ty = AbiType::InlineCallbackFn {
            params: vec![AbiType::I32],
            return_type: Box::new(AbiType::Void),
        };
        assert_eq!(abi_type_c(&ty), "void (*)(void*, int32_t)");
    }

    #[test]
    fn inline_callback_primitive_return_emits_typed_fn_ptr() {
        let ty = AbiType::InlineCallbackFn {
            params: vec![AbiType::I32],
            return_type: Box::new(AbiType::I32),
        };
        assert_eq!(abi_type_c(&ty), "int32_t (*)(void*, int32_t)");
    }

    #[test]
    fn inline_callback_struct_return_emits_struct_fn_ptr() {
        let ty = AbiType::InlineCallbackFn {
            params: vec![AbiType::Pointer(PrimitiveType::U8), AbiType::USize],
            return_type: Box::new(AbiType::Struct("Point".into())),
        };
        assert_eq!(
            abi_type_c(&ty),
            "___Point (*)(void*, const uint8_t*, uintptr_t)"
        );
    }

    #[test]
    fn inline_callback_owned_buffer_return_emits_buffer_fn_ptr() {
        let ty = AbiType::InlineCallbackFn {
            params: vec![AbiType::Pointer(PrimitiveType::U8), AbiType::USize],
            return_type: Box::new(AbiType::OwnedBuffer),
        };
        assert_eq!(
            abi_type_c(&ty),
            "FfiBuf_u8 (*)(void*, const uint8_t*, uintptr_t)"
        );
    }

    #[test]
    fn inline_callback_no_params_with_return() {
        let ty = AbiType::InlineCallbackFn {
            params: vec![],
            return_type: Box::new(AbiType::Bool),
        };
        assert_eq!(abi_type_c(&ty), "bool (*)(void*)");
    }

    #[test]
    fn handle_emits_named_opaque_struct_pointer() {
        let ty = AbiType::Handle("Player".into());
        assert_eq!(abi_type_c(&ty), "const struct BoltFFIPlayerHandle *");
    }
}
