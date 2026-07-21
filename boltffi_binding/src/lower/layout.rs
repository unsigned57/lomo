use boltffi_ast::RecordDef as SourceRecord;

use crate::{ByteAlignment, ByteOffset, ByteSize, FieldKey, FieldLayout, RecordLayout};

use super::{LowerError, error::UnsupportedType, primitive};

/// Computes the byte-level layout of a direct record.
///
/// Walks the fields in source order, advancing the running offset to
/// the alignment each primitive demands and tracking the largest
/// alignment seen as the record's own. The trailing size is rounded up
/// to that alignment so an array of these records lays out without
/// internal padding.
pub fn compute(record: &SourceRecord) -> Result<RecordLayout, LowerError> {
    let (offset, alignment, fields) = record.fields.iter().try_fold(
        (0_u64, 1_u64, Vec::new()),
        |(offset, alignment, mut fields), field| {
            let field_type = primitive::direct_field_type(&field.type_expr)
                .ok_or_else(|| LowerError::unsupported_type(UnsupportedType::RecordField))?;
            let field_alignment = field_type.byte_alignment().get();
            let field_offset = align_up(offset, field_alignment);
            fields.push(FieldLayout::new(
                FieldKey::from(field),
                ByteOffset::new(field_offset),
            ));
            Ok::<_, LowerError>((
                field_offset + field_type.byte_size().get(),
                alignment.max(field_alignment),
                fields,
            ))
        },
    )?;
    let alignment = ByteAlignment::new(alignment)
        .map_err(|error| LowerError::invalid_alignment(error.bytes()))?;

    Ok(RecordLayout::new(
        ByteSize::new(align_up(offset, alignment.get())),
        alignment,
        fields,
    ))
}

fn align_up(offset: u64, alignment: u64) -> u64 {
    (offset + alignment - 1) & !(alignment - 1)
}
