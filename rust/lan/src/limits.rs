//! Resource limits and product ceilings for LAN v2 (fail closed, never clamp).

/// Maximum items in one LAN v2 batch (product decision; larger sets use a workspace archive).
pub const MAX_BATCH_ITEMS: usize = 100;

/// Maximum total attachment bytes in one batch (100 MiB).
pub const MAX_BATCH_TOTAL_BYTES: u64 = 100 * 1_048_576;

/// Maximum bytes for a single attachment (100 MiB).
pub const MAX_ATTACHMENT_BYTES: u64 = 100 * 1_048_576;

/// Plaintext bytes carried by one chunk before AEAD sealing (256 KiB).
pub const CHUNK_PLAINTEXT_BYTES: usize = 256 * 1_024;

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

/// Maximum characters retained for one preview title/first line before approval.
pub const MAX_PREVIEW_TITLE_CHARS: usize = 80;

/// Maximum peers a device may trust (bounded durable registry).
pub const MAX_TRUSTED_PEERS: usize = 64;

/// Short authentication code digit count shown on both ends during pairing.
pub const PAIRING_CODE_DIGITS: usize = 6;

/// Durable LAN journal schema version.
pub const LAN_DURABLE_SCHEMA: u32 = 1;

/// Maximum bytes for one durable LAN journal record body (256 KiB).
pub const MAX_LAN_RECORD_BYTES: usize = 256 * 1_024;

/// Maximum discovery / network snapshot entries accepted from the Kotlin platform adapter.
pub const MAX_SNAPSHOT_ENTRIES: usize = 64;

/// Maximum UTF-8 bytes for a peer display name.
pub const MAX_DISPLAY_NAME_BYTES: usize = 128;
