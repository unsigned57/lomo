//! Behavior Contract (Stage-6 P6-02 device identity, pairing transcript, short auth code)
//!
//! Capability: `lomo-lan` derives device identity from a non-exportable device signing key, builds
//! one canonical pairing transcript from both endpoints, and derives the short authentication code
//! from that transcript so an in-path attacker cannot make both ends show the same code.
//!
//! Scenarios:
//! - Given two endpoints completing an honest pairing, when both derive the short code, then the
//!   codes are equal and are `PAIRING_CODE_DIGITS` decimal digits.
//! - Given an in-path attacker running two separate exchanges, when both honest ends derive their
//!   codes, then the codes differ and neither honest end can be told to store the attacker's peer.
//! - Given a completed transcript, when either side confirms with its device signature, then the
//!   other side verifies it against the transcript and stores the peer.
//! - Given a confirmation signature over a *different* transcript, when verified, then it fails and
//!   no peer is stored.
//! - Given a structurally invalid device public key, when parsed, then the boundary rejects it.
//! - Given a display name above the byte ceiling or containing control characters, when parsed,
//!   then the boundary rejects it.
//! - Given a peer record, when revoked, then it reports revoked and is no longer connectable.
//! - Given a device id, then it is derived from the public key, so a peer cannot claim an identity
//!   that does not match the key it authenticates with.
//!
//! Observable outcomes: derived `DeviceId` bytes, transcript bytes, short code digits, signature
//! verification results, peer record state, `LomoError` code/category.
//!
//! Excludes: sockets, session AEAD, batches, journal durability, Kotlin Keystore implementation.

#![deny(unsafe_code)]

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests fail closed with panics on any unmet precondition"
)]
mod tests {
    use aws_lc_rs::rand::SystemRandom;
    use aws_lc_rs::signature::{ECDSA_P256_SHA256_ASN1_SIGNING, EcdsaKeyPair, KeyPair};
    use aws_lc_rs::{agreement, encoding::AsBigEndian};
    use lomo_core::ErrorCategory;
    use lomo_lan::{
        DeviceId, DevicePublicKey, DeviceSigner, DisplayName, PAIRING_CODE_DIGITS, PairingRole,
        PairingTranscript, PeerRecord, derive_pairing_code, verify_pairing_confirmation,
    };

