//! Session authentication, key derivation and chunk/control AEAD.
//!
//! Each connection derives a fresh session key from an ephemeral X25519 agreement bound to a
//! session transcript, and both endpoints authenticate with their device signing keys over that
//! same transcript.
//!
//! Traffic keys are derived with HKDF-SHA256 from that session key over protocol version, direction
//! and purpose. Chunks add the batch id so two batches never share a data key. Nonces are a bounded
//! coordinate/sequence encoding; exhausting the counter requires a new crypto session. Recovery
//! reuses the original coordinates and therefore the original ciphertext. Control frames are AEAD
//! sealed; frame kind, session, batch, sequence and declared length are additional authenticated
//! data. The session key bytes never leave this module.

use aws_lc_rs::aead::{Aad, CHACHA20_POLY1305, LessSafeKey, Nonce, UnboundKey};
use aws_lc_rs::hkdf::{HKDF_SHA256, KeyType, Salt};

use crate::error::{authentication, resource_limit, validation};
use crate::frame::{FrameKind, LAN_PROTOCOL_VERSION};
use crate::identity::DevicePublicKey;
use crate::limits::{AEAD_TAG_BYTES, MAX_CONTROL_PAYLOAD_BYTES, MAX_SEALED_CHUNK_PAYLOAD_BYTES};
use lomo_core::LomoError;

/// Domain separation label for the session transcript.
const SESSION_TRANSCRIPT_LABEL: &[u8] = b"lomo-lan-session-v3";

/// Domain separation salt for session key derivation.
const SESSION_KEY_SALT: &[u8] = b"lomo-lan-session-key-v3";

/// Domain separation prefix for chunk additional authenticated data.
const CHUNK_AAD_LABEL: &[u8] = b"lomo-lan-chunk-v3";

/// Domain separation prefix for control additional authenticated data.
const CONTROL_AAD_LABEL: &[u8] = b"lomo-lan-control-v3";

/// Domain separation prefix for the per-batch, per-direction chunk data key.
const CHUNK_KEY_LABEL: &[u8] = b"lomo-lan-chunk-key-v3";

/// HKDF salt for per-batch, per-direction chunk key derivation.
const CHUNK_KEY_SALT: &[u8] = b"lomo-lan-chunk-key-salt-v3";

/// Domain separation prefix for the per-direction control key.
const CONTROL_KEY_LABEL: &[u8] = b"lomo-lan-control-key-v3";

/// HKDF salt for per-direction control key derivation.
const CONTROL_KEY_SALT: &[u8] = b"lomo-lan-control-key-salt-v3";

/// Expected X25519 public key length.
const EPHEMERAL_PUBLIC_KEY_BYTES: usize = 32;

/// ChaCha20-Poly1305 key length.
const SESSION_KEY_BYTES: usize = 32;

/// ChaCha20-Poly1305 nonce length.
const NONCE_BYTES: usize = 12;

/// Attachment slot reserved for the memo body itself (attachments use `0..=0xFFFE`).
pub const ATTACHMENT_SLOT_BODY: u16 = 0xFFFF;

#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
pub enum SessionControlKind {
    Prepare,
    Approve,
    Reject,
    Complete,
}

impl SessionControlKind {
    /// Stable control-purpose code mixed into control AAD and nonces.
    #[must_use]
    pub const fn code(self) -> u8 {
        match self {
            Self::Prepare => 1,
            Self::Approve => 2,
            Self::Reject => 3,
            Self::Complete => 4,
        }
    }
}

/// Direction of sealed traffic relative to the session opener.
///
/// Opposite directions derive independent AEAD keys, so the same chunk coordinates can be used
/// both ways without repeating a nonce under one key.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
pub enum LanDirection {
    /// Opener → responder.
    Forward,
    /// Responder → opener.
    Reverse,
}

impl LanDirection {
    const fn wire_code(self) -> u8 {
        match self {
            Self::Forward => 1,
            Self::Reverse => 2,
        }
    }
}

/// A per-connection session identifier (32 lowercase hex characters).
#[derive(Clone, Debug, Eq, PartialEq, Hash, Ord, PartialOrd)]
pub struct LanSessionId(String);

impl LanSessionId {
    /// Parses a session id.
    ///
    /// # Errors
    ///
    /// Validation when the value is not 32 lowercase hex characters.
    pub fn parse(raw: &str) -> Result<Self, LomoError> {
        if raw.len() != 32 || !raw.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            return Err(validation(
                "lan_session_id_invalid",
                "session id must be 32 lowercase hex characters",
            ));
        }
        Ok(Self(raw.to_ascii_lowercase()))
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// The canonical per-connection session transcript both endpoints sign.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SessionTranscript {
    bytes: Vec<u8>,
}

