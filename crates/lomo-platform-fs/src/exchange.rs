use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};

use lomo_core::{ExchangeArtifact, ExchangeToken, LomoError, Sha256Digest};
use sha2::{Digest, Sha256};

use crate::error::{storage, validation};

/// Private application exchange storage used to buffer files across execution boundaries.
#[derive(Debug, Clone)]
pub struct ExchangeDirectory {
    exchange_dir: PathBuf,
}

impl ExchangeDirectory {
    /// Creates or opens an exchange directory.
    ///
    /// # Errors
    ///
    /// Returns storage error if the directory cannot be created.
    pub fn new(exchange_dir: impl AsRef<Path>) -> Result<Self, LomoError> {
        let exchange_dir = exchange_dir.as_ref().to_path_buf();
        fs::create_dir_all(&exchange_dir).map_err(|err| {
            storage(
                "exchange_dir_unavailable",
                &format!(
                    "failed to create exchange directory '{}': {err}",
                    exchange_dir.display()
                ),
            )
        })?;
        Ok(Self { exchange_dir })
    }

    #[must_use]
    pub fn path(&self) -> &Path {
        &self.exchange_dir
    }

    /// Resolves an exchange token to its file path in the exchange directory.
    #[must_use]
    pub fn file_path(&self, token: &ExchangeToken) -> PathBuf {
        self.exchange_dir.join(token.as_str())
    }

    /// Atomically writes content into the exchange directory under `token`,
    /// syncing to disk, and computes verifiable artifact evidence.
    ///
    /// # Errors
    ///
    /// Returns storage error on I/O failure or validation error if artifact validation fails.
    pub fn write_content(
        &self,
        token: &ExchangeToken,
        content: &[u8],
    ) -> Result<ExchangeArtifact, LomoError> {
        let target_file = self.file_path(token);
        let nonce = generate_random_nonce()?;
        let temp_file = self
            .exchange_dir
            .join(format!(".tmp.{}.{nonce}", token.as_str()));

        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temp_file)
            .map_err(|err| {
                storage(
                    "exchange_temp_open_failed",
                    &format!("failed to create temp exchange file: {err}"),
                )
            })?;

        if let Err(err) = file.write_all(content) {
            let msg = remove_temp_file_on_error(
                &temp_file,
                &format!("failed to write exchange bytes: {err}"),
            );
            return Err(storage("exchange_write_failed", &msg));
        }

        if let Err(err) = file.sync_all() {
            let msg = remove_temp_file_on_error(
                &temp_file,
                &format!("failed to fsync temp exchange file: {err}"),
            );
            return Err(storage("exchange_fsync_failed", &msg));
        }
        drop(file);

        if let Err(err) = fs::rename(&temp_file, &target_file) {
            let msg = remove_temp_file_on_error(
                &temp_file,
                &format!("failed to commit exchange file: {err}"),
            );
            return Err(storage("exchange_rename_failed", &msg));
        }

        let parent_dir = fs::File::open(&self.exchange_dir).map_err(|err| {
            storage(
                "exchange_dir_sync_open_failed",
                &format!("failed to open exchange dir for sync: {err}"),
            )
        })?;
        parent_dir.sync_all().map_err(|err| {
            storage(
                "exchange_dir_sync_failed",
                &format!("failed to sync exchange dir: {err}"),
            )
        })?;

        let length = u64::try_from(content.len()).map_err(|err| {
            storage(
                "file_length_overflow",
                &format!("exchange content length exceeds u64: {err}"),
            )
        })?;
        let digest = compute_sha256(content)?;
        ExchangeArtifact::new(token.as_str(), length, digest)
    }

    /// Reads and digests an exchange artifact.
    ///
    /// # Errors
    ///
    /// Returns storage error if missing, or validation error on mismatch.
    pub fn read_artifact(&self, artifact: &ExchangeArtifact) -> Result<Vec<u8>, LomoError> {
        let path = self.file_path(artifact.token());
        let buffer = fs::read(&path).map_err(|err| {
            if err.kind() == std::io::ErrorKind::NotFound {
                storage(
                    "exchange_artifact_missing",
                    &format!("exchange artifact '{}' does not exist", path.display()),
                )
            } else {
                storage(
                    "exchange_read_failed",
                    &format!(
                        "failed to read exchange artifact '{}': {err}",
                        path.display()
                    ),
                )
            }
        })?;

        if buffer.len() as u64 != artifact.length() {
            return Err(validation(
                "exchange_artifact_mismatch",
                &format!(
                    "exchange artifact length mismatch: expected {}, got {}",
                    artifact.length(),
                    buffer.len()
                ),
            ));
        }

        let actual_digest = compute_sha256(&buffer)?;
        if actual_digest != *artifact.digest() {
            return Err(validation(
                "exchange_artifact_mismatch",
                &format!(
                    "exchange artifact digest mismatch: expected {}, got {}",
                    artifact.digest().as_str(),
                    actual_digest.as_str()
                ),
            ));
        }

        Ok(buffer)
    }
}

/// Generates a cryptographically random hexadecimal nonce without timestamp reliance.
///
/// # Errors
fn remove_temp_file_on_error(temp_file: &Path, original_err: &str) -> String {
    match fs::remove_file(temp_file) {
        Ok(()) => original_err.to_owned(),
        Err(cleanup_err) => format!("{original_err} (cleanup failed: {cleanup_err})"),
    }
}

/// Generates a high-entropy 16-byte random hex nonce.
///
/// # Errors
///
/// Returns storage error if random bytes cannot be obtained from the system.
pub fn generate_random_nonce() -> Result<String, LomoError> {
    let mut bytes = [0u8; 16];
    getrandom::fill(&mut bytes)
        .map_err(|error| storage("random_nonce_failed", &error.to_string()))?;
    Ok(format!("{:032x}", u128::from_be_bytes(bytes)))
}

pub fn compute_sha256(data: &[u8]) -> Result<Sha256Digest, LomoError> {
    let mut hasher = Sha256::new();
    hasher.update(data);
    let hex = format!("{:x}", hasher.finalize());
    Sha256Digest::parse(&hex)
}
