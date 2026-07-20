//! Behavior Contract
//!
//! Capability: preserve the provider-neutral sync-v1 planner and its binary envelope contract.
//!
//! Scenarios:
//! - Given valid local/remote/metadata snapshots, when planning runs, then observable actions and
//!   reasons match the provider-neutral policy.
//! - Given malformed paths, versions, strings, or counts, when the boundary decodes or plans, then
//!   a structured protocol error is returned.
//! - Given shared golden vectors, when encoding/decoding runs, then bytes remain stable.
//!
//! Observable outcomes: action lists, pending counts, exact bytes, and protocol error variants.
//! TDD proof: existing locked behavior tests; this edit only replaces lint-forbidden panic helpers.
//! Excludes: provider I/O, benchmark timing, and production sync orchestration.

#[cfg(test)]
#[allow(
    clippy::expect_used,
    clippy::unwrap_used,
    clippy::too_many_lines,
    reason = "contract/harness tests fail closed with panics on missing facts"
)]
mod tests {
    use std::fmt::Debug;

    use lomo_sync_core::{
        Action, Backend, Direction, LocalSnapshot, MAGIC, MetadataSnapshot, ProtocolError, Reason,
        RemoteAbsenceVerification, RemoteSnapshot, Request, decode_plan, encode_request, plan,
        plan_envelope,
    };

    const EMPTY_S3_REQUEST_HEX: &str =
        include_str!("../../../data/testResources/rust-sync/empty-s3-request.hex");
    const LOCAL_ONLY_S3_REQUEST_HEX: &str =
        include_str!("../../../data/testResources/rust-sync/local-only-s3-request.hex");
    const LOCAL_ONLY_UPLOAD_PLAN_HEX: &str =
        include_str!("../../../data/testResources/rust-sync/local-only-upload-plan.hex");

    trait ResultTestExt<T> {
        fn test_ok(self, context: &str) -> T;
    }

    impl<T, E: Debug> ResultTestExt<T> for Result<T, E> {
        fn test_ok(self, context: &str) -> T {
            match self {
                Ok(value) => value,
                Err(error) => panic!("{context}: {error:?}"),
            }
        }
    }

    fn request(backend: Backend) -> Request {
        Request {
            backend,
            timestamp_tolerance_ms: 0,
            local: Vec::new(),
            remote: Vec::new(),
            metadata: Vec::new(),
            pre_resolved: Vec::new(),
            suppressed: Vec::new(),
            missing_remote_verification: Vec::new(),
            default_missing_remote_verification: RemoteAbsenceVerification::VerifiedAbsent,
        }
    }

    fn action(path: &str, direction: Direction, reason: Reason) -> Action {
        Action {
            path: path.to_owned(),
            direction,
            reason,
        }
    }

    #[test]
    fn local_only_file_uploads() {
        let mut request = request(Backend::S3);
        request.local.push(LocalSnapshot {
            path: "memo.md".into(),
            last_modified: 20,
            size: None,
            fingerprint: None,
        });
        let plan = plan(&request).test_ok("valid request");
        assert_eq!(
            plan.actions,
            vec![action("memo.md", Direction::Upload, Reason::LocalOnly)]
        );
    }

    #[test]
    fn unchanged_remote_absence_deletes_local_only_when_verified() {
        let mut request = request(Backend::S3);
        request.local.push(LocalSnapshot {
            path: "memo.md".into(),
            last_modified: 10,
            size: None,
            fingerprint: None,
        });
        request.metadata.push(MetadataSnapshot {
            path: "memo.md".into(),
            etag: Some("e".into()),
            remote_last_modified: Some(10),
            local_last_modified: Some(10),
            local_fingerprint: None,
            last_synced_at: 10,
        });
        let plan = plan(&request).test_ok("valid request");
        assert_eq!(
            plan.actions,
            vec![action(
                "memo.md",
                Direction::DeleteLocal,
                Reason::RemoteDeleted
            )]
        );
    }

