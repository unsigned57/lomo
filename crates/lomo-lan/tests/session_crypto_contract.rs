//! Behavior Contract (Stage-6 P6-03 session authentication, key derivation and chunk AEAD)
//!
//! Capability: each connection performs mutual device-signature authentication over a fresh session
//! transcript, derives a session key with HKDF-SHA256, and seals every chunk with
//! ChaCha20-Poly1305 under a nonce and AAD bound to session, batch, item, attachment slot and chunk
//! index. Replayed session ids and replayed chunks are rejected against a durable ledger.
//!
//! Scenarios:
//! - Given both endpoints of an honest session, when each derives the session key, then the keys
//!   are equal and differ from another session's key.
//! - Given a session transcript, when a peer signs it, then the other side verifies it under the
//!   stored peer key; a signature over another transcript or under another key fails.
//! - Given a sealed chunk, when opened with the same session/batch/item/slot/index, then the
//!   plaintext round-trips.
//! - Given a sealed chunk, when opened under any *different* binding field, then it fails closed.
//! - Given a tampered ciphertext or tag, when opened, then it fails closed.
//! - Given two distinct chunks in one session, then their nonces differ.
//! - Given a session id already seen, when accepted again, then the replay ledger rejects it.
//! - Given a chunk index already confirmed, when replayed, then the ledger rejects it and the
//!   confirmed range is unchanged.
//!
//! Observable outcomes: derived key equality, signature verification results, sealed/opened bytes,
//! nonce values, ledger accept/reject decisions and confirmed ranges, `LomoError` code/category.
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
        ATTACHMENT_SLOT_BODY, ChunkBinding, DevicePublicKey, DeviceSigner, LanSessionId,
        ReplayLedger, SessionKey, SessionTranscript,
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
        assert_eq!(
            session.opener_key.as_bytes(),
            session.responder_key.as_bytes(),
            "an honest session derives one key on both ends"
        );

        let other = honest_session("fedcba9876543210fedcba9876543210");
        assert_ne!(
            session.opener_key.as_bytes(),
            other.opener_key.as_bytes(),
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
            .seal_chunk(&bind, plaintext.clone())
            .expect("sealing succeeds");
        assert_ne!(sealed, plaintext, "the wire payload must be ciphertext");

        let opened = session
            .responder_key
            .open_chunk(&bind, sealed)
            .expect("the peer opens the chunk under the same binding");
        assert_eq!(opened, plaintext);
    }

    #[test]
    fn any_different_binding_field_fails_to_open_the_chunk() {
        let session = honest_session("0123456789abcdef0123456789abcdef");
        let bind = binding(&session, 3, ATTACHMENT_SLOT_BODY, 7);
        let sealed = session
            .opener_key
            .seal_chunk(&bind, b"attachment bytes".to_vec())
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
                .open_chunk(&wrong, sealed.clone())
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
            .seal_chunk(&bind, b"tamper target".to_vec())
            .expect("sealing succeeds");

        for index in [0_usize, sealed.len() - 1] {
            let mut tampered = sealed.clone();
            tampered[index] ^= 0x01;
            let error = session
                .responder_key
                .open_chunk(&bind, tampered)
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

        let nonces = [a.nonce(), b.nonce(), c.nonce(), d.nonce()];
        for (left, right) in [(0, 1), (0, 2), (0, 3), (1, 2), (1, 3), (2, 3)] {
            assert_ne!(
                nonces[left], nonces[right],
                "nonce reuse within one session key is forbidden"
            );
        }
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
}
