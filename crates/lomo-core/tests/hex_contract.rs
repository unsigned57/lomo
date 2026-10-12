//! Behavior Contract (P-05 hex convergence, W-03)
//!
//! Capability: `lomo_core::hex_encode` is the single lowercase-hex authority for raw
//! byte slices — record checksums, CSPRNG tokens, and MAC outputs that are not
//! `LowerHex` digest types. Digest values continue to render through
//! `format!("{digest:x}")`; no other encoder may exist beside these two forms.
//!
//! Scenarios:
//! - Given an empty slice, when encoded, then the output is empty (no padding).
//! - Given a slice of any length, when encoded, then the output is exactly two
//!   lowercase `0-9a-f` characters per byte, most-significant nibble first.
//! - Given all-`0x00` or all-`0xFF` input, when encoded, then every emitted pair is
//!   the fixed-width `00`/`ff` form — never `0`/`f`.
//! - Given every byte value, when encoded, then the output equals the std
//!   `format!("{b:02x}")` oracle byte-for-byte.
//!
//! Observable outcomes: returned strings. Excludes: percent-encoding (`%XX`), digest
//! values (covered by `LowerHex` usage), and uppercase surfaces (`SigV4` `UriEncode`).

#[cfg(test)]
mod tests {
    use lomo_core::hex_encode;

    /// Given an empty slice, when encoded, then the output is empty.
    #[test]
    fn empty_input_encodes_to_empty_string() {
        assert_eq!(hex_encode(&[]), "");
    }

    /// Given an odd-length slice, when encoded, then every byte still emits its own
    /// two-character pair with no leading truncation or tail padding.
    #[test]
    fn odd_length_input_keeps_fixed_width_pairs() {
        let bytes = [0x01_u8, 0xab, 0xff];
        let encoded = hex_encode(&bytes);
        assert_eq!(encoded, "01abff");
        assert_eq!(encoded.len(), bytes.len() * 2);
    }

    /// Given all-`0x00` bytes, when encoded, then fixed-width `00` pairs are emitted.
    #[test]
    fn all_zero_bytes_emit_fixed_width_zero_pairs() {
        let encoded = hex_encode(&[0x00_u8; 16]);
        assert_eq!(encoded, "00".repeat(16));
    }

    /// Given all-`0xFF` bytes, when encoded, then fixed-width `ff` pairs are emitted.
    #[test]
    fn all_ff_bytes_emit_fixed_width_ff_pairs() {
        let encoded = hex_encode(&[0xff_u8; 16]);
        assert_eq!(encoded, "ff".repeat(16));
    }

    /// Given every byte value, when encoded, then the output equals the std
    /// `{b:02x}` oracle — lowercase, most-significant nibble first.
    #[test]
    fn every_byte_value_matches_std_lower_hex_oracle() {
        for byte in 0x00_u8..=0xFF {
            assert_eq!(
                hex_encode(&[byte]),
                format!("{byte:02x}"),
                "byte {byte:#04X} must encode byte-exactly"
            );
        }
    }

    /// Given a mixed slice spanning both nibble families, when encoded, then output
    /// is lowercase `0-9a-f` only, exactly `2 * len` characters long.
    #[test]
    fn mixed_bytes_stay_lowercase_and_fixed_width() {
        // Fixed pseudo-random bytes covering high/low nibbles and letter/digit mixes.
        let bytes = [
            0xde, 0xad, 0xbe, 0xef, 0x00, 0x10, 0xa0, 0x0f, 0x5c, 0x9f, 0x7e, 0x33, 0xc1, 0x42,
            0x88, 0x6b,
        ];
        let encoded = hex_encode(&bytes);
        assert_eq!(encoded, "deadbeef0010a00f5c9f7e33c142886b");
        assert_eq!(encoded.len(), bytes.len() * 2);
        assert!(
            encoded
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)),
            "hex output must be lowercase 0-9a-f only: {encoded}"
        );
    }
}