impl SessionTranscript {
    /// Builds the canonical session transcript.
    ///
    /// # Errors
    ///
    /// Validation when an ephemeral public key has the wrong length.
    pub fn build(
        session_id: &LanSessionId,
        opener_key: &DevicePublicKey,
        opener_ephemeral: &[u8],
        responder_key: &DevicePublicKey,
        responder_ephemeral: &[u8],
    ) -> Result<Self, LomoError> {
        for ephemeral in [opener_ephemeral, responder_ephemeral] {
            if ephemeral.len() != EPHEMERAL_PUBLIC_KEY_BYTES {
                return Err(validation(
                    "lan_session_ephemeral_invalid",
                    "session ephemeral public key must be a 32-byte X25519 point",
                ));
            }
        }
        let mut bytes = Vec::new();
        push_field(&mut bytes, SESSION_TRANSCRIPT_LABEL);
        bytes.extend_from_slice(&LAN_PROTOCOL_VERSION.to_be_bytes());
        push_field(&mut bytes, session_id.as_str().as_bytes());
        push_field(&mut bytes, opener_key.as_bytes());
        push_field(&mut bytes, opener_ephemeral);
        push_field(&mut bytes, responder_key.as_bytes());
        push_field(&mut bytes, responder_ephemeral);
        Ok(Self { bytes })
    }

    /// The transcript bytes both endpoints sign and derive from.
    #[must_use]
    pub fn bytes(&self) -> &[u8] {
        &self.bytes
    }

    /// Authenticates a peer's session signature against this transcript.
    ///
    /// # Errors
    ///
    /// Authentication when the signature does not verify under `peer_key`.
    pub fn verify_peer(
        &self,
        peer_key: &DevicePublicKey,
        signature: &[u8],
    ) -> Result<(), LomoError> {
        peer_key.verify(&self.bytes, signature, "lan_session_signature_invalid")
    }
}

/// A derived per-session ChaCha20-Poly1305 key.
///
/// The key material is never logged, serialized or exposed as bytes outside this module.
pub struct SessionKey {
    bytes: [u8; SESSION_KEY_BYTES],
}

impl std::fmt::Debug for SessionKey {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Never render key material, even in diagnostics.
        formatter.write_str("SessionKey(redacted)")
    }
}

impl SessionKey {
    /// Derives the session key from the agreed secret bound to the session transcript.
    ///
    /// # Errors
    ///
    /// Validation when the shared secret is empty; authentication when HKDF expansion fails.
    pub fn derive(transcript: &SessionTranscript, shared_secret: &[u8]) -> Result<Self, LomoError> {
        if shared_secret.is_empty() {
            return Err(validation(
                "lan_session_secret_invalid",
                "session shared secret must not be empty",
            ));
        }
        let prk = Salt::new(HKDF_SHA256, SESSION_KEY_SALT).extract(shared_secret);
        let mut bytes = [0_u8; SESSION_KEY_BYTES];
        prk.expand(&[transcript.bytes()], SessionKeyLen)
            .and_then(|okm| okm.fill(&mut bytes))
            .map_err(|_expansion_error| {
                authentication(
                    "lan_session_key_derivation_failed",
                    "session key derivation failed",
                )
            })?;
        Ok(Self { bytes })
    }

    /// True when both endpoints derived the same session key. Never exports key bytes.
    #[must_use]
    pub fn derived_material_matches(&self, other: &Self) -> bool {
        let mut diff = 0_u8;
        for (left, right) in self.bytes.iter().zip(other.bytes.iter()) {
            diff |= left ^ right;
        }
        diff == 0
    }

    /// Seals one control payload. Ciphertext includes the AEAD tag; plaintext never rides the wire.
    ///
    /// # Errors
    ///
    /// Resource-limit when the sealed control would exceed the control ceiling; authentication
    /// when the AEAD operation fails.
    pub fn seal_control(
        &self,
        direction: LanDirection,
        binding: &ControlBinding,
        mut plaintext: Vec<u8>,
    ) -> Result<Vec<u8>, LomoError> {
        if plaintext.len().saturating_add(AEAD_TAG_BYTES) > MAX_CONTROL_PAYLOAD_BYTES {
            return Err(resource_limit(
                "lan_control_too_large",
                "sealed control would exceed the wire control ceiling",
            ));
        }
        let declared_len = u32::try_from(plaintext.len()).map_err(|_error| {
            resource_limit(
                "lan_control_too_large",
                "sealed control would exceed the wire control ceiling",
            )
        })?;
        self.control_key(direction, binding.session_id())?
            .seal_in_place_append_tag(
                Nonce::assume_unique_for_key(binding.nonce()),
                Aad::from(binding.aad(direction, declared_len)),
                &mut plaintext,
            )
            .map_err(|_seal_error| {
                authentication(
                    "lan_control_seal_failed",
                    "control frame could not be sealed",
                )
            })?;
        Ok(plaintext)
    }

