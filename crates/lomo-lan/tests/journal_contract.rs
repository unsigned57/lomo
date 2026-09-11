//! Behavior Contract (Stage-6 P6-05 durable app-private LAN journal)
//!
//! Capability: LAN peer trust, accepted session identities, batch approvals and confirmed chunk
//! ranges survive process death in an app-private, checksummed journal that is never part of the
//! workspace, sync or an archive.
//!
//! Scenarios:
//! - Given a journal root inside a `.lomo` control tree, when opened, then it is rejected, because
//!   peer trust must never become syncable or archivable.
//! - Given peers, approvals and confirmed chunks written by one process, when a second process
//!   opens the same journal, then it observes exactly the same state.
//! - Given a revoked peer, when reloaded, then it is still present and still revoked, so later
//!   connections are refused explicitly rather than silently re-trusted.
//! - Given a transfer that confirmed some chunks, when it resumes, then only the unconfirmed
//!   indices are reported, in order.
//! - Given an accepted session id, when a second process tries to accept it as new, then replay is
//!   rejected while recovery can still identify it as the same durable session.
//! - Given confirming the same chunk twice, then the journal is idempotent.
//! - Given opened chunk bytes, when staged before confirmation, then identical retry is idempotent,
//!   different bytes fail closed, and confirmed payload bytes survive process restart.
//! - Given a pending batch, approval generation and per-item outcome, when the process restarts,
//!   then the complete recovery state is restored without re-approval or item duplication.
//! - Given a record whose checksum, magic or schema does not match, when opened, then it fails
//!   closed as corruption instead of resetting to an empty set.
//! - Given a body above the record ceiling, when encoded, then it is rejected.
//! - Given a full peer registry, when pairing another device, then it is rejected.
//!
//! Observable outcomes: reloaded journal contents, revocation state, unconfirmed index lists,
//! `LomoError` code/category, on-disk record bytes.
//!
//! Excludes: sockets, AEAD, `lomo-store` commit, Kotlin adapters.

#![deny(unsafe_code)]

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::indexing_slicing,
    reason = "contract tests fail closed with panics and index fixture records of known size"
)]
mod tests {
    use lomo_core::ErrorCategory;
    use lomo_lan::{
        ATTACHMENT_SLOT_BODY, ApprovedGeneration, ChunkBinding, DeviceId, DevicePublicKey,
        DisplayName, LanApproval, LanBatchId, LanBatchPlan, LanDurableBatch, LanItemOutcome,
        LanItemPlan, LanJournal, LanJournalPaths, LanSessionId, MAX_LAN_RECORD_BYTES, PeerRecord,
        decode_record, encode_record,
    };

    fn device_key() -> DevicePublicKey {
        use aws_lc_rs::encoding::AsBigEndian;
        use aws_lc_rs::signature::{ECDSA_P256_SHA256_ASN1_SIGNING, EcdsaKeyPair, KeyPair};
        let pair =
            EcdsaKeyPair::generate(&ECDSA_P256_SHA256_ASN1_SIGNING).expect("key pair generates");
        let bytes: aws_lc_rs::encoding::EcPublicKeyUncompressedBin<'_> =
            pair.public_key().as_be_bytes().expect("public key exports");
        DevicePublicKey::parse(bytes.as_ref()).expect("generated key is a valid P-256 point")
    }

    fn name(value: &str) -> DisplayName {
        DisplayName::parse(value).expect("fixture display name is valid")
    }

    fn session() -> LanSessionId {
        LanSessionId::parse("0123456789abcdef0123456789abcdef").expect("fixture session is valid")
    }

    fn batch() -> LanBatchId {
        LanBatchId::parse("batch-resume").expect("fixture batch id is valid")
    }

    fn chunk(index: u32) -> ChunkBinding {
        ChunkBinding::new(&session(), "batch-resume", 0, ATTACHMENT_SLOT_BODY, index)
            .expect("fixture binding is valid")
    }

    fn plan() -> LanBatchPlan {
        let batch = batch();
        let item = LanItemPlan::new(
            &batch,
            0,
            1_700_000_000_000,
            &"0".repeat(64),
            12,
            "Recovery title",
            Vec::new(),
        )
        .expect("item plan is valid");
        LanBatchPlan::new(batch, vec![item]).expect("batch plan is valid")
    }

