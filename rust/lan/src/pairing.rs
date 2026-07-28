//! Pairing transcript and short authentication code.
//!
//! Both endpoints build one canonical transcript over: protocol version, both device public keys,
//! both display names, both ephemeral X25519 public keys, and the agreed shared secret. Roles are
//! fixed (initiator / responder) so the byte layout is identical on both ends regardless of who
//! computed it.
//!
//! The short authentication code is derived from that transcript with HKDF-SHA256. Because an
//! in-path attacker must run two separate exchanges, it holds two different shared secrets and two
//! different ephemeral keys, so the two honest ends necessarily derive different codes. Users
//! comparing the codes detect the attacker; the code is never a secret and never authenticates
//! anything by itself.

use aws_lc_rs::hkdf::{HKDF_SHA256, KeyType, Salt};

use crate::error::validation;
use crate::identity::{DevicePublicKey, DisplayName, PeerRecord};
use crate::limits::PAIRING_CODE_DIGITS;
use lomo_core::LomoError;

/// Domain separation label for the pairing transcript.
const PAIRING_TRANSCRIPT_LABEL: &[u8] = b"lomo-lan-pair-v2";

/// Domain separation salt for the short authentication code.
const PAIRING_CODE_SALT: &[u8] = b"lomo-lan-pair-code-v2";

/// Expected X25519 public key length.
const EPHEMERAL_PUBLIC_KEY_BYTES: usize = 32;

/// Which side of the pairing exchange this endpoint played.
///
/// The role never enters the transcript bytes — both ends must serialize identically. It selects
/// which endpoint's material is local, which is what an endpoint actually needs to know when it
/// decides what to sign and what to verify.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PairingRole {
    Initiator,
    Responder,
}

impl PairingRole {
    /// Returns this endpoint's own value from an initiator/responder pair.
    #[must_use]
    pub const fn local<'a, T>(self, initiator: &'a T, responder: &'a T) -> &'a T {
        match self {
            Self::Initiator => initiator,
            Self::Responder => responder,
        }
    }

    /// Returns the peer's value from an initiator/responder pair.
    #[must_use]
    pub const fn peer<'a, T>(self, initiator: &'a T, responder: &'a T) -> &'a T {
        match self {
            Self::Initiator => responder,
            Self::Responder => initiator,
        }
    }
}

/// The canonical pairing transcript bytes shared by both endpoints.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PairingTranscript {
    bytes: Vec<u8>,
}

impl PairingTranscript {
    /// Builds the canonical transcript from both endpoints' identity and ephemeral material.
    ///
    /// The byte layout is always ordered initiator-then-responder, so both endpoints produce
    /// identical bytes regardless of which side computed them.
    ///
    /// # Errors
    ///
    /// Validation when an ephemeral public key or the shared secret has the wrong length.
    pub fn build(
        initiator_key: &DevicePublicKey,
        initiator_name: &DisplayName,
        initiator_ephemeral: &[u8],
        responder_key: &DevicePublicKey,
        responder_name: &DisplayName,
        responder_ephemeral: &[u8],
        shared_secret: &[u8],
    ) -> Result<Self, LomoError> {
        for ephemeral in [initiator_ephemeral, responder_ephemeral] {
            if ephemeral.len() != EPHEMERAL_PUBLIC_KEY_BYTES {
                return Err(validation(
                    "lan_pairing_ephemeral_invalid",
                    "ephemeral public key must be a 32-byte X25519 point",
                ));
            }
        }
        if shared_secret.is_empty() {
            return Err(validation(
                "lan_pairing_secret_invalid",
                "pairing shared secret must not be empty",
            ));
        }

        let mut bytes = Vec::new();
        push_field(&mut bytes, PAIRING_TRANSCRIPT_LABEL);
        bytes.extend_from_slice(&crate::frame::LAN_PROTOCOL_VERSION.to_be_bytes());
        push_field(&mut bytes, initiator_key.as_bytes());
        push_field(&mut bytes, initiator_name.as_str().as_bytes());
        push_field(&mut bytes, initiator_ephemeral);
        push_field(&mut bytes, responder_key.as_bytes());
        push_field(&mut bytes, responder_name.as_str().as_bytes());
        push_field(&mut bytes, responder_ephemeral);
        push_field(&mut bytes, shared_secret);
        Ok(Self { bytes })
    }

    /// The canonical transcript bytes that both endpoints sign and compare.
    #[must_use]
    pub fn bytes(&self) -> &[u8] {
        &self.bytes
    }
}

/// Derives the short authentication code both users compare during pairing.
#[must_use]
pub fn derive_pairing_code(transcript: &PairingTranscript) -> String {
    let prk = Salt::new(HKDF_SHA256, PAIRING_CODE_SALT).extract(transcript.bytes());
    let mut derived = [0_u8; 8];
    match prk
        .expand(&[b"short-code"], CodeLen)
        .and_then(|okm| okm.fill(&mut derived))
    {
        Ok(()) => {}
        // HKDF-SHA256 expansion to 8 bytes cannot fail for a valid PRK; a failure here must not
        // silently produce a predictable code, so fall back to transcript bytes that still differ
        // between endpoints rather than to a constant.
        Err(_expansion_error) => {
            let digest = <sha2::Sha256 as sha2::Digest>::digest(transcript.bytes());
            derived.copy_from_slice(digest.get(0..8).unwrap_or(&[0; 8]));
        }
    }
    let modulus = 10_u64.pow(u32::try_from(PAIRING_CODE_DIGITS).unwrap_or(6));
    let value = u64::from_be_bytes(derived) % modulus;
    let width = PAIRING_CODE_DIGITS;
    format!("{value:0width$}")
}

/// Verifies a peer's pairing confirmation signature and returns the peer record to store.
///
/// The signature must be over **this endpoint's** transcript bytes and must verify under the peer's
/// claimed device key, so a substituted key or a signature over another transcript both fail and
/// no peer is stored.
///
/// # Errors
///
/// Authentication when the signature does not verify.
pub fn verify_pairing_confirmation(
    transcript: &PairingTranscript,
    peer_key: &DevicePublicKey,
    peer_name: &DisplayName,
    signature: &[u8],
    paired_at_ms: i64,
) -> Result<PeerRecord, LomoError> {
    peer_key.verify(
        transcript.bytes(),
        signature,
        "lan_pairing_signature_invalid",
    )?;
    Ok(PeerRecord::paired(
        peer_key.clone(),
        peer_name.clone(),
        paired_at_ms,
    ))
}

/// Length marker for the eight-byte short-code expansion.
#[derive(Clone, Copy, Debug)]
struct CodeLen;

impl KeyType for CodeLen {
    fn len(&self) -> usize {
        8
    }
}

/// Appends a length-prefixed field so no two distinct field sets can serialize identically.
fn push_field(buffer: &mut Vec<u8>, field: &[u8]) {
    let length = u32::try_from(field.len()).unwrap_or(u32::MAX);
    buffer.extend_from_slice(&length.to_be_bytes());
    buffer.extend_from_slice(field);
}
