//! Behavior Contract (Stage-6 P6-07 blocking transport + hermetic two-endpoint matrix)
//!
//! Capability: two `lomo-lan` endpoints complete a real pairing, session authentication, sealed
//! chunk transfer and resume over real loopback TCP sockets, with every socket operation bounded by
//! a deadline and every frame header validated before the declared payload is reserved.
//!
//! Scenarios:
//! - Given two endpoints on loopback, when a control frame is written, then the peer reads it back
//!   with the same kind and payload.
//! - Given a header declaring a length above the kind ceiling, when read, then it fails closed
//!   before reserving the declared length, and the reader does not hang.
//! - Given a peer that closes mid-frame, when read, then it reports incomplete rather than
//!   inventing a frame.
//! - Given a peer that sends nothing, when read, then the read deadline fires with a typed
//!   transient network error instead of pinning the worker.
//! - Given a zero deadline, when built, then it is rejected.
//! - Given a full two-endpoint exchange, when both sides pair, authenticate a session, seal and
//!   open chunks and commit, then the short codes match, the peer is stored, every chunk
//!   round-trips, and the item commits exactly once.
//! - Given a transfer that died after some chunks, when the same session resumes, then only the
//!   unconfirmed chunks travel and the reassembled body still matches the plan digest.
//!
//! Observable outcomes: bytes read back over the socket, `LomoError` code/category, derived short
//! codes, stored peer records, reassembled body digest, journal confirmed ranges.
//!
//! Excludes: NSD discovery, Android network selection, Keystore, FFI, Kotlin, real Wi-Fi.

#![deny(unsafe_code)]

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::indexing_slicing,
    reason = "contract tests fail closed with panics and index fixture buffers of known size"
)]
mod tests {
    use std::io::Write;
    use std::net::{Ipv4Addr, SocketAddr, TcpStream};
    use std::time::Duration;

    use aws_lc_rs::agreement;
    use aws_lc_rs::encoding::AsBigEndian;
    use aws_lc_rs::rand::SystemRandom;
    use aws_lc_rs::signature::{ECDSA_P256_SHA256_ASN1_SIGNING, EcdsaKeyPair, KeyPair};
    use lomo_core::ErrorCategory;
    use lomo_lan::{
        ATTACHMENT_SLOT_BODY, ChunkBinding, DevicePublicKey, DeviceSigner, DisplayName, FrameKind,
        LAN_FRAME_MAGIC, LAN_PROTOCOL_VERSION, LanBatchId, LanBatchPlan, LanBatchSnapshot,
        LanDeadlines, LanFrame, LanItemOutcome, LanItemPlan, LanJournal, LanJournalPaths,
        LanSessionId, MAX_CONTROL_PAYLOAD_BYTES, PairingRole, PairingTranscript, SessionKey,
        SessionTranscript, accept_peer, bind_listener, connect_peer, derive_pairing_code,
        verify_pairing_confirmation,
    };
    use sha2::{Digest, Sha256};

    const BODY: &str = "# 跨设备 memo\n\n这是一段足够长的正文，用来切成多个 chunk。\n";
    const CHUNK_BYTES: usize = 16;
    const SESSION_HEX: &str = "0123456789abcdef0123456789abcdef";

    fn deadlines() -> LanDeadlines {
        LanDeadlines::new(Duration::from_secs(5), Duration::from_secs(5))
            .expect("fixture deadlines are non-zero")
    }

    struct TestSigner {
        key_pair: EcdsaKeyPair,
        public_key: DevicePublicKey,
        rng: SystemRandom,
    }

    impl TestSigner {
        fn generate() -> Self {
            let key_pair = EcdsaKeyPair::generate(&ECDSA_P256_SHA256_ASN1_SIGNING)
                .expect("host key pair generates");
            let x963: aws_lc_rs::encoding::EcPublicKeyUncompressedBin<'_> = key_pair
                .public_key()
                .as_be_bytes()
                .expect("public key exports as X9.62 bytes");
            let public_key = DevicePublicKey::parse(x963.as_ref()).expect("valid P-256 point");
            Self {
                key_pair,
                public_key,
                rng: SystemRandom::new(),
            }
        }
    }

