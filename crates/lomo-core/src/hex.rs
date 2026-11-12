//! Lowercase hex rendering for raw byte slices (P-05 single authority).
//!
//! Shared encoder for digest outputs, record checksums, CSPRNG tokens and MAC outputs.
//! SHA2 0.11 returns byte arrays without a formatting implementation; all callers render
//! the bytes through this authority so persisted identifiers retain the same encoding.

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
