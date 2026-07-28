# Stage-6 behavior contract (LAN multi-memo device transfer core)

> Version: v1 (2026-07-28)
>
> Owner crate: `lomo-lan`. This document is the versioned behavior lock; RED/GREEN evidence lives in
> `fixtures/baseline/STAGE6-EVIDENCE.md`. `ROADMAP.MD` §14 and `ARCHITECTURE.md` remain the
> architecture sources of truth.
>
> Entry prerequisites inherited from earlier stages: Stage-3 P3-10 store cutover, Stage-4 P4-10A/B
> media/archive cutover, and the Stage-5 sync cutover are production owners. Stage-6 production
> wiring must not land before those hold. Residual env gates (six real provider smokes, formal signed
> shipping APK) stay `pending_env` and are **not** Stage-6 blockers.

## Capability

`lomo-lan` is the sole owner of LAN device trust and transfer: device-level peer identity, pairing
transcript and short authentication code, revocation, the versioned TCP control/chunk wire, session
key derivation, bounded approval previews, resumable chunked transfer, and per-item idempotent
commit of received memos through the `lomo-core` single writer.

Kotlin keeps exactly: NSD registration/discovery, Android `NetworkCallback` / address candidates /
local-network permission / multicast lock, Keystore device-key creation + signing + public-key
export, the foreground service lifecycle, and Compose UI.

## Fundamental invariant

Every byte that reaches the workspace is bound to one authenticated session transcript, one
user-approved batch, one active `WorkspaceGenerationId`, and one idempotent `LanItemId`. Peer trust
belongs to the device installation, never to the Markdown workspace, and therefore never enters
sync or a workspace archive.

## Axiom violation (pre-Stage-6 Kotlin)

- A user-typed **pairing code** is a shared symmetric secret, not a device identity: any peer that
  learns the code is trusted, and no transcript binds the code to the two endpoints, so an
  in-path attacker can complete a "successful" pairing on both sides.
- Kotlin owns HTTP wire, nonce/session validation, chunk assembly and `ShareIncomingMemoSaver`
  writes the workspace directly, so protocol truth and workspace truth are two authorities.
- Transfer identity is per-request, not per-item: a retried transfer can duplicate memos, and a
  partially failed batch has no per-item resumable state.
- No workspace generation fence exists between approval and commit.

## Rebuild from truth

- Device signing identity is a non-exportable Android Keystore P-256 key. Rust receives only the
  public key (SPKI DER) and requests signatures over transcripts it constructs.
- Pairing derives an ephemeral X25519 shared secret, builds a canonical transcript over both
  device public keys, both display names and the protocol version, and shows both ends a short
  authentication code derived from that transcript. An in-path attacker produces two different
  transcripts, therefore two different codes.
- Each connection performs mutual device-signature authentication over a fresh session transcript
  and derives a session key with HKDF-SHA256. AEAD is ChaCha20-Poly1305 with nonce and AAD bound to
  session, batch, item, attachment and chunk index.
- A durable app-private journal owns peers, sessions, batches, items and confirmed chunk ranges.
- Every received item commits through `lomo-store`'s expected-revision `LocalSyncMutationBatch`
  port; `lomo-lan` never writes user files itself.

## Edge enforcement

- Frame magic, schema version, type and length ceiling are validated before any buffer allocation.
- Unknown frame type or protocol version is rejected without allocating the declared length.
- Replayed session ids, batch ids, nonces and chunk indices are rejected against a durable ledger.
- Revoking a peer cancels its uncommitted sessions; later connections cannot reach approval UI.
- Workspace generation is re-checked at approval and again at commit; a mismatch fails closed.
- Batch limits (100 items, 100 MB total, 100 MB per attachment) are rejected before send.

## Tail deletion (single cutover)

`LomoShareServer`, `LomoShareServerHandlers`, `LomoShareClient`, `LomoShareTransferHandler`,
`SharePrepareRequestExecutor/Processor`, `ShareTransferRequestExecutor/Processor`,
`ShareTransferOrchestrator`, `ShareTransferPayloadBuilder`, `ShareRequestValidator`,
`ShareIncomingMemoSaver`, `ShareAttachmentResolver/Storage/ReferenceRemapper`, `ShareCryptoUtils`,
`ShareAuthUtils`, `ShareAuthValidation`, `ShareAuthenticationValidator`, `SharePairingConfig`,
`LanSharePingProtocol`, `LanShareCredentialStore`, `LanSharePairingCodePolicy`, the pairing-code UI
and storage, `lanShareE2eEnabled`, OPEN mode, primary+legacy key material, peer-UUID trust, and the
Ktor server/content-negotiation dependencies that exist only for the LAN wire. The Ktor **client**
used by app update stays.

No v1 decoder, compatibility flag, dual listener or Kotlin fallback survives the cutover. Old peers
and keys are not migrated: users re-pair with device keys.

## Given/When/Then scenarios

### Pairing and device identity

- Given an in-path attacker substitutes the pairing transcript, when both ends display the short
  authentication code, Then the two codes differ and neither side stores the peer.
- Given a completed pairing, when either side declines the code or the session deadline expires,
  Then the session is discarded and no peer record is written.
