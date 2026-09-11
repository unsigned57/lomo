//! Behavior Contract (Stage-6 P6-01 versioned LAN frame codec)
//!
//! Capability: `lomo-lan` owns one versioned length-prefixed frame codec for LAN v2. Control frames
//! and encrypted chunk frames are distinct types with distinct ceilings; every header field is
//! validated before the declared payload length is allocated.
//!
//! Scenarios:
//! - Given a control frame, when encoded then decoded, then magic/version/type/payload round-trip.
//! - Given a declared length above the type ceiling, when decoded, then it fails closed **without**
//!   allocating the declared length.
//! - Given an unknown frame type, when decoded, then it fails closed.
//! - Given a protocol version other than v2, when decoded, then it fails closed (no v1 decoder).
//! - Given a wrong magic, when decoded, then it fails closed.
//! - Given a truncated header or truncated payload, when decoded, then it reports incomplete rather
//!   than inventing a frame.
//! - Given a chunk frame at exactly the sealed chunk ceiling, when decoded, then it is accepted;
//!   one byte more is rejected.
//!
//! Observable outcomes: encoded bytes, decoded frame values, `LomoError` code/category, and the
//! length the decoder is willing to reserve.
//!
//! Excludes: crypto, session state, transport sockets, journal durability, Kotlin adapters.

#![deny(unsafe_code)]

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::indexing_slicing,
    reason = "contract tests fail closed with panics and index fixed-size fixture buffers"
)]
mod tests {
    use lomo_core::ErrorCategory;
    use lomo_lan::{
        FrameKind, LAN_FRAME_MAGIC, LAN_PROTOCOL_VERSION, LanFrame, MAX_CONTROL_PAYLOAD_BYTES,
        MAX_SEALED_CHUNK_PAYLOAD_BYTES, decode_frame, encode_frame, peek_declared_payload_len,
    };

    fn header(kind_code: u16, version: u16, length: u32) -> Vec<u8> {
        let mut bytes = Vec::with_capacity(12);
        bytes.extend_from_slice(&LAN_FRAME_MAGIC);
        bytes.extend_from_slice(&version.to_be_bytes());
        bytes.extend_from_slice(&kind_code.to_be_bytes());
        bytes.extend_from_slice(&length.to_be_bytes());
        bytes
    }

    #[test]
    fn control_frame_round_trips_through_the_versioned_header() {
        let frame = LanFrame::new(FrameKind::PairHello, b"pair-hello-payload".to_vec())
            .expect("small control payload is within the control ceiling");
        let encoded = encode_frame(&frame);

        assert_eq!(&encoded[0..4], &LAN_FRAME_MAGIC);
        assert_eq!(
            u16::from_be_bytes([encoded[4], encoded[5]]),
            LAN_PROTOCOL_VERSION
        );

        let decoded = decode_frame(&encoded).expect("round trip decodes");
        assert_eq!(decoded.kind(), FrameKind::PairHello);
        assert_eq!(decoded.payload(), b"pair-hello-payload");
    }

    #[test]
    fn every_frame_kind_round_trips() {
        for kind in FrameKind::ALL {
            let frame = LanFrame::new(kind, vec![7_u8; 32]).expect("32-byte payload is in range");
            let decoded = decode_frame(&encode_frame(&frame)).expect("round trip decodes");
            assert_eq!(decoded.kind(), kind, "frame kind must survive the wire");
            assert_eq!(decoded.payload(), &[7_u8; 32]);
        }
    }

    #[test]
    fn control_payload_above_the_ceiling_is_rejected_before_allocation() {
        let oversized = u32::try_from(MAX_CONTROL_PAYLOAD_BYTES + 1)
            .expect("control ceiling fits in a u32 length field");
        let bytes = header(FrameKind::PairHello.code(), LAN_PROTOCOL_VERSION, oversized);

        let error = peek_declared_payload_len(&bytes)
            .expect_err("declared control length above the ceiling must fail closed");
        assert_eq!(error.category(), ErrorCategory::ResourceLimit);
        assert_eq!(error.code(), "lan_frame_payload_too_large");

        let error = decode_frame(&bytes).expect_err("decode must also fail closed");
        assert_eq!(error.code(), "lan_frame_payload_too_large");
    }

