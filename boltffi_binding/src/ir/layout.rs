use serde::{Deserialize, Serialize};
use std::{error::Error, fmt};

use crate::FieldKey;

/// A size in bytes.
///
/// Distinct from [`ByteOffset`] and [`ByteAlignment`] so a layout cannot
/// accidentally use a size where it meant an offset.
#[derive(
    Clone, Copy, Debug, Default, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize,
)]
#[serde(transparent)]
pub struct ByteSize(u64);

impl ByteSize {
    /// Builds a byte size.
    pub const fn new(bytes: u64) -> Self {
        Self(bytes)
    }

    /// Returns the size in bytes.
    pub const fn get(self) -> u64 {
        self.0
    }
}

/// An offset in bytes from the start of a value.
///
/// Distinct from [`ByteSize`] so layout consumers cannot confuse where a
/// field begins with how large the whole value is.
#[derive(
    Clone, Copy, Debug, Default, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize,
)]
#[serde(transparent)]
pub struct ByteOffset(u64);

impl ByteOffset {
    /// Builds a byte offset.
    pub const fn new(bytes: u64) -> Self {
        Self(bytes)
    }

    /// Returns the offset in bytes.
    pub const fn get(self) -> u64 {
        self.0
    }
}

/// An ABI alignment in bytes.
///
/// Always a non-zero power of two. The constructor enforces the invariant;
/// a deserialized contract that produces an invalid alignment is rejected
/// by [`Bindings::validate`](crate::Bindings::validate).
///
/// # Example
///
/// `ByteAlignment::new(8)` succeeds. `ByteAlignment::new(0)` and
/// `ByteAlignment::new(3)` return [`AlignmentError`].
#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
#[serde(transparent)]
pub struct ByteAlignment(u64);

impl ByteAlignment {
    /// Returns `bytes` as an alignment, or [`AlignmentError`] when `bytes`
    /// is zero or not a power of two.
    pub const fn new(bytes: u64) -> Result<Self, AlignmentError> {
        if bytes != 0 && bytes.is_power_of_two() {
            Ok(Self(bytes))
        } else {
            Err(AlignmentError { bytes })
        }
    }

    /// Returns the alignment in bytes.
    pub const fn get(self) -> u64 {
        self.0
    }
}

/// Returned when a byte count is rejected as an alignment.
///
/// Carries the rejected value so a diagnostic can show what was offered.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct AlignmentError {
    bytes: u64,
}

impl AlignmentError {
    /// Returns the rejected byte count.
    pub const fn bytes(self) -> u64 {
        self.bytes
    }
}

impl fmt::Display for AlignmentError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            formatter,
            "alignment must be a non-zero power of two, got {}",
            self.bytes
        )
    }
}

impl Error for AlignmentError {}

/// Where one field sits inside a record's bytes.
///
/// Direct records cross the boundary as raw memory. Both sides have to
/// agree on the offset of every field, so the offset is part of the
/// contract instead of being recomputed by every consumer.
///
/// # Example
///
/// In a `Point { x: f64, y: f64 }` whose layout is the obvious one, `x` has
/// offset `ByteOffset::new(0)` and `y` has offset `ByteOffset::new(8)`.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct FieldLayout {
    key: FieldKey,
    offset: ByteOffset,
}

impl FieldLayout {
    pub(crate) fn new(key: FieldKey, offset: ByteOffset) -> Self {
        Self { key, offset }
    }

    /// Returns the field key.
    pub fn key(&self) -> &FieldKey {
        &self.key
    }

    /// Returns the offset in bytes.
    pub const fn offset(&self) -> ByteOffset {
        self.offset
    }
}

/// The byte-level shape of a direct record value.
///
/// A direct record is one whose Rust struct layout is itself the wire
/// shape: foreign code reads its fields by offset rather than asking Rust
/// to serialize and deserialize on every call. `RecordLayout` is the
/// agreement that makes that possible: total size, required alignment, and
/// the offset of every field.
///
/// Encoded records do not carry a `RecordLayout`. Holding one means the
/// classifier chose the direct value path.
///
/// # Example
///
/// `struct Point { x: f64, y: f64 }` has size 16, alignment 8, and field
/// offsets `x = 0`, `y = 8`.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct RecordLayout {
    size: ByteSize,
    alignment: ByteAlignment,
    fields: Vec<FieldLayout>,
}

impl RecordLayout {
    pub(crate) fn new(size: ByteSize, alignment: ByteAlignment, fields: Vec<FieldLayout>) -> Self {
        Self {
            size,
            alignment,
            fields,
        }
    }

    /// Returns the total size in bytes.
    pub const fn size(&self) -> ByteSize {
        self.size
    }

    /// Returns the alignment in bytes.
    pub const fn alignment(&self) -> ByteAlignment {
        self.alignment
    }

    /// Returns the field layouts in source order.
    pub fn fields(&self) -> &[FieldLayout] {
        &self.fields
    }

    /// Returns the layout for `key`, or `None`.
    pub fn field(&self, key: &FieldKey) -> Option<&FieldLayout> {
        self.fields.iter().find(|field| field.key() == key)
    }
}

#[cfg(test)]
mod tests {
    use super::ByteAlignment;

    #[test]
    fn accepts_power_of_two_alignments() {
        assert_eq!(ByteAlignment::new(8).map(ByteAlignment::get), Ok(8));
    }

    #[test]
    fn rejects_zero_alignment() {
        assert_eq!(ByteAlignment::new(0).map_err(|error| error.bytes()), Err(0));
    }

    #[test]
    fn rejects_non_power_of_two_alignment() {
        assert_eq!(ByteAlignment::new(3).map_err(|error| error.bytes()), Err(3));
    }
}