    impl DeviceSigner for TestSigner {
        fn public_key(&self) -> &DevicePublicKey {
            &self.public_key
        }

        fn sign(&self, transcript: &[u8]) -> Result<Vec<u8>, lomo_core::LomoError> {
            self.key_pair
                .sign(&self.rng, transcript)
                .map(|signature| signature.as_ref().to_vec())
                .map_err(|_error| {
                    lomo_lan::lan_authentication("lan_device_sign_failed", "host signer failed")
                })
        }
    }

    struct Ephemeral {
        private: agreement::PrivateKey,
        public: Vec<u8>,
    }

    impl Ephemeral {
        fn generate() -> Self {
            let private =
                agreement::PrivateKey::generate(&agreement::X25519).expect("X25519 generates");
            let public = private
                .compute_public_key()
                .expect("public key derives")
                .as_ref()
                .to_vec();
            Self { private, public }
        }

        fn agree(&self, peer_public: &[u8]) -> Vec<u8> {
            agreement::agree(
                &self.private,
                agreement::UnparsedPublicKey::new(&agreement::X25519, peer_public),
                (),
                |secret| Ok(secret.to_vec()),
            )
            .expect("honest agreement succeeds")
        }
    }

    fn name(value: &str) -> DisplayName {
        DisplayName::parse(value).expect("fixture display name is valid")
    }

    fn digest_hex(bytes: &[u8]) -> String {
        format!("{:x}", Sha256::digest(bytes))
    }

    /// Binds a loopback listener and returns it with its actual address.
    fn loopback() -> (std::net::TcpListener, SocketAddr) {
        let listener =
            bind_listener(SocketAddr::from((Ipv4Addr::LOCALHOST, 0))).expect("loopback binds");
        let address = listener.local_addr().expect("listener has an address");
        (listener, address)
    }

    #[test]
    fn a_control_frame_round_trips_over_a_real_loopback_socket() {
        let (listener, address) = loopback();
        let server = std::thread::spawn(move || {
            let (mut peer, _address) =
                accept_peer(&listener, deadlines()).expect("accept succeeds");
            peer.read_frame().expect("server reads the frame")
        });

        let mut client =
            connect_peer(address, Duration::from_secs(5), deadlines()).expect("client connects");
        let sent = LanFrame::new(FrameKind::BatchApprove, b"approve-batch-1".to_vec())
            .expect("control payload is in range");
        client.write_frame(&sent).expect("client writes the frame");

        let received = server.join().expect("server thread completes");
        assert_eq!(received.kind(), FrameKind::BatchApprove);
        assert_eq!(received.payload(), b"approve-batch-1");
    }

    #[test]
    fn an_oversize_declared_length_fails_closed_without_reserving_it() {
        let (listener, address) = loopback();
        let server = std::thread::spawn(move || {
            let (mut peer, _address) =
                accept_peer(&listener, deadlines()).expect("accept succeeds");
            peer.read_frame()
                .expect_err("an oversize declared length must fail closed")
        });

        let mut raw = TcpStream::connect(address).expect("raw client connects");
        // A control-kind header claiming a payload far above every ceiling.
        let mut header = Vec::new();
        header.extend_from_slice(&LAN_FRAME_MAGIC);
        header.extend_from_slice(&LAN_PROTOCOL_VERSION.to_be_bytes());
        header.extend_from_slice(&FrameKind::PairHello.code().to_be_bytes());
        header.extend_from_slice(&u32::MAX.to_be_bytes());
        raw.write_all(&header).expect("header is written");
        raw.flush().expect("header is flushed");

        let error = server.join().expect("server thread completes");
        assert_eq!(error.category(), ErrorCategory::ResourceLimit);
        assert_eq!(error.code(), "lan_frame_payload_too_large");
    }

