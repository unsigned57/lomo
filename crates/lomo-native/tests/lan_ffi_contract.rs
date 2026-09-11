//! Behavior Contract (Stage-6 P6-08 LAN FFI conversion surface)
//!
//! Capability: the `BoltFFI` LAN surface is conversion-only. It parses wire types, delegates every
//! decision to `lomo-lan`, and maps results back. No business rule lives at this boundary.
//!
//! Scenarios:
//! - Given both endpoints' transcript inputs, when the short code is derived twice, then it is
//!   stable; when one ephemeral key differs, then the code differs.
//! - Given a malformed device key, display name or ephemeral point, when converted, then the
//!   boundary rejects it before any transcript exists.
//! - Given a valid confirmation, when confirmed, then the peer appears in the returned page; given
//!   a confirmation signed over another transcript, then it is rejected and no peer is stored.
//! - Given a stored peer, when revoked, then the page still lists it and marks it revoked, so a
//!   later connection is refused explicitly.
//! - Given a send request inside the limits, when prepared, then the preview reports counts, total
//!   bytes and truncated titles and carries no body.
//! - Given a send request above the item ceiling, when prepared, then it is rejected before any
//!   socket is opened.
//! - Given an approval, when recorded, then validity is reported inside the TTL and not past it;
//!   a non-positive TTL is rejected.
//! - Given confirmed chunks, when resume is queried, then only the unconfirmed indices are
//!   returned.
//! - Given a journal root inside a `.lomo` control tree, when any call is made, then it is rejected.
//!
//! Observable outcomes: returned DTO field values, `EngineError` code, journal contents.
//!
//! Excludes: Kotlin adapters, production DI, sockets, NSD, Keystore, device runs.

#![deny(unsafe_code)]

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::indexing_slicing,
    reason = "contract tests fail closed with panics and index fixture pages of known size"
)]
mod tests {
    use aws_lc_rs::agreement;
    use aws_lc_rs::encoding::AsBigEndian;
    use aws_lc_rs::rand::SystemRandom;
    use aws_lc_rs::signature::{ECDSA_P256_SHA256_ASN1_SIGNING, EcdsaKeyPair, KeyPair};
    use lomo_native::{
        LanPairingTranscriptDto, LanSendItemDto, lan_approval_is_valid, lan_approve_receive,
        lan_confirm_pairing, lan_list_peers, lan_pairing_short_code, lan_prepare_send,
        lan_revoke_peer, lan_unconfirmed_chunks,
    };
    use sha2::{Digest, Sha256};

    const BODY: &str = "# 收到的 memo\n";

    struct Endpoint {
        key_pair: EcdsaKeyPair,
        public_key: Vec<u8>,
        rng: SystemRandom,
    }

    impl Endpoint {
        fn generate() -> Self {
            let key_pair = EcdsaKeyPair::generate(&ECDSA_P256_SHA256_ASN1_SIGNING)
                .expect("host key pair generates");
            let bytes: aws_lc_rs::encoding::EcPublicKeyUncompressedBin<'_> = key_pair
                .public_key()
                .as_be_bytes()
                .expect("public key exports as X9.62 bytes");
            Self {
                key_pair,
                public_key: bytes.as_ref().to_vec(),
                rng: SystemRandom::new(),
            }
        }

        fn sign(&self, message: &[u8]) -> Vec<u8> {
            self.key_pair
                .sign(&self.rng, message)
                .expect("host signer signs")
                .as_ref()
                .to_vec()
        }

        fn device_id(&self) -> String {
            let mut hasher = Sha256::new();
            hasher.update(b"lomo-lan-device-id-v2");
            hasher.update(&self.public_key);
            format!("{:x}", hasher.finalize())
        }
    }

    fn ephemeral() -> (agreement::PrivateKey, Vec<u8>) {
        let private =
            agreement::PrivateKey::generate(&agreement::X25519).expect("X25519 generates");
        let public = private
            .compute_public_key()
            .expect("public key derives")
            .as_ref()
            .to_vec();
        (private, public)
    }

