//! Behavior Contract (Stage-6 P6-09 engine-owned LAN runtime FFI)
//!
//! Capability: the one `LomoEngine` handle owns LAN lifecycle and accepts only conversion DTOs for
//! Android network/NSD facts; no second native service handle or Kotlin bind decision exists.
//!
//! Scenarios:
//! - Given a no-workspace engine and a validated network snapshot, when LAN starts, then the same
//!   engine reports the Rust-bound address and stop releases it.
//! - Given a stale platform snapshot, when submitted through `BoltFFI`, then the stable owner error
//!   crosses unchanged.
//! - Given a discovery snapshot at the active protocol version, when submitted and queried, then
//!   the bounded Rust-validated endpoint is returned; a foreign version is rejected at conversion.
//! - Given no pending session, when its challenge or authenticated snapshot is queried, then the
//!   engine rejects the unknown session identity instead of returning an empty sentinel.
//! - Given no Ready workspace, when prepare or approve is requested, then the native boundary
//!   fails before LAN I/O; preview/reject still fail against their own missing session/batch state.
//! - Given an inbox wait whose generation did not advance, when it completes, then the DTO carries
//!   no snapshot, so a caller never refetches identical state through a second call.
//!
//! Observable outcomes: `LanServiceSnapshotDto`, discovered endpoint DTOs and `EngineError.code`.
//!
//! TDD proof: RED before the native edit because `LomoEngine` had no LAN runtime field or methods.
//!
//! Test Change Justification:
//! Reason category: protocol owner moved to `lomo-lan` (T25).
//! Old behavior/assertion being replaced: discovery accepted hardcoded protocol version 2; approve
//! used a 60s TTL that Kotlin also chose.
//! Why old assertion is no longer correct: v3 AEAD control is the only decoder, and lifetimes are
//! crate constants echoed through `lan_protocol_limits`.
//! Coverage preserved by: the same snapshot round-trip plus an explicit foreign-version rejection;
//! approve still fails `lan_workspace_not_ready` when the workspace is missing.
//! Why this is not fitting the test to the implementation: the product contract is "one protocol
//! version, Kotlin does not choose TTL", not a frozen v2 wire.
//!
//! Excludes: generated Kotlin, Android network callbacks, Keystore, pairing and transfer wire.

#![deny(unsafe_code)]