    #[test]
    fn malformed_path_is_rejected_before_planning() {
        let mut request = request(Backend::S3);
        request.local.push(LocalSnapshot {
            path: "../memo.md".into(),
            last_modified: 1,
            size: None,
            fingerprint: None,
        });
        assert!(matches!(
            plan(&request),
            Err(ProtocolError::InvalidPath { .. })
        ));
    }

    #[test]
    fn envelope_rejects_unknown_version() {
        let mut bytes = MAGIC.to_vec();
        bytes.extend(99_u16.to_le_bytes());
        assert!(matches!(
            plan_envelope(&bytes),
            Err(ProtocolError::UnsupportedVersion(99))
        ));
    }

    #[test]
    fn empty_s3_request_matches_shared_golden_vector() {
        let encoded = encode_request(&request(Backend::S3)).test_ok("request encodes");
        assert_eq!(encoded, decode_hex(EMPTY_S3_REQUEST_HEX));
    }

    #[test]
    fn shared_upload_plan_decodes_to_provider_neutral_action() {
        let decoded = decode_plan(&decode_hex(LOCAL_ONLY_UPLOAD_PLAN_HEX)).test_ok("plan decodes");
        assert_eq!(
            decoded.actions,
            vec![action("memo.md", Direction::Upload, Reason::LocalOnly)]
        );
        assert_eq!(decoded.pending_changes, 1);
    }

    #[test]
    fn malformed_output_path_is_rejected_at_decode_boundary() {
        let bytes = decode_hex("4c4f4d4f01000100000001000000ff010101000000");
        assert!(matches!(
            decode_plan(&bytes),
            Err(ProtocolError::InvalidString {
                field: "action path"
            })
        ));
    }

    #[test]
    fn pending_count_mismatch_is_rejected_at_decode_boundary() {
        let bytes = decode_hex("4c4f4d4f01000000000001000000");
        assert!(matches!(
            decode_plan(&bytes),
            Err(ProtocolError::PendingCountMismatch {
                expected: 0,
                actual: 1
            })
        ));
    }

    #[test]
    fn local_only_s3_request_matches_shared_golden_vector() {
        let mut request = request(Backend::S3);
        request.local.push(LocalSnapshot {
            path: "memo.md".into(),
            last_modified: 20,
            size: None,
            fingerprint: None,
        });
        assert_eq!(
            encode_request(&request).test_ok("request encodes"),
            decode_hex(LOCAL_ONLY_S3_REQUEST_HEX)
        );
    }

    #[test]
    fn request_and_plan_envelopes_round_trip() {
        let mut request = request(Backend::WebDav);
        request.local.push(LocalSnapshot {
            path: "memo.md".into(),
            last_modified: 20,
            size: Some(3),
            fingerprint: Some("local".into()),
        });
        request.remote.push(RemoteSnapshot {
            path: "memo.md".into(),
            etag: Some("remote".into()),
            last_modified: Some(20),
            size: Some(4),
            fingerprint: None,
        });
        request.metadata.push(MetadataSnapshot {
            path: "memo.md".into(),
            etag: Some("remote".into()),
            remote_last_modified: Some(20),
            local_last_modified: Some(10),
            local_fingerprint: Some("old".into()),
            last_synced_at: 10,
        });
        let encoded = encode_request(&request).test_ok("request encodes");
        let output = plan_envelope(&encoded).test_ok("request plans");
        let decoded = decode_plan(&output).test_ok("plan decodes");
        assert_eq!(
            decoded.actions,
            vec![action("memo.md", Direction::Upload, Reason::LocalOnly)]
        );
    }

    fn decode_hex(value: &str) -> Vec<u8> {
        let value = value.trim();
        assert_eq!(value.len() % 2, 0, "hex fixture must contain whole bytes");
        value
            .as_bytes()
            .chunks_exact(2)
            .map(|pair| {
                let pair = std::str::from_utf8(pair).test_ok("hex fixture is ASCII");
                u8::from_str_radix(pair, 16).test_ok("hex fixture contains valid bytes")
            })
            .collect()
    }
}
