//! Behavior Contract (Stage-6 P6-09 product pairing runtime)
//!
//! Capability: two Rust-owned LAN runtimes exchange v2 pairing frames, derive one authenticated
//! transcript/code, request only an external device-key signature, and store peer trust only after
//! both local users confirm. Kotlin never handles X25519, shared secrets or frame bytes.
//!
//! Scenarios:
//! - Given two discovered v2 endpoints, when pairing begins, then both runtimes expose the same
//!   short code and transcript while all protocol bytes remain inside `lomo-lan`.
//! - Given only one endpoint confirms, when peer trust is queried, then neither endpoint reports a
//!   completed pairing.
//! - Given both endpoints confirm with valid P-256 signatures, when confirmation frames are
//!   processed, then each installation journal stores the other device exactly once.
//! - Given a confirmation after the pairing deadline, when submitted, then it fails closed and no
//!   peer is stored.
//!
//! Observable outcomes: pairing challenge code/transcript, journal peer records and typed errors.
//!
//! TDD proof: RED before the runtime implementation because identity configuration, begin/poll/
//! confirm and challenge query methods do not exist.
//!
//! Excludes: Android Keystore implementation, NSD APIs, Compose and memo transfer.

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
        DevicePublicKey, DiscoveredPeerEndpoint, DisplayName, LAN_PROTOCOL_VERSION,
        LanBindCandidate, LanNetworkSnapshot, LanServiceManager,
    };

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
                DisplayName::parse(name).expect("name"),
            )
            .expect("identity configures");
        manager
            .update_network(
                LanNetworkSnapshot::new(
                    1,
                    true,
                    vec![LanBindCandidate::parse("127.0.0.1", 0).expect("loopback candidate")],
                )
                .expect("network snapshot"),
            )
            .expect("network publishes");
        manager.start().expect("listener starts");
        (root, manager)
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
            lomo_lan::DeviceId::derive(&identity.public).as_str(),
            name,
            &address.ip().to_string(),
            address.port(),
            LAN_PROTOCOL_VERSION,
        )
        .expect("discovered endpoint")
    }

    #[test]
    fn both_users_must_confirm_the_same_rust_owned_transcript_before_trust_is_stored() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (_phone_root, mut phone_runtime) = manager(&phone, "Phone");
        let (_tablet_root, tablet_runtime) = manager(&tablet, "Tablet");
        let tablet_endpoint = endpoint(&tablet_runtime, &tablet, "Tablet");

        let responder = thread::spawn(move || {
            let mut runtime = tablet_runtime;
            runtime
                .poll_listener(1_700_000_000_000)
                .expect("pair hello is handled");
            runtime
        });
        let phone_challenge = phone_runtime
            .begin_pairing(&tablet_endpoint, 1_700_000_000_000, 300_000)
            .expect("initiator exchanges hello/accept");
        let tablet_runtime = responder.join().expect("responder returns");
        let tablet_challenge = tablet_runtime
            .pairing_challenge(phone_challenge.pairing_id())
            .expect("responder exposes the pending challenge");

        assert_eq!(phone_challenge.short_code(), tablet_challenge.short_code());
        assert_eq!(
            phone_challenge.transcript_to_sign(),
            tablet_challenge.transcript_to_sign(),
            "both Keystore adapters must sign the same Rust-owned bytes"
        );

        let phone_signature = phone.sign(phone_challenge.transcript_to_sign());
        let phone_confirm_id = phone_challenge.pairing_id().clone();
        let tablet_receiver = thread::spawn(move || {
            let mut runtime = tablet_runtime;
            runtime
                .poll_listener(1_700_000_010_000)
                .expect("phone confirmation is handled");
            runtime
        });
        phone_runtime
            .confirm_pairing(&phone_confirm_id, &phone_signature, 1_700_000_010_000)
            .expect("phone confirms locally and sends its signature");
        let mut tablet_runtime = tablet_receiver.join().expect("tablet returns");

        assert_eq!(phone_runtime.peers().len(), 0);
        assert_eq!(
            tablet_runtime.peers().len(),
            0,
            "one confirmation never stores trust"
        );

        let tablet_signature = tablet.sign(tablet_challenge.transcript_to_sign());
        let tablet_confirm_id = tablet_challenge.pairing_id().clone();
        let phone_receiver = thread::spawn(move || {
            let mut runtime = phone_runtime;
            runtime
                .poll_listener(1_700_000_020_000)
                .expect("tablet confirmation is handled");
            runtime
        });
        tablet_runtime
            .confirm_pairing(&tablet_confirm_id, &tablet_signature, 1_700_000_020_000)
            .expect("tablet confirms locally and sends its signature");
        let phone_runtime = phone_receiver.join().expect("phone returns");

        assert_eq!(phone_runtime.peers().len(), 1);
        assert_eq!(tablet_runtime.peers().len(), 1);
        assert!(
            phone_runtime
                .peers()
                .contains_key(&lomo_lan::DeviceId::derive(&tablet.public))
        );
        assert!(
            tablet_runtime
                .peers()
                .contains_key(&lomo_lan::DeviceId::derive(&phone.public))
        );
    }

    #[test]
    fn pairing_confirmation_after_the_deadline_stores_no_peer() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (_phone_root, mut phone_runtime) = manager(&phone, "Phone");
        let (_tablet_root, tablet_runtime) = manager(&tablet, "Tablet");
        let tablet_endpoint = endpoint(&tablet_runtime, &tablet, "Tablet");
        let responder = thread::spawn(move || {
            let mut runtime = tablet_runtime;
            runtime.poll_listener(1_000).expect("hello is handled");
            runtime
        });
        let challenge = phone_runtime
            .begin_pairing(&tablet_endpoint, 1_000, 50)
            .expect("pairing starts");
        let tablet_runtime = responder.join().expect("responder returns");

        let error = phone_runtime
            .confirm_pairing(
                challenge.pairing_id(),
                &phone.sign(challenge.transcript_to_sign()),
                1_051,
            )
            .expect_err("expired pairing must fail closed");
        assert_eq!(error.code(), "lan_pairing_expired");
        assert!(phone_runtime.peers().is_empty());
        assert!(tablet_runtime.peers().is_empty());
    }

    #[test]
    fn declining_the_short_code_discards_local_pairing_without_storing_trust() {
        let phone = TestIdentity::generate();
        let tablet = TestIdentity::generate();
        let (_phone_root, mut phone_runtime) = manager(&phone, "Phone");
        let (_tablet_root, tablet_runtime) = manager(&tablet, "Tablet");
        let tablet_endpoint = endpoint(&tablet_runtime, &tablet, "Tablet");
        let responder = thread::spawn(move || {
            let mut runtime = tablet_runtime;
            runtime.poll_listener(1_000).expect("hello is handled");
            runtime
        });
        let challenge = phone_runtime
            .begin_pairing(&tablet_endpoint, 1_000, 60_000)
            .expect("pairing starts");
        let tablet_runtime = responder.join().expect("responder returns");

        phone_runtime
            .decline_pairing(challenge.pairing_id())
            .expect("pending pairing declines");

        assert!(
            phone_runtime
                .pairing_challenge(challenge.pairing_id())
                .is_none()
        );
        assert!(phone_runtime.peers().is_empty());
        assert!(tablet_runtime.peers().is_empty());
    }
}
