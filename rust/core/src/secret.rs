//! Ephemeral native secret vault and typed lease tokens (stage-5 P5-02).
//!
//! Invariant: credentials exist only as process-local leases. Journals, diagnostics, and
//! `WorkManager` inputs never hold plaintext secrets — only opaque lease identifiers.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};

use crate::LomoError;

/// Opaque lease identifier stored in durable job records (never the secret bytes).
#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
pub struct SecretLeaseId(String);

impl SecretLeaseId {
    /// Parses a lease id token.
    ///
    /// # Errors
    ///
    /// Validation when empty, oversized, or outside the protocol identifier alphabet.
    pub fn parse(raw: &str) -> Result<Self, LomoError> {
        crate::JobId::parse(raw)
            .map(|id| Self(id.as_str().to_owned()))
            .map_err(|_error| {
                LomoError::validation(
                    "invalid_secret_lease_id",
                    "secret lease id must be a 1..=128 protocol identifier",
                )
            })
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// Process-local secret material that must never be serialized into journals.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SecretMaterial {
    bytes: Vec<u8>,
}

impl SecretMaterial {
    /// Creates secret material from raw bytes (caller owns wipe of external buffers).
    #[must_use]
    pub fn from_bytes(bytes: impl Into<Vec<u8>>) -> Self {
        Self {
            bytes: bytes.into(),
        }
    }

    #[must_use]
    pub const fn as_bytes(&self) -> &[u8] {
        self.bytes.as_slice()
    }

    #[must_use]
    pub const fn len(&self) -> usize {
        self.bytes.len()
    }

    #[must_use]
    pub const fn is_empty(&self) -> bool {
        self.bytes.is_empty()
    }
}

impl Drop for SecretMaterial {
    fn drop(&mut self) {
        // Best-effort wipe of process-local secret bytes on drop.
        for byte in &mut self.bytes {
            *byte = 0;
        }
    }
}

#[derive(Debug)]
struct LeaseEntry {
    material: SecretMaterial,
    expires_at: Instant,
}

/// In-process ephemeral vault. Not durable across process death.
#[derive(Debug, Default)]
pub struct EphemeralSecretVault {
    next_id: AtomicU64,
    entries: Mutex<HashMap<String, LeaseEntry>>,
}

impl EphemeralSecretVault {
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Inserts secret material and returns a typed lease with the given TTL.
    ///
    /// # Errors
    ///
    /// Resource limit when the vault already holds too many leases.
    pub fn put(
        &self,
        material: SecretMaterial,
        ttl: Duration,
        _job_id: Option<&str>,
    ) -> Result<SecretLeaseId, LomoError> {
        const MAX_LEASES: usize = 64;
        let id = {
            let mut entries = self.entries.lock().map_err(|_poison| {
                LomoError::internal(
                    "secret_vault_lock_poisoned",
                    "ephemeral secret vault mutex is poisoned",
                )
            })?;
            if entries.len() >= MAX_LEASES {
                return Err(LomoError::resource_limit(
                    "secret_lease_limit_exceeded",
                    "ephemeral secret vault supports at most 64 concurrent leases",
                ));
            }
            let counter = self.next_id.fetch_add(1, Ordering::Relaxed);
            let raw = format!("lease-{counter}");
            let id = SecretLeaseId::parse(&raw)?;
            entries.insert(
                id.as_str().to_owned(),
                LeaseEntry {
                    material,
                    expires_at: Instant::now() + ttl,
                },
            );
            id
        };
        Ok(id)
    }

    /// Resolves a lease if present and not expired.
    ///
    /// # Errors
    ///
    /// - `secret_lease_missing` when the id is unknown
    /// - `secret_lease_expired` when the TTL has elapsed (entry is removed)
    pub fn resolve(&self, lease_id: &SecretLeaseId) -> Result<SecretMaterial, LomoError> {
        let mut entries = self.entries.lock().map_err(|_poison| {
            LomoError::internal(
                "secret_vault_lock_poisoned",
                "ephemeral secret vault mutex is poisoned",
            )
        })?;
        let Some(entry) = entries.get(lease_id.as_str()) else {
            return Err(LomoError::validation(
                "secret_lease_missing",
                "secret lease is missing; process death or never issued",
            ));
        };
        if Instant::now() >= entry.expires_at {
            entries.remove(lease_id.as_str());
            return Err(LomoError::validation(
                "secret_lease_expired",
                "secret lease TTL elapsed; re-issue credentials for a new lease",
            ));
        }
        let material = entry.material.clone();
        drop(entries);
        Ok(material)
    }

    /// Revokes a lease (best-effort wipe via drop).
    pub fn revoke(&self, lease_id: &SecretLeaseId) {
        if let Ok(mut entries) = self.entries.lock() {
            entries.remove(lease_id.as_str());
        }
    }

    /// Drops all expired leases.
    pub fn purge_expired(&self) {
        if let Ok(mut entries) = self.entries.lock() {
            let now = Instant::now();
            entries.retain(|_id, entry| entry.expires_at > now);
        }
    }

    #[must_use]
    pub fn active_count(&self) -> usize {
        self.entries.lock().map_or(0, |entries| entries.len())
    }
}

/// Shared vault handle for engine and external workers.
pub type SharedSecretVault = Arc<EphemeralSecretVault>;
