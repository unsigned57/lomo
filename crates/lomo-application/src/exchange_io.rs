use std::path::Path;

use lomo_core::{ErrorCategory, ExchangeArtifact, ExchangeToken, LomoError, Sha256Digest};
use sha2::{Digest, Sha256};

use crate::{
    csprng::generate_hex_token,
    error::{corruption, resource_limit, storage},
    private_io::{remove_durable, write_atomic},
    resource::{MAX_FILE_BYTES, read_bounded},
    sysfs::open_read_nofollow,
};

/// Stages a bounded file into exchange with durable atomic publication.
///
/// # Errors
/// Rejects oversized data and surfaces all write/fsync failures.
pub fn stage_content(exchange_dir: &Path, content: &[u8]) -> Result<ExchangeArtifact, LomoError> {
    let length = u64::try_from(content.len())
        .map_err(|error| resource_limit("exchange_content_overflow", error.to_string()))?;
    if length > MAX_FILE_BYTES {
        return Err(resource_limit(
            "exchange_content_too_large",
            "exchange content exceeds the per-file budget",
        ));
    }
    let token = ExchangeToken::parse(&format!("ex_{}", generate_hex_token(16)?))?;
    write_atomic(&exchange_dir.join(token.as_str()), content)?;
    ExchangeArtifact::new(
        token.as_str(),
        length,
        Sha256Digest::parse(&format!("{:x}", Sha256::digest(content)))?,
    )
}

/// Reads from one descriptor after bounding its size; verifies evidence before reclamation.
///
/// # Errors
/// Invalid/oversized artifacts remain available as evidence. Cleanup errors are observable.
pub fn read_artifact_content(
    exchange_dir: &Path,
    artifact: &ExchangeArtifact,
) -> Result<Vec<u8>, LomoError> {
    if artifact.length() > MAX_FILE_BYTES {
        return Err(resource_limit(
            "exchange_content_too_large",
            "artifact declaration exceeds the per-file budget",
        ));
    }
    let path = exchange_dir.join(artifact.token().as_str());
    let file = open_read_nofollow(&path).map_err(|error| {
        if error.category() == ErrorCategory::Permission {
            error
        } else {
            storage("exchange_artifact_read_failed", error.to_string())
        }
    })?;
    let length = file
        .metadata()
        .map_err(|error| storage("exchange_artifact_stat_failed", error.to_string()))?
        .len();
    if length > MAX_FILE_BYTES {
        return Err(resource_limit(
            "exchange_content_too_large",
            "artifact file exceeds the per-file budget",
        ));
    }
    if length != artifact.length() {
        return Err(corruption(
            "exchange_artifact_length_mismatch",
            "artifact metadata differs from its declared length",
        ));
    }
    let bytes = read_bounded(file, artifact.length())?;
    if u64::try_from(bytes.len())
        .map_err(|error| resource_limit("exchange_content_overflow", error.to_string()))?
        != artifact.length()
    {
        return Err(corruption(
            "exchange_artifact_length_mismatch",
            "artifact length changed while reading",
        ));
    }
    if format!("{:x}", Sha256::digest(&bytes)) != artifact.digest().as_str() {
        return Err(corruption(
            "exchange_artifact_digest_mismatch",
            "artifact bytes fail their SHA-256 checksum",
        ));
    }
    remove_durable(&path)?;
    Ok(bytes)
}