    /// Opens one sealed control payload under the same binding and direction.
    ///
    /// # Errors
    ///
    /// Authentication when the binding differs, the tag fails, or the payload was tampered with.
    pub fn open_control(
        &self,
        direction: LanDirection,
        binding: &ControlBinding,
        mut sealed: Vec<u8>,
    ) -> Result<Vec<u8>, LomoError> {
        if sealed.len() < AEAD_TAG_BYTES {
            return Err(authentication(
                "lan_control_open_failed",
                "control frame is shorter than the AEAD tag",
            ));
        }
        let declared_len = u32::try_from(sealed.len() - AEAD_TAG_BYTES).map_err(|_error| {
            authentication(
                "lan_control_open_failed",
                "control frame declared length is not representable",
            )
        })?;
        let opened_len = self
            .control_key(direction, binding.session_id())?
            .open_in_place(
                Nonce::assume_unique_for_key(binding.nonce()),
                Aad::from(binding.aad(direction, declared_len)),
                &mut sealed,
            )
            .map_err(|_open_error| {
                authentication(
                    "lan_control_open_failed",
                    "control failed authenticated decryption under its declared binding",
                )
            })?
            .len();
        sealed.truncate(opened_len);
        Ok(sealed)
    }

    /// Seals one chunk, returning ciphertext with the appended authentication tag.
    ///
    /// # Errors
    ///
    /// Resource-limit when the sealed payload would exceed the wire ceiling; authentication when
    /// the AEAD operation fails.
    pub fn seal_chunk(
        &self,
        direction: LanDirection,
        binding: &ChunkBinding,
        mut plaintext: Vec<u8>,
    ) -> Result<Vec<u8>, LomoError> {
        if plaintext.len().saturating_add(AEAD_TAG_BYTES) > MAX_SEALED_CHUNK_PAYLOAD_BYTES {
            return Err(resource_limit(
                "lan_chunk_too_large",
                "sealed chunk would exceed the wire chunk ceiling",
            ));
        }
        self.chunk_key(direction, binding)?
            .seal_in_place_append_tag(
                Nonce::assume_unique_for_key(binding.nonce()?),
                Aad::from(binding.aad(direction)),
                &mut plaintext,
            )
            .map_err(|_seal_error| {
                authentication("lan_chunk_seal_failed", "chunk could not be sealed")
            })?;
        Ok(plaintext)
    }

    /// Opens one sealed chunk under the same binding and direction.
    ///
    /// # Errors
    ///
    /// Authentication when the binding differs, the tag fails, or the payload was tampered with.
    pub fn open_chunk(
        &self,
        direction: LanDirection,
        binding: &ChunkBinding,
        mut sealed: Vec<u8>,
    ) -> Result<Vec<u8>, LomoError> {
        let opened_len = self
            .chunk_key(direction, binding)?
            .open_in_place(
                Nonce::assume_unique_for_key(binding.nonce()?),
                Aad::from(binding.aad(direction)),
                &mut sealed,
            )
            .map_err(|_open_error| {
                authentication(
                    "lan_chunk_open_failed",
                    "chunk failed authenticated decryption under its declared binding",
                )
            })?
            .len();
        sealed.truncate(opened_len);
        Ok(sealed)
    }

    /// Derives the per-batch, per-direction data key so a nonce never repeats under one key.
    fn chunk_key(
        &self,
        direction: LanDirection,
        binding: &ChunkBinding,
    ) -> Result<LessSafeKey, LomoError> {
        self.derive_traffic_key(
            CHUNK_KEY_LABEL,
            CHUNK_KEY_SALT,
            direction,
            binding.session_id(),
            Some(binding.batch_id()),
        )
    }

    fn control_key(
        &self,
        direction: LanDirection,
        session_id: &LanSessionId,
    ) -> Result<LessSafeKey, LomoError> {
        self.derive_traffic_key(
            CONTROL_KEY_LABEL,
            CONTROL_KEY_SALT,
            direction,
            session_id,
            None,
        )
    }

