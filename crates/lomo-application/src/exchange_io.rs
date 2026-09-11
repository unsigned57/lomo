use std::fs::{OpenOptions, create_dir_all, read, remove_file, rename};
use std::io::Write;
use std::path::Path;

use lomo_core::{ExchangeArtifact, ExchangeToken, LomoError, Sha256Digest};
use sha2::{Digest, Sha256};

use crate::csprng::generate_hex_token;
use crate::error::{corruption, storage, validation};

/// Stages bytes into the exchange directory with fsync and returns an `ExchangeArtifact`.
///
/// # Errors
/// Returns `Storage` or `Validation` errors if staging or hashing fails.
pub fn stage_content(exchange_dir: &Path, content: &[u8]) -> Result<ExchangeArtifact, LomoError> {
    create_dir_all(exchange_dir).map_err(|err| {
        storage(
            "exchange_dir_unavailable",
            format!("failed to create exchange directory: {err}"),
        )
    })?;

    let nonce = generate_hex_token(16)?;
    let token_str = format!("ex_{nonce}");
    let token = ExchangeToken::parse(&token_str)?;

    let target_path = exchange_dir.join(&token_str);
    let temp_path = exchange_dir.join(format!(".tmp.{token_str}"));

    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temp_path)
        .map_err(|err| {
            storage(
                "exchange_temp_open_failed",
                format!("failed to open temp exchange file: {err}"),
            )
        })?;

    file.write_all(content).map_err(|err| {
        drop(remove_file(&temp_path));
        storage(
            "exchange_temp_write_failed",
            format!("failed to write temp exchange file: {err}"),
        )
    })?;

    file.sync_all().map_err(|err| {
        drop(remove_file(&temp_path));
        storage(
            "exchange_temp_fsync_failed",
            format!("failed to fsync temp exchange file: {err}"),
        )
    })?;

    drop(file);

    rename(&temp_path, &target_path).map_err(|err| {
        drop(remove_file(&temp_path));
        storage(
            "exchange_temp_rename_failed",
            format!("failed to rename temp exchange file: {err}"),
        )
    })?;

    let digest_bytes = Sha256::digest(content);
    let digest_hex = format!("{digest_bytes:x}");
    let digest = Sha256Digest::parse(&digest_hex)?;
    let length = u64::try_from(content.len())
        .map_err(|_overflow| validation("exchange_content_overflow", "content size exceeds u64"))?;

    ExchangeArtifact::new(token.as_str(), length, digest)
}

/// Reads artifact bytes from exchange, verifying expected length and SHA-256 digest,
/// and reclaims the artifact file from exchange.
///
/// # Errors
/// Returns `Storage` or `Corruption` errors if reading or verification fails.
pub fn read_artifact_content(
    exchange_dir: &Path,
    artifact: &ExchangeArtifact,
) -> Result<Vec<u8>, LomoError> {
    let path = exchange_dir.join(artifact.token().as_str());
    if !path.exists() {
        return Err(storage(
            "exchange_artifact_not_found",
            format!("exchange artifact '{}' not found", path.display()),
        ));
    }

    let bytes = read(&path).map_err(|err| {
        storage(
            "exchange_artifact_read_failed",
            format!(
                "failed to read exchange artifact '{}': {err}",
                path.display()
            ),
        )
    })?;

    let len_u64 = u64::try_from(bytes.len())
        .map_err(|_overflow| validation("exchange_content_overflow", "content size exceeds u64"))?;

    if len_u64 != artifact.length() {
        drop(remove_file(&path));
        return Err(corruption(
            "exchange_artifact_length_mismatch",
            format!(
                "exchange artifact length mismatch: expected {}, observed {}",
                artifact.length(),
                len_u64
            ),
        ));
    }

    let digest_bytes = Sha256::digest(&bytes);
    let digest_hex = format!("{digest_bytes:x}");

    if digest_hex != artifact.digest().as_str() {
        drop(remove_file(&path));
        return Err(corruption(
            "exchange_artifact_digest_mismatch",
            format!(
                "exchange artifact digest mismatch: expected {}, observed {}",
                artifact.digest().as_str(),
                digest_hex
            ),
        ));
    }

    // Clean up temporary exchange artifact
    drop(remove_file(&path));

    Ok(bytes)
}
