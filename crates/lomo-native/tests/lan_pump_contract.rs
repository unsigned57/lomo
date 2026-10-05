// adversarial-audit: 假设「锁域拆分后协议状态锁不跨网络等待、入站洪泛有界」；
// 探测的是 CLOSED 声明在当前生产代码上的残余违反，任何 RED 禁止通过修改生产代码转绿。
//
//! Two residual probes against the connection-pool closure claim at the native edge:
//! - `MAX_INBOUND_CONNECTIONS` must bound a connection flood without killing the pump.
//! - Every FFI entry that performs a control send (`begin_lan_pairing`, `confirm_*`,
//!   `prepare/approve/reject_lan_batch`) resolves `self.lan_manager()` — the single global
//!   `Mutex<LanServiceManager>` — and then blocks inside `connect_peer`/`write_frame`/
//!   `read_frame`. While one stalled peer occupies that send, every other manager query
//!   (including the pump's `handle_inbound_frame` and inbox projections) waits on the same
//!   mutex. The lock-free network wait was only implemented for the chunk data path.

#![deny(unsafe_code)]

#[cfg(test)]
mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::result_large_err,
    reason = "adversarial fixtures fail fast; a broken fixture is not a finding"
)]
mod tests {
    use super::support::{OptionTestExt, ResultTestExt};
    use std::net::{SocketAddr, TcpListener, TcpStream};
    use std::time::{Duration, Instant};

    use aws_lc_rs::encoding::AsBigEndian;
    use aws_lc_rs::signature::{ECDSA_P256_SHA256_ASN1_SIGNING, EcdsaKeyPair, KeyPair};
    use lomo_lan::{LAN_PROTOCOL_VERSION, MAX_INBOUND_CONNECTIONS, PAIRING_TTL_MS};
    use lomo_native::{
        EngineConfig, LanBindCandidateDto, LanDeviceIdentityDto, LanDiscoveredPeerDto,
        LanDiscoverySnapshotDto, LanNetworkSnapshotDto, LomoEngine,
    };

    fn engine() -> (tempfile::TempDir, LomoEngine) {
        let temporary = tempfile::tempdir().test_ok("temporary root");
        let control = temporary.path().join("control");
        let exchange = temporary.path().join("exchange");
        std::fs::create_dir(&control).test_ok("control root");
        std::fs::create_dir(&exchange).test_ok("exchange root");
        let engine = LomoEngine::open(EngineConfig {
            control_root: control.display().to_string(),
            exchange_root: exchange.display().to_string(),
            workspace: None,
            bootstrap_deadline_millis: 30_000,
        })
        .test_ok("engine opens");
        (temporary, engine)
    }

    fn lan_engine(name: &str) -> (tempfile::TempDir, LomoEngine, String) {
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
        (root, engine, local.device_id)
    }

    fn now_ms() -> i64 {
        i64::try_from(
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .expect("system clock is past the epoch")
                .as_millis(),
        )
        .expect("millis fit i64")
    }