    #[test]
    fn a_peer_that_closes_mid_frame_reports_incomplete() {
        let (listener, address) = loopback();
        let server = std::thread::spawn(move || {
            let (mut peer, _address) =
                accept_peer(&listener, deadlines()).expect("accept succeeds");
            peer.read_frame()
                .expect_err("a stream that ends mid-frame must not decode")
        });

        let mut raw = TcpStream::connect(address).expect("raw client connects");
        let mut header = Vec::new();
        header.extend_from_slice(&LAN_FRAME_MAGIC);
        header.extend_from_slice(&LAN_PROTOCOL_VERSION.to_be_bytes());
        header.extend_from_slice(&FrameKind::ChunkAck.code().to_be_bytes());
        header.extend_from_slice(&64_u32.to_be_bytes());
        raw.write_all(&header).expect("header is written");
        raw.write_all(&[0_u8; 8])
            .expect("a partial payload is written");
        raw.flush().expect("bytes are flushed");
        drop(raw);

        let error = server.join().expect("server thread completes");
        assert_eq!(error.code(), "lan_frame_incomplete");
    }

    #[test]
    fn a_silent_peer_trips_the_read_deadline_instead_of_pinning_the_worker() {
        let (listener, address) = loopback();
        let short = LanDeadlines::new(Duration::from_millis(150), Duration::from_secs(5))
            .expect("short deadlines are non-zero");
        let server = std::thread::spawn(move || {
            let (mut peer, _address) = accept_peer(&listener, short).expect("accept succeeds");
            peer.read_frame()
                .expect_err("a silent peer must trip the read deadline")
        });

        let held = TcpStream::connect(address).expect("client connects and stays silent");
        let error = server.join().expect("server thread completes");
        drop(held);

        assert_eq!(error.category(), ErrorCategory::Network);
        assert_eq!(error.code(), "lan_deadline_exceeded");
        assert_eq!(
            error.retry_disposition(),
            lomo_core::RetryDisposition::Transient,
            "a fired deadline is transient, not a permanent failure"
        );
    }

    #[test]
    fn a_zero_deadline_is_rejected() {
        let error = LanDeadlines::new(Duration::ZERO, Duration::from_secs(1))
            .expect_err("a zero deadline means block forever and must be rejected");
        assert_eq!(error.code(), "lan_deadline_invalid");
    }

    #[test]
    fn a_control_kind_cannot_claim_the_chunk_ceiling_over_the_wire() {
        let (listener, address) = loopback();
        let server = std::thread::spawn(move || {
            let (mut peer, _address) =
                accept_peer(&listener, deadlines()).expect("accept succeeds");
            peer.read_frame()
                .expect_err("a control kind must keep the control ceiling on the wire")
        });

        let mut raw = TcpStream::connect(address).expect("raw client connects");
        let mut header = Vec::new();
        header.extend_from_slice(&LAN_FRAME_MAGIC);
        header.extend_from_slice(&LAN_PROTOCOL_VERSION.to_be_bytes());
        header.extend_from_slice(&FrameKind::BatchPrepare.code().to_be_bytes());
        let over_control = u32::try_from(MAX_CONTROL_PAYLOAD_BYTES + 1).expect("fits u32");
        header.extend_from_slice(&over_control.to_be_bytes());
        raw.write_all(&header).expect("header is written");
        raw.flush().expect("header is flushed");

        let error = server.join().expect("server thread completes");
        assert_eq!(error.code(), "lan_frame_payload_too_large");
    }

    /// Completes an honest pairing and returns the stored peer plus both endpoints' short codes.
    fn pair_endpoints(
        initiator: &TestSigner,
        responder: &TestSigner,
    ) -> (lomo_lan::PeerRecord, String, String) {
        let initiator_eph = Ephemeral::generate();
        let responder_eph = Ephemeral::generate();
        let initiator_view = PairingTranscript::build(
            initiator.public_key(),
            &name("Phone"),
            &initiator_eph.public,
            responder.public_key(),
            &name("Tablet"),
            &responder_eph.public,
            &initiator_eph.agree(&responder_eph.public),
        )
        .expect("initiator transcript builds");
        let responder_view = PairingTranscript::build(
            initiator.public_key(),
            &name("Phone"),
            &initiator_eph.public,
            responder.public_key(),
            &name("Tablet"),
            &responder_eph.public,
            &responder_eph.agree(&initiator_eph.public),
        )
        .expect("responder transcript builds");

        let confirmation = initiator
            .sign(initiator_view.bytes())
            .expect("initiator confirms with its device key");
        let peer = verify_pairing_confirmation(
            &responder_view,
            initiator.public_key(),
            &name("Phone"),
            &confirmation,
            1_700_000_000_000,
        )
        .expect("the responder stores the initiator as a peer");

        (
            peer,
            derive_pairing_code(&initiator_view),
            derive_pairing_code(&responder_view),
        )
    }