- Given a stored peer, when it is revoked, Then its uncommitted sessions are cancelled and a later
  connection is rejected before any approval preview is produced.
- Given a peer public key that is not a valid P-256 SPKI key, when pairing begins, Then the boundary
  rejects it before transcript construction.

### Wire and session

- Given a frame whose declared length exceeds the type ceiling, when decoded, Then decoding fails
  before allocating the declared length.
- Given an unknown frame type or protocol version, when decoded, Then decoding fails closed.
- Given a replayed session id or a reused nonce, when the frame is processed, Then it is rejected.
- Given a tampered AEAD tag, digest, frame length or frame order, when decoded, Then the item fails
  and no workspace side effect occurs.

### Approval and batch

- Given an authenticated 100-item batch, when the user has not yet approved, Then the receiver holds
  only a bounded preview (sender identity, counts, total bytes, truncated per-item title) and no
  full body or attachment bytes.
- Given a batch that exceeds 100 items, 100 MB total or 100 MB for one attachment, when the sender
  prepares, Then it is rejected before transfer with guidance to use a workspace archive.
- Given an approval recorded with a TTL, when the process dies and recovers inside the TTL, Then
  transfer resumes without re-approval; after the TTL it must be re-approved.

### Transfer, resume and commit

- Given a transfer interrupted after any chunk, when the same peer/session resumes, Then only
  unconfirmed chunks are retransmitted.
- Given several items referencing one attachment with the same digest, when the batch transfers,
  Then the attachment travels once and each item commits only after its own full verification.
- Given one item fails while others complete, when the batch is queried, Then it reports explicit
  partial completion with per-item retry state, and completed items are not rolled back.
- Given the same `LanItemId` is submitted twice, when the engine already committed it, Then no
  second memo is created and the existing result is returned.
- Given two different transfers carry the same timestamp and content, when both commit, Then two
  memos exist and identity collision is resolved by ordinal — never by content de-duplication and
  never through a sync conflict.
- Given the workspace switches or enters write freeze after approval, when apply runs, Then the
  generation check fails and the new workspace is not written.

### Content policy

- Given a shared memo, Then only the current body, its original timestamp and referenced
  attachments transfer. Pin, trash, history and snooze never transfer. Reminder tokens in the body
  are preserved verbatim and re-planned by local reminder policy after commit.

## Observable outcomes

Short authentication code bytes; peer records and revocation state; frame encode/decode results and
rejection codes; AEAD nonce/AAD binding; durable journal contents; confirmed chunk ranges; bounded
preview fields; per-item commit results and `LanBatchSnapshot` partial-completion state; workspace
mutations observed through `lomo-store`.

## Excludes

NSD, Android network selection, local-network permission, multicast lock, Keystore private-key
operations, Compose layout, notification presentation. Whole-workspace transfer, pin/trash/history
transfer, and batches above 100 items / 100 MB are out of scope by product decision — those use the
workspace archive.

## Approved divergences from `ROADMAP.MD` §14

1. **Transport runtime.** The roadmap named Tokio TCP. `lomo-lan` instead uses blocking
   `std::net` sockets driven by `lomo-core`'s existing bounded `NativeTaskWorkerPool`, with explicit
   read/write deadlines. Rationale: Stage 5 shipped its network stack on blocking I/O plus that same
   worker pool, so adding Tokio would introduce a second concurrency model and a fourth-ABI size
   cost for one crate. The locked properties the roadmap actually requires — versioned
   length-prefixed frames, bounded in-flight chunks, cancellation, resumable journal — are all met.
   Recorded as an Architecture Impact exception, not a silent deviation.
2. **Crypto provider.** The roadmap named the algorithms X25519 + HKDF-SHA256 +
   ChaCha20-Poly1305 + Keystore P-256. Those algorithms are unchanged; they are sourced from
   `aws-lc-rs`, already linked for four ABIs through rustls, instead of adding four RustCrypto
   crates. Rationale: a second RustCrypto generation duplicates `rand_core`/`cpufeatures` (a
   `cargo deny` ban) and grows every ABI for no behavioral gain.

## Hard gates

- Hermetic two-endpoint peer matrix: pairing, MITM code divergence, revocation, frame fuzz corpus,
  resume, per-item commit, partial completion, generation fence.
- `cargo clippy -p lomo-lan --all-targets --locked -- -D warnings` and
  `cargo test -p lomo-lan --locked`.
- Architecture tests: `lomo-lan` is the sole LAN protocol owner; it must not depend on `lomo-sync`;
  production Kotlin must not contain the deleted wire, pairing code, or `lanShareE2eEnabled`.
- Four-ABI build/ELF/API 26 load and the Stage-5 shipping size ceiling still hold.
- `just check`; `just ci`; API ≥ 26 arm64 `just device-smoke`.
- i18n strings updated in both `values` and `values-zh-rCN`.

## Non-claims

Nothing in this document is evidence. No pairing, transfer, resume, device or size result may be
described as GREEN unless `fixtures/baseline/STAGE6-EVIDENCE.md` records the real command, exit code
and observed output. Absent a device, arm64 LAN evidence stays `OPEN / pending_env`.
