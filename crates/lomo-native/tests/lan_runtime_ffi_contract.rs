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
//! - Given a v2 NSD snapshot, when submitted and queried, then the bounded Rust-validated endpoint
//!   is returned; a foreign version is rejected at conversion.
//! - Given no pending session, when its challenge or authenticated snapshot is queried, then the
//!   engine rejects the unknown session identity instead of returning an empty sentinel.
//! - Given no Ready workspace, when prepare or approve is requested, then the native boundary
//!   fails before LAN I/O; preview/reject still fail against their own missing session/batch state.
//!
//! Observable outcomes: `LanServiceSnapshotDto`, discovered endpoint DTOs and `EngineError.code`.
//!
//! TDD proof: RED before the native edit because `LomoEngine` had no LAN runtime field or methods.
//!
//! Excludes: generated Kotlin, Android network callbacks, Keystore, pairing and transfer wire.

#![deny(unsafe_code)]

#[cfg(test)]
mod support;

#[cfg(test)]
mod tests {
    use super::support::{OptionTestExt, ResultTestExt};
    use aws_lc_rs::encoding::AsBigEndian;
    use aws_lc_rs::signature::{ECDSA_P256_SHA256_ASN1_SIGNING, EcdsaKeyPair, KeyPair};
    use std::fs;
    use std::net::{SocketAddr, TcpListener};

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
    fn discovery_is_v2_only_and_round_trips_as_validated_facts() {
        let (_root, engine) = engine();
        let peer = LanDiscoveredPeerDto {
            device_id: "a".repeat(64),
            display_name: "Tablet".to_owned(),
            host: "127.0.0.1".to_owned(),
            port: 43123,
            protocol_version: 2,
        };
        engine
            .update_lan_discovery_snapshot(LanDiscoverySnapshotDto {
                revision: 1,
                peers: vec![peer.clone()],
            })
            .test_ok("v2 discovery publishes");
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
    fn engine_keeps_pairing_identity_and_trust_queries_on_the_same_handle() {
        let (_root, engine) = engine();
        let inbox = engine.lan_runtime_inbox().test_ok("runtime inbox queries");
        assert!(inbox.pairing_challenges.is_empty());
        assert!(inbox.session_challenges.is_empty());
        assert!(inbox.active_sessions.is_empty());
        assert!(inbox.pending_batches.is_empty());
        assert!(inbox.batch_recoveries.is_empty());
        assert!(inbox.committable_items.is_empty());
        assert!(inbox.outgoing_batches.is_empty());

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

        let unknown_session = engine
            .lan_session_challenge("0".repeat(32))
            .test_err("unknown session challenge fails closed");
        assert_eq!(unknown_session.code(), "lan_session_unknown");
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

        let unknown_batch = engine
            .lan_batch_preview("batch-native-runtime".to_owned())
            .test_err("preview requires a prepared batch");
        assert_eq!(unknown_batch.code(), "lan_batch_unknown");

        let unknown_approve = engine
            .approve_lan_batch(
                "0".repeat(32),
                "batch-native-runtime".to_owned(),
                1_000,
                60_000,
            )
            .test_err("approval requires a Ready workspace");
        assert_eq!(unknown_approve.code(), "lan_workspace_not_ready");

        let unknown_reject = engine
            .reject_lan_batch("0".repeat(32), "batch-native-runtime".to_owned(), 1_000)
            .test_err("rejection requires an authenticated session");
        assert_eq!(unknown_reject.code(), "lan_session_not_authenticated");
    }
}
