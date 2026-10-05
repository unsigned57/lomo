// adversarial re-audit (round 2) of the 05-F1 LAN fixes: these probes extend the original
// audit matrix into same-family variants the first pass did not reach — out-of-plan
// coordinate boundaries before sealing, digest-pin scope across batch/index/session keys,
// receiver-side replay backstop across a *session rebind* (the sender pin resets per
// session), out-of-order/duplicate/cross-session response routing, the stored clamped
// deadline gating the confirm path, the approval TTL as a recoverable refusal class, and
// the confirmed-side on-disk corruption downgrade at reopen.
// Any RED is a live regression, not an expectation edit.
//
//! Probes:
//! - `plan_batch_chunk` refuses out-of-plan coordinates (`lan_item_not_in_batch`,
//!   `lan_attachment_not_in_item`, `lan_chunk_index_invalid`) *before* sealing and leaves no
//!   digest-pin residue; `ChunkBinding::successor` refuses a `u32::MAX` wrap with
//!   `lan_chunk_nonce_exhausted`.
//! - The `planned_digests` pin is keyed by the full `ChunkBinding`: batch A's pin never
//!   covers batch B (a distinct per-batch key anyway), chunk 0's pin never covers chunk 1,
//!   and a foreign session id fails at `lan_batch_session_mismatch`.
//! - After the real rebind drive (`NeedsRebind` → re-prepare under a fresh session) the
//!   receiver's confirmed-set is the replay backstop: drifted bytes over an already
//!   confirmed coordinate earn a sealed `lan_chunk_replayed_with_different_bytes` refusal
//!   that terminally fails the sender's durable batch.
//! - `match_pending` retires strictly by cleartext receipt: out-of-order acks drain, while a
//!   duplicate response for a retired receipt and a forged ack naming a sister session both
//!   fail closed `lan_error_frame_unsolicited` with every drained fact preserved.
//! - A peer-declared `deadline_ms` is clamped into the *stored* challenge and the clamped
//!   value gates `PairConfirm`/`SessionConfirm` — a cryptographically valid signature cannot
//!   extend the lease past the local TTL.
//! - `lan_approval_expired` is a recoverable refusal: it suspends the sender's session
//!   toward `NeedsRebind` without a terminal failure code.
//! - A confirmed chunk corrupted on disk is downgraded on reopen: `reconcile_confirmed`
//!   rehashes the fully-confirmed payload against the plan digest, unconfirms it, and makes
//!   the coordinate retransmittable.

#![deny(unsafe_code)]

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::indexing_slicing,
    clippy::too_many_lines,
    clippy::cast_possible_truncation,
    clippy::string_lit_as_bytes,
    clippy::redundant_clone,
    clippy::similar_names,
    reason = "adversarial fixtures fail fast; a broken fixture is not a finding"
)]
mod tests {
    use std::net::SocketAddr;
    use std::thread;

    use aws_lc_rs::agreement::{EphemeralPrivateKey, X25519};
    use aws_lc_rs::encoding::AsBigEndian;
    use aws_lc_rs::rand::SystemRandom;
    use aws_lc_rs::signature::{ECDSA_P256_SHA256_ASN1_SIGNING, EcdsaKeyPair, KeyPair};
    use lomo_lan::{
        ATTACHMENT_SLOT_BODY, ApprovedGeneration, ChunkBinding, DeviceId, DevicePublicKey,
        DiscoveredPeerEndpoint, DisplayName, FrameKind, LAN_PROTOCOL_VERSION, LanBatchId,
        LanBatchPlan, LanBindCandidate, LanConnectionPool, LanDurableBatch, LanFrame, LanItemPlan,
        LanJournal, LanJournalPaths, LanNetworkSnapshot, LanOutgoingBatchDrive, LanServiceManager,
        LanSessionChallenge, LanSessionId, PAIRING_TTL_MS, PeerRecord, SESSION_TTL_MS,
    };
    use sha2::{Digest, Sha256};

