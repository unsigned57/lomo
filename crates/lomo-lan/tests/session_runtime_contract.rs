//! Behavior Contract (Stage-6 P6-09 product session runtime)
//!
//! Capability: paired Rust-owned LAN managers mutually authenticate a fresh X25519 session with
//! external device-key signatures and durably accept its replay identity only after both sides
//! confirm.
//!
//! Scenarios:
//! - Given two paired peers, when a session hello/accept exchange completes, then both managers
//!   expose the same transcript for their Keystore adapter.
//! - Given only one session signature, then neither manager reports an authenticated session.
//! - Given both valid signatures, then both managers report the same authenticated session.
//! - Given the target peer was revoked, when a session begins, then it fails before network I/O.
//! - Given an inbound pairing, session or batch control frame, when Android polls the bounded
//!   runtime inbox, then it receives the Rust-owned IDs/challenges/previews needed for UI actions.
//!
//! Observable outcomes: session challenge bytes, authenticated snapshots and stable error codes.
//! TDD proof: RED because the runtime had pairing lifecycle but no session lifecycle methods.
//! - Given an authenticated session and a batch plan, when prepare and approval cross real sockets,
//!   then the receiver exposes only bounded preview metadata and recovers the generation-bound
//!   approval after restart.
//! - Given another prepared batch, when the receiver rejects it, then the sender observes the
//!   authenticated terminal rejection and the receiver recovers that decision after restart.
//! - Given an approved batch body chunk, when it crosses the authenticated socket, then the sender
//!   receives a durable acknowledgement and the receiver recovers verified body bytes after
//!   restart.
//! - Given two items reference one attachment digest at different slots, when the canonical chunk
//!   travels once, then both items resolve the same durable verified bytes without a second wire
//!   coordinate.
//! - Given a sender process restarts after one receiver-confirmed chunk, when both peers
//!   authenticate a fresh session and re-prepare the same batch, then the sender durably learns
//!   the receiver's confirmed ranges and retransmits only the missing chunk.
//! - Given two approved batches each with one durably confirmed chunk, when the receiver inbox is
//!   read, then both batches stay visible with their own confirmed byte counts, and a wire-level
//!   replay of a confirmed chunk never double-counts progress.
//! - Given a fully confirmed payload whose staged chunk bytes were corrupted on disk, when the
//!   receiver authorizes the commit, then the item is refused as incomplete and the corrupted
//!   coordinate downgrades to retransmittable.
//!   Excludes: store apply, Android Keystore and Compose.

#![deny(unsafe_code)]

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests use fail-fast cryptographic and filesystem fixtures"
)]
mod tests {
    use std::thread;

    use aws_lc_rs::encoding::AsBigEndian;
    use aws_lc_rs::rand::SystemRandom;
    use aws_lc_rs::signature::{ECDSA_P256_SHA256_ASN1_SIGNING, EcdsaKeyPair, KeyPair};
    use lomo_lan::{
        ATTACHMENT_SLOT_BODY, ApprovedGeneration, CHUNK_PLAINTEXT_BYTES, ChunkBinding, DeviceId,
        DevicePublicKey, DiscoveredPeerEndpoint, DisplayName, LAN_PROTOCOL_VERSION,
        LanAttachmentRef, LanBatchId, LanBatchPlan, LanBindCandidate, LanItemPlan,
        LanNetworkSnapshot, LanOutgoingBatchDrive, LanReceivedBatchDecision,
        LanReceivedItemOutcome, LanServiceManager, LanSessionPhase,
    };
    use sha2::{Digest, Sha256};

    const FIRST_BODY: &[u8] = b"![shared](media/shared.png)";
    const SECOND_BODY: &[u8] = b"![same bytes](attachments/copy.png)";
    const SHARED_ATTACHMENT: &[u8] = b"one durable shared attachment";

    struct TestIdentity {
        key: EcdsaKeyPair,
        public: DevicePublicKey,
        rng: SystemRandom,
    }

    impl TestIdentity {
        fn generate() -> Self {
            let key = EcdsaKeyPair::generate(&ECDSA_P256_SHA256_ASN1_SIGNING)
                .expect("P-256 identity generates");
            let encoded: aws_lc_rs::encoding::EcPublicKeyUncompressedBin<'_> =
                key.public_key().as_be_bytes().expect("public key exports");
            let public = DevicePublicKey::parse(encoded.as_ref()).expect("public key parses");
            Self {
                key,
                public,
                rng: SystemRandom::new(),
            }
        }

        fn sign(&self, transcript: &[u8]) -> Vec<u8> {
            self.key
                .sign(&self.rng, transcript)
                .expect("device key signs")
                .as_ref()
                .to_vec()
        }
    }

    fn manager(identity: &TestIdentity, name: &str) -> (tempfile::TempDir, LanServiceManager) {
        let root = tempfile::tempdir().expect("app-private root exists");
        let manager = reopen_manager(root.path(), identity, name);
        (root, manager)
    }

    fn reopen_manager(
        root: &std::path::Path,
        identity: &TestIdentity,
        name: &str,
    ) -> LanServiceManager {
        let mut manager = LanServiceManager::open(root).expect("runtime opens");
        manager
            .configure_identity(
                identity.public.clone(),
                DisplayName::parse(name).expect("name parses"),
            )
            .expect("identity configures");
        manager
            .update_network(
                LanNetworkSnapshot::new(
                    1,
                    true,
                    vec![LanBindCandidate::parse("127.0.0.1", 0).expect("candidate")],
                )
                .expect("network snapshot"),
            )
            .expect("network publishes");
        manager.start().expect("listener starts");
        manager
    }

    fn endpoint(
        manager: &LanServiceManager,
        identity: &TestIdentity,
        name: &str,
    ) -> DiscoveredPeerEndpoint {
        let address = manager
            .snapshot()
            .listen_address()
            .expect("listener address")
            .parse::<SocketAddr>()
            .expect("socket address");
        DiscoveredPeerEndpoint::parse(
            DeviceId::derive(&identity.public).as_str(),
            name,
            &address.ip().to_string(),
            address.port(),
            LAN_PROTOCOL_VERSION,
        )
        .expect("endpoint parses")
    }

    fn pair(
        phone_runtime: &mut LanServiceManager,
        tablet_runtime: &mut LanServiceManager,
        phone: &TestIdentity,
        tablet: &TestIdentity,
    ) {
        let tablet_endpoint = endpoint(tablet_runtime, tablet, "Tablet");
        let phone_challenge = thread::scope(|scope| {
            let responder = scope.spawn(|| tablet_runtime.poll_listener(1_000));
            let challenge = phone_runtime
                .begin_pairing(&tablet_endpoint, 1_000, 60_000)
                .expect("pairing hello exchanges");
            responder
                .join()
                .expect("responder joins")
                .expect("pair hello handles");
            challenge
        });
        let tablet_challenge = tablet_runtime
            .pairing_challenge(phone_challenge.pairing_id())
            .expect("responder challenge exists");
        let pairing_inbox = tablet_runtime.inbox(1_001).expect("pairing inbox builds");
        assert_eq!(
            pairing_inbox.pairing_challenges(),
            std::slice::from_ref(&tablet_challenge)
        );

        thread::scope(|scope| {
            let receiver = scope.spawn(|| tablet_runtime.poll_listener(2_000));
            phone_runtime
                .confirm_pairing(
                    phone_challenge.pairing_id(),
                    &phone.sign(phone_challenge.transcript_to_sign()),
                    2_000,
                )
                .expect("phone confirms");
            receiver
                .join()
                .expect("receiver joins")
                .expect("phone confirm handles");
        });
        thread::scope(|scope| {
            let receiver = scope.spawn(|| phone_runtime.poll_listener(3_000));
            tablet_runtime
                .confirm_pairing(
                    tablet_challenge.pairing_id(),
                    &tablet.sign(tablet_challenge.transcript_to_sign()),
                    3_000,
                )
                .expect("tablet confirms");
            receiver
                .join()
                .expect("receiver joins")
                .expect("tablet confirm handles");
        });
    }

    fn authenticate_session(
        phone_runtime: &mut LanServiceManager,
        tablet_runtime: &mut LanServiceManager,
        phone: &TestIdentity,
        tablet: &TestIdentity,
        now_ms: i64,
    ) -> (lomo_lan::LanSessionChallenge, lomo_lan::LanSessionChallenge) {
        let tablet_endpoint = endpoint(tablet_runtime, tablet, "Tablet");
        let phone_challenge = thread::scope(|scope| {
            let responder = scope.spawn(|| tablet_runtime.poll_listener(now_ms));
            let challenge = phone_runtime
                .begin_session(&tablet_endpoint, now_ms, 60_000)
                .expect("session hello exchanges");
            responder
                .join()
                .expect("responder joins")
                .expect("session hello handles");
            challenge
        });
        let tablet_challenge = tablet_runtime
            .inbox(now_ms + 1)
            .expect("responder inbox builds")
            .session_challenges()
            .iter()
            .find(|challenge| challenge.session_id() == phone_challenge.session_id())
            .cloned()
            .expect("responder challenge exists");
        thread::scope(|scope| {
            let receiver = scope.spawn(|| tablet_runtime.poll_listener(now_ms + 1));
            phone_runtime
                .confirm_session(
                    phone_challenge.session_id(),
                    &phone.sign(phone_challenge.transcript_to_sign()),
                    now_ms + 1,
                )
                .expect("phone confirms session");
            receiver
                .join()
                .expect("receiver joins")
                .expect("phone session confirm handles");
        });
        thread::scope(|scope| {
            let receiver = scope.spawn(|| phone_runtime.poll_listener(now_ms + 2));
            tablet_runtime
                .confirm_session(
                    tablet_challenge.session_id(),
                    &tablet.sign(tablet_challenge.transcript_to_sign()),
                    now_ms + 2,
                )
                .expect("tablet confirms session");
            receiver
                .join()
                .expect("receiver joins")
                .expect("tablet session confirm handles");
        });
        (phone_challenge, tablet_challenge)
    }