    #[test]
    fn inbound_flood_is_bounded_at_the_worker_cap_and_the_pump_survives() {
        let (_root_a, engine_a, _id_a) = lan_engine("Phone");
        let (_root_b, engine_b, id_b) = lan_engine("Tablet");
        engine_a.start_lan_service().test_ok("phone listens");
        let address_b: SocketAddr = engine_b
            .start_lan_service()
            .test_ok("tablet listens")
            .listen_address
            .test_ok("tablet address")
            .parse()
            .test_ok("tablet address parses");
        let baseline = engine_b
            .await_lan_inbox(0, 10_000)
            .test_ok("startup generation reads")
            .generation;

        // Sixteen idle connections occupy every worker (each blocks in read_frame until the
        // 5s channel deadline); the seventeenth must be refused at admission.
        let mut flood = Vec::new();
        for _ in 0..MAX_INBOUND_CONNECTIONS {
            flood.push(TcpStream::connect(address_b).expect("flood connection opens"));
        }
        let overflow = TcpStream::connect(address_b).expect("overflow connection opens");

        let mut rejected = engine_b
            .await_lan_inbox(baseline, 10_000)
            .test_ok("inbox wait returns");
        for _attempt in 0..40 {
            if rejected
                .last_rejection_diagnostic
                .as_deref()
                .is_some_and(|diagnostic| diagnostic.contains("lan_inbound_capacity"))
            {
                break;
            }
            rejected = engine_b
                .await_lan_inbox(rejected.generation, 10_000)
                .test_ok("inbox wait returns");
        }
        assert!(
            rejected
                .last_rejection_diagnostic
                .as_deref()
                .is_some_and(|diagnostic| diagnostic.contains("lan_inbound_capacity")),
            "the overflow connection must be refused by the bounded worker cap, got: {:?}",
            rejected.last_rejection_diagnostic
        );
        drop(overflow);
        drop(flood);

        // The pump stays alive: a valid PairHello still completes the round-trip.
        engine_a
            .update_lan_discovery_snapshot(LanDiscoverySnapshotDto {
                revision: 1,
                peers: vec![LanDiscoveredPeerDto {
                    device_id: id_b.clone(),
                    display_name: "Tablet".to_owned(),
                    host: "127.0.0.1".to_owned(),
                    port: u32::from(address_b.port()),
                    protocol_version: u32::from(LAN_PROTOCOL_VERSION),
                }],
            })
            .test_ok("phone discovers tablet");
        let challenge = engine_a
            .begin_lan_pairing(id_b, now_ms(), PAIRING_TTL_MS)
            .test_ok("pairing still exchanges after the flood");
        assert!(!challenge.pairing_id.is_empty());
        let observed = engine_b
            .await_lan_inbox(rejected.generation, 10_000)
            .test_ok("tablet surfaces the pairing work");
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
    fn a_control_send_holds_the_protocol_lock_across_its_network_wait() {
        // A peer that accepts TCP but never answers: begin_lan_pairing blocks inside its
        // socket read until the pairing deadline (~5s). Meanwhile an unrelated manager
        // query on the same engine must still be instant — unless the send is holding the
        // single global `Mutex<LanServiceManager>` across the network wait.
        let silent = TcpListener::bind("127.0.0.1:0").expect("silent listener binds");
        let silent_address = silent.local_addr().expect("silent address");
        // The silent peer keeps the accepted socket open until the test process ends.
        let _parked = std::thread::spawn(move || {
            let _held = silent.accept().expect("silent peer accepts");
            std::thread::sleep(Duration::from_secs(15));
        });

        let (_root, engine, _id) = lan_engine("Phone");
        engine.start_lan_service().test_ok("phone listens");
        engine
            .update_lan_discovery_snapshot(LanDiscoverySnapshotDto {
                revision: 1,
                peers: vec![LanDiscoveredPeerDto {
                    device_id: "a".repeat(64),
                    display_name: "Silent".to_owned(),
                    host: "127.0.0.1".to_owned(),
                    port: u32::from(silent_address.port()),
                    protocol_version: u32::from(LAN_PROTOCOL_VERSION),
                }],
            })
            .test_ok("silent peer is discovered");

        let (send_result, contended_elapsed) = std::thread::scope(|scope| {
            let sender =
                scope.spawn(|| engine.begin_lan_pairing("a".repeat(64), now_ms(), PAIRING_TTL_MS));
            // Let the pairing thread connect, write the hello and enter its blocked read.
            std::thread::sleep(Duration::from_millis(400));
            let started = Instant::now();
            let _peers = engine.list_lan_peers();
            let elapsed = started.elapsed();
            let result = sender.join().expect("pairing thread joins");
            (result, elapsed)
        });

        assert!(
            send_result.is_err(),
            "a silent peer cannot complete a pairing handshake"
        );
        assert!(
            contended_elapsed < Duration::from_secs(1),
            "an unrelated manager query waited {contended_elapsed:?} — \
             `begin_lan_pairing` held the global `Mutex<LanServiceManager>` across a ~5s \
             socket connect/write/read, so one stalled peer froze the entire LAN runtime \
             (inbox waits, inbound frames, every other session's chunk application)"
        );
    }
}
