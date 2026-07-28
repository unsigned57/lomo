//! Session authentication, key derivation, chunk AEAD and the replay ledger.
//!
//! Each connection derives a fresh session key from an ephemeral X25519 agreement bound to a
//! session transcript, and both endpoints authenticate with their device signing keys over that
//! same transcript.
//!
//! Every chunk is sealed with ChaCha20-Poly1305 under:
//!
//! - a **nonce** derived from `(item index, attachment slot, chunk index)`, unique within the
//!   session because the key is per-session, and
//! - **AAD** covering session id, batch id, item index, attachment slot and chunk index,
//!
//! so a chunk cannot be replayed into a different position, item, attachment, batch or session.

use std::collections::BTreeSet;

use aws_lc_rs::aead::{Aad, CHACHA20_POLY1305, LessSafeKey, Nonce, UnboundKey};
use aws_lc_rs::hkdf::{HKDF_SHA256, KeyType, Salt};

use crate::error::{authentication, resource_limit, validation};
use crate::identity::DevicePublicKey;
use crate::limits::MAX_SEALED_CHUNK_PAYLOAD_BYTES;
use lomo_core::LomoError;

/// Domain separation label for the session transcript.
const SESSION_TRANSCRIPT_LABEL: &[u8] = b"lomo-lan-session-v2";

/// Domain separation salt for session key derivation.
const SESSION_KEY_SALT: &[u8] = b"lomo-lan-session-key-v2";

/// Domain separation prefix for chunk additional authenticated data.
const CHUNK_AAD_LABEL: &[u8] = b"lomo-lan-chunk-v2";

/// Expected X25519 public key length.
const EPHEMERAL_PUBLIC_KEY_BYTES: usize = 32;

/// ChaCha20-Poly1305 key length.
const SESSION_KEY_BYTES: usize = 32;

/// ChaCha20-Poly1305 nonce length.
const NONCE_BYTES: usize = 12;

/// Attachment slot reserved for the memo body itself (attachments use `0..=0xFFFE`).
pub const ATTACHMENT_SLOT_BODY: u16 = 0xFFFF;

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
        bytes.extend_from_slice(&crate::frame::LAN_PROTOCOL_VERSION.to_be_bytes());
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

    /// Test/diagnostic view used only to compare two endpoints' derivations.
    ///
    /// Callers must not transport or persist this value.
    #[must_use]
    pub const fn as_bytes(&self) -> &[u8; SESSION_KEY_BYTES] {
        &self.bytes
    }

    /// Seals one chunk, returning ciphertext with the appended authentication tag.
    ///
    /// # Errors
    ///
    /// Resource-limit when the sealed payload would exceed the wire ceiling; authentication when
    /// the AEAD operation fails.
    pub fn seal_chunk(
        &self,
        binding: &ChunkBinding,
        mut plaintext: Vec<u8>,
    ) -> Result<Vec<u8>, LomoError> {
        if plaintext
            .len()
            .saturating_add(crate::limits::AEAD_TAG_BYTES)
            > MAX_SEALED_CHUNK_PAYLOAD_BYTES
        {
            return Err(resource_limit(
                "lan_chunk_too_large",
                "sealed chunk would exceed the wire chunk ceiling",
            ));
        }
        self.aead_key()?
            .seal_in_place_append_tag(
                Nonce::assume_unique_for_key(binding.nonce()),
                Aad::from(binding.aad()),
                &mut plaintext,
            )
            .map_err(|_seal_error| {
                authentication("lan_chunk_seal_failed", "chunk could not be sealed")
            })?;
        Ok(plaintext)
    }

    /// Opens one sealed chunk under the same binding.
    ///
    /// # Errors
    ///
    /// Authentication when the binding differs, the tag fails, or the payload was tampered with.
    pub fn open_chunk(
        &self,
        binding: &ChunkBinding,
        mut sealed: Vec<u8>,
    ) -> Result<Vec<u8>, LomoError> {
        let opened_len = self
            .aead_key()?
            .open_in_place(
                Nonce::assume_unique_for_key(binding.nonce()),
                Aad::from(binding.aad()),
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

    fn aead_key(&self) -> Result<LessSafeKey, LomoError> {
        UnboundKey::new(&CHACHA20_POLY1305, &self.bytes)
            .map(LessSafeKey::new)
            .map_err(|_key_error| {
                authentication(
                    "lan_session_key_invalid",
                    "derived session key is not a valid ChaCha20-Poly1305 key",
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

    /// Deterministic per-chunk nonce, unique within one session key.
    #[must_use]
    pub const fn nonce(&self) -> [u8; NONCE_BYTES] {
        let item = self.item_index.to_be_bytes();
        let slot = self.attachment_slot.to_be_bytes();
        let chunk = self.chunk_index.to_be_bytes();
        [
            item[0], item[1], slot[0], slot[1], chunk[0], chunk[1], chunk[2], chunk[3], 0, 0, 0, 0,
        ]
    }

    /// Additional authenticated data binding the chunk to its exact position.
    #[must_use]
    pub fn aad(&self) -> Vec<u8> {
        let mut aad = Vec::new();
        push_field(&mut aad, CHUNK_AAD_LABEL);
        push_field(&mut aad, self.session_id.as_str().as_bytes());
        push_field(&mut aad, self.batch_id.as_bytes());
        aad.extend_from_slice(&self.item_index.to_be_bytes());
        aad.extend_from_slice(&self.attachment_slot.to_be_bytes());
        aad.extend_from_slice(&self.chunk_index.to_be_bytes());
        aad
    }

    #[must_use]
    pub const fn chunk_index(&self) -> u32 {
        self.chunk_index
    }
}

/// Durable replay protection for session ids and confirmed chunks.
///
/// Confirmed chunks double as the resume ledger: a recovering transfer retransmits only chunks that
/// are **not** already confirmed.
#[derive(Clone, Debug, Default)]
pub struct ReplayLedger {
    sessions: BTreeSet<LanSessionId>,
    chunks: BTreeSet<ChunkBinding>,
}

impl ReplayLedger {
    /// Accepts a session id exactly once.
    ///
    /// # Errors
    ///
    /// Authentication when the session id was already accepted.
    pub fn accept_session(&mut self, session_id: &LanSessionId) -> Result<(), LomoError> {
        if !self.sessions.insert(session_id.clone()) {
            return Err(authentication(
                "lan_session_replayed",
                "session id was already used and may not be replayed",
            ));
        }
        Ok(())
    }

    /// Confirms one chunk exactly once.
    ///
    /// # Errors
    ///
    /// Authentication when the chunk was already confirmed.
    pub fn confirm_chunk(&mut self, binding: &ChunkBinding) -> Result<(), LomoError> {
        if !self.chunks.insert(binding.clone()) {
            return Err(authentication(
                "lan_chunk_replayed",
                "chunk was already confirmed and may not be replayed",
            ));
        }
        Ok(())
    }

    /// True when the chunk is already confirmed (resume skips it).
    #[must_use]
    pub fn is_chunk_confirmed(&self, binding: &ChunkBinding) -> bool {
        self.chunks.contains(binding)
    }

    /// Count of confirmed chunks.
    #[must_use]
    pub fn confirmed_chunk_count(&self) -> usize {
        self.chunks.len()
    }

    /// True when the session id has been accepted.
    #[must_use]
    pub fn is_session_accepted(&self, session_id: &LanSessionId) -> bool {
        self.sessions.contains(session_id)
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