    fn exercise_batch_control(
        phone_runtime: &mut LanServiceManager,
        tablet_runtime: &mut LanServiceManager,
        phone_challenge: &lomo_lan::LanSessionChallenge,
        tablet_challenge: &lomo_lan::LanSessionChallenge,
        phone_root: &tempfile::TempDir,
        tablet_root: &tempfile::TempDir,
    ) {
        let batch_id = LanBatchId::parse("batch-runtime-control").expect("batch id parses");
        let plan = shared_attachment_batch(&batch_id);
        thread::scope(|scope| {
            let receiver = scope.spawn(|| tablet_runtime.poll_listener(20_000));
            phone_runtime
                .prepare_batch(phone_challenge.session_id(), plan, 20_000)
                .expect("authenticated prepare sends");
            receiver
                .join()
                .expect("receiver joins")
                .expect("prepare handles");
        });
        let outgoing = phone_runtime.inbox(20_001).expect("outgoing inbox builds");
        assert_eq!(
            outgoing
                .outgoing_batches()
                .first()
                .expect("outgoing batch exists")
                .drive(),
            LanOutgoingBatchDrive::AwaitingDecision
        );
        assert_prepared_batch_needs_rebind_after_restart(phone_root, &batch_id);
        let inbox = tablet_runtime.inbox(20_001).expect("batch inbox builds");
        let pending_batch = inbox
            .pending_batches()
            .first()
            .expect("received batch enters the bounded inbox");
        assert_eq!(pending_batch.session_id(), tablet_challenge.session_id());
        let pending_recovery = inbox
            .batch_recoveries()
            .first()
            .expect("pending batch exposes durable recovery state");
        assert_eq!(pending_recovery.session_id(), tablet_challenge.session_id());
        assert_eq!(pending_recovery.preview(), pending_batch.preview());
        assert_eq!(
            pending_recovery.decision(),
            LanReceivedBatchDecision::Pending
        );
        assert_eq!(pending_recovery.items().len(), 2);
        assert!(
            pending_recovery
                .items()
                .iter()
                .all(|item| matches!(item.outcome(), LanReceivedItemOutcome::Pending))
        );
        let preview = pending_recovery.preview();
        assert_eq!(preview.item_count(), 2);
        assert_eq!(preview.attachment_count(), 1);
        assert_eq!(
            preview.total_bytes(),
            (FIRST_BODY.len() + SECOND_BODY.len() + SHARED_ATTACHMENT.len()) as u64
        );
        assert_eq!(
            preview.titles(),
            &["Bounded preview title", "Second preview"]
        );

        approve_batch_and_assert_sender_recovery(
            phone_runtime,
            tablet_runtime,
            tablet_challenge,
            phone_root,
            &batch_id,
        );

        exercise_body_transfer(
            phone_runtime,
            tablet_runtime,
            phone_challenge,
            tablet_root,
            &batch_id,
        );
        exercise_batch_rejection(
            phone_runtime,
            tablet_runtime,
            phone_challenge,
            tablet_challenge,
            phone_root,
            tablet_root,
        );
    }

    fn assert_prepared_batch_needs_rebind_after_restart(
        phone_root: &tempfile::TempDir,
        batch_id: &LanBatchId,
    ) {
        let mut recovered_sender =
            LanServiceManager::open(phone_root.path()).expect("sender runtime reopens");
        let recovered_batch = recovered_sender
            .inbox(20_001)
            .expect("sender recovery inbox builds")
            .outgoing_batches()
            .first()
            .expect("a process restart must not erase a prepared outgoing batch")
            .clone();
        assert_eq!(recovered_batch.batch_id(), batch_id);
        assert_eq!(
            recovered_batch.drive(),
            LanOutgoingBatchDrive::NeedsRebind,
            "a prepared batch whose session died must drive rebinding, not decision polling"
        );
    }

    fn approve_batch_and_assert_sender_recovery(
        phone_runtime: &mut LanServiceManager,
        tablet_runtime: &mut LanServiceManager,
        tablet_challenge: &lomo_lan::LanSessionChallenge,
        phone_root: &tempfile::TempDir,
        batch_id: &LanBatchId,
    ) {
        thread::scope(|scope| {
            let receiver = scope.spawn(|| phone_runtime.poll_listener(21_000));
            tablet_runtime
                .approve_batch(
                    tablet_challenge.session_id(),
                    batch_id,
                    ApprovedGeneration::capture("workspace-generation-9")
                        .expect("generation captures"),
                    21_000,
                    60_000,
                )
                .expect("approval sends");
            receiver
                .join()
                .expect("receiver joins")
                .expect("approval handles");
        });
        assert!(phone_runtime.outgoing_batch_is_approved(batch_id));
        let approved_inbox = tablet_runtime.inbox(21_001).expect("approved inbox builds");
        let approved_recovery = approved_inbox
            .batch_recoveries()
            .first()
            .expect("approved batch remains recoverable");
        assert_eq!(
            approved_recovery.decision(),
            LanReceivedBatchDecision::Approved
        );
        assert_eq!(
            phone_runtime
                .inbox(21_001)
                .expect("approved inbox builds")
                .outgoing_batches()
                .first()
                .expect("approved outgoing batch exists")
                .drive(),
            LanOutgoingBatchDrive::Sendable
        );
        assert_eq!(
            LanServiceManager::open(phone_root.path())
                .expect("approved sender runtime reopens")
                .inbox(21_001)
                .expect("approved sender recovery inbox builds")
                .outgoing_batches()
                .first()
                .expect("approved outgoing batch survives restart")
                .drive(),
            LanOutgoingBatchDrive::NeedsRebind,
            "an approved batch survives restart but must rebind before sending"
        );
    }

    fn shared_attachment_batch(batch_id: &LanBatchId) -> LanBatchPlan {
        let attachment_digest = format!("{:x}", Sha256::digest(SHARED_ATTACHMENT));
        LanBatchPlan::new(
            batch_id.clone(),
            vec![
                LanItemPlan::new(
                    batch_id,
                    0,
                    1_700_000_000_000,
                    &format!("{:x}", Sha256::digest(FIRST_BODY)),
                    FIRST_BODY.len() as u64,
                    "Bounded preview title",
                    vec![
                        LanAttachmentRef::new(
                            0,
                            "media/shared.png",
                            "shared.png",
                            &attachment_digest,
                            SHARED_ATTACHMENT.len() as u64,
                        )
                        .expect("first shared reference builds"),
                    ],
                )
                .expect("item plan builds"),
                LanItemPlan::new(
                    batch_id,
                    1,
                    1_700_000_000_001,
                    &format!("{:x}", Sha256::digest(SECOND_BODY)),
                    SECOND_BODY.len() as u64,
                    "Second preview",
                    vec![
                        LanAttachmentRef::new(
                            7,
                            "attachments/copy.png",
                            "copy.png",
                            &attachment_digest,
                            SHARED_ATTACHMENT.len() as u64,
                        )
                        .expect("second shared reference builds"),
                    ],
                )
                .expect("second item plan builds"),
            ],
        )
        .expect("batch plan builds")
    }

    fn exercise_body_transfer(
        phone_runtime: &mut LanServiceManager,
        tablet_runtime: &mut LanServiceManager,
        phone_challenge: &lomo_lan::LanSessionChallenge,
        tablet_root: &tempfile::TempDir,
        batch_id: &LanBatchId,
    ) {
        assert_shared_transfer_ranges(phone_runtime, tablet_runtime, phone_challenge, batch_id);
        send_payload_chunk(
            phone_runtime,
            tablet_runtime,
            phone_challenge,
            batch_id,
            (0, ATTACHMENT_SLOT_BODY, 0),
            FIRST_BODY,
        );
        send_payload_chunk(
            phone_runtime,
            tablet_runtime,
            phone_challenge,
            batch_id,
            (1, ATTACHMENT_SLOT_BODY, 0),
            SECOND_BODY,
        );
        send_payload_chunk(
            phone_runtime,
            tablet_runtime,
            phone_challenge,
            batch_id,
            (0, 0, 0),
            SHARED_ATTACHMENT,
        );
        assert!(
            tablet_runtime
                .unconfirmed_batch_chunks(batch_id, 0, ATTACHMENT_SLOT_BODY)
                .expect("resume range resolves")
                .is_empty()
        );
        assert!(
            tablet_runtime
                .unconfirmed_batch_chunks(batch_id, 1, 7)
                .expect("shared attachment alias resolves")
                .is_empty(),
            "one canonical transfer confirms every reference to the shared digest"
        );
        let committable = tablet_runtime.inbox(22_001).expect("commit inbox builds");
        assert_eq!(committable.committable_items().len(), 2);
        assert_eq!(
            committable
                .committable_items()
                .first()
                .expect("first committable item exists")
                .batch_id(),
            batch_id
        );
        assert_eq!(
            committable
                .committable_items()
                .first()
                .expect("first committable item exists")
                .item_index(),
            0
        );
        assert_eq!(
            committable
                .committable_items()
                .get(1)
                .expect("second committable item exists")
                .item_index(),
            1
        );
        verify_recovered_transfer(tablet_root, batch_id);
    }

