//! LAN device-to-device transfer core (`lomo-lan`) — stage 6 owner.
//!
//! Sole owner of LAN device trust and transfer: peer identity, pairing transcript and short
//! authentication code, revocation, the versioned TCP control/chunk wire, session key derivation,
//! bounded approval previews, resumable chunked transfer, and per-item idempotent commit of
//! received memos through the `lomo-core` single writer.
//!
//! Deliberately does **not** depend on `lomo-sync`: LAN peer trust belongs to the device
//! installation, never to a workspace baseline, tombstone or remote conflict session.
//!
//! Kotlin keeps NSD, Android network topology/permission, multicast lock, Keystore private-key
//! operations and Compose. This crate never opens a `content://` URI, decodes media, or writes user
//! files itself — received items commit through `lomo-store` expected-revision ports.

#![deny(unsafe_code)]

mod error;
mod frame;
mod identity;
mod limits;
mod pairing;
mod session;

pub use error::{
    authentication as lan_authentication, cancelled as lan_cancelled, conflict as lan_conflict,
    corrupt_state as lan_corrupt_state, network as lan_network, permission as lan_permission,
    resource_limit as lan_resource_limit, storage as lan_storage, validation as lan_validation,
};
pub use frame::{
    FrameKind, LAN_FRAME_HEADER_BYTES, LAN_FRAME_MAGIC, LAN_PROTOCOL_VERSION, LanFrame,
    decode_frame, encode_frame, peek_declared_payload_len,
};
pub use identity::{
    DEVICE_PUBLIC_KEY_BYTES, DeviceId, DevicePublicKey, DeviceSigner, DisplayName, PeerRecord,
};
pub use limits::{
    AEAD_TAG_BYTES, CHUNK_PLAINTEXT_BYTES, LAN_DURABLE_SCHEMA, MAX_ATTACHMENT_BYTES,
    MAX_BATCH_ITEMS, MAX_BATCH_TOTAL_BYTES, MAX_CONTROL_PAYLOAD_BYTES, MAX_DISPLAY_NAME_BYTES,
    MAX_INFLIGHT_CHUNKS, MAX_LAN_RECORD_BYTES, MAX_PREVIEW_TITLE_CHARS,
    MAX_SEALED_CHUNK_PAYLOAD_BYTES, MAX_SNAPSHOT_ENTRIES, MAX_TRUSTED_PEERS, PAIRING_CODE_DIGITS,
};

pub use pairing::{
    PairingRole, PairingTranscript, derive_pairing_code, verify_pairing_confirmation,
};
pub use session::{
    ATTACHMENT_SLOT_BODY, ChunkBinding, LanSessionId, ReplayLedger, SessionKey, SessionTranscript,
};

/// Crate package identity for architecture ownership locks.
pub const LAN_CRATE_NAME: &str = "lomo-lan";