    const BODY: &[u8] = b"reaudit2 body bytes -- exactly thirty-two";
    const GENERATION: &str = "workspace-generation-r2";
    const BASE_MS: i64 = 1_700_000_000_000;
    const FAR_FUTURE: i64 = i64::MAX;

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
        let mut manager = LanServiceManager::open(root.path()).expect("runtime opens");
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
        (root, manager)
    }

    /// Pump-free manager for listener-independent admission paths (hello/confirm handling).
    fn headless_manager(
        identity: &TestIdentity,
        name: &str,
    ) -> (tempfile::TempDir, LanServiceManager) {
        let root = tempfile::tempdir().expect("app-private root exists");
        let mut manager = LanServiceManager::open(root.path()).expect("runtime opens");
        manager
            .configure_identity(
                identity.public.clone(),
                DisplayName::parse(name).expect("name parses"),
            )
            .expect("identity configures");
        (root, manager)
    }

    fn endpoint(manager: &LanServiceManager, identity: &TestIdentity) -> DiscoveredPeerEndpoint {
        let address = manager
            .snapshot()
            .listen_address()
            .expect("listener address")
            .parse::<SocketAddr>()
            .expect("socket address");
        DiscoveredPeerEndpoint::parse(
            DeviceId::derive(&identity.public).as_str(),
            "Peer",
            &address.ip().to_string(),
            address.port(),
            LAN_PROTOCOL_VERSION,
        )
        .expect("endpoint parses")
    }

    /// Full pair + session + prepare + approve exchange over real sockets (`now_ms` is a
    /// logical clock only; socket deadlines are real).
    fn establish_approved_batch(
        phone_runtime: &mut LanServiceManager,
        tablet_runtime: &mut LanServiceManager,
        phone: &TestIdentity,
        tablet: &TestIdentity,
        plan: LanBatchPlan,
        now_ms: i64,
    ) -> LanSessionChallenge {
        pair_devices(phone_runtime, tablet_runtime, phone, tablet, now_ms);
        let session = open_session(phone_runtime, tablet_runtime, phone, tablet, now_ms + 3);
        prepare_and_approve(
            phone_runtime,
            tablet_runtime,
            &session,
            plan,
            now_ms + 6,
            60_000,
        );
        session
    }

    fn pair_devices(
        phone_runtime: &mut LanServiceManager,
        tablet_runtime: &mut LanServiceManager,
        phone: &TestIdentity,
        tablet: &TestIdentity,
        now_ms: i64,
    ) {
        let tablet_endpoint = endpoint(tablet_runtime, tablet);
        let pairing = thread::scope(|scope| {
            let responder = scope.spawn(|| tablet_runtime.poll_listener(now_ms));
            let challenge = phone_runtime
                .begin_pairing(&tablet_endpoint, now_ms, 60_000)
                .expect("pairing hello exchanges");
            responder
                .join()
                .expect("responder joins")
                .expect("pair hello handles");
            challenge
        });
        let tablet_pairing = tablet_runtime
            .pairing_challenge(pairing.pairing_id())
            .expect("responder challenge exists");
        thread::scope(|scope| {
            let receiver = scope.spawn(|| tablet_runtime.poll_listener(now_ms + 1));
            phone_runtime
                .confirm_pairing(
                    pairing.pairing_id(),
                    &phone.sign(pairing.transcript_to_sign()),
                    now_ms + 1,
                )
                .expect("phone confirms pairing");
            receiver
                .join()
                .expect("receiver joins")
                .expect("pair confirm handles");
        });
        thread::scope(|scope| {
            let receiver = scope.spawn(|| phone_runtime.poll_listener(now_ms + 2));
            tablet_runtime
                .confirm_pairing(
                    tablet_pairing.pairing_id(),
                    &tablet.sign(tablet_pairing.transcript_to_sign()),
                    now_ms + 2,
                )
                .expect("tablet confirms pairing");
            receiver
                .join()
                .expect("receiver joins")
                .expect("tablet confirm handles");
        });
    }

    /// Fresh mutually-confirmed session on an already-trusted pair (the rebind leg).
    fn open_session(
        phone_runtime: &mut LanServiceManager,
        tablet_runtime: &mut LanServiceManager,
        phone: &TestIdentity,
        tablet: &TestIdentity,
        now_ms: i64,
    ) -> LanSessionChallenge {
        let tablet_endpoint = endpoint(tablet_runtime, tablet);
        let session = thread::scope(|scope| {
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
        let tablet_session = tablet_runtime
            .inbox(now_ms + 1)
            .expect("session inbox builds")
            .session_challenges()
            .iter()
            .find(|challenge| challenge.session_id() == session.session_id())
            .cloned()
            .expect("responder session challenge exists");
        thread::scope(|scope| {
            let receiver = scope.spawn(|| tablet_runtime.poll_listener(now_ms + 1));
            phone_runtime
                .confirm_session(
                    session.session_id(),
                    &phone.sign(session.transcript_to_sign()),
                    now_ms + 1,
                )
                .expect("phone confirms session");
            receiver
                .join()
                .expect("receiver joins")
                .expect("session confirm handles");
        });
        thread::scope(|scope| {
            let receiver = scope.spawn(|| phone_runtime.poll_listener(now_ms + 2));
            tablet_runtime
                .confirm_session(
                    tablet_session.session_id(),
                    &tablet.sign(tablet_session.transcript_to_sign()),
                    now_ms + 2,
                )
                .expect("tablet confirms session");
            receiver
                .join()
                .expect("receiver joins")
                .expect("tablet session confirm handles");
        });
        session
    }

    /// Prepares `plan` under `session`: on a known batch id with an identical plan this is a
    /// session rebind (the durable decision and approval carry over unchanged).
    fn send_prepare(
        phone_runtime: &mut LanServiceManager,
        tablet_runtime: &mut LanServiceManager,
        session: &LanSessionChallenge,
        plan: LanBatchPlan,
        now_ms: i64,
    ) {
        thread::scope(|scope| {
            let receiver = scope.spawn(|| tablet_runtime.poll_listener(now_ms));
            phone_runtime
                .prepare_batch(session.session_id(), plan, now_ms)
                .expect("prepare sends");
            receiver
                .join()
                .expect("receiver joins")
                .expect("prepare handles");
        });
    }

    /// Prepares `plan` under `session` and grants a fresh approval — a rebind must NOT
    /// re-approve: the receiver's durable decision is terminal (`lan_batch_decision_terminal`)
    /// and the surviving approval governs the rebound session.
    fn prepare_and_approve(
        phone_runtime: &mut LanServiceManager,
        tablet_runtime: &mut LanServiceManager,
        session: &LanSessionChallenge,
        plan: LanBatchPlan,
        now_ms: i64,
        approval_ttl_ms: i64,
    ) {
        let batch_id = plan.batch_id().clone();
        send_prepare(phone_runtime, tablet_runtime, session, plan, now_ms);
        thread::scope(|scope| {
            let receiver = scope.spawn(|| phone_runtime.poll_listener(now_ms + 1));
            tablet_runtime
                .approve_batch(
                    session.session_id(),
                    &batch_id,
                    ApprovedGeneration::capture(GENERATION).expect("generation captures"),
                    now_ms + 1,
                    approval_ttl_ms,
                )
                .expect("approval sends");
            receiver
                .join()
                .expect("receiver joins")
                .expect("approval handles");
        });
    }

    fn body_plan(batch_id: &str, body: &[u8]) -> LanBatchPlan {
        let batch = LanBatchId::parse(batch_id).expect("batch id parses");
        LanBatchPlan::new(
            batch.clone(),
            vec![
                LanItemPlan::new(
                    &batch,
                    0,
                    1_700_000_000_000,
                    &format!("{:x}", Sha256::digest(body)),
                    body.len() as u64,
                    "reaudit item",
                    Vec::new(),
                )
                .expect("item plan builds"),
            ],
        )
        .expect("batch plan builds")
    }

    /// Cleartext receipt prefix length of a `FrameKind::Chunk` payload:
    /// `u16 len + session`, `u16 len + batch`, `u16 item`, `u16 slot`, `u32 chunk`.
    fn receipt_prefix_len(payload: &[u8]) -> usize {
        let session_len =
            u16::from_be_bytes(payload[0..2].try_into().expect("session len")) as usize;
        let batch_offset = 2 + session_len;
        let batch_len = u16::from_be_bytes(
            payload[batch_offset..batch_offset + 2]
                .try_into()
                .expect("batch len"),
        ) as usize;
        batch_offset + 2 + batch_len + 8
    }

    /// Wire field helper (u16 length-prefixed), mirroring `push_wire_field` in runtime.rs.
    fn push_wire_field(buffer: &mut Vec<u8>, field: &[u8]) {
        buffer.extend_from_slice(&(field.len() as u16).to_be_bytes());
        buffer.extend_from_slice(field);
    }

    fn ephemeral_public() -> Vec<u8> {
        let private = EphemeralPrivateKey::generate(&X25519, &SystemRandom::new())
            .expect("ephemeral key generates");
        private
            .compute_public_key()
            .expect("ephemeral public derives")
            .as_ref()
            .to_vec()
    }

    fn pair_hello_frame(
        pairing_id: &str,
        public_key: &DevicePublicKey,
        deadline_ms: i64,
    ) -> LanFrame {
        let mut payload = Vec::new();
        push_wire_field(&mut payload, pairing_id.as_bytes());
        push_wire_field(&mut payload, public_key.as_bytes());
        push_wire_field(&mut payload, "Attacker".as_bytes());
        push_wire_field(&mut payload, &ephemeral_public());
        payload.extend_from_slice(&4242_u16.to_be_bytes());
        payload.extend_from_slice(&deadline_ms.to_be_bytes());
        LanFrame::new(FrameKind::PairHello, payload).expect("frame fits the control ceiling")
    }

    fn pair_confirm_frame(pairing_id: &str, signature: &[u8]) -> LanFrame {
        let mut payload = Vec::new();
        push_wire_field(&mut payload, pairing_id.as_bytes());
        push_wire_field(&mut payload, signature);
        LanFrame::new(FrameKind::PairConfirm, payload).expect("confirm frame builds")
    }

    fn session_hello_frame(
        session_id: &str,
        public_key: &DevicePublicKey,
        deadline_ms: i64,
    ) -> LanFrame {
        let mut payload = Vec::new();
        push_wire_field(&mut payload, session_id.as_bytes());
        push_wire_field(&mut payload, public_key.as_bytes());
        push_wire_field(&mut payload, &ephemeral_public());
        payload.extend_from_slice(&4242_u16.to_be_bytes());
        payload.extend_from_slice(&deadline_ms.to_be_bytes());
        LanFrame::new(FrameKind::SessionHello, payload).expect("frame fits the control ceiling")
    }

    fn session_confirm_frame(session_id: &str, signature: &[u8]) -> LanFrame {
        let mut payload = Vec::new();
        push_wire_field(&mut payload, session_id.as_bytes());
        push_wire_field(&mut payload, signature);
        LanFrame::new(FrameKind::SessionConfirm, payload).expect("confirm frame builds")
    }

    /// `ChunkAck` carrying an arbitrary receipt plus the wire shape `decode_chunk_response`
    /// demands (`receipt ∥ nonce ∥ sealed-tail`) — a forged response that is well-formed
    /// enough to reach receipt routing but names a coordinate the channel never sent.
    fn foreign_receipt_ack_frame(
        session_id: &LanSessionId,
        batch_id: &LanBatchId,
        item_index: u16,
        attachment_slot: u16,
        chunk_index: u32,
    ) -> LanFrame {
        let mut payload = Vec::new();
        push_wire_field(&mut payload, session_id.as_str().as_bytes());
        push_wire_field(&mut payload, batch_id.as_str().as_bytes());
        payload.extend_from_slice(&item_index.to_be_bytes());
        payload.extend_from_slice(&attachment_slot.to_be_bytes());
        payload.extend_from_slice(&chunk_index.to_be_bytes());
        payload.extend_from_slice(&[0_u8; 12]); // response nonce
        payload.extend_from_slice(&[0_u8; 16]); // tag-shaped sealed tail
        LanFrame::new(FrameKind::ChunkAck, payload).expect("forged ack builds")
    }

    /// The sender's durable terminal code for `batch`, if any. `now_ms` must stay scenario-
    /// local: a terminal batch retires from the inbox past its retire horizon.
    fn failure_code_of(
        runtime: &mut LanServiceManager,
        batch: &LanBatchId,
        now_ms: i64,
    ) -> Option<String> {
        runtime
            .inbox(now_ms)
            .expect("inbox builds")
            .outgoing_batches()
            .iter()
            .find(|outgoing| outgoing.batch_id() == batch)
            .expect("batch is tracked")
            .failure_code()
            .map(str::to_owned)
    }

    fn drive_of(
        runtime: &mut LanServiceManager,
        batch: &LanBatchId,
        now_ms: i64,
    ) -> LanOutgoingBatchDrive {
        runtime
            .inbox(now_ms)
            .expect("inbox builds")
            .outgoing_batches()
            .iter()
            .find(|outgoing| outgoing.batch_id() == batch)
            .expect("batch is tracked")
            .drive()
    }

    #[test]
    fn out_of_plan_coordinates_refuse_before_sealing_and_leave_no_pin() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (_phone_root, mut phone_runtime) = manager(&phone, "Phone");
        let (_tablet_root, mut tablet_runtime) = manager(&tablet, "Tablet");
        let plan = body_plan("r2-coords", BODY);
        let batch_id = plan.batch_id().clone();
        let session = establish_approved_batch(
            &mut phone_runtime,
            &mut tablet_runtime,
            &phone,
            &tablet,
            plan,
            10_000,
        );
        let binding_of = |item: u16, slot: u16, chunk: u32| {
            ChunkBinding::new(session.session_id(), batch_id.as_str(), item, slot, chunk)
                .expect("binding builds")
        };

        // Coordinate 0 is legal; every neighbour outside the plan must refuse *before* the
        // digest pin is touched and before a nonce could seal attacker bytes.
        let err = phone_runtime
            .plan_batch_chunk(&binding_of(u16::MAX, ATTACHMENT_SLOT_BODY, 0), BODY)
            .expect_err("an item index outside the plan must not seal");
        assert_eq!(err.code(), "lan_item_not_in_batch");
        let err = phone_runtime
            .plan_batch_chunk(&binding_of(0, 0xBEEF, 0), BODY)
            .expect_err("an attachment slot outside the plan must not seal");
        assert_eq!(err.code(), "lan_attachment_not_in_item");
        let err = phone_runtime
            .plan_batch_chunk(&binding_of(0, ATTACHMENT_SLOT_BODY, 1), BODY)
            .expect_err("a one-chunk payload has no chunk index 1");
        assert_eq!(err.code(), "lan_chunk_index_invalid");
        let max_binding = binding_of(0, ATTACHMENT_SLOT_BODY, u32::MAX);
        let err = phone_runtime
            .plan_batch_chunk(&max_binding, BODY)
            .expect_err("u32::MAX is outside the plan, not a wrap-around");
        assert_eq!(err.code(), "lan_chunk_index_invalid");
        let err = max_binding
            .successor()
            .expect_err("the u32 chunk-index space cannot wrap into a second nonce");
        assert_eq!(err.code(), "lan_chunk_nonce_exhausted");

        // None of the refused coordinates touched the pin: the legal coordinate seals once…
        phone_runtime
            .plan_batch_chunk(&binding_of(0, ATTACHMENT_SLOT_BODY, 0), BODY)
            .expect("the legal coordinate plans after refused neighbours");
        // …and the first planned digest still pins it against drift.
        let drifted: Vec<u8> = BODY.iter().map(|byte| byte ^ 0x5A).collect();
        let err = phone_runtime
            .plan_batch_chunk(&binding_of(0, ATTACHMENT_SLOT_BODY, 0), &drifted)
            .expect_err("re-planning a pinned coordinate with new bytes must refuse");
        assert_eq!(err.code(), "lan_chunk_content_changed");
    }

    #[test]
    fn the_digest_pin_is_scoped_to_the_full_binding() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (_phone_root, mut phone_runtime) = manager(&phone, "Phone");
        let (_tablet_root, mut tablet_runtime) = manager(&tablet, "Tablet");
        // A two-chunk body exercises the chunk-index axis of the pin key.
        let wide: Vec<u8> = vec![0xAB; lomo_lan::RUNTIME_CHUNK_PLAINTEXT_BYTES + 16];
        let head = &wide[..lomo_lan::RUNTIME_CHUNK_PLAINTEXT_BYTES];
        let tail = &wide[lomo_lan::RUNTIME_CHUNK_PLAINTEXT_BYTES..];
        let plan_a = body_plan("r2-pin-a", &wide);
        let session = establish_approved_batch(
            &mut phone_runtime,
            &mut tablet_runtime,
            &phone,
            &tablet,
            plan_a,
            20_000,
        );
        // Batch B rides the *same* session — the batch id is part of the key domain, so the
        // same coordinate under B is a different (key, nonce) pair, not a nonce reuse.
        prepare_and_approve(
            &mut phone_runtime,
            &mut tablet_runtime,
            &session,
            body_plan("r2-pin-b", &wide),
            26_000,
            60_000,
        );
        let batch_a = LanBatchId::parse("r2-pin-a").expect("batch a parses");
        let batch_b = LanBatchId::parse("r2-pin-b").expect("batch b parses");
        let binding_of = |batch: &LanBatchId, chunk: u32| {
            ChunkBinding::new(
                session.session_id(),
                batch.as_str(),
                0,
                ATTACHMENT_SLOT_BODY,
                chunk,
            )
            .expect("binding builds")
        };

        let plan_a0 = phone_runtime
            .plan_batch_chunk(&binding_of(&batch_a, 0), head)
            .expect("A chunk 0 plans");
        let plan_b0 = phone_runtime
            .plan_batch_chunk(&binding_of(&batch_b, 0), head)
            .expect("same coordinate in another batch plans under a different key");
        let offset = receipt_prefix_len(plan_a0.frame().payload());
        assert_eq!(
            receipt_prefix_len(plan_b0.frame().payload()),
            offset,
            "both frames carry the same receipt-prefix shape"
        );
        assert_ne!(
            &plan_a0.frame().payload()[offset..],
            &plan_b0.frame().payload()[offset..],
            "same coordinate + same plaintext in two batches must not produce the same sealed \
             bytes — the batch id participates in the key domain"
        );
        phone_runtime
            .plan_batch_chunk(&binding_of(&batch_a, 1), tail)
            .expect("A chunk 1 plans on its own coordinate");

        // Each pin holds independently: drift on A's coordinate cannot hide behind B's pin,
        // and a coordinate that pinned nothing stays unpinned only until first planned.
        let drifted: Vec<u8> = head.iter().map(|byte| byte ^ 0x5A).collect();
        let err = phone_runtime
            .plan_batch_chunk(&binding_of(&batch_a, 0), &drifted)
            .expect_err("A's pin refuses drift");
        assert_eq!(err.code(), "lan_chunk_content_changed");
        let err = phone_runtime
            .plan_batch_chunk(&binding_of(&batch_b, 0), &drifted)
            .expect_err("B's pin is an independent entry, not a shared batch or session pin");
        assert_eq!(err.code(), "lan_chunk_content_changed");
        let drifted_tail: Vec<u8> = tail.iter().map(|byte| byte ^ 0x5A).collect();
        let err = phone_runtime
            .plan_batch_chunk(&binding_of(&batch_a, 1), &drifted_tail)
            .expect_err("the pin follows the full coordinate, chunk index included");
        assert_eq!(err.code(), "lan_chunk_content_changed");

        // A binding under a session that does not own the outgoing batch never reaches the
        // pin check — the session gate fires first, so no nonce domain exists to pollute.
        let foreign_session =
            LanSessionId::parse("ffffffffffffffffffffffffffffffff").expect("session parses");
        let foreign_binding = ChunkBinding::new(
            &foreign_session,
            batch_a.as_str(),
            0,
            ATTACHMENT_SLOT_BODY,
            0,
        )
        .expect("foreign binding builds");
        let err = phone_runtime
            .plan_batch_chunk(&foreign_binding, head)
            .expect_err("a foreign session cannot plan the batch's coordinate");
        assert_eq!(err.code(), "lan_batch_session_mismatch");

        // Identical re-plans stay idempotent on every pinned coordinate.
        phone_runtime
            .plan_batch_chunk(&binding_of(&batch_a, 0), head)
            .expect("identical re-plan is idempotent");
        phone_runtime
            .plan_batch_chunk(&binding_of(&batch_b, 0), head)
            .expect("identical re-plan is idempotent per batch");
    }

    #[test]
    fn a_rebound_session_cannot_resend_drifted_bytes_over_a_confirmed_coordinate() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (_phone_root, mut phone_runtime) = manager(&phone, "Phone");
        let (_tablet_root, mut tablet_runtime) = manager(&tablet, "Tablet");
        let plan = body_plan("r2-rebind", BODY);
        let batch_id = plan.batch_id().clone();
        let session = establish_approved_batch(
            &mut phone_runtime,
            &mut tablet_runtime,
            &phone,
            &tablet,
            plan.clone(),
            30_000,
        );
        let binding_s1 = ChunkBinding::new(
            session.session_id(),
            batch_id.as_str(),
            0,
            ATTACHMENT_SLOT_BODY,
            0,
        )
        .expect("binding builds");

        // Chunk 0 lands and is durably confirmed on the receiver.
        let pool = LanConnectionPool::default();
        let tablet_listener = tablet_runtime
            .clone_listener()
            .expect("listener clones")
            .expect("listener is bound");
        thread::scope(|scope| {
            let _responder = scope.spawn(|| {
                let mut stream = None;
                let mut source = None;
                for _attempt in 0..60 {
                    if let Some((accepted, peer)) =
                        LanServiceManager::accept_one(&tablet_listener).expect("accept polls")
                    {
                        stream = Some(accepted);
                        source = Some(peer);
                        break;
                    }
                }
                let mut stream = stream.expect("data connection arrives");
                let source = source.expect("peer address records");
                let chunk = stream.read_frame().expect("chunk reads");
                let reply = tablet_runtime
                    .handle_inbound_frame(source, &chunk, 30_500)
                    .expect("chunk is answered")
                    .expect("an ack replies");
                stream.write_frame(&reply).expect("ack writes");
            });
            phone_runtime
                .send_batch_chunk(&pool, &binding_s1, BODY, 30_600)
                .expect("the first chunk lands");
        });
        assert_eq!(
            tablet_runtime
                .unconfirmed_batch_chunks(&batch_id, 0, ATTACHMENT_SLOT_BODY)
                .expect("receiver recovery reads"),
            Vec::<u32>::new(),
            "the coordinate is durably confirmed on the receiver"
        );

        // Network flap: both sides drop the authenticated session. The durable batch demands
        // a rebind — the exact drive Kotlin acts on.
        for runtime in [&mut phone_runtime, &mut tablet_runtime] {
            runtime
                .update_network(
                    LanNetworkSnapshot::new(
                        2,
                        true,
                        vec![LanBindCandidate::parse("127.0.0.1", 7).expect("candidate")],
                    )
                    .expect("network snapshot"),
                )
                .expect("network publishes");
        }
        assert_eq!(
            drive_of(&mut phone_runtime, &batch_id, 31_000),
            LanOutgoingBatchDrive::NeedsRebind,
            "a durable batch whose session died must demand rebind, never silently reset"
        );

        // Rebind: fresh session, same batch id, identical plan. The receiver's durable
        // decision is terminal — the surviving approval (TTL still open) governs the
        // rebound session, so rebind is prepare-only on both sides.
        let session2 = open_session(
            &mut phone_runtime,
            &mut tablet_runtime,
            &phone,
            &tablet,
            31_000,
        );
        send_prepare(
            &mut phone_runtime,
            &mut tablet_runtime,
            &session2,
            plan,
            31_010,
        );
        assert_ne!(
            session2.session_id(),
            session.session_id(),
            "the rebind runs under a fresh session identity"
        );
        // The sealed batch-status report in the rebind reply already carried the receiver's
        // confirmed set: the sender knows chunk 0 is done and drives AwaitingReport rather
        // than rewinding to Sendable. Durable progress is never re-rolled by a rebind.
        assert_eq!(
            drive_of(&mut phone_runtime, &batch_id, 31_020),
            LanOutgoingBatchDrive::AwaitingReport,
            "a rebind preserves confirmed progress — the receiver's report re-arms the drive"
        );

        // Adversarial: the sender's per-session digest pin reset with S1, so the *planner*
        // would happily seal drifted bytes under the fresh session's nonce domain. The
        // receiver's confirmed-set is the durable backstop — a drifted resend must come
        // back as a sealed terminal refusal, not stage.
        let drifted: Vec<u8> = BODY.iter().map(|byte| byte ^ 0x5A).collect();
        let binding_s2 = ChunkBinding::new(
            session2.session_id(),
            batch_id.as_str(),
            0,
            ATTACHMENT_SLOT_BODY,
            0,
        )
        .expect("rebound binding builds");
        let tablet_listener = tablet_runtime
            .clone_listener()
            .expect("listener clones")
            .expect("listener is bound");
        thread::scope(|scope| {
            let _responder = scope.spawn(|| {
                let mut stream = None;
                let mut source = None;
                for _attempt in 0..60 {
                    if let Some((accepted, peer)) =
                        LanServiceManager::accept_one(&tablet_listener).expect("accept polls")
                    {
                        stream = Some(accepted);
                        source = Some(peer);
                        break;
                    }
                }
                let mut stream = stream.expect("data connection arrives");
                let source = source.expect("peer address records");
                let chunk = stream.read_frame().expect("drifted chunk reads");
                let reply = tablet_runtime
                    .handle_inbound_frame(source, &chunk, 31_500)
                    .expect("drifted resend is answered")
                    .expect("a bound refusal replies");
                assert_eq!(reply.kind(), FrameKind::Error);
                stream.write_frame(&reply).expect("refusal writes");
            });
            let send = phone_runtime.send_batch_chunk(&pool, &binding_s2, &drifted, 31_600);
            let err = send.expect_err("a drifted resend over a confirmed coordinate refuses");
            assert_eq!(
                err.code(),
                "lan_chunk_replayed_with_different_bytes",
                "the receiver's sealed refusal must reach the sender's durable state"
            );
        });
        assert_eq!(
            failure_code_of(&mut phone_runtime, &batch_id, 31_700).as_deref(),
            Some("lan_chunk_replayed_with_different_bytes"),
            "the terminal refusal lands durably — a cross-session replay cannot wear a fresh \
             nonce into the receiver's confirmed truth"
        );
        assert_eq!(
            drive_of(&mut phone_runtime, &batch_id, 31_700),
            LanOutgoingBatchDrive::Failed,
        );
        // Receiver truth is untouched by the refused resend.
        assert_eq!(
            tablet_runtime
                .unconfirmed_batch_chunks(&batch_id, 0, ATTACHMENT_SLOT_BODY)
                .expect("receiver recovery reads"),
            Vec::<u32>::new(),
            "the confirmed coordinate is never un-confirmed by a foreign-session replay"
        );
    }

    #[test]
    fn window_responses_route_by_receipt_through_reorder_duplicate_and_foreign_session() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (_phone_root, mut phone_runtime) = manager(&phone, "Phone");
        let (_tablet_root, mut tablet_runtime) = manager(&tablet, "Tablet");
        let plan_a = body_plan("r2-route-a", BODY);
        let session = establish_approved_batch(
            &mut phone_runtime,
            &mut tablet_runtime,
            &phone,
            &tablet,
            plan_a,
            40_000,
        );
        // Batch B's approval must exist *before* the receiver answers the chunks at 40_500.
        prepare_and_approve(
            &mut phone_runtime,
            &mut tablet_runtime,
            &session,
            body_plan("r2-route-b", BODY),
            40_100,
            60_000,
        );
        let batch_a = LanBatchId::parse("r2-route-a").expect("batch a parses");
        let batch_b = LanBatchId::parse("r2-route-b").expect("batch b parses");
        let binding_of = |batch: &LanBatchId| {
            ChunkBinding::new(
                session.session_id(),
                batch.as_str(),
                0,
                ATTACHMENT_SLOT_BODY,
                0,
            )
            .expect("binding builds")
        };
        let plan_a = phone_runtime
            .plan_batch_chunk(&binding_of(&batch_a), BODY)
            .expect("chunk A plans");
        let plan_b = phone_runtime
            .plan_batch_chunk(&binding_of(&batch_b), BODY)
            .expect("chunk B plans");

        // Real receiver answers both chunks; the wire returns B's genuine ack first
        // (reordered), then a *duplicate* of B's ack (receipt already retired), and only then
        // A's genuine ack which the window must never reach.
        let tablet_listener = tablet_runtime
            .clone_listener()
            .expect("listener clones")
            .expect("listener is bound");
        let (send, drained) = thread::scope(|scope| {
            let spoofer = scope.spawn(|| {
                let mut stream = None;
                let mut source = None;
                for _attempt in 0..60 {
                    if let Some((accepted, peer)) =
                        LanServiceManager::accept_one(&tablet_listener).expect("accept polls")
                    {
                        stream = Some(accepted);
                        source = Some(peer);
                        break;
                    }
                }
                let mut stream = stream.expect("data connection arrives");
                let source = source.expect("peer address records");
                let chunk_a = stream.read_frame().expect("chunk A reads");
                let reply_a = tablet_runtime
                    .handle_inbound_frame(source, &chunk_a, 40_500)
                    .expect("chunk A answered")
                    .expect("ack A replies");
                let chunk_b = stream.read_frame().expect("chunk B reads");
                let reply_b = tablet_runtime
                    .handle_inbound_frame(source, &chunk_b, 40_501)
                    .expect("chunk B answered")
                    .expect("ack B replies");
                // Reorder: B before A.
                stream.write_frame(&reply_b).expect("ack B writes first");
                // Duplicate the already-retired receipt.
                stream
                    .write_frame(&reply_b)
                    .expect("ack B duplicate writes");
                drop(reply_a); // A's genuine ack never gets a chance to be read.
            });
            let pool = LanConnectionPool::default();
            let mut drained = Vec::new();
            let send = pool.send_chunks(&[plan_a.clone(), plan_b.clone()], &mut drained);
            spoofer.join().expect("spoofer joins");
            (send, drained)
        });
        let err = send.expect_err("a duplicate response for a retired receipt must fail");
        assert_eq!(
            err.code(),
            "lan_error_frame_unsolicited",
            "an out-of-window duplicate is refused, not silently re-matched"
        );
        assert_eq!(
            drained.len(),
            1,
            "the reordered genuine ack already drained survives the send error"
        );
        assert_eq!(drained[0].0.batch_id(), &batch_b);
        assert_eq!(drained[0].1.kind(), FrameKind::ChunkAck);
        phone_runtime
            .apply_chunk_receipt(&drained[0].0, &drained[0].1, 47_000)
            .expect("B's reordered ack authenticates under its receipt binding");
        // A ChunkAck is window-level truth: the sender's durable confirmed set is only ever
        // written by the sealed batch-status report, which has not run for this batch yet.
        // Receiver-side durable truth, however, was written when the chunk was answered.
        assert_eq!(
            tablet_runtime
                .unconfirmed_batch_chunks(&batch_b, 0, ATTACHMENT_SLOT_BODY)
                .expect("receiver recovery reads"),
            Vec::<u32>::new(),
            "the receiver durably confirmed B's coordinate when it sealed the ack"
        );
        assert_eq!(
            phone_runtime
                .unconfirmed_batch_chunks(&batch_a, 0, ATTACHMENT_SLOT_BODY)
                .expect("sender recovery reads"),
            vec![0],
            "A's un-acked coordinate stays retransmittable — the window error did not \
             fabricate a receipt"
        );

        // Second window on the same session: an ack naming a *different* session's receipt
        // for this coordinate can never retire A's pending plan.
        let foreign_session =
            LanSessionId::parse("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee").expect("session parses");
        let foreign_ack =
            foreign_receipt_ack_frame(&foreign_session, &batch_a, 0, ATTACHMENT_SLOT_BODY, 0);
        let plan_a2 = phone_runtime
            .plan_batch_chunk(&binding_of(&batch_a), BODY)
            .expect("chunk A re-plans");
        let tablet_listener = tablet_runtime
            .clone_listener()
            .expect("listener clones")
            .expect("listener is bound");
        let spoofer = thread::spawn(move || {
            let mut stream = None;
            for _attempt in 0..60 {
                if let Some((accepted, _peer)) =
                    LanServiceManager::accept_one(&tablet_listener).expect("accept polls")
                {
                    stream = Some(accepted);
                    break;
                }
            }
            let mut stream = stream.expect("data connection arrives");
            let _chunk = stream.read_frame().expect("chunk reads");
            stream
                .write_frame(&foreign_ack)
                .expect("foreign-session ack writes");
        });
        let pool = LanConnectionPool::default();
        let mut drained = Vec::new();
        let send = pool.send_chunk(&plan_a2, &mut drained);
        spoofer.join().expect("spoofer joins");
        let err = send.expect_err("a sister session's receipt cannot retire this window");
        assert_eq!(
            err.code(),
            "lan_error_frame_unsolicited",
            "receipt routing is session-scoped, not coordinate-scoped"
        );
        assert!(drained.is_empty());
        assert_eq!(
            phone_runtime
                .unconfirmed_batch_chunks(&batch_a, 0, ATTACHMENT_SLOT_BODY)
                .expect("sender recovery reads"),
            vec![0],
            "A's coordinate remains unconfirmed after the foreign ack"
        );
    }

    #[test]
    fn a_far_future_deadline_is_clamped_in_the_challenge_and_gates_the_confirm() {
        let tablet_identity = TestIdentity::generate();
        let attacker = TestIdentity::generate();
        let source: SocketAddr = "192.0.2.66:4000".parse().expect("source address");

        // — Pairing leg: stored challenge deadline is the local TTL, not i64::MAX, and the
        // clamped value is what `handle_pair_confirm` enforces.
        let (_root, mut tablet) = headless_manager(&tablet_identity, "Tablet");
        let pairing_id = "00000000000000000000000000aaaa01";
        let reply = tablet
            .handle_inbound_frame(
                source,
                &pair_hello_frame(pairing_id, &attacker.public, FAR_FUTURE),
                BASE_MS,
            )
            .expect("hello is handled")
            .expect("admitted hello earns a reply");
        assert_eq!(reply.kind(), FrameKind::PairAccept);
        let parsed = lomo_lan::LanPairingId::parse(pairing_id).expect("pairing id parses");
        let challenge = tablet
            .pairing_challenge(&parsed)
            .expect("the admitted pairing is pending");
        assert_eq!(
            challenge.deadline_ms(),
            BASE_MS + PAIRING_TTL_MS,
            "the stored deadline is clamped to the local TTL — the attacker-declared \
             i64::MAX never enters durable challenge state"
        );
        // A *cryptographically valid* attacker confirm cannot extend the clamped lease.
        let expired = BASE_MS + PAIRING_TTL_MS + 1;
        let err = tablet
            .handle_inbound_frame(
                source,
                &pair_confirm_frame(pairing_id, &attacker.sign(challenge.transcript_to_sign())),
                expired,
            )
            .expect_err("a confirm past the clamped deadline is refused");
        assert_eq!(
            err.code(),
            "lan_pairing_expired",
            "the deadline gate runs before signature verification"
        );
    }

    #[test]
    fn a_far_future_session_deadline_is_clamped_and_gates_the_confirm() {
        let tablet_identity = TestIdentity::generate();
        let attacker = TestIdentity::generate();
        let source: SocketAddr = "192.0.2.99:4000".parse().expect("source address");
        let (root, tablet) = headless_manager(&tablet_identity, "Tablet");

        // Seed trust exactly as a completed pairing would.
        {
            let mut journal =
                LanJournal::open(LanJournalPaths::new(root.path()).expect("journal paths"))
                    .expect("journal opens");
            journal
                .store_peer(PeerRecord::paired(
                    attacker.public.clone(),
                    DisplayName::parse("Trusted Attacker").expect("name parses"),
                    BASE_MS,
                ))
                .expect("trusted peer stores");
        }
        drop(tablet);
        let mut tablet = LanServiceManager::open(root.path()).expect("runtime reopens");
        tablet
            .configure_identity(
                tablet_identity.public.clone(),
                DisplayName::parse("Tablet").expect("name parses"),
            )
            .expect("identity configures");

        let session_id = "00000000000000000000000000bbbb02";
        let reply = tablet
            .handle_inbound_frame(
                source,
                &session_hello_frame(session_id, &attacker.public, FAR_FUTURE),
                BASE_MS,
            )
            .expect("session hello is handled")
            .expect("admitted hello earns a reply");
        assert_eq!(reply.kind(), FrameKind::SessionAccept);
        let challenge = tablet
            .inbox(BASE_MS)
            .expect("inbox builds")
            .session_challenges()
            .iter()
            .find(|challenge| challenge.session_id().as_str() == session_id)
            .cloned()
            .expect("the admitted session is pending");
        assert_eq!(
            challenge.deadline_ms(),
            BASE_MS + SESSION_TTL_MS,
            "the stored session deadline is clamped to the local TTL"
        );
        let expired = BASE_MS + SESSION_TTL_MS + 1;
        let err = tablet
            .handle_inbound_frame(
                source,
                &session_confirm_frame(session_id, &attacker.sign(challenge.transcript_to_sign())),
                expired,
            )
            .expect_err("a session confirm past the clamped deadline is refused");
        assert_eq!(
            err.code(),
            "lan_session_expired",
            "the clamped deadline gates the confirm even with a valid signature"
        );
    }

    #[test]
    fn an_expired_approval_refusal_drives_rebind_not_terminal_failure() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (_phone_root, mut phone_runtime) = manager(&phone, "Phone");
        let (_tablet_root, mut tablet_runtime) = manager(&tablet, "Tablet");
        let plan = body_plan("r2-approval", BODY);
        let batch_id = plan.batch_id().clone();
        pair_devices(
            &mut phone_runtime,
            &mut tablet_runtime,
            &phone,
            &tablet,
            50_000,
        );
        let session = open_session(
            &mut phone_runtime,
            &mut tablet_runtime,
            &phone,
            &tablet,
            50_003,
        );
        // A deliberately short approval TTL: 30 seconds of recovery budget.
        prepare_and_approve(
            &mut phone_runtime,
            &mut tablet_runtime,
            &session,
            plan,
            50_006,
            30_000,
        );
        let binding = ChunkBinding::new(
            session.session_id(),
            batch_id.as_str(),
            0,
            ATTACHMENT_SLOT_BODY,
            0,
        )
        .expect("binding builds");

        // The sender arrives after the receiver's approval window closed. The receiver
        // answers a sealed `lan_approval_expired` refusal — a *recoverable* code that must
        // suspend the session toward NeedsRebind, never fail the batch durably.
        let pool = LanConnectionPool::default();
        let tablet_listener = tablet_runtime
            .clone_listener()
            .expect("listener clones")
            .expect("listener is bound");
        let late = 50_007 + 30_001;
        thread::scope(|scope| {
            let _responder = scope.spawn(|| {
                let mut stream = None;
                let mut source = None;
                for _attempt in 0..60 {
                    if let Some((accepted, peer)) =
                        LanServiceManager::accept_one(&tablet_listener).expect("accept polls")
                    {
                        stream = Some(accepted);
                        source = Some(peer);
                        break;
                    }
                }
                let mut stream = stream.expect("data connection arrives");
                let source = source.expect("peer address records");
                let chunk = stream.read_frame().expect("late chunk reads");
                let reply = tablet_runtime
                    .handle_inbound_frame(source, &chunk, late)
                    .expect("late chunk is answered")
                    .expect("a bound refusal replies");
                assert_eq!(reply.kind(), FrameKind::Error);
                stream.write_frame(&reply).expect("refusal writes");
            });
            let send = phone_runtime.send_batch_chunk(&pool, &binding, BODY, late + 100);
            let err = send.expect_err("an expired approval refuses the chunk");
            assert_eq!(
                err.code(),
                "lan_approval_expired",
                "the sealed refusal surfaces the receiver's recoverable code"
            );
        });
        assert_eq!(
            drive_of(&mut phone_runtime, &batch_id, late + 200),
            LanOutgoingBatchDrive::NeedsRebind,
            "a lapsed approval window suspends the session — the batch must rebind for a \
             fresh approval, not die"
        );
        assert_eq!(
            failure_code_of(&mut phone_runtime, &batch_id, late + 200),
            None,
            "an approval expiry is never a terminal durable failure"
        );
        assert_eq!(
            phone_runtime
                .unconfirmed_batch_chunks(&batch_id, 0, ATTACHMENT_SLOT_BODY)
                .expect("sender recovery reads"),
            vec![0],
            "the refused coordinate stays retransmittable"
        );
    }

    #[test]
    fn a_confirmed_chunk_corrupted_on_disk_is_downgraded_at_reopen() {
        let root = tempfile::tempdir().expect("journal root exists");
        let paths = LanJournalPaths::new(root.path()).expect("journal paths open");
        let device = TestIdentity::generate();
        let session =
            LanSessionId::parse("0123456789abcdef0123456789abcdef").expect("session id parses");
        let plan = body_plan("r2-confirmed-rot", BODY);
        let batch_id = plan.batch_id().clone();
        let binding = ChunkBinding::new(&session, batch_id.as_str(), 0, ATTACHMENT_SLOT_BODY, 0)
            .expect("binding builds");
        {
            let mut journal = LanJournal::open(paths.clone()).expect("journal opens");
            journal
                .store_batch(LanDurableBatch::pending(
                    plan,
                    session.clone(),
                    DeviceId::derive(&device.public),
                    DisplayName::parse("Sender").expect("name parses"),
                ))
                .expect("pending batch stores");
            journal
                .stage_chunk(&binding, BODY)
                .expect("staging persists");
            journal
                .confirm_chunk(&binding)
                .expect("the coordinate confirms");
            assert!(journal.is_chunk_confirmed(&binding));
        }

        // Same-length corruption of the *confirmed* staged file — passes the length check,
        // so only the payload-digest rehash can catch it.
        let staged = paths
            .root()
            .join("payloads")
            .join(batch_id.as_str())
            .join(format!("0-{ATTACHMENT_SLOT_BODY}"))
            .join("0.chunk");
        let corrupted: Vec<u8> = BODY.iter().map(|byte| byte ^ 0xFF).collect();
        std::fs::write(&staged, &corrupted).expect("corruption writes");

        let mut reopened = LanJournal::open(paths).expect("journal reopens");
        assert!(
            !reopened.is_chunk_confirmed(&binding),
            "a fully-confirmed payload that fails its plan digest cannot keep the \
             confirmation — the whole payload is downgraded, not the single coordinate"
        );
        assert_eq!(
            reopened.unconfirmed_chunk_indices(&batch_id, 0, ATTACHMENT_SLOT_BODY, 1),
            vec![0],
            "the corrupted payload returns to retransmittable truth"
        );
        reopened
            .stage_chunk(&binding, BODY)
            .expect("the reclaimed coordinate stages fresh bytes");
        reopened
            .confirm_chunk(&binding)
            .expect("the restaged coordinate confirms again");
    }
}