    fn assert_shared_transfer_ranges(
        phone_runtime: &mut LanServiceManager,
        tablet_runtime: &LanServiceManager,
        phone_challenge: &lomo_lan::LanSessionChallenge,
        batch_id: &LanBatchId,
    ) {
        assert_eq!(
            tablet_runtime
                .unconfirmed_batch_chunks(batch_id, 0, ATTACHMENT_SLOT_BODY)
                .expect("resume range resolves"),
            vec![0]
        );
        assert_eq!(
            tablet_runtime
                .unconfirmed_batch_chunks(batch_id, 0, 0)
                .expect("canonical attachment range resolves"),
            vec![0]
        );
        assert_eq!(
            tablet_runtime
                .unconfirmed_batch_chunks(batch_id, 1, 7)
                .expect("shared attachment alias resolves canonical range"),
            vec![0]
        );
        let noncanonical = phone_runtime
            .send_batch_chunk(
                &LanConnectionPool::default(),
                &ChunkBinding::new(phone_challenge.session_id(), batch_id.as_str(), 1, 7, 0)
                    .expect("noncanonical binding builds"),
                SHARED_ATTACHMENT,
                22_000,
            )
            .expect_err("shared attachment cannot travel at a second coordinate");
        assert_eq!(
            noncanonical.code(),
            "lan_attachment_transfer_coordinate_not_canonical"
        );
    }

    fn send_payload_chunk(
        phone_runtime: &mut LanServiceManager,
        tablet_runtime: &mut LanServiceManager,
        phone_challenge: &lomo_lan::LanSessionChallenge,
        batch_id: &LanBatchId,
        coordinate: (u16, u16, u32),
        payload: &[u8],
    ) {
        let (item_index, attachment_slot, chunk_index) = coordinate;
        let binding = ChunkBinding::new(
            phone_challenge.session_id(),
            batch_id.as_str(),
            item_index,
            attachment_slot,
            chunk_index,
        )
        .expect("payload binding builds");
        let pool = LanConnectionPool::default();
        thread::scope(|scope| {
            let receiver = scope.spawn(|| tablet_runtime.poll_listener(22_000));
            phone_runtime
                .send_batch_chunk(&pool, &binding, payload, 22_000)
                .expect("approved payload chunk sends and acknowledges");
            receiver
                .join()
                .expect("receiver joins")
                .expect("payload chunk handles");
        });
    }

    fn exchange_hello(
        phone_runtime: &mut LanServiceManager,
        tablet_runtime: &mut LanServiceManager,
        tablet_endpoint: &DiscoveredPeerEndpoint,
        now_ms: i64,
    ) -> Result<lomo_lan::LanPairingChallenge, lomo_core::LomoError> {
        thread::scope(|scope| {
            let responder = scope.spawn(|| tablet_runtime.poll_listener(now_ms));
            let outcome = phone_runtime.begin_pairing(tablet_endpoint, now_ms, 120_000);
            responder
                .join()
                .expect("responder joins")
                .expect("a refused hello is still a handled connection");
            outcome
        })
    }

    fn verify_recovered_transfer(tablet_root: &tempfile::TempDir, batch_id: &LanBatchId) {
        let mut recovered = LanServiceManager::open(tablet_root.path()).expect("runtime reopens");
        let first_item_id = {
            let batch = recovered
                .batch_recovery(batch_id)
                .expect("batch recovery survives restart");
            batch
                .approval()
                .expect("approval survives")
                .assert_valid_at(22_000)
                .expect("approval remains valid");
            assert_eq!(
                batch
                    .approved_generation()
                    .expect("generation survives")
                    .as_str(),
                "workspace-generation-9"
            );
            batch
                .plan()
                .items()
                .first()
                .expect("first item plan exists")
                .item_id()
                .clone()
        };
        assert_eq!(
            recovered
                .inbox(22_001)
                .expect("recovered inbox builds")
                .committable_items()
                .len(),
            2,
            "durable confirmed ranges rebuild the commit work queue"
        );
        let body_artifact = recovered
            .received_payload_artifact(batch_id, 0, ATTACHMENT_SLOT_BODY)
            .expect("received body validates")
            .expect("fully confirmed body assembles");
        assert_eq!(
            std::fs::read(body_artifact.path()).expect("staged body reads"),
            FIRST_BODY
        );
        let shared_artifact = recovered
            .received_payload_artifact(batch_id, 1, 7)
            .expect("shared attachment alias validates")
            .expect("fully confirmed attachment assembles");
        assert_eq!(
            std::fs::read(shared_artifact.path()).expect("staged attachment reads"),
            SHARED_ATTACHMENT
        );
        let command = recovered
            .authorize_received_item_create(batch_id, 0, "workspace-generation-9", 22_000)
            .expect("recovered item authorization validates")
            .expect("pending item yields a received create command");
        assert_eq!(command.item_id(), &first_item_id);
        assert_eq!(command.content(), String::from_utf8_lossy(FIRST_BODY));
        let first_attachment = command
            .attachments()
            .first()
            .expect("first authorized attachment exists");
        assert_eq!(first_attachment.name(), "shared.png");
        assert_eq!(
            std::fs::read(first_attachment.payload_path())
                .expect("authorized attachment payload reads"),
            SHARED_ATTACHMENT
        );

        let second = recovered
            .authorize_received_item_create(batch_id, 1, "workspace-generation-9", 22_000)
            .expect("second item authorization validates")
            .expect("second pending item yields a received create command");
        assert_eq!(second.content(), String::from_utf8_lossy(SECOND_BODY));
        let second_attachment = second
            .attachments()
            .first()
            .expect("second authorized attachment exists");
        assert_eq!(second_attachment.source_reference(), "attachments/copy.png");
        assert_eq!(
            second_attachment.name(),
            "shared.png",
            "the canonical transfer name is shared across item references"
        );
        assert_eq!(
            std::fs::read(second_attachment.payload_path())
                .expect("second authorized attachment payload reads"),
            SHARED_ATTACHMENT
        );

        recovered
            .record_received_item_committed(batch_id, command.item_id(), "memo-received-1")
            .expect("committed result persists");
        assert_committed_item_survives_restart(tablet_root, batch_id);
    }

    fn assert_committed_item_survives_restart(
        tablet_root: &tempfile::TempDir,
        batch_id: &LanBatchId,
    ) {
        let mut reopened =
            LanServiceManager::open(tablet_root.path()).expect("runtime reopens again");
        assert!(
            reopened
                .authorize_received_item_create(batch_id, 0, "workspace-generation-9", 22_000)
                .expect("committed replay remains valid")
                .is_none(),
            "committed item replay must not produce a second store command"
        );
        let recovery_inbox = reopened
            .inbox(22_001)
            .expect("committed recovery inbox builds");
        let recovery = recovery_inbox
            .batch_recoveries()
            .iter()
            .find(|recovery| recovery.preview().batch_id() == batch_id)
            .expect("committed batch recovery survives restart");
        assert_eq!(recovery.decision(), LanReceivedBatchDecision::Approved);
        assert!(matches!(
            recovery.items().first().expect("first item recovery exists").outcome(),
            LanReceivedItemOutcome::Committed { memo_id } if memo_id == "memo-received-1"
        ));
        assert!(matches!(
            recovery
                .items()
                .get(1)
                .expect("second item recovery exists")
                .outcome(),
            LanReceivedItemOutcome::Pending
        ));
    }

    fn exercise_batch_rejection(
        phone_runtime: &mut LanServiceManager,
        tablet_runtime: &mut LanServiceManager,
        phone_challenge: &lomo_lan::LanSessionChallenge,
        tablet_challenge: &lomo_lan::LanSessionChallenge,
        phone_root: &tempfile::TempDir,
        tablet_root: &tempfile::TempDir,
    ) {
        let rejected_id = LanBatchId::parse("batch-runtime-rejected").expect("batch id parses");
        let rejected_plan = LanBatchPlan::new(
            rejected_id.clone(),
            vec![
                LanItemPlan::new(
                    &rejected_id,
                    0,
                    1_700_000_000_001,
                    &"1".repeat(64),
                    4,
                    "Rejected preview",
                    Vec::new(),
                )
                .expect("item plan builds"),
            ],
        )
        .expect("batch plan builds");
        thread::scope(|scope| {
            let receiver = scope.spawn(|| tablet_runtime.poll_listener(23_000));
            phone_runtime
                .prepare_batch(phone_challenge.session_id(), rejected_plan, 23_000)
                .expect("second prepare sends");
            receiver
                .join()
                .expect("receiver joins")
                .expect("second prepare handles");
        });
        thread::scope(|scope| {
            let receiver = scope.spawn(|| phone_runtime.poll_listener(24_000));
            tablet_runtime
                .reject_batch(tablet_challenge.session_id(), &rejected_id, 24_000)
                .expect("rejection sends");
            receiver
                .join()
                .expect("receiver joins")
                .expect("rejection handles");
        });
        assert!(phone_runtime.outgoing_batch_is_rejected(&rejected_id));
        assert_eq!(
            phone_runtime
                .inbox(24_001)
                .expect("rejected inbox builds")
                .outgoing_batches()
                .iter()
                .find(|batch| batch.batch_id() == &rejected_id)
                .expect("rejected outgoing batch remains observable")
                .drive(),
            LanOutgoingBatchDrive::Rejected
        );
        assert_eq!(
            LanServiceManager::open(phone_root.path())
                .expect("rejected sender runtime reopens")
                .inbox(24_001)
                .expect("rejected sender recovery inbox builds")
                .outgoing_batches()
                .iter()
                .find(|batch| batch.batch_id() == &rejected_id)
                .expect("rejected outgoing batch survives sender restart")
                .drive(),
            LanOutgoingBatchDrive::Rejected
        );
        let mut recovered = LanServiceManager::open(tablet_root.path()).expect("runtime reopens");
        assert!(matches!(
            recovered
                .batch_recovery(&rejected_id)
                .expect("rejected batch survives")
                .decision(),
            lomo_lan::LanBatchDecision::Rejected {
                rejected_at_ms: 24_000
            }
        ));
        let recovered_inbox = recovered
            .inbox(24_001)
            .expect("rejected recovery inbox builds");
        let recovery = recovered_inbox
            .batch_recoveries()
            .iter()
            .find(|recovery| recovery.preview().batch_id() == &rejected_id)
            .expect("rejected recovery is queryable");
        assert_eq!(recovery.decision(), LanReceivedBatchDecision::Rejected);
        assert!(matches!(
            recovery
                .items()
                .first()
                .expect("rejected item recovery exists")
                .outcome(),
            LanReceivedItemOutcome::Pending
        ));
    }

