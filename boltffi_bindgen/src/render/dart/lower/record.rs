use crate::{
    ir::{
        AbiRecord, FieldDef, FieldName, FieldReadOp, OffsetExpr, ReadOp, ReadSeq, RecordDef,
        RecordId, WriteOp, WriteSeq,
    },
    render::dart::{
        DartBlittableField, DartBlittableLayout, DartNativeType, DartRecord, DartRecordField,
        NamingConvention, emit,
    },
};

impl<'a> super::DartLowerer<'a> {
    fn record_field_read_seq(
        &self,
        abi_record: &AbiRecord,
        field_name: &FieldName,
    ) -> Option<ReadSeq> {
        match abi_record.decode_ops.ops.first() {
            Some(ReadOp::Record { fields, .. }) => fields
                .iter()
                .find(|field| field.name == *field_name)
                .map(|field| field.seq.clone()),
            _ => None,
        }
    }

    fn record_field_write_seq(
        &self,
        abi_record: &AbiRecord,
        field_name: &FieldName,
    ) -> Option<WriteSeq> {
        match abi_record.encode_ops.ops.first() {
            Some(WriteOp::Record { fields, .. }) => fields
                .iter()
                .find(|field| field.name == *field_name)
                .map(|field| field.seq.clone()),
            _ => None,
        }
    }

    fn abi_record_for(&self, record_id: &RecordId) -> Option<&AbiRecord> {
        self.abi
            .records
            .iter()
            .find(|record| record.id == *record_id)
    }

    fn lower_record_field(&self, field: &FieldDef, abi_record: &AbiRecord) -> DartRecordField {
        let record_field_write_seq = self
            .record_field_write_seq(abi_record, &field.name)
            .unwrap();
        let record_field_read_seq = self.record_field_read_seq(abi_record, &field.name).unwrap();

        DartRecordField {
            name: NamingConvention::property_name(field.name.as_str()),
            offset: 0,
            dart_type: emit::type_expr_dart_type(&field.type_expr),
            read_seq: record_field_read_seq,
            write_seq: record_field_write_seq,
        }
    }

    fn lower_record_blittable_field(&self, field: &FieldReadOp) -> DartBlittableField {
        let (primitive, offset) = match field.seq.ops.first() {
            Some(ReadOp::Primitive { primitive, offset }) => (*primitive, offset),
            _ => unreachable!(),
        };
        let offset = match offset {
            OffsetExpr::Base => 0,
            OffsetExpr::BasePlus(offset) => *offset,
            _ => unreachable!(),
        };
        let name = NamingConvention::property_name(field.name.as_str());
        let offset_const_name =
            NamingConvention::priv_const_name(format!("offset_{}", field.name.as_str()).as_str());

        DartBlittableField {
            name,
            offset,
            native_type: DartNativeType::Primitive(primitive),
            primitive,
            offset_const_name,
        }
    }

    fn lower_record_blittable_layout(&self, abi_record: &AbiRecord) -> DartBlittableLayout {
        let fields = match abi_record.decode_ops.ops.first() {
            Some(ReadOp::Record { fields, .. }) => fields
                .iter()
                .map(|f| self.lower_record_blittable_field(f))
                .collect(),
            _ => unreachable!(),
        };

        DartBlittableLayout {
            fields,
            struct_name: NamingConvention::record_struct_name(abi_record.id.as_str()),
            struct_size: abi_record
                .size
                .expect("record.is_blittable <=> size != None"),
        }
    }

    pub(super) fn lower_one_record(&self, record: &RecordDef) -> DartRecord {
        let name = NamingConvention::class_name(record.id.as_str());

        let abi_record = self.abi_record_for(&record.id).unwrap();

        let fields = record
            .fields
            .iter()
            .map(|f| self.lower_record_field(f, abi_record))
            .collect();

        let blittable_layout = abi_record
            .is_blittable
            .then(|| self.lower_record_blittable_layout(abi_record));

        let constructors = record
            .constructor_calls()
            .map(|(id, ctor_def)| self.lower_constructor(ctor_def, id))
            .collect();

        let methods = record
            .method_calls()
            .map(|(id, meth_def)| self.lower_method(meth_def, id))
            .collect();

        DartRecord {
            name,
            is_error: record.is_error,
            fields,
            blittable_layout,
            constructors,
            methods,
        }
    }

    pub(super) fn lower_records(&self) -> Vec<DartRecord> {
        self.ffi
            .catalog
            .all_records()
            .map(|r| self.lower_one_record(r))
            .collect()
    }
}

#[cfg(test)]
mod test {
    use crate::{
        ir::{PrimitiveType, RecordId, TypeExpr},
        render::dart::{DartEmitter, test},
    };

    use super::*;

    #[test]
    pub fn blittable_record_produces_dart_ffi_struct() {
        let mut ffi = test::empty_contract();
        ffi.catalog.insert_record(RecordDef {
            id: RecordId::new("Point"),
            is_repr_c: true,
            is_error: false,
            fields: vec![
                FieldDef {
                    name: FieldName::new("x"),
                    type_expr: TypeExpr::Primitive(PrimitiveType::F64),
                    doc: None,
                    default: None,
                },
                FieldDef {
                    name: FieldName::new("y"),
                    type_expr: TypeExpr::Primitive(PrimitiveType::F64),
                    doc: None,
                    default: None,
                },
            ],
            constructors: vec![],
            methods: vec![],
            doc: None,
            deprecated: None,
        });

        let library = test::lower(&ffi);

        let output = DartEmitter::emit(&library, "test");

        assert!(library.records[0].blittable_layout.is_some());
        assert!(
            output
                .lib
                .contains("final class _$Point$Struct extends $$ffi.Struct")
        );
    }

    #[test]
    pub fn non_blittable_record_does_not_produce_dart_ffi_struct() {
        let mut ffi = test::empty_contract();
        ffi.catalog.insert_record(RecordDef {
            id: RecordId::new("Person"),
            is_repr_c: false,
            is_error: false,
            fields: vec![
                FieldDef {
                    name: FieldName::new("age"),
                    type_expr: TypeExpr::Primitive(PrimitiveType::U64),
                    doc: None,
                    default: None,
                },
                FieldDef {
                    name: FieldName::new("name"),
                    type_expr: TypeExpr::String,
                    doc: None,
                    default: None,
                },
            ],
            constructors: vec![],
            methods: vec![],
            doc: None,
            deprecated: None,
        });

        let library = test::lower(&ffi);

        assert!(library.records[0].blittable_layout.is_none());
    }

    #[test]
    pub fn error_record_implements_exception() {
        let mut ffi = test::empty_contract();
        ffi.catalog.insert_record(RecordDef {
            id: RecordId::new("AppError"),
            is_repr_c: false,
            is_error: true,
            fields: vec![FieldDef {
                name: FieldName::new("details"),
                type_expr: TypeExpr::String,
                doc: None,
                default: None,
            }],
            constructors: vec![],
            methods: vec![],
            doc: None,
            deprecated: None,
        });

        let library = test::lower(&ffi);

        let output = DartEmitter::emit(&library, "test");

        assert!(library.records[0].is_error);
        assert!(
            output
                .lib
                .contains("final class AppError implements Exception")
        );
    }
}
