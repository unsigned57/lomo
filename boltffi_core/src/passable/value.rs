use crate::types::{FfiBuf, FfiSpan};

/// Describes how a Rust value is unpacked from ABI input and packed into ABI output.
///
/// This trait is the boundary between generated foreign-callable wrappers and the
/// Rust values those wrappers work with. `In` and `Out` are the concrete ABI-facing
/// shapes that cross the boundary for a given Rust type.
///
/// For direct scalar values, `In` and `Out` are usually the same Rust primitive.
/// For values that need owned or borrowed byte storage, `In` and `Out` are FFI
/// transport types such as [`FfiSpan`] and [`FfiBuf`].
///
/// # Safety
///
/// `unpack` may dereference raw pointers embedded in `Self::In`. Implementations
/// must document which invariants the caller has to uphold for the input value.
pub unsafe trait Passable: Sized {
    /// The ABI representation accepted when moving this value into Rust.
    type In;

    /// The ABI representation produced when moving this value out of Rust.
    type Out;

    /// Reconstructs the Rust value from its ABI input form.
    ///
    /// # Safety
    ///
    /// The caller must guarantee that `input` satisfies the invariants expected by
    /// the concrete implementation. For pointer-backed inputs such as [`FfiSpan`],
    /// that includes pointer validity, length validity, and lifetime guarantees for
    /// the duration of the call.
    unsafe fn unpack(input: Self::In) -> Self;

    /// Converts the Rust value into its ABI output form.
    fn pack(self) -> Self::Out;
}

macro_rules! impl_passable_scalar {
    ($($ty:ty),* $(,)?) => {
        $(
            unsafe impl Passable for $ty {
                type In = $ty;
                type Out = $ty;

                unsafe fn unpack(input: $ty) -> Self {
                    input
                }

                fn pack(self) -> $ty {
                    self
                }
            }
        )*
    };
}

impl_passable_scalar!(
    i8, i16, i32, i64, u8, u16, u32, u64, f32, f64, bool, usize, isize
);

unsafe impl Passable for String {
    type In = FfiSpan;
    type Out = FfiBuf;

    unsafe fn unpack(input: FfiSpan) -> Self {
        let bytes = unsafe { input.as_bytes() };
        core::str::from_utf8(bytes)
            .expect("invalid UTF-8 in FfiSpan")
            .to_string()
    }

    fn pack(self) -> FfiBuf {
        FfiBuf::from_vec(self.into_bytes())
    }
}

#[cfg(test)]
mod tests {
    use crate::types::FfiSpan;

    use super::Passable;

    #[test]
    fn primitive_roundtrip() {
        let value: i32 = 42;
        let packed = value.pack();
        let unpacked = unsafe { i32::unpack(packed) };
        assert_eq!(unpacked, 42);
    }

    #[test]
    fn bool_roundtrip() {
        assert!(unsafe { bool::unpack(true.pack()) });
        assert!(!unsafe { bool::unpack(false.pack()) });
    }

    #[test]
    fn string_pack() {
        let value = String::from("hello");
        let buffer = value.pack();
        assert_eq!(buffer.len(), 5);
    }

    #[test]
    fn string_roundtrip() {
        let original = String::from("hello world");
        let bytes = original.as_bytes();
        let span = FfiSpan {
            ptr: bytes.as_ptr(),
            len: bytes.len(),
        };
        let recovered = unsafe { String::unpack(span) };
        assert_eq!(recovered, "hello world");
    }
}