    fn resume_plan(batch_id: &LanBatchId, body: &[u8]) -> LanBatchPlan {
        LanBatchPlan::new(
            batch_id.clone(),
            vec![
                LanItemPlan::new(
                    batch_id,
                    0,
                    1_700_000_000_002,
                    &format!("{:x}", Sha256::digest(body)),
                    body.len() as u64,
                    "Restart resume",
                    Vec::new(),
                )
                .expect("resume item plan builds"),
            ],
        )
        .expect("resume batch plan builds")
    }

    fn prepare_and_approve_resume_batch(
        phone_runtime: &mut LanServiceManager,
        tablet_runtime: &mut LanServiceManager,
        phone_session: &lomo_lan::LanSessionChallenge,
        tablet_session: &lomo_lan::LanSessionChallenge,
        batch_id: &LanBatchId,
        plan: LanBatchPlan,
    ) {
        thread::scope(|scope| {
            let receiver = scope.spawn(|| tablet_runtime.poll_listener(20_000));
            phone_runtime
                .prepare_batch(phone_session.session_id(), plan, 20_000)
                .expect("initial prepare sends");
            receiver
                .join()
                .expect("receiver joins")
                .expect("prepare handles");
        });
        thread::scope(|scope| {
            let receiver = scope.spawn(|| phone_runtime.poll_listener(20_001));
            tablet_runtime
                .approve_batch(
                    tablet_session.session_id(),
                    batch_id,
                    ApprovedGeneration::capture("workspace-generation-resume")
                        .expect("generation captures"),
                    20_001,
                    60_000,
                )
                .expect("approval sends");
            receiver
                .join()
                .expect("receiver joins")
                .expect("approval handles");
        });
    }

    #[test]
    fn a_new_authenticated_session_recovers_only_receiver_missing_chunks() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (phone_root, mut phone_runtime) = manager(&phone, "Phone");
        let (_tablet_root, mut tablet_runtime) = manager(&tablet, "Tablet");
        pair(&mut phone_runtime, &mut tablet_runtime, &phone, &tablet);
        let (first_phone_session, first_tablet_session) = authenticate_session(
            &mut phone_runtime,
            &mut tablet_runtime,
            &phone,
            &tablet,
            10_000,
        );
        let batch_id = LanBatchId::parse("batch-process-restart-resume").expect("batch id parses");
        let first_chunk_len = CHUNK_PLAINTEXT_BYTES - 128;
        let body = vec![b'x'; first_chunk_len + 23];
        let plan = resume_plan(&batch_id, &body);

        prepare_and_approve_resume_batch(
            &mut phone_runtime,
            &mut tablet_runtime,
            &first_phone_session,
            &first_tablet_session,
            &batch_id,
            plan.clone(),
        );
        send_payload_chunk(
            &mut phone_runtime,
            &mut tablet_runtime,
            &first_phone_session,
            &batch_id,
            (0, ATTACHMENT_SLOT_BODY, 0),
            body.get(..first_chunk_len).expect("first chunk exists"),
        );
        assert_eq!(
            tablet_runtime
                .unconfirmed_batch_chunks(&batch_id, 0, ATTACHMENT_SLOT_BODY)
                .expect("receiver resume range resolves"),
            vec![1]
        );

