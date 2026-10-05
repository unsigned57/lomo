// adversarial-audit: 假设「三包 CLOSED 声明在当前代码上仍然成立」；
// 本文件只做探测与取证，任何 RED 都是现存代码的真实残余缺陷，禁止通过修改生产代码转绿。
//
//! Adversarial probes against the CLOSED LAN channel/pool/payload claims:
//! - It is claimed that every data-channel signal the sender trusts is AEAD-authentic and that one
//!   `(key, nonce)` pair never seals two plaintexts. The `ChunkAck` receipt and Error refusal are
//!   cleartext, and re-planning a coordinate after the source bytes drift re-seals under the
//!   same deterministic nonce.
//! - It is claimed that the protocol-state lock never spans a network wait and that channel results are
//!   attributed to the receipt they answer. A cleartext Error frame is charged to the oldest
//!   pending send of whatever session channel carried it, and a drained refusal is dropped when
//!   a later read fails.
//! - It is claimed that staged corruption downgrades to retransmission. A corrupted staged file whose
//!   coordinate was never confirmed can never be re-staged: the journal answers every retry
//!   with `lan_chunk_replayed_with_different_bytes`, which the sender applies as a terminal
//!   batch failure.

#![deny(unsafe_code)]

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::indexing_slicing,
    clippy::too_many_lines,
    clippy::cast_possible_truncation,
    reason = "adversarial fixtures fail fast; a broken fixture is not a finding"
)]
mod tests {
    use std::net::SocketAddr;
    use std::thread;

    use aws_lc_rs::encoding::AsBigEndian;
    use aws_lc_rs::rand::SystemRandom;
    use aws_lc_rs::signature::{ECDSA_P256_SHA256_ASN1_SIGNING, EcdsaKeyPair, KeyPair};
    use lomo_lan::{
        ATTACHMENT_SLOT_BODY, ApprovedGeneration, ChunkBinding, DeviceId, DevicePublicKey,
        DiscoveredPeerEndpoint, DisplayName, FrameKind, LAN_PROTOCOL_VERSION, LanBatchId,
        LanBatchPlan, LanBindCandidate, LanConnectionPool, LanDurableBatch, LanFrame, LanItemPlan,
        LanJournal, LanJournalPaths, LanNetworkSnapshot, LanOutgoingBatchDrive, LanServiceManager,
    };
    use sha2::{Digest, Sha256};

    const BODY: &[u8] = b"adversarial body bytes -- exactly thirty two!";
    const GENERATION: &str = "workspace-generation-g6";

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

