//! Behavior Contract
//!
//! Capability: staged media always has a durable owner; releasing one holder never destroys bytes
//! another holder still needs.
//!
//! Scenarios:
//! - Given two holders leasing the same artifact, when the first releases, then the staged bytes
//!   survive and one lease remains; when the last releases, then the bytes are deleted.
//! - Given two same-named different-digest stages in one stage directory, when recorded, then each
//!   keeps its own digest and receives a distinct deterministic destination.
//! - Given a recorded artifact, when the ledger is re-loaded (process restart), then the artifact
//!   and its lease are still present.
//! - Given a ledger whose staged bytes vanished, when reconciled, then the artifact is reported
//!   missing so a draft failure can be surfaced instead of a silent empty plan.
//! - Given an unleased artifact, when discarded, then the bytes are deleted; a leased artifact is
//!   never discarded.
//!
//! Observable outcomes: staged file existence, ledger reload, resolved destinations.
//! TDD proof: the pre-fix registry keyed staged facts by basename in memory and rejected a second
//! digest at the same key, so the same-name scenario could not even be represented.
//! Excludes: platform writes, promote, FFI, production DI.

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on missing facts"
)]
mod tests {
    use std::fs;

    use lomo_media::{
        ArtifactId, MediaSource, StageLease, StageLedger, StageOwnerKind, stage_directory_of,
        stage_media, write_bytes_for_tests,
    };
    use tempfile::tempdir;

    const PNG_1X1: &[u8] = &[
        0x89, b'P', b'N', b'G', b'\r', b'\n', 0x1a, b'\n', 0x00, 0x00, 0x00, 0x0d, b'I', b'H',
        b'D', b'R', 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00,
        0x90, 0x77, 0x53, 0xde, 0x00, 0x00, 0x00, 0x0c, b'I', b'D', b'A', b'T', 0x08, 0xd7, 0x63,
        0xf8, 0xcf, 0xc0, 0x00, 0x00, 0x03, 0x01, 0x01, 0x00, 0x18, 0xdd, 0x8d, 0xb4, 0x00, 0x00,
        0x00, 0x00, b'I', b'E', b'N', b'D', 0xae, 0x42, 0x60, 0x82,
    ];

    const PNG_1X1_ALT: &[u8] = &[
        0x89, b'P', b'N', b'G', b'\r', b'\n', 0x1a, b'\n', 0x00, 0x00, 0x00, 0x0d, b'I', b'H',
        b'D', b'R', 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00,
        0x90, 0x77, 0x53, 0xde, 0x00, 0x00, 0x00, 0x0c, b'I', b'D', b'A', b'T', 0x08, 0xd7, 0x63,
        0xf8, 0xcf, 0xc0, 0x00, 0x00, 0x03, 0x01, 0x01, 0x00, 0x18, 0xdd, 0x8d, 0xb5, 0x00, 0x00,
        0x00, 0x00, b'I', b'E', b'N', b'D', 0xae, 0x42, 0x60, 0x83,
    ];

    fn stage(root: &std::path::Path, bytes: &[u8], name: &str) -> lomo_media::MediaStaged {
        let incoming = root.join("incoming");
        fs::create_dir_all(&incoming).expect("create incoming temp root");
        let source = incoming.join(format!("{name}.png"));
        write_bytes_for_tests(&source, bytes).expect("write source");
        stage_media(root, MediaSource::StagedTemp { path: source }, name).expect("stage")
    }

    fn lease(staged: &lomo_media::MediaStaged, kind: StageOwnerKind, owner: &str) -> StageLease {
        StageLease::new(ArtifactId::of_digest(&staged.digest), kind, owner).expect("lease")
    }