    fn derive_traffic_key(
        &self,
        label: &[u8],
        salt: &[u8],
        direction: LanDirection,
        session_id: &LanSessionId,
        batch_id: Option<&str>,
    ) -> Result<LessSafeKey, LomoError> {
        let mut info = Vec::new();
        push_field(&mut info, label);
        info.extend_from_slice(&LAN_PROTOCOL_VERSION.to_be_bytes());
        info.push(direction.wire_code());
        push_field(&mut info, session_id.as_str().as_bytes());
        if let Some(batch_id) = batch_id {
            push_field(&mut info, batch_id.as_bytes());
        }
        let prk = Salt::new(HKDF_SHA256, salt).extract(&self.bytes);
        let mut bytes = [0_u8; SESSION_KEY_BYTES];
        prk.expand(&[info.as_slice()], SessionKeyLen)
            .and_then(|okm| okm.fill(&mut bytes))
            .map_err(|_expansion_error| {
                authentication(
                    "lan_traffic_key_derivation_failed",
                    "directional traffic key derivation failed",
                )
            })?;
        UnboundKey::new(&CHACHA20_POLY1305, &bytes)
            .map(LessSafeKey::new)
            .map_err(|_key_error| {
                authentication(
                    "lan_traffic_key_invalid",
                    "derived traffic key is not a valid ChaCha20-Poly1305 key",
                )
            })
    }
}

/// The tuple every chunk is cryptographically bound to.
#[derive(Clone, Debug, Eq, PartialEq, Hash, Ord, PartialOrd)]
pub struct ChunkBinding {
    session_id: LanSessionId,
    batch_id: String,
    item_index: u16,
    attachment_slot: u16,
    chunk_index: u32,
}

impl ChunkBinding {
    /// Builds a chunk binding.
    ///
    /// # Errors
    ///
    /// Validation when the batch id is empty or above the identifier ceiling.
    pub fn new(
        session_id: &LanSessionId,
        batch_id: &str,
        item_index: u16,
        attachment_slot: u16,
        chunk_index: u32,
    ) -> Result<Self, LomoError> {
        if batch_id.is_empty() || batch_id.len() > 64 {
            return Err(validation(
                "lan_batch_id_invalid",
                "batch id must be 1..=64 bytes",
            ));
        }
        Ok(Self {
            session_id: session_id.clone(),
            batch_id: batch_id.to_owned(),
            item_index,
            attachment_slot,
            chunk_index,
        })
    }

    /// Next chunk index under the same item/slot, or a new crypto session if the counter is full.
    ///
    /// # Errors
    ///
    /// Resource-limit when `chunk_index` cannot advance without wrapping the nonce.
    pub fn successor(&self) -> Result<Self, LomoError> {
        let chunk_index = self.chunk_index.checked_add(1).ok_or_else(|| {
            resource_limit(
                "lan_chunk_nonce_exhausted",
                "chunk nonce space is exhausted; a new crypto session is required",
            )
        })?;
        Self::new(
            &self.session_id,
            &self.batch_id,
            self.item_index,
            self.attachment_slot,
            chunk_index,
        )
    }

    /// Deterministic per-chunk nonce, unique within one directional batch key.
    ///
    /// # Errors
    ///
    /// Resource-limit when the coordinate cannot be encoded without wrapping.
    pub const fn nonce(&self) -> Result<[u8; NONCE_BYTES], LomoError> {
        let item = self.item_index.to_be_bytes();
        let slot = self.attachment_slot.to_be_bytes();
        let chunk = self.chunk_index.to_be_bytes();
        let version = LAN_PROTOCOL_VERSION.to_be_bytes();
        Ok([
            item[0], item[1], slot[0], slot[1], chunk[0], chunk[1], chunk[2], chunk[3], version[0],
            version[1], 0, 0,
        ])
    }

    /// Additional authenticated data binding the chunk to its exact position and direction.
    #[must_use]
    pub fn aad(&self, direction: LanDirection) -> Vec<u8> {
        let mut aad = Vec::new();
        push_field(&mut aad, CHUNK_AAD_LABEL);
        aad.extend_from_slice(&LAN_PROTOCOL_VERSION.to_be_bytes());
        aad.push(direction.wire_code());
        push_field(&mut aad, self.session_id.as_str().as_bytes());
        push_field(&mut aad, self.batch_id.as_bytes());
        aad.extend_from_slice(&self.item_index.to_be_bytes());
        aad.extend_from_slice(&self.attachment_slot.to_be_bytes());
        aad.extend_from_slice(&self.chunk_index.to_be_bytes());
        aad
    }

    #[must_use]
    pub const fn session_id(&self) -> &LanSessionId {
        &self.session_id
    }

