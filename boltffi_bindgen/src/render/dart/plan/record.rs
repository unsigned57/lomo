use crate::{
    ir::{PrimitiveType, ReadSeq, WriteSeq},
    render::dart::emit,
};

#[derive(Debug, Clone)]
pub struct DartRecordField {
    pub name: String,
    pub offset: usize,
    pub dart_type: String,
    pub read_seq: ReadSeq,
    pub write_seq: WriteSeq,
}

impl DartRecordField {
    pub fn wire_decode_expr(&self, reader_name: &str) -> String {
        emit::emit_reader_read(&self.read_seq, reader_name)
    }

    pub fn wire_encode_expr(&self, writer_name: &str) -> String {
        emit::emit_writer_write(&self.write_seq, writer_name, &self.name)
    }

    pub fn wire_encoded_size_expr(&self) -> String {
        emit::emit_size_expr(&self.write_seq.size)
    }
}

#[derive(Debug, Clone)]
pub struct DartBlittableLayout {
    pub struct_name: String,
    pub struct_size: usize,
    pub fields: Vec<DartBlittableField>,
}

#[derive(Debug, Clone)]
pub struct DartBlittableField {
    pub name: String,
    pub primitive: PrimitiveType,
    pub native_type: super::DartNativeType,
    pub offset_const_name: String,
    pub offset: usize,
}

impl DartBlittableField {
    pub fn blittable_decode_expr(&self, bytes_name: &str) -> String {
        emit::emit_read_blittable_value(&self.offset_const_name, self.primitive, bytes_name)
    }

    pub fn blittable_encode_expr(&self, bytes_name: &str) -> String {
        emit::emit_write_blittable_value(
            &self.offset_const_name,
            self.primitive,
            &self.name,
            bytes_name,
        )
    }
}

#[derive(Debug, Clone)]
pub struct DartRecord {
    pub name: String,
    pub is_error: bool,
    pub fields: Vec<DartRecordField>,
    pub blittable_layout: Option<DartBlittableLayout>,
    pub constructors: Vec<super::DartConstructor>,
    pub methods: Vec<super::DartFunction>,
}
