//! Lowercase hex rendering for raw byte slices (P-05 single authority).
//!
//! Digest values (`Sha256::digest`, `Hasher::finalize`) already implement
//! `core::fmt::LowerHex` and must render via `format!("{digest:x}")`; this function
//! is the single shared encoder for `&[u8]` payloads that are not digest types —
//! record checksums, CSPRNG tokens, MAC outputs. No third hand-rolled nibble or
//! `{b:02x}` loop may exist beside these two forms.

/// Encodes `bytes` as lowercase hex: exactly two `0-9a-f` characters per byte,
/// most-significant nibble first. Empty input yields an empty string.
#[must_use]
pub fn hex_encode(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len().saturating_mul(2));
    for byte in bytes {
        out.push(nibble_hex(byte >> 4));
        out.push(nibble_hex(byte & 0x0f));
    }
    out
}

/// Renders one nibble (0..=15 by construction — callers pass masked halves only)
/// as its lowercase hex digit, with no lookup table or fallible path.
fn nibble_hex(nibble: u8) -> char {
    if nibble < 10 {
        char::from(b'0' + nibble)
    } else {
        char::from(b'a' + (nibble - 10))
    }
}