    #[test]
    fn releasing_one_holder_keeps_bytes_another_holder_needs() {
        let root = tempdir().expect("temp");
        let staged = stage(root.path(), PNG_1X1, "IMG_0001");
        let stage_dir = stage_directory_of(&staged.staging_path).expect("stage dir");
        let mut ledger = StageLedger::load(&stage_dir).expect("load");

        let first = lease(&staged, StageOwnerKind::PendingOperation, "op-1");
        let second = lease(&staged, StageOwnerKind::PendingOperation, "op-2");
        ledger
            .record(None, &staged, first.clone())
            .expect("lease one");
        let record = ledger
            .record(None, &staged, second.clone())
            .expect("lease two");
        assert_eq!(record.leases.len(), 2);

        let outcome = ledger.release(&stage_dir, &first).expect("release one");
        assert_eq!(outcome.remaining_leases, 1);
        assert!(!outcome.bytes_deleted);
        assert!(staged.staging_path.is_file(), "shared bytes must survive");

        let outcome = ledger.release(&stage_dir, &second).expect("release two");
        assert_eq!(outcome.remaining_leases, 0);
        assert!(outcome.bytes_deleted);
        assert!(!staged.staging_path.exists(), "last release deletes bytes");
    }

    #[test]
    fn same_name_different_digest_keep_identity_and_distinct_destinations() {
        let root = tempdir().expect("temp");
        let first = stage(root.path(), PNG_1X1, "IMG_0001");
        let second = stage(root.path(), PNG_1X1_ALT, "IMG_0001");
        assert_ne!(first.digest, second.digest);
        let stage_dir = stage_directory_of(&first.staging_path).expect("stage dir");
        let mut ledger = StageLedger::load(&stage_dir).expect("load");

        let first_record = ledger
            .record(
                None,
                &first,
                lease(&first, StageOwnerKind::Draft, "draft-a"),
            )
            .expect("record first");
        let second_record = ledger
            .record(
                None,
                &second,
                lease(&second, StageOwnerKind::Draft, "draft-b"),
            )
            .expect("record second");

        assert_eq!(first_record.digest, first.digest);
        assert_eq!(second_record.digest, second.digest);
        assert_ne!(
            first_record.suggested_final_relative_path, second_record.suggested_final_relative_path,
            "same-name different-digest must not collapse onto one path"
        );
        assert!(
            second_record
                .suggested_final_relative_path
                .starts_with("media/IMG_0001_"),
            "conflicting destination gets a deterministic suffix: {}",
            second_record.suggested_final_relative_path
        );
    }

    #[test]
    fn ledger_leases_survive_process_restart() {
        let root = tempdir().expect("temp");
        let staged = stage(root.path(), PNG_1X1, "IMG_0002");
        let stage_dir = stage_directory_of(&staged.staging_path).expect("stage dir");
        let mut ledger = StageLedger::load(&stage_dir).expect("load");
        ledger
            .record(
                None,
                &staged,
                lease(&staged, StageOwnerKind::Draft, "draft-a"),
            )
            .expect("record");

        let reloaded = StageLedger::load(&stage_dir).expect("reload");
        let records = reloaded.records_for_owner(StageOwnerKind::Draft, "draft-a");
        assert_eq!(records.len(), 1);
        let record = records.first().expect("one lease record");
        assert_eq!(record.digest, staged.digest);
        assert!(record.is_present());
    }

    #[test]
    fn missing_staged_bytes_are_reported_for_recovery() {
        let root = tempdir().expect("temp");
        let staged = stage(root.path(), PNG_1X1, "IMG_0003");
        let stage_dir = stage_directory_of(&staged.staging_path).expect("stage dir");
        let mut ledger = StageLedger::load(&stage_dir).expect("load");
        ledger
            .record(
                None,
                &staged,
                lease(&staged, StageOwnerKind::Draft, "draft-a"),
            )
            .expect("record");
        fs::remove_file(&staged.staging_path).expect("simulate external loss");

        let missing = ledger.missing_artifacts();
        assert_eq!(missing, vec![ArtifactId::of_digest(&staged.digest)]);
    }