    #[must_use]
    pub fn batch_id(&self) -> &str {
        &self.batch_id
    }

    #[must_use]
    pub const fn item_index(&self) -> u16 {
        self.item_index
    }

    #[must_use]
    pub const fn attachment_slot(&self) -> u16 {
        self.attachment_slot
    }

    #[must_use]
    pub const fn chunk_index(&self) -> u32 {
        self.chunk_index
    }
}

/// The tuple every control frame is cryptographically bound to.
#[derive(Clone, Debug, Eq, PartialEq, Hash)]
pub struct ControlBinding {
    session_id: LanSessionId,
    batch_id: String,
    frame_kind: FrameKind,
    control_kind: SessionControlKind,
    sequence: u32,
}

impl ControlBinding {
    /// Builds a control binding.
    ///
    /// # Errors
    ///
    /// Validation when the batch id is empty or above the identifier ceiling.
    pub fn new(
        session_id: &LanSessionId,
        batch_id: &str,
        frame_kind: FrameKind,
        control_kind: SessionControlKind,
        sequence: u32,
    ) -> Result<Self, LomoError> {
        if batch_id.is_empty() || batch_id.len() > 64 {
            return Err(validation(
                "lan_batch_id_invalid",
                "batch id must be 1..=64 bytes",
            ));
        }
        Ok(Self {
            session_id: session_id.clone(),
            batch_id: batch_id.to_owned(),
            frame_kind,
            control_kind,
            sequence,
        })
    }

    /// Next control sequence, or a new crypto session if the counter is full.
    ///
    /// # Errors
    ///
    /// Resource-limit when `sequence` cannot advance without wrapping the nonce.
    pub fn successor(&self) -> Result<Self, LomoError> {
        let sequence = self.sequence.checked_add(1).ok_or_else(|| {
            resource_limit(
                "lan_control_nonce_exhausted",
                "control nonce space is exhausted; a new crypto session is required",
            )
        })?;
        Self::new(
            &self.session_id,
            &self.batch_id,
            self.frame_kind,
            self.control_kind,
            sequence,
        )
    }

    /// Deterministic per-control nonce, unique within one directional control key.
    #[must_use]
    pub const fn nonce(&self) -> [u8; NONCE_BYTES] {
        let sequence = self.sequence.to_be_bytes();
        let version = LAN_PROTOCOL_VERSION.to_be_bytes();
        let frame = self.frame_kind.code().to_be_bytes();
        [
            self.control_kind.code(),
            frame[1],
            version[0],
            version[1],
            sequence[0],
            sequence[1],
            sequence[2],
            sequence[3],
            frame[0],
            0,
            0,
            0,
        ]
    }

    /// Additional authenticated data covering kind, identity, sequence and declared length.
    #[must_use]
    pub fn aad(&self, direction: LanDirection, declared_plaintext_len: u32) -> Vec<u8> {
        let mut aad = Vec::new();
        push_field(&mut aad, CONTROL_AAD_LABEL);
        aad.extend_from_slice(&LAN_PROTOCOL_VERSION.to_be_bytes());
        aad.push(direction.wire_code());
        aad.extend_from_slice(&self.frame_kind.code().to_be_bytes());
        aad.push(self.control_kind.code());
        push_field(&mut aad, self.session_id.as_str().as_bytes());
        push_field(&mut aad, self.batch_id.as_bytes());
        aad.extend_from_slice(&self.sequence.to_be_bytes());
        aad.extend_from_slice(&declared_plaintext_len.to_be_bytes());
        aad
    }

    #[must_use]
    pub const fn session_id(&self) -> &LanSessionId {
        &self.session_id
    }

    #[must_use]
    pub fn batch_id(&self) -> &str {
        &self.batch_id
    }

    #[must_use]
    pub const fn frame_kind(&self) -> FrameKind {
        self.frame_kind
    }

    #[must_use]
    pub const fn control_kind(&self) -> SessionControlKind {
        self.control_kind
    }

    #[must_use]
    pub const fn sequence(&self) -> u32 {
        self.sequence
    }
}

/// Length marker for 32-byte session key expansion.
#[derive(Clone, Copy, Debug)]
struct SessionKeyLen;

impl KeyType for SessionKeyLen {
    fn len(&self) -> usize {
        SESSION_KEY_BYTES
    }
}

fn push_field(buffer: &mut Vec<u8>, field: &[u8]) {
    let length = u32::try_from(field.len()).unwrap_or(u32::MAX);
    buffer.extend_from_slice(&length.to_be_bytes());
    buffer.extend_from_slice(field);
}