    fn agree(private: &agreement::PrivateKey, peer_public: &[u8]) -> Vec<u8> {
        agreement::agree(
            private,
            agreement::UnparsedPublicKey::new(&agreement::X25519, peer_public),
            (),
            |secret| Ok(secret.to_vec()),
        )
        .expect("honest agreement succeeds")
    }

    struct Pairing {
        initiator: Endpoint,
        transcript: LanPairingTranscriptDto,
    }

    fn honest_pairing() -> Pairing {
        let initiator = Endpoint::generate();
        let responder = Endpoint::generate();
        let (initiator_private, initiator_public) = ephemeral();
        let (_responder_private, responder_public) = ephemeral();
        let shared = agree(&initiator_private, &responder_public);

        Pairing {
            transcript: LanPairingTranscriptDto {
                initiator_public_key: initiator.public_key.clone(),
                initiator_display_name: "Phone".to_owned(),
                initiator_ephemeral: initiator_public,
                responder_public_key: responder.public_key,
                responder_display_name: "Tablet".to_owned(),
                responder_ephemeral: responder_public,
                shared_secret: shared,
            },
            initiator,
        }
    }

    fn root() -> tempfile::TempDir {
        tempfile::tempdir().expect("app-private root is creatable")
    }

    fn root_str(dir: &tempfile::TempDir) -> String {
        dir.path()
            .to_str()
            .expect("fixture path is UTF-8")
            .to_owned()
    }

    fn item(title: &str) -> LanSendItemDto {
        LanSendItemDto {
            timestamp_ms: 1_700_000_000_000,
            content_digest: format!("{:x}", Sha256::digest(BODY.as_bytes())),
            content_bytes: BODY.len() as u64,
            title: title.to_owned(),
            attachments: Vec::new(),
        }
    }

    #[test]
    fn the_short_code_is_stable_for_one_transcript_and_differs_for_another() {
        let pairing = honest_pairing();
        let first = lan_pairing_short_code(pairing.transcript.clone()).expect("code derives");
        let second = lan_pairing_short_code(pairing.transcript.clone()).expect("code derives");
        assert_eq!(first, second, "one transcript yields one code");
        assert_eq!(first.len(), 6);

        let mut altered = pairing.transcript;
        altered.responder_ephemeral = ephemeral().1;
        let different = lan_pairing_short_code(altered).expect("code derives");
        assert_ne!(
            first, different,
            "a different ephemeral key must change the code"
        );
    }

    #[test]
    fn malformed_transcript_inputs_are_rejected_at_the_boundary() {
        let pairing = honest_pairing();

        let mut bad_key = pairing.transcript.clone();
        bad_key.initiator_public_key = vec![0x04; 64];
        assert_eq!(
            lan_pairing_short_code(bad_key)
                .expect_err("a short device key is rejected")
                .code(),
            "lan_device_key_invalid"
        );

        let mut bad_name = pairing.transcript.clone();
        bad_name.responder_display_name = String::new();
        assert_eq!(
            lan_pairing_short_code(bad_name)
                .expect_err("an empty display name is rejected")
                .code(),
            "lan_display_name_invalid"
        );

        let mut bad_ephemeral = pairing.transcript;
        bad_ephemeral.initiator_ephemeral = vec![0_u8; 16];
        assert_eq!(
            lan_pairing_short_code(bad_ephemeral)
                .expect_err("a short ephemeral point is rejected")
                .code(),
            "lan_pairing_ephemeral_invalid"
        );
    }