    /// Host stand-in for the Android Keystore: owns the private key, exposes sign + public key.
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
            let public_key = DevicePublicKey::parse(x963.as_ref())
                .expect("generated key is a valid P-256 point");
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
                    lomo_lan::lan_authentication(
                        "lan_device_sign_failed",
                        "host test signer could not sign the transcript",
                    )
                })
        }
    }

    struct Ephemeral {
        private: agreement::PrivateKey,
        public: Vec<u8>,
    }

    impl Ephemeral {
        fn generate() -> Self {
            let private = agreement::PrivateKey::generate(&agreement::X25519)
                .expect("ephemeral X25519 key generates");
            let public = private
                .compute_public_key()
                .expect("ephemeral public key derives")
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
            .expect("honest X25519 agreement succeeds")
        }
    }

    fn name(value: &str) -> DisplayName {
        DisplayName::parse(value).expect("fixture display name is valid")
    }

    /// Builds both endpoints' view of one honest pairing exchange.
    fn honest_pairing() -> (TestSigner, TestSigner, PairingTranscript, PairingTranscript) {
        let initiator = TestSigner::generate();
        let responder = TestSigner::generate();
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

        (initiator, responder, initiator_view, responder_view)
    }

    #[test]
    fn honest_pairing_yields_one_transcript_and_one_short_code_on_both_ends() {
        let (_initiator, _responder, initiator_view, responder_view) = honest_pairing();

        assert_eq!(
            initiator_view.bytes(),
            responder_view.bytes(),
            "both endpoints must build byte-identical transcripts"
        );

        assert_eq!(
            PairingRole::Initiator.local(&"phone-eph", &"tablet-eph"),
            PairingRole::Responder.peer(&"phone-eph", &"tablet-eph"),
            "the two roles must agree on which endpoint's material is the initiator's"
        );

        let initiator_code = derive_pairing_code(&initiator_view);
        let responder_code = derive_pairing_code(&responder_view);
        assert_eq!(initiator_code, responder_code, "honest codes must match");
        assert_eq!(initiator_code.len(), PAIRING_CODE_DIGITS);
        assert!(
            initiator_code.chars().all(|c| c.is_ascii_digit()),
            "code is decimal digits only: {initiator_code}"
        );
    }

    #[test]
    fn in_path_attacker_cannot_make_both_ends_display_the_same_code() {
        // Honest ends A and B; attacker M runs a separate exchange with each.
        let alice = TestSigner::generate();
        let bob = TestSigner::generate();
        let mallory = TestSigner::generate();

        let alice_eph = Ephemeral::generate();
        let mallory_to_alice = Ephemeral::generate();
        let mallory_to_bob = Ephemeral::generate();
        let bob_eph = Ephemeral::generate();

        // Alice believes she paired with "Tablet" but actually agreed with Mallory.
        let alice_view = PairingTranscript::build(
            alice.public_key(),
            &name("Phone"),
            &alice_eph.public,
            mallory.public_key(),
            &name("Tablet"),
            &mallory_to_alice.public,
            &alice_eph.agree(&mallory_to_alice.public),
        )
        .expect("alice transcript builds");

        // Bob believes he responded to "Phone" but actually agreed with Mallory.
        let bob_view = PairingTranscript::build(
            mallory.public_key(),
            &name("Phone"),
            &mallory_to_bob.public,
            bob.public_key(),
            &name("Tablet"),
            &bob_eph.public,
            &bob_eph.agree(&mallory_to_bob.public),
        )
        .expect("bob transcript builds");

        assert_ne!(
            alice_view.bytes(),
            bob_view.bytes(),
            "an in-path attacker necessarily produces two distinct transcripts"
        );
        assert_ne!(
            derive_pairing_code(&alice_view),
            derive_pairing_code(&bob_view),
            "the short code must diverge so the users detect the in-path attacker"
        );
    }

    #[test]
    fn confirmation_signature_over_the_transcript_verifies_and_stores_the_peer() {
        let (initiator, _responder, initiator_view, responder_view) = honest_pairing();

        let signature = initiator
            .sign(initiator_view.bytes())
            .expect("initiator signs its own transcript");

        let peer = verify_pairing_confirmation(
            &responder_view,
            initiator.public_key(),
            &name("Phone"),
            &signature,
            1_700_000_000_000,
        )
        .expect("the responder verifies the initiator confirmation");

        assert_eq!(peer.device_id(), &DeviceId::derive(initiator.public_key()));
        assert_eq!(peer.public_key(), initiator.public_key());
        assert!(!peer.is_revoked(), "a freshly paired peer is not revoked");
    }

    #[test]
    fn confirmation_over_a_different_transcript_is_rejected_and_stores_no_peer() {
        let (initiator, _responder, _initiator_view, responder_view) = honest_pairing();
        let (_other_initiator, _other_responder, other_view, _other_responder_view) =
            honest_pairing();

        let signature = initiator
            .sign(other_view.bytes())
            .expect("initiator signs an unrelated transcript");

        let error = verify_pairing_confirmation(
            &responder_view,
            initiator.public_key(),
            &name("Phone"),
            &signature,
            1_700_000_000_000,
        )
        .expect_err("a signature over a different transcript must be rejected");
        assert_eq!(error.category(), ErrorCategory::Authentication);
        assert_eq!(error.code(), "lan_pairing_signature_invalid");
    }

    #[test]
    fn confirmation_from_a_substituted_key_is_rejected() {
        let (initiator, _responder, initiator_view, responder_view) = honest_pairing();
        let impostor = TestSigner::generate();

        let signature = initiator
            .sign(initiator_view.bytes())
            .expect("initiator signs the transcript");

        let error = verify_pairing_confirmation(
            &responder_view,
            impostor.public_key(),
            &name("Phone"),
            &signature,
            1_700_000_000_000,
        )
        .expect_err("a valid signature under a different key must not authenticate that key");
        assert_eq!(error.code(), "lan_pairing_signature_invalid");
    }

    #[test]
    fn device_id_is_derived_from_the_public_key() {
        let signer = TestSigner::generate();
        let other = TestSigner::generate();

        let derived = DeviceId::derive(signer.public_key());
        assert_eq!(
            derived,
            DeviceId::derive(signer.public_key()),
            "derivation is deterministic"
        );
        assert_ne!(
            derived,
            DeviceId::derive(other.public_key()),
            "distinct keys yield distinct device ids"
        );
        assert!(
            !derived.as_str().is_empty(),
            "device id renders as a stable string"
        );
    }

    #[test]
    fn structurally_invalid_device_public_keys_are_rejected() {
        for (label, bytes) in [
            ("empty", Vec::new()),
            ("too short", vec![0x04; 64]),
            ("too long", vec![0x04; 66]),
            ("compressed point tag", vec![0x02; 65]),
        ] {
            let error = DevicePublicKey::parse(&bytes)
                .err()
                .unwrap_or_else(|| panic!("{label} device key must be rejected"));
            assert_eq!(
                error.code(),
                "lan_device_key_invalid",
                "{label} must report the stable boundary code"
            );
            assert_eq!(error.category(), ErrorCategory::Validation);
        }
    }

    #[test]
    fn display_name_boundary_rejects_oversize_and_control_characters() {
        DisplayName::parse("Kitchen Tablet").expect("an ordinary device name is accepted");
        assert!(DisplayName::parse("").is_err(), "empty name is rejected");
        assert!(
            DisplayName::parse("bad\u{0}name").is_err(),
            "control characters are rejected"
        );
        let oversized = "x".repeat(lomo_lan::MAX_DISPLAY_NAME_BYTES + 1);
        let error = DisplayName::parse(&oversized).expect_err("oversize name is rejected");
        assert_eq!(error.category(), ErrorCategory::ResourceLimit);
    }

    #[test]
    fn revoking_a_peer_makes_it_unconnectable() {
        let signer = TestSigner::generate();
        let peer = PeerRecord::paired(
            signer.public_key().clone(),
            name("Phone"),
            1_700_000_000_000,
        );
        peer.assert_connectable()
            .expect("a freshly paired peer is connectable");

        let revoked = peer.revoked(1_700_000_001_000);
        assert!(revoked.is_revoked());
        let error = revoked
            .assert_connectable()
            .expect_err("a revoked peer must not be connectable");
        assert_eq!(error.category(), ErrorCategory::Authentication);
        assert_eq!(error.code(), "lan_peer_revoked");
    }
}
