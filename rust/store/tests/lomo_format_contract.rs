//! Behavior Contract (P3-04)
//!
//! Capability: `.lomo` records use magic+schema+len+checksum; writes are temp+fsync+rename;
//! unknown schema / bad checksum fail closed; corrupt records can be isolated without deleting
//! the durable tree.
//!
//! Scenarios:
//! - Given a payload, when encoded and decoded, then fields round-trip and checksum matches.
//! - Given a flipped checksum or unknown schema version, when decode runs, then corruption
//!   errors are returned and no auto-delete occurs.
//! - Given a corrupt on-disk record, when isolated, then a `*.corrupt` sibling exists and the
//!   `.lomo` root remains.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use std::fs;

    use lomo_core::ErrorCategory;
    use lomo_store::{
        LOMO_CODEC_SCHEMA, LOMO_MAGIC, LomoPaths, LomoPayload, LomoRecordKind, decode_record,
        encode_record, isolate_corrupt_record, read_record, write_record_atomic,
    };
    use tempfile::tempdir;

    #[test]
    fn encode_decode_round_trip_and_atomic_write() {
        let dir = tempdir().expect("tempdir");
        let paths = LomoPaths::for_workspace(dir.path());
        paths.ensure_layout().expect("layout");

        let payload = LomoPayload {
            kind: LomoRecordKind::State,
            record_id: "memo-1".into(),
            body_json: r#"{"memo_id":"memo-1","pinned":true,"trashed":false}"#.into(),
        };
        let bytes = encode_record(&payload).expect("encode");
        assert_eq!(bytes.get(0..4), Some(LOMO_MAGIC.as_slice()));
        let schema_bytes = bytes.get(4..8).expect("schema bytes");
        let schema = u32::from_le_bytes(schema_bytes.try_into().expect("4 bytes"));
        assert_eq!(schema, LOMO_CODEC_SCHEMA);

        let decoded = decode_record(&bytes).expect("decode");
        assert_eq!(decoded.payload, payload);
        assert!(!decoded.checksum_hex.is_empty());

        let path = paths.state.join("memo-1.rec");
        write_record_atomic(&path, &payload).expect("write");
        assert!(path.exists());
        let on_disk = read_record(&path).expect("read");
        assert_eq!(on_disk.payload.record_id, "memo-1");
    }

    #[test]
    fn unknown_schema_and_checksum_fail_closed_without_delete() {
        let payload = LomoPayload {
            kind: LomoRecordKind::Operation,
            record_id: "op-1".into(),
            body_json: "{}".into(),
        };
        let mut bytes = encode_record(&payload).expect("encode");

        bytes
            .get_mut(4..8)
            .expect("schema mut")
            .copy_from_slice(&99u32.to_le_bytes());
        let err = decode_record(&bytes).expect_err("unknown schema");
        assert_eq!(err.category(), ErrorCategory::Corruption);
        assert_eq!(err.code(), "lomo_unknown_schema");

        let mut bytes = encode_record(&payload).expect("encode");
        if let Some(b) = bytes.get_mut(12) {
            *b ^= 0xff;
        }
        let err = decode_record(&bytes).expect_err("bad checksum");
        assert_eq!(err.code(), "lomo_checksum_mismatch");

        let mut bytes = encode_record(&payload).expect("encode");
        if let Some(b) = bytes.get_mut(0) {
            *b = b'X';
        }
        let err = decode_record(&bytes).expect_err("bad magic");
        assert_eq!(err.code(), "lomo_bad_magic");

        let dir = tempdir().expect("tempdir");
        let paths = LomoPaths::for_workspace(dir.path());
        paths.ensure_layout().expect("layout");
        let path = paths.operations.join("bad.rec");
        write_record_atomic(
            &path,
            &LomoPayload {
                kind: LomoRecordKind::Operation,
                record_id: "bad".into(),
                body_json: "{}".into(),
            },
        )
        .expect("write");
        let mut raw = fs::read(&path).expect("read");
        if let Some(b) = raw.get_mut(12) {
            *b ^= 0xff;
        }
        fs::write(&path, &raw).expect("corrupt write");
        let err = read_record(&path).expect_err("must fail");
        assert_eq!(err.code(), "lomo_checksum_mismatch");
        assert!(path.exists(), "must not auto-delete corrupt record");
        assert!(paths.root.exists(), "must not delete .lomo tree");

        let isolated = isolate_corrupt_record(&path).expect("isolate");
        assert!(isolated.exists());
        assert!(!path.exists());
        assert!(paths.root.exists());
    }

    #[test]
    fn truncated_and_length_mismatch_fail_closed() {
        let payload = LomoPayload {
            kind: LomoRecordKind::History,
            record_id: "hist-1".into(),
            body_json: r#"{"memo_id":"m","revision":1,"content":"x","file_fingerprint":"f"}"#
                .into(),
        };
        let bytes = encode_record(&payload).expect("encode");
        let truncated = bytes.get(0..20).expect("slice").to_vec();
        let err = decode_record(&truncated).expect_err("truncated");
        assert_eq!(err.category(), ErrorCategory::Corruption);
        assert_eq!(err.code(), "lomo_record_truncated");

        let mut overstated = encode_record(&payload).expect("encode");
        // Claim a larger payload length than bytes available.
        overstated
            .get_mut(8..12)
            .expect("len mut")
            .copy_from_slice(&10_000u32.to_le_bytes());
        let err = decode_record(&overstated).expect_err("length");
        assert_eq!(err.code(), "lomo_record_truncated");

        // Manifest kind round-trips through the same envelope.
        let manifest = LomoPayload {
            kind: LomoRecordKind::Manifest,
            record_id: "manifest".into(),
            body_json: r#"{"version":1}"#.into(),
        };
        let dir = tempdir().expect("tempdir");
        let paths = LomoPaths::for_workspace(dir.path());
        paths.ensure_layout().expect("layout");
        write_record_atomic(&paths.manifest, &manifest).expect("write manifest");
        let read = read_record(&paths.manifest).expect("read");
        assert_eq!(read.payload.kind, LomoRecordKind::Manifest);
        assert_eq!(read.payload.record_id, "manifest");

        // Missing file fails as storage, not silent empty.
        let missing = paths.state.join("nope.rec");
        let err = read_record(&missing).expect_err("missing");
        assert_eq!(err.code(), "lomo_read_failed");
    }

    #[test]
    fn payload_json_corruption_and_history_kind_round_trip() {
        use sha2::{Digest, Sha256};

        // Valid envelope framing around non-JSON payload fails closed as payload decode.
        let bad_payload = b"not-json";
        let mut crafted = Vec::new();
        crafted.extend_from_slice(LOMO_MAGIC);
        crafted.extend_from_slice(&LOMO_CODEC_SCHEMA.to_le_bytes());
        crafted.extend_from_slice(&u32::try_from(bad_payload.len()).expect("len").to_le_bytes());
        let checksum = Sha256::digest(bad_payload);
        crafted.extend_from_slice(&checksum);
        crafted.extend_from_slice(bad_payload);
        let err = decode_record(&crafted).expect_err("bad json");
        assert_eq!(err.code(), "lomo_payload_decode_failed");

        let history = LomoPayload {
            kind: LomoRecordKind::History,
            record_id: "m-r1".into(),
            body_json: r#"{"memo_id":"m","revision":1,"content":"h","file_fingerprint":"f"}"#
                .into(),
        };
        let dir = tempdir().expect("tempdir");
        let paths = LomoPaths::for_workspace(dir.path());
        paths.ensure_layout().expect("layout");
        let path = paths.history.join("m-r1.rec");
        write_record_atomic(&path, &history).expect("write");
        let read = read_record(&path).expect("read");
        assert_eq!(read.payload.kind, LomoRecordKind::History);
    }
}