    #[test]
    fn confirming_a_valid_pairing_stores_the_peer_and_revoking_keeps_it_visible() {
        let dir = root();
        let pairing = honest_pairing();
        let transcript_bytes = {
            // Re-derive the exact bytes lomo-lan will build so the signature matches.
            let code = lan_pairing_short_code(pairing.transcript.clone()).expect("code derives");
            assert_eq!(code.len(), 6);
            lomo_lan::PairingTranscript::build(
                &lomo_lan::DevicePublicKey::parse(&pairing.transcript.initiator_public_key)
                    .expect("key parses"),
                &lomo_lan::DisplayName::parse(&pairing.transcript.initiator_display_name)
                    .expect("name parses"),
                &pairing.transcript.initiator_ephemeral,
                &lomo_lan::DevicePublicKey::parse(&pairing.transcript.responder_public_key)
                    .expect("key parses"),
                &lomo_lan::DisplayName::parse(&pairing.transcript.responder_display_name)
                    .expect("name parses"),
                &pairing.transcript.responder_ephemeral,
                &pairing.transcript.shared_secret,
            )
            .expect("transcript builds")
        };
        let signature = pairing.initiator.sign(transcript_bytes.bytes());

        let page = lan_confirm_pairing(
            root_str(&dir),
            pairing.transcript.clone(),
            pairing.initiator.public_key.clone(),
            "Phone".to_owned(),
            signature,
            1_700_000_000_000,
        )
        .expect("a valid confirmation stores the peer");
        assert_eq!(page.total, 1);
        assert_eq!(page.peers[0].display_name, "Phone");
        assert!(!page.peers[0].revoked);

        let device_id = pairing.initiator.device_id();
        assert_eq!(page.peers[0].device_id, device_id);

        let revoked = lan_revoke_peer(root_str(&dir), device_id, 1_700_000_500_000)
            .expect("revoking a stored peer succeeds");
        assert_eq!(revoked.total, 1, "a revoked peer stays listed");
        assert!(revoked.peers[0].revoked);
        assert_eq!(revoked.peers[0].revoked_at_ms, Some(1_700_000_500_000));

        let listed = lan_list_peers(root_str(&dir)).expect("listing succeeds");
        assert!(listed.peers[0].revoked, "revocation is durable");
    }

    #[test]
    fn a_confirmation_over_another_transcript_stores_no_peer() {
        let dir = root();
        let pairing = honest_pairing();
        let foreign = honest_pairing();
        let signature = pairing.initiator.sign(b"an unrelated message");

        let error = lan_confirm_pairing(
            root_str(&dir),
            pairing.transcript,
            pairing.initiator.public_key,
            "Phone".to_owned(),
            signature,
            1_700_000_000_000,
        )
        .expect_err("a confirmation that does not verify must be rejected");
        assert_eq!(error.code(), "lan_pairing_signature_invalid");
        let _unused = foreign;

        let listed = lan_list_peers(root_str(&dir)).expect("listing succeeds");
        assert_eq!(listed.total, 0, "a rejected confirmation stores no peer");
    }

    #[test]
    fn revoking_an_unknown_device_is_rejected() {
        let dir = root();
        let unknown = "0".repeat(64);
        let error = lan_revoke_peer(root_str(&dir), unknown, 1_700_000_000_000)
            .expect_err("revoking a device that was never paired is rejected");
        assert_eq!(error.code(), "lan_peer_unknown");
    }

    #[test]
    fn a_journal_root_under_a_lomo_control_tree_is_rejected() {
        let error = lan_list_peers("/tmp/workspace/.lomo/private".to_owned())
            .expect_err("peer trust must never live under .lomo");
        assert_eq!(error.code(), "lan_journal_root_invalid");
    }

