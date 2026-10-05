// adversarial-audit: peer-declared hello deadlines are never upper-bounded;
// a far-future `deadline_ms` fills the bounded pending-pairing / pending-session budget with
// entries that TTL eviction can never release, permanently locking out legitimate peers
//!
//! Hypothesis under audit: `apply_pair_hello`/`apply_session_hello` reject only *past* deadlines
//! (`assert_before_deadline`), while `admit_pair_hello`/the pending-session cap evict strictly on
//! `deadline_ms > now_ms`. An attacker (or a trusted-but-hostile peer) who declares
//! `deadline_ms = i64::MAX` parks a pending entry that outlives every TTL horizon, so the
//! "capacity + TTL" resource budget is defeated after `MAX_PENDING_*` hellos.
//!
//! If the final hello in each test comes back `FrameKind::Error` with `lan_pairing_capacity` /
//! `lan_session_capacity` long after `PAIRING_TTL_MS`/`SESSION_TTL_MS` elapsed, the hole is real.
//!
//! Also locked: a forged confirmed-log coordinate outside the batch plan is dropped at open
//! (open-coordinate reconciliation must not admit coordinates the plan never reserved).

#![deny(unsafe_code)]

#[cfg(test)]
#[expect(
    clippy::expect_used,
    clippy::indexing_slicing,
    clippy::cast_possible_truncation,
    clippy::uninlined_format_args,
    clippy::string_lit_as_bytes,
    clippy::redundant_clone,
    clippy::items_after_statements,
    reason = "adversarial fixtures fail fast; a broken fixture is not a finding"
)]
mod tests {
    use aws_lc_rs::agreement::{EphemeralPrivateKey, X25519};
    use aws_lc_rs::encoding::AsBigEndian;
    use aws_lc_rs::rand::SystemRandom;
    use aws_lc_rs::signature::{ECDSA_P256_SHA256_ASN1_SIGNING, EcdsaKeyPair, KeyPair};
    use lomo_lan::{
        ATTACHMENT_SLOT_BODY, ChunkBinding, DeviceId, DevicePublicKey, DisplayName, FrameKind,
        LanBatchId, LanBatchPlan, LanDurableBatch, LanFrame, LanItemPlan, LanJournal,
        LanJournalPaths, LanServiceManager, LanSessionId, MAX_PENDING_PAIRINGS,
        MAX_PENDING_SESSIONS, PAIRING_TTL_MS, PeerRecord, SESSION_TTL_MS,
    };
    use sha2::{Digest, Sha256};
    use std::net::SocketAddr;

    const BASE_MS: i64 = 1_700_000_000_000;
    const FAR_FUTURE: i64 = i64::MAX;

    /// Wire control payloads use u16 length-prefixed fields (`push_wire_field` in runtime.rs).
    fn push_wire_field(buffer: &mut Vec<u8>, field: &[u8]) {
        buffer.extend_from_slice(&(field.len() as u16).to_be_bytes());
        buffer.extend_from_slice(field);
    }

    /// Journal confirmed-log entries use u32 length-prefixed fields (`push_field` in journal.rs).
    fn push_record_field(buffer: &mut Vec<u8>, field: &[u8]) {
        buffer.extend_from_slice(&(field.len() as u32).to_be_bytes());
        buffer.extend_from_slice(field);
    }

    /// Reads the refusal code out of a `FrameKind::Error` reply payload (`u16 len + utf8`).
    fn error_code(frame: &LanFrame) -> String {
        if frame.kind() != FrameKind::Error {
            return format!("<kind {:?}>", frame.kind());
        }
        let payload = frame.payload();
        if payload.len() < 2 {
            return format!("<short error payload {:?}>", payload);
        }
        let len = u16::from_be_bytes(payload[0..2].try_into().expect("len")) as usize;
        match String::from_utf8(payload[2..payload.len().min(2 + len)].to_vec()) {
            Ok(code) if 2 + len == payload.len() => code,
            _ => format!("<malformed error payload {:?}>", payload),
        }
    }

    fn ephemeral_public() -> Vec<u8> {
        let private = EphemeralPrivateKey::generate(&X25519, &SystemRandom::new())
            .expect("ephemeral key generates");
        private
            .compute_public_key()
            .expect("ephemeral public derives")
            .as_ref()
            .to_vec()
    }

    fn device_key() -> (EcdsaKeyPair, DevicePublicKey) {
        let key = EcdsaKeyPair::generate(&ECDSA_P256_SHA256_ASN1_SIGNING)
            .expect("P-256 identity generates");
        let encoded: aws_lc_rs::encoding::EcPublicKeyUncompressedBin<'_> =
            key.public_key().as_be_bytes().expect("public key exports");
        let public = DevicePublicKey::parse(encoded.as_ref()).expect("public key parses");
        (key, public)
    }

