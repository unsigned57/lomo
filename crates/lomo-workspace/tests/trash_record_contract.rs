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
//! Filename TDD proof: reports/manual/gallery-media-refresh/trash-red.log fails
//! on a digest-only name; trash-green.log verifies source-date labels and distinct same-day identities.
//! TDD proof: RED on 2026-08-09 because `lomo-workspace` had no durable trash record type or codec;
//! SAF trash existed only in app-private `SQLite` and a process-local body map.
//! Test Change Justification:
//! Reason category: requested external trash format change.
//! Old behavior/assertion being replaced: four-component binary record paths under the control tree.
//! Why old assertion is no longer correct: trash is a readable Markdown document directly under `.trash`.
//! Coverage preserved by: exact round-trip, deterministic identity-hiding paths, tamper and boundary rejection.
//! Why this is not fitting the test to the implementation: readable Markdown is the requested storage contract.
//! Storage TDD proof: native `session_ffi_contract` RED could not find public trash Markdown.
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
    fn trash_filename_starts_with_memo_date_and_keeps_distinct_identity() {
        let first = record();
        let path =
            trash_record_relative_path(&first.memo_id, &first.source_path).test_ok("dated path");
        assert!(
            path.as_str().starts_with(".trash/2026_08_09-"),
            "{}",
            path.as_str()
        );
        let other = trash_record_relative_path("2026_08_09_12:34:56_1", &first.source_path)
            .test_ok("second path");
        assert_ne!(path, other);
    }

    #[test]
    fn opaque_identity_uses_source_date_and_imported_labels_stay_filename_bounded() {
        let id = "m_00000000000000000000000000000001";
        let path = trash_record_relative_path(id, "notes/2026_11_09.md").test_ok("opaque identity");
        assert!(path.as_str().starts_with(".trash/2026_11_09-"));
        assert!(lomo_workspace::trash_record_path_matches_identity(
            path.as_str(),
            id
        ));
        assert!(!lomo_workspace::trash_record_path_matches_identity(
            path.as_str(),
            "another-memo"
        ));
        let source = format!("notes/{}.md", "文".repeat(80));
        let long = trash_record_relative_path(id, &source).test_ok("bounded display label");
        assert!(
            long.as_str()
                .strip_prefix(".trash/")
                .test_ok("filename")
                .len()
                <= 255
        );
        assert_eq!(
            trash_record_relative_path(id, "../2026_11_09.md")
                .test_err("escaped source")
                .code(),
            "invalid_workspace_path"
        );
    }

    #[test]
    fn durable_trash_record_round_trips_exact_recovery_facts_without_identity_in_path() {
        let expected = record();

        let bytes = encode_trash_record(&expected).test_ok("encode");
        let decoded = decode_trash_record(&bytes).test_ok("decode");
        let path =
            trash_record_relative_path(&expected.memo_id, &expected.source_path).test_ok("path");

        assert_eq!(decoded, expected);
        assert_eq!(path.as_str().split('/').count(), 2);
        assert!(path.as_str().starts_with(".trash/"));
        assert!(
            std::path::Path::new(path.as_str())
                .extension()
                .is_some_and(|extension| extension.eq_ignore_ascii_case("md"))
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