    /// Authenticates a session between the two endpoints and returns both derived keys.
    fn authenticate_session(
        opener: &TestSigner,
        accepter: &TestSigner,
        session_id: &LanSessionId,
    ) -> (SessionKey, SessionKey) {
        let opener_eph = Ephemeral::generate();
        let accepter_eph = Ephemeral::generate();
        let transcript = SessionTranscript::build(
            session_id,
            opener.public_key(),
            &opener_eph.public,
            accepter.public_key(),
            &accepter_eph.public,
        )
        .expect("session transcript builds");

        let signature = opener
            .sign(transcript.bytes())
            .expect("opener signs the session transcript");
        transcript
            .verify_peer(opener.public_key(), &signature)
            .expect("the receiver authenticates the opener against the stored peer key");

        let sender = SessionKey::derive(&transcript, &opener_eph.agree(&accepter_eph.public))
            .expect("sender derives the session key");
        let receiver = SessionKey::derive(&transcript, &accepter_eph.agree(&opener_eph.public))
            .expect("receiver derives the session key");
        (sender, receiver)
    }

    /// The full hermetic matrix: two endpoints pair, authenticate a session, transfer a chunked
    /// body over real sockets, resume a died transfer sending only the unconfirmed chunks, and
    /// commit exactly once.
    #[test]
    fn two_endpoints_pair_authenticate_transfer_and_resume_over_real_sockets() {
        let initiator = TestSigner::generate();
        let responder = TestSigner::generate();

        let (stored_peer, initiator_code, responder_code) = pair_endpoints(&initiator, &responder);
        assert_eq!(
            initiator_code, responder_code,
            "an honest pairing shows one code on both devices"
        );
        assert_eq!(
            PairingRole::Initiator.peer(initiator.public_key(), responder.public_key()),
            responder.public_key(),
            "the initiator's peer is the responder"
        );

        let root = tempfile::tempdir().expect("app-private root is creatable");
        let paths = LanJournalPaths::new(root.path()).expect("journal paths build");
        let mut journal = LanJournal::open(paths.clone()).expect("journal opens");
        journal
            .store_peer(stored_peer.clone())
            .expect("peer is stored durably");
        stored_peer
            .assert_connectable()
            .expect("a freshly paired peer may open a session");

        let session_id = LanSessionId::parse(SESSION_HEX).expect("session id is valid");
        let (sender_key, receiver_key) = authenticate_session(&initiator, &responder, &session_id);

        let batch_id = LanBatchId::parse("batch-wire").expect("batch id is valid");
        let item = LanItemPlan::new(
            &batch_id,
            0,
            1_700_000_000_000,
            &digest_hex(BODY.as_bytes()),
            BODY.len() as u64,
            "跨设备 memo",
            Vec::new(),
        )
        .expect("item plan is valid");
        let plan = LanBatchPlan::new(batch_id.clone(), vec![item]).expect("batch is in limits");
        let chunks: Vec<Vec<u8>> = BODY
            .as_bytes()
            .chunks(CHUNK_BYTES)
            .map(<[u8]>::to_vec)
            .collect();
        let total_chunks = u32::try_from(chunks.len()).expect("chunk count fits u32");
        assert!(total_chunks > 3, "the fixture body spans several chunks");

        // Attempt 1 dies after two chunks.
        let delivered = transfer_chunks(&sender_key, &receiver_key, &session_id, &chunks, &[0, 1]);
        for index in [0_u32, 1] {
            journal
                .confirm_chunk(&binding(&session_id, index))
                .expect("a delivered chunk is confirmed durably");
        }
        assert_eq!(delivered.len(), 2, "attempt 1 delivered only two chunks");

        // A fresh journal open must ask only for the unconfirmed chunks.
        let resumed_journal = LanJournal::open(paths).expect("journal reopens after process death");
        let remaining = resumed_journal.unconfirmed_chunk_indices(
            &batch_id,
            0,
            ATTACHMENT_SLOT_BODY,
            total_chunks,
        );
        assert_eq!(
            remaining,
            (2..total_chunks).collect::<Vec<_>>(),
            "resume must retransmit exactly the chunks that were never confirmed"
        );

        let resumed = transfer_chunks(&sender_key, &receiver_key, &session_id, &chunks, &remaining);
        assert_eq!(resumed.len(), remaining.len());

        let mut reassembled = Vec::new();
        for (_index, bytes) in delivered.iter().chain(resumed.iter()) {
            reassembled.extend_from_slice(bytes);
        }
        assert_eq!(
            digest_hex(&reassembled),
            plan.items()[0].content_digest(),
            "the reassembled body must match the digest the sender planned"
        );

        let mut snapshot = LanBatchSnapshot::pending(&plan);
        snapshot
            .record(
                plan.items()[0].item_id(),
                LanItemOutcome::committed("memo-1"),
            )
            .expect("the item commits");
        let replay = snapshot
            .record(
                plan.items()[0].item_id(),
                LanItemOutcome::committed("memo-2"),
            )
            .expect("a replay is idempotent");
        assert_eq!(replay, LanItemOutcome::committed("memo-1"));
        assert!(snapshot.is_complete() && !snapshot.is_partially_failed());
        assert_eq!(snapshot.committed_memo_ids(), vec!["memo-1"]);
    }

