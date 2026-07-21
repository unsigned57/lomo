use crate::{
    ir::{AbiEnumField, AbiEnumPayload, AbiEnumVariant, EnumDef, EnumRepr},
    render::dart::{
        DartEnum, DartEnumField, DartEnumKind, DartEnumVariant, DartType, NamingConvention,
    },
};

impl<'a> super::DartLowerer<'a> {
    fn lower_enum_field(&self, field: &AbiEnumField) -> DartEnumField {
        let field_name = super::NamingConvention::property_name(field.name.as_str());

        DartEnumField {
            name: field_name,
            dart_type: DartType::from_type_expr(&field.type_expr, &self.ffi.catalog),
            read_seq: field.decode.clone(),
            write_seq: field.encode.clone(),
        }
    }

    fn lower_enum_variant(&self, variant: &AbiEnumVariant, enum_name: &str) -> DartEnumVariant {
        let variant_name = NamingConvention::property_name(variant.name.as_str());
        let variant_class_name = format!(
            "{}${}",
            enum_name,
            NamingConvention::class_name(variant.name.as_str())
        );

        let fields = match &variant.payload {
            AbiEnumPayload::Unit => Vec::new(),
            AbiEnumPayload::Tuple(abi_enum_fields) | AbiEnumPayload::Struct(abi_enum_fields) => {
                abi_enum_fields
                    .iter()
                    .map(|f| self.lower_enum_field(f))
                    .collect()
            }
        };

        DartEnumVariant {
            name: variant_name,
            class_name: variant_class_name,
            tag: variant.discriminant,
            fields,
        }
    }

    fn lower_one_enum(&self, enum_def: &EnumDef) -> DartEnum {
        let enum_name = NamingConvention::class_name(enum_def.id.as_str());

        let abi_enum = self
            .abi
            .enums
            .iter()
            .find(|en| en.id == enum_def.id)
            .unwrap();

        let enum_kind = if abi_enum.is_c_style {
            DartEnumKind::Enhanced
        } else {
            DartEnumKind::SealedClass
        };

        let tag_type = match &enum_def.repr {
            EnumRepr::CStyle { tag_type, .. } | EnumRepr::Data { tag_type, .. } => *tag_type,
        };

        let enum_variants = abi_enum
            .variants
            .iter()
            .map(|v| self.lower_enum_variant(v, &enum_name))
            .collect();

        let constructors = enum_def
            .constructor_calls()
            .map(|(id, ctor_def)| self.lower_constructor(ctor_def, id))
            .collect();

        let methods = enum_def
            .method_calls()
            .map(|(id, meth_def)| self.lower_method(meth_def, id))
            .collect();

        DartEnum {
            name: enum_name,
            kind: enum_kind,
            tag_type,
            variants: enum_variants,
            size_expr: abi_enum.encode_ops.size.clone(),
            is_error: enum_def.is_error,
            constructors,
            methods,
        }
    }

    pub(super) fn lower_enums(&self) -> Vec<DartEnum> {
        self.ffi
            .catalog
            .all_enums()
            .map(|e| self.lower_one_enum(e))
            .collect()
    }
}
