//! Behavior Contract (Stage-6 P6-03 session authentication, key derivation and directional AEAD)
//!
//! Capability: each connection performs mutual device-signature authentication over a fresh session
//! transcript, derives a session key with HKDF-SHA256, and seals chunks and control frames with
//! ChaCha20-Poly1305 under versioned, directional keys. Chunk nonces are bound to session, batch,
//! item, attachment slot and chunk index; control AAD covers frame kind, session, batch, sequence
//! and declared length. Replayed session ids and replayed chunks are rejected against a durable
//! ledger. Exhausting a nonce counter requires a new crypto session. Owning layer: `lomo-lan`.
//! Priority: P0.
//!
//! Scenarios:
//! - Given both endpoints of an honest session, when each derives the session key, then the keys
//!   match and differ from another session's key.
//! - Given a session transcript, when a peer signs it, then the other side verifies it under the
//!   stored peer key; a signature over another transcript or under another key fails.
//! - Given a sealed chunk, when opened with the same session/batch/item/slot/index/direction, then
//!   the plaintext round-trips.
//! - Given a sealed chunk, when opened under any *different* binding field, then it fails closed.
//! - Given a tampered ciphertext or tag, when opened, then it fails closed.
//! - Given two distinct chunks in one session, then their nonces differ.
//! - Given two batches with identical chunk coordinates, when sealed, then the keystreams differ.
//! - Given both directions sealing the same coordinates, when compared, then the keystreams differ.
//! - Given a control payload, when sealed, then the wire bytes are not plaintext; opening under a
//!   tampered kind, batch, header field or declared length fails closed.
//! - Given the last usable chunk or control sequence, when a successor is requested, then it fails
//!   closed and recovery reseals the original binding to identical ciphertext.
//! - Given a session id already seen, when accepted again, then the replay ledger rejects it.
//! - Given a chunk index already confirmed, when replayed, then the ledger rejects it and the
//!   confirmed range is unchanged.
//!
//! Observable outcomes: derived key equality without exporting key bytes, signature verification
//! results, sealed/opened bytes, nonce values, ledger accept/reject decisions and confirmed ranges,
//! `LomoError` code/category.
//!
//! TDD proof: `cargo test -p lomo-lan --test session_crypto_contract --locked` RED on missing
//! directional keys / HMAC-only control, GREEN after AEAD + versioned directional HKDF.
//!
//! Excludes: sockets, batch preview policy, journal file durability, Kotlin adapters.

#![deny(unsafe_code)]

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::indexing_slicing,
    reason = "contract tests fail closed with panics and index fixed-size key material"
)]
mod tests {
    use aws_lc_rs::rand::SystemRandom;
    use aws_lc_rs::signature::{ECDSA_P256_SHA256_ASN1_SIGNING, EcdsaKeyPair, KeyPair};
    use aws_lc_rs::{agreement, encoding::AsBigEndian};
    use lomo_core::ErrorCategory;
    use lomo_lan::{
        AEAD_TAG_BYTES, ATTACHMENT_SLOT_BODY, ChunkBinding, ControlBinding, DevicePublicKey,
        DeviceSigner, FrameKind, LanDirection, LanSessionId, ReplayLedger, SessionControlKind,
        SessionKey, SessionTranscript,
    };

    struct TestSigner {
        key_pair: EcdsaKeyPair,
        public_key: DevicePublicKey,
        rng: SystemRandom,
    }