    /// Full pair + session + prepare + approve exchange over real sockets, returning the two
    /// session challenges. `now_ms` is a logical clock only; socket deadlines are real.
    fn establish_approved_batch(
        phone_runtime: &mut LanServiceManager,
        tablet_runtime: &mut LanServiceManager,
        phone: &TestIdentity,
        tablet: &TestIdentity,
        plan: LanBatchPlan,
        now_ms: i64,
    ) -> lomo_lan::LanSessionChallenge {
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

        let session = thread::scope(|scope| {
            let responder = scope.spawn(|| tablet_runtime.poll_listener(now_ms + 3));
            let challenge = phone_runtime
                .begin_session(&tablet_endpoint, now_ms + 3, 60_000)
                .expect("session hello exchanges");
            responder
                .join()
                .expect("responder joins")
                .expect("session hello handles");
            challenge
        });
        let tablet_session = tablet_runtime
            .inbox(now_ms + 4)
            .expect("session inbox builds")
            .session_challenges()
            .iter()
            .find(|challenge| challenge.session_id() == session.session_id())
            .cloned()
            .expect("responder session challenge exists");
        thread::scope(|scope| {
            let receiver = scope.spawn(|| tablet_runtime.poll_listener(now_ms + 4));
            phone_runtime
                .confirm_session(
                    session.session_id(),
                    &phone.sign(session.transcript_to_sign()),
                    now_ms + 4,
                )
                .expect("phone confirms session");
            receiver
                .join()
                .expect("receiver joins")
                .expect("session confirm handles");
        });
        thread::scope(|scope| {
            let receiver = scope.spawn(|| phone_runtime.poll_listener(now_ms + 5));
            tablet_runtime
                .confirm_session(
                    tablet_session.session_id(),
                    &tablet.sign(tablet_session.transcript_to_sign()),
                    now_ms + 5,
                )
                .expect("tablet confirms session");
            receiver
                .join()
                .expect("receiver joins")
                .expect("tablet session confirm handles");
        });

        thread::scope(|scope| {
            let receiver = scope.spawn(|| tablet_runtime.poll_listener(now_ms + 6));
            phone_runtime
                .prepare_batch(session.session_id(), plan, now_ms + 6)
                .expect("prepare sends");
            receiver
                .join()
                .expect("receiver joins")
                .expect("prepare handles");
        });
        let batch_id = phone_runtime
            .inbox(now_ms + 7)
            .expect("outgoing inbox builds")
            .outgoing_batches()
            .first()
            .expect("prepared batch exists")
            .batch_id()
            .clone();
        thread::scope(|scope| {
            let receiver = scope.spawn(|| phone_runtime.poll_listener(now_ms + 7));
            tablet_runtime
                .approve_batch(
                    tablet_session.session_id(),
                    &batch_id,
                    ApprovedGeneration::capture(GENERATION).expect("generation captures"),
                    now_ms + 7,
                    60_000,
                )
                .expect("approval sends");
            receiver
                .join()
                .expect("receiver joins")
                .expect("approval handles");
        });
        session
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
                    "adversarial item",
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

    /// `encode_error_reply` wire shape: one u16-length-prefixed UTF-8 code, no binding fields.
    fn cleartext_error_frame(code: &str) -> LanFrame {
        let mut payload = (code.len() as u16).to_be_bytes().to_vec();
        payload.extend_from_slice(code.as_bytes());
        LanFrame::new(FrameKind::Error, payload).expect("error frame builds")
    }

    #[test]
    fn resealing_one_coordinate_with_different_bytes_reuses_the_nonce() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (_phone_root, mut phone_runtime) = manager(&phone, "Phone");
        let (_tablet_root, mut tablet_runtime) = manager(&tablet, "Tablet");
        let plan = body_plan("g6-nonce-reuse", BODY);
        let batch_id = plan.batch_id().clone();
        let session = establish_approved_batch(
            &mut phone_runtime,
            &mut tablet_runtime,
            &phone,
            &tablet,
            plan,
            1_000,
        );
        let binding = ChunkBinding::new(
            session.session_id(),
            batch_id.as_str(),
            0,
            ATTACHMENT_SLOT_BODY,
            0,
        )
        .expect("binding builds");

        let first = phone_runtime
            .plan_batch_chunk(&binding, BODY)
            .expect("first seal plans");
        let drifted: Vec<u8> = BODY.iter().map(|byte| byte ^ 0x5A).collect();
        let second = phone_runtime.plan_batch_chunk(&binding, &drifted);

        if let Ok(second) = &second {
            // The sealed region follows the cleartext receipt prefix in both frames.
            let offset = receipt_prefix_len(first.frame().payload());
            let sealed_a = &first.frame().payload()[offset..offset + BODY.len()];
            let sealed_b = &second.frame().payload()[offset..offset + BODY.len()];
            let xor_of_ciphertexts: Vec<u8> = sealed_a
                .iter()
                .zip(sealed_b.iter())
                .map(|(a, b)| a ^ b)
                .collect();
            let xor_of_plaintexts: Vec<u8> = BODY
                .iter()
                .zip(drifted.iter())
                .map(|(a, b)| a ^ b)
                .collect();
            assert_eq!(
                xor_of_ciphertexts, xor_of_plaintexts,
                "two seals of one coordinate share the keystream: the nonce was reused"
            );
        }
        assert!(
            second.is_err(),
            "a coordinate re-planned with different bytes must be rejected — \
             re-sealing under the same deterministic nonce reuses the AEAD keystream"
        );
    }

