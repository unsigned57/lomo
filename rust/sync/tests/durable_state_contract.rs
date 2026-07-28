//! Behavior Contract (P5-03 durable session/baseline/tombstone)
//!
//! Capability: durable `.lomo/sync/v1` records use schema/checksum/size limits; corrupt bytes
//! return `CorruptState` (corruption category) without clean slate; identity fence mismatches
//! reject.
//!
//! Scenarios:
//! - Given a session, when written and read, then fence and kind round-trip.
//! - Given corrupt magic or checksum, when decode runs, then corruption codes fire and bytes stay.
//! - Given oversized payload, when encode runs, then resource-limit fails.
//! - Given mismatched generation fence, when `matches` runs, then validation rejects (no clean slate).
//!
//! Observable outcomes: round-trip equality, error codes/categories, retained corrupt files.
//! Excludes: `SQLite` authority, production DI, provider adapters.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use std::fs;

    use lomo_core::ErrorCategory;
    use lomo_sync::{
        BaselineHead, SYNC_DURABLE_SCHEMA, SessionKind, SyncIdentityFence, SyncPaths, SyncSession,
        decode_sync_record, encode_sync_record, error_category, read_baseline, read_session,
        write_baseline, write_session,
    };
    use lomo_workspace::{RemoteDatasetId, RemoteIdentityDigest, WorkspaceGenerationId};
    use tempfile::tempdir;

    fn fence() -> SyncIdentityFence {
        let generation = WorkspaceGenerationId::parse(&"ab".repeat(32)).expect("gen");
        let dataset = RemoteDatasetId::parse("dataset-1").expect("dataset");
        let identity = RemoteIdentityDigest::parse(&"cd".repeat(32)).expect("identity");
        SyncIdentityFence::from_parts(&generation, &dataset, &identity)
    }

    #[test]
    fn session_round_trips_on_disk() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let session =
            SyncSession::new(fence(), SessionKind::FirstTakeover, "session-1").expect("session");
        write_session(&paths, &session).expect("write");
        let loaded = read_session(&paths).expect("read");
        assert_eq!(loaded.schema, SYNC_DURABLE_SCHEMA);
        assert_eq!(loaded.kind, SessionKind::FirstTakeover);
        assert_eq!(loaded.session_id, "session-1");
        assert_eq!(loaded.fence, session.fence);
    }

    #[test]
    fn baseline_empty_when_missing_and_round_trips_when_present() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        let empty = read_baseline(&paths).expect("missing is empty");
        assert!(!empty.is_established());
        assert!(empty.entries.is_empty());

        let mut head = BaselineHead::empty();
        head.fence = Some(fence());
        write_baseline(&paths, &head).expect("write");
        let loaded = read_baseline(&paths).expect("read");
        assert!(loaded.is_established());
        assert_eq!(loaded.fence, head.fence);
    }

    #[test]
    fn corrupt_magic_is_corrupt_state_not_clean_slate() {
        let temporary = tempdir().expect("temp");
        let paths = SyncPaths::for_workspace(temporary.path());
        paths.ensure_layout().expect("layout");
        let corrupt = b"BAD!\x01\x00\x00\x00";
        fs::write(&paths.session, corrupt).expect("seed corrupt");
        let err = read_session(&paths).expect_err("must fail");
        assert_eq!(error_category(&err), ErrorCategory::Corruption);
        assert!(
            err.code() == "sync_bad_magic"
                || err.code() == "sync_record_truncated"
                || err.code().starts_with("sync_"),
            "expected corrupt-state code, got {}",
            err.code()
        );
        // Bytes retained (no clean slate).
        assert_eq!(fs::read(&paths.session).expect("retained"), corrupt);
    }

    #[test]
    fn checksum_mismatch_is_corrupt_state() {
        let body = r#"{"schema":1}"#;
        let mut bytes = encode_sync_record(SYNC_DURABLE_SCHEMA, body).expect("encode");
        // Flip a payload byte after the checksum header.
        let last = bytes.len().checked_sub(1).expect("encoded bytes non-empty");
        let byte = bytes.get_mut(last).expect("last payload byte");
        *byte ^= 0xff;
        let err = decode_sync_record(&bytes).expect_err("checksum");
        assert_eq!(err.category(), ErrorCategory::Corruption);
        assert_eq!(err.code(), "sync_checksum_mismatch");
    }

    #[test]
    fn unknown_schema_is_corrupt_state() {
        let body = "{}";
        let bytes = encode_sync_record(99, body).expect("encode");
        let err = decode_sync_record(&bytes).expect_err("schema");
        assert_eq!(err.category(), ErrorCategory::Corruption);
        assert_eq!(err.code(), "sync_unknown_schema");
    }

    #[test]
    fn oversized_record_fails_resource_limit_on_encode() {
        let huge = "x".repeat(lomo_sync::MAX_DURABLE_RECORD_BYTES + 1);
        let err = encode_sync_record(SYNC_DURABLE_SCHEMA, &huge).expect_err("size");
        assert_eq!(err.category(), ErrorCategory::ResourceLimit);
        assert_eq!(err.code(), "sync_record_too_large");
    }

    #[test]
    fn identity_fence_mismatch_rejects_without_clean_slate() {
        let f = fence();
        let other_generation = WorkspaceGenerationId::parse(&"11".repeat(32)).expect("gen");
        let dataset = RemoteDatasetId::parse("dataset-1").expect("dataset");
        let identity = RemoteIdentityDigest::parse(&"cd".repeat(32)).expect("identity");
        let err = f
            .matches(&other_generation, &dataset, &identity)
            .expect_err("mismatch");
        assert_eq!(err.category(), ErrorCategory::Validation);
        assert_eq!(err.code(), "sync_identity_mismatch");
    }
}
