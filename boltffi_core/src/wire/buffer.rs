//! Move Rust values into and out of BoltFFI's binary transport format.
//!
//! `WireBuffer` is the owned container for the bytes produced by [`WireEncode`] and
//! consumed by [`WireDecode`]. It is the public entry point for the wire layer:
//! callers either build a buffer from a Rust value and pass the bytes across the
//! boundary, or receive bytes from the boundary and reconstruct a Rust value from them.
//!
//! The format is intentionally simple:
//!
//! - Fixed-size primitives use little-endian byte order.
//! - `bool` uses one byte: `0` for `false`, `1` for `true`.
//! - `String` stores a `u32` byte length followed by UTF-8 bytes.
//! - `Vec<T>` stores a `u32` element count followed by each element in order.
//! - `Option<T>` stores a one-byte tag, then the payload when present.
//! - `Result<T, E>` stores a one-byte tag, then the `Ok` or `Err` payload.
//!
//! A small set of types also support a blittable fast path. That optimization is
//! used only when the layout is safe to copy as raw bytes. It does not replace the
//! wire format as the main transport model; it is an implementation detail used for
//! bulk fixed-layout values such as primitive vectors.
//!
//! The wire layer separates two concerns:
//!
//! 1. `WireEncode` decides how many bytes a value needs and how to write them.
//! 2. `WireDecode` reconstructs a value and reports how many bytes were consumed.
//!
//! `WireBuffer` itself does not add framing, checksums, or schema metadata. It is
//! only an owned byte container around a single encoded value.
//!
//! Decoding follows the current implementations in `decode.rs`. In particular,
//! string decoding trusts that the encoded bytes are valid UTF-8 and rebuilds the
//! string directly after the length check. That means the buffer format assumes the
//! producer followed the same encoding rules.

use crate::wire::decode::{DecodeError, WireDecode};
use crate::wire::encode::WireEncode;

/// Owned bytes for one value in BoltFFI's wire format.
pub struct WireBuffer {
    data: Vec<u8>,
}

impl WireBuffer {
    /// Encode one Rust value into an owned wire buffer.
    ///
    /// This allocates exactly [`WireEncode::wire_size`] bytes, asks `value` to write
    /// its wire representation into that allocation, and stores the resulting bytes
    /// as one complete wire value.
    pub fn new<T: WireEncode>(value: &T) -> Self {
        let size = value.wire_size();
        let mut data = vec![0u8; size];
        value.encode_to(&mut data);
        Self { data }
    }

    /// Wrap already-encoded bytes as a `WireBuffer`.
    ///
    /// This does not validate the bytes or check that they contain exactly one
    /// well-formed value. It is a convenience for code that already received bytes
    /// from the FFI boundary and wants to use the `WireBuffer` API to inspect or
    /// decode them.
    pub fn from_bytes(data: Vec<u8>) -> Self {
        Self { data }
    }

    /// Decode the buffer as one value of type `T`.
    ///
    /// Decoding starts at the beginning of the buffer and uses [`WireDecode`] for `T`
    /// to reconstruct a value. Malformed input returns [`DecodeError`]. If the buffer
    /// contains trailing bytes after the decoded value, they are currently ignored.
    pub fn decode<T: WireDecode>(&self) -> Result<T, DecodeError> {
        let (value, _) = T::decode_from(&self.data)?;
        Ok(value)
    }

    /// Borrow the encoded bytes without transferring ownership.
    ///
    /// This is the usual way to hand the buffer to an FFI layer that only needs a
    /// temporary byte slice view.
    pub fn as_bytes(&self) -> &[u8] {
        &self.data
    }

    /// Consume the buffer and return the owned bytes.
    ///
    /// This is useful when the caller wants to move the encoded bytes into another
    /// owner, such as an FFI-owned buffer or transport container.
    pub fn into_bytes(self) -> Vec<u8> {
        self.data
    }

    /// Return the encoded byte length of the stored value.
    pub fn len(&self) -> usize {
        self.data.len()
    }

    /// Return whether the buffer currently holds zero bytes.
    ///
    /// An empty buffer is valid for types whose wire representation is empty, such as
    /// `()`.
    pub fn is_empty(&self) -> bool {
        self.data.is_empty()
    }
}

impl AsRef<[u8]> for WireBuffer {
    fn as_ref(&self) -> &[u8] {
        &self.data
    }
}

impl From<WireBuffer> for Vec<u8> {
    fn from(buffer: WireBuffer) -> Self {
        buffer.data
    }
}

/// Encode one value and return the owned wire bytes directly.
///
/// This is a convenience wrapper around [`WireBuffer::new`] followed by
/// [`WireBuffer::into_bytes`] when the intermediate `WireBuffer` object is not needed.
pub fn encode<T: WireEncode>(value: &T) -> Vec<u8> {
    WireBuffer::new(value).into_bytes()
}

/// Decode one value directly from wire bytes.
///
/// This is a convenience wrapper around [`WireDecode::decode_from`] that returns only
/// the decoded value. Trailing bytes, if present, are ignored.
pub fn decode<T: WireDecode>(data: &[u8]) -> Result<T, DecodeError> {
    let (value, _) = T::decode_from(data)?;
    Ok(value)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn buffer_primitive() {
        let buffer = WireBuffer::new(&42i32);
        assert_eq!(buffer.len(), 4);
        let decoded: i32 = buffer.decode().unwrap();
        assert_eq!(decoded, 42);
    }

    #[test]
    fn buffer_string() {
        let original = "hello world".to_string();
        let buffer = WireBuffer::new(&original);
        let decoded: String = buffer.decode().unwrap();
        assert_eq!(decoded, original);
    }

    #[test]
    fn buffer_vec() {
        let original = vec![1i32, 2, 3, 4, 5];
        let buffer = WireBuffer::new(&original);
        let decoded: Vec<i32> = buffer.decode().unwrap();
        assert_eq!(decoded, original);
    }

    #[test]
    fn encode_decode_helpers() {
        let original = vec!["hello".to_string(), "world".to_string()];
        let bytes = encode(&original);
        let decoded: Vec<String> = decode(&bytes).unwrap();
        assert_eq!(decoded, original);
    }
}
