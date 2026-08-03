//! Behavior Contract (Stage-6 P6-09 LAN lifecycle and platform snapshots)
//!
//! Capability: `lomo-lan` is the sole lifecycle owner. Kotlin may publish bounded Android network
//! and NSD facts, but only Rust validates revisions, protocol versions and socket addresses, binds
//! the listener, exposes the effective state, and releases the listener.
//!
//! Scenarios:
//! - Given no network snapshot, when the service starts, then it fails closed without a listener.
//! - Given local-network permission is denied, when the service starts, then it reports the typed
//!   permission boundary and does not bind.
//! - Given a validated loopback candidate, when the service starts and stops, then Rust owns the
//!   bound port and releases it on stop.
//! - Given a stale network or discovery snapshot, when it is submitted, then it is rejected rather
//!   than replacing newer platform facts.
//! - Given discovery entries with a foreign protocol or an unspecified address, when submitted,
//!   then the edge rejects the whole snapshot before it becomes UI state.
//!
//! Observable outcomes: typed `LomoError` codes, effective service phase/address, accepted
//! discovery endpoints, and the OS ability to rebind the stopped listener address.
//!
//! TDD proof: RED before `runtime.rs` because all lifecycle/snapshot types and methods are absent.
//!
//! Excludes: Android callbacks, NSD APIs, Keystore signing, Compose, pairing and transfer wire.

#![deny(unsafe_code)]

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "contract tests use fail-fast fixtures with explicit diagnostics"
)]
mod tests {
    use std::net::{SocketAddr, TcpListener};
    use std::time::{Duration, Instant};

    use lomo_core::ErrorCategory;
    use lomo_lan::{
        DiscoveredPeerEndpoint, LAN_PROTOCOL_VERSION, LanBindCandidate, LanDiscoverySnapshot,
        LanNetworkSnapshot, LanServiceManager, LanServicePhase,
    };

    #[test]
    fn service_fails_closed_without_a_network_snapshot_or_permission() {
        let root = tempfile::tempdir().expect("app-private root exists");
        let mut manager = LanServiceManager::open(root.path()).expect("manager opens");

        let missing = manager
            .start()
            .expect_err("a listener cannot start without platform network facts");
        assert_eq!(missing.code(), "lan_network_snapshot_missing");

        manager
            .update_network(
                LanNetworkSnapshot::new(
                    1,
                    false,
                    vec![
                        LanBindCandidate::parse("127.0.0.1", 0).expect("loopback candidate parses"),
                    ],
                )
                .expect("bounded snapshot builds"),
            )
            .expect("first snapshot is current");
        let denied = manager
            .start()
            .expect_err("permission denial must fail before bind");
        assert_eq!(denied.category(), ErrorCategory::Permission);
        assert_eq!(denied.code(), "lan_local_network_permission_denied");
        assert_eq!(manager.snapshot().phase(), LanServicePhase::Stopped);
    }

    #[test]
    fn rust_binds_and_releases_the_effective_listener() {
        let root = tempfile::tempdir().expect("app-private root exists");
        let mut manager = LanServiceManager::open(root.path()).expect("manager opens");
        manager
            .update_network(
                LanNetworkSnapshot::new(
                    1,
                    true,
                    vec![
                        LanBindCandidate::parse("127.0.0.1", 0).expect("loopback candidate parses"),
                    ],
                )
                .expect("bounded snapshot builds"),
            )
            .expect("network snapshot publishes");

        let started = manager.start().expect("validated listener starts");
        assert_eq!(started.phase(), LanServicePhase::Listening);
        let address: SocketAddr = started
            .listen_address()
            .expect("listening state has an address")
            .parse()
            .expect("effective address is a socket address");
        assert_ne!(address.port(), 0, "the OS-selected port is observable");

        let already_started = manager.start().expect("start is idempotent");
        assert_eq!(already_started.listen_address(), started.listen_address());

        let stopped = manager.stop();
        assert_eq!(stopped.phase(), LanServicePhase::Stopped);
        TcpListener::bind(address).expect("stopping releases the Rust-owned listener");
    }

    #[test]
    fn idle_listener_poll_is_bounded_so_stop_can_acquire_runtime_ownership() {
        let root = tempfile::tempdir().expect("app-private root exists");
        let mut manager = LanServiceManager::open(root.path()).expect("manager opens");
        manager
            .update_network(
                LanNetworkSnapshot::new(
                    1,
                    true,
                    vec![
                        LanBindCandidate::parse("127.0.0.1", 0).expect("loopback candidate parses"),
                    ],
                )
                .expect("network snapshot builds"),
            )
            .expect("network snapshot publishes");
        let started = manager.start().expect("listener starts");
        let address: SocketAddr = started
            .listen_address()
            .expect("listening state has an address")
            .parse()
            .expect("address parses");

        let poll_started = Instant::now();
        manager.poll_listener(1_000).expect("idle poll is normal");
        assert!(
            poll_started.elapsed() < Duration::from_secs(1),
            "an idle listener must yield so lifecycle stop can acquire the runtime"
        );

        let stopped = manager.stop();
        assert_eq!(stopped.phase(), LanServicePhase::Stopped);
        TcpListener::bind(address).expect("bounded poll permits synchronous listener release");
    }

    #[test]
    fn platform_snapshots_are_monotonic_bounded_and_v2_only() {
        let root = tempfile::tempdir().expect("app-private root exists");
        let mut manager = LanServiceManager::open(root.path()).expect("manager opens");
        manager
            .update_network(
                LanNetworkSnapshot::new(
                    2,
                    true,
                    vec![
                        LanBindCandidate::parse("127.0.0.1", 0).expect("loopback candidate parses"),
                    ],
                )
                .expect("bounded snapshot builds"),
            )
            .expect("new network snapshot publishes");
        let stale_network = manager
            .update_network(
                LanNetworkSnapshot::new(1, true, Vec::new()).expect("empty snapshot is valid"),
            )
            .expect_err("older network facts cannot replace newer facts");
        assert_eq!(stale_network.code(), "lan_network_snapshot_stale");

        let peer = DiscoveredPeerEndpoint::parse(
            &"a".repeat(64),
            "Tablet",
            "127.0.0.1",
            43123,
            LAN_PROTOCOL_VERSION,
        )
        .expect("v2 endpoint parses");
        manager
            .update_discovery(
                LanDiscoverySnapshot::new(4, vec![peer.clone()]).expect("bounded discovery builds"),
            )
            .expect("new discovery snapshot publishes");
        assert_eq!(manager.discovered_peers(), &[peer]);

        let stale_discovery = manager
            .update_discovery(
                LanDiscoverySnapshot::new(3, Vec::new()).expect("empty discovery builds"),
            )
            .expect_err("older NSD facts cannot replace newer facts");
        assert_eq!(stale_discovery.code(), "lan_discovery_snapshot_stale");

        let foreign =
            DiscoveredPeerEndpoint::parse(&"b".repeat(64), "Foreign", "127.0.0.1", 43123, 1)
                .expect_err("there is no legacy protocol decoder");
        assert_eq!(foreign.code(), "lan_discovery_protocol_unsupported");

        let unspecified = DiscoveredPeerEndpoint::parse(
            &"c".repeat(64),
            "Unspecified",
            "0.0.0.0",
            43123,
            LAN_PROTOCOL_VERSION,
        )
        .expect_err("an NSD peer must have a concrete address");
        assert_eq!(unspecified.code(), "lan_discovery_address_invalid");
    }
}
