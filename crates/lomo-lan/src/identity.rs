//! Device identity, display names and the durable peer record.
//!
//! The device signing key is a non-exportable Android Keystore P-256 key. This crate never sees the
//! private key: it holds the public key and calls [`DeviceSigner::sign`] when it needs a signature
//! over a transcript it built itself.
//!
//! [`DeviceId`] is *derived* from the public key, so a peer cannot claim an identity that differs
//! from the key it authenticates with — identity and authentication cannot drift apart.

use aws_lc_rs::signature::{ECDSA_P256_SHA256_ASN1, ParsedPublicKey};
use sha2::{Digest, Sha256};

use crate::error::{authentication, resource_limit, validation};
use crate::limits::MAX_DISPLAY_NAME_BYTES;
use lomo_core::LomoError;

/// Length of an X9.62 uncompressed P-256 public key (`0x04 || X(32) || Y(32)`).
pub const DEVICE_PUBLIC_KEY_BYTES: usize = 65;

/// X9.62 uncompressed tag for an uncompressed elliptic-curve point.
const UNCOMPRESSED_POINT_TAG: u8 = 0x04;

/// A peer's device signing public key (P-256, X9.62 uncompressed).
///
/// Both the encoding **and** the curve point are validated at the boundary: a point that is not on
/// P-256 is rejected here, before any transcript is built or peer record stored.
#[derive(Clone, Debug, Eq, PartialEq, Hash)]
pub struct DevicePublicKey(Vec<u8>);

impl DevicePublicKey {
    /// Parses an X9.62 uncompressed P-256 public key.
    ///
    /// # Errors
    ///
    /// Validation when the length or point tag is wrong, or when the point is not on P-256.
    pub fn parse(bytes: &[u8]) -> Result<Self, LomoError> {
        if bytes.len() != DEVICE_PUBLIC_KEY_BYTES || bytes.first() != Some(&UNCOMPRESSED_POINT_TAG)
        {
            return Err(invalid_device_key());
        }
        ParsedPublicKey::new(&ECDSA_P256_SHA256_ASN1, bytes)
            .map_err(|_rejected| invalid_device_key())?;
        Ok(Self(bytes.to_vec()))
    }

    /// Verifies an ASN.1 DER ECDSA P-256 signature over `message` under this key.
    ///
    /// # Errors
    ///
    /// Authentication when the signature does not verify.
    pub fn verify(&self, message: &[u8], signature: &[u8], code: &str) -> Result<(), LomoError> {
        ParsedPublicKey::new(&ECDSA_P256_SHA256_ASN1, &self.0)
            .map_err(|_rejected| invalid_device_key())?
            .verify_sig(message, signature)
            .map_err(|_verification_error| {
                authentication(
                    code,
                    "signature does not verify under the claimed device key",
                )
            })
    }

    #[must_use]
    pub fn as_bytes(&self) -> &[u8] {
        &self.0
    }
}

fn invalid_device_key() -> LomoError {
    validation(
        "lan_device_key_invalid",
        "device public key must be a valid 65-byte X9.62 uncompressed P-256 point",
    )
}

/// Stable device identity derived from the device signing public key.
#[derive(Clone, Debug, Eq, PartialEq, Hash, Ord, PartialOrd)]
pub struct DeviceId(String);

impl DeviceId {
    /// Derives the device id as the lowercase hex SHA-256 of the public key bytes.
    #[must_use]
    pub fn derive(public_key: &DevicePublicKey) -> Self {
        let mut hasher = Sha256::new();
        hasher.update(b"lomo-lan-device-id-v2");
        hasher.update(public_key.as_bytes());
        Self(format!("{:x}", hasher.finalize()))
    }

