# Stage-6 implementation evidence

> Status: **P6-01…P6-10 host/production cutover and formal non-performance gates GREEN
> (2026-08-03)** — `lomo-lan` owns the LAN v2 frame codec, device
> identity, pairing transcript and short authentication code, session authentication and chunk AEAD,
> the batch/approval/outcome model, the durable app-private journal, the per-item commit fences, and
> the blocking transport, and the `BoltFFI` conversion surface. Two endpoints complete pairing,
> session authentication, chunked transfer and resume over **real loopback TCP sockets**.
>
> P6-09 adapters and P6-10 production DI/tail deletion are host-verified. The old Kotlin HTTP wire,
> pairing secret, E2E/OPEN settings, and direct-write path are deleted; `RustLanShareService` is
> the sole production domain adapter over the managed Rust engine. The packaged native engine
> passes `just device-smoke` on API 36 arm64; two-physical-device LAN/Wi-Fi/NSD evidence remains
> **OPEN / `pending_env`** and is not inferred from that single-device smoke.

**Honesty (this file):** physical LAN and runtime-performance claims remain open. Host production
wiring, the release SO ceiling and packaged-engine arm64 smoke below are backed by real commands.
The transport matrix uses **real loopback TCP sockets between two threads in one host process** —
that is a real socket, not two devices. Do not describe the physical LAN matrix as complete, and do
not claim a two-device pairing, a Wi-Fi transfer, NSD discovery, a real Android Keystore signature,
a 100-item / 100 MB transfer or runtime performance from this file.

Behavior contract: `fixtures/baseline/STAGE6-CONTRACT.md`.

## Inherited entry prerequisites

Stage-3 P3-10 store cutover, Stage-4 P4-10A/B media/archive cutover and the Stage-5 sync cutover are
production owners; Stage-5 residual env gates (six real provider smokes, formal signed shipping APK)
remain `OPEN / pending_env` and are **not** Stage-6 blockers. See `STAGE5-EVIDENCE.md`.

## Landed packages

| Package | Surface | Host result |
| --- | --- | --- |
| P6-01 frame codec | `rust/lan/src/frame.rs` | 10 tests |
| P6-02 identity + pairing | `rust/lan/src/identity.rs`, `src/pairing.rs` | 9 tests |
| P6-03 session crypto | `rust/lan/src/session.rs` | 8 tests |
| P6-04 batch model | `rust/lan/src/batch.rs` | 10 tests |
| P6-05 durable journal | `rust/lan/src/journal.rs` | 10 tests |
| P6-06 commit fences | `rust/lan/src/commit.rs` | 8 tests |
| P6-07 transport + two-endpoint matrix | `rust/lan/src/transport.rs` | 7 tests (real sockets) |
| P6-08 `BoltFFI` conversion surface | `rust/native/src/lan_ffi.rs` | 10 tests |

### First principles (P6-01…P6-08)

1. **Invariant:** every byte that reaches the workspace is bound to one authenticated session
   transcript, one user-approved batch, one active `WorkspaceGenerationId`, and one idempotent
   `LanItemId`. Peer trust belongs to the device installation, never to the workspace.
2. **Axiom violation:** the Kotlin wire trusted a user-typed pairing code — a shared symmetric
   secret with no transcript binding, so an in-path attacker completes a "successful" pairing on
   both ends. Transfer identity was per-request, so a retry could duplicate memos, and
   `ShareIncomingMemoSaver` wrote the workspace directly, making protocol and workspace two
   authorities. No generation fence existed between approval and commit.
3. **Rebuild from truth:** device identity derived from a non-exportable Keystore P-256 key; one
   canonical pairing transcript whose short code diverges under an in-path attacker; per-connection
   session key with nonce/AAD bound to session, batch, item, attachment slot and chunk; durable
   app-private journal; per-item commit through the `lomo-store` expected-revision port.
4. **Edge enforcement:** frame header validated before the declared length is reserved; unknown
   kind/version rejected; curve points validated at parse; replay ledger for sessions and chunks;
   batch limits checked before transfer; approval TTL and generation fence re-checked at commit.
5. **Tail deletion:** completed in the P6-10 atomic cutover. Kotlin retains only the platform
   adapter boundary described by `STAGE6-CONTRACT.md`.

### RED/GREEN (real commands, 2026-07-29)

```text
$ cargo test -p lomo-lan --test frame_codec_contract --locked
# RED before src/frame.rs: unresolved imports FrameKind, LanFrame, decode_frame, encode_frame,
#   peek_declared_payload_len, LAN_FRAME_MAGIC, LAN_PROTOCOL_VERSION, MAX_CONTROL_PAYLOAD_BYTES,
#   MAX_SEALED_CHUNK_PAYLOAD_BYTES
# GREEN after: 10 passed

$ cargo test -p lomo-lan --test identity_pairing_contract --locked
# RED before src/identity.rs + src/pairing.rs: unresolved imports DeviceId, DevicePublicKey,
#   DeviceSigner, DisplayName, PairingRole, PeerRecord, PairingTranscript, derive_pairing_code,
#   verify_pairing_confirmation
# GREEN after: 9 passed

$ cargo test -p lomo-lan --test transport_contract --locked
# GREEN: 7 passed, including
#   two_endpoints_pair_authenticate_transfer_and_resume_over_real_sockets

$ cargo test -p lomo-native --test lan_ffi_contract
# GREEN: 10 passed (short code, transcript boundary rejects, confirm/revoke, preview limits,
#   approval TTL, resume indices, .lomo root rejection)

$ cargo test -p lomo-lan --locked
# EXIT:0 — 62 passed
#   batch_contract 10, commit_contract 8, frame_codec_contract 10,
#   identity_pairing_contract 9, journal_contract 10, session_crypto_contract 8,
#   transport_contract 7

$ cargo clippy -p lomo-lan --all-targets --locked -- -D warnings
# EXIT:0

$ cargo test -p lomo-architecture-tests --locked -- stage_six stage_five
# EXIT:0 — 8 passed (incl. stage_six_lan_owner_is_unique_and_independent_of_sync)

$ cargo deny check
# advisories ok, bans ok, licenses ok, sources ok

$ just check
# EXIT:0 (2026-08-03) — strict Rust/Kotlin/Android host graph; app tests 613/613.

$ just ci
# EXIT:0 (2026-08-03) — Rust/Kotlin coverage, cargo deny, four-ABI release pack,
# Android lint/static analysis and packaged APK ABI validation; release SO total 43,654,804 bytes
# <= 46,530,532-byte ceiling. This is the shipping-size gate, not runtime performance evidence.

$ just device-smoke
# EXIT:0 (2026-08-03) — SM_S9110, API 36, arm64-v8a; install, launch and durable-recovery relaunch.
# This loads the packaged engine on one device; it is not two-device LAN evidence.
```