        drop(phone_runtime);
        let mut phone_runtime = reopen_manager(phone_root.path(), &phone, "Phone");
        let (second_phone_session, _second_tablet_session) = authenticate_session(
            &mut phone_runtime,
            &mut tablet_runtime,
            &phone,
            &tablet,
            30_000,
        );
        thread::scope(|scope| {
            let receiver = scope.spawn(|| tablet_runtime.poll_listener(30_010));
            phone_runtime
                .prepare_batch(second_phone_session.session_id(), plan, 30_010)
                .expect("resume prepare exchanges durable status");
            receiver
                .join()
                .expect("receiver joins")
                .expect("resume prepare handles");
        });
        assert_eq!(
            phone_runtime
                .unconfirmed_batch_chunks(&batch_id, 0, ATTACHMENT_SLOT_BODY)
                .expect("sender durable resume range resolves"),
            vec![1],
            "the new session must not retransmit the receiver-confirmed first chunk"
        );
        assert_eq!(
            LanServiceManager::open(phone_root.path())
                .expect("sender journal reopens after status")
                .unconfirmed_batch_chunks(&batch_id, 0, ATTACHMENT_SLOT_BODY)
                .expect("sender durable status survives another restart"),
            vec![1]
        );
        send_payload_chunk(
            &mut phone_runtime,
            &mut tablet_runtime,
            &second_phone_session,
            &batch_id,
            (0, ATTACHMENT_SLOT_BODY, 1),
            body.get(first_chunk_len..).expect("second chunk exists"),
        );
        let resumed_artifact = tablet_runtime
            .received_payload_artifact(&batch_id, 0, ATTACHMENT_SLOT_BODY)
            .expect("resumed payload validates")
            .expect("resumed payload assembles");
        assert_eq!(
            std::fs::read(resumed_artifact.path()).expect("resumed staged payload reads"),
            body
        );
    }

    #[test]
    fn both_device_signatures_are_required_before_a_session_is_authenticated() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (phone_root, mut phone_runtime) = manager(&phone, "Phone");
        let (tablet_root, mut tablet_runtime) = manager(&tablet, "Tablet");
        pair(&mut phone_runtime, &mut tablet_runtime, &phone, &tablet);
        let tablet_endpoint = endpoint(&tablet_runtime, &tablet, "Tablet");

        let phone_challenge = thread::scope(|scope| {
            let responder = scope.spawn(|| tablet_runtime.poll_listener(10_000));
            let challenge = phone_runtime
                .begin_session(&tablet_endpoint, 10_000, 60_000)
                .expect("session hello exchanges");
            responder
                .join()
                .expect("responder joins")
                .expect("session hello handles");
            challenge
        });
        let session_inbox = tablet_runtime.inbox(11_001).expect("session inbox builds");
        let tablet_challenge = session_inbox
            .session_challenges()
            .first()
            .expect("responder challenge exists")
            .clone();
        assert_eq!(session_inbox.session_challenges().len(), 1);
        assert_eq!(tablet_challenge.session_id(), phone_challenge.session_id());
        assert_eq!(
            phone_challenge.transcript_to_sign(),
            tablet_challenge.transcript_to_sign()
        );

        thread::scope(|scope| {
            let receiver = scope.spawn(|| tablet_runtime.poll_listener(11_000));
            phone_runtime
                .confirm_session(
                    phone_challenge.session_id(),
                    &phone.sign(phone_challenge.transcript_to_sign()),
                    11_000,
                )
                .expect("phone confirms session");
            receiver
                .join()
                .expect("receiver joins")
                .expect("phone session confirm handles");
        });
        assert!(
            phone_runtime
                .session_snapshot(phone_challenge.session_id())
                .is_none()
        );
        assert!(
            tablet_runtime
                .session_snapshot(phone_challenge.session_id())
                .is_none()
        );

        thread::scope(|scope| {
            let receiver = scope.spawn(|| phone_runtime.poll_listener(12_000));
            tablet_runtime
                .confirm_session(
                    tablet_challenge.session_id(),
                    &tablet.sign(tablet_challenge.transcript_to_sign()),
                    12_000,
                )
                .expect("tablet confirms session");
            receiver
                .join()
                .expect("receiver joins")
                .expect("tablet session confirm handles");
        });

        assert_eq!(
            phone_runtime
                .session_snapshot(phone_challenge.session_id())
                .expect("phone session authenticated")
                .phase(),
            LanSessionPhase::Authenticated
        );
        assert_eq!(
            phone_runtime
                .inbox(12_001)
                .expect("active session inbox builds")
                .active_sessions(),
            std::slice::from_ref(
                phone_runtime
                    .session_snapshot(phone_challenge.session_id())
                    .expect("authenticated session remains queryable")
            )
        );

        exercise_batch_control(
            &mut phone_runtime,
            &mut tablet_runtime,
            &phone_challenge,
            &tablet_challenge,
            &phone_root,
            &tablet_root,
        );
    }

    #[test]
    fn a_transport_change_suspends_sessions_and_drives_rebinding() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (_phone_root, mut phone_runtime) = manager(&phone, "Phone");
        let (_tablet_root, mut tablet_runtime) = manager(&tablet, "Tablet");
        pair(&mut phone_runtime, &mut tablet_runtime, &phone, &tablet);
        let (phone_challenge, tablet_challenge) = authenticate_session(
            &mut phone_runtime,
            &mut tablet_runtime,
            &phone,
            &tablet,
            10_000,
        );
        let batch_id = LanBatchId::parse("batch-network-suspend").expect("batch id parses");
        prepare_and_approve_resume_batch(
            &mut phone_runtime,
            &mut tablet_runtime,
            &phone_challenge,
            &tablet_challenge,
            &batch_id,
            resume_plan(&batch_id, b"suspend me"),
        );
        assert_eq!(
            phone_runtime
                .inbox(21_001)
                .expect("sendable inbox builds")
                .outgoing_batches()
                .first()
                .expect("outgoing batch exists")
                .drive(),
            LanOutgoingBatchDrive::Sendable
        );

        let moved = LanNetworkSnapshot::new(
            2,
            true,
            vec![LanBindCandidate::parse("127.0.0.2", 0).expect("moved candidate")],
        )
        .expect("moved snapshot builds");
        phone_runtime
            .update_network(moved.clone())
            .expect("a different transport fact suspends sessions");
        let suspended = phone_runtime.inbox(22_000).expect("suspended inbox builds");
        assert!(
            suspended.active_sessions().is_empty(),
            "the live session must not survive a transport change"
        );
        assert_eq!(
            suspended
                .outgoing_batches()
                .first()
                .expect("outgoing durable work survives suspension")
                .drive(),
            LanOutgoingBatchDrive::NeedsRebind,
            "durable outgoing work drives rebinding instead of silently retrying a dead session"
        );

        tablet_runtime
            .update_network(moved)
            .expect("receiver sessions suspend on the same transport change");
        assert_eq!(
            tablet_runtime
                .inbox(22_000)
                .expect("receiver suspended inbox builds")
                .batch_recoveries()
                .first()
                .expect("received durable work survives suspension")
                .drive(),
            lomo_lan::LanReceivedBatchDrive::NeedsRebind,
            "received work waiting on chunks drives rebinding for the dead session"
        );
    }

    #[test]
    fn a_retired_batch_refuses_reprepare_and_fails_the_sender_durably() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (_phone_root, mut phone_runtime) = manager(&phone, "Phone");
        let (_tablet_root, mut tablet_runtime) = manager(&tablet, "Tablet");
        pair(&mut phone_runtime, &mut tablet_runtime, &phone, &tablet);
        let (phone_challenge, tablet_challenge) = authenticate_session(
            &mut phone_runtime,
            &mut tablet_runtime,
            &phone,
            &tablet,
            10_000,
        );
        let batch_id = LanBatchId::parse("batch-retired-target").expect("batch id parses");
        let plan = resume_plan(&batch_id, b"retire me");
        thread::scope(|scope| {
            let receiver = scope.spawn(|| tablet_runtime.poll_listener(20_000));
            phone_runtime
                .prepare_batch(phone_challenge.session_id(), plan.clone(), 20_000)
                .expect("prepare sends");
            receiver
                .join()
                .expect("receiver joins")
                .expect("prepare handles");
        });
        thread::scope(|scope| {
            let receiver = scope.spawn(|| phone_runtime.poll_listener(24_000));
            tablet_runtime
                .reject_batch(tablet_challenge.session_id(), &batch_id, 24_000)
                .expect("rejection sends");
            receiver
                .join()
                .expect("receiver joins")
                .expect("rejection handles");
        });

        let retired_at = 24_000 + lomo_lan::LAN_BATCH_RETIRE_DELAY_MS;
        tablet_runtime
            .inbox(retired_at)
            .expect("maintenance retires the terminal batch");
        assert!(
            tablet_runtime.batch_recovery(&batch_id).is_none(),
            "the rejected batch retires once its anti-replay window closes"
        );

        let error = thread::scope(|scope| {
            let receiver = scope.spawn(|| tablet_runtime.poll_listener(retired_at + 1));
            let error = phone_runtime
                .prepare_batch(phone_challenge.session_id(), plan, retired_at + 1)
                .expect_err("a retired batch id refuses resurrection");
            receiver
                .join()
                .expect("receiver joins")
                .expect("refusal handles");
            error
        });
        assert_eq!(error.code(), "lan_batch_retired");
        assert!(
            phone_runtime
                .inbox(retired_at + 2)
                .expect("sender inbox builds")
                .outgoing_batches()
                .iter()
                .all(|batch| batch.batch_id() != &batch_id),
            "the sender's own terminal record retires inside the same anti-replay window"
        );
    }

    #[test]
    fn a_remotely_rejected_chunk_fails_the_outgoing_batch_durably() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (_phone_root, mut phone_runtime) = manager(&phone, "Phone");
        let (_tablet_root, mut tablet_runtime) = manager(&tablet, "Tablet");
        pair(&mut phone_runtime, &mut tablet_runtime, &phone, &tablet);
        let (phone_challenge, tablet_challenge) = authenticate_session(
            &mut phone_runtime,
            &mut tablet_runtime,
            &phone,
            &tablet,
            10_000,
        );
        let batch_id = LanBatchId::parse("batch-chunk-refusal").expect("batch id parses");
        let body = b"refuse me".to_vec();
        prepare_and_approve_resume_batch(
            &mut phone_runtime,
            &mut tablet_runtime,
            &phone_challenge,
            &tablet_challenge,
            &batch_id,
            resume_plan(&batch_id, &body),
        );
        send_payload_chunk(
            &mut phone_runtime,
            &mut tablet_runtime,
            &phone_challenge,
            &batch_id,
            (0, ATTACHMENT_SLOT_BODY, 0),
            &body,
        );
        // A byzantine sender resends the same binding with different bytes; the receiver's
        // durable stage refuses the replay as a terminal conflict.
        let replay_binding = ChunkBinding::new(
            phone_challenge.session_id(),
            batch_id.as_str(),
            0,
            ATTACHMENT_SLOT_BODY,
            0,
        )
        .expect("replayed binding builds");
        let error = thread::scope(|scope| {
            let receiver = scope.spawn(|| tablet_runtime.poll_listener(25_000));
            let error = phone_runtime
                .send_batch_chunk(
                    &LanConnectionPool::default(),
                    &replay_binding,
                    b"differs..",
                    25_000,
                )
                .expect_err("a replayed binding with different bytes refuses");
            receiver
                .join()
                .expect("receiver joins")
                .expect("refusal handles");
            error
        });
        assert_eq!(error.code(), "lan_chunk_replayed_with_different_bytes");
        let outgoing = phone_runtime
            .inbox(25_001)
            .expect("failed inbox builds")
            .outgoing_batches()
            .first()
            .expect("outgoing batch remains observable")
            .clone();
        assert_eq!(
            outgoing.drive(),
            LanOutgoingBatchDrive::Failed,
            "a terminal remote refusal drives Failed instead of retrying forever"
        );
        assert_eq!(
            outgoing.failure_code(),
            Some("lan_chunk_replayed_with_different_bytes")
        );
    }

    #[test]
    fn a_revoked_peer_cannot_start_a_session() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (_phone_root, mut phone_runtime) = manager(&phone, "Phone");
        let (_tablet_root, mut tablet_runtime) = manager(&tablet, "Tablet");
        pair(&mut phone_runtime, &mut tablet_runtime, &phone, &tablet);
        let tablet_id = DeviceId::derive(&tablet.public);
        phone_runtime
            .revoke_peer(&tablet_id, 5_000)
            .expect("peer revokes");

        let error = phone_runtime
            .begin_session(&endpoint(&tablet_runtime, &tablet, "Tablet"), 6_000, 60_000)
            .expect_err("revoked peer fails before network I/O");
        assert_eq!(error.code(), "lan_peer_revoked");
    }

    #[test]
    fn a_failed_commit_leaves_the_item_out_of_the_committable_queue() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (_phone_root, mut phone_runtime) = manager(&phone, "Phone");
        let (tablet_root, mut tablet_runtime) = manager(&tablet, "Tablet");
        pair(&mut phone_runtime, &mut tablet_runtime, &phone, &tablet);
        let (phone_challenge, tablet_challenge) = authenticate_session(
            &mut phone_runtime,
            &mut tablet_runtime,
            &phone,
            &tablet,
            10_000,
        );
        let batch_id = LanBatchId::parse("batch-failed-item").expect("batch id parses");
        prepare_and_approve_resume_batch(
            &mut phone_runtime,
            &mut tablet_runtime,
            &phone_challenge,
            &tablet_challenge,
            &batch_id,
            resume_plan(&batch_id, b"store will fail"),
        );
        send_payload_chunk(
            &mut phone_runtime,
            &mut tablet_runtime,
            &phone_challenge,
            &batch_id,
            (0, ATTACHMENT_SLOT_BODY, 0),
            b"store will fail",
        );
        assert_eq!(
            tablet_runtime
                .inbox(22_001)
                .expect("commit inbox builds")
                .committable_items()
                .len(),
            1
        );

        let item_id = tablet_runtime
            .batch_recovery(&batch_id)
            .expect("batch recovery exists")
            .plan()
            .items()
            .first()
            .expect("item plan exists")
            .item_id()
            .clone();
        tablet_runtime
            .record_received_item_failed(&batch_id, &item_id, "lan_item_store_failed")
            .expect("a commit failure persists an explicit disposition");

        let after = tablet_runtime
            .inbox(23_000)
            .expect("post-failure inbox builds");
        assert!(
            after.committable_items().is_empty(),
            "a failed item must leave the automatic commit queue"
        );
        let recovery = after
            .batch_recoveries()
            .iter()
            .find(|recovery| recovery.preview().batch_id() == &batch_id)
            .expect("the failed batch stays recoverable");
        assert!(matches!(
            recovery.items().first().expect("item recovery exists").outcome(),
            LanReceivedItemOutcome::Failed { code } if code == "lan_item_store_failed"
        ));

        let mut reopened = LanServiceManager::open(tablet_root.path()).expect("runtime reopens");
        assert!(
            reopened
                .inbox(24_000)
                .expect("reopened inbox builds")
                .committable_items()
                .is_empty(),
            "the failure disposition survives restart and keeps the item out of the hot loop"
        );
    }

    #[test]
    fn a_pairing_hello_flood_is_bounded_and_rate_limited_before_crypto() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (_phone_root, mut phone_runtime) = manager(&phone, "Phone");
        let (_tablet_root, mut tablet_runtime) = manager(&tablet, "Tablet");
        let tablet_endpoint = endpoint(&tablet_runtime, &tablet, "Tablet");

        for _ in 0..lomo_lan::MAX_PAIR_HELLOS_PER_WINDOW {
            exchange_hello(
                &mut phone_runtime,
                &mut tablet_runtime,
                &tablet_endpoint,
                50_000,
            )
            .expect("hellos inside the per-source window admit");
        }
        let rate_limited = exchange_hello(
            &mut phone_runtime,
            &mut tablet_runtime,
            &tablet_endpoint,
            50_000,
        )
        .expect_err("the next hello in the same window is refused");
        assert_eq!(rate_limited.code(), "lan_pairing_rate_limited");

        let mut accepted = lomo_lan::MAX_PAIR_HELLOS_PER_WINDOW as usize;
        let mut now_ms = 51_000;
        while accepted < lomo_lan::MAX_PENDING_PAIRINGS {
            for _ in 0..lomo_lan::MAX_PAIR_HELLOS_PER_WINDOW {
                exchange_hello(
                    &mut phone_runtime,
                    &mut tablet_runtime,
                    &tablet_endpoint,
                    now_ms,
                )
                .expect("a fresh window admits more hellos");
                accepted += 1;
            }
            now_ms += 1_000;
        }
        let capacity = exchange_hello(
            &mut phone_runtime,
            &mut tablet_runtime,
            &tablet_endpoint,
            now_ms,
        )
        .expect_err("a full pending-pairing budget refuses before key agreement");
        assert_eq!(capacity.code(), "lan_pairing_capacity");
        assert_eq!(
            tablet_runtime
                .inbox(now_ms + 1)
                .expect("flood inbox builds")
                .pairing_challenges()
                .len(),
            lomo_lan::MAX_PENDING_PAIRINGS,
            "pending pairings stay bounded"
        );
    }

    // -- T48 (B10): connection reuse, bounded window, lock-domain separation ---------------

    use lomo_lan::{
        LanConnectionPool, LanSessionId, MAX_INFLIGHT_CHUNKS, RUNTIME_CHUNK_PLAINTEXT_BYTES,
    };
    use std::net::SocketAddr;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::{Arc, Mutex};
    use std::time::Duration;

    /// One inbound connection worker: read off-lock, validate/apply under the manager lock,
    /// write the reply off-lock — the same discipline the production pump owns.
    fn connection_worker(
        manager: &Arc<Mutex<LanServiceManager>>,
        stop: &Arc<AtomicBool>,
        mut stream: lomo_lan::FrameStream<std::net::TcpStream>,
        peer: SocketAddr,
        frame_budget: usize,
        now_ms: i64,
    ) {
        let mut handled = 0_usize;
        while handled < frame_budget && !stop.load(Ordering::SeqCst) {
            let frame = match stream.read_frame() {
                Ok(frame) => frame,
                Err(_closed) => return,
            };
            let reply = match manager.lock() {
                Ok(mut manager) => manager.handle_inbound_frame(peer, &frame, now_ms),
                Err(_poisoned) => return,
            };
            match reply {
                Ok(Some(reply)) => {
                    if stream.write_frame(&reply).is_err() {
                        return;
                    }
                }
                Ok(None) => {}
                Err(_rejected) => return,
            }
            handled += 1;
        }
    }

    /// Persistent accept loop: every accepted connection gets its own worker, mirroring the
    /// bounded production pump so a stalled peer never occupies the accept thread.
    fn serve(
        manager: &Arc<Mutex<LanServiceManager>>,
        stop: &Arc<AtomicBool>,
        frame_budget: usize,
        now_ms: i64,
    ) -> thread::JoinHandle<()> {
        let manager = Arc::clone(manager);
        let stop = Arc::clone(stop);
        thread::spawn(move || {
            loop {
                if stop.load(Ordering::SeqCst) {
                    return;
                }
                let listener = match manager.lock() {
                    Ok(manager) => manager.clone_listener(),
                    Err(_poisoned) => return,
                };
                let listener = match listener {
                    Ok(Some(listener)) => listener,
                    Ok(None) => {
                        thread::sleep(Duration::from_millis(20));
                        continue;
                    }
                    Err(_error) => return,
                };
                match LanServiceManager::accept_one(&listener) {
                    Ok(Some((stream, peer))) => {
                        let worker_manager = Arc::clone(&manager);
                        let worker_stop = Arc::clone(&stop);
                        thread::spawn(move || {
                            connection_worker(
                                &worker_manager,
                                &worker_stop,
                                stream,
                                peer,
                                frame_budget,
                                now_ms,
                            );
                        });
                    }
                    Ok(None) => {}
                    Err(_error) => return,
                }
            }
        })
    }

    fn body_batch(batch_id: &LanBatchId, body: &[u8]) -> LanBatchPlan {
        LanBatchPlan::new(
            batch_id.clone(),
            vec![
                LanItemPlan::new(
                    batch_id,
                    0,
                    1_700_000_000_000,
                    &format!("{:x}", Sha256::digest(body)),
                    body.len() as u64,
                    "pooled window body",
                    Vec::new(),
                )
                .expect("item plan builds"),
            ],
        )
        .expect("batch plan builds")
    }

    /// Prepare + approve one batch across real sockets, then wrap both peers for pooled sends.
    fn approved_body_batch(
        sender: &Arc<Mutex<LanServiceManager>>,
        receiver: &Arc<Mutex<LanServiceManager>>,
        sender_challenge: &lomo_lan::LanSessionChallenge,
        receiver_challenge: &lomo_lan::LanSessionChallenge,
        batch_id: &LanBatchId,
        body_len: usize,
    ) -> Vec<u8> {
        let body: Vec<u8> = (0..body_len)
            .map(|index| u8::try_from(index % 251).expect("modulus fits u8"))
            .collect();
        thread::scope(|scope| {
            let responder = scope.spawn(|| {
                receiver
                    .lock()
                    .expect("receiver lock")
                    .poll_listener(40_000)
            });
            sender
                .lock()
                .expect("sender lock")
                .prepare_batch(
                    sender_challenge.session_id(),
                    body_batch(batch_id, &body),
                    40_000,
                )
                .expect("prepare sends");
            responder
                .join()
                .expect("responder joins")
                .expect("prepare handles");
        });
        thread::scope(|scope| {
            let responder =
                scope.spawn(|| sender.lock().expect("sender lock").poll_listener(41_000));
            receiver
                .lock()
                .expect("receiver lock")
                .approve_batch(
                    receiver_challenge.session_id(),
                    batch_id,
                    ApprovedGeneration::capture("workspace-generation-10")
                        .expect("generation captures"),
                    41_000,
                    60_000,
                )
                .expect("approval sends");
            responder
                .join()
                .expect("responder joins")
                .expect("approval handles");
        });
        body
    }

    /// Sends every unconfirmed body chunk through the pool: plan under the manager lock,
    /// network wait off-lock, receipt application under the lock again.
    fn drive_body(
        sender: &Arc<Mutex<LanServiceManager>>,
        pool: &LanConnectionPool,
        session_id: &LanSessionId,
        batch_id: &LanBatchId,
        body: &[u8],
        now_ms: i64,
    ) {
        let missing = sender
            .lock()
            .expect("sender lock")
            .unconfirmed_batch_chunks(batch_id, 0, ATTACHMENT_SLOT_BODY)
            .expect("missing range resolves");
        for chunk_index in missing {
            let start = chunk_index as usize * RUNTIME_CHUNK_PLAINTEXT_BYTES;
            let end = (start + RUNTIME_CHUNK_PLAINTEXT_BYTES).min(body.len());
            let binding = ChunkBinding::new(
                session_id,
                batch_id.as_str(),
                0,
                ATTACHMENT_SLOT_BODY,
                chunk_index,
            )
            .expect("binding builds");
            let plan = sender
                .lock()
                .expect("sender lock")
                .plan_batch_chunk(
                    &binding,
                    body.get(start..end).expect("chunk window inside body"),
                )
                .expect("chunk plans under the short lock");
            let drained = pool.send_chunk(&plan).expect("chunk acknowledges off-lock");
            let mut manager = sender.lock().expect("sender lock");
            for (confirmed, response) in &drained {
                manager
                    .apply_chunk_receipt(confirmed, response, now_ms)
                    .expect("receipt applies");
            }
            drop(manager);
        }
    }

    #[test]
    fn pooled_channels_scale_with_sessions_not_chunks() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let laptop = TestIdentity::generate();
        let (_phone_root, mut phone_m) = manager(&phone, "Phone");
        let (_tablet_root, mut tablet_m) = manager(&tablet, "Tablet");
        let (_laptop_root, mut laptop_m) = manager(&laptop, "Laptop");
        pair(&mut phone_m, &mut tablet_m, &phone, &tablet);
        pair(&mut phone_m, &mut laptop_m, &phone, &laptop);
        let (phone_challenge, tablet_challenge) =
            authenticate_session(&mut phone_m, &mut tablet_m, &phone, &tablet, 10_000);
        let (phone_challenge2, laptop_challenge) =
            authenticate_session(&mut phone_m, &mut laptop_m, &phone, &laptop, 20_000);
        let phone = Arc::new(Mutex::new(phone_m));
        let tablet = Arc::new(Mutex::new(tablet_m));
        let laptop = Arc::new(Mutex::new(laptop_m));

        let stop = Arc::new(AtomicBool::new(false));
        let tablet_server = serve(&tablet, &stop, usize::MAX, 42_000);
        let laptop_server = serve(&laptop, &stop, usize::MAX, 42_000);
        let pool = LanConnectionPool::default();

        // Three chunks on one session ride one reusable connection.
        let batch_id = LanBatchId::parse("batch-pooled-window").expect("batch id parses");
        let body = approved_body_batch(
            &phone,
            &tablet,
            &phone_challenge,
            &tablet_challenge,
            &batch_id,
            RUNTIME_CHUNK_PLAINTEXT_BYTES * 2 + 17,
        );
        drive_body(
            &phone,
            &pool,
            phone_challenge.session_id(),
            &batch_id,
            &body,
            42_000,
        );
        assert_eq!(pool.open_channels(), 1, "one session holds one channel");
        assert_eq!(
            pool.connects_made(),
            1,
            "every chunk rode the same reusable connection"
        );
        assert!(
            tablet
                .lock()
                .expect("receiver lock")
                .received_payload_artifact(&batch_id, 0, ATTACHMENT_SLOT_BODY)
                .expect("payload resolves")
                .is_some()
        );

        // A second session grows the pool by exactly one channel — never per chunk.
        let batch_id2 = LanBatchId::parse("batch-pooled-window-2").expect("batch id parses");
        let body2 = approved_body_batch(
            &phone,
            &laptop,
            &phone_challenge2,
            &laptop_challenge,
            &batch_id2,
            RUNTIME_CHUNK_PLAINTEXT_BYTES + 5,
        );
        drive_body(
            &phone,
            &pool,
            phone_challenge2.session_id(),
            &batch_id2,
            &body2,
            42_000,
        );
        assert_eq!(pool.open_channels(), 2);
        assert_eq!(pool.connects_made(), 2, "channels scale with sessions");

        pool.close_all();
        assert_eq!(pool.open_channels(), 0);
        stop.store(true, Ordering::SeqCst);
        tablet_server.join().expect("tablet server joins");
        laptop_server.join().expect("laptop server joins");
    }

    #[test]
    fn sliding_window_pipelines_without_stop_and_wait() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (_phone_root, mut phone_m) = manager(&phone, "Phone");
        let (_tablet_root, mut tablet_m) = manager(&tablet, "Tablet");
        pair(&mut phone_m, &mut tablet_m, &phone, &tablet);
        let (phone_challenge, tablet_challenge) =
            authenticate_session(&mut phone_m, &mut tablet_m, &phone, &tablet, 10_000);
        let phone = Arc::new(Mutex::new(phone_m));
        let tablet = Arc::new(Mutex::new(tablet_m));
        let batch_id = LanBatchId::parse("batch-sliding-window").expect("batch id parses");
        // More chunks than the window proves the window, not the batch size, is the bound.
        let body = approved_body_batch(
            &phone,
            &tablet,
            &phone_challenge,
            &tablet_challenge,
            &batch_id,
            RUNTIME_CHUNK_PLAINTEXT_BYTES * MAX_INFLIGHT_CHUNKS + 1,
        );

        // Receiver answers only after every windowed frame arrived: a stop-and-wait sender
        // would stall waiting for the first acknowledgement while the receiver waits for the
        // rest of the window.
        let responder = {
            let tablet = Arc::clone(&tablet);
            thread::spawn(move || {
                let listener = tablet
                    .lock()
                    .expect("receiver lock")
                    .clone_listener()
                    .expect("listener clones")
                    .expect("listener exists");
                let mut accepted = None;
                for _ in 0..40 {
                    if let Some(conn) =
                        LanServiceManager::accept_one(&listener).expect("accept succeeds")
                    {
                        accepted = Some(conn);
                        break;
                    }
                }
                let (mut stream, peer) = accepted.expect("connection arrives");
                let mut replies = Vec::new();
                for _ in 0..MAX_INFLIGHT_CHUNKS {
                    let frame = stream.read_frame().expect("windowed frame arrives");
                    replies.push(
                        tablet
                            .lock()
                            .expect("receiver lock")
                            .handle_inbound_frame(peer, &frame, 42_000)
                            .expect("chunk validates"),
                    );
                }
                for reply in replies.into_iter().flatten() {
                    stream.write_frame(&reply).expect("ack writes");
                }
            })
        };

        let pool = LanConnectionPool::default();
        let mut plans = Vec::new();
        for chunk_index in 0..MAX_INFLIGHT_CHUNKS {
            let start = chunk_index * RUNTIME_CHUNK_PLAINTEXT_BYTES;
            let end = (start + RUNTIME_CHUNK_PLAINTEXT_BYTES).min(body.len());
            let binding = ChunkBinding::new(
                phone_challenge.session_id(),
                batch_id.as_str(),
                0,
                ATTACHMENT_SLOT_BODY,
                u32::try_from(chunk_index).expect("chunk index fits u32"),
            )
            .expect("binding builds");
            plans.push(
                phone
                    .lock()
                    .expect("sender lock")
                    .plan_batch_chunk(
                        &binding,
                        body.get(start..end).expect("chunk window inside body"),
                    )
                    .expect("plan builds"),
            );
        }
        let drained = pool
            .send_chunks(&plans)
            .expect("the whole window acknowledges on one channel");
        assert_eq!(drained.len(), MAX_INFLIGHT_CHUNKS);
        let mut manager = phone.lock().expect("sender lock");
        for (confirmed, response) in &drained {
            manager
                .apply_chunk_receipt(confirmed, response, 42_000)
                .expect("receipt applies");
        }
        drop(manager);
        assert_eq!(pool.connects_made(), 1);
        responder.join().expect("responder joins");
    }

    /// Serves one accepted connection by reading frames and never answering: the sender's
    /// channel read blocks until the socket deadline, holding only that channel's lock.
    fn swallow_server(
        manager: &Arc<Mutex<LanServiceManager>>,
        stop: &Arc<AtomicBool>,
    ) -> thread::JoinHandle<()> {
        let manager = Arc::clone(manager);
        let stop = Arc::clone(stop);
        thread::spawn(move || {
            loop {
                if stop.load(Ordering::SeqCst) {
                    return;
                }
                let listener = match manager.lock() {
                    Ok(manager) => manager.clone_listener(),
                    _ => return,
                };
                let Ok(Some(listener)) = listener else {
                    return;
                };
                if let Ok(Some((mut stream, _peer))) = LanServiceManager::accept_one(&listener) {
                    while stream.read_frame().is_ok() {}
                    return;
                }
            }
        })
    }

    #[test]
    fn a_blocked_session_never_blocks_another_sessions_send() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let laptop = TestIdentity::generate();
        let (_r1, mut phone_m) = manager(&phone, "Phone");
        let (_r2, mut tablet_m) = manager(&tablet, "Tablet");
        let (_r3, mut laptop_m) = manager(&laptop, "Laptop");
        pair(&mut phone_m, &mut tablet_m, &phone, &tablet);
        pair(&mut phone_m, &mut laptop_m, &phone, &laptop);
        let (phone_challenge, tablet_challenge) =
            authenticate_session(&mut phone_m, &mut tablet_m, &phone, &tablet, 10_000);
        let (phone_challenge2, laptop_challenge) =
            authenticate_session(&mut phone_m, &mut laptop_m, &phone, &laptop, 20_000);
        let phone = Arc::new(Mutex::new(phone_m));
        let tablet = Arc::new(Mutex::new(tablet_m));
        let laptop = Arc::new(Mutex::new(laptop_m));

        // Both sessions approve a batch; laptop's channel is then silenced mid-flight.
        let laptop_batch = LanBatchId::parse("batch-blocked-peer").expect("batch id parses");
        let laptop_body = approved_body_batch(
            &phone,
            &laptop,
            &phone_challenge2,
            &laptop_challenge,
            &laptop_batch,
            1_024,
        );
        let tablet_batch = LanBatchId::parse("batch-unblocked-peer").expect("batch id parses");
        let tablet_body = approved_body_batch(
            &phone,
            &tablet,
            &phone_challenge,
            &tablet_challenge,
            &tablet_batch,
            1_024,
        );

        let stop_tablet = Arc::new(AtomicBool::new(false));
        let tablet_server = serve(&tablet, &stop_tablet, usize::MAX, 42_000);
        let stop_laptop = Arc::new(AtomicBool::new(false));
        let laptop_server = swallow_server(&laptop, &stop_laptop);

        let pool = Arc::new(LanConnectionPool::default());
        let blocked = {
            let phone = Arc::clone(&phone);
            let pool = Arc::clone(&pool);
            thread::spawn(move || {
                let binding = ChunkBinding::new(
                    phone_challenge2.session_id(),
                    laptop_batch.as_str(),
                    0,
                    ATTACHMENT_SLOT_BODY,
                    0,
                )
                .expect("binding builds");
                let plan = phone
                    .lock()
                    .expect("sender lock")
                    .plan_batch_chunk(&binding, &laptop_body)
                    .expect("plan builds");
                pool.send_chunk(&plan).map(|_drained| ())
            })
        };
        // Give the blocked send a moment to park on the channel read, then prove the healthy
        // session still completes end to end on its own channel.
        thread::sleep(Duration::from_millis(200));
        drive_body(
            &phone,
            &pool,
            phone_challenge.session_id(),
            &tablet_batch,
            &tablet_body,
            42_000,
        );
        assert!(
            tablet
                .lock()
                .expect("receiver lock")
                .received_payload_artifact(&tablet_batch, 0, ATTACHMENT_SLOT_BODY)
                .expect("payload resolves")
                .is_some(),
            "the healthy session completed while the blocked one was parked"
        );
        assert!(!blocked.is_finished(), "the blocked send is still parked");

        let outcome = blocked.join().expect("blocked send joins");
        let code = outcome
            .expect_err("a channel that can never deliver must fail closed")
            .code()
            .to_owned();
        // The silent peer and the parked sender share one socket deadline: whichever read side
        // fires first decides whether the sender observes its own deadline or the peer's EOF.
        assert!(
            matches!(
                code.as_str(),
                "lan_deadline_exceeded" | "lan_frame_incomplete"
            ),
            "a stalled channel surfaces a typed network/close error, got {code}"
        );
        stop_tablet.store(true, Ordering::SeqCst);
        stop_laptop.store(true, Ordering::SeqCst);
        tablet_server.join().expect("tablet server joins");
        laptop_server.join().expect("laptop server joins");
    }

    #[test]
    fn a_dead_channel_releases_and_rebuilds_once() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (_phone_root, mut phone_m) = manager(&phone, "Phone");
        let (_tablet_root, mut tablet_m) = manager(&tablet, "Tablet");
        pair(&mut phone_m, &mut tablet_m, &phone, &tablet);
        let (phone_challenge, tablet_challenge) =
            authenticate_session(&mut phone_m, &mut tablet_m, &phone, &tablet, 10_000);
        let phone = Arc::new(Mutex::new(phone_m));
        let tablet = Arc::new(Mutex::new(tablet_m));
        let batch_id = LanBatchId::parse("batch-dead-channel").expect("batch id parses");
        let body = approved_body_batch(
            &phone,
            &tablet,
            &phone_challenge,
            &tablet_challenge,
            &batch_id,
            RUNTIME_CHUNK_PLAINTEXT_BYTES + 9,
        );

        // The first connection dies after serving exactly one frame.
        let stop = Arc::new(AtomicBool::new(false));
        let server = serve(&tablet, &stop, 1, 42_000);
        let pool = LanConnectionPool::default();
        send_one_chunk(
            &phone,
            &pool,
            &binding(&phone_challenge, &batch_id, 0),
            body.get(..RUNTIME_CHUNK_PLAINTEXT_BYTES)
                .expect("chunk window inside body"),
            42_000,
        )
        .expect("first chunk acknowledges on the fresh channel");

        // The dead channel's ack can never arrive: the send fails closed and the channel is
        // released rather than retried forever.
        let dead_error = send_one_chunk(
            &phone,
            &pool,
            &binding(&phone_challenge, &batch_id, 1),
            body.get(RUNTIME_CHUNK_PLAINTEXT_BYTES..)
                .expect("chunk window inside body"),
            42_000,
        )
        .expect_err("dead channel fails closed");
        assert!(
            matches!(
                dead_error.code(),
                "lan_frame_incomplete"
                    | "lan_deadline_exceeded"
                    | "lan_frame_write_failed"
                    | "lan_frame_read_failed"
                    | "lan_connect_failed"
            ),
            "dead channel surfaces a typed network/close error, got {}",
            dead_error.code()
        );
        assert_eq!(pool.open_channels(), 0, "the dead channel released");

        // The next send connects once more — rebuild is lazy, bounded and successful.
        let stop2 = Arc::new(AtomicBool::new(false));
        let server2 = serve(&tablet, &stop2, usize::MAX, 42_000);
        send_one_chunk(
            &phone,
            &pool,
            &binding(&phone_challenge, &batch_id, 1),
            body.get(RUNTIME_CHUNK_PLAINTEXT_BYTES..)
                .expect("chunk window inside body"),
            42_000,
        )
        .expect("the rebuilt channel acknowledges");
        assert_eq!(
            pool.connects_made(),
            2,
            "the dead channel was released and rebuilt exactly once"
        );
        assert_eq!(pool.open_channels(), 1);
        stop.store(true, Ordering::SeqCst);
        stop2.store(true, Ordering::SeqCst);
        server.join().expect("server joins");
        server2.join().expect("server joins");
    }

    fn binding(
        challenge: &lomo_lan::LanSessionChallenge,
        batch_id: &LanBatchId,
        chunk_index: u32,
    ) -> ChunkBinding {
        ChunkBinding::new(
            challenge.session_id(),
            batch_id.as_str(),
            0,
            ATTACHMENT_SLOT_BODY,
            chunk_index,
        )
        .expect("binding builds")
    }

    fn send_one_chunk(
        sender: &Arc<Mutex<LanServiceManager>>,
        pool: &LanConnectionPool,
        binding: &ChunkBinding,
        plaintext: &[u8],
        now_ms: i64,
    ) -> Result<(), lomo_core::LomoError> {
        let plan = sender
            .lock()
            .expect("sender lock")
            .plan_batch_chunk(binding, plaintext)?;
        let drained = pool.send_chunk(&plan)?;
        let mut manager = sender.lock().expect("sender lock");
        for (confirmed, response) in &drained {
            manager.apply_chunk_receipt(confirmed, response, now_ms)?;
        }
        drop(manager);
        Ok(())
    }

    #[test]
    fn active_batches_all_stay_visible_and_replayed_confirms_never_double_count() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (_phone_root, mut phone_m) = manager(&phone, "Phone");
        let (_tablet_root, mut tablet_m) = manager(&tablet, "Tablet");
        pair(&mut phone_m, &mut tablet_m, &phone, &tablet);
        let (phone_challenge, tablet_challenge) =
            authenticate_session(&mut phone_m, &mut tablet_m, &phone, &tablet, 10_000);
        let phone = Arc::new(Mutex::new(phone_m));
        let tablet = Arc::new(Mutex::new(tablet_m));
        let stop = Arc::new(AtomicBool::new(false));
        let tablet_server = serve(&tablet, &stop, usize::MAX, 42_000);
        let pool = LanConnectionPool::default();

        // Two independent batches stay active at once: neither may collapse into the other.
        let batch_a = LanBatchId::parse("batch-multi-a").expect("batch id parses");
        let batch_b = LanBatchId::parse("batch-multi-b").expect("batch id parses");
        let body_a = approved_body_batch(
            &phone,
            &tablet,
            &phone_challenge,
            &tablet_challenge,
            &batch_a,
            RUNTIME_CHUNK_PLAINTEXT_BYTES + 7,
        );
        let body_b = approved_body_batch(
            &phone,
            &tablet,
            &phone_challenge,
            &tablet_challenge,
            &batch_b,
            RUNTIME_CHUNK_PLAINTEXT_BYTES + 9,
        );

        // Only the first chunk of each body lands: progress reports durable confirmed bytes.
        for (batch_id, body) in [(&batch_a, &body_a), (&batch_b, &body_b)] {
            send_one_chunk(
                &phone,
                &pool,
                &binding(&phone_challenge, batch_id, 0),
                body.get(..RUNTIME_CHUNK_PLAINTEXT_BYTES)
                    .expect("first chunk inside body"),
                42_000,
            )
            .expect("first chunk confirms durably");
        }

        let confirmed = || {
            tablet
                .lock()
                .expect("receiver lock")
                .inbox(42_000)
                .expect("inbox builds")
                .batch_recoveries()
                .iter()
                .map(|recovery| {
                    (
                        recovery.preview().batch_id().as_str().to_owned(),
                        recovery.confirmed_bytes(),
                    )
                })
                .collect::<std::collections::BTreeMap<_, _>>()
        };
        let durable = confirmed();
        assert_eq!(
            durable.get("batch-multi-a"),
            Some(&(RUNTIME_CHUNK_PLAINTEXT_BYTES as u64)),
            "batch A reports its own durable confirmed bytes"
        );
        assert_eq!(
            durable.get("batch-multi-b"),
            Some(&(RUNTIME_CHUNK_PLAINTEXT_BYTES as u64)),
            "batch B reports its own durable confirmed bytes"
        );

        // A wire-level replay of chunk 0 (lost ACK retry) reconfirms idempotently:
        // the same coordinate can never be counted twice.
        send_one_chunk(
            &phone,
            &pool,
            &binding(&phone_challenge, &batch_a, 0),
            body_a
                .get(..RUNTIME_CHUNK_PLAINTEXT_BYTES)
                .expect("first chunk inside body"),
            42_000,
        )
        .expect("replayed chunk reconfirms");
        assert_eq!(
            confirmed().get("batch-multi-a"),
            Some(&(RUNTIME_CHUNK_PLAINTEXT_BYTES as u64)),
            "a replayed confirmation never double-counts durable bytes"
        );

        pool.close_all();
        stop.store(true, Ordering::SeqCst);
        tablet_server.join().expect("tablet server joins");
    }

    #[test]
    fn a_corrupted_staged_chunk_downgrades_back_to_retransmittable() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (_phone_root, mut phone_m) = manager(&phone, "Phone");
        let (tablet_root, mut tablet_m) = manager(&tablet, "Tablet");
        pair(&mut phone_m, &mut tablet_m, &phone, &tablet);
        let (phone_challenge, tablet_challenge) =
            authenticate_session(&mut phone_m, &mut tablet_m, &phone, &tablet, 10_000);
        let phone = Arc::new(Mutex::new(phone_m));
        let tablet = Arc::new(Mutex::new(tablet_m));
        let stop = Arc::new(AtomicBool::new(false));
        let tablet_server = serve(&tablet, &stop, usize::MAX, 42_000);
        let pool = LanConnectionPool::default();

        let batch_id = LanBatchId::parse("batch-corrupt-stage").expect("batch id parses");
        let body = approved_body_batch(
            &phone,
            &tablet,
            &phone_challenge,
            &tablet_challenge,
            &batch_id,
            RUNTIME_CHUNK_PLAINTEXT_BYTES + 11,
        );
        drive_body(
            &phone,
            &pool,
            phone_challenge.session_id(),
            &batch_id,
            &body,
            42_000,
        );

        // Flip bytes inside one durable staged chunk, keeping its length: the corruption is
        // invisible to the length check and only the plan digest can catch it.
        let staged = tablet_root
            .path()
            .join("lan")
            .join("v1")
            .join("payloads")
            .join(batch_id.as_str())
            .join(format!("0-{ATTACHMENT_SLOT_BODY}"))
            .join("0.chunk");
        let mut bytes = std::fs::read(&staged).expect("staged chunk exists");
        *bytes.first_mut().expect("a staged chunk is nonempty") ^= 0xFF;
        std::fs::write(&staged, &bytes).expect("corruption writes");

        // Commit must refuse and the coordinate must become retransmittable — never silently
        // land corrupted bytes.
        let error = tablet
            .lock()
            .expect("receiver lock")
            .authorize_received_item_create(&batch_id, 0, "workspace-generation-10", 42_000)
            .expect_err("corrupted staging cannot commit");
        assert_eq!(error.code(), "lan_item_body_incomplete");
        let missing = tablet
            .lock()
            .expect("receiver lock")
            .unconfirmed_batch_chunks(&batch_id, 0, ATTACHMENT_SLOT_BODY)
            .expect("missing range resolves");
        assert_eq!(
            missing,
            vec![0, 1],
            "a payload that fails the plan digest retransmits whole: the corrupt chunk is indistinguishable"
        );

        pool.close_all();
        stop.store(true, Ordering::SeqCst);
        tablet_server.join().expect("tablet server joins");
    }
}