    #[test]
    fn prepare_send_returns_a_bounded_preview_and_rejects_an_oversized_batch() {
        let preview = lan_prepare_send(
            "batch-ffi".to_owned(),
            "a".repeat(64),
            "Phone".to_owned(),
            vec![item("first"), item(&"标题".repeat(200))],
        )
        .expect("a two-item batch is inside the limits");

        assert_eq!(preview.batch_id, "batch-ffi");
        assert_eq!(preview.item_count, 2);
        assert_eq!(preview.attachment_count, 0);
        assert_eq!(preview.total_bytes, 2 * BODY.len() as u64);
        for title in &preview.titles {
            assert!(
                title.chars().count() <= 80,
                "preview titles stay truncated across the boundary: {title}"
            );
        }

        let too_many = (0..101).map(|_index| item("x")).collect();
        let error = lan_prepare_send(
            "batch-ffi".to_owned(),
            "a".repeat(64),
            "Phone".to_owned(),
            too_many,
        )
        .expect_err("more than 100 items is rejected before any socket opens");
        assert_eq!(error.code(), "lan_batch_too_many_items");
    }

    #[test]
    fn prepare_send_rejects_a_malformed_digest_and_an_oversized_attachment() {
        let mut bad_digest = item("first");
        bad_digest.content_digest = "not-a-digest".to_owned();
        assert_eq!(
            lan_prepare_send(
                "batch-ffi".to_owned(),
                "a".repeat(64),
                "Phone".to_owned(),
                vec![bad_digest],
            )
            .expect_err("a malformed content digest is rejected")
            .code(),
            "lan_item_digest_invalid"
        );

        let mut oversized = item("first");
        oversized.attachments = vec![lomo_native::LanAttachmentDto {
            slot: 0,
            source_reference: "huge.bin".to_owned(),
            name: "huge.bin".to_owned(),
            digest: "b".repeat(64),
            size_bytes: 100 * 1_048_576 + 1,
        }];
        assert_eq!(
            lan_prepare_send(
                "batch-ffi".to_owned(),
                "a".repeat(64),
                "Phone".to_owned(),
                vec![oversized],
            )
            .expect_err("an attachment above 100 MiB is rejected")
            .code(),
            "lan_attachment_too_large"
        );
    }

    #[test]
    fn approval_validity_follows_the_recorded_ttl() {
        let dir = root();
        lan_approve_receive(
            root_str(&dir),
            "batch-ffi".to_owned(),
            1_700_000_000_000,
            600_000,
        )
        .expect("approval is recorded");

        assert!(
            lan_approval_is_valid(root_str(&dir), "batch-ffi".to_owned(), 1_700_000_300_000)
                .expect("validity is reported"),
            "an approval inside its TTL authorizes the batch"
        );
        assert!(
            !lan_approval_is_valid(root_str(&dir), "batch-ffi".to_owned(), 1_700_000_600_001)
                .expect("validity is reported"),
            "an approval past its TTL no longer authorizes the batch"
        );
        assert!(
            !lan_approval_is_valid(root_str(&dir), "batch-other".to_owned(), 1_700_000_300_000)
                .expect("validity is reported"),
            "an unapproved batch is never authorized"
        );

        let error =
            lan_approve_receive(root_str(&dir), "batch-ffi".to_owned(), 1_700_000_000_000, 0)
                .expect_err("a non-positive TTL is rejected");
        assert_eq!(error.code(), "lan_ffi_approval_ttl_invalid");
    }

    #[test]
    fn resume_reports_the_unconfirmed_chunk_indices_across_the_boundary() {
        let dir = root();
        let session = "0123456789abcdef0123456789abcdef".to_owned();

        let all = lan_unconfirmed_chunks(
            root_str(&dir),
            session,
            "batch-ffi".to_owned(),
            0,
            0xFFFF,
            5,
        )
        .expect("resume query succeeds");
        assert_eq!(
            all,
            vec![0, 1, 2, 3, 4],
            "with nothing confirmed, every chunk must be sent"
        );

        let error = lan_unconfirmed_chunks(
            root_str(&dir),
            "not-hex".to_owned(),
            "batch-ffi".to_owned(),
            0,
            0xFFFF,
            5,
        )
        .expect_err("a malformed session id is rejected");
        assert_eq!(error.code(), "lan_session_id_invalid");
    }
}