    #[test]
    fn a_journal_root_under_a_lomo_control_tree_is_rejected() {
        let error = LanJournalPaths::new("/tmp/workspace/.lomo/private")
            .expect_err("peer trust must never live under .lomo");
        assert_eq!(error.category(), ErrorCategory::Validation);
        assert_eq!(error.code(), "lan_journal_root_invalid");

        LanJournalPaths::new("/tmp/app-private")
            .expect("an app-private root outside .lomo is accepted");
    }

    #[test]
    fn peers_approvals_and_confirmed_chunks_survive_a_process_restart() {
        let root = tempfile::tempdir().expect("app-private root is creatable");
        let paths = LanJournalPaths::new(root.path()).expect("paths build");

        let key = device_key();
        let peer = PeerRecord::paired(key, name("Tablet"), 1_700_000_000_000);
        let device_id = peer.device_id().clone();
        {
            let mut journal = LanJournal::open(paths.clone()).expect("journal opens");
            journal.store_peer(peer).expect("peer is stored");
            journal
                .store_approval(LanApproval::granted(batch(), 1_700_000_000_000, 600_000))
                .expect("approval is stored");
            journal.confirm_chunk(&chunk(0)).expect("chunk 0 confirmed");
            journal.confirm_chunk(&chunk(2)).expect("chunk 2 confirmed");
        }

        let reopened = LanJournal::open(paths).expect("a second process opens the same journal");
        assert_eq!(reopened.peers().len(), 1);
        let restored = reopened
            .peers()
            .get(&device_id)
            .expect("the peer survives the restart");
        assert_eq!(restored.display_name().as_str(), "Tablet");
        assert_eq!(restored.paired_at_ms(), 1_700_000_000_000);
        assert!(!restored.is_revoked());

        let approval = reopened
            .approval(&batch())
            .expect("the approval survives the restart");
        approval
            .assert_valid_at(1_700_000_300_000)
            .expect("a surviving approval is still inside its TTL");

        assert!(reopened.is_chunk_confirmed(&chunk(0)));
        assert!(reopened.is_chunk_confirmed(&chunk(2)));
        assert!(!reopened.is_chunk_confirmed(&chunk(1)));
    }

    #[test]
    fn an_accepted_session_survives_restart_and_cannot_reenter_as_new() {
        let root = tempfile::tempdir().expect("app-private root is creatable");
        let paths = LanJournalPaths::new(root.path()).expect("paths build");
        {
            let mut journal = LanJournal::open(paths.clone()).expect("journal opens");
            journal
                .accept_session(&session())
                .expect("fresh session is accepted durably");
        }

        let mut reopened = LanJournal::open(paths).expect("journal reopens");
        assert!(
            reopened.has_session(&session()),
            "recovery identifies the accepted session"
        );
        let error = reopened
            .accept_session(&session())
            .expect_err("the same id cannot enter a second fresh session");
        assert_eq!(error.category(), ErrorCategory::Authentication);
        assert_eq!(error.code(), "lan_session_replayed");
    }

    #[test]
    fn batch_plan_generation_and_item_outcomes_survive_restart() {
        let root = tempfile::tempdir().expect("app-private root is creatable");
        let paths = LanJournalPaths::new(root.path()).expect("paths build");
        let plan = plan();
        let item_id = plan.items()[0].item_id().clone();
        {
            let mut journal = LanJournal::open(paths.clone()).expect("journal opens");
            journal
                .store_batch(LanDurableBatch::pending(
                    plan.clone(),
                    session(),
                    DeviceId::derive(&device_key()),
                    name("Sender"),
                ))
                .expect("pending batch stores");
            journal
                .approve_batch(
                    plan.batch_id(),
                    LanApproval::granted(plan.batch_id().clone(), 2_000, 60_000),
                    ApprovedGeneration::capture("workspace-generation-7")
                        .expect("generation captures"),
                )
                .expect("approval stores with its generation");
            journal
                .record_batch_outcome(
                    plan.batch_id(),
                    &item_id,
                    LanItemOutcome::committed("memo-created-1"),
                )
                .expect("item outcome stores");
        }

        let reopened = LanJournal::open(paths).expect("journal reopens");
        let recovered = reopened
            .batch(plan.batch_id())
            .expect("batch recovery state survives");
        assert_eq!(recovered.plan(), &plan);
        assert_eq!(recovered.session_id(), &session());
        assert_eq!(
            recovered
                .approval()
                .expect("approval survives")
                .approved_at_ms(),
            2_000
        );
        assert_eq!(
            recovered
                .approved_generation()
                .expect("generation survives")
                .as_str(),
            "workspace-generation-7"
        );
        assert_eq!(
            recovered.snapshot().outcome(&item_id),
            Some(&LanItemOutcome::committed("memo-created-1"))
        );
    }