    /// A manager with identity configured; `handle_inbound_frame` admission logic does not
    /// require a bound listener, so the pump-free state machine is exercised directly.
    fn manager(name: &str) -> (tempfile::TempDir, LanServiceManager) {
        let root = tempfile::tempdir().expect("app-private root exists");
        let mut manager = LanServiceManager::open(root.path()).expect("runtime opens");
        let (_key, public) = device_key();
        manager
            .configure_identity(public, DisplayName::parse(name).expect("name parses"))
            .expect("identity configures");
        (root, manager)
    }

    fn pair_hello_frame(
        pairing_id: &str,
        public_key: &DevicePublicKey,
        deadline_ms: i64,
    ) -> LanFrame {
        let mut payload = Vec::new();
        push_wire_field(&mut payload, pairing_id.as_bytes());
        push_wire_field(&mut payload, public_key.as_bytes());
        push_wire_field(&mut payload, "Attacker".as_bytes());
        push_wire_field(&mut payload, &ephemeral_public());
        payload.extend_from_slice(&4242_u16.to_be_bytes());
        payload.extend_from_slice(&deadline_ms.to_be_bytes());
        LanFrame::new(FrameKind::PairHello, payload).expect("frame fits the control ceiling")
    }

    fn session_hello_frame(
        session_id: &str,
        public_key: &DevicePublicKey,
        deadline_ms: i64,
    ) -> LanFrame {
        let mut payload = Vec::new();
        push_wire_field(&mut payload, session_id.as_bytes());
        push_wire_field(&mut payload, public_key.as_bytes());
        push_wire_field(&mut payload, &ephemeral_public());
        payload.extend_from_slice(&4242_u16.to_be_bytes());
        payload.extend_from_slice(&deadline_ms.to_be_bytes());
        LanFrame::new(FrameKind::SessionHello, payload).expect("frame fits the control ceiling")
    }

    #[test]
    fn a_far_future_pair_hello_deadline_pins_pending_capacity_forever() {
        let (_root, mut tablet) = manager("Tablet");
        let (_attacker_key, attacker_public) = device_key();
        let source: SocketAddr = "192.0.2.66:4000".parse().expect("source address");

        // Eight hellos per source per 1s window: walk `now` forward so the rate limiter admits
        // exactly MAX_PENDING_PAIRINGS entries, each carrying an attacker-declared deadline that
        // never expires.
        let mut now = BASE_MS;
        let mut admitted = 0_usize;
        while admitted < MAX_PENDING_PAIRINGS {
            for index in 0..8 {
                let pairing_id = format!("{:032x}", admitted * 8 + index + 1);
                let reply = tablet
                    .handle_inbound_frame(
                        source,
                        &pair_hello_frame(&pairing_id, &attacker_public, FAR_FUTURE),
                        now,
                    )
                    .expect("hello is handled")
                    .expect("admitted hello earns a reply");
                assert_eq!(reply.kind(), FrameKind::PairAccept, "hello must admit");
            }
            admitted += 8;
            now += 1_000;
        }

        let over_cap = tablet
            .handle_inbound_frame(
                source,
                &pair_hello_frame(
                    "ffffffffffffffffffffffffffffffff",
                    &attacker_public,
                    FAR_FUTURE,
                ),
                now,
            )
            .expect("hello is handled")
            .expect("refused hello still earns a reply");
        assert_eq!(error_code(&over_cap), "lan_pairing_capacity");

        // The promised TTL bound: once PAIRING_TTL_MS elapses, stale pendings must free the
        // budget. With attacker-declared deadlines they never do.
        let after_ttl = now + PAIRING_TTL_MS + 60_000;
        let reply = tablet
            .handle_inbound_frame(
                source,
                &pair_hello_frame(
                    "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                    &attacker_public,
                    after_ttl + PAIRING_TTL_MS,
                ),
                after_ttl,
            )
            .expect("hello is handled")
            .expect("the reply frame exists either way");
        assert_eq!(
            reply.kind(),
            FrameKind::PairAccept,
            "after the pairing TTL a stale pending set must evict and admit a legitimate hello; \
             got refusal code {}",
            if reply.kind() == FrameKind::Error {
                error_code(&reply)
            } else {
                "none".to_owned()
            },
        );
    }

