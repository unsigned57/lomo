//! Workspace and remote identity types (stage-5 P5-01).
//!
//! `WorkspaceGenerationId` is real random durable under `.lomo/local/v1/generation.rec`, never
//! synced or archived. Archive activation must mint a new value (caller responsibility).

use std::fs::File;
use std::io::Read;
use std::path::Path;

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::limits::{corruption, storage, validation};
use crate::lomo_record::{
    LomoPaths, LomoPayload, LomoRecordKind, hex_encode, read_record, write_record_atomic,
};

/// Byte length of a workspace generation id (256-bit).
pub const WORKSPACE_GENERATION_ID_BYTES: usize = 32;

/// Real random durable workspace generation fence.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct WorkspaceGenerationId(String);

impl WorkspaceGenerationId {
    /// Parses a lowercase hex generation id (64 hex chars).
    ///
    /// # Errors
    ///
    /// Validation when length/charset is wrong.
    pub fn parse(raw: &str) -> Result<Self, lomo_core::LomoError> {
        if raw.len() != WORKSPACE_GENERATION_ID_BYTES * 2
            || !raw.bytes().all(|b| b.is_ascii_hexdigit())
        {
            return Err(validation(
                "invalid_workspace_generation_id",
                "WorkspaceGenerationId must be 64 lowercase hex characters",
            ));
        }
        Ok(Self(raw.to_ascii_lowercase()))
    }

    /// Returns the hex string form.
    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }

    /// Mints a new cryptographically random generation id (does not persist).
    ///
    /// # Errors
    ///
    /// Storage when the host CSPRNG cannot be read.
    pub fn mint() -> Result<Self, lomo_core::LomoError> {
        let mut bytes = [0_u8; WORKSPACE_GENERATION_ID_BYTES];
        fill_csprng(&mut bytes)?;
        Ok(Self(hex_encode(&bytes)))
    }
}

/// Opaque remote dataset identity (permanent tombstone binding).
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct RemoteDatasetId(String);

impl RemoteDatasetId {
    /// Parses a non-empty dataset id (≤ 128 chars, printable ASCII without controls).
    ///
    /// # Errors
    ///
    /// Validation on empty/oversized/control characters.
    pub fn parse(raw: &str) -> Result<Self, lomo_core::LomoError> {
        if raw.is_empty() || raw.len() > 128 || raw.chars().any(char::is_control) {
            return Err(validation(
                "invalid_remote_dataset_id",
                "RemoteDatasetId must be 1..=128 non-control characters",
            ));
        }
        Ok(Self(raw.to_owned()))
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// Canonical digest of remote configuration that participates in the generation fence.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct RemoteIdentityDigest(String);

impl RemoteIdentityDigest {
    /// Parses a 64-char lowercase hex SHA-256 digest.
    ///
    /// # Errors
    ///
    /// Validation when length/charset is wrong.
    pub fn parse(raw: &str) -> Result<Self, lomo_core::LomoError> {
        if raw.len() != 64 || !raw.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(validation(
                "invalid_remote_identity_digest",
                "RemoteIdentityDigest must be 64 lowercase hex characters",
            ));
        }
        Ok(Self(raw.to_ascii_lowercase()))
    }

    /// Computes the canonical config digest from stable UTF-8 config bytes.
    #[must_use]
    pub fn from_canonical_config_bytes(canonical_utf8: &[u8]) -> Self {
        let digest = Sha256::digest(canonical_utf8);
        Self(hex_encode(&digest[..]))
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// Loads the durable generation id, or mints and persists one if missing.
///
/// # Errors
///
/// Corruption on malformed existing records; storage on I/O failure.
pub fn load_or_mint_workspace_generation(
    workspace_root: &Path,
) -> Result<WorkspaceGenerationId, lomo_core::LomoError> {
    let path = LomoPaths::generation_record_path(workspace_root);
    if path.exists() {
        return load_workspace_generation(workspace_root);
    }
    let id = WorkspaceGenerationId::mint()?;
    persist_workspace_generation(workspace_root, &id)?;
    Ok(id)
}

/// Loads an existing durable generation id (fail-closed; never clean-slates).
///
/// # Errors
///
/// - Missing file → validation `workspace_generation_missing`
/// - Corrupt bytes → corruption (not empty default)
pub fn load_workspace_generation(
    workspace_root: &Path,
) -> Result<WorkspaceGenerationId, lomo_core::LomoError> {
    let path = LomoPaths::generation_record_path(workspace_root);
    if !path.exists() {
        return Err(validation(
            "workspace_generation_missing",
            "workspace generation.rec is missing",
        ));
    }
    // Preserve codec corruption codes; never clean-slate on malformed bytes.
    let record = read_record(&path)?;
    if record.payload.kind != LomoRecordKind::Generation {
        return Err(corruption(
            "workspace_generation_kind_mismatch",
            "generation.rec does not contain a Generation record",
        ));
    }
    let body: GenerationBody = serde_json::from_str(&record.payload.body_json).map_err(|err| {
        corruption(
            "workspace_generation_payload_invalid",
            &format!("cannot decode generation body: {err}"),
        )
    })?;
    WorkspaceGenerationId::parse(&body.generation_id).map_err(|_parse| {
        corruption(
            "workspace_generation_payload_invalid",
            "generation body id is not a valid WorkspaceGenerationId",
        )
    })
}

/// Persists a generation id under `.lomo/local/v1/generation.rec` (atomic).
///
/// # Errors
///
/// Storage/encode failures.
pub fn persist_workspace_generation(
    workspace_root: &Path,
    id: &WorkspaceGenerationId,
) -> Result<(), lomo_core::LomoError> {
    let paths = LomoPaths::for_workspace(workspace_root);
    paths.ensure_layout()?;
    let path = LomoPaths::generation_record_path(workspace_root);
    let body = GenerationBody {
        generation_id: id.as_str().to_owned(),
    };
    let body_json = serde_json::to_string(&body).map_err(|err| {
        validation(
            "generation_encode_failed",
            &format!("cannot encode generation body: {err}"),
        )
    })?;
    write_record_atomic(
        &path,
        &LomoPayload {
            kind: LomoRecordKind::Generation,
            record_id: "generation".into(),
            body_json,
        },
    )
}

/// Mints a **new** generation and overwrites the durable record (archive activation).
///
/// # Errors
///
/// Storage/CSPRNG failures.
pub fn mint_new_workspace_generation(
    workspace_root: &Path,
) -> Result<WorkspaceGenerationId, lomo_core::LomoError> {
    let id = WorkspaceGenerationId::mint()?;
    persist_workspace_generation(workspace_root, &id)?;
    Ok(id)
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
struct GenerationBody {
    generation_id: String,
}

fn fill_csprng(buf: &mut [u8]) -> Result<(), lomo_core::LomoError> {
    // Host + Android both expose /dev/urandom; avoids a new production crate dependency.
    let mut file = File::open("/dev/urandom").map_err(|err| {
        storage(
            "csprng_open_failed",
            &format!("cannot open /dev/urandom: {err}"),
        )
    })?;
    file.read_exact(buf).map_err(|err| {
        storage(
            "csprng_read_failed",
            &format!("cannot read /dev/urandom: {err}"),
        )
    })?;
    Ok(())
}