    fn binding(session_id: &LanSessionId, chunk_index: u32) -> ChunkBinding {
        ChunkBinding::new(
            session_id,
            "batch-wire",
            0,
            ATTACHMENT_SLOT_BODY,
            chunk_index,
        )
        .expect("fixture binding is valid")
    }

    /// Seals the requested chunk indices, ships them over a real loopback socket as `Chunk` frames,
    /// and returns what the receiver actually opened.
    fn transfer_chunks(
        sender_key: &SessionKey,
        receiver_key: &SessionKey,
        session_id: &LanSessionId,
        chunks: &[Vec<u8>],
        indices: &[u32],
    ) -> Vec<(u32, Vec<u8>)> {
        let (listener, address) = loopback();
        let expected = indices.len();
        let receiver_session = session_id.clone();
        let receiver = std::thread::spawn(move || {
            let (mut peer, _address) =
                accept_peer(&listener, deadlines()).expect("accept succeeds");
            let mut opened = Vec::new();
            for _ in 0..expected {
                let frame = peer.read_frame().expect("receiver reads a chunk frame");
                assert_eq!(frame.kind(), FrameKind::Chunk);
                let payload = frame.into_payload();
                let index_bytes: [u8; 4] = payload[0..4].try_into().expect("index prefix present");
                let chunk_index = u32::from_be_bytes(index_bytes);
                opened.push((chunk_index, payload[4..].to_vec(), receiver_session.clone()));
            }
            opened
        });

        let mut sender =
            connect_peer(address, Duration::from_secs(5), deadlines()).expect("sender connects");
        for index in indices {
            let plaintext = chunks
                .get(*index as usize)
                .expect("the index is inside the chunk list")
                .clone();
            let sealed = sender_key
                .seal_chunk(&binding(session_id, *index), plaintext)
                .expect("chunk seals");
            let mut payload = index.to_be_bytes().to_vec();
            payload.extend_from_slice(&sealed);
            let frame = LanFrame::new(FrameKind::Chunk, payload).expect("chunk frame is in range");
            sender.write_frame(&frame).expect("chunk frame is written");
        }

        receiver
            .join()
            .expect("receiver thread completes")
            .into_iter()
            .map(|(chunk_index, sealed, session)| {
                let opened = receiver_key
                    .open_chunk(&binding(&session, chunk_index), sealed)
                    .expect("the receiver opens the chunk under the same binding");
                (chunk_index, opened)
            })
            .collect()
    }
}
