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
        ATTACHMENT_SLOT_BODY, ApprovedGeneration, CHUNK_PLAINTEXT_BYTES, DeviceId, DevicePublicKey,
        DiscoveredPeerEndpoint, DisplayName, LAN_PROTOCOL_VERSION, LanAttachmentRef, LanBatchId,
        LanBatchPlan, LanBindCandidate, LanItemPlan, LanNetworkSnapshot, LanOutgoingBatchPhase,
        LanReceivedBatchDecision, LanReceivedItemOutcome, LanServiceManager, LanSessionPhase,
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
            .parse::<std::net::SocketAddr>()
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
        let pairing_inbox = tablet_runtime.inbox().expect("pairing inbox builds");
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
            .session_challenge(phone_challenge.session_id())
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
                .prepare_batch(phone_challenge.session_id(), plan)
                .expect("authenticated prepare sends");
            receiver
                .join()
                .expect("receiver joins")
                .expect("prepare handles");
        });
        let outgoing = phone_runtime.inbox().expect("outgoing inbox builds");
        assert_eq!(
            outgoing
                .outgoing_batches()
                .first()
                .expect("outgoing batch exists")
                .phase(),
            LanOutgoingBatchPhase::AwaitingApproval
        );
        let recovered_sender =
            LanServiceManager::open(phone_root.path()).expect("sender runtime reopens");
        let recovered_outgoing = recovered_sender
            .inbox()
            .expect("sender recovery inbox builds");
        assert_eq!(
            recovered_outgoing.outgoing_batches(),
            outgoing.outgoing_batches(),
            "a process restart must not erase a prepared outgoing batch"
        );
        let preview = tablet_runtime
            .batch_preview(&batch_id)
            .expect("receiver exposes pending preview");
        let inbox = tablet_runtime.inbox().expect("batch inbox builds");
        let pending_batch = inbox
            .pending_batches()
            .first()
            .expect("received batch enters the bounded inbox");
        assert_eq!(pending_batch.session_id(), tablet_challenge.session_id());
        assert_eq!(pending_batch.preview(), &preview);
        let pending_recovery = inbox
            .batch_recoveries()
            .first()
            .expect("pending batch exposes durable recovery state");
        assert_eq!(pending_recovery.session_id(), tablet_challenge.session_id());
        assert_eq!(pending_recovery.preview(), &preview);
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
        let approved_inbox = tablet_runtime.inbox().expect("approved inbox builds");
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
                .inbox()
                .expect("approved inbox builds")
                .outgoing_batches()
                .first()
                .expect("approved outgoing batch exists")
                .phase(),
            LanOutgoingBatchPhase::Approved
        );
        assert_eq!(
            LanServiceManager::open(phone_root.path())
                .expect("approved sender runtime reopens")
                .inbox()
                .expect("approved sender recovery inbox builds")
                .outgoing_batches()
                .first()
                .expect("approved outgoing batch survives restart")
                .phase(),
            LanOutgoingBatchPhase::Approved
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
        phone_runtime: &LanServiceManager,
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
        let committable = tablet_runtime.inbox().expect("commit inbox builds");
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
        phone_runtime: &LanServiceManager,
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
                phone_challenge.session_id(),
                batch_id,
                1,
                7,
                0,
                SHARED_ATTACHMENT,
            )
            .expect_err("shared attachment cannot travel at a second coordinate");
        assert_eq!(
            noncanonical.code(),
            "lan_attachment_transfer_coordinate_not_canonical"
        );
    }

    fn send_payload_chunk(
        phone_runtime: &LanServiceManager,
        tablet_runtime: &mut LanServiceManager,
        phone_challenge: &lomo_lan::LanSessionChallenge,
        batch_id: &LanBatchId,
        coordinate: (u16, u16, u32),
        payload: &[u8],
    ) {
        let (item_index, attachment_slot, chunk_index) = coordinate;
        thread::scope(|scope| {
            let receiver = scope.spawn(|| tablet_runtime.poll_listener(22_000));
            phone_runtime
                .send_batch_chunk(
                    phone_challenge.session_id(),
                    batch_id,
                    item_index,
                    attachment_slot,
                    chunk_index,
                    payload,
                )
                .expect("approved payload chunk sends and acknowledges");
            receiver
                .join()
                .expect("receiver joins")
                .expect("payload chunk handles");
        });
    }

    fn verify_recovered_transfer(tablet_root: &tempfile::TempDir, batch_id: &LanBatchId) {
        let recovered = LanServiceManager::open(tablet_root.path()).expect("runtime reopens");
        let batch = recovered
            .batch_recovery(batch_id)
            .expect("batch recovery survives restart");
        assert_eq!(
            recovered
                .inbox()
                .expect("recovered inbox builds")
                .committable_items()
                .len(),
            2,
            "durable confirmed ranges rebuild the commit work queue"
        );
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
        assert_eq!(
            recovered
                .received_batch_payload(batch_id, 0, ATTACHMENT_SLOT_BODY)
                .expect("received body validates"),
            Some(FIRST_BODY.to_vec())
        );
        assert_eq!(
            recovered
                .received_batch_payload(batch_id, 1, 7)
                .expect("shared attachment alias validates"),
            Some(SHARED_ATTACHMENT.to_vec())
        );
        let command = recovered
            .authorize_received_item_create(batch_id, 0, "workspace-generation-9", 22_000)
            .expect("recovered item authorization validates")
            .expect("pending item yields a received create command");
        let first_plan = batch
            .plan()
            .items()
            .first()
            .expect("first item plan exists");
        assert_eq!(command.item_id(), first_plan.item_id());
        assert_eq!(command.content(), String::from_utf8_lossy(FIRST_BODY));
        let first_attachment = command
            .attachments()
            .first()
            .expect("first authorized attachment exists");
        assert_eq!(first_attachment.name(), "shared.png");
        assert_eq!(first_attachment.bytes(), SHARED_ATTACHMENT);

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
        assert_eq!(second_attachment.bytes(), SHARED_ATTACHMENT);

        let mut recovered = recovered;
        recovered
            .record_received_item_committed(batch_id, command.item_id(), "memo-received-1")
            .expect("committed result persists");
        assert_committed_item_survives_restart(tablet_root, batch_id);
    }

    fn assert_committed_item_survives_restart(
        tablet_root: &tempfile::TempDir,
        batch_id: &LanBatchId,
    ) {
        let reopened = LanServiceManager::open(tablet_root.path()).expect("runtime reopens again");
        assert!(
            reopened
                .authorize_received_item_create(batch_id, 0, "workspace-generation-9", 22_000)
                .expect("committed replay remains valid")
                .is_none(),
            "committed item replay must not produce a second store command"
        );
        let recovery_inbox = reopened.inbox().expect("committed recovery inbox builds");
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
                .prepare_batch(phone_challenge.session_id(), rejected_plan)
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
                .inbox()
                .expect("rejected inbox builds")
                .outgoing_batches()
                .iter()
                .find(|batch| batch.batch_id() == &rejected_id)
                .expect("rejected outgoing batch remains observable")
                .phase(),
            LanOutgoingBatchPhase::Rejected
        );
        assert_eq!(
            LanServiceManager::open(phone_root.path())
                .expect("rejected sender runtime reopens")
                .inbox()
                .expect("rejected sender recovery inbox builds")
                .outgoing_batches()
                .iter()
                .find(|batch| batch.batch_id() == &rejected_id)
                .expect("rejected outgoing batch survives sender restart")
                .phase(),
            LanOutgoingBatchPhase::Rejected
        );
        let recovered = LanServiceManager::open(tablet_root.path()).expect("runtime reopens");
        assert!(matches!(
            recovered
                .batch_recovery(&rejected_id)
                .expect("rejected batch survives")
                .decision(),
            lomo_lan::LanBatchDecision::Rejected {
                rejected_at_ms: 24_000
            }
        ));
        let recovered_inbox = recovered.inbox().expect("rejected recovery inbox builds");
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
                .prepare_batch(phone_session.session_id(), plan)
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
            &phone_runtime,
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
                .prepare_batch(second_phone_session.session_id(), plan)
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
            &phone_runtime,
            &mut tablet_runtime,
            &second_phone_session,
            &batch_id,
            (0, ATTACHMENT_SLOT_BODY, 1),
            body.get(first_chunk_len..).expect("second chunk exists"),
        );
        assert_eq!(
            tablet_runtime
                .received_batch_payload(&batch_id, 0, ATTACHMENT_SLOT_BODY)
                .expect("resumed payload validates"),
            Some(body)
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
        let tablet_challenge = tablet_runtime
            .session_challenge(phone_challenge.session_id())
            .expect("responder challenge exists");
        let session_inbox = tablet_runtime.inbox().expect("session inbox builds");
        assert_eq!(
            session_inbox.session_challenges(),
            std::slice::from_ref(&tablet_challenge)
        );
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
                .inbox()
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
}