    /// Parses a previously derived device id from durable state.
    ///
    /// # Errors
    ///
    /// Validation when the value is not 64 lowercase hex characters.
    pub fn parse(raw: &str) -> Result<Self, LomoError> {
        if raw.len() != 64 || !raw.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            return Err(validation(
                "lan_device_id_invalid",
                "device id must be 64 lowercase hex characters",
            ));
        }
        Ok(Self(raw.to_ascii_lowercase()))
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// A bounded, control-character-free peer display name.
#[derive(Clone, Debug, Eq, PartialEq, Hash)]
pub struct DisplayName(String);

impl DisplayName {
    /// Parses a display name.
    ///
    /// # Errors
    ///
    /// Validation when empty or containing control characters; resource-limit when above the
    /// byte ceiling.
    pub fn parse(raw: &str) -> Result<Self, LomoError> {
        if raw.is_empty() {
            return Err(validation(
                "lan_display_name_invalid",
                "peer display name must not be empty",
            ));
        }
        if raw.len() > MAX_DISPLAY_NAME_BYTES {
            return Err(resource_limit(
                "lan_display_name_too_long",
                "peer display name exceeds the 128-byte ceiling",
            ));
        }
        if raw.chars().any(char::is_control) {
            return Err(validation(
                "lan_display_name_invalid",
                "peer display name must not contain control characters",
            ));
        }
        Ok(Self(raw.to_owned()))
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// Port for the device signing key held outside this crate (Android Keystore in production).
///
/// The private key never crosses this boundary: the implementor signs bytes that `lomo-lan` built.
pub trait DeviceSigner {
    /// This device's signing public key.
    fn public_key(&self) -> &DevicePublicKey;

    /// Signs a transcript with the device key, returning an ASN.1 DER ECDSA P-256 signature.
    ///
    /// # Errors
    ///
    /// Authentication when the platform key is unavailable or the signature fails.
    fn sign(&self, transcript: &[u8]) -> Result<Vec<u8>, LomoError>;
}

/// A durable trusted-peer record.
///
/// Peer trust belongs to the device installation: it is never written into `.lomo`, never synced,
/// and never included in a workspace archive.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PeerRecord {
    device_id: DeviceId,
    public_key: DevicePublicKey,
    display_name: DisplayName,
    paired_at_ms: i64,
    revoked_at_ms: Option<i64>,
}

impl PeerRecord {
    /// Builds a freshly paired peer record.
    #[must_use]
    pub fn paired(
        public_key: DevicePublicKey,
        display_name: DisplayName,
        paired_at_ms: i64,
    ) -> Self {
        Self {
            device_id: DeviceId::derive(&public_key),
            public_key,
            display_name,
            paired_at_ms,
            revoked_at_ms: None,
        }
    }

    #[must_use]
    pub const fn device_id(&self) -> &DeviceId {
        &self.device_id
    }

    #[must_use]
    pub const fn public_key(&self) -> &DevicePublicKey {
        &self.public_key
    }

    #[must_use]
    pub const fn display_name(&self) -> &DisplayName {
        &self.display_name
    }

    #[must_use]
    pub const fn paired_at_ms(&self) -> i64 {
        self.paired_at_ms
    }

    #[must_use]
    pub const fn revoked_at_ms(&self) -> Option<i64> {
        self.revoked_at_ms
    }

    #[must_use]
    pub const fn is_revoked(&self) -> bool {
        self.revoked_at_ms.is_some()
    }

    /// Returns a revoked copy of this record.
    #[must_use]
    pub fn revoked(&self, revoked_at_ms: i64) -> Self {
        Self {
            revoked_at_ms: Some(revoked_at_ms),
            ..self.clone()
        }
    }

    /// Fails closed when the peer may not open a session.
    ///
    /// # Errors
    ///
    /// Authentication when the peer has been revoked.
    pub fn assert_connectable(&self) -> Result<(), LomoError> {
        if self.is_revoked() {
            return Err(authentication(
                "lan_peer_revoked",
                "peer is revoked and may not open a LAN session",
            ));
        }
        Ok(())
    }
}