#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    use super::support::{OptionTestExt, ResultTestExt};
    use aws_lc_rs::{
        encoding::AsBigEndian,
        signature::{ECDSA_P256_SHA256_ASN1_SIGNING, EcdsaKeyPair, KeyPair},
    };
    use std::{
        fs,
        net::{SocketAddr, TcpListener},
    };

    use lomo_lan::{APPROVAL_TTL_MS, LAN_PROTOCOL_VERSION, PAIRING_TTL_MS, SESSION_TTL_MS};
    use lomo_native::{
        EngineConfig, LanBindCandidateDto, LanDeviceIdentityDto, LanDiscoveredPeerDto,
        LanDiscoverySnapshotDto, LanNetworkSnapshotDto, LanSendItemDto, LanServicePhaseDto,
        LomoEngine,
    };

    fn engine() -> (tempfile::TempDir, LomoEngine) {
        let temporary = tempfile::tempdir().test_ok("temporary root");
        let control = temporary.path().join("control");
        let exchange = temporary.path().join("exchange");
        fs::create_dir(&control).test_ok("control root");
        fs::create_dir(&exchange).test_ok("exchange root");
        let engine = LomoEngine::open(EngineConfig {
            control_root: control.display().to_string(),
            exchange_root: exchange.display().to_string(),
            workspace: None,
            bootstrap_deadline_millis: 30_000,
        })
        .test_ok("engine opens");
        (temporary, engine)
    }

    #[test]
    fn the_engine_owns_start_stop_and_monotonic_network_facts() {
        let (_root, engine) = engine();
        let shape = engine.lan_transfer_shape();
        assert_eq!(shape.body_slot, u32::from(lomo_lan::ATTACHMENT_SLOT_BODY));
        assert_eq!(shape.chunk_plaintext_bytes, 256 * 1_024 - 128);
        engine
            .update_lan_network_snapshot(LanNetworkSnapshotDto {
                revision: 2,
                local_network_permission_granted: true,
                candidates: vec![LanBindCandidateDto {
                    host: "127.0.0.1".to_owned(),
                    port: 0,
                }],
            })
            .test_ok("network facts publish");

        let started = engine.start_lan_service().test_ok("LAN starts");
        assert_eq!(started.phase, LanServicePhaseDto::Listening);
        let address: SocketAddr = started
            .listen_address
            .test_ok("listening state has an address")
            .parse()
            .test_ok("address parses");

        let stale = engine
            .update_lan_network_snapshot(LanNetworkSnapshotDto {
                revision: 1,
                local_network_permission_granted: true,
                candidates: Vec::new(),
            })
            .test_err("stale facts fail closed");
        assert_eq!(stale.code(), "lan_network_snapshot_stale");

        let stopped = engine.stop_lan_service().test_ok("LAN stops");
        assert_eq!(stopped.phase, LanServicePhaseDto::Stopped);
        TcpListener::bind(address).test_ok("stop releases listener");
    }

    #[test]
    fn discovery_accepts_only_the_active_protocol_version_and_round_trips_as_validated_facts() {
        let (_root, engine) = engine();
        let peer = LanDiscoveredPeerDto {
            device_id: "a".repeat(64),
            display_name: "Tablet".to_owned(),
            host: "127.0.0.1".to_owned(),
            port: 43123,
            protocol_version: u32::from(LAN_PROTOCOL_VERSION),
        };
        engine
            .update_lan_discovery_snapshot(LanDiscoverySnapshotDto {
                revision: 1,
                peers: vec![peer.clone()],
            })
            .test_ok("active protocol discovery publishes");
        assert_eq!(
            engine.list_lan_discovered_peers().test_ok("list"),
            vec![peer]
        );

        let foreign = engine
            .update_lan_discovery_snapshot(LanDiscoverySnapshotDto {
                revision: 2,
                peers: vec![LanDiscoveredPeerDto {
                    device_id: "b".repeat(64),
                    display_name: "Legacy".to_owned(),
                    host: "127.0.0.1".to_owned(),
                    port: 43123,
                    protocol_version: 1,
                }],
            })
            .test_err("foreign protocol is rejected");
        assert_eq!(foreign.code(), "lan_discovery_protocol_unsupported");
    }

    #[test]
    fn protocol_limits_are_owned_by_rust_and_not_invented_at_the_ffi_edge() {
        let (_root, engine) = engine();
        let limits = engine.lan_protocol_limits();
        assert_eq!(limits.protocol_version, u32::from(LAN_PROTOCOL_VERSION));
        assert_eq!(limits.pairing_ttl_ms, PAIRING_TTL_MS);
        assert_eq!(limits.session_ttl_ms, SESSION_TTL_MS);
        assert_eq!(limits.approval_ttl_ms, APPROVAL_TTL_MS);
    }

    #[test]
    fn engine_keeps_pairing_identity_and_trust_queries_on_the_same_handle() {
        let (_root, engine) = engine();
        let wait = engine.await_lan_inbox(0, 0).test_ok("inbox wait queries");
        assert_eq!(wait.generation, 0);
        assert!(
            wait.inbox.is_none(),
            "a fresh engine publishes no inbox work"
        );

        let key = EcdsaKeyPair::generate(&ECDSA_P256_SHA256_ASN1_SIGNING)
            .test_ok("Keystore fixture generates");
        let encoded: aws_lc_rs::encoding::EcPublicKeyUncompressedBin<'_> =
            key.public_key().as_be_bytes().test_ok("public key exports");
        let public_key = encoded.as_ref().to_vec();
        let local_identity = engine
            .configure_lan_identity(LanDeviceIdentityDto {
                public_key,
                display_name: "Phone".to_owned(),
            })
            .test_ok("public identity configures");
        assert_eq!(local_identity.device_id.len(), 64);
        assert_eq!(local_identity.display_name, "Phone");

        let peers = engine.list_lan_peers().test_ok("peer registry lists");
        assert_eq!(peers.total, 0);

        let unknown = engine
            .lan_pairing_challenge("0".repeat(32))
            .test_err("unknown challenge fails closed");
        assert_eq!(unknown.code(), "lan_pairing_unknown");
        let unknown_decline = engine
            .decline_lan_pairing("0".repeat(32))
            .test_err("unknown decline fails closed");
        assert_eq!(unknown_decline.code(), "lan_pairing_unknown");

        let unknown_snapshot = engine
            .lan_session_snapshot("0".repeat(32))
            .test_err("unknown authenticated session fails closed");
        assert_eq!(unknown_snapshot.code(), "lan_session_unknown");

        let unknown_prepare = engine
            .prepare_lan_batch(
                "0".repeat(32),
                "batch-native-runtime".to_owned(),
                vec![LanSendItemDto {
                    timestamp_ms: 1_700_000_000_000,
                    content_digest: "0".repeat(64),
                    content_bytes: 4,
                    title: "Preview".to_owned(),
                    attachments: Vec::new(),
                }],
            )
            .test_err("prepare requires an authenticated session");
        assert_eq!(unknown_prepare.code(), "lan_workspace_not_ready");

        let unknown_approve = engine
            .approve_lan_batch(
                "0".repeat(32),
                "batch-native-runtime".to_owned(),
                1_000,
                APPROVAL_TTL_MS,
            )
            .test_err("approval requires a Ready workspace");
        assert_eq!(unknown_approve.code(), "lan_workspace_not_ready");

        let unknown_reject = engine
            .reject_lan_batch("0".repeat(32), "batch-native-runtime".to_owned(), 1_000)
            .test_err("rejection requires an authenticated session");
        assert_eq!(unknown_reject.code(), "lan_session_not_authenticated");
    }

    fn lan_engine(name: &str) -> (tempfile::TempDir, LomoEngine, EcdsaKeyPair, String) {
        let (root, engine) = engine();
        let key = EcdsaKeyPair::generate(&ECDSA_P256_SHA256_ASN1_SIGNING)
            .test_ok("Keystore fixture generates");
        let encoded: aws_lc_rs::encoding::EcPublicKeyUncompressedBin<'_> =
            key.public_key().as_be_bytes().test_ok("public key exports");
        let local = engine
            .configure_lan_identity(LanDeviceIdentityDto {
                public_key: encoded.as_ref().to_vec(),
                display_name: name.to_owned(),
            })
            .test_ok("public identity configures");
        engine
            .update_lan_network_snapshot(LanNetworkSnapshotDto {
                revision: 1,
                local_network_permission_granted: true,
                candidates: vec![LanBindCandidateDto {
                    host: "127.0.0.1".to_owned(),
                    port: 0,
                }],
            })
            .test_ok("network facts publish");
        (root, engine, key, local.device_id)
    }

    fn sign_transcript(key: &EcdsaKeyPair, transcript: &[u8]) -> Vec<u8> {
        key.sign(&aws_lc_rs::rand::SystemRandom::new(), transcript)
            .test_ok("transcript signs")
            .as_ref()
            .to_vec()
    }

    fn now_ms() -> i64 {
        i64::try_from(
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .test_ok("system clock is past the epoch")
                .as_millis(),
        )
        .test_ok("millis fit i64")
    }

    fn peer_of(device_id: &str, name: &str, address: SocketAddr) -> LanDiscoveredPeerDto {
        LanDiscoveredPeerDto {
            device_id: device_id.to_owned(),
            display_name: name.to_owned(),
            host: "127.0.0.1".to_owned(),
            port: u32::from(address.port()),
            protocol_version: u32::from(LAN_PROTOCOL_VERSION),
        }
    }

    #[test]
    fn a_rejected_inbound_connection_is_counted_and_the_service_stays_alive() {
        let (_root_a, engine_a, _key_a, _id_a) = lan_engine("Phone");
        let (_root_b, engine_b, _key_b, id_b) = lan_engine("Tablet");
        let _start_a = engine_a.start_lan_service().test_ok("phone listens");
        let address_b: SocketAddr = engine_b
            .start_lan_service()
            .test_ok("tablet listens")
            .listen_address
            .test_ok("tablet address")
            .parse()
            .test_ok("tablet address parses");

        let baseline = engine_b
            .await_lan_inbox(0, 10_000)
            .test_ok("the first inbox wait only reports the startup generation")
            .generation;

        let mut junk = std::net::TcpStream::connect(address_b).test_ok("junk connection opens");
        std::io::Write::write_all(&mut junk, &[0xFF; 64]).test_ok("junk bytes write");
        drop(junk);
        let first = engine_b
            .await_lan_inbox(baseline, 10_000)
            .test_ok("a malformed connection is connection-scoped, not a pump failure");
        assert_eq!(first.rejected_connection_count, 1);
        assert!(first.last_rejection_diagnostic.is_some());

        let mut junk =
            std::net::TcpStream::connect(address_b).test_ok("second junk connection opens");
        std::io::Write::write_all(&mut junk, &[0x00; 4]).test_ok("second junk bytes write");
        drop(junk);
        let second = engine_b
            .await_lan_inbox(first.generation, 10_000)
            .test_ok("a second rejection is counted without killing the pump");
        assert_eq!(second.rejected_connection_count, 2);

        engine_a
            .update_lan_discovery_snapshot(LanDiscoverySnapshotDto {
                revision: 1,
                peers: vec![peer_of(&id_b, "Tablet", address_b)],
            })
            .test_ok("phone discovers tablet");
        let challenge = engine_a
            .begin_lan_pairing(id_b, now_ms(), PAIRING_TTL_MS)
            .test_ok("a valid hello still exchanges after rejected input");
        assert!(!challenge.pairing_id.is_empty());
        let observed = engine_b
            .await_lan_inbox(second.generation, 10_000)
            .test_ok("the tablet inbox surfaces the valid pairing work");
        assert_eq!(
            observed
                .inbox
                .test_ok("an advanced generation carries its snapshot")
                .pairing_challenges
                .len(),
            1
        );
    }

    #[test]
    fn an_inbound_storage_fault_is_a_sticky_failure_for_every_waiter() {
        let (_root_a, engine_a, key_a, _id_a) = lan_engine("Phone");
        let (root_b, engine_b, key_b, id_b) = lan_engine("Tablet");
        engine_a
            .start_lan_service()
            .test_ok("phone listens")
            .listen_address
            .test_ok("phone address")
            .parse::<SocketAddr>()
            .test_ok("phone address parses");
        let address_b: SocketAddr = engine_b
            .start_lan_service()
            .test_ok("tablet listens")
            .listen_address
            .test_ok("tablet address")
            .parse()
            .test_ok("tablet address parses");

        engine_a
            .update_lan_discovery_snapshot(LanDiscoverySnapshotDto {
                revision: 1,
                peers: vec![peer_of(&id_b, "Tablet", address_b)],
            })
            .test_ok("phone discovers tablet");
        let now = now_ms();
        let challenge_a = engine_a
            .begin_lan_pairing(id_b, now, PAIRING_TTL_MS)
            .test_ok("pairing hello exchanges");
        let inbox_b = engine_b
            .await_lan_inbox(0, 10_000)
            .test_ok("tablet wakes for the hello");
        let challenge_b = inbox_b
            .inbox
            .test_ok("an advanced generation carries its snapshot")
            .pairing_challenges
            .first()
            .test_ok("responder challenge exists")
            .clone();
        engine_b
            .confirm_lan_pairing(
                challenge_b.pairing_id.clone(),
                sign_transcript(&key_b, &challenge_b.transcript_to_sign),
                now,
            )
            .test_ok("tablet confirms locally and awaits the initiator signature");

        fs::remove_dir_all(root_b.path().join("control"))
            .test_ok("the responder journal root is deleted");
        engine_a
            .confirm_lan_pairing(
                challenge_a.pairing_id,
                sign_transcript(&key_a, &challenge_a.transcript_to_sign),
                now,
            )
            .test_ok("the initiator confirm still sends");

        // The responder's own confirm advanced its generation before the inbound confirm hit the
        // deleted journal: drain that legitimate observation, then every waiter sees the fault.
        let settled = engine_b
            .await_lan_inbox(inbox_b.generation, 10_000)
            .test_ok("the local confirm observation settles");
        let first = engine_b
            .await_lan_inbox(settled.generation, 10_000)
            .test_err("the storage fault is a pump failure");
        let second = engine_b
            .await_lan_inbox(0, 10_000)
            .test_err("a second waiter observes the same failure");
        assert_eq!(first.code(), second.code());
    }

    #[test]
    fn an_unchanged_generation_returns_no_snapshot_and_no_side_channel_fetch() {
        let (_root, engine) = engine();
        let settled = engine
            .await_lan_inbox(0, 200)
            .test_ok("the first wait reports the current generation");

        let idle = engine
            .await_lan_inbox(settled.generation, 200)
            .test_ok("an unchanged generation still completes the wait");
        assert_eq!(
            idle.generation, settled.generation,
            "nothing advanced while the engine was idle"
        );
        assert!(
            idle.inbox.is_none(),
            "an unchanged generation must not rebuild the inbox snapshot"
        );
    }
}
