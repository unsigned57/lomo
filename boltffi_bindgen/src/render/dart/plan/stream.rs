use crate::{
    ir::{ReadSeq, StreamMode},
    render::dart::emit,
};

#[derive(Debug, Clone)]
pub struct DartStream {
    pub name: String,
    pub item_ty: super::DartType,
    pub item_read_seq: ReadSeq,
    pub ffi_item_ty: super::DartNativeType,
    pub ffi_item_size: Option<usize>,
    pub subscribe_fn: super::DartNativeFunction,
    pub poll_fn: super::DartNativeFunction,
    pub pop_batch_fn: super::DartNativeFunction,
    pub wait_fn: super::DartNativeFunction,
    pub unsubscribe_fn: super::DartNativeFunction,
    pub free_fn: super::DartNativeFunction,
    pub mode: StreamMode,
}

impl DartStream {
    pub fn item_wire_decode_expr(&self, reader_name: &str) -> String {
        emit::emit_reader_read(&self.item_read_seq, reader_name)
    }
}
