use serde::{Deserialize, Serialize};

/// A Rust primitive type that BoltFFI accepts at the source boundary.
///
/// The variants follow Rust's primitive names so scanned types can use a compact
/// enum instead of keeping primitive spellings as strings.
#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
pub enum Primitive {
    /// The Rust `bool` type.
    Bool,
    /// The Rust `i8` type.
    I8,
    /// The Rust `u8` type.
    U8,
    /// The Rust `i16` type.
    I16,
    /// The Rust `u16` type.
    U16,
    /// The Rust `i32` type.
    I32,
    /// The Rust `u32` type.
    U32,
    /// The Rust `i64` type.
    I64,
    /// The Rust `u64` type.
    U64,
    /// The Rust `isize` type.
    ISize,
    /// The Rust `usize` type.
    USize,
    /// The Rust `f32` type.
    F32,
    /// The Rust `f64` type.
    F64,
}

impl Primitive {
    /// Returns the Rust spelling of the primitive.
    ///
    /// The returned string is the Rust keyword or primitive type name.
    pub const fn rust_name(self) -> &'static str {
        match self {
            Self::Bool => "bool",
            Self::I8 => "i8",
            Self::U8 => "u8",
            Self::I16 => "i16",
            Self::U16 => "u16",
            Self::I32 => "i32",
            Self::U32 => "u32",
            Self::I64 => "i64",
            Self::U64 => "u64",
            Self::ISize => "isize",
            Self::USize => "usize",
            Self::F32 => "f32",
            Self::F64 => "f64",
        }
    }

    /// Returns the primitive matching a Rust primitive type name.
    ///
    /// The inverse of [`Primitive::rust_name`]. Returns `None` for any
    /// name outside BoltFFI's accepted primitive scalars.
    pub fn from_rust_name(name: &str) -> Option<Self> {
        Self::ALL
            .into_iter()
            .find(|primitive| primitive.rust_name() == name)
    }

    const ALL: [Self; 13] = [
        Self::Bool,
        Self::I8,
        Self::U8,
        Self::I16,
        Self::U16,
        Self::I32,
        Self::U32,
        Self::I64,
        Self::U64,
        Self::ISize,
        Self::USize,
        Self::F32,
        Self::F64,
    ];
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn from_rust_name_inverts_rust_name() {
        for primitive in Primitive::ALL {
            assert_eq!(
                Primitive::from_rust_name(primitive.rust_name()),
                Some(primitive)
            );
        }
    }

    #[test]
    fn from_rust_name_rejects_non_primitive() {
        assert_eq!(Primitive::from_rust_name("String"), None);
        assert_eq!(Primitive::from_rust_name("Point"), None);
    }
}
