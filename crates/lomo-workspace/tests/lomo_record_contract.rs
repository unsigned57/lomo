// Dependency upgrade — Test Change Justification:
// Reason category: mechanical migration.
// Old behavior/assertion being replaced: old dependency API spelling and boolean empty checks.
// Why old assertion is no longer correct: updated APIs and Rust 1.99 diagnostics require explicit
// byte encoding, image geometry types and equality assertions that print failing collections.
// Coverage preserved by: the same scenarios, input bytes, expected values, ordering and failures.
// Why this is not fitting the test to the implementation: observable behavior and golden vectors
// are unchanged; only equivalent API/assertion syntax and fixture construction are migrated.
//
//! Behavior Contract (P5-01 codec)
//!
//! Capability: workspace-owned `.lomo` durable record codec uses magic+schema+len+checksum;
//! atomic temp+fsync+rename writes; layout roots resolve v1 by default and v2 after layout head;
//! corrupt records fail closed without auto-delete of durable trees.
//!
//! Scenarios:
//! - Given a payload, when encoded/decoded, then fields round-trip and checksum matches.
//! - Given flipped checksum / unknown schema / bad magic, when decode runs, then corruption codes.
//! - Given a workspace without layout head, when `WorkspaceLayout::for_workspace` runs, then layout is V1
//!   and history/state point at `v1` segments.
//! - Given a V2 layout head, when paths resolve, then history/state point at `v2` segments.
//!
//! Observable outcomes: framed bytes, structured error codes, layout path segments.
//! Excludes: transaction recovery, `SQLite` projections, production dual-stack wiring.

#[cfg(test)]
#[path = "support/option_success.rs"]
mod option_success;
#[cfg(test)]
#[path = "support/result_failure.rs"]
mod result_failure;
#[cfg(test)]
#[path = "support/result_success.rs"]
mod result_success;

#[cfg(test)]
mod tests {
    use super::option_success::OptionTestExt;
    use super::result_failure::ResultFailureTestExt;
    use super::result_success::ResultTestExt;
    use lomo_core::ErrorCategory;
    use lomo_workspace::{
        LOMO_CODEC_SCHEMA, LOMO_MAGIC, LomoLayoutVersion, LomoPayload, LomoRecordKind,
        WorkspaceLayout, decode_record, encode_record, read_record, write_layout_head_v2,
        write_record_atomic,
    };
    use tempfile::tempdir;

    #[test]
    fn encode_decode_round_trip_and_atomic_write() {
        let dir = tempdir().test_ok("tempdir");
        let paths = WorkspaceLayout::for_workspace(dir.path());
        paths.ensure_layout().test_ok("layout");

        let payload = LomoPayload {
            kind: LomoRecordKind::State,
            record_id: "memo-1".into(),
            body_json: r#"{"memo_id":"memo-1","pinned":true,"trashed":false}"#.into(),
        };
        let bytes = encode_record(&payload).test_ok("encode");
        assert_eq!(bytes.get(0..4), Some(LOMO_MAGIC.as_slice()));
        let schema_bytes = bytes.get(4..8).test_ok("schema bytes");
        let schema = u32::from_le_bytes(schema_bytes.try_into().test_ok("4 bytes"));
        assert_eq!(schema, LOMO_CODEC_SCHEMA);

        let decoded = decode_record(&bytes).test_ok("decode");
        assert_eq!(decoded.payload, payload);
        assert_ne!(decoded.checksum_hex, "");

        let path = paths.state.join("memo-1.rec");
        write_record_atomic(&path, &payload).test_ok("write");
        assert!(path.exists());
        let on_disk = read_record(&path).test_ok("read");
        assert_eq!(on_disk.payload.record_id, "memo-1");
    }

    #[test]
    fn unknown_schema_checksum_and_magic_fail_closed() {
        let payload = LomoPayload {
            kind: LomoRecordKind::Operation,
            record_id: "op-1".into(),
            body_json: "{}".into(),
        };
        let mut bytes = encode_record(&payload).test_ok("encode");
        bytes
            .get_mut(4..8)
            .test_ok("schema mut")
            .copy_from_slice(&99u32.to_le_bytes());
        let err = decode_record(&bytes).test_err("unknown schema");
        assert_eq!(err.category(), ErrorCategory::Corruption);
        assert_eq!(err.code(), "lomo_unknown_schema");

        let mut bytes = encode_record(&payload).test_ok("encode");
        if let Some(b) = bytes.get_mut(12) {
            *b ^= 0xff;
        }
        let err = decode_record(&bytes).test_err("bad checksum");
        assert_eq!(err.code(), "lomo_checksum_mismatch");

        let mut bytes = encode_record(&payload).test_ok("encode");
        if let Some(b) = bytes.get_mut(0) {
            *b = b'X';
        }
        let err = decode_record(&bytes).test_err("bad magic");
        assert_eq!(err.code(), "lomo_bad_magic");
    }

    #[test]
    fn default_layout_is_v1_until_layout_head_switches() {
        let dir = tempdir().test_ok("tempdir");
        let paths = WorkspaceLayout::for_workspace(dir.path());
        assert_eq!(paths.layout, LomoLayoutVersion::V1);
        assert!(paths.history.ends_with("history/v1"));
        assert!(paths.state.ends_with("state/v1"));
        assert!(paths.local.ends_with("local/v1"));

        write_layout_head_v2(dir.path()).test_ok("layout head");
        let paths_v2 = WorkspaceLayout::for_workspace(dir.path());
        assert_eq!(paths_v2.layout, LomoLayoutVersion::V2);
        assert!(paths_v2.history.ends_with("history/v2"));
        assert!(paths_v2.state.ends_with("state/v2"));
    }
}