    impl TestSigner {
        fn generate() -> Self {
            let key_pair = EcdsaKeyPair::generate(&ECDSA_P256_SHA256_ASN1_SIGNING)
                .expect("host test key pair generates");
            let x963: aws_lc_rs::encoding::EcPublicKeyUncompressedBin<'_> = key_pair
                .public_key()
                .as_be_bytes()
                .expect("public key exports as X9.62 uncompressed bytes");
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

    struct Session {
        opener: TestSigner,
        responder: TestSigner,
        transcript: SessionTranscript,
        opener_key: SessionKey,
        responder_key: SessionKey,
        id: LanSessionId,
    }

    fn honest_session(session_id: &str) -> Session {
        let opener = TestSigner::generate();
        let responder = TestSigner::generate();
        let opener_eph = Ephemeral::generate();
        let responder_eph = Ephemeral::generate();
        let session_id = LanSessionId::parse(session_id).expect("fixture session id is valid");

        let transcript = SessionTranscript::build(
            &session_id,
            opener.public_key(),
            &opener_eph.public,
            responder.public_key(),
            &responder_eph.public,
        )
        .expect("session transcript builds");

        let opener_key = SessionKey::derive(&transcript, &opener_eph.agree(&responder_eph.public))
            .expect("opener derives the session key");
        let responder_key =
            SessionKey::derive(&transcript, &responder_eph.agree(&opener_eph.public))
                .expect("responder derives the session key");

        Session {
            opener,
            responder,
            transcript,
            opener_key,
            responder_key,
            id: session_id,
        }
    }

    fn binding(session: &Session, item: u16, slot: u16, chunk: u32) -> ChunkBinding {
        ChunkBinding::new(&session.id, "batch-1", item, slot, chunk)
            .expect("fixture binding is valid")
    }

    #[test]
    fn both_endpoints_derive_the_same_session_key_and_sessions_do_not_share_keys() {
        let session = honest_session("0123456789abcdef0123456789abcdef");
        assert!(
            session
                .opener_key
                .derived_material_matches(&session.responder_key),
            "an honest session derives one key on both ends"
        );

        let other = honest_session("fedcba9876543210fedcba9876543210");
        assert!(
            !session
                .opener_key
                .derived_material_matches(&other.opener_key),
            "distinct sessions must not share a key"
        );
    }

    #[test]
    fn session_signature_authenticates_the_peer_and_rejects_substitution() {
        let session = honest_session("0123456789abcdef0123456789abcdef");
        let signature = session
            .opener
            .sign(session.transcript.bytes())
            .expect("opener signs the session transcript");

        session
            .transcript
            .verify_peer(session.opener.public_key(), &signature)
            .expect("the responder authenticates the opener");

        let error = session
            .transcript
            .verify_peer(session.responder.public_key(), &signature)
            .expect_err("a signature under another key must not authenticate that key");
        assert_eq!(error.category(), ErrorCategory::Authentication);
        assert_eq!(error.code(), "lan_session_signature_invalid");

        let other = honest_session("fedcba9876543210fedcba9876543210");
        let foreign = session
            .opener
            .sign(other.transcript.bytes())
            .expect("opener signs a different transcript");
        let error = session
            .transcript
            .verify_peer(session.opener.public_key(), &foreign)
            .expect_err("a signature over another transcript must be rejected");
        assert_eq!(error.code(), "lan_session_signature_invalid");
    }

    #[test]
    fn sealed_chunks_round_trip_under_the_same_binding() {
        let session = honest_session("0123456789abcdef0123456789abcdef");
        let bind = binding(&session, 3, ATTACHMENT_SLOT_BODY, 7);
        let plaintext = b"# memo body chunk".to_vec();

        let sealed = session
            .opener_key
            .seal_chunk(LanDirection::Forward, &bind, plaintext.clone())
            .expect("sealing succeeds");
        assert_ne!(sealed, plaintext, "the wire payload must be ciphertext");

        let opened = session
            .responder_key
            .open_chunk(LanDirection::Forward, &bind, sealed)
            .expect("the peer opens the chunk under the same binding");
        assert_eq!(opened, plaintext);
    }

    #[test]
    fn any_different_binding_field_fails_to_open_the_chunk() {
        let session = honest_session("0123456789abcdef0123456789abcdef");
        let bind = binding(&session, 3, ATTACHMENT_SLOT_BODY, 7);
        let sealed = session
            .opener_key
            .seal_chunk(LanDirection::Forward, &bind, b"attachment bytes".to_vec())
            .expect("sealing succeeds");

        let other_session = LanSessionId::parse("fedcba9876543210fedcba9876543210")
            .expect("fixture session id is valid");
        let wrong_bindings = [
            ChunkBinding::new(&other_session, "batch-1", 3, ATTACHMENT_SLOT_BODY, 7),
            ChunkBinding::new(&session.id, "batch-2", 3, ATTACHMENT_SLOT_BODY, 7),
            ChunkBinding::new(&session.id, "batch-1", 4, ATTACHMENT_SLOT_BODY, 7),
            ChunkBinding::new(&session.id, "batch-1", 3, 0, 7),
            ChunkBinding::new(&session.id, "batch-1", 3, ATTACHMENT_SLOT_BODY, 8),
        ];

        for wrong in wrong_bindings {
            let wrong = wrong.expect("fixture binding is valid");
            let error = session
                .responder_key
                .open_chunk(LanDirection::Forward, &wrong, sealed.clone())
                .expect_err("a chunk must not open under a different binding");
            assert_eq!(error.code(), "lan_chunk_open_failed");
        }
    }

    #[test]
    fn tampered_ciphertext_or_tag_fails_closed() {
        let session = honest_session("0123456789abcdef0123456789abcdef");
        let bind = binding(&session, 1, ATTACHMENT_SLOT_BODY, 0);
        let sealed = session
            .opener_key
            .seal_chunk(LanDirection::Forward, &bind, b"tamper target".to_vec())
            .expect("sealing succeeds");

        for index in [0_usize, sealed.len() - 1] {
            let mut tampered = sealed.clone();
            tampered[index] ^= 0x01;
            let error = session
                .responder_key
                .open_chunk(LanDirection::Forward, &bind, tampered)
                .expect_err("tampered bytes must fail closed");
            assert_eq!(error.code(), "lan_chunk_open_failed");
            assert_eq!(error.category(), ErrorCategory::Authentication);
        }
    }

    #[test]
    fn distinct_chunks_in_one_session_use_distinct_nonces() {
        let session = honest_session("0123456789abcdef0123456789abcdef");
        let a = binding(&session, 1, ATTACHMENT_SLOT_BODY, 0);
        let b = binding(&session, 1, ATTACHMENT_SLOT_BODY, 1);
        let c = binding(&session, 2, ATTACHMENT_SLOT_BODY, 0);
        let d = binding(&session, 1, 0, 0);

        let nonces = [
            a.nonce().expect("nonce encodes"),
            b.nonce().expect("nonce encodes"),
            c.nonce().expect("nonce encodes"),
            d.nonce().expect("nonce encodes"),
        ];
        for (left, right) in [(0, 1), (0, 2), (0, 3), (1, 2), (1, 3), (2, 3)] {
            assert_ne!(
                nonces[left], nonces[right],
                "nonce reuse within one session key is forbidden"
            );
        }
    }

    #[test]
    fn distinct_batches_with_the_same_chunk_coordinates_do_not_reuse_the_keystream() {
        let session = honest_session("0123456789abcdef0123456789abcdef");
        let first =
            ChunkBinding::new(&session.id, "batch-1", 3, ATTACHMENT_SLOT_BODY, 7).expect("binding");
        let second =
            ChunkBinding::new(&session.id, "batch-2", 3, ATTACHMENT_SLOT_BODY, 7).expect("binding");
        assert_eq!(
            first.nonce().expect("nonce encodes"),
            second.nonce().expect("nonce encodes"),
            "the chunk coordinates (and therefore the nonce) are identical by design"
        );

        let plaintext = b"the same body bytes".to_vec();
        let sealed_first = session
            .opener_key
            .seal_chunk(LanDirection::Forward, &first, plaintext.clone())
            .expect("sealing succeeds");
        let sealed_second = session
            .opener_key
            .seal_chunk(LanDirection::Forward, &second, plaintext.clone())
            .expect("sealing succeeds");

        // The Poly1305 tag always differs because the AAD covers the batch id; keystream reuse is
        // detected only in the ciphertext body that precedes the tag.
        let first_body = sealed_first
            .get(..sealed_first.len() - AEAD_TAG_BYTES)
            .expect("sealed chunk always carries a trailing AEAD tag");
        let second_body = sealed_second
            .get(..sealed_second.len() - AEAD_TAG_BYTES)
            .expect("sealed chunk always carries a trailing AEAD tag");
        assert_ne!(
            first_body, second_body,
            "two batches sharing one key and nonce would reuse the ChaCha20 keystream"
        );

        assert_eq!(
            session
                .responder_key
                .open_chunk(LanDirection::Forward, &first, sealed_first)
                .expect("the peer opens batch-1 under its own key"),
            plaintext
        );
        assert_eq!(
            session
                .responder_key
                .open_chunk(LanDirection::Forward, &second, sealed_second)
                .expect("the peer opens batch-2 under its own key"),
            plaintext
        );
    }

    #[test]
    fn replayed_session_ids_are_rejected() {
        let mut ledger = ReplayLedger::default();
        let session = LanSessionId::parse("0123456789abcdef0123456789abcdef")
            .expect("fixture session id is valid");

        ledger
            .accept_session(&session)
            .expect("the first use of a session id is accepted");
        let error = ledger
            .accept_session(&session)
            .expect_err("a replayed session id must be rejected");
        assert_eq!(error.category(), ErrorCategory::Authentication);
        assert_eq!(error.code(), "lan_session_replayed");
    }

    #[test]
    fn replayed_chunks_are_rejected_and_leave_the_confirmed_range_unchanged() {
        let mut ledger = ReplayLedger::default();
        let session = LanSessionId::parse("0123456789abcdef0123456789abcdef")
            .expect("fixture session id is valid");
        ledger.accept_session(&session).expect("session accepted");

        let bind = ChunkBinding::new(&session, "batch-1", 0, ATTACHMENT_SLOT_BODY, 0)
            .expect("fixture binding is valid");
        ledger.confirm_chunk(&bind).expect("first chunk confirmed");
        assert_eq!(ledger.confirmed_chunk_count(), 1);

        let error = ledger
            .confirm_chunk(&bind)
            .expect_err("a replayed chunk must be rejected");
        assert_eq!(error.code(), "lan_chunk_replayed");
        assert_eq!(
            ledger.confirmed_chunk_count(),
            1,
            "a rejected replay must not grow the confirmed set"
        );

        let next = ChunkBinding::new(&session, "batch-1", 0, ATTACHMENT_SLOT_BODY, 1)
            .expect("fixture binding is valid");
        ledger.confirm_chunk(&next).expect("next chunk confirmed");
        assert_eq!(ledger.confirmed_chunk_count(), 2);
        assert!(
            ledger.is_chunk_confirmed(&bind) && ledger.is_chunk_confirmed(&next),
            "resume must be able to ask which chunks are already confirmed"
        );
    }

    fn control_binding(
        session: &Session,
        batch_id: &str,
        kind: SessionControlKind,
        sequence: u32,
    ) -> ControlBinding {
        let frame = match kind {
            SessionControlKind::Prepare => FrameKind::BatchPrepare,
            SessionControlKind::Approve => FrameKind::BatchApprove,
            SessionControlKind::Reject => FrameKind::BatchReject,
            SessionControlKind::Complete => FrameKind::BatchComplete,
        };
        ControlBinding::new(&session.id, batch_id, frame, kind, sequence)
            .expect("fixture control binding is valid")
    }

    #[test]
    fn opposite_directions_with_identical_chunk_coordinates_do_not_share_a_keystream() {
        let session = honest_session("0123456789abcdef0123456789abcdef");
        let bind = binding(&session, 3, ATTACHMENT_SLOT_BODY, 7);
        let plaintext = b"same coordinates both ways".to_vec();

        let forward = session
            .opener_key
            .seal_chunk(LanDirection::Forward, &bind, plaintext.clone())
            .expect("opener seals forward");
        let reverse = session
            .responder_key
            .seal_chunk(LanDirection::Reverse, &bind, plaintext.clone())
            .expect("responder seals reverse");

        let forward_body = forward
            .get(..forward.len() - AEAD_TAG_BYTES)
            .expect("sealed chunk always carries a trailing AEAD tag");
        let reverse_body = reverse
            .get(..reverse.len() - AEAD_TAG_BYTES)
            .expect("sealed chunk always carries a trailing AEAD tag");
        assert_ne!(
            forward_body, reverse_body,
            "bidirectional traffic must not reuse one ChaCha20 keystream"
        );

        assert_eq!(
            session
                .responder_key
                .open_chunk(LanDirection::Forward, &bind, forward)
                .expect("responder opens opener traffic"),
            plaintext
        );
        assert_eq!(
            session
                .opener_key
                .open_chunk(LanDirection::Reverse, &bind, reverse)
                .expect("opener opens responder traffic"),
            plaintext
        );
    }

    #[test]
    fn control_frames_are_aead_sealed_and_reject_kind_batch_and_header_tampering() {
        let session = honest_session("0123456789abcdef0123456789abcdef");
        let binding = control_binding(&session, "batch-1", SessionControlKind::Prepare, 0);
        let plaintext = b"batch-title\0attachment-name.png".to_vec();

        let sealed = session
            .opener_key
            .seal_control(LanDirection::Forward, &binding, plaintext.clone())
            .expect("control seals");
        assert_ne!(sealed, plaintext, "control must travel as ciphertext");
        assert!(
            !sealed
                .windows(plaintext.len())
                .any(|window| window == plaintext),
            "plaintext titles must not appear in the sealed control frame"
        );

        let opened = session
            .responder_key
            .open_control(LanDirection::Forward, &binding, sealed.clone())
            .expect("peer opens honest control");
        assert_eq!(opened, plaintext);

        let wrong_kind = control_binding(&session, "batch-1", SessionControlKind::Approve, 0);
        let kind_error = session
            .responder_key
            .open_control(LanDirection::Forward, &wrong_kind, sealed.clone())
            .expect_err("a swapped frame kind must fail authentication");
        assert_eq!(kind_error.code(), "lan_control_open_failed");

        let wrong_batch = control_binding(&session, "batch-2", SessionControlKind::Prepare, 0);
        let batch_error = session
            .responder_key
            .open_control(LanDirection::Forward, &wrong_batch, sealed.clone())
            .expect_err("a swapped batch id must fail authentication");
        assert_eq!(batch_error.code(), "lan_control_open_failed");

        let wrong_sequence = control_binding(&session, "batch-1", SessionControlKind::Prepare, 1);
        let header_error = session
            .responder_key
            .open_control(LanDirection::Forward, &wrong_sequence, sealed.clone())
            .expect_err("a swapped sequence must fail authentication");
        assert_eq!(header_error.code(), "lan_control_open_failed");

        let mut tampered = sealed;
        let last = tampered.len() - 1;
        tampered[last] ^= 0x01;
        let tag_error = session
            .responder_key
            .open_control(LanDirection::Forward, &binding, tampered)
            .expect_err("a tampered control tag must fail authentication");
        assert_eq!(tag_error.code(), "lan_control_open_failed");
        assert_eq!(tag_error.category(), ErrorCategory::Authentication);
    }

    #[test]
    fn control_aad_golden_covers_kind_batch_sequence_and_declared_length() {
        let session = honest_session("0123456789abcdef0123456789abcdef");
        let binding = control_binding(&session, "batch-1", SessionControlKind::Prepare, 7);
        let aad = binding.aad(LanDirection::Forward, 13);
        assert_eq!(
            aad,
            [
                0, 0, 0, 19, b'l', b'o', b'm', b'o', b'-', b'l', b'a', b'n', b'-', b'c', b'o',
                b'n', b't', b'r', b'o', b'l', b'-', b'v', b'3', 0, 3, 1, 0, 6, 1, 0, 0, 0, 32,
                b'0', b'1', b'2', b'3', b'4', b'5', b'6', b'7', b'8', b'9', b'a', b'b', b'c', b'd',
                b'e', b'f', b'0', b'1', b'2', b'3', b'4', b'5', b'6', b'7', b'8', b'9', b'a', b'b',
                b'c', b'd', b'e', b'f', 0, 0, 0, 7, b'b', b'a', b't', b'c', b'h', b'-', b'1', 0, 0,
                0, 7, 0, 0, 0, 13,
            ],
            "control AAD is a golden vector over version, direction, frame kind, control kind, session, batch, sequence and declared length"
        );
    }

    #[test]
    fn retransmitting_the_same_chunk_binding_reuses_ciphertext_and_does_not_wrap_the_nonce() {
        let session = honest_session("0123456789abcdef0123456789abcdef");
        let bind = binding(&session, 0, ATTACHMENT_SLOT_BODY, 0);
        let plaintext = b"resume payload".to_vec();
        let first = session
            .opener_key
            .seal_chunk(LanDirection::Forward, &bind, plaintext.clone())
            .expect("first seal");
        let retry = session
            .opener_key
            .seal_chunk(LanDirection::Forward, &bind, plaintext)
            .expect("retransmit seal");
        assert_eq!(
            first, retry,
            "recovery must reuse the original nonce and ciphertext rather than wrapping the counter"
        );
    }

    #[test]
    fn exhausting_chunk_or_control_nonce_space_requires_a_new_crypto_session() {
        let session = honest_session("0123456789abcdef0123456789abcdef");
        let last_chunk =
            ChunkBinding::new(&session.id, "batch-1", 0, ATTACHMENT_SLOT_BODY, u32::MAX)
                .expect("last chunk index is representable");
        let chunk_error = last_chunk
            .successor()
            .expect_err("overflowing the chunk counter must force a new session");
        assert_eq!(chunk_error.code(), "lan_chunk_nonce_exhausted");
        assert_eq!(chunk_error.category(), ErrorCategory::ResourceLimit);

        let last_control =
            control_binding(&session, "batch-1", SessionControlKind::Complete, u32::MAX);
        let control_error = last_control
            .successor()
            .expect_err("overflowing the control counter must force a new session");
        assert_eq!(control_error.code(), "lan_control_nonce_exhausted");
        assert_eq!(control_error.category(), ErrorCategory::ResourceLimit);
    }
}
