//! Resource limits and product ceilings for LAN v2 (fail closed, never clamp).

/// Maximum items in one LAN v2 batch (product decision; larger sets use a workspace archive).
pub const MAX_BATCH_ITEMS: usize = 100;

/// Maximum total attachment bytes in one batch (100 MiB).
pub const MAX_BATCH_TOTAL_BYTES: u64 = 100 * 1_048_576;

/// Maximum bytes for a single attachment (100 MiB).
pub const MAX_ATTACHMENT_BYTES: u64 = 100 * 1_048_576;

/// Plaintext bytes carried by one chunk before AEAD sealing (256 KiB).
pub const CHUNK_PLAINTEXT_BYTES: usize = 256 * 1_024;

/// Runtime payload reserves 128 bytes of the sealed-chunk ceiling for transport metadata.
pub const RUNTIME_CHUNK_PLAINTEXT_BYTES: usize = CHUNK_PLAINTEXT_BYTES - 128;

/// Exact `BoltFFI` representation of [`RUNTIME_CHUNK_PLAINTEXT_BYTES`].
pub const RUNTIME_CHUNK_PLAINTEXT_BYTES_U32: u32 = 262_016;

const _: () = assert!(RUNTIME_CHUNK_PLAINTEXT_BYTES == 262_016);

/// ChaCha20-Poly1305 authentication tag length.
pub const AEAD_TAG_BYTES: usize = 16;

/// Maximum sealed chunk payload accepted on the wire (plaintext + AEAD tag).
pub const MAX_SEALED_CHUNK_PAYLOAD_BYTES: usize = CHUNK_PLAINTEXT_BYTES + AEAD_TAG_BYTES;

/// Maximum payload accepted for any control frame (64 KiB).
///
/// Control frames carry identity, transcripts, previews and acknowledgements only. A control kind
/// may never borrow the chunk ceiling.
pub const MAX_CONTROL_PAYLOAD_BYTES: usize = 64 * 1_024;

/// Maximum concurrent in-flight chunks per session (bounded memory, independent of batch size).
pub const MAX_INFLIGHT_CHUNKS: usize = 4;

/// Maximum simultaneous outbound channels in the pool (one reusable connection per session).
pub const MAX_OUTBOUND_CHANNELS: usize = 8;

/// Maximum simultaneous inbound connection workers in the accept pump.
///
/// Covers a full outbound channel pool per peer plus short-lived control connections; a stalled
/// worker is also bounded by the socket deadline, so a hostile peer cannot pin a slot forever.
pub const MAX_INBOUND_CONNECTIONS: usize = 16;

/// Maximum characters retained for one preview title/first line before approval.
pub const MAX_PREVIEW_TITLE_CHARS: usize = 80;

/// Maximum peers a device may trust (bounded durable registry).
pub const MAX_TRUSTED_PEERS: usize = 64;

/// Short authentication code digit count shown on both ends during pairing.
pub const PAIRING_CODE_DIGITS: usize = 6;

/// Durable LAN journal schema version. Schema 4 timestamps session witnesses, carries outgoing
/// failure/terminal facts and adds the retired-batch witness record.
pub const LAN_DURABLE_SCHEMA: u32 = 4;

/// Oldest durable schema this build can still read. Schema 3 session records carry no timestamp;
/// their witnesses load as accepted at epoch 0 and retire at the first maintenance pass because
/// they are older than any live session time-to-live.
pub const LAN_DURABLE_SCHEMA_MIN_READ: u32 = 3;

/// Maximum bytes for one durable LAN journal record body (256 KiB).
pub const MAX_LAN_RECORD_BYTES: usize = 256 * 1_024;

/// Append tail budget for the confirmed-chunk journal (64 KiB).
///
/// Each `confirm_chunk` appends one entry instead of rewriting the whole coordinate set; once the
/// append tail exceeds this bound the set folds back into the compacted `chunks.rec` snapshot and
/// the tail restarts empty, keeping both the per-chunk write and the snapshot bounded.
pub const LAN_CONFIRMED_LOG_COMPACT_BYTES: u64 = 64 * 1_024;

/// Maximum discovery / network snapshot entries accepted from the Kotlin platform adapter.
pub const MAX_SNAPSHOT_ENTRIES: usize = 64;

/// Maximum UTF-8 bytes for a peer display name.
pub const MAX_DISPLAY_NAME_BYTES: usize = 128;

/// Pairing challenge lifetime. Kotlin displays remaining time from the deadline; it does not choose
/// this value.
pub const PAIRING_TTL_MS: i64 = 2 * 60 * 1_000;

/// Mutually authenticated session challenge lifetime.
pub const SESSION_TTL_MS: i64 = 60 * 1_000;

/// Batch approval lifetime used for recovery without re-prompting.
pub const APPROVAL_TTL_MS: i64 = 15 * 60 * 1_000;

/// Delay between a batch reaching its terminal anchor (rejection instant or approval expiry) and
/// reclamation.
///
/// Anchored to the approval window: within it a peer may still replay the batch id, so the durable
/// record must survive to answer or refuse that replay; after it the payload bytes and batch record
/// retire while the anti-replay witness stays.
pub const LAN_BATCH_RETIRE_DELAY_MS: i64 = APPROVAL_TTL_MS;

/// How long an accepted session id remains a replay witness after acceptance. Far longer than the
/// session time-to-live, so a replayed id is still refused long after the session itself died.
pub const LAN_SESSION_WITNESS_RETENTION_MS: i64 = 7 * 24 * 60 * 60 * 1_000;

/// How long a retired batch id remains a resurrection witness after reclamation.
pub const LAN_RETIRED_WITNESS_RETENTION_MS: i64 = 7 * 24 * 60 * 60 * 1_000;

/// Maximum accepted-session witnesses retained before the oldest are evicted.
pub const MAX_SESSION_WITNESSES: usize = 256;

/// Maximum retired-batch witnesses retained before the oldest are evicted.
pub const MAX_RETIRED_WITNESSES: usize = 256;

/// Maximum pending pairings awaiting two-sided confirmation.
///
/// Inbound hellos are attacker-controlled input; the pending set is a bounded resource,
/// never an unbounded map.
pub const MAX_PENDING_PAIRINGS: usize = 64;

/// Maximum pending sessions awaiting two-sided confirmation.
pub const MAX_PENDING_SESSIONS: usize = 64;

/// Sliding admission window for inbound pairing hellos from one source address.
pub const PAIR_HELLO_WINDOW_MS: i64 = 1_000;

/// Maximum inbound pairing hellos one source address may open per window. `PairHello` is
/// unauthenticated; admission is charged before any key agreement work runs.
pub const MAX_PAIR_HELLOS_PER_WINDOW: u32 = 8;

/// Maximum distinct source addresses tracked by the pairing-hello admission window.
pub const MAX_PAIR_HELLO_SOURCES: usize = 256;

/// Remaining protocol time-to-live for display. Never negative.
#[must_use]
pub const fn remaining_ttl_ms(now_ms: i64, deadline_ms: i64) -> i64 {
    let remaining = deadline_ms.saturating_sub(now_ms);
    if remaining < 0 { 0 } else { remaining }
}