    #[test]
    fn discard_keeps_bytes_that_another_holder_still_leases() {
        let root = tempdir().expect("temp");
        let staged = stage(root.path(), PNG_1X1, "IMG_0004");
        let stage_dir = stage_directory_of(&staged.staging_path).expect("stage dir");
        let mut ledger = StageLedger::load(&stage_dir).expect("load");
        let draft = lease(&staged, StageOwnerKind::Draft, "draft-a");
        ledger.record(None, &staged, draft.clone()).expect("record");
        // A second draft holds the same artifact (same digest, different draft).
        let other = lease(&staged, StageOwnerKind::Draft, "draft-b");
        ledger
            .record(None, &staged, other)
            .expect("record second draft");

        let outcome = ledger.release(&stage_dir, &draft).expect("discard draft-a");
        assert_eq!(outcome.remaining_leases, 1);
        assert!(!outcome.bytes_deleted);
        assert!(staged.staging_path.is_file());
    }

    #[test]
    fn transfer_moves_the_draft_claim_to_the_pending_operation() {
        let root = tempdir().expect("temp");
        let staged = stage(root.path(), PNG_1X1, "IMG_0005");
        let stage_dir = stage_directory_of(&staged.staging_path).expect("stage dir");
        let mut ledger = StageLedger::load(&stage_dir).expect("load");
        let draft = lease(&staged, StageOwnerKind::Draft, "draft-a");
        ledger.record(None, &staged, draft.clone()).expect("record");

        let pending = StageLease::new(
            draft.artifact_id.clone(),
            StageOwnerKind::PendingOperation,
            "op-1",
        )
        .expect("lease");
        ledger
            .transfer(&stage_dir, &draft, pending)
            .expect("transfer");

        let reloaded = StageLedger::load(&stage_dir).expect("reload");
        assert!(
            reloaded
                .records_for_owner(StageOwnerKind::Draft, "draft-a")
                .is_empty()
        );
        let pending_records = reloaded.records_for_owner(StageOwnerKind::PendingOperation, "op-1");
        assert_eq!(pending_records.len(), 1);
        assert!(
            staged.staging_path.is_file(),
            "transfer never deletes bytes"
        );
    }

    #[test]
    fn retried_transfer_stays_on_the_frozen_operation_identity() {
        let root = tempdir().expect("temp");
        let staged = stage(root.path(), PNG_1X1, "IMG_0007");
        let stage_dir = stage_directory_of(&staged.staging_path).expect("stage dir");
        let mut ledger = StageLedger::load(&stage_dir).expect("load");
        let draft = lease(&staged, StageOwnerKind::Draft, "draft-a");
        ledger.record(None, &staged, draft.clone()).expect("record");
        let pending = StageLease::new(
            draft.artifact_id.clone(),
            StageOwnerKind::PendingOperation,
            "op-1",
        )
        .expect("lease");

        ledger
            .transfer(&stage_dir, &draft, pending.clone())
            .expect("first transfer");
        // A retried submit observes the claim already on the same frozen operation.
        let outcome = ledger
            .transfer(&stage_dir, &draft, pending)
            .expect("idempotent retry");
        assert_eq!(outcome.remaining_leases, 1);
        assert!(staged.staging_path.is_file());
    }

    #[test]
    fn completing_the_last_lease_deletes_staged_bytes() {
        let root = tempdir().expect("temp");
        let staged = stage(root.path(), PNG_1X1, "IMG_0006");
        let stage_dir = stage_directory_of(&staged.staging_path).expect("stage dir");
        let mut ledger = StageLedger::load(&stage_dir).expect("load");
        let owner = lease(&staged, StageOwnerKind::PendingOperation, "op-9");
        ledger.record(None, &staged, owner.clone()).expect("record");
        ledger.release(&stage_dir, &owner).expect("release");
        assert!(!staged.staging_path.exists());
    }
}
