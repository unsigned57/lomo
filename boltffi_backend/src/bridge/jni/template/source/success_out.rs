use crate::bridge::{
    c::TypeFragment,
    jni::{SuccessOutValue, SuccessOutWriter},
};

pub struct SuccessOutWriterView {
    pub symbol: String,
    pub value_jni_type: TypeFragment,
    pub value_c_type: TypeFragment,
    pub writes_scalar: bool,
    pub writes_bytes: bool,
    pub writes_record: bool,
}

impl SuccessOutWriterView {
    pub fn from_writer(writer: &SuccessOutWriter) -> Self {
        let value = writer.value();
        Self {
            symbol: writer.symbol().to_string(),
            value_jni_type: value
                .jni_type()
                .map(|ty| ty.as_type_fragment())
                .unwrap_or_else(|| TypeFragment::new("jbyteArray")),
            value_c_type: value
                .c_type()
                .cloned()
                .unwrap_or_else(|| TypeFragment::new("FfiBuf_u8")),
            writes_scalar: matches!(value, SuccessOutValue::Scalar { .. }),
            writes_bytes: matches!(value, SuccessOutValue::Bytes),
            writes_record: matches!(value, SuccessOutValue::Record { .. }),
        }
    }
}