    #[test]
    fn sealed_chunk_ceiling_is_inclusive_and_one_byte_over_is_rejected() {
        let at_ceiling = u32::try_from(MAX_SEALED_CHUNK_PAYLOAD_BYTES)
            .expect("chunk ceiling fits in a u32 length field");
        let accepted = header(FrameKind::Chunk.code(), LAN_PROTOCOL_VERSION, at_ceiling);
        assert_eq!(
            peek_declared_payload_len(&accepted).expect("chunk ceiling is inclusive"),
            MAX_SEALED_CHUNK_PAYLOAD_BYTES
        );

        let rejected = header(
            FrameKind::Chunk.code(),
            LAN_PROTOCOL_VERSION,
            at_ceiling + 1,
        );
        let error = peek_declared_payload_len(&rejected)
            .expect_err("one byte above the chunk ceiling must fail closed");
        assert_eq!(error.code(), "lan_frame_payload_too_large");
    }

    #[test]
    fn a_control_kind_may_not_borrow_the_chunk_ceiling() {
        let chunk_sized = u32::try_from(MAX_CONTROL_PAYLOAD_BYTES + 1)
            .expect("control ceiling fits in a u32 length field");
        let bytes = header(
            FrameKind::ChunkAck.code(),
            LAN_PROTOCOL_VERSION,
            chunk_sized,
        );
        let error =
            peek_declared_payload_len(&bytes).expect_err("control kinds keep the control ceiling");
        assert_eq!(error.code(), "lan_frame_payload_too_large");
    }

    #[test]
    fn unknown_frame_kind_is_rejected() {
        let bytes = header(0xFFFF, LAN_PROTOCOL_VERSION, 4);
        let error = decode_frame(&bytes).expect_err("unknown frame kind must fail closed");
        assert_eq!(error.category(), ErrorCategory::Validation);
        assert_eq!(error.code(), "lan_frame_unknown_kind");
    }

    #[test]
    fn non_v2_protocol_version_is_rejected_without_a_legacy_decoder() {
        for version in [0_u16, 1, LAN_PROTOCOL_VERSION + 1] {
            let bytes = header(FrameKind::PairHello.code(), version, 4);
            let error = decode_frame(&bytes)
                .expect_err("only LAN protocol v2 decodes; there is no v1 compatibility path");
            assert_eq!(error.code(), "lan_frame_unsupported_version");
        }
    }

    #[test]
    fn wrong_magic_is_rejected() {
        let mut bytes = header(FrameKind::PairHello.code(), LAN_PROTOCOL_VERSION, 0);
        bytes[0] = b'X';
        let error = decode_frame(&bytes).expect_err("foreign magic must fail closed");
        assert_eq!(error.code(), "lan_frame_bad_magic");
    }

    #[test]
    fn truncated_header_and_payload_report_incomplete_instead_of_inventing_a_frame() {
        let frame =
            LanFrame::new(FrameKind::BatchApprove, vec![1, 2, 3, 4]).expect("payload in range");
        let encoded = encode_frame(&frame);

        for cut in 0..encoded.len() {
            let error = decode_frame(&encoded[..cut])
                .expect_err("a partial frame must never decode as a whole frame");
            assert_eq!(
                error.code(),
                "lan_frame_incomplete",
                "cut at {cut} must report incomplete"
            );
        }
        assert!(decode_frame(&encoded).is_ok(), "the full frame decodes");
    }

    #[test]
    fn frame_constructor_rejects_payloads_above_the_kind_ceiling() {
        let error = LanFrame::new(FrameKind::PairHello, vec![0; MAX_CONTROL_PAYLOAD_BYTES + 1])
            .expect_err("the constructor enforces the same ceiling as the decoder");
        assert_eq!(error.code(), "lan_frame_payload_too_large");
    }
}