    #[test]
    fn a_revoked_peer_stays_revoked_across_a_restart() {
        let root = tempfile::tempdir().expect("app-private root is creatable");
        let paths = LanJournalPaths::new(root.path()).expect("paths build");

        let peer = PeerRecord::paired(device_key(), name("Old Phone"), 1_700_000_000_000);
        let device_id = peer.device_id().clone();
        {
            let mut journal = LanJournal::open(paths.clone()).expect("journal opens");
            journal.store_peer(peer).expect("peer is stored");
            journal
                .revoke_peer(&device_id, 1_700_000_500_000)
                .expect("peer is revoked");
        }

        let reopened = LanJournal::open(paths).expect("journal reopens");
        let restored = reopened
            .peers()
            .get(&device_id)
            .expect("a revoked peer is retained so refusal is explicit");
        assert!(restored.is_revoked());
        assert_eq!(restored.revoked_at_ms(), Some(1_700_000_500_000));
        let error = restored
            .assert_connectable()
            .expect_err("a revoked peer stays unconnectable after a restart");
        assert_eq!(error.code(), "lan_peer_revoked");
    }

    #[test]
    fn revoking_an_unknown_device_is_rejected() {
        let root = tempfile::tempdir().expect("app-private root is creatable");
        let paths = LanJournalPaths::new(root.path()).expect("paths build");
        let mut journal = LanJournal::open(paths).expect("journal opens");

        let unknown = DeviceId::derive(&device_key());
        let error = journal
            .revoke_peer(&unknown, 1_700_000_000_000)
            .expect_err("revoking a device that was never paired is rejected");
        assert_eq!(error.code(), "lan_peer_unknown");
    }

    #[test]
    fn resume_reports_only_unconfirmed_chunk_indices_in_order() {
        let root = tempfile::tempdir().expect("app-private root is creatable");
        let paths = LanJournalPaths::new(root.path()).expect("paths build");
        let mut journal = LanJournal::open(paths).expect("journal opens");

        for index in [0_u32, 1, 4] {
            journal
                .confirm_chunk(&chunk(index))
                .expect("chunk confirmed");
        }

        assert_eq!(
            journal.unconfirmed_chunk_indices(&batch(), 0, ATTACHMENT_SLOT_BODY, 6),
            vec![2, 3, 5],
            "resume must retransmit exactly the chunks that were never confirmed"
        );
    }

    #[test]
    fn confirming_the_same_chunk_twice_is_idempotent() {
        let root = tempfile::tempdir().expect("app-private root is creatable");
        let paths = LanJournalPaths::new(root.path()).expect("paths build");
        let mut journal = LanJournal::open(paths.clone()).expect("journal opens");

        journal.confirm_chunk(&chunk(3)).expect("first confirm");
        journal
            .confirm_chunk(&chunk(3))
            .expect("replaying a confirm is not an error");

        let reopened = LanJournal::open(paths).expect("journal reopens");
        assert_eq!(
            reopened.unconfirmed_chunk_indices(&batch(), 0, ATTACHMENT_SLOT_BODY, 4),
            vec![0, 1, 2],
            "a duplicate confirm must not duplicate the record"
        );
    }

