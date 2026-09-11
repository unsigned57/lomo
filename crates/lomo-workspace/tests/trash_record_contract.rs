//! Behavior Contract — durable SAF trash records
//!
//! Capability: represent one soft-deleted memo as a checksummed, versioned workspace fact that can
//! rebuild the query projection and recover the exact body without trusting app-private state.
//!
//! Scenarios:
//! - Given valid memo facts, when a trash record is encoded and decoded, then every recoverable fact
//!   round-trips exactly and the deterministic record path does not expose the memo identity.
//! - Given one byte of a durable trash record is changed, when it is decoded, then corruption is
//!   observable and no partial/default memo facts are returned.
//! - Given an invalid source path or non-positive chronology, when a record is constructed, then the
//!   invalid state is rejected before any platform action can be planned.
//!
//! Observable outcomes: encoded bytes, decoded typed facts, canonical relative path, error code.
//! TDD proof: RED on 2026-08-09 because `lomo-workspace` had no durable trash record type or codec;
//! SAF trash existed only in app-private `SQLite` and a process-local body map.
//! Excludes: Android `DocumentsProvider` execution, `SQLite` projection publication, Compose
//! rendering.

#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    use super::support::{OptionTestExt, ResultTestExt};
    use lomo_workspace::{
        SourceFingerprint, TrashRecordCreate, TrashRecordV1, decode_trash_record,
        encode_trash_record, trash_record_relative_path,
    };

    fn record() -> TrashRecordV1 {
        TrashRecordV1::try_new(TrashRecordCreate {
            memo_id: "2026_08_09_12:34:56_0".to_owned(),
            source_path: "2026_08_09.md".to_owned(),
            time_part: "12:34:56".to_owned(),
            source_fingerprint: SourceFingerprint::of_bytes(b"source").as_str().to_owned(),
            chronology_epoch_ms: 1_754_713_696_000,
            trashed_at_ms: 1_754_713_700_000,
            body: "body #tag\n- [ ] task".to_owned(),
            tags: vec!["tag".to_owned()],
            attachments: vec!["attachments/a.png".to_owned()],
            reminders: Vec::new(),
            has_todo: true,
            has_url: false,
        })
        .test_ok("valid trash record")
    }

    #[test]
    fn durable_trash_record_round_trips_exact_recovery_facts_without_identity_in_path() {
        let expected = record();

        let bytes = encode_trash_record(&expected).test_ok("encode");
        let decoded = decode_trash_record(&bytes).test_ok("decode");
        let path = trash_record_relative_path(&expected.memo_id).test_ok("path");

        assert_eq!(decoded, expected);
        assert_eq!(path.as_str().split('/').count(), 4);
        assert!(path.as_str().starts_with(".lomo/trash/v1/"));
        assert!(
            std::path::Path::new(path.as_str())
                .extension()
                .is_some_and(|extension| extension.eq_ignore_ascii_case("rec"))
        );
        assert!(!path.as_str().contains(&expected.memo_id));
    }

    #[test]
    fn durable_trash_record_rejects_tampered_bytes_without_partial_fallback() {
        let mut bytes = encode_trash_record(&record()).test_ok("encode");
        let last = bytes.last_mut().test_ok("payload byte");
        *last ^= 1;

        let error = decode_trash_record(&bytes).test_err("tampering must fail closed");

        assert_eq!(error.code(), "lomo_checksum_mismatch");
    }

    #[test]
    fn durable_trash_record_rejects_invalid_boundary_facts() {
        let bad_path = TrashRecordV1::try_new(TrashRecordCreate {
            memo_id: "2026_08_09_12:34:56_0".to_owned(),
            source_path: "../outside.md".to_owned(),
            time_part: "12:34:56".to_owned(),
            source_fingerprint: SourceFingerprint::of_bytes(b"source").as_str().to_owned(),
            chronology_epoch_ms: 1,
            trashed_at_ms: 2,
            body: "body".to_owned(),
            tags: Vec::new(),
            attachments: Vec::new(),
            reminders: Vec::new(),
            has_todo: false,
            has_url: false,
        })
        .test_err("escaped source path");
        assert_eq!(bad_path.code(), "invalid_workspace_path");

        let bad_time = TrashRecordV1::try_new(TrashRecordCreate {
            memo_id: "2026_08_09_12:34:56_0".to_owned(),
            source_path: "2026_08_09.md".to_owned(),
            time_part: "12:34:56".to_owned(),
            source_fingerprint: SourceFingerprint::of_bytes(b"source").as_str().to_owned(),
            chronology_epoch_ms: 0,
            trashed_at_ms: 2,
            body: "body".to_owned(),
            tags: Vec::new(),
            attachments: Vec::new(),
            reminders: Vec::new(),
            has_todo: false,
            has_url: false,
        })
        .test_err("non-positive chronology");
        assert_eq!(bad_time.code(), "invalid_trash_chronology");
    }
}