    #[test]
    fn a_forged_cleartext_chunk_ack_is_accepted_as_a_durable_receipt() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (_phone_root, mut phone_runtime) = manager(&phone, "Phone");
        let (_tablet_root, mut tablet_runtime) = manager(&tablet, "Tablet");
        let plan = body_plan("g6-forged-ack", BODY);
        let batch_id = plan.batch_id().clone();
        let session = establish_approved_batch(
            &mut phone_runtime,
            &mut tablet_runtime,
            &phone,
            &tablet,
            plan,
            10_000,
        );
        let binding = ChunkBinding::new(
            session.session_id(),
            batch_id.as_str(),
            0,
            ATTACHMENT_SLOT_BODY,
            0,
        )
        .expect("binding builds");

        // A fake "receiver" on the real listener: read the Chunk frame, copy its cleartext
        // receipt prefix into a ChunkAck, and answer without ever running the manager.
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
            let chunk = stream.read_frame().expect("chunk frame reads");
            assert_eq!(chunk.kind(), FrameKind::Chunk);
            let receipt_len = receipt_prefix_len(chunk.payload());
            let forged =
                LanFrame::new(FrameKind::ChunkAck, chunk.payload()[..receipt_len].to_vec())
                    .expect("forged ack builds");
            stream.write_frame(&forged).expect("forged ack writes");
        });

        let send_plan = phone_runtime
            .plan_batch_chunk(&binding, BODY)
            .expect("chunk plans");
        let pool = LanConnectionPool::default();
        let mut drained = Vec::new();
        let send = pool.send_chunk(&send_plan, &mut drained);
        spoofer.join().expect("spoofer joins");

        // Receiver durable truth: nothing was ever staged or confirmed for the coordinate.
        assert_eq!(
            tablet_runtime
                .unconfirmed_batch_chunks(&batch_id, 0, ATTACHMENT_SLOT_BODY)
                .expect("receiver recovery reads"),
            vec![0],
            "the forged acknowledgement touched no durable receiver state"
        );
        assert!(
            send.is_err(),
            "an unauthenticated cleartext ChunkAck is not a sealed chunk response — \
             it must fail the send instead of retiring the window"
        );
        assert!(
            drained.is_empty(),
            "a response that cannot authenticate retires no pending send"
        );
    }

    #[test]
    fn a_cleartext_error_frame_is_charged_to_the_oldest_pending_batch() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (_phone_root, mut phone_runtime) = manager(&phone, "Phone");
        let (_tablet_root, mut tablet_runtime) = manager(&tablet, "Tablet");
        // Two approved batches ride the same session channel; only batch B is refused.
        let session = {
            let plan_a = body_plan("g6-blame-a", BODY);
            let session = establish_approved_batch(
                &mut phone_runtime,
                &mut tablet_runtime,
                &phone,
                &tablet,
                plan_a,
                20_000,
            );
            let plan_b = body_plan("g6-blame-b", BODY);
            thread::scope(|scope| {
                let receiver = scope.spawn(|| tablet_runtime.poll_listener(26_000));
                phone_runtime
                    .prepare_batch(session.session_id(), plan_b, 26_000)
                    .expect("second prepare sends");
                receiver
                    .join()
                    .expect("receiver joins")
                    .expect("second prepare handles");
            });
            thread::scope(|scope| {
                let receiver = scope.spawn(|| phone_runtime.poll_listener(26_001));
                tablet_runtime
                    .approve_batch(
                        session.session_id(),
                        &LanBatchId::parse("g6-blame-b").expect("batch id parses"),
                        ApprovedGeneration::capture(GENERATION).expect("generation captures"),
                        26_001,
                        60_000,
                    )
                    .expect("second approval sends");
                receiver
                    .join()
                    .expect("receiver joins")
                    .expect("second approval handles");
            });
            session
        };
        let batch_a = LanBatchId::parse("g6-blame-a").expect("batch id parses");
        let batch_b = LanBatchId::parse("g6-blame-b").expect("batch id parses");
        let binding_a = ChunkBinding::new(
            session.session_id(),
            batch_a.as_str(),
            0,
            ATTACHMENT_SLOT_BODY,
            0,
        )
        .expect("binding A builds");
        let binding_b = ChunkBinding::new(
            session.session_id(),
            batch_b.as_str(),
            0,
            ATTACHMENT_SLOT_BODY,
            0,
        )
        .expect("binding B builds");
        let plan_a = phone_runtime
            .plan_batch_chunk(&binding_a, BODY)
            .expect("chunk A plans");
        let plan_b = phone_runtime
            .plan_batch_chunk(&binding_b, BODY)
            .expect("chunk B plans");

        // Phase 1 — a forged cleartext Error: it names no receipt and authenticates nothing,
        // so it must fail the send without touching either batch's durable state.
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
            let _chunk_a = stream.read_frame().expect("chunk A reads");
            let _chunk_b = stream.read_frame().expect("chunk B reads");
            stream
                .write_frame(&cleartext_error_frame("lan_batch_rejected"))
                .expect("cleartext error writes");
        });
        let pool = LanConnectionPool::default();
        let mut drained = Vec::new();
        let forged_send = pool.send_chunks(&[plan_a.clone(), plan_b.clone()], &mut drained);
        spoofer.join().expect("spoofer joins");
        assert!(
            forged_send.is_err(),
            "a cleartext error frame is not a sealed chunk response and cannot retire a send"
        );
        assert!(
            drained.is_empty(),
            "a frame that names no pending receipt drains nothing"
        );
        let failure_code_of = |runtime: &mut LanServiceManager, batch: &LanBatchId| {
            runtime
                .inbox(27_000)
                .expect("inbox builds")
                .outgoing_batches()
                .iter()
                .find(|outgoing| outgoing.batch_id() == batch)
                .expect("batch is tracked")
                .failure_code()
                .map(str::to_owned)
        };
        assert_eq!(failure_code_of(&mut phone_runtime, &batch_a), None);
        assert_eq!(failure_code_of(&mut phone_runtime, &batch_b), None);

        // Phase 2 — a refusal that *is* authentic: the real receiver corrupts chunk B in place
        // and answers with its sealed refusal, bound to B's receipt. The window must charge B,
        // never the oldest pending send.
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
                    .handle_inbound_frame(source, &chunk_a, 26_500)
                    .expect("chunk A is answered")
                    .expect("an acknowledgement replies");
                stream.write_frame(&reply_a).expect("ack A writes");
                let chunk_b = stream.read_frame().expect("chunk B reads");
                let mut corrupted_b = chunk_b.payload().to_vec();
                let offset = receipt_prefix_len(&corrupted_b);
                corrupted_b[offset] ^= 0x01;
                let corrupt_frame =
                    LanFrame::new(FrameKind::Chunk, corrupted_b).expect("corrupt frame builds");
                let refusal_b = tablet_runtime
                    .handle_inbound_frame(source, &corrupt_frame, 26_501)
                    .expect("corrupt chunk B is answered")
                    .expect("a bound refusal replies");
                assert_eq!(refusal_b.kind(), FrameKind::Error);
                stream.write_frame(&refusal_b).expect("refusal B writes");
            });
            let mut drained = Vec::new();
            let send = pool.send_chunks(&[plan_a, plan_b], &mut drained);
            spoofer.join().expect("spoofer joins");
            (send, drained)
        });
        send.expect("both receipts retire: A acked, B refused");
        assert_eq!(
            drained.len(),
            2,
            "the window drains A's ack then B's refusal"
        );
        assert_eq!(drained[0].0.batch_id(), &batch_a);
        assert_eq!(drained[1].0.batch_id(), &batch_b);
        assert_eq!(drained[1].1.kind(), FrameKind::Error);

        let refusal_error = phone_runtime
            .apply_chunk_receipt(&drained[1].0, &drained[1].1, 27_000)
            .expect_err("an authenticated refusal surfaces as an error to the caller");
        assert_eq!(refusal_error.code(), "lan_chunk_open_failed");
        phone_runtime
            .apply_chunk_receipt(&drained[0].0, &drained[0].1, 27_000)
            .expect("A's authenticated ack applies");
        assert_eq!(
            failure_code_of(&mut phone_runtime, &batch_a),
            None,
            "a refusal bound to B's receipt was charged to the oldest pending plan — \
             batch A is terminally failed although the refusal belonged to B"
        );
        assert_eq!(
            failure_code_of(&mut phone_runtime, &batch_b).as_deref(),
            Some("lan_chunk_open_failed"),
            "the sealed refusal charged the batch its receipt names"
        );
    }

    #[test]
    fn a_drained_refusal_is_lost_when_a_later_window_read_fails() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (_phone_root, mut phone_runtime) = manager(&phone, "Phone");
        let (_tablet_root, mut tablet_runtime) = manager(&tablet, "Tablet");
        // A two-chunk body forces a second drain after the refusal was already consumed.
        let wide: Vec<u8> = vec![0xAB; lomo_lan::RUNTIME_CHUNK_PLAINTEXT_BYTES + 16];
        let plan = body_plan("g6-lost-refusal", &wide);
        let batch_id = plan.batch_id().clone();
        let session = establish_approved_batch(
            &mut phone_runtime,
            &mut tablet_runtime,
            &phone,
            &tablet,
            plan,
            30_000,
        );
        let binding_c0 = ChunkBinding::new(
            session.session_id(),
            batch_id.as_str(),
            0,
            ATTACHMENT_SLOT_BODY,
            0,
        )
        .expect("chunk 0 binds");
        let binding_c1 = ChunkBinding::new(
            session.session_id(),
            batch_id.as_str(),
            0,
            ATTACHMENT_SLOT_BODY,
            1,
        )
        .expect("chunk 1 binds");
        let plan_c0 = phone_runtime
            .plan_batch_chunk(
                &binding_c0,
                &wide[..lomo_lan::RUNTIME_CHUNK_PLAINTEXT_BYTES],
            )
            .expect("chunk 0 plans");
        let plan_c1 = phone_runtime
            .plan_batch_chunk(
                &binding_c1,
                &wide[lomo_lan::RUNTIME_CHUNK_PLAINTEXT_BYTES..],
            )
            .expect("chunk 1 plans");

        let tablet_listener = tablet_runtime
            .clone_listener()
            .expect("listener clones")
            .expect("listener is bound");
        // The real receiver answers chunk 0 with its sealed refusal, then dies before chunk 1:
        // the refusal already drained is an observed fact the socket death cannot retract.
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
                let chunk0 = stream.read_frame().expect("chunk 0 reads");
                let mut corrupted = chunk0.payload().to_vec();
                let offset = receipt_prefix_len(&corrupted);
                corrupted[offset] ^= 0x01;
                let corrupt_frame =
                    LanFrame::new(FrameKind::Chunk, corrupted).expect("corrupt frame builds");
                let refusal = tablet_runtime
                    .handle_inbound_frame(source, &corrupt_frame, 30_500)
                    .expect("corrupt chunk 0 is answered")
                    .expect("a bound refusal replies");
                assert_eq!(refusal.kind(), FrameKind::Error);
                stream.write_frame(&refusal).expect("refusal writes");
                let _chunk1 = stream.read_frame().expect("chunk 1 reads");
                // Dropping the socket mid-window: the second drain hits EOF after the refusal
                // for chunk 0 was already retired into `drained`.
            });
            let pool = LanConnectionPool::default();
            let mut drained = Vec::new();
            let send = pool.send_chunks(&[plan_c0, plan_c1], &mut drained);
            spoofer.join().expect("spoofer joins");
            (send, drained)
        });
        assert!(
            send.is_err(),
            "the truncated channel surfaces a network error"
        );
        assert_eq!(
            drained.len(),
            1,
            "the refusal drained before the socket died survives the send error"
        );
        let apply_error = phone_runtime
            .apply_chunk_receipt(&drained[0].0, &drained[0].1, 31_000)
            .expect_err("the drained refusal applies as a durable refusal");
        assert_eq!(apply_error.code(), "lan_chunk_open_failed");
        let failure = phone_runtime
            .inbox(31_000)
            .expect("inbox builds")
            .outgoing_batches()
            .iter()
            .find(|batch| batch.batch_id() == &batch_id)
            .expect("batch is tracked")
            .failure_code()
            .map(str::to_owned);
        assert!(
            failure.is_some(),
            "the refusal drained before the socket died was dropped with the \
             send error — the sender never applies the receiver's terminal decision"
        );
    }

    #[test]
    fn a_corrupted_unconfirmed_staged_chunk_can_never_be_retransmitted() {
        let root = tempfile::tempdir().expect("journal root exists");
        let paths = LanJournalPaths::new(root.path()).expect("journal paths open");
        let mut journal = LanJournal::open(paths.clone()).expect("journal opens");
        let device_key = TestIdentity::generate();
        let session = lomo_lan::LanSessionId::parse("0123456789abcdef0123456789abcdef")
            .expect("session id parses");
        let plan = body_plan("g6-staged-brick", BODY);
        let batch_id = plan.batch_id().clone();
        journal
            .store_batch(LanDurableBatch::pending(
                plan,
                session.clone(),
                DeviceId::derive(&device_key.public),
                DisplayName::parse("Sender").expect("name parses"),
            ))
            .expect("pending batch stores");
        let binding = ChunkBinding::new(&session, batch_id.as_str(), 0, ATTACHMENT_SLOT_BODY, 0)
            .expect("binding builds");
        journal
            .stage_chunk(&binding, BODY)
            .expect("first staging persists");

        // Same-length corruption of the staged file (bit rot / torn prior write).
        let staged = paths
            .root()
            .join("payloads")
            .join(batch_id.as_str())
            .join(format!("0-{ATTACHMENT_SLOT_BODY}"))
            .join("0.chunk");
        let corrupted: Vec<u8> = BODY.iter().map(|byte| byte ^ 0xFF).collect();
        std::fs::write(&staged, &corrupted).expect("corruption writes");

        let restage = journal.stage_chunk(&binding, BODY);
        assert!(
            restage.is_err(),
            "the corrupted staged file is seen as a different-bytes replay"
        );
        // Reopen: the poisoned coordinate survives restart — nothing ever confirmed it.
        let mut reopened = LanJournal::open(paths).expect("journal reopens");
        let after_restart = reopened.stage_chunk(&binding, BODY);
        assert!(
            after_restart.is_ok(),
            "a never-confirmed staged file corrupted on disk must be reclaimed \
             for retransmission, not pinned forever as `lan_chunk_replayed_with_different_bytes` \
             (the receiver answers every retry with a terminal refusal to the sender)"
        );
    }

    #[test]
    fn dead_channel_pending_receipts_are_replayed_not_forgotten() {
        // Sanity rail: an interrupted window must leave the sender's unconfirmed truth intact
        // so a later send retries the same coordinates (receiver journal is the durable owner).
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (_phone_root, mut phone_runtime) = manager(&phone, "Phone");
        let (_tablet_root, mut tablet_runtime) = manager(&tablet, "Tablet");
        let plan = body_plan("g6-dead-channel", BODY);
        let batch_id = plan.batch_id().clone();
        let session = establish_approved_batch(
            &mut phone_runtime,
            &mut tablet_runtime,
            &phone,
            &tablet,
            plan,
            40_000,
        );
        let binding = ChunkBinding::new(
            session.session_id(),
            batch_id.as_str(),
            0,
            ATTACHMENT_SLOT_BODY,
            0,
        )
        .expect("binding builds");
        let send_plan = phone_runtime
            .plan_batch_chunk(&binding, BODY)
            .expect("chunk plans");

        // Server accepts the channel and dies without answering: the read deadline errors and
        // the channel must be discarded, while receiver truth still demands the chunk.
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
            // Dropping the socket with the chunk unanswered: the sender's window read hits EOF
            // and the channel must be discarded while durable truth keeps the coordinate.
        });
        let pool = LanConnectionPool::default();
        let mut drained = Vec::new();
        let outcome = pool.send_chunk(&send_plan, &mut drained);
        spoofer.join().expect("spoofer joins");
        assert!(outcome.is_err(), "a silent channel must fail the send");
        assert_eq!(
            tablet_runtime
                .unconfirmed_batch_chunks(&batch_id, 0, ATTACHMENT_SLOT_BODY)
                .expect("receiver recovery reads"),
            vec![0],
            "the un-acked coordinate stays retransmittable on durable truth"
        );
        assert_eq!(
            phone_runtime
                .inbox(46_000)
                .expect("inbox builds")
                .outgoing_batches()
                .first()
                .expect("outgoing batch exists")
                .drive(),
            LanOutgoingBatchDrive::Sendable,
            "a dead channel never terminally fails the batch"
        );
    }
}
