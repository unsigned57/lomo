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
//! - Given a confirmed chunk whose staged file is missing, torn or digest-corrupt, when the
//!   journal reopens, then the coordinate downgrades to retransmittable instead of poisoning the
//!   batch as "confirmed but impossible to complete".
//! - Given a crash mid-append left a torn tail in `chunks.log`, when the journal reopens, then
//!   the valid prefix replays and the torn suffix is ignored instead of failing the open.
//! - Given staged payload files for an unknown or retired batch, when the journal reopens, then
//!   the orphaned bytes are reclaimed and orphaned coordinates dropped.
//! - Given a batch past its terminal anchor plus the anti-replay window, when it is retired,
//!   then its payloads and records are reclaimed and a durable witness blocks id resurrection.
//! - Given accepted session witnesses, when the replay retention window passes or the witness
//!   ceiling fills, then stale witnesses are evicted instead of growing forever.
//!
//! Observable outcomes: reloaded journal contents, revocation state, unconfirmed index lists,
//! `LomoError` code/category, on-disk record bytes.
//!
//! Test Change Justification:
//! - Reason category: behavior contract change (confirmed coordinates are verified against
//!   staged files and owning batch plans at open).
//! - Old behavior/assertion being replaced: restart fixtures confirmed coordinates without a
//!   stored batch plan or staged bytes; those coordinates now downgrade as unverifiable.
//! - Coverage preserved by: fixtures store the owning batch and stage exact-length bytes, so the
//!   same survival assertions still lock confirmed-coordinate durability.
//! - Why this is not fitting the test to the implementation: the invariant is the declared T42
//!   contract — confirmed must mean durable and verifiable bytes exist.
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
        RUNTIME_CHUNK_PLAINTEXT_BYTES, decode_record, encode_record,
    };
    use sha2::{Digest, Sha256};
    use std::path::PathBuf;

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

    /// A one-item plan whose body digest is the real SHA-256 of `body`, so fully confirmed
    /// payloads pass the open-time digest verification.
    fn verified_plan(body: &[u8]) -> LanBatchPlan {
        verified_plan_named("batch-resume", body)
    }

    fn verified_plan_named(batch_id: &str, body: &[u8]) -> LanBatchPlan {
        let batch = LanBatchId::parse(batch_id).expect("fixture batch id is valid");
        let item = LanItemPlan::new(
            &batch,
            0,
            1_700_000_000_000,
            &format!("{:x}", Sha256::digest(body)),
            body.len() as u64,
            "Recovery title",
            Vec::new(),
        )
        .expect("item plan is valid");
        LanBatchPlan::new(batch, vec![item]).expect("batch plan is valid")
    }

    fn store_pending(journal: &mut LanJournal, plan: &LanBatchPlan) {
        journal
            .store_batch(LanDurableBatch::pending(
                plan.clone(),
                session(),
                DeviceId::derive(&device_key()),
                name("Sender"),
            ))
            .expect("pending batch stores");
    }

    fn staged_file(
        paths: &LanJournalPaths,
        batch_id: &str,
        item_index: u16,
        attachment_slot: u16,
        chunk_index: u32,
    ) -> PathBuf {
        paths
            .root()
            .join("payloads")
            .join(batch_id)
            .join(format!("{item_index}-{attachment_slot}"))
            .join(format!("{chunk_index}.chunk"))
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
            let three_chunk_body = vec![0_u8; RUNTIME_CHUNK_PLAINTEXT_BYTES * 2 + 1];
            store_pending(&mut journal, &verified_plan(&three_chunk_body));
            journal
                .stage_chunk(
                    &chunk(0),
                    &three_chunk_body[..RUNTIME_CHUNK_PLAINTEXT_BYTES],
                )
                .expect("chunk 0 stages");
            journal
                .stage_chunk(
                    &chunk(2),
                    &three_chunk_body[RUNTIME_CHUNK_PLAINTEXT_BYTES * 2..],
                )
                .expect("chunk 2 stages");
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
                .accept_session(&session(), 1_700_000_000_000)
                .expect("fresh session is accepted durably");
        }

        let mut reopened = LanJournal::open(paths).expect("journal reopens");
        assert!(
            reopened.has_session(&session()),
            "recovery identifies the accepted session"
        );
        let error = reopened
            .accept_session(&session(), 1_700_000_010_000)
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

        let four_chunk_body = vec![0_u8; RUNTIME_CHUNK_PLAINTEXT_BYTES * 3 + 1];
        store_pending(&mut journal, &verified_plan(&four_chunk_body));
        journal
            .stage_chunk(
                &chunk(3),
                &four_chunk_body[RUNTIME_CHUNK_PLAINTEXT_BYTES * 3..],
            )
            .expect("chunk 3 stages");
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
            store_pending(&mut journal, &verified_plan(b"first payload"));
            journal
                .stage_chunk(&chunk(0), b"first payload")
                .expect("first chunk stages");
            journal
                .stage_chunk(&chunk(0), b"first payload")
                .expect("identical retry is idempotent");
            let replay = journal
                .stage_chunk(&chunk(0), b"first payloaa")
                .expect_err("different bytes under one binding fail closed");
            assert_eq!(replay.code(), "lan_chunk_replayed_with_different_bytes");
            journal.confirm_chunk(&chunk(0)).expect("chunk confirms");
        }

        let mut reopened = LanJournal::open(paths).expect("journal reopens");
        let payload = reopened
            .assemble_confirmed_payload(&batch(), 0, ATTACHMENT_SLOT_BODY, 1)
            .expect("payload assembles")
            .expect("fully confirmed payload is present");
        assert_eq!(
            payload.size_bytes(),
            u64::try_from(b"first payload".len()).expect("byte length fits u64")
        );
        assert_eq!(
            payload.digest(),
            format!("{:x}", Sha256::digest(b"first payload"))
        );
        assert_eq!(
            std::fs::read(payload.path()).expect("assembled payload reads"),
            b"first payload"
        );
    }

    #[test]
    fn a_confirmed_chunk_without_staged_bytes_reopens_as_retransmittable() {
        let root = tempfile::tempdir().expect("app-private root is creatable");
        let paths = LanJournalPaths::new(root.path()).expect("paths build");
        {
            let mut journal = LanJournal::open(paths.clone()).expect("journal opens");
            store_pending(&mut journal, &verified_plan(b"resume me"));
            journal
                .stage_chunk(&chunk(0), b"resume me")
                .expect("chunk stages");
            journal.confirm_chunk(&chunk(0)).expect("chunk confirms");
        }
        std::fs::remove_file(staged_file(
            &paths,
            "batch-resume",
            0,
            ATTACHMENT_SLOT_BODY,
            0,
        ))
        .expect("staged file deletes");

        let mut reopened = LanJournal::open(paths.clone()).expect("journal reopens");
        assert!(
            !reopened.is_chunk_confirmed(&chunk(0)),
            "a confirmed coordinate without durable bytes must downgrade, not poison the batch"
        );
        assert_eq!(
            reopened.unconfirmed_chunk_indices(&batch(), 0, ATTACHMENT_SLOT_BODY, 1),
            vec![0]
        );
        assert_eq!(
            reopened
                .assemble_confirmed_payload(&batch(), 0, ATTACHMENT_SLOT_BODY, 1)
                .expect("a missing confirmed file is retransmittable, not corrupt"),
            None
        );

        let third = LanJournal::open(paths).expect("the downgrade is itself durable");
        assert_eq!(
            third.unconfirmed_chunk_indices(&batch(), 0, ATTACHMENT_SLOT_BODY, 1),
            vec![0],
            "the downgraded coordinate stays downgraded across opens"
        );
    }

    #[test]
    fn a_torn_staged_chunk_reopens_as_retransmittable() {
        let root = tempfile::tempdir().expect("app-private root is creatable");
        let paths = LanJournalPaths::new(root.path()).expect("paths build");
        {
            let mut journal = LanJournal::open(paths.clone()).expect("journal opens");
            store_pending(&mut journal, &verified_plan(b"resume me"));
            journal
                .stage_chunk(&chunk(0), b"resume me")
                .expect("chunk stages");
            journal.confirm_chunk(&chunk(0)).expect("chunk confirms");
        }
        std::fs::write(
            staged_file(&paths, "batch-resume", 0, ATTACHMENT_SLOT_BODY, 0),
            b"torn",
        )
        .expect("staged file is torn to a wrong length");

        let reopened = LanJournal::open(paths).expect("journal reopens");
        assert!(
            !reopened.is_chunk_confirmed(&chunk(0)),
            "a staged file whose length no longer matches the plan must downgrade"
        );
        assert_eq!(
            reopened.unconfirmed_chunk_indices(&batch(), 0, ATTACHMENT_SLOT_BODY, 1),
            vec![0]
        );
    }

    #[test]
    fn a_fully_confirmed_payload_with_corrupted_bytes_reopens_as_retransmittable() {
        let root = tempfile::tempdir().expect("app-private root is creatable");
        let paths = LanJournalPaths::new(root.path()).expect("paths build");
        {
            let mut journal = LanJournal::open(paths.clone()).expect("journal opens");
            store_pending(&mut journal, &verified_plan(b"resume me"));
            journal
                .stage_chunk(&chunk(0), b"resume me")
                .expect("chunk stages");
            journal.confirm_chunk(&chunk(0)).expect("chunk confirms");
        }
        let file = staged_file(&paths, "batch-resume", 0, ATTACHMENT_SLOT_BODY, 0);
        let mut bytes = std::fs::read(&file).expect("staged file exists");
        bytes[0] ^= 0xFF;
        std::fs::write(&file, &bytes).expect("staged file keeps its length but changes a byte");

        let reopened = LanJournal::open(paths).expect("journal reopens");
        assert!(
            !reopened.is_chunk_confirmed(&chunk(0)),
            "a fully confirmed payload whose bytes fail the plan digest must downgrade"
        );
        assert_eq!(
            reopened.unconfirmed_chunk_indices(&batch(), 0, ATTACHMENT_SLOT_BODY, 1),
            vec![0]
        );
    }

    #[test]
    fn orphaned_payload_bytes_and_coordinates_are_reclaimed_at_open() {
        let root = tempfile::tempdir().expect("app-private root is creatable");
        let paths = LanJournalPaths::new(root.path()).expect("paths build");
        {
            let journal = LanJournal::open(paths.clone()).expect("journal opens");
            drop(journal);
        }
        let orphan_dir = paths
            .root()
            .join("payloads")
            .join("batch-orphaned")
            .join("0-65535");
        std::fs::create_dir_all(&orphan_dir).expect("orphan directory creates");
        std::fs::write(orphan_dir.join("0.chunk"), b"orphan").expect("orphan file writes");
        {
            let mut journal = LanJournal::open(paths.clone()).expect("journal reopens");
            journal
                .confirm_chunk(
                    &ChunkBinding::new(&session(), "batch-orphaned", 0, ATTACHMENT_SLOT_BODY, 0)
                        .expect("orphan binding builds"),
                )
                .expect("an orphaned coordinate is journaled");
        }

        let reopened = LanJournal::open(paths.clone()).expect("journal reopens");
        assert!(
            !reopened.is_chunk_confirmed(
                &ChunkBinding::new(&session(), "batch-orphaned", 0, ATTACHMENT_SLOT_BODY, 0)
                    .expect("orphan binding builds")
            ),
            "a confirmed coordinate for an unknown batch cannot survive reconciliation"
        );
        assert!(
            !paths
                .root()
                .join("payloads")
                .join("batch-orphaned")
                .exists(),
            "payload bytes for an unknown batch are reclaimed"
        );
    }

    #[test]
    fn a_retired_batch_leaves_a_witness_and_releases_its_payloads() {
        let root = tempfile::tempdir().expect("app-private root is creatable");
        let paths = LanJournalPaths::new(root.path()).expect("paths build");
        let mut journal = LanJournal::open(paths.clone()).expect("journal opens");
        let sender = DeviceId::derive(&device_key());
        journal
            .store_batch(LanDurableBatch::pending(
                verified_plan(b"retire me"),
                session(),
                sender.clone(),
                name("Sender"),
            ))
            .expect("pending batch stores");
        journal
            .stage_chunk(&chunk(0), b"retire me")
            .expect("chunk stages");
        journal.confirm_chunk(&chunk(0)).expect("chunk confirms");
        journal
            .reject_batch(&batch(), 1_000)
            .expect("rejection stores");
        let rejected_dir = paths.root().join("payloads").join("batch-resume");
        assert!(rejected_dir.exists());

        journal
            .retire_batch(&batch(), 1_000 + lomo_lan::LAN_BATCH_RETIRE_DELAY_MS)
            .expect("a batch past its terminal anchor plus replay window retires");
        assert!(journal.batch(&batch()).is_none());
        assert!(!journal.is_chunk_confirmed(&chunk(0)));
        assert!(
            !rejected_dir.exists(),
            "retirement reclaims the staged payload subtree"
        );
        let error = journal
            .store_batch(LanDurableBatch::pending(
                verified_plan(b"retire me"),
                session(),
                sender.clone(),
                name("Sender"),
            ))
            .expect_err("a retired batch id cannot resurrect from the same sender");
        assert_eq!(error.code(), "lan_batch_retired");

        let mut reopened = LanJournal::open(paths).expect("journal reopens");
        assert!(reopened.batch(&batch()).is_none());
        assert_eq!(
            reopened
                .store_batch(LanDurableBatch::pending(
                    verified_plan(b"retire me"),
                    session(),
                    sender,
                    name("Sender"),
                ))
                .expect_err("the retirement witness survives restart")
                .code(),
            "lan_batch_retired"
        );
    }

    #[test]
    fn retirement_refuses_a_batch_that_is_not_terminal() {
        let root = tempfile::tempdir().expect("app-private root is creatable");
        let paths = LanJournalPaths::new(root.path()).expect("paths build");
        let mut journal = LanJournal::open(paths).expect("journal opens");
        store_pending(&mut journal, &verified_plan(b"keep me"));

        assert_eq!(
            journal
                .retire_batch(&batch(), i64::MAX)
                .expect_err("a pending batch is never reclaimable")
                .code(),
            "lan_batch_not_retirable"
        );
        journal
            .approve_batch(
                &batch(),
                LanApproval::granted(batch(), 1_000, 60_000),
                ApprovedGeneration::capture("generation-1").expect("generation captures"),
            )
            .expect("approval stores");
        assert_eq!(
            journal
                .retire_batch(&batch(), 61_000)
                .expect_err("an approval still inside its window cannot retire")
                .code(),
            "lan_batch_not_retirable"
        );
        assert_eq!(
            journal
                .retire_batch(&batch(), 61_000 + lomo_lan::LAN_BATCH_RETIRE_DELAY_MS - 1)
                .expect_err("the anti-replay window must close before reclamation")
                .code(),
            "lan_batch_not_retirable"
        );
        journal
            .retire_batch(&batch(), 61_000 + lomo_lan::LAN_BATCH_RETIRE_DELAY_MS)
            .expect("an expired approval past the replay window retires");
    }

    #[test]
    fn session_witnesses_expire_past_the_replay_retention_window() {
        let root = tempfile::tempdir().expect("app-private root is creatable");
        let paths = LanJournalPaths::new(root.path()).expect("paths build");
        let mut journal = LanJournal::open(paths).expect("journal opens");
        journal
            .accept_session(&session(), 1_000)
            .expect("session accepted durably");
        assert!(journal.has_session(&session()));

        journal
            .maintain(1_000 + lomo_lan::LAN_SESSION_WITNESS_RETENTION_MS - 1)
            .expect("inside the window the witness stays");
        assert!(journal.has_session(&session()));

        journal
            .maintain(1_000 + lomo_lan::LAN_SESSION_WITNESS_RETENTION_MS)
            .expect("past the replay window the witness retires");
        assert!(
            !journal.has_session(&session()),
            "a witness beyond its retention window is evicted, not kept forever"
        );
    }

    #[test]
    fn maintenance_retires_terminal_batches_and_keeps_live_bytes() {
        let root = tempfile::tempdir().expect("app-private root is creatable");
        let paths = LanJournalPaths::new(root.path()).expect("paths build");
        let mut journal = LanJournal::open(paths.clone()).expect("journal opens");
        let sender = DeviceId::derive(&device_key());
        journal
            .store_batch(LanDurableBatch::pending(
                verified_plan(b"rejected soon"),
                session(),
                sender.clone(),
                name("Sender"),
            ))
            .expect("rejected-soon batch stores");
        journal
            .stage_chunk(&chunk(0), b"rejected soon")
            .expect("chunk stages");
        journal.confirm_chunk(&chunk(0)).expect("chunk confirms");
        journal
            .reject_batch(&batch(), 1_000)
            .expect("rejection stores");

        let live_batch = LanBatchId::parse("batch-live").expect("fixture batch id is valid");
        let live_chunk = ChunkBinding::new(&session(), "batch-live", 0, ATTACHMENT_SLOT_BODY, 0)
            .expect("fixture binding is valid");
        journal
            .store_batch(LanDurableBatch::pending(
                verified_plan_named("batch-live", b"still arriving"),
                session(),
                sender.clone(),
                name("Sender"),
            ))
            .expect("live batch stores");
        journal
            .stage_chunk(&live_chunk, b"still arriving")
            .expect("live chunk stages");
        journal
            .confirm_chunk(&live_chunk)
            .expect("live chunk confirms");
        let live_dir = paths.root().join("payloads").join("batch-live");

        journal
            .maintain(1_000 + lomo_lan::LAN_BATCH_RETIRE_DELAY_MS - 1)
            .expect("inside the anti-replay window nothing retires");
        assert!(journal.batch(&batch()).is_some());

        journal
            .maintain(1_000 + lomo_lan::LAN_BATCH_RETIRE_DELAY_MS)
            .expect("past the window the terminal batch retires");
        assert!(journal.batch(&batch()).is_none());
        assert!(
            !paths.root().join("payloads").join("batch-resume").exists(),
            "retired payloads are reclaimed"
        );
        assert!(
            journal.batch(&live_batch).is_some() && journal.is_chunk_confirmed(&live_chunk),
            "maintenance never reclaims bytes a live batch still needs"
        );
        assert!(live_dir.exists(), "live staged payloads stay put");
        assert_eq!(
            journal
                .store_batch(LanDurableBatch::pending(
                    verified_plan(b"rejected soon"),
                    session(),
                    sender,
                    name("Sender"),
                ))
                .expect_err("the retired witness still blocks resurrection")
                .code(),
            "lan_batch_retired"
        );
    }
    #[test]
    fn a_staged_chunk_must_match_the_planned_length_for_a_known_batch() {
        let temp = tempfile::tempdir().expect("tempdir");
        let paths = LanJournalPaths::new(temp.path()).expect("paths build");
        let mut journal = LanJournal::open(paths).expect("journal opens");
        store_pending(&mut journal, &verified_plan(b"planned bytes"));
        let coordinate = chunk(0);
        assert_eq!(
            journal
                .stage_chunk(&coordinate, b"short")
                .expect_err("a short write must not land under a planned coordinate")
                .code(),
            "lan_chunk_plan_mismatch"
        );
        assert_eq!(
            journal
                .stage_chunk(&coordinate, b"planned bytes and then some")
                .expect_err("an oversized write must not land under a planned coordinate")
                .code(),
            "lan_chunk_plan_mismatch"
        );
        journal
            .stage_chunk(&coordinate, b"planned bytes")
            .expect("the exact planned length stages");
    }

    #[test]
    fn a_torn_confirmation_log_tail_replays_only_its_valid_prefix() {
        let root = tempfile::tempdir().expect("app-private root is creatable");
        let paths = LanJournalPaths::new(root.path()).expect("paths build");
        let body = vec![0_u8; RUNTIME_CHUNK_PLAINTEXT_BYTES + 1];
        {
            let mut journal = LanJournal::open(paths.clone()).expect("journal opens");
            store_pending(&mut journal, &verified_plan(&body));
            journal
                .stage_chunk(&chunk(0), &body[..RUNTIME_CHUNK_PLAINTEXT_BYTES])
                .expect("chunk 0 stages");
            journal
                .stage_chunk(&chunk(1), &body[RUNTIME_CHUNK_PLAINTEXT_BYTES..])
                .expect("chunk 1 stages");
            journal.confirm_chunk(&chunk(0)).expect("chunk 0 confirms");
            journal.confirm_chunk(&chunk(1)).expect("chunk 1 confirms");
        }
        // A crash mid-append leaves a partial entry: the valid prefix must survive and the torn
        // suffix must be ignored, not fail the whole open as corruption.
        let log = paths.confirmed_log();
        let mut bytes = std::fs::read(&log).expect("append log exists");
        bytes.extend_from_slice(&[0xFF, 0xFF, 0x00]);
        std::fs::write(&log, &bytes).expect("torn tail writes");

        let reopened = LanJournal::open(paths).expect("journal reopens past the torn tail");
        assert!(
            reopened.is_chunk_confirmed(&chunk(0)),
            "the valid prefix of the append log replays"
        );
        assert!(
            reopened.is_chunk_confirmed(&chunk(1)),
            "every entry before the torn suffix replays"
        );
        let mut reopened = reopened;
        assert_eq!(
            reopened
                .assemble_confirmed_payload(&batch(), 0, ATTACHMENT_SLOT_BODY, 2)
                .expect("the recovered payload assembles")
                .map(|payload| payload.digest().to_owned()),
            Some(format!("{:x}", Sha256::digest(&body)))
        );
    }
}