    #[test]
    fn a_damaged_record_fails_closed_instead_of_resetting_to_an_empty_set() {
        let encoded = encode_record(b"peer state").expect("record encodes");
        decode_record(&encoded).expect("an intact record decodes");

        let mut flipped_body = encoded.clone();
        let last = flipped_body.len() - 1;
        flipped_body[last] ^= 0x01;
        let error = decode_record(&flipped_body).expect_err("a flipped body byte fails closed");
        assert_eq!(error.category(), ErrorCategory::Corruption);
        assert_eq!(error.code(), "lan_record_checksum_mismatch");

        let mut bad_magic = encoded.clone();
        bad_magic[0] = b'X';
        assert_eq!(
            decode_record(&bad_magic)
                .expect_err("foreign magic fails closed")
                .code(),
            "lan_record_bad_magic"
        );

        let mut bad_schema = encoded.clone();
        bad_schema[7] = 0xFF;
        assert_eq!(
            decode_record(&bad_schema)
                .expect_err("an unknown schema fails closed")
                .code(),
            "lan_record_unknown_schema"
        );

        for cut in 0..encoded.len() {
            decode_record(&encoded[..cut]).expect_err("a truncated record never decodes");
        }
    }

    #[test]
    fn a_corrupt_journal_file_fails_the_open_rather_than_untrusting_every_peer() {
        let root = tempfile::tempdir().expect("app-private root is creatable");
        let paths = LanJournalPaths::new(root.path()).expect("paths build");
        {
            let mut journal = LanJournal::open(paths.clone()).expect("journal opens");
            journal
                .store_peer(PeerRecord::paired(
                    device_key(),
                    name("Tablet"),
                    1_700_000_000_000,
                ))
                .expect("peer is stored");
        }

        let mut bytes = std::fs::read(paths.peers()).expect("the peer record exists");
        let last = bytes.len() - 1;
        bytes[last] ^= 0xFF;
        std::fs::write(paths.peers(), &bytes).expect("the record is rewritten");

        let error = LanJournal::open(paths)
            .expect_err("a corrupt peer record must fail the open, not silently un-trust peers");
        assert_eq!(error.category(), ErrorCategory::Corruption);
    }

    #[test]
    fn the_trusted_peer_registry_is_bounded() {
        let root = tempfile::tempdir().expect("app-private root is creatable");
        let paths = LanJournalPaths::new(root.path()).expect("paths build");
        let mut journal = LanJournal::open(paths).expect("journal opens");

        for index in 0..lomo_lan::MAX_TRUSTED_PEERS {
            journal
                .store_peer(PeerRecord::paired(
                    device_key(),
                    name(&format!("peer-{index}")),
                    1_700_000_000_000,
                ))
                .expect("pairing up to the ceiling succeeds");
        }

        let error = journal
            .store_peer(PeerRecord::paired(
                device_key(),
                name("one too many"),
                1_700_000_000_000,
            ))
            .expect_err("pairing past the ceiling is rejected");
        assert_eq!(error.category(), ErrorCategory::ResourceLimit);
        assert_eq!(error.code(), "lan_peer_registry_full");
    }

    #[test]
    fn a_record_body_above_the_ceiling_is_rejected() {
        let error = encode_record(&vec![0_u8; MAX_LAN_RECORD_BYTES + 1])
            .expect_err("an oversized record body is rejected");
        assert_eq!(error.category(), ErrorCategory::ResourceLimit);
        assert_eq!(error.code(), "lan_record_too_large");
    }

    #[test]
    fn staged_chunk_bytes_survive_restart_and_reject_a_different_replay() {
        let root = tempfile::tempdir().expect("app-private root is creatable");
        let paths = LanJournalPaths::new(root.path()).expect("paths build");
        {
            let mut journal = LanJournal::open(paths.clone()).expect("journal opens");
            journal
                .stage_chunk(&chunk(0), b"first ")
                .expect("first chunk stages");
            journal
                .stage_chunk(&chunk(1), b"payload")
                .expect("second chunk stages");
            journal
                .stage_chunk(&chunk(0), b"first ")
                .expect("identical retry is idempotent");
            let replay = journal
                .stage_chunk(&chunk(0), b"changed")
                .expect_err("different bytes under one binding fail closed");
            assert_eq!(replay.code(), "lan_chunk_replayed_with_different_bytes");
            journal.confirm_chunk(&chunk(0)).expect("chunk 0 confirms");
            journal.confirm_chunk(&chunk(1)).expect("chunk 1 confirms");
        }

        let reopened = LanJournal::open(paths).expect("journal reopens");
        assert_eq!(
            reopened
                .read_confirmed_payload(&batch(), 0, ATTACHMENT_SLOT_BODY, 2)
                .expect("payload reads"),
            Some(b"first payload".to_vec())
        );
    }
}