### Security properties actually proven on host

- **In-path attacker detection.** `in_path_attacker_cannot_make_both_ends_display_the_same_code`
  models an attacker running two separate exchanges and asserts the two honest ends derive
  different short codes. This is the property a shared pairing code cannot have.
- **Signature binding.** A confirmation over a different transcript, and a valid signature under a
  substituted key, are both rejected and store no peer.
- **Chunk binding.** A sealed chunk fails to open under any different session, batch, item,
  attachment slot or chunk index, and under any tampered ciphertext or tag byte.
- **Nonce uniqueness.** Distinct chunks within one session key derive distinct nonces.
- **Replay.** Session ids and chunks are single-use; a rejected replay does not grow the confirmed
  set.
- **Preview containment.** The approval preview is derived from plan metadata and bodies never enter
  `LanItemPlan`, so "no body before approval" is structural, not a convention.
- **Fail-closed durability.** A damaged journal record returns `CorruptState`; it is never dropped
  and never replaced with an empty set, so a peer is never silently un-trusted.
- **Generation fence.** A workspace switch between approval and commit fails closed and the new
  workspace is not written.
- **Wire-level allocation safety.** Over a real socket, a header declaring `u32::MAX`, or a control
  kind claiming the chunk ceiling, fails closed before the declared length is reserved.
- **Bounded socket lifetime.** A peer that connects and stays silent trips the read deadline with a
  typed transient network error instead of pinning the worker; a zero deadline is rejected at
  construction.
- **Mid-frame close.** A peer that closes after a partial payload reports `lan_frame_incomplete`
  rather than decoding a truncated frame.
- **End-to-end resume.** Two endpoints pair, authenticate a session, ship two chunks, drop the
  connection, reopen the journal, retransmit exactly the unconfirmed indices, and reassemble a body
  whose digest equals the sender's plan digest — then commit exactly once under replay.
- **Conversion-only FFI.** Every rejection observed across the `BoltFFI` boundary carries the same
  stable `lomo-lan` code (`lan_device_key_invalid`, `lan_pairing_signature_invalid`,
  `lan_batch_too_many_items`, `lan_attachment_too_large`, `lan_journal_root_invalid`,
  `lan_session_id_invalid`, `lan_peer_unknown`), so no decision was re-implemented at the boundary.
  A rejected confirmation stores no peer; an over-limit batch is refused before a socket opens.

### Approved divergences (recorded, not silent)

1. Blocking `std::net` sockets on the existing `lomo-core` bounded worker pool instead of a second
   Tokio runtime.
2. `aws-lc-rs` — already linked for four ABIs through rustls — as the provider for the same locked
   algorithms (X25519, HKDF-SHA256, ChaCha20-Poly1305, P-256), instead of adding four RustCrypto
   crates that would duplicate `rand_core`/`cpufeatures` (a `cargo deny` ban) and grow every ABI.

Rationale and scope are in `STAGE6-CONTRACT.md` §"Approved divergences from `ROADMAP.MD` §14".

## Residual OPEN

| Residual | Status |
| --- | --- |
| P6-09 Kotlin NSD / network / Keystore adapters + Compose | **GREEN (host compile/tests)** |
| LAN FFI registered in production DI | **GREEN (architecture + data compile)** |
| LAN session lifecycle (`start`/`stop`, live batch query) | **GREEN (Rust coordinator adapter host tests)** |
| P6-10 production cutover + Kotlin tail deletion + i18n both locales | **GREEN (host source/compile checks)** |
| API ≥ 26 arm64 packaged-engine device smoke | **GREEN** (SM_S9110, API 36, arm64-v8a) |
| Two physical devices over Wi-Fi/NSD/Keystore LAN path | **OPEN / `pending_env`** |
| Four-ABI release SO shipping-size gate | **GREEN** (43,654,804 <= 46,530,532 bytes) |
| `just check` / `just ci` for the Stage-6 surface | **GREEN** (2026-08-03) |

## Non-claims

- The two-endpoint matrix runs both endpoints in one host process over loopback. No **two physical
  devices** have ever paired, and the MITM property is proven by a host model of the attacker, not
  by a real network path.
- No transfer has run over Wi-Fi, through NSD discovery, or against a real Android Keystore.
- No physical-device LAN code path is claimed here; host evidence verifies the production Rust
  adapter graph and the deleted Kotlin v1 tail, not Android Wi-Fi behavior.
- Four-ABI build/ELF/APK packaging and the release SO ceiling are GREEN through `just ci`; no
  startup/query/parse or LAN throughput result is claimed for Stage 6.