    #[test]
    fn a_far_future_session_hello_deadline_pins_pending_session_capacity_forever() {
        let (root, tablet) = manager("Tablet");
        let (_attacker_key, attacker_public) = device_key();
        let source: SocketAddr = "192.0.2.99:4000".parse().expect("source address");

        // Seed trust directly into the durable journal, exactly as a completed pairing would.
        {
            let mut journal =
                LanJournal::open(LanJournalPaths::new(root.path()).expect("journal paths"))
                    .expect("journal opens");
            journal
                .store_peer(PeerRecord::paired(
                    attacker_public.clone(),
                    DisplayName::parse("Trusted Attacker").expect("name parses"),
                    BASE_MS,
                ))
                .expect("trusted peer stores");
        }
        // Reopen so the manager observes the seeded peer record.
        drop(tablet);
        let mut tablet = LanServiceManager::open(root.path()).expect("runtime reopens");
        let (_key, public) = device_key();
        tablet
            .configure_identity(public, DisplayName::parse("Tablet").expect("name parses"))
            .expect("identity configures");

        let mut now = BASE_MS;
        for index in 0..MAX_PENDING_SESSIONS {
            let session_id = format!("{:032x}", index + 1);
            let reply = tablet
                .handle_inbound_frame(
                    source,
                    &session_hello_frame(&session_id, &attacker_public, FAR_FUTURE),
                    now,
                )
                .expect("session hello is handled")
                .expect("admitted hello earns a reply");
            assert_eq!(
                reply.kind(),
                FrameKind::SessionAccept,
                "session hello {index} must admit; got {}",
                if reply.kind() == FrameKind::Error {
                    error_code(&reply)
                } else {
                    "no error".to_owned()
                },
            );
        }

        let over_cap = tablet
            .handle_inbound_frame(
                source,
                &session_hello_frame(
                    "ffffffffffffffffffffffffffffffff",
                    &attacker_public,
                    FAR_FUTURE,
                ),
                now,
            )
            .expect("session hello is handled")
            .expect("refused hello still earns a reply");
        assert_eq!(error_code(&over_cap), "lan_session_capacity");

        // Session pendings must expire past SESSION_TTL_MS; attacker-declared deadlines keep them
        // pinned, so the trusted peer (or its impersonator of record) is locked out forever.
        now += SESSION_TTL_MS + 60_000;
        let reply = tablet
            .handle_inbound_frame(
                source,
                &session_hello_frame(
                    "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                    &attacker_public,
                    now + SESSION_TTL_MS,
                ),
                now,
            )
            .expect("session hello is handled")
            .expect("the reply frame exists either way");
        assert_eq!(
            reply.kind(),
            FrameKind::SessionAccept,
            "after the session TTL a stale pending set must evict and admit the trusted peer; \
             got refusal code {}",
            if reply.kind() == FrameKind::Error {
                error_code(&reply)
            } else {
                "none".to_owned()
            },
        );
    }

    #[test]
    fn an_out_of_plan_confirmed_log_entry_is_dropped_at_open() {
        // Reconciliation: a forged confirmed coordinate the plan never reserved must not
        // survive `LanJournal::open`, even when it is well-formed in the append log.
        let root = tempfile::tempdir().expect("app-private root exists");
        let paths = LanJournalPaths::new(root.path()).expect("journal paths");
        let session = LanSessionId::parse(&"a".repeat(32)).expect("session id parses");
        let batch_id = LanBatchId::parse("batch-forged").expect("batch id parses");
        let body = b"durable body bytes";
        let item = LanItemPlan::new(
            &batch_id,
            0,
            1_700_000_000_000,
            &format!("{:x}", Sha256::digest(body)),
            body.len() as u64,
            "Title",
            Vec::new(),
        )
        .expect("item plan is valid");
        let plan = LanBatchPlan::new(batch_id.clone(), vec![item]).expect("plan is valid");
        let sender = DeviceId::parse(&"b".repeat(64)).expect("device id parses");

        {
            let mut journal = LanJournal::open(paths.clone()).expect("journal opens");
            journal
                .store_batch(LanDurableBatch::pending(
                    plan,
                    session.clone(),
                    sender,
                    DisplayName::parse("Sender").expect("name parses"),
                ))
                .expect("pending batch stores");
            let real = ChunkBinding::new(&session, "batch-forged", 0, ATTACHMENT_SLOT_BODY, 0)
                .expect("binding is valid");
            journal.stage_chunk(&real, body).expect("chunk stages");
            journal.confirm_chunk(&real).expect("chunk confirms");

            // Forge a well-formed confirmed-log tail entry for a coordinate the plan never
            // declared (chunk index 7 does not exist in a one-chunk payload).
            let mut forged = Vec::new();
            push_record_field(&mut forged, "batch-forged".as_bytes());
            forged.extend_from_slice(&0_u16.to_be_bytes());
            forged.extend_from_slice(&0_u16.to_be_bytes());
            forged.extend_from_slice(&7_u32.to_be_bytes());
            use std::io::Write as _;
            let mut log = std::fs::OpenOptions::new()
                .append(true)
                .open(paths.confirmed_log())
                .expect("confirmed log exists after confirm_chunk");
            log.write_all(&forged).expect("forged entry appends");
            log.sync_all().expect("forged entry is durable");
        }

        let journal = LanJournal::open(paths).expect("journal reopens");
        let forged_binding =
            ChunkBinding::new(&session, "batch-forged", 0, ATTACHMENT_SLOT_BODY, 7)
                .expect("binding is valid");
        assert!(
            !journal.is_chunk_confirmed(&forged_binding),
            "a confirmed coordinate outside the plan must be dropped at open"
        );
        let real = ChunkBinding::new(&session, "batch-forged", 0, ATTACHMENT_SLOT_BODY, 0)
            .expect("binding is valid");
        assert!(
            journal.is_chunk_confirmed(&real),
            "the legitimately confirmed coordinate survives reconciliation"
        );
    }
}
